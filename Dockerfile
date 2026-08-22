# syntax=docker/dockerfile:1.7

FROM eclipse-temurin:21-jdk-alpine@sha256:6ea5548706b60ac0a602eaf48af74792cbab012d90e811ca8db6184b16b5c3d6 AS build
WORKDIR /workspace

COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle/wrapper/ gradle/wrapper/
RUN chmod 0755 gradlew && ./gradlew --no-daemon dependencies

COPY src/main/ src/main/
RUN ./gradlew --no-daemon clean bootJar \
    && cp "$(find build/libs -maxdepth 1 -type f -name '*.jar' ! -name '*-plain.jar' -print -quit)" /workspace/app.jar

FROM eclipse-temurin:21-jre-alpine@sha256:974b08960c5d96694c780e65b2d5705268ab1e1ca1a0dd0caf4ba6c3fe34d699
ARG VCS_REF
RUN case "${VCS_REF}" in \
        ""|*[!0-9a-f]*) exit 1 ;; \
    esac \
    && test "${#VCS_REF}" -eq 40

LABEL org.opencontainers.image.source="https://github.com/crabitteam2/crabit-backend" \
      org.opencontainers.image.revision="${VCS_REF}"

WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/app.jar /app/app.jar

USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
