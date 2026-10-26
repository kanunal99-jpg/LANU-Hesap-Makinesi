#!/usr/bin/env bash
# Dummy-only self-test: no production key or password is accessed.
set -Eeuo pipefail
umask 077

for tool in script gpg tar cmp mktemp find; do
  command -v "$tool" >/dev/null || { echo "Missing test dependency: $tool" >&2; exit 1; }
done

root="$(cd "$(dirname "$0")/.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"' EXIT
home="$tmp/home"
mkdir -p -m 700 "$home/.lanu/signing"
printf 'DUMMY_KEYSTORE_FIXTURE_NOT_A_VALID_PKCS12_FILE\n' > "$home/.lanu/signing/lanu-stable.jks"
printf 'dummy-store-password-only\n' > "$home/.lanu/signing/store-password"
printf 'dummy-store-password-only\n' > "$home/.lanu/signing/key-password"
chmod 700 "$home/.lanu" "$home/.lanu/signing"
chmod 600 "$home/.lanu/signing/"*

# The dummy passphrase is intentionally public and is NEVER a real credential.
# The pseudo-terminal emulates an Android Termux terminal for the secure prompt.
dummy='LANU_PUBLIC_DUMMY_BACKUP_TEST_PASSWORD_2026'
printf '%s\n%s\n' "$dummy" "$dummy" |
  script -q -e -c "env HOME=$home GNUPGHOME=$home/.gnupg bash $root/scripts/backup_stable_signing_encrypted.sh" \
    /dev/null > "$tmp/pty.log" 2>&1 || {
    echo 'Dummy encrypted backup failed; see private test artifact.' >&2
    exit 1
  }
grep -q 'Encrypted backup and isolated restore verification passed.' "$tmp/pty.log" || {
  echo 'Backup script did not confirm isolated restore.' >&2
  exit 1
}
archive="$(find "$home/.lanu/backups" -maxdepth 1 -type f -name '*.gpg' -print -quit)"
[[ -n "$archive" && -s "$archive" ]] || {
  echo 'Encrypted backup file not created.' >&2
  exit 1
}
[[ "$(stat -c '%a' "$archive")" == 600 ]] || {
  echo 'Encrypted backup permissions must be private.' >&2
  exit 1
}
mkdir -p -m 700 "$tmp/restore"
gpg --quiet --batch --yes --pinentry-mode loopback --no-tty \
  --passphrase-fd 3 --decrypt "$archive" 3<<<"$dummy" |
  tar -xz -C "$tmp/restore" -f -
for name in lanu-stable.jks store-password key-password; do
  cmp -s "$home/.lanu/signing/$name" "$tmp/restore/signing/$name" || {
    echo 'Recovered key/credential mismatch.' >&2
    exit 1
  }
done
[[ "$(find "$home/.lanu/backups" -maxdepth 1 -type f | wc -l)" == 1 ]] || {
  echo 'A plaintext archive or extra backup artifact was created.' >&2
  exit 1
}
# An archive with inconsistent store/key passwords looks complete but cannot
# recover the original stable PKCS12 private key. Never publish a second one.
printf 'WRONG_DUMMY_KEY_PASSWORD' > "$home/.lanu/signing/key-password"
if printf '%s\n%s\n' "$dummy" "$dummy" |
  script -q -e -c "env HOME=$home GNUPGHOME=$home/.gnupg bash $root/scripts/backup_stable_signing_encrypted.sh" \
    /dev/null > "$tmp/invalid.log" 2>&1; then
  echo 'An unrecoverable backup was accepted.' >&2
  exit 1
fi
grep -q 'credentials differ' "$tmp/invalid.log" || {
  echo 'Missing signed-key credential mismatch guard.' >&2
  exit 1
}
[[ "$(find "$home/.lanu/backups" -maxdepth 1 -type f | wc -l)" == 1 ]] || {
  echo 'Failed backup created another archive.' >&2
  exit 1
}
cp "$home/.lanu/signing/store-password" "$home/.lanu/signing/key-password"
# Refuse absurd keystore sizes before encryption/extraction.
truncate -s 1048577 "$home/.lanu/signing/lanu-stable.jks"
if printf '%s\n%s\n' "$dummy" "$dummy" |
  script -q -e -c "env HOME=$home GNUPGHOME=$home/.gnupg bash $root/scripts/backup_stable_signing_encrypted.sh" \
    /dev/null > "$tmp/oversized.log" 2>&1; then
  echo 'Oversized signing archive input was accepted.' >&2
  exit 1
fi
grep -q 'Unexpected keystore size' "$tmp/oversized.log" || {
  echo 'Missing keystore size guard.' >&2
  exit 1
}
echo 'Dummy encrypted signing backup, restore and unsafe input rejections: PASS'
