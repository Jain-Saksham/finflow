# syntax=docker/dockerfile:1.7
# Build: compile with the Maven wrapper, then explode the Boot jar into layers so dependency layers
# are cached across application-only changes.
# Bytecode is platform-independent: compile on the native builder, even for arm64 images.
FROM --platform=$BUILDPLATFORM eclipse-temurin:21.0.12.1_1-jdk-alpine AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline
COPY src src
# Tests run in CI (they need Docker for Testcontainers), not inside the image build.
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package \
 && java -Djarmode=tools -jar target/finflow-backend-*.jar extract --layers --launcher --destination /layers

FROM eclipse-temurin:21.0.12.1_1-jre-alpine
RUN addgroup -S finflow && adduser -S -G finflow finflow
WORKDIR /app
COPY --from=build /layers/dependencies/ ./
COPY --from=build /layers/spring-boot-loader/ ./
COPY --from=build /layers/snapshot-dependencies/ ./
COPY --from=build /layers/application/ ./
USER finflow
EXPOSE 8080
# Heap sized from the container memory limit; crash fast on OOM so the orchestrator restarts us.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError" \
    FINFLOW_LOG_FORMAT=ecs
HEALTHCHECK --interval=15s --timeout=3s --start-period=90s --retries=5 \
  CMD wget -qO- http://127.0.0.1:8080/actuator/health/readiness >/dev/null || exit 1
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
