# 하이브리드 RAG 검색 & AI 큐레이션 명세

영화([`MovieRecommendService.java`](../../src/main/java/com/t/tcine/domain/movie/service/MovieRecommendService.java))와 시리즈([`TvRecommendService.java`](../../src/main/java/com/t/tcine/domain/tv/service/TvRecommendService.java)) 추천 엔진의 핵심 동작 원리와 유지보수 시 절대 깨뜨리면 안 되는 규칙을 정리한 문서입니다.

---

## 1. 검색 파이프라인 (Hybrid Search + Re-ranking + LLM RAG)

1. **Qdrant 인메모리 코퍼스 캐시 (`cachedCorpus`)**
   - Qdrant 컬렉션(`tcine-movies-openai` 약 7,370편, `tcine-tv-openai` 약 4,260편) 전체 문서를 인메모리에 15분간 캐시(`warmUpCorpus()`로 기동 직후 예열, 색인 건수 변경 시 자동 갱신).
   - 매 검색마다 Qdrant `scrollAsync`를 호출하던 병목(5~6초)을 제거하여 키워드 매칭을 `< 5ms`에 수행하고, 전체 1단계 검색(OpenAI 임베딩 + Qdrant 벡터 검색 병렬 실행)을 **135~270ms** 내에 완료함.
2. **병렬 하이브리드 검색 & RRF 결합**
   - `keywordFuture`: 인메모리 코퍼스 대상 BM25 스타일 필드 가중치 점수 계산 (제목 65~100점, 감독/제작 80점, 배우/OTT 75점, 키워드 45점, 장르 35점, 줄거리 15점).
   - `vectorFuture`: OpenAI `text-embedding-3-small` (768차원) 코사인 유사도 상위 `FETCH = 60`건 조회.
   - 두 결과를 **RRF (Reciprocal Rank Fusion, `k = 60`)** 와 고유명사 일치 보너스(`entityBonus`)로 결합.
3. **다차원 Re-ranking (`boostedScore`) 및 후보 필터링 (`CANDIDATES = 20`, `MAX_CARDS = 18`)**
   - **"OO와 비슷한/같은 작품" 질의 확장**: `SIMILAR_QUERY_PATTERN` 매칭 시 기준 작품의 장르·키워드·한줄소개·줄거리를 결합해 벡터 검색을 수행하고, 기준 작품과 같은 시리즈(`extractSeriesStem`)는 후보에서 제외. 기준 작품이 실사이면 애니메이션 후보를 감점(`-0.42`).
   - **시대성(Recency) & 평점(Rating) 가중치**: 고전 명시(`CLASSIC_ERA_PATTERN`)가 없으면 1990년대 이전 노후 작품에 감점, 최신작 및 고평점 작품에 가점 부여.
   - **명시적 장르 필터 & 상충 장르 차단 (`extractRequestedGenres`, `hasConflictingGenre`)**:
     - 사용자가 `"달달한 로맨스 영화 추천"`처럼 장르/분위기를 명시했을 때 요청 장르가 포함된 작품에 `+0.45` 가점, 미포함 작품에 `-0.65` 감점.
     - 특히 로맨스·달달·설레는·힐링 요청에 **전쟁·공포·범죄·비로맨스 역사물**(예: `로즈 - 드라마, 역사, 전쟁`)이 끼어드는 것을 후보군(`byId`) 단계에서 원천 차단.
   - **OTT / 방송사 띄어쓰기 무시 인식 & 외래어 조사 보호 (`normalizeOttSpacing`, `extractRequestedNetworks`, `PROTECTED_WORD_SUFFIXES`)**:
     - `"쿠팡 플레이"`와 `"쿠팡플레이"`, `"디즈니 플러스"`와 `"디즈니+"`, `"애플 티비"`와 `"애플TV+"` 등 띄어쓰기 여부와 무관하게 동일하게 OTT/방송사를 인식.
     - 한국어 조사 제거(`stripKoreanParticle`) 시 `"쿠팡플레이"`, `"토이스토리"`, `"미스터리"`, `"판타지"`, `"코미디"` 등의 마지막 글자(`이`, `로` 등)가 조사로 오인되어 잘리지 않도록 `PROTECTED_WORD_SUFFIXES`로 보호.
     - 특정 OTT/방송사가 명시된 검색어는 해당 OTT/방송사 작품만 `byId` 후보에 남김.
4. **최종 정렬 기준 (`BY_YEAR_DESC`)**
   - 화면에 반환되는 모든 추천 카드는 최대 **18개 (`MAX_CARDS = 18`)** 이며, 항상 **개봉연도/방영연도 내림차순(최신순)**, 동률 시 평점 높은 순으로 정렬됨.

---

## 2. Gemini 3.1 Flash-Lite 설정 및 속도 최적화 주의사항

- **모델 설정 ([`application.yml`](../../src/main/resources/application.yml))**:
  ```yaml
  spring:
    ai:
      google:
        genai:
          chat:
            model: ${GEMINI_MODEL:gemini-3.1-flash-lite-preview}
            thinking-level: ${GEMINI_THINKING_LEVEL:MINIMAL}
            temperature: 0.3
  ```
- **주의 (`HTTP 400 Failed to generate content` 방지)**:
  - `gemini-3.1-flash-lite-preview` 모델에서 `thinking-level: MINIMAL`과 함께 `thinking-budget: 0`이나 `include-thoughts: false`를 동시에 설정하면 API가 `400 Bad Request`를 반환하여 `"AI 설명을 만들지 못해서..."` Fallback으로 빠집니다. 반드시 `thinking-level: MINIMAL`만 단독 사용해야 합니다.
- **프롬프트 입출력 토큰 다이어트**:
  - LLM 응답 지연 시간(TTFT 및 토큰 생성 시간)은 **출력 토큰 수(18편 각각의 `reason` 길이)** 에 비례합니다.
  - 따라서 `CANDIDATES = 20`으로 압축된 메타데이터만 전달하고, `summary`는 50자 이내 1문장, 각 `reason`은 20~32자 내외의 짧은 한 줄로 생성하도록 유지해야 빠른 응답 속도(약 1.5~2초)가 보장됩니다.
