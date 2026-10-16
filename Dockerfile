# syntax=docker/dockerfile:1

# ---- Build stage: compile and assemble the layered boot jar ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Resolve dependencies first so the Gradle cache layer survives source-only changes.
COPY gradlew settings.gradle.kts ./
COPY gradle gradle
COPY ot-core/build.gradle.kts ot-core/
COPY server/build.gradle.kts server/
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :server:dependencies >/dev/null 2>&1 || true

COPY . .
# Tests need Docker (Testcontainers) and run in CI, not in the image build.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :server:bootJar -x test
RUN java -Djarmode=layertools -jar server/build/libs/*.jar extract --destination server/build/extracted

# ---- Runtime stage: JRE only, non-root, Spring Boot layers for cache reuse ----
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
RUN groupadd --system otter && useradd --system --gid otter --home /app otter

# Ordered least- to most-frequently-changing so a code change only rebuilds the last layer.
COPY --from=build /workspace/server/build/extracted/dependencies/ ./
COPY --from=build /workspace/server/build/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/server/build/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/server/build/extracted/application/ ./

USER otter
EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
