# TCINE (영화 · 시리즈 하이브리드 AI 추천 플랫폼)

TMDB의 **영화**와 **시리즈(드라마·예능·애니메이션)** 데이터를 OpenAI 임베딩으로 벡터화해 **Qdrant**에 저장하고, 키워드 가중치 검색과 의미 기반 벡터 검색을 결합한 **하이브리드 검색(RRF) + AI 추천(RAG)** 을 제공하는 웹 서비스입니다.

## 주요 기능

| 영역 | 기능 |
|---|---|
| **회원 & 보안** | 회원가입/로그인 (BCrypt), 비밀번호 정책 검사, **Cloudflare Turnstile 캡챠**, **Redis 기반 분산 세션 클러스터링** (무중단 배포 시 로그인 유지) |
| **영화 추천 (`/movies`)** | TMDB 영화 데이터를 OpenAI 임베딩으로 벡터화해 **Qdrant(`tcine-movies-openai`)**에 색인. 제목·감독·배우·장르 키워드 가중치 검색과 벡터 유사도 검색을 결합한 **하이브리드 검색(RRF)** 후 Gemini가 추천 이유를 작성(RAG). 상단에는 박스오피스/한국 신작/인기작 포스터 슬라이드 제공 |
| **시리즈 추천 (`/tv`)** | 드라마·예능·애니메이션 데이터를 **Qdrant(`tcine-tv-openai`)**에 별도 색인. 플랫폼(Netflix, TVING, Disney+, Wavve, Coupang Play 등) 브랜드 배지 표시, 제작진·출연진·OTT·분위기 기반 하이브리드 검색 및 상세 페이지 제공 |

## 기술 스택

- **Backend**: Java 21, Spring Boot 4.1.1, Spring Security, Spring Data JPA, Spring Session Data Redis, Spring Boot Actuator
- **DB & Cache**: PostgreSQL 17, **Redis 7** (세션 클러스터링), **Qdrant** (벡터 DB — 영화 `tcine-movies-openai`, 시리즈 `tcine-tv-openai`)
- **AI**: Spring AI 2.0 — 채팅 Google Gemini, 임베딩 OpenAI (`text-embedding-3-small`, 768차원)
- **Infra & CI/CD**: Oracle Cloud A1.Flex (4 OCPU / 24GB RAM, ARM64), Docker, **Jenkins Pipeline (Blue-Green 무중단 배포 & 태그 롤백)**, **Nginx** (내장 DNS 동적 변수 라우팅)

## 아키텍처 & 무중단 배포 (Blue-Green)

```
GitHub (main) ──Webhook──> Jenkins ──> 1. Docker Image Build (tcine:prod-N)
                                   ──> 2. 비활성 슬롯(blue 또는 green) 컨테이너 기동
                                   ──> 3. /actuator/health 헬스체크 & /login JVM 워밍업
                                   ──> 4. Nginx service-url.inc 변수 스왑 & nginx -s reload (0ms 무중단 전환)
                                   ──> 5. 구 슬롯 컨테이너 정지
```

- **Nginx 동적 라우팅 (`upstream` 미사용)**: 도커 내장 DNS(`127.0.0.11`)와 `service-url.inc`(`set $service_url tcine-blue;`)를 조합하여, 비활성 슬롯 컨테이너가 내려가 있어도 Nginx 기동 오류가 발생하지 않으며 `nginx -s reload`만으로 즉시 트래픽이 전환됩니다.
- **Redis 세션 공유**: `spring-session-data-redis`를 통해 `tcine-blue`와 `tcine-green`이 로그인 세션을 공유하므로 배포 중에도 사용자의 로그인이 풀리지 않습니다.
