#!/usr/bin/env bash
# Publish only a previously verified, signer-checked APK. This is idempotent
# across transient GitHub HTTP 5xx failures and an already-created release.
set -Eeuo pipefail
tag="$1"
notes="$2"
[[ "$tag" =~ ^v1\.(0\.0-debug|1\.0-signed|2\.0-signed)\.[0-9]+$ ]] || {
  echo "::error::Unexpected APK release tag." >&2
  exit 1
}
[[ "$GITHUB_SHA" =~ ^[0-9a-f]{40}$ ]] || {
  echo "::error::Expected commit SHA missing." >&2
  exit 1
}
for artifact in "$APK_PATH" "$CHECKSUM_PATH" "$SIGNER_PATH"; do
  [[ -s "$artifact" ]] || { echo "::error::Verified asset missing." >&2; exit 1; }
done

# Earlier steps MUST validate signer continuity and SHA256. Do not skip them.
# If GitHub creates a release then returns HTTP 500, retry by querying it.
# A preexisting tag pointing to another commit is a hard security failure.
published=false
for attempt in 1 2 3 4; do
  if target="$(gh api "repos/$GITHUB_REPOSITORY/releases/tags/$tag" --jq '.target_commitish' 2>/dev/null)"; then
    if [[ "$target" != "$GITHUB_SHA" ]]; then
      echo "::error::Existing release points at a different commit. Aborting." >&2
      exit 1
    fi
    if gh release upload "$tag" \
      "$APK_PATH" "$CHECKSUM_PATH" "$SIGNER_PATH" --clobber; then
      published=true
      break
    fi
  elif gh release create "$tag" \
    "$APK_PATH" "$CHECKSUM_PATH" "$SIGNER_PATH" \
    --target "$GITHUB_SHA" \
    --title "LANU CALCULATOR $tag" \
    --notes "Commit: $GITHUB_SHA. $notes" \
    --prerelease; then
    published=true
    break
  fi
  if (( attempt < 4 )); then
    echo "::warning::Transient release API failure; retrying the same verified assets."
    sleep "$((attempt * 4))"
  fi
done
[[ "$published" == true ]] || {
  echo "::error::Release publication failed after bounded retries." >&2
  exit 1
}
echo "Verified release publication step completed; public download read-back follows."
