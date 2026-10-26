#!/usr/bin/env bash
# READ-ONLY: prove an official LANU release could replace the APK currently
# installed on one ADB-authorized Android device, before invoking PackageInstaller.
# Never installs, uninstalls, overwrites app data, extracts keys or reads databases.
set -Eeuo pipefail
umask 077
export LC_ALL=C

if [[ $# -ne 3 ]]; then
  echo 'Usage: verify_existing_install_signer.sh app-debug.apk app-debug.apk.sha256 app-debug.apk.signer.txt' >&2
  exit 2
fi
candidate="$1"
checksum="$2"
signer_metadata="$3"
package='com.aistudio.lanucalc.kxwzqm'

for bin in adb aapt apksigner sha256sum mktemp sed grep tr; do
  command -v "$bin" >/dev/null 2>&1 || {
    echo "Missing Android upgrade verification tool: $bin" >&2; exit 1;
  }
done
for file in "$candidate" "$checksum" "$signer_metadata"; do
  [[ -f "$file" && ! -L "$file" && -s "$file" ]] || {
    echo 'Missing or unsafe signed release input.' >&2; exit 1;
  }
done
[[ $(stat -c '%s' "$candidate") -le 94371840 &&
   $(stat -c '%s' "$checksum") -le 256 &&
   $(stat -c '%s' "$signer_metadata") -le 1024 ]] || {
  echo 'Unexpected release input size.' >&2; exit 1;
}
hash_line="$(cat "$checksum")"
[[ "$hash_line" =~ ^([0-9a-f]{64})[[:space:]]+app-debug\.apk$ ]] || {
  echo 'Malformed pinned SHA-256 asset.' >&2; exit 1;
}
hash_expected="${BASH_REMATCH[1]}"
hash_actual="$(sha256sum "$candidate")"
hash_actual="${hash_actual%% *}"
[[ "$hash_actual" == "$hash_expected" ]] || {
  echo 'Release APK SHA-256 mismatch; refusing upgrade.' >&2; exit 1;
}
[[ $(grep -c '^signing_mode=stable$' "$signer_metadata" || true) == 1 &&
   $(grep -c '^apk=app-debug.apk$' "$signer_metadata" || true) == 1 &&
   $(grep -Ec '^certificate_sha256=[0-9a-f]{64}$' "$signer_metadata" || true) == 1 ]] || {
  echo 'Release signer metadata does not prove stable signing.' >&2; exit 1;
}
pinned="$(sed -nE 's/^certificate_sha256=([0-9a-f]{64})$/\1/p' "$signer_metadata")"

extract_signer() {
  local report
  report="$(apksigner verify --print-certs "$1")" || {
    echo 'APK signature verification failed.' >&2; return 1;
  }
  local digest
  digest="$(printf '%s\n' "$report" |
    sed -nE 's/^Signer #[0-9]+ certificate SHA-256 digest:[[:space:]]*([0-9a-fA-F:]+).*$/\1/p' |
    tr -d ':' | tr '[:upper:]' '[:lower:]')"
  [[ "$digest" =~ ^[0-9a-f]{64}$ ]] || {
    echo 'Expected exactly one verifiable APK signing certificate.' >&2; return 1;
  }
  printf '%s\n' "$digest"
}
new_cert="$(extract_signer "$candidate")"
[[ "$new_cert" == "$pinned" ]] || {
  echo 'Release signer does not match public signer metadata.' >&2; exit 1;
}

extract_version() {
  local manifest
  manifest="$(aapt dump badging "$1")" || return 1
  local row
  row="$(printf '%s\n' "$manifest" | grep -m 1 '^package:' || true)"
  [[ "$row" == "package: name='$package' "* ]] || {
    echo 'APK applicationId is not LANU; refusing upgrade.' >&2; return 1;
  }
  if [[ "$row" =~ versionCode=\'([0-9]{1,10})\' ]]; then
    printf '%s\n' "${BASH_REMATCH[1]}"
  else
    echo 'Unable to verify APK versionCode.' >&2; return 1;
  fi
}
new_version="$(extract_version "$candidate")"

device_state="$(adb get-state 2>/dev/null)" || {
  echo 'No single authorized Android device available via ADB.' >&2; exit 1;
}
[[ "$device_state" == device ]] || {
  echo 'ADB is not connected to an authorized Android device.' >&2; exit 1;
}
paths="$(adb shell pm path "$package" 2>/dev/null | tr -d '\r')" || {
  echo 'LANU is not installed on the ADB device.' >&2; exit 1;
}
# Android split APKs may be listed; only the base package is inspected.
base_path="$(printf '%s\n' "$paths" |
  sed -nE 's|^package:(/data/app/[^[:space:]]+/base\.apk)$|\1|p')"
[[ -n "$base_path" && "$base_path" != *$'\n'* ]] || {
  echo 'Unable to identify one installed LANU base APK.' >&2; exit 1;
}
scratch="$(mktemp -d)"
cleanup() { rm -rf -- "$scratch"; }
trap cleanup EXIT INT TERM
# This copies the public installed package APK only, not the app data directory.
adb pull "$base_path" "$scratch/installed.apk" >/dev/null 2>&1 || {
  echo 'Installed APK was not readable; verification failed closed.' >&2; exit 1;
}
installed_cert="$(extract_signer "$scratch/installed.apk")"
installed_version="$(extract_version "$scratch/installed.apk")"
[[ "$installed_cert" == "$pinned" ]] || {
  echo 'Installed APK signing certificate differs from the new stable release. Do NOT uninstall.' >&2; exit 1;
}
(( 10#$new_version > 10#$installed_version )) || {
  echo 'Candidate APK must have a strictly newer versionCode.' >&2; exit 1;
}
echo 'PASS: installed LANU APK and new release share the pinned signing certificate, and versionCode increases.'
echo 'Read-only check completed. No installation, PIN, conversations, keystore or user data were touched.'
