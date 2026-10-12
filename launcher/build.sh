#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
BUILD_DIR="$(mktemp -d "${TMPDIR:-/tmp}/appdrawer-build.XXXXXX")"
SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/android-sdk}}"
PLATFORM_DIR="$SDK_DIR/platforms/android-35"
TOOLS_DIR="$SDK_DIR/build-tools/35.0.1"
ANDROID_JAR="$PLATFORM_DIR/android.jar"
KEYSTORE="$PROJECT_DIR/signing/app-drawer.p12"
KEY_ALIAS="appdrawer"
KEY_PASSWORD="appdrawer"

trap 'rm -rf "$BUILD_DIR"' EXIT

for tool in aapt javac zip "$TOOLS_DIR/d8" "$TOOLS_DIR/zipalign" "$TOOLS_DIR/apksigner"; do
    if ! command -v "$tool" >/dev/null 2>&1 && [[ ! -x "$tool" ]]; then
        echo "Missing build tool: $tool" >&2
        exit 1
    fi
done

if [[ ! -f "$ANDROID_JAR" ]]; then
    echo "Missing Android platform: $ANDROID_JAR" >&2
    exit 1
fi

mkdir -p "$BUILD_DIR/gen" "$BUILD_DIR/classes" "$BUILD_DIR/dex" "$PROJECT_DIR/signing"

if [[ ! -f "$KEYSTORE" ]]; then
    keytool -genkeypair -noprompt \
        -keystore "$KEYSTORE" -storetype PKCS12 \
        -storepass "$KEY_PASSWORD" -keypass "$KEY_PASSWORD" \
        -alias "$KEY_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=App Drawer, OU=Local, O=Custom, C=US"
fi

aapt package -f -m \
    -J "$BUILD_DIR/gen" \
    -M "$PROJECT_DIR/AndroidManifest.xml" \
    -S "$PROJECT_DIR/res" \
    -I "$ANDROID_JAR" \
    -F "$BUILD_DIR/resources.apk"

javac -source 8 -target 8 \
    -bootclasspath "$ANDROID_JAR" \
    -d "$BUILD_DIR/classes" \
    "$BUILD_DIR/gen/com/custom/appdrawer/R.java" \
    "$PROJECT_DIR/src/com/custom/appdrawer/AppDrawerActivity.java"

"$TOOLS_DIR/d8" \
    --release \
    --min-api 27 \
    --lib "$ANDROID_JAR" \
    --output "$BUILD_DIR/dex" \
    "$BUILD_DIR/classes/com/custom/appdrawer/"*.class

cp "$BUILD_DIR/resources.apk" "$BUILD_DIR/AppDrawer-unsigned.apk"
zip -q -j "$BUILD_DIR/AppDrawer-unsigned.apk" "$BUILD_DIR/dex/classes.dex"

"$TOOLS_DIR/zipalign" -f -p 4 \
    "$BUILD_DIR/AppDrawer-unsigned.apk" \
    "$BUILD_DIR/AppDrawer-aligned.apk"

"$TOOLS_DIR/apksigner" sign \
    --v4-signing-enabled false \
    --ks "$KEYSTORE" \
    --ks-key-alias "$KEY_ALIAS" \
    --ks-pass "pass:$KEY_PASSWORD" \
    --key-pass "pass:$KEY_PASSWORD" \
    --out "$PROJECT_DIR/AppDrawer.apk" \
    "$BUILD_DIR/AppDrawer-aligned.apk"

"$TOOLS_DIR/apksigner" verify --verbose "$PROJECT_DIR/AppDrawer.apk"
echo "Built $PROJECT_DIR/AppDrawer.apk"
