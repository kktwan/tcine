# 1) build (Jenkins에서 이미 ./gradlew bootJar를 수행한 경우 바로 복사하고, 단독 docker build 시에도 동작하도록 멀티스테이지 구성)
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon > /dev/null 2>&1 || true
COPY src src
RUN ./gradlew bootJar -x test --no-daemon

# 2) run (A1 ARM64 24GB 서버 최적화: G1GC + Actuator 헬스체크 내장)
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*-SNAPSHOT.jar app.jar
ENV JAVA_TOOL_OPTIONS="-Xms512m -Xmx2048m -XX:+UseG1GC -XX:MaxGCPauseMillis=200"
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --retries=12 \
  CMD curl -fsS http://localhost:8080/actuator/health || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]
