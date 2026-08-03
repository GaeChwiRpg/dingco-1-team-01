# ── build ────────────────────────────────────────────────────
# D-003 대로 Java 21. 로컬 기본 JDK 가 25 여도 여기서는 관계없다.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# wrapper 와 빌드 스크립트를 먼저 복사해 의존성 레이어를 캐시한다.
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

COPY src src
RUN ./gradlew --no-daemon clean bootJar -x test

# ── runtime ──────────────────────────────────────────────────
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN useradd --create-home --shell /bin/false triage
USER triage

COPY --from=build --chown=triage:triage /workspace/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
