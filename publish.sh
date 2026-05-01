#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUILD_GRADLE="$SCRIPT_DIR/anchorkit-android-sdk/build.gradle"

# ── Read version from build.gradle ─────────────────────────────────────────
VERSION=$(grep "def sdkVersion" "$BUILD_GRADLE" | sed "s/.*'\(.*\)'.*/\1/")

if [[ -z "$VERSION" ]]; then
    echo "ERROR: Could not read sdkVersion from anchorkit-android-sdk/build.gradle" >&2
    exit 1
fi

echo "==> AnchorKit SDK  v${VERSION}"
echo "==> Upload only — you must publish manually at central.sonatype.com"
echo ""

# ── Check required signing/credentials properties ──────────────────────────
GRADLE_PROPS="$HOME/.gradle/gradle.properties"
MISSING=()
for key in signing.keyId signing.password signing.secretKeyRingFile mavenCentralUsername mavenCentralPassword; do
    if ! grep -q "^${key}=" "$GRADLE_PROPS" 2>/dev/null; then
        MISSING+=("$key")
    fi
done

if [[ ${#MISSING[@]} -gt 0 ]]; then
    echo "ERROR: Missing required properties in ~/.gradle/gradle.properties:" >&2
    for k in "${MISSING[@]}"; do echo "  - $k" >&2; done
    exit 1
fi

# ── Check secring.gpg exists ────────────────────────────────────────────────
SECRING=$(grep "^signing.secretKeyRingFile=" "$GRADLE_PROPS" | cut -d= -f2-)
if [[ ! -f "$SECRING" ]]; then
    echo "ERROR: GPG secring not found at $SECRING" >&2
    echo "       Run: gpg --export-secret-keys $(grep '^signing.keyId=' "$GRADLE_PROPS" | cut -d= -f2) > $SECRING" >&2
    exit 1
fi

# ── Clean previous build artifacts ─────────────────────────────────────────
echo "==> Cleaning previous build..."
rm -rf "$SCRIPT_DIR/anchorkit-android-sdk/build"

# ── Publish to Central Portal (upload only, stays in VALIDATED state) ──────
echo "==> Building and uploading anchorkit-sdk-${VERSION} to Maven Central Portal..."
echo ""
cd "$SCRIPT_DIR"
./gradlew :anchorkit-android-sdk:publishToMavenCentral --no-configuration-cache

echo ""
echo "==> Done. Deployment is in VALIDATED state."
echo "    Review and publish at: https://central.sonatype.com/publishing/deployments"
echo "    Artifact: net.anchorkit:anchorkit-sdk:${VERSION}"
