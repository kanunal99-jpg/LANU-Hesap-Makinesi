#!/usr/bin/env bash
# CI-only launcher for the exact Gradle installed by setup-gradle.
# The repository has no wrapper. Avoid a redundant network HEAD request just
# to generate one after the pinned distribution is already installed.
set -Eeuo pipefail
[[ "${CI:-}" == true ]] || { echo "FAIL: CI Gradle preparation only"; exit 1; }
[[ ! -L ./gradlew ]] || { echo "FAIL: unexpected Gradle launcher link"; exit 1; }
if [[ -e ./gradlew ]]; then
  chmod +x ./gradlew
  ./gradlew --version | grep -Fxq 'Gradle 9.3.1'
  echo "PASS: existing Gradle 9.3.1 launcher retained"
  exit 0
fi
gradle_bin="$(command -v gradle)" || { echo "FAIL: installed Gradle missing"; exit 1; }
"$gradle_bin" --version | grep -Fxq 'Gradle 9.3.1'
launcher="$(mktemp ./.lanu-gradle.XXXXXX)"
trap 'rm -f "$launcher"' EXIT
printf '#!/usr/bin/env bash\nset -Eeuo pipefail\nexec %q "$@"\n' "$gradle_bin" > "$launcher"
chmod 700 "$launcher"
mv "$launcher" ./gradlew
echo "PASS: CI launcher uses installed Gradle 9.3.1"
