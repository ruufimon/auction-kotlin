# ---- Build stage -----------------------------------------------------------
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Resolve the Gradle wrapper and dependencies first so they are cached
# independently of source changes.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY app/build.gradle.kts app/
COPY shared-kernel/build.gradle.kts shared-kernel/
COPY shared-web/build.gradle.kts shared-web/
COPY user-onboarding/build.gradle.kts user-onboarding/
COPY product-catalog/build.gradle.kts product-catalog/
COPY bidding/build.gradle.kts bidding/
COPY settlement/build.gradle.kts settlement/
RUN ./gradlew --no-daemon :app:dependencies > /dev/null

COPY . .
RUN ./gradlew --no-daemon :app:installDist -x test

# ---- Runtime stage ---------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

RUN groupadd --system app && useradd --system --gid app --no-create-home app

# Dependencies change less often than application code; copy them as a
# separate layer.
COPY --from=build /workspace/app/build/install/gavel/lib ./lib
COPY --from=build /workspace/app/build/install/gavel/bin ./bin

USER app

ENV PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
    CMD curl -fsS "http://localhost:${PORT}/health/ready" || exit 1

ENTRYPOINT ["/app/bin/gavel"]
