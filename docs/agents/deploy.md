# 로컬 개발 환경 & 운영 배포 가이드

## 1. 브랜치 및 배포 원칙

- **브랜치 흐름**: `feature/*` ➔ `prod` (운영 배포 기본 브랜치) ➔ `main`
- **역할 분담**:
  - 에이전트는 로컬 워크스페이스(`D:\pra\tcine`)에서 코드 수정 및 `./gradlew.bat compileJava compileTestJava --no-daemon` 검증까지만 수행합니다.
  - 배포는 사용자가 직접 `prod` 브랜치 Push 및 Jenkins(`https://tjenkins.duckdns.org`) 빌드를 통해 진행합니다.

---

## 2. 로컬 IntelliJ 실행 환경 (`D:\pra\tcine`)

1. **로컬 Docker 컨테이너 3종 실행 확인**:
   - `tcine-db` (`postgres:17-alpine`): 호스트 포트 **`5433`** ➔ 컨테이너 `5432`
   - `tcine-redis` (`redis:7-alpine`): 호스트 포트 **`6379`** ➔ 컨테이너 `6379`
   - `tcine-qdrant` (`qdrant/qdrant:latest`): 호스트 포트 **`6333`(HTTP), `6334`(gRPC)**
   ```powershell
   docker start tcine-db tcine-redis tcine-qdrant
   ```
2. **환경변수 자동 주입 (`.env`)**:
   - 프로젝트 루트의 `.env` 파일을 [`application.yml`](../../src/main/resources/application.yml)의 `spring.config.import: optional:file:.env[.properties]`가 자동으로 로드합니다.
   - 인텔리제이에서 별도 환경변수 설정 없이 `TcineApplication`을 바로 Run/Debug 하면 `http://localhost:8080`으로 기동됩니다.

---

## 3. 운영 서버 (Oracle Cloud A1) 구조 및 Blue-Green 무중단 배포

- **서버 사양**: Oracle Cloud `VM.Standard.A1.Flex` (4 OCPU / 24GB RAM, ARM64)
- **공용 인프라 (`/data/infra/docker-compose.yml`, 네트워크 `infra-net`)**:
  - `infra-nginx`: `80/443` 리버스 프록시 (`tcine.duckdns.org`, `tjenkins.duckdns.org`), Docker 내장 DNS(`127.0.0.11`) 및 `/etc/nginx/conf.d/tcine-url.inc`(`set $tcine_url ...;`)를 통한 무중단 동적 라우팅
  - `infra-certbot`: Let's Encrypt SSL 인증서 자동 갱신
  - `infra-jenkins`: CI/CD 파이프라인 실행
  - `infra-postgres`, `infra-redis`, `infra-qdrant`: 데이터베이스, 분산 세션 Redis, 벡터 DB
- **앱 배포 (`/data/tcine/docker-compose.app.yml` & [`Jenkinsfile`](../../Jenkinsfile))**:
  - `tcine-blue` / `tcine-green` 슬롯을 번갈아 기동하고 `/actuator/health` 통과 및 `/login` JVM 워밍업 후 `tcine-url.inc` 스왑 & `nginx -s reload` 수행
  - Jenkins에서 `ROLLBACK = true` 파라미터로 빌드 시 이미지 빌드 없이 직전 `prod-*` 태그로 15초 내 즉시 롤백
