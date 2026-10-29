#!/usr/bin/env bash
# Real Android emulator OTA: previously published stable APK installed, Room v4
# and Keystore identity seeded by instrumentation, then candidate applied with
# adb install -r (same-package, same-signer, NO uninstall or pm clear).
set -Eeuo pipefail
if [[ "$#" -ne 6 ]]; then
  echo "usage: $0 baseline.apk new.apk old-signed-test.apk new-signed-test.apk baseline-version new-version" >&2
  exit 2
fi
old="$1"; candidate="$2"; old_tests="$3"; new_tests="$4"; old_version="$5"; new_version="$6"
for file in "$old" "$candidate" "$old_tests" "$new_tests"; do
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
 sed -nE 's/.*certificate SHA-256 digest:[[:space:]]*([0-9a-fA-F:]+).*/\1/p' |
 head -1 | tr -d ':' | tr '[:upper:]' '[:lower:]'
}
[[ "$(pkg "$old")" == "$package" && "$(pkg "$candidate")" == "$package" ]] ||
  { echo "::error::OTA package identity changed"; exit 1; }
[[ "$(ver "$old")" == "$old_version" && "$(ver "$candidate")" == "$new_version" ]] ||
  { echo "::error::OTA version mismatch"; exit 1; }
old_cert="$(cert "$old")"; current_cert="$(cert "$candidate")"
old_test_cert="$(cert "$old_tests")"; new_test_cert="$(cert "$new_tests")"
[[ "$(pkg "$old_tests")" == "$package.test" && "$(pkg "$new_tests")" == "$package.test" ]] ||
  { echo "::error::Instrumentation package identity mismatch"; exit 1; }
# Fail closed, but identify WHICH of the four signed APKs differs.
# Do not echo certificate fingerprints or keystore secrets in GitHub Actions.
require_signer_digest() {
  local label="$1" digest="$2"
  if [[ ! "$digest" =~ ^[a-f0-9]{64}$ ]]; then
    echo "::error::OTA certificate unreadable for $label; preserve previous installation" >&2
    exit 1
  fi
}
require_signer_digest "published baseline" "$old_cert"
require_signer_digest "candidate release" "$current_cert"
require_signer_digest "baseline instrumentation" "$old_test_cert"
require_signer_digest "candidate instrumentation" "$new_test_cert"
if [[ "$current_cert" != "$old_cert" ]]; then
  echo "::error::OTA signer mismatch: candidate release vs published baseline; preserve previous installation" >&2
  exit 1
fi
if [[ "$old_test_cert" != "$old_cert" ]]; then
  echo "::error::OTA signer mismatch: baseline instrumentation vs published baseline; preserve previous installation" >&2
  exit 1
fi
if [[ "$new_test_cert" != "$old_cert" ]]; then
  echo "::error::OTA signer mismatch: candidate instrumentation vs published baseline; preserve previous installation" >&2
  exit 1
fi

# Instrumentation can include exception messages, stack traces, app data or
# ciphertext. On failure print ONLY fixed checkpoints and exception CLASS names.
# Never dump raw adb output into a public GitHub Actions log.
emit_safe_probe_diagnostics() {
  local phase="$1" output="$2" line checkpoint exception_class
  while IFS= read -r line; do
    case "$line" in
      "INSTRUMENTATION_STATUS: lanu_ota_checkpoint="*)
        checkpoint="${line#*=}"
        case "$checkpoint" in
          gate|prefs-empty|identity-store-created|identity-store-empty|fresh-identity|identity-crypto|database-open|seven-tables-written|sentinel-written|pending-verified)
            echo "OTA $phase checkpoint: $checkpoint" ;;
        esac ;;
      "INSTRUMENTATION_STATUS_CODE: -1"|"INSTRUMENTATION_STATUS_CODE: -2"|"INSTRUMENTATION_CODE: 0"|"INSTRUMENTATION_CODE: -1")
        echo "OTA $phase runner: $line" ;;
    esac
  done <<< "$output"
  exception_class="$(grep -Eo '(java|android|kotlin)\.[A-Za-z0-9_.]*(Exception|Error)' <<< "$output" | sort -u | head -n 5 || true)"
  if [[ -n "$exception_class" ]]; then
    while IFS= read -r line; do
      echo "OTA $phase exception type: $line"
    done <<< "$exception_class"
  fi
  # Classify known source-code ABI symbols only. NEVER print the exception
  # message/stack: it can contain account identifiers or private data.
  if grep -Fq 'java.lang.NoSuchMethodError' <<< "$output"; then
    case "$output" in
      *EncryptedMessagingIdentityStore*) echo "OTA $phase ABI hint: identity-store method" ;;
      *UserCodes*) echo "OTA $phase ABI hint: identity-generator method" ;;
      *PinCredentialCodec*) echo "OTA $phase ABI hint: PIN-codec method" ;;
      *PeerMessageCrypto*) echo "OTA $phase ABI hint: E2EE method" ;;
      *SettingsEntity*) echo "OTA $phase ABI hint: Room settings entity method" ;;
    esac
  fi
}

run_probe() {
  local phase="$1" version="$2" method="$3"
  local result rc=0
  result="$(adb shell am instrument -w -r \
    -e lanu_ota_ci isolated-emulator-v1 \
    -e lanu_ota_phase "$phase" \
    -e lanu_expected_version "$version" \
    -e class "com.example.RoomActualOtaUpgradeTest#$method" \
    "$runner" 2>&1)" || rc=$?
  # am instrument may exit 0 even if a JUnit assertion failed.
  if (( rc != 0 )) ||
     ! grep -Eq 'OK \(1 test\)' <<< "$result" ||
     ! grep -Fq "lanu_ota_verified_phase=$phase" <<< "$result" ||
     grep -Eq 'FAILURES|INSTRUMENTATION_FAILED|INSTRUMENTATION_CODE: 0' <<< "$result"; then
    echo "::error::OTA data preservation assertion failed in $phase phase"
    emit_safe_probe_diagnostics "$phase" "$result"
    exit 1
  fi
  echo "OTA $phase: one complete Android instrumentation test passed."
}
echo "Installing the EXACT previously published stable APK into fresh emulator"
adb install -r "$old" > /dev/null
adb install -r "$old_tests" > /dev/null
run_probe seed "$old_version" seedPreviouslyInstalledStableRelease
echo "Applying the REAL candidate upgrade with adb install -r; no data clear"
adb install -r "$candidate" > /dev/null
# The previous phase links to the previous R8-minified APK. Rebuild the test
# against the new code for the new APK ABI; never mask an R8 method mismatch.
adb install -r "$new_tests" > /dev/null
run_probe verify "$new_version" verifySameAccountAndAllSevenTablesAfterRealApkReplacement
echo "OTA Android 14: all seven Room v4 tables, encrypted queued message, PIN and Keystore identity survived signed APK replacement."
