# TCINE 아키텍처 & 도메인 구조

## 1. 전체 흐름 요약

1. **인증 및 보안 (`domain.member`, `global.security`, `infra.turnstile`)**
   - Spring Security Form Login (`/login`, `/signup`) + Cloudflare Turnstile 봇 방지 캡챠
   - 비밀번호 정책(`PasswordPolicy`): 8자 이상, 영문/숫자/특수문자 중 3종 이상 조합, 아이디 포함 금지
   - 세션 저장소: 운영 환경은 **Redis (`Spring Session Data Redis`, 네임스페이스 `tcine:session`)** 를 사용하여 Blue-Green 무중단 배포 전환 시에도 로그인 세션이 유지됨
2. **홈 화면 캐시 및 백그라운드 예열 (`MovieHomeService`, `TvHomeService`)**
   - 영화 홈(`/movies`): 일별 박스오피스(KOBIS API + TMDB 매칭), 한국 최신 영화, 오늘의 트렌딩 영화
   - 시리즈 홈(`/tv`): 한국 시리즈 최신작, 한국 대표 인기 드라마·예능, 오늘의 인기 시리즈
   - `@EventListener(ApplicationReadyEvent.class)`로 기동 직후 비동기 적재 + `@Scheduled(fixedDelay = 25분)`로 캐시 만료(30분) 전 자동 갱신하여 사용자 체감 로딩 시간 `< 10ms` 유지
   - 시리즈 포스터 우측 상단 배지(`.net-badge`):
     - 6대 OTT(`Netflix`, `TVING`, `Coupang Play`, `Wavve`, `Disney+`, `Watcha`)는 [`app.css`](../../src/main/resources/static/css/app.css)에서 원형 심볼 로고(SVG Data URI)로 렌더링
     - 일반 방송 채널(`tvN`, `ENA`, `JTBC`, `KBS2`, `SBS`, `Fuji TV` 등) 및 포스터 하단 메타 텍스트(`2026 · Netflix`)는 영문/채널명 텍스트 그대로 표시
3. **정적 리소스 캐시 버스팅 (Cache Busting)**
   - [`application.yml`](../../src/main/resources/application.yml)의 `spring.web.resources.chain.strategy.content.enabled: true` 설정을 통해 `/css/app.css`, `/js/movies.js` 등에 파일 내용 기반 MD5 해시(`/css/app-<hash>.css`)가 자동 부여됨
   - 배포 후 사용자가 강력 새로고침(`Ctrl+Shift+R`)을 하지 않아도 변경된 CSS/JS가 즉시 반영됨

## 2. 패키지 구조

```text
com.t.tcine
├─ TcineApplication.java          (@EnableScheduling 활성화)
├─ domain
│  ├─ member
│  │  ├─ controller/AuthController.java
│  │  ├─ entity/Member.java
│  │  ├─ repository/MemberRepository.java
│  │  └─ service/{MemberService, CustomUserDetailsService, PasswordPolicy}.java
│  ├─ movie
│  │  ├─ controller/MovieController.java
│  │  ├─ dto/{MovieResult, HomeMovie, ModelAnswer}.java
│  │  └─ service/{MovieRecommendService, MovieHomeService, MovieIndexService, MovieDetailService, MovieAutoIndexScheduler}.java
│  └─ tv
│     ├─ controller/TvController.java
│     ├─ dto/{TvResult, HomeTv}.java
│     └─ service/{TvRecommendService, TvHomeService, TvIndexService, TvDetailService}.java
├─ infra
│  ├─ kobis/KobisClient.java      (영화진흥위원회 일별 박스오피스 API)
│  ├─ tmdb/TmdbClient.java        (TMDB 영화·TV 상세/목록/크레딧/키워드 API)
│  └─ turnstile/TurnstileService.java
└─ global
   ├─ config/RestClientConfig.java
   ├─ controller/{HomeController, GlobalErrorController, ModelAdvice}.java
   └─ security/SecurityConfig.java
```
