#!/usr/bin/env bash
# No GitHub network calls and no real APK: verifies bounded publication fallback.
set -Eeuo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT
mkdir -p "$tmp/bin" "$tmp/state"
for artifact in apk sha signer; do
  printf 'DUMMY_%s\n' "$artifact" > "$tmp/$artifact"
done
cat > "$tmp/bin/gh" <<'MOCK'
#!/usr/bin/env bash
set -Eeuo pipefail
case "$1 $2" in
  "api "*)
    [[ -f "$MOCK_STATE/created" ]] || exit 22
    cat "$MOCK_STATE/target"
    ;;
  "release create")
    echo 1 >> "$MOCK_STATE/creates"
    if [[ "$MOCK_SCENARIO" == "fail_once" && ! -f "$MOCK_STATE/first_error" ]]; then
      touch "$MOCK_STATE/first_error"
      echo 'HTTP 500 dummy' >&2
      exit 1
    fi
    if [[ "$MOCK_SCENARIO" == "after_create_error" && ! -f "$MOCK_STATE/first_error" ]]; then
      touch "$MOCK_STATE/first_error" "$MOCK_STATE/created"
      printf '%s\n' "$GITHUB_SHA" > "$MOCK_STATE/target"
      echo 'HTTP 500 after remote creation dummy' >&2
      exit 1
    fi
    touch "$MOCK_STATE/created"
    printf '%s\n' "$GITHUB_SHA" > "$MOCK_STATE/target"
    ;;
  "release upload")
    touch "$MOCK_STATE/uploaded"
    ;;
  *)
    echo 'Unexpected simulated GitHub command' >&2
    exit 2
    ;;
esac
MOCK
chmod 700 "$tmp/bin/gh"
export GITHUB_SHA="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
export GITHUB_REPOSITORY="kanunal99-jpg/LANU-Hesap-Makinesi"
export APK_PATH="$tmp/apk" CHECKSUM_PATH="$tmp/sha" SIGNER_PATH="$tmp/signer"
export MOCK_STATE="$tmp/state"
export PATH="$tmp/bin:$PATH"

reset_state() { rm -f "$MOCK_STATE/"*; }
reset_state
export MOCK_SCENARIO="fail_once"
bash "$root/scripts/publish_verified_release_retry.sh" "v1.1.0-signed.987" "dummy" > "$tmp/out" 2>&1
[[ -f "$MOCK_STATE/created" ]] || exit 1
[[ "$(wc -l < "$MOCK_STATE/creates")" == 2 ]] || exit 1

reset_state
export MOCK_SCENARIO="after_create_error"
bash "$root/scripts/publish_verified_release_retry.sh" "v1.1.0-signed.988" "dummy" > "$tmp/out" 2>&1
[[ -f "$MOCK_STATE/uploaded" ]] || exit 1
[[ "$(wc -l < "$MOCK_STATE/creates")" == 1 ]] || exit 1

reset_state
export MOCK_SCENARIO="success"
bash "$root/scripts/publish_verified_release_retry.sh" "v1.2.0-signed.990" "verified 1.2" > "$tmp/out" 2>&1
[[ -f "$MOCK_STATE/created" ]] || exit 1
[[ "$(wc -l < "$MOCK_STATE/creates")" == 1 ]] || exit 1

# Stable 1.2 is allowed; unknown/newer tags and debug labels must never
# be published by a malformed or unreviewed workflow change.
for invalid in "v1.2.0-debug.991" "v1.3.0-signed.991" "v2.0.0-signed.991" "v1.1.0-debug.991" "../v1.2.0-signed.991"; do
  reset_state
  if bash "$root/scripts/publish_verified_release_retry.sh" "$invalid" "not-allowed" > "$tmp/out" 2>&1; then
    echo "Unexpected unapproved release tag was accepted: $invalid" >&2
    exit 1
  fi
  [[ ! -f "$MOCK_STATE/created" ]] || exit 1
done

reset_state
export MOCK_SCENARIO="wrong_target"
touch "$MOCK_STATE/created"
printf 'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb\n' > "$MOCK_STATE/target"
if bash "$root/scripts/publish_verified_release_retry.sh" "v1.1.0-signed.989" "dummy" > "$tmp/out" 2>&1; then
  echo 'Foreign commit release tag was wrongly accepted.' >&2
  exit 1
fi
[[ ! -f "$MOCK_STATE/uploaded" ]] || exit 1
echo 'Dummy GitHub release HTTP500 recovery, 1.2 OTA tags, allowlist and foreign-tag rejection: PASS'
