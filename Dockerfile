# ---- Build stage -----------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Resolve the Gradle wrapper and dependencies first so they are cached
# independently of source changes.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY app/build.gradle.kts ./app/
COPY features/user-onboarding/build.gradle.kts ./features/user-onboarding/
COPY features/product-catalog/build.gradle.kts ./features/product-catalog/
COPY features/bidding/build.gradle.kts ./features/bidding/
COPY features/settlement/build.gradle.kts ./features/settlement/
RUN ./gradlew --no-daemon :app:dependencies > /dev/null

COPY app/src ./app/src
COPY features ./features
RUN ./gradlew --no-daemon :app:installDist -x test

# ---- Runtime stage ---------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

RUN groupadd --system app && useradd --system --gid app --no-create-home app

# Dependencies change less often than application code; copy them as a
# separate layer.
COPY --from=build /workspace/app/build/install/app/lib ./lib
COPY --from=build /workspace/app/build/install/app/bin ./bin

USER app

ENV PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=20s --retries=3 \
    CMD curl -fsS "http://localhost:${PORT}/health/ready" || exit 1

ENTRYPOINT ["/app/bin/app"]
