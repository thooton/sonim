#!/usr/bin/env bash
# Installs the Android emulator and a headless system image into the
# user-writable SDK directory, then boots an AVD sized like a flip phone.
set -euo pipefail

SDK="${HOME}/android-sdk"
DL="${SDK}/.dl"

need() {
    if [[ ! -f "${DL}/${2}" ]]; then
        echo "Missing download: ${2}. Re-run the curl commands in the setup." >&2
        exit 1
    fi
}

echo "Extracting emulator..."
need "emulator" "emulator.zip"
unzip -q -o "${DL}/emulator.zip" -d "${SDK}"

echo "Extracting system image..."
need "sysimg35" "sysimg35.zip"
unzip -q -o "${DL}/sysimg35.zip" -d "${SDK}"

export ANDROID_HOME="${SDK}"
export ANDROID_SDK_ROOT="${SDK}"
export ANDROID_USER_HOME="${SDK}/.sdk"

EMU="${SDK}/emulator/emulator"
ADB="${SDK}/platform-tools/adb"
AVDMAN="${SDK}/cmdline-tools/latest/bin/avdmanager"

echo "Listing device profiles..."
"${AVDMAN}" list device 2>&1 | grep -iE "id:|name:" | grep -iE "qvga|hvga|2.7|n1" || true

DEVICE_ID=""
for cand in "2.7in QVGA" "3.2in HVGA"; do
    if "${AVDMAN}" list device 2>/dev/null | grep -qi "${cand}"; then
        DEVICE_ID="${cand}"
        break
    fi
done

AVD_NAME="xp3test"

echo "Creating AVD '${AVD_NAME}' (device: ${DEVICE_ID:-default})..."
if "${AVDMAN}" list avd 2>/dev/null | grep -q "Name: ${AVD_NAME}"; then
    echo "AVD already exists."
else
    if [[ -n "${DEVICE_ID}" ]]; then
        echo no | "${AVDMAN}" create avd -n "${AVD_NAME}" \
            -k "system-images;android-35;default;x86_64" \
            -d "${DEVICE_ID}" >/dev/null 2>&1 || true
    fi
    if [[ ! -d "${HOME}/.android/avd/${AVD_NAME}.avd" \
        && ! -d "${ANDROID_USER_HOME}/avd/${AVD_NAME}.avd" ]]; then
        echo no | "${AVDMAN}" create avd -n "${AVD_NAME}" \
            -k "system-images;android-35;default;x86_64" >/dev/null 2>&1 || true
    fi
fi

# Locate the AVD we just created (it may live under ~/.android/avd).
AVD_DIR=""
for d in "${HOME}/.android/avd/${AVD_NAME}.avd" "${ANDROID_USER_HOME}/avd/${AVD_NAME}.avd"; do
    if [[ -d "${d}" ]]; then AVD_DIR="${d}"; break; fi
done
if [[ -z "${AVD_DIR}" ]]; then
    echo "Could not find the created AVD." >&2
    exit 1
fi

# Flip-phone panel: 240x320 at mdpi (160 dpi), portrait.
CONFIG="${AVD_DIR}/config.ini"
: > "${CONFIG}"
cat >> "${CONFIG}" <<'CFG'
hw.lcd.width=240
hw.lcd.height=320
hw.lcd.density=160
hw.lcd.aspect=1.333333
hw.keyboard=yes
hw.mainKeys=no
hw.audioInput=no
hw.audioOutput=no
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader_indirect
hw.keyboard.charmap=qwerty2
disk.dataPartition.size=512M
vm.heapSize=128
image.sysdir.1=system-images/android-35/default/x86_64/
CFG

echo "Boot script ready. To run:"
echo "  ${EMU} -avd ${AVD_NAME} -no-window -no-boot-anim -no-audio -gpu swiftshader_indirect &"
echo "  ${ADB} wait-for-device"