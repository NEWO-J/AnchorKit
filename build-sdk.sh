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
rm -rf "$SCRIPT_DIR/demo-app/build"

# ── Build debug APK ─────────────────────────────────────────────────────────
echo "==> Building demo app APK..."
echo ""
cd "$SCRIPT_DIR"
./gradlew :demo-app:assembleDebug

APK="$SCRIPT_DIR/demo-app/build/outputs/apk/debug/demo-app-debug.apk"
echo ""
echo "==> Build complete."
echo "    APK: $APK"
