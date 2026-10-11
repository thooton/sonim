#!/usr/bin/env bash
set -euo pipefail

root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
export JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk}"

unsigned="$root/android/app/build/outputs/apk/release/app-release-unsigned.apk"
output="$root/Canvas-XP3-release.apk"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/android-sdk}}"
signer="${TODO_APKSIGNER:-$sdk/build-tools/35.0.1/apksigner}"
keystore="${TODO_KEYSTORE:-$HOME/.android/debug.keystore}"
: "${TODO_STORE_PASSWORD:=android}"
: "${TODO_KEY_PASSWORD:=$TODO_STORE_PASSWORD}"
export TODO_STORE_PASSWORD TODO_KEY_PASSWORD

"$root/android/gradlew" -p "$root/android" :app:assembleRelease

signed="$(mktemp "${TMPDIR:-/tmp}/canvas-xp3-release.XXXXXX.apk")"
trap 'rm -f "$signed"' EXIT
"$signer" sign --ks "$keystore" --ks-key-alias "${TODO_KEY_ALIAS:-androiddebugkey}" \
  --ks-pass env:TODO_STORE_PASSWORD --key-pass env:TODO_KEY_PASSWORD \
  --out "$signed" "$unsigned"
"$signer" verify "$signed"
cp "$signed" "$output"
echo "Release APK: $output"
