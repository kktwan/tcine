# TCINE 프로젝트 에이전트 가이드 (AGENTS.md)

Spring Boot 4.1(Java 21) + PostgreSQL + Redis + Qdrant(OpenAI 임베딩) + Google Gemini(`gemini-3.1-flash-lite-preview`) 기반의 **영화·시리즈 하이브리드 AI 추천 플랫폼** (`https://tcine.duckdns.org`).

작업 범위에 따라 아래 세부 문서를 참고할 것:

- [docs/agents/architecture.md](docs/agents/architecture.md) — 전체 흐름, 패키지 구조, 홈 화면 백그라운드 캐시 예열, OTT 원형 로고 배지, 정적 리소스 캐시 버스팅
- [docs/agents/rag-and-search.md](docs/agents/rag-and-search.md) — Qdrant 인메모리 코퍼스 캐시, 하이브리드(RRF) 검색, 다차원 Re-ranking(장르/OTT/띄어쓰기/조사 처리), Gemini 3.1 설정 및 토큰 최적화 규칙
- [docs/agents/deploy.md](docs/agents/deploy.md) — 로컬 IntelliJ + Docker(`5433`/`6379`/`6334`) 실행 환경, 브랜치 전략(`feature -> prod -> main`), Oracle Cloud A1 Blue-Green 무중단 배포 구조

## 빠른 참고 & 협업 규칙

- **컴파일 검증**: `./gradlew.bat compileJava compileTestJava --no-daemon` (Java 21)
- **로컬 실행**: 로컬 Docker 컨테이너(`tcine-db:5433`, `tcine-redis:6379`, `tcine-qdrant:6333/6334`) 기동 상태에서 인텔리제이로 `TcineApplication` 실행 (루트 `.env` 자동 로드)
- **배포 원칙**: 에이전트는 로컬 코드 수정 및 빌드 검증까지만 수행하며, 원격 서버 배포는 사용자가 직접 Git Push(`prod` 브랜치) 및 Jenkins(`https://tjenkins.duckdns.org`)를 통해 수행함
- **주요 주의사항**:
  1. `application.yml`의 Gemini 설정에서 `thinking-level: MINIMAL`과 `thinking-budget: 0`을 절대 함께 쓰지 말 것 (`HTTP 400 Failed to generate content` 발생)
  2. 검색/추천 로직 수정 시 영화([`MovieRecommendService.java`](src/main/java/com/t/tcine/domain/movie/service/MovieRecommendService.java))와 시리즈([`TvRecommendService.java`](src/main/java/com/t/tcine/domain/tv/service/TvRecommendService.java)) 양쪽 모두 일관되게 반영할 것
