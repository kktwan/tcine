# TCINE (영화 · 시리즈 하이브리드 AI 추천 플랫폼)

TMDB의 **영화**와 **시리즈(드라마·예능·애니메이션)** 데이터를 OpenAI 임베딩으로 벡터화해 **Qdrant**에 저장하고, 키워드 가중치 검색과 의미 기반 벡터 검색을 결합한 **하이브리드 검색(RRF) + AI 추천(RAG)** 을 제공하는 웹 서비스입니다.

- 🎬 **서비스 주소**: <https://tcine.duckdns.org>
- 🛠️ **Jenkins CI/CD**: <https://tjenkins.duckdns.org>

---

## 주요 기능

| 영역 | 기능 |
|---|---|
| **회원 & 보안** | 회원가입/로그인 (BCrypt), 비밀번호 정책 검사(3종류 이상 8자↑ 등), **Cloudflare Turnstile 캡챠**(로그인·가입), **Redis 기반 분산 세션 클러스터링** (Blue-Green 무중단 배포 시 로그인 유지) |
| **영화 추천 (`/movies`)** | TMDB 영화 데이터를 OpenAI 임베딩으로 벡터화해 **Qdrant**에 색인. 제목·감독·배우·장르 키워드 가중치 검색과 벡터 유사도 검색을 결합한 **하이브리드 검색(RRF)** 후 Gemini가 추천 이유를 작성(RAG). 상단에는 박스오피스/한국 신작/인기작 포스터 슬라이드 제공 |
| **시리즈 추천 (`/tv`)** | 드라마·예능·애니메이션 데이터를 **Qdrant**에 별도 색인. 플랫폼(Netflix, TVING, Disney+, Wavve, Coupang Play, tvN, SBS 등) 브랜드 배지 표시, 제작진·출연진·OTT·분위기 기반 하이브리드 검색 및 상세 페이지 제공 |

---

## 기술 스택

- **Backend**: Java 21, Spring Boot 4.1.1, Spring Security, Spring Data JPA, Spring Session Data Redis, Spring Boot Actuator
- **보안**: Cloudflare Turnstile(캡챠), CSRF 보호(토큰 만료 시 로그인 화면 안내), Let's Encrypt HTTPS(HTTP/2)
- **DB & Cache**: PostgreSQL 17, **Redis 7** (분산 세션 클러스터링), **Qdrant** (벡터 DB)
- **AI**: Spring AI 2.0 — 채팅 Google Gemini (`gemini-3.1-flash-lite`), 임베딩 OpenAI (`text-embedding-3-small`, 768차원)
- **View**: Thymeleaf, 순수 CSS/JS
- **외부 API**: TMDB API (영화·TV 시리즈 데이터), KOBIS API (일별 박스오피스)
- **Infra & CI/CD**: Oracle Cloud A1.Flex (4 OCPU / 24GB RAM, ARM64), Docker, **Jenkins Pipeline (Blue-Green 무중단 배포 & 자동 롤백)**, **Nginx** (도커 내장 DNS 동적 변수 라우팅), **Certbot** (인증서 자동 갱신)

---

## 프로젝트 구조

```text
src/main/java/com/t/tcine
├─ domain
│  ├─ member       회원가입, 로그인, 비밀번호 정책 (Security UserDetailsService)
│  ├─ movie        영화 추천·상세·색인 (TMDB -> Qdrant 하이브리드 검색 -> Gemini RAG)
│  ├─ tv           시리즈(드라마·예능·애니) 추천·상세·색인
│  └─ search       영화·시리즈 공통 검색 엔진 (검색어 해석, 하이브리드 RRF·재순위, 코퍼스 캐시, 추천 공통 흐름)
├─ infra
│  ├─ tmdb         TMDB 영화·시리즈 API 클라이언트
│  ├─ kobis        KOBIS 영화진흥위원회 박스오피스 클라이언트
│  └─ turnstile    Cloudflare Turnstile 서버 검증
└─ global          보안 설정(SecurityConfig), 스케줄링, 예외 처리, 공통 모델(ModelAdvice)

src/main/resources
├─ search-dictionary.yml  검색 어휘 사전 (장르 표현, OTT 별칭, 불용어 등)
├─ templates       Thymeleaf 화면 (movies, movie-detail, tv, tv-detail, login, signup, fragments)
├─ static/css      app.css
├─ static/js       movies.js, login.js
├─ static/img      icons.svg
├─ application.yml 운영 기본 설정 (Redis 세션, Actuator 헬스체크)
└─ application-local.yml 로컬 개발 전용 설정 (세션 메모리 모드, 정적 파일 즉시 반영)

deploy/
├─ docker-compose.infra.yml  서버 공용 인프라 (/data/infra/docker-compose.yml)
├─ docker-compose.app.yml    tcine Blue/Green 앱 컨테이너 (/data/tcine/docker-compose.app.yml)
└─ nginx/
   ├─ default.conf           tcine.duckdns.org 가상 호스트 설정 (/data/infra/nginx/conf.d/tcine.conf)
   ├─ jenkins.conf           tjenkins.duckdns.org 가상 호스트 설정 (/data/infra/nginx/conf.d/jenkins.conf)
   └─ service-url.inc        현재 활성 슬롯 포인터 (/data/infra/nginx/conf.d/tcine-url.inc)
```

---

## 서버 아키텍처 & 무중단 배포 (Blue-Green)

오라클 클라우드 `VM.Standard.A1.Flex (4 OCPU / 24GB RAM)` 단일 서버 위에서 **공용 인프라(`/data/infra`)** 와 **서비스별 앱(`/data/tcine`)** 을 분리해 다중 프로젝트를 확장할 수 있도록 구성했습니다.

### 1. 컨테이너 구성 (`infra-net` 네트워크 공유)

| 구분 | 컨테이너 이름 | 경로 | 설명 |
|---|---|---|---|
| **공용 인프라** | `infra-nginx` | `/data/infra` | 80/443 포트 담당, 도메인별 가상 호스트 분기 (`tcine.duckdns.org`, `tjenkins.duckdns.org`) |
| **공용 인프라** | `infra-certbot` | `/data/infra` | 12시간 주기로 Let's Encrypt SSL 인증서 만료 확인 및 무중단 갱신 |
| **공용 인프라** | `infra-jenkins` | `/data/infra` | 호스트 Docker 소켓 공유, 네이티브 ARM64 이미지 빌드 및 Blue/Green 배포 총괄 |
| **공용 인프라** | `infra-postgres` | `/data/infra` | PostgreSQL 17 (2GB 튜닝) |
| **공용 인프라** | `infra-redis` | `/data/infra` | Redis 7 (`Spring Session Data Redis`를 통해 Blue ↔ Green 전환 시 로그인 세션 유지) |
| **공용 인프라** | `infra-qdrant` | `/data/infra` | Qdrant 벡터 DB (2GB 할당, 영화·시리즈 임베딩 보관) |
| **서비스 앱** | `tcine-blue` / `tcine-green` | `/data/tcine` | Jenkins가 번갈아 가동하는 Spring Boot 앱 컨테이너 (`G1GC`, 최대 2GB Heap) |

### 2. 브랜치 전략 및 배포 파이프라인 (`Jenkinsfile`)

```text
feature/* (기능 개발) ──Merge──> prod (소스 통합 & 운영 배포) ──Merge──> main (검증 완료 최종 머지)
                                        │
                         Jenkins Build (https://tjenkins.duckdns.org)
                                        ▼
                        1. Git Checkout (기본: prod 브랜치)
                        2. Docker Image Build (tcine:prod-N, 최근 3개 이미지 유지)
                        3. 비활성 슬롯(tcine-blue 또는 tcine-green) 컨테이너 기동
                        4. /actuator/health 헬스체크 통과 확인 & /login JVM 워밍업
                        5. /data/infra/nginx/conf.d/tcine-url.inc 변수 스왑 & nginx -s reload (0ms 무중단 전환)
                        6. 구 슬롯 컨테이너 안전 정지
```

- **`upstream` 블록 없는 동적 스위칭**: 도커 내장 DNS(`127.0.0.11`)와 `$tcine_url` 변수(`include /etc/nginx/conf.d/tcine-url.inc;`)를 사용해 비활성 컨테이너가 내려가 있어도 Nginx 기동 오류가 발생하지 않습니다.
- **즉시 롤백 (`ROLLBACK` 파라미터)**: Jenkins에서 `Build with Parameters` → `ROLLBACK` 체크 후 빌드하면, 코드 빌드를 건너뛰고 직전 버전 도커 이미지(`prod-N`)로 15초 만에 무중단 롤백됩니다.

---

## 로컬 실행 (`local` 프로파일)

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

- `local` 프로파일에서는 Redis 없이도 동작하도록 `spring.session.store-type=none` 이 자동 적용됩니다.
- HTML/CSS/JS를 소스 폴더에서 직접 읽어 저장 후 브라우저 새로고침만으로 반영됩니다.
- Cloudflare Turnstile은 로컬 테스트 키가 자동 적용되어 항상 통과합니다.
