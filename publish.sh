#!/usr/bin/env bash
# Publish anchorkit-sdk to Maven Central via the Sonatype Central Portal REST API.
# Reads sonatypeUsername and sonatypePassword from ~/.gradle/gradle.properties.
# Run from the repo root: bash publish.sh
set -euo pipefail

GRADLE_PROPS="$HOME/.gradle/gradle.properties"
USERNAME=$(grep '^sonatypeUsername=' "$GRADLE_PROPS" | cut -d= -f2-)
PASSWORD=$(grep '^sonatypePassword=' "$GRADLE_PROPS" | cut -d= -f2-)

if [[ -z "$USERNAME" || -z "$PASSWORD" ]]; then
    echo "Error: sonatypeUsername and sonatypePassword must be set in ~/.gradle/gradle.properties"
    exit 1
fi

export ORG_GRADLE_PROJECT_signingKey="$(gpg --export-secret-keys --armor 7DE24CE5)"

echo "==> Building and signing release artifacts..."
# Publish to the LocalBundle repo (not MavenLocal) so Gradle generates md5/sha1.
./gradlew :anchorkit-android-sdk:publishReleasePublicationToLocalBundleRepository

BUNDLE="/tmp/anchorkit-bundle.zip"
rm -f "$BUNDLE"

BUNDLE_REPO="$PWD/anchorkit-android-sdk/build/maven-bundle"

echo "==> Bundling artifacts..."
# Exclude .module files — Gradle metadata that confuses Central Portal.
(cd "$BUNDLE_REPO" && zip -r "$BUNDLE" net/anchorkit/anchorkit-sdk/1.0.1/ \
    --exclude "*.module" --exclude "*.module.asc")

TOKEN=$(printf '%s:%s' "$USERNAME" "$PASSWORD" | base64 -w0)

echo "==> Uploading to Central Portal..."
curl --request POST \
    --fail-with-body \
    --header "Authorization: Bearer $TOKEN" \
    --form "bundle=@${BUNDLE}" \
    "https://central.sonatype.com/api/v1/publisher/upload?name=anchorkit-sdk-1.0.1&publishingType=USER_MANAGED"

echo ""
echo "==> Upload complete. Review and release at https://central.sonatype.com"
