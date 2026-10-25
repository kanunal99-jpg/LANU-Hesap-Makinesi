#!/usr/bin/env bash
# Android Emulator Runner executes each YAML script line in a separate shell.
# Keep the failure-handling block inside one checked-in Bash script.
set -Eeuo pipefail

if ./gradlew -PlanuDeviceBuildType=release :app:connectedReleaseAndroidTest --no-daemon --stacktrace; then
  printf '%s\n' 'Release Android instrumentation passed.'
  exit 0
fi

# Never dump the normal logcat buffer: SDP, ICE and user identities may appear there.
# Read only the Android crash buffer and select exception types / stack-frame names.
printf '%s\n' '::group::Filtered Android runtime crash diagnostics'
(adb logcat -d -b crash -t 300 2>/dev/null || true) |
  grep -E 'FATAL EXCEPTION|JNI DETECTED|Fatal signal|NoSuchMethodError|NoClassDefFoundError|UnsatisfiedLinkError|Caused by:|org\.webrtc|org\.jni_zero|at com\.example' |
  tail -n 80 || true
printf '%s\n' '::endgroup::'
exit 1
