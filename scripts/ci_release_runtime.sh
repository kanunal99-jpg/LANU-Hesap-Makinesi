#!/usr/bin/env bash
# Android Emulator Runner executes each YAML script line in a separate shell.
# Keep the failure-handling block inside one checked-in Bash script.
set -Eeuo pipefail

if ./gradlew -PlanuDeviceBuildType=release :app:connectedReleaseAndroidTest --no-daemon --stacktrace; then
  printf '%s\n' 'Release Android instrumentation passed.'
  exit 0
fi

# The native crash log can contain addresses, SDP or other sensitive strings.
# Pipe it directly into the fixed-code classifier; never print unfiltered logcat.
printf '%s\n' '::group::Safe WebRTC native failure explanation'
adb logcat -d -b main -b system -b crash -t 2400 2>/dev/null |
  python3 scripts/rtc_failure_diagnose.py || true
printf '%s\n' '::endgroup::'
exit 1
