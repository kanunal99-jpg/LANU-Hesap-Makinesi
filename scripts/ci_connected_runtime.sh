#!/usr/bin/env bash
# Pass only disposable CI credentials; Android tests fail if fixture arguments are absent.
set -Eeuo pipefail
[[ "${CI:-}" == true && -n "${LANU_TURN_TEST_USER:-}" && -n "${LANU_TURN_TEST_PASSWORD:-}" ]] || {
  echo 'FAIL: authenticated local TURN fixture required'; exit 1;
}
case "${1:-}" in
  debug) task=:app:connectedDebugAndroidTest; variant=() ;;
  release) task=:app:connectedReleaseAndroidTest; variant=(-PlanuDeviceBuildType=release) ;;
  *) exit 2 ;;
esac
./gradlew "${variant[@]}" "$task" \
  -Pandroid.testInstrumentationRunnerArguments.lanu_turn_ci=true \
  -Pandroid.testInstrumentationRunnerArguments.lanu_turn_user="$LANU_TURN_TEST_USER" \
  -Pandroid.testInstrumentationRunnerArguments.lanu_turn_password="$LANU_TURN_TEST_PASSWORD" \
  --no-daemon --stacktrace
