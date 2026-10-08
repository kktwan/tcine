# 하이브리드 RAG 검색 & AI 큐레이션 명세

영화([`MovieRecommendService.java`](../../src/main/java/com/t/tcine/domain/movie/service/MovieRecommendService.java))와 시리즈([`TvRecommendService.java`](../../src/main/java/com/t/tcine/domain/tv/service/TvRecommendService.java)) 추천 엔진의 핵심 동작 원리와 유지보수 시 절대 깨뜨리면 안 되는 규칙을 정리한 문서입니다.

---

## 0. 코드 구조 (공통 엔진 + 영화/시리즈 얇은 하위 클래스)

```
domain/search                         영화·시리즈 공통 검색 엔진
├─ MediaKind                          MOVIE / TV 구분 (예전의 boolean isTv)
├─ config/
│  ├─ SearchDictionary                search-dictionary.yml 바인딩 (어휘·규칙)
│  └─ RankingProperties               점수 가중치 (기본값 = 기존 상수, search.ranking.* 로 덮어쓰기)
├─ query/QueryAnalyzer                검색어 해석: 핵심어, 요청 장르·OTT, "OO와 비슷한" 기준 작품, 시리즈 전체 요청
├─ ranking/
│  ├─ RankingEngine                   키워드 점수, RRF 결합, boostedScore, 장르·OTT 판정
│  └─ DocFields                       문서 메타데이터/본문 값 꺼내기 도우미
├─ corpus/
│  ├─ AbstractCorpusManager           Qdrant 전체를 메모리에 올리는 코퍼스 캐시
│  └─ CorpusSource                    (인터페이스)
└─ recommend/
   ├─ AbstractRecommendService        추천 공통 흐름 (아래 4단계)
   ├─ ResultCache / UsageLimiter      30분 LRU 결과 캐시 / 사용자별 하루 한도
   ├─ RecommendMessages               영화·시리즈별 안내 문구 묶음
   └─ Recommendation / RecommendCard  결과·카드 DTO가 구현하는 공통 인터페이스

domain/movie/service  MovieRecommendService, MovieCorpusManager   (AbstractXxx 를 상속, 영화 고유 부분만)
domain/tv/service     TvRecommendService, TvCorpusManager         (AbstractXxx 를 상속, 시리즈 고유 부분만)
```

`AbstractRecommendService.run()`은 아래 4단계 메서드로 나뉜다.

| 단계 | 메서드 | 하는 일 |
|---|---|---|
| 1 | `resolveReference` | "OO와 비슷한"이면 기준 작품과 그에 맞춘 검색·요청 문장을 정함 |
| 2 | `retrieve` | 키워드 검색 + 벡터 검색을 병렬 실행하고 RRF로 결합 |
| 3 | `selectCandidates` | `boostedScore` 재정렬 + 장르/OTT/기준 작품 시리즈 필터로 후보 20편 추림 |
| 4 | `curate` | Gemini 큐레이션. 시간 초과·실패 시 검색 순서 그대로 (`fallback`) |

영화와 시리즈의 차이는 하위 클래스의 아래 항목뿐이다.

- 카드 모양(`card`), Gemini에게 주는 후보 한 줄 형식(`candidateLine`), 안내 문구(`RecommendMessages`)
- 기준 작품과 같은 시리즈를 거르는 방식(`seriesStem`): 영화는 `제목: 부제`·`제목 2`에서 앞 제목만, 시리즈는 제목 전체
- 영화만 "OO 시리즈 전체" 요청을 AI 없이 제목 검색으로 돌림(`handleBeforeRun`)
- OTT 필터는 시리즈에서만 동작(`MediaKind.isTv()`)

> 검색/추천 로직을 고칠 때는 공통 클래스(`search/**`)를 고치면 영화·시리즈에 동시에 반영된다. 한쪽에만 다르게 넣어야 할 때만 하위 클래스를 고칠 것.

### 설정으로 분리한 것

- **어휘·규칙 → [`search-dictionary.yml`](../../src/main/resources/search-dictionary.yml)** (`spring.config.import`로 로드)
  - 요청 장르 표현(`genres`), 장르 동의어(`genre-keyword-synonyms`), 가벼운 분위기 판단·충돌 규칙(`light-mood`, `conflicts`), OTT 별칭(`networks`), OTT 표기 정리(`ott-spacing`), 불용어, 조사, 조사 오인 보호 어휘, "비슷한"/"고전" 정규식, 시리즈 전체 요청 표현
  - 비교는 소문자·공백/구두점 제거(`compact`) 후에 하므로 yml의 비교용 단어는 소문자로 쓴다.
- **점수 가중치 → `RankingProperties`** (`search.ranking.*`)
  - 키워드 일치 점수, RRF `k`/배율, 영화·시리즈별 최신작 기준 연도와 오래된 작품 감점, 평점 가감점, 기준 작품·장르·OTT 가감점. 기본값은 리팩터링 전 상수와 같다. 조정하려면 `application.yml`에 필요한 값만 적는다.
  - 예) `search.ranking.alignment.genre-miss: -0.5`
  - 코드에 남긴 값(튜닝 대상이 아님): 후보 수/카드 수/타임아웃/캐시 크기(`AbstractRecommendService` 상수)

### 자주 하는 수정

| 하고 싶은 일 | 어디를 고치나 |
|---|---|
| 새 OTT/방송사 추가 | `search-dictionary.yml` → `networks`에 한 줄 (`id`, `query-triggers`, `term-triggers`, `data-aliases`) |
| 새 장르 표현 추가 (예: "로맨스"에 "썸" 추가) | `genres`의 해당 규칙 `triggers`에 단어 추가 |
| 불용어/조사 보호 단어 추가 | `stopwords`, `protected-word-suffixes` |
| "가볍고 따뜻한 분위기"에서 막을 장르 조정 | `conflicts` (`blocked`, `unless-requested`, `unless-candidate-has`) |
| 최신작 가점, 장르 가감점 등 조정 | `application.yml`의 `search.ranking.*` |
| 추천 문구/카드 항목 변경 | 각 `*RecommendService`의 `RecommendMessages`, `card`, `candidateLine` |

### 회귀 방지 테스트

[`SearchRefactorDifferentialTest`](../../src/test/java/com/t/tcine/domain/search/SearchRefactorDifferentialTest.java)가 리팩터링 직전 구현(`src/test/.../search/legacy/Legacy*`, 테스트 전용 복사본)과 현재 구현의 결과를 비교한다. 질의 약 90개 × 샘플 문서 24개 × 영화/시리즈에 대해 검색어 해석, 키워드 점수, `boostedScore`, 장르·OTT 판정, RRF 순서를 확인한다.

- 실행: `./gradlew.bat test --tests "*SearchRefactorDifferentialTest*" --no-daemon`
- **검색 규칙을 일부러 바꾸면 이 테스트가 실패한다.** 의도한 변경이면 `Legacy*`를 그대로 두지 말고 테스트 기대값을 새 규칙에 맞게 고치거나, 레거시 비교를 걷어내고 기대값 고정 테스트로 바꿀 것.

---

## 1. 검색 파이프라인 (Hybrid Search + Re-ranking + LLM RAG)

1. **Qdrant 인메모리 코퍼스 캐시 (`AbstractCorpusManager`)**
   - Qdrant 컬렉션(`tcine-movies-openai` 약 7,370편, `tcine-tv-openai` 약 4,260편) 전체 문서를 인메모리에 15분간 캐시(`warmUpCorpus()`로 기동 직후 예열, 색인 건수 변경 시 자동 갱신).
   - 매 검색마다 Qdrant `scrollAsync`를 호출하던 병목(5~6초)을 제거하여 키워드 매칭을 `< 5ms`에 수행하고, 전체 1단계 검색(OpenAI 임베딩 + Qdrant 벡터 검색 병렬 실행)을 **135~270ms** 내에 완료함.
2. **병렬 하이브리드 검색 & RRF 결합 (`retrieve`)**
   - 키워드 검색: 인메모리 코퍼스 대상 BM25 스타일 필드 가중치 점수 계산 (제목 65~100점, 감독/제작 80점, 배우/OTT 75점, 키워드 45점, 장르 35점, 줄거리 15점. `RankingProperties.Keyword`).
   - 벡터 검색: OpenAI `text-embedding-3-small` (768차원) 코사인 유사도 상위 `FETCH = 60`건 조회.
   - 두 결과를 **RRF (Reciprocal Rank Fusion, `k = 60`)** 와 고유명사 일치 보너스(`entityBonus`)로 결합 (`RankingEngine.mergeAndRank`).
3. **다차원 Re-ranking (`boostedScore`) 및 후보 필터링 (`selectCandidates`, `CANDIDATES = 20`, `MAX_CARDS = 18`)**
   - **"OO와 비슷한/같은 작품" 질의 확장**: 사전의 `similar-query-pattern` 매칭 시 기준 작품의 장르·키워드·한줄소개·줄거리를 결합해 벡터 검색을 수행하고, 기준 작품과 같은 시리즈(`seriesStem`)는 후보에서 제외. 기준 작품이 실사이면 애니메이션 후보를 감점(`alignment.animation-mismatch`, 기본 `-0.42`).
     - 기준 작품은 **색인(코퍼스)에 있어야** 찾을 수 있다. 제목이 색인에 없으면 일반 의미 검색으로 처리된다(예: 색인에 없는 작품명으로 "OO와 비슷한 영화"를 검색하면 제목 단어만 따라간 결과가 나옴).
   - **시대성(Recency) & 평점(Rating) 가중치**: 사전의 `classic-era-pattern`(고전·명작·옛날 등) 요청이 없으면 영화 1995/2001년 이전, 시리즈 2000/2008년 이전 노후 작품에 감점, 최신작 및 고평점 작품에 가점 부여.
   - **명시적 장르 필터 & 상충 장르 차단 (`extractRequestedGenres`, `hasConflictingGenre`)**:
     - 사용자가 `"달달한 로맨스 영화 추천"`처럼 장르/분위기를 명시했을 때 요청 장르가 포함된 작품에 `+0.45` 가점, 미포함 작품에 `-0.65` 감점.
     - 특히 로맨스·가족·달달·설레는·힐링·따뜻 요청에 **전쟁·공포·범죄·비로맨스 역사물**(예: `로즈 - 드라마, 역사, 전쟁`)이 끼어드는 것을 후보군 단계에서 원천 차단 (영화/시리즈별 규칙은 `conflicts`).
   - **OTT / 방송사 띄어쓰기 무시 인식 & 외래어 조사 보호 (`normalizeOttSpacing`, `extractRequestedNetworks`, `protected-word-suffixes`)**:
     - `"쿠팡 플레이"`와 `"쿠팡플레이"`, `"디즈니 플러스"`와 `"디즈니+"`, `"애플 티비"`와 `"애플TV+"` 등 띄어쓰기 여부와 무관하게 동일하게 OTT/방송사를 인식(`ott-spacing`).
     - 한국어 조사 제거 시 `"쿠팡플레이"`, `"토이스토리"`, `"미스터리"`, `"판타지"`, `"코미디"` 등의 마지막 글자(`이`, `로` 등)가 조사로 오인되어 잘리지 않도록 `protected-word-suffixes`로 보호.
     - 특정 OTT/방송사가 명시된 시리즈 검색어는 해당 OTT/방송사 작품만 후보에 남김(영화 검색에는 OTT 필터 없음).
4. **최종 정렬 기준 (`byYearDesc`)**
   - 화면에 반환되는 모든 추천 카드는 최대 **18개 (`MAX_CARDS = 18`)** 이며, 항상 **개봉연도/방영연도 내림차순(최신순)**, 동률 시 평점 높은 순으로 정렬됨.
   - 단, 기준 작품 없는 일반 검색에서 **제목·인물이 질의와 정확히 맞은 작품**(키워드 점수 `entityStrong`=65 이상)은 연도와 상관없이 맨 앞에 두고, 그 안에서 최신순(`sortCards`). 분위기·장르 검색처럼 일치 작품이 없으면 전체 최신순. 빠른 검색(`searchFast`)에도 같은 규칙을 쓴다.

### 관련도 하한 (`search.ranking.relevance.min-vector-score`)

- 의미 없는 질의(`asdfasdf`)도 벡터 검색은 항상 가장 가까운 작품을 돌려주고 AI가 그럴듯하게 골라 주는 문제를 막는 장치다.
- 벡터 유사도 1위가 하한보다 낮고, **제목·인물이 맞은 작품도 없으면** AI를 부르지 않고 "관련 작품을 찾지 못했어요"로 답한다. "비슷한 작품"(기준 작품 있음) 요청에는 적용하지 않는다.
- 기본값 `0`(꺼짐). 환경변수 `SEARCH_MIN_VECTOR_SCORE`로 코드 수정 없이 조정한다. 값은 평가 세트 리포트의 `점수: 벡터 1위 …` 분포를 보고 정한다.
  - 로컬 색인 기준 무의미 질의 1위 0.27, 정상 질의 1위 0.33 이상. 색인 규모가 다르면 분포도 다르므로 **운영에서 먼저 측정**할 것.
- 평가 실행 시 같은 환경변수를 주면 하한이 적용된 상태로 측정된다.

### OTT 점수 처리

- OTT/방송사 일치는 후보 선정 뒤 필터(`enforceNetworkFilter`)로만 반영한다. RRF의 고유명사 보너스·`[검색어 일치]` 표시·일치 작품 앞세우기에는 쓰지 않는다(쓰면 ×25로 증폭돼 장르·의미 점수가 순위에 영향을 못 줌).
### 알려진 주의점

- **코퍼스 문서 메타데이터 수정**: "OO와 비슷한" 검색에서 기준 작품의 `genres`/`keywords`를 여러 일치 작품의 합집합으로 바꿀 때(`resolveReference`) 캐시된 코퍼스 문서의 메타데이터를 직접 수정한다. 그 작품의 장르가 코퍼스 캐시(15분)가 갱신될 때까지 합쳐진 값으로 남는다. 리팩터링 전부터 있던 동작이며 순위에 영향을 줄 수 있어 그대로 유지했다. 고친다면 문서 복사본을 쓰고 위 비교 테스트 기대값을 같이 확인할 것.
- `MovieIndexService`/`TvIndexService`, `MovieHomeService`/`TvHomeService`는 아직 영화·시리즈별로 중복되어 있다.

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

## 3. 검색 품질 평가 (평가 세트)

검색 규칙·점수·프롬프트·색인을 바꾸기 전후에 **좋아졌는지 숫자로 확인**하는 도구다. 실제로 겪은 이상한 검색 결과를 케이스로 쌓아 둔다.

- 케이스: [`src/test/resources/search-eval/cases.yml`](../../src/test/resources/search-eval/cases.yml) (검색어, 나와야 할 것/나오면 안 되는 것, 왜 만들었는지)
- 실행기: `SearchEvalTest` — 실제 임베딩·Qdrant·Gemini를 호출하므로 평소 빌드에서는 건너뛴다.
  ```bash
  RUN_SEARCH_EVAL=true ./gradlew.bat test --tests "*SearchEvalTest*" --no-daemon -i
  ```
  PowerShell: `$env:RUN_SEARCH_EVAL='true'; ./gradlew.bat test --tests "*SearchEvalTest*" --no-daemon -i`
- 결과: 콘솔과 `build/search-eval-report.txt`에 케이스별 PASS/FAIL, 상위 결과 제목, 실패 이유가 나온다. 실패 시 빌드를 깨려면 `EVAL_STRICT=true`.
- 로컬 색인(영화 1,300편·시리즈 220편)과 운영 색인(약 7,370편·4,260편)은 규모가 달라 통과율이 다를 수 있다. 이상한 검색 결과를 발견하면 먼저 케이스로 추가하고, 원인(관련도 하한 / 점수 균형 / 색인 데이터)으로 설명되는지 본다.

