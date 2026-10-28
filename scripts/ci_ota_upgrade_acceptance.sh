#!/usr/bin/env bash
# Real Android emulator OTA: previously published stable APK installed, Room v4
# and Keystore identity seeded by instrumentation, then candidate applied with
# adb install -r (same-package, same-signer, NO uninstall or pm clear).
set -Eeuo pipefail
if [[ "$#" -ne 5 ]]; then
  echo "usage: $0 baseline.apk new.apk signed-test.apk baseline-version new-version" >&2
  exit 2
fi
old="$1"; candidate="$2"; tests="$3"; old_version="$4"; new_version="$5"
for file in "$old" "$candidate" "$tests"; do
  [[ -s "$file" ]] || { echo "::error::OTA test APK artifact missing" >&2; exit 1; }
done
[[ "$old_version" =~ ^[0-9]+$ && "$new_version" =~ ^[0-9]+$ ]] || exit 2
(( new_version > old_version )) || {
  echo "::error::OTA versionCode must strictly increase"; exit 1;
}
package="com.aistudio.lanucalc.kxwzqm"
runner="$package.test/androidx.test.runner.AndroidJUnitRunner"
aapt="$(find "${ANDROID_HOME:?}/build-tools" -mindepth 2 -maxdepth 2 -name aapt -type f | sort -V | tail -n 1)"
apksigner="$(find "$ANDROID_HOME/build-tools" -mindepth 2 -maxdepth 2 -name apksigner -type f | sort -V | tail -n 1)"
[[ -x "$aapt" && -x "$apksigner" ]] || exit 1
pkg() { "$aapt" dump badging "$1" | sed -nE "s/^package: name='([^']+)'.*/\\1/p" | head -1; }
ver() { "$aapt" dump badging "$1" | sed -nE "s/^package:.*versionCode='([0-9]+)'.*/\\1/p" | head -1; }
cert() {
 "$apksigner" verify --print-certs "$1" |
 sed -nE 's/.*certificate SHA-256 digest:[[:space:]]*([0-9a-fA-F:]+).*/\\1/p' |
 head -1 | tr -d ':' | tr '[:upper:]' '[:lower:]'
}
[[ "$(pkg "$old")" == "$package" && "$(pkg "$candidate")" == "$package" ]] ||
  { echo "::error::OTA package identity changed"; exit 1; }
[[ "$(ver "$old")" == "$old_version" && "$(ver "$candidate")" == "$new_version" ]] ||
  { echo "::error::OTA version mismatch"; exit 1; }
old_cert="$(cert "$old")"; current_cert="$(cert "$candidate")"; test_cert="$(cert "$tests")"
[[ "$old_cert" =~ ^[a-f0-9]{64}$ && "$current_cert" == "$old_cert" && "$test_cert" == "$old_cert" ]] ||
  { echo "::error::OTA signer mismatch; preserve previous installation"; exit 1; }

run_probe() {
  local phase="$1" version="$2" method="$3"
  local result
  result="$(adb shell am instrument -w -r \
    -e lanu_ota_ci isolated-emulator-v1 \
    -e lanu_ota_phase "$phase" \
    -e lanu_expected_version "$version" \
    -e class "com.example.RoomActualOtaUpgradeTest#$method" \
    "$runner")" || { echo "::error::OTA instrument failed in phase $phase"; exit 1; }
  # am instrument may exit 0 even if a JUnit assertion failed.
  if ! grep -Eq 'OK \(1 test\)' <<< "$result" ||
     grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|INSTRUMENTATION_CODE: 0' <<< "$result"; then
    echo "::error::OTA data preservation assertion failed in $phase phase"
    exit 1
  fi
  echo "OTA $phase: one complete Android instrumentation test passed."
}
echo "Installing the EXACT previously published stable APK into fresh emulator"
adb install -r "$old" > /dev/null
adb install -r "$tests" > /dev/null
run_probe seed "$old_version" seedPreviouslyInstalledStableRelease
echo "Applying the REAL candidate upgrade with adb install -r; no data clear"
adb install -r "$candidate" > /dev/null
run_probe verify "$new_version" verifySameAccountAndAllSevenTablesAfterRealApkReplacement
echo "OTA Android 14: all seven Room v4 tables, encrypted queued message, PIN and Keystore identity survived signed APK replacement."
