#!/usr/bin/env bash
# Safe, local-only encrypted recovery archive for the existing LANU signing key.
# Never creates a plaintext tar file or changes the signing keystore.
set -Eeuo pipefail
umask 077
export LC_ALL=C

sign_dir="$HOME/.lanu/signing"
backup_dir="$HOME/.lanu/backups"
for binary in tar gpg cmp mktemp date; do
  command -v "$binary" >/dev/null 2>&1 || {
    printf 'Missing requirement: %s\n' "$binary" >&2
    exit 1
  }
done
for name in lanu-stable.jks store-password key-password; do
  path="$sign_dir/$name"
  [[ -f "$path" && ! -L "$path" && -s "$path" ]] || {
    echo 'Signing recovery requires an existing, complete key and both passwords; no new key will be generated.' >&2
    exit 1
  }
done
[[ -d "$HOME/.lanu" && ! -L "$HOME/.lanu" ]] || {
  echo 'Expected private ~/.lanu directory.' >&2
  exit 1
}
mkdir -p -m 700 "$backup_dir"
[[ -d "$backup_dir" && ! -L "$backup_dir" ]] || {
  echo 'Unsafe backup destination.' >&2
  exit 1
}
chmod 700 "$backup_dir"

# Prompt on the actual console. Never put a real password in argv, export it,
# print it, write it to a file, or use the Android PIN as the backup password.
[[ -r /dev/tty ]] || { echo 'Open an interactive Termux session.' >&2; exit 1; }
passphrase=''
repeat=''
printf 'Choose a NEW backup password (20+ characters; NOT your PIN): ' >/dev/tty
IFS= read -r -s passphrase </dev/tty
printf '\nRepeat the backup password: ' >/dev/tty
IFS= read -r -s repeat </dev/tty
printf '\n' >/dev/tty
passphrase_length="$(printf '%s' "$passphrase" | wc -c)"
if (( passphrase_length < 20 )) || [[ "$passphrase" != "$repeat" ]]; then
  unset passphrase repeat
  echo 'Backup password mismatch or shorter than 20 characters; nothing created.' >&2
  exit 1
fi
unset repeat

scratch="$(mktemp -d "$backup_dir/.staging-XXXXXXXX")"
cleanup() { rm -rf -- "$scratch"; unset passphrase; }
trap cleanup EXIT INT TERM
encrypted="$scratch/lanu-signing.tar.gz.gpg"
# Plaintext TAR exists only as a pipe, never on the filesystem.
tar -C "$HOME/.lanu" -czf - \
  signing/lanu-stable.jks signing/store-password signing/key-password |
  gpg --quiet --batch --yes --pinentry-mode loopback --no-tty \
    --symmetric --cipher-algo AES256 --s2k-mode 3 \
    --s2k-digest-algo SHA512 --s2k-count 65011712 \
    --passphrase-fd 3 --output "$encrypted" 3<<<"$passphrase"
[[ -s "$encrypted" ]] || { echo 'Encrypted archive is empty.' >&2; exit 1; }

# Restore encrypted archive into isolated permission-0700 area ONLY.
# Compare all original file bytes; never print private key/password content.
mkdir -m 700 "$scratch/verify"
gpg --quiet --batch --yes --pinentry-mode loopback --no-tty \
  --decrypt --passphrase-fd 3 "$encrypted" 3<<<"$passphrase" |
  tar -xz -C "$scratch/verify" -f -
for name in lanu-stable.jks store-password key-password; do
  cmp -s -- "$sign_dir/$name" "$scratch/verify/signing/$name" || {
    echo 'Encrypted recovery test failed! No output will be published.' >&2
    exit 1
  }
done
unset passphrase
suffix="$(basename "$scratch")"
final="$backup_dir/lanu-signing-$(date -u +%Y%m%dT%H%M%SZ)-$suffix.tar.gz.gpg"
[[ ! -e "$final" ]] || { echo 'Backup file collision; refusing overwrite.' >&2; exit 1; }
mv -- "$encrypted" "$final"
chmod 600 "$final"
printf 'Encrypted backup and isolated restore verification passed.\n%s\n' "$final"
printf 'IMPORTANT: Copy this .gpg file OFF YOUR PHONE to private offline storage.\n'
printf 'Keep the unique password separately. Never upload plaintext keys or passwords.\n'
