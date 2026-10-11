#!/usr/bin/env bash
set -euo pipefail

repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
android_dir="${repo_dir}/android"
unsigned_apk="${android_dir}/app/build/outputs/apk/release/app-release-unsigned.apk"
output_apk="${repo_dir}/Flashcards-XP3.apk"

# Gradle otherwise falls back to android/local.properties, which can retain a
# temporary SDK path from an older build. Keep both names set because Android
# tooling in this project checks both conventions.
android_sdk="${FLASHCARDS_ANDROID_SDK:-${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/opt/android-sdk}}}"
export ANDROID_HOME="${android_sdk}"
export ANDROID_SDK_ROOT="${android_sdk}"

if [[ ! -d "${android_sdk}" ]]; then
    echo "Could not find the Android SDK: ${android_sdk}" >&2
    echo "Set FLASHCARDS_ANDROID_SDK to the SDK root." >&2
    exit 1
fi

printf 'sdk.dir=%s\n' "${android_sdk}" > "${android_dir}/local.properties"

keystore="${FLASHCARDS_KEYSTORE:-${repo_dir}/../signing/debug.keystore}"
key_alias="${FLASHCARDS_KEY_ALIAS:-androiddebugkey}"
: "${FLASHCARDS_STORE_PASSWORD:=android}"
: "${FLASHCARDS_KEY_PASSWORD:=${FLASHCARDS_STORE_PASSWORD}}"
export FLASHCARDS_STORE_PASSWORD FLASHCARDS_KEY_PASSWORD

sdk_apksigner="${android_sdk}/build-tools/35.0.1/apksigner"
if [[ -x "${sdk_apksigner}" ]]; then
    default_apksigner="${sdk_apksigner}"
else
    default_apksigner="$(command -v apksigner || true)"
fi
apksigner_bin="${FLASHCARDS_APKSIGNER:-${default_apksigner}}"

if [[ ! -x "${android_dir}/gradlew" ]]; then
    echo "Missing executable Gradle wrapper: ${android_dir}/gradlew" >&2
    exit 1
fi
if [[ -z "${apksigner_bin}" || ! -x "${apksigner_bin}" ]]; then
    echo "Could not find apksigner. Set FLASHCARDS_APKSIGNER to its path." >&2
    exit 1
fi
if [[ ! -f "${keystore}" ]]; then
    echo "Could not find signing key: ${keystore}" >&2
    echo "Set FLASHCARDS_KEYSTORE and FLASHCARDS_KEY_ALIAS to use another key." >&2
    exit 1
fi

echo "Building optimized release APK..."
(
    cd "${android_dir}"
    ./gradlew :app:assembleRelease \
        -x lintVitalRelease \
        -x lintVitalAnalyzeRelease \
        -x lintVitalReportRelease
)

if [[ ! -f "${unsigned_apk}" ]]; then
    echo "Release build did not produce ${unsigned_apk}" >&2
    exit 1
fi

signed_apk="$(mktemp "${TMPDIR:-/tmp}/flashcards-xp3-release.XXXXXX.apk")"
trap 'rm -f "${signed_apk}"' EXIT

echo "Signing release APK..."
"${apksigner_bin}" sign \
    --ks "${keystore}" \
    --ks-key-alias "${key_alias}" \
    --ks-pass env:FLASHCARDS_STORE_PASSWORD \
    --key-pass env:FLASHCARDS_KEY_PASSWORD \
    --out "${signed_apk}" \
    "${unsigned_apk}"

"${apksigner_bin}" verify --verbose "${signed_apk}"
cp "${signed_apk}" "${output_apk}"

echo
echo "Release APK: ${output_apk}"
sha256sum "${output_apk}"