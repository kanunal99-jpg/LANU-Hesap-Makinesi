#!/usr/bin/env bash
# Android Emulator Runner executes each YAML script line in a separate shell.
# Keep the failure-handling block inside one checked-in Bash script.
set -Eeuo pipefail

if ./gradlew -PlanuDeviceBuildType=release :app:connectedReleaseAndroidTest --no-daemon --stacktrace; then
  printf '%s\n' 'Release Android instrumentation passed.'
  exit 0
fi

# Never print general logcat: it could include SDP, candidates or private data.
# Only fixed LANU-RTC-SMOKE stage labels and native/JNI fatal exception types
# from this disposable emulator test may reach GitHub Actions logs.
printf '%s\n' '::group::Bounded native WebRTC crash diagnostics'
(adb logcat -d -b main -b system -b crash -t 1500 2>/dev/null || true) |
  grep -E 'LANU-RTC-SMOKE|FATAL EXCEPTION|Fatal error in:|Check failed:|JNI DETECTED ERROR|JNI exception|Fatal signal|NoSuchMethodError|NoClassDefFoundError|ClassNotFoundException|UnsatisfiedLinkError' |
  sed -E 's/(a=candidate:|a=fingerprint:|a=ice-ufrag:|a=ice-pwd:)[^ ]+/[redacted]/g' |
  tail -n 100 || true
printf '%s\n' '::endgroup::'
exit 1
