#!/usr/bin/env bash
# CI-only SDK bootstrap. A partially downloaded Google API system image
# previously failed with "Archive is not a ZIP archive" and blocked a green
# main release; retry SDK transport, NEVER bypass Android runtime acceptance.
set -Eeuo pipefail
image="system-images;android-34;google_apis;x86_64"
tool="${ANDROID_HOME:?ANDROID_HOME is required}/cmdline-tools/latest/bin/sdkmanager"
if [[ ! -x "$tool" ]]; then
  tool="$(command -v sdkmanager)"
fi
[[ -x "$tool" ]] || { echo "::error::Android sdkmanager unavailable"; exit 1; }
for attempt in 1 2 3; do
  echo "Android 14 system image installation attempt $attempt/3"
  if "$tool" --install "$image" > "${RUNNER_TEMP:-/tmp}/lanu-sdk-image-install.log" 2>&1; then
    echo "Android 14 Google APIs image ready (verified by sdkmanager)."
    exit 0
  fi
  echo "::warning::Android SDK image download/install failed on attempt $attempt; retrying clean transport cache"
  # SDK downloader cache is runner-only, not any app or user database.
  if [[ -d "$HOME/.android/cache" ]]; then
    find "$HOME/.android/cache" -maxdepth 1 -type f -delete
  fi
  if [[ "$attempt" -lt 3 ]]; then sleep 5; fi
done
echo "::error::Android 14 image did not install after three tries; release is blocked."
exit 1
