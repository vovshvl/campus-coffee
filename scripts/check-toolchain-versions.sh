#!/usr/bin/env bash
# Fail if the Java major version drifts between the files that pin it independently. The Gradle
# toolchain and the Kotlin jvmTarget read it from the version catalog (gradle/libs.versions.toml,
# `java`), which this script treats as the source of truth; mise.toml (local + CI tool provisioning)
# and the Dockerfile runtime image must agree with it. Run locally or in CI; emits GitHub Actions
# error annotations on mismatch.
set -euo pipefail

cd "$(dirname "$0")/.."

fail() {
  echo "::error::$*" >&2
  exit 1
}

# Source of truth: the catalog entry the convention plugins resolve (java = "25").
catalog=$(sed -n 's/^java = "\([0-9][0-9]*\)".*/\1/p' gradle/libs.versions.toml)
[ -n "$catalog" ] || fail "could not read the 'java' version from gradle/libs.versions.toml"

# mise.toml: java = 'temurin-25' (distribution-NN); capture the trailing major.
mise=$(sed -n "s/^java *= *.*-\([0-9][0-9]*\).*/\1/p" mise.toml)
[ -n "$mise" ] || fail "could not read the Java version from mise.toml"

# Dockerfile runtime stage: FROM eclipse-temurin:25-jre-...
docker=$(sed -n 's/^FROM eclipse-temurin:\([0-9][0-9]*\).*/\1/p' Dockerfile)
[ -n "$docker" ] || fail "could not read the Java version from the Dockerfile runtime image"

echo "Java major version — catalog=$catalog mise=$mise dockerfile=$docker"

[ "$catalog" = "$mise" ] || fail "mise.toml Java version ($mise) differs from the version catalog ($catalog)"
[ "$catalog" = "$docker" ] || fail "Dockerfile Java version ($docker) differs from the version catalog ($catalog)"

echo "Java versions are consistent ($catalog)."
