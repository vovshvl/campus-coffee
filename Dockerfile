# Build stage — provision the JDK and Gradle from mise.toml, the single source of truth shared with
# CI (jdx/mise-action), instead of pinning their versions in a base image. This mirrors the CI build:
# mise puts the pinned JDK on PATH, Gradle runs on it, and its no-download toolchain (the version
# catalog's `java`) is satisfied by that running JVM.
FROM debian:stable-slim AS build
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*
RUN curl -fsSL https://mise.run | MISE_INSTALL_PATH=/usr/local/bin/mise sh
WORKDIR /app
# Copy the tool pins first so this layer is cached unless the pinned versions change. Install only the
# JDK and Gradle (not the gcloud/python entries, which the build does not need) to keep it lean.
COPY mise.toml .
RUN mise trust && mise install java gradle
COPY . .
RUN mise exec -- gradle :application:bootJar --no-daemon

# Runtime stage — the only place a Java version is written by hand; scripts/check-toolchain-versions.sh
# (run in CI) fails the build if its major diverges from the version catalog and mise.toml.
FROM eclipse-temurin:25-jre-alpine
RUN mkdir /opt/app
COPY --from=build /app/application/build/libs/application.jar /opt/app
WORKDIR /opt/app
ENTRYPOINT ["java", "-jar", "application.jar"]
EXPOSE 8080
