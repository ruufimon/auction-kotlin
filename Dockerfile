# ---- Build stage -----------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Resolve the Gradle wrapper and dependencies first so they are cached
# independently of source changes.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies > /dev/null

COPY src ./src
RUN ./gradlew --no-daemon installDist -x test

# ---- Runtime stage ---------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

RUN groupadd --system app && useradd --system --gid app --no-create-home app

# Dependencies change less often than application code; copy them as a
# separate layer.
COPY --from=build /workspace/build/install/auction/lib ./lib
COPY --from=build /workspace/build/install/auction/bin ./bin

USER app

ENV PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
    CMD curl -fsS "http://localhost:${PORT}/health/ready" || exit 1

ENTRYPOINT ["/app/bin/auction"]
