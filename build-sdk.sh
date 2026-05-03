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
echo "==> Build only — no upload or publishing"
echo ""

# ── Clean previous build artifacts ─────────────────────────────────────────
echo "==> Cleaning previous build..."
rm -rf "$SCRIPT_DIR/anchorkit-android-sdk/build"

# ── Build release AAR ───────────────────────────────────────────────────────
echo "==> Building anchorkit-sdk-${VERSION}..."
echo ""
cd "$SCRIPT_DIR"
./gradlew :anchorkit-android-sdk:assembleRelease

AAR="$SCRIPT_DIR/anchorkit-android-sdk/build/outputs/aar/anchorkit-android-sdk-release.aar"
echo ""
echo "==> Build complete."
echo "    AAR: $AAR"
