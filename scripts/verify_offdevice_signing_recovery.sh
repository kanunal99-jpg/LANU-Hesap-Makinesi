#!/usr/bin/env bash
# Standalone OFF-DEVICE check of an existing encrypted signing backup.
# Never overwrites ~/.lanu/signing, uploads secrets or modifies GitHub secrets.
set -Eeuo pipefail
umask 077
export LC_ALL=C

if [[ $# -ne 2 ]]; then
  echo 'Usage: verify_offdevice_signing_recovery.sh ENCRYPTED_BACKUP.gpg TRUSTED_RELEASE.signer.txt' >&2
  exit 2
fi
archive="$1"
signer="$2"
for binary in gpg tar mktemp keytool openssl grep sort awk sed tr stat cmp jar jarsigner; do
  command -v "$binary" >/dev/null 2>&1 || { echo "Missing recovery dependency: $binary" >&2; exit 1; }
done
[[ -f "$archive" && ! -L "$archive" && -s "$archive" &&
   -f "$signer" && ! -L "$signer" && -s "$signer" ]] || {
  echo 'Missing or unsafe encrypted backup / trusted release signer file.' >&2
  exit 1
}
(( $(stat -c '%s' "$archive") <= 4194304 )) || {
  echo 'Encrypted signing archive exceeds safe 4 MiB recovery bound.' >&2
  exit 1
}
[[ "$(grep -c '^signing_mode=stable$' "$signer" || true)" == 1 &&
   "$(grep -c '^apk=app-debug.apk$' "$signer" || true)" == 1 &&
   "$(grep -Ec '^certificate_sha256=[0-9a-f]{64}$' "$signer" || true)" == 1 ]] || {
  echo 'Trusted release signer is missing valid stable signing metadata.' >&2
  exit 1
}
expected="$(grep -E '^certificate_sha256=[0-9a-f]{64}$' "$signer")"
expected="${expected#certificate_sha256=}"

scratch_base="${TMPDIR:-/tmp}"
if [[ -d /dev/shm && -w /dev/shm && ! -L /dev/shm ]]; then scratch_base=/dev/shm; fi
[[ -d "$scratch_base" && ! -L "$scratch_base" ]] || {
  echo 'Unsafe temporary recovery directory.' >&2
  exit 1
}
scratch="$(mktemp -d "$scratch_base/lanu-recovery.XXXXXXXX")"
passphrase=''
STORE_PASSWORD=''
KEY_PASSWORD=''
cleanup() {
  unset passphrase STORE_PASSWORD KEY_PASSWORD
  rm -rf -- "$scratch"
}
trap cleanup EXIT INT TERM

# A removable/cloud-synced backup can change between the three archive
# inspections and extraction. Snapshot the encrypted bytes ONCE into the
# private 0700 scratch directory, and decrypt only that immutable-by-path
# local copy. A source swapped to a symlink during copy must fail closed.
# This never touches the original encrypted backup or signing keystore.
cp -P -- "$archive" "$scratch/backup.gpg" || {
  echo 'RECOVERY FAILED: cannot create private encrypted backup snapshot.' >&2
  exit 1
}
[[ -f "$scratch/backup.gpg" && ! -L "$scratch/backup.gpg" &&
   -s "$scratch/backup.gpg" ]] || {
  echo 'RECOVERY FAILED: encrypted backup snapshot is unsafe.' >&2
  exit 1
}
(( $(stat -c '%s' "$scratch/backup.gpg") <= 4194304 )) || {
  echo 'RECOVERY FAILED: encrypted backup changed size during snapshot.' >&2
  exit 1
}
archive="$scratch/backup.gpg"

[[ -r /dev/tty ]] || { echo 'Open a trusted interactive terminal to enter the recovery password.' >&2; exit 1; }
printf 'Enter encrypted backup password (never sent anywhere): ' > /dev/tty
IFS= read -r -s passphrase < /dev/tty || { echo 'Unable to read backup password.' >&2; exit 1; }
printf '\n' > /dev/tty
[[ -n "$passphrase" ]] || { echo 'Empty backup password is prohibited.' >&2; exit 1; }

# Inspect before extracting. The only legal archive entries are three regular
# files created by backup_stable_signing_encrypted.sh. No dirs/links/traversal.
expected_members=$'signing/key-password\nsigning/lanu-stable.jks\nsigning/store-password'
members="$(gpg --quiet --batch --yes --pinentry-mode loopback --no-tty \
  --passphrase-fd 3 --decrypt "$archive" 3<<<"$passphrase" | tar -tzf - | sort)"
[[ "$members" == "$expected_members" ]] || {
  echo 'Encrypted backup contains unexpected/duplicate archive paths.' >&2
  exit 1
}
types="$(gpg --quiet --batch --yes --pinentry-mode loopback --no-tty \
  --passphrase-fd 3 --decrypt "$archive" 3<<<"$passphrase" | tar -tvzf - | awk '{print substr($1,1,1)}' | sort)"
[[ "$types" == $'-\n-\n-' ]] || {
  echo 'Encrypted backup contains a non-regular file.' >&2
  exit 1
}

# A tiny encrypted tar.gz can inflate to gigabytes. Validate GNU tar's
# uncompressed member sizes BEFORE extracting into private temporary storage.
# Password files need only a few bytes; a real PKCS12 signer stays below 2 MiB.
# This also prevents a crafted archive from exhausting device disk space.
safe_sizes="$(gpg --quiet --batch --yes --pinentry-mode loopback --no-tty \
  --passphrase-fd 3 --decrypt "$archive" 3<<<"$passphrase" |
  tar -tvzf - | awk '
    BEGIN { count=0; total=0; valid=1 }
    {
      bytes=$3; name=$NF; count++
      if (bytes !~ /^[0-9]+$/ || bytes+0 < 1) valid=0
      if (name=="signing/lanu-stable.jks") {
        if (bytes+0 > 2097152) valid=0
      } else if (name=="signing/store-password" || name=="signing/key-password") {
        if (bytes+0 > 4096) valid=0
      } else valid=0
      total += bytes+0
    }
    END { if (valid && count==3 && total<=2105344) print "OK"; else print "FAIL" }
  ')"
[[ "$safe_sizes" == "OK" ]] || {
  echo 'RECOVERY FAILED: encrypted archive expands beyond safe file limits.' >&2
  exit 1
}

mkdir -m 700 "$scratch/signing"
gpg --quiet --batch --yes --pinentry-mode loopback --no-tty \
  --passphrase-fd 3 --decrypt "$archive" 3<<<"$passphrase" |
  tar -xzf - -C "$scratch" --no-same-owner --no-same-permissions
unset passphrase
for name in lanu-stable.jks store-password key-password; do
  f="$scratch/signing/$name"
  [[ -f "$f" && ! -L "$f" && -s "$f" ]] || {
    echo 'An expected recovered file was missing or unsafe.' >&2
    exit 1
  }
done

# Preserve BOTH independently supplied credentials. PKCS12/JKS archives and
# providers may use distinct store and private-key passwords; byte equality
# is not a valid recovery requirement. An incorrect key password is rejected
# by the isolated private-key signing challenge below (fail closed).
STORE_PASSWORD="$(cat "$scratch/signing/store-password")"
KEY_PASSWORD="$(cat "$scratch/signing/key-password")"
[[ -n "$STORE_PASSWORD" && -n "$KEY_PASSWORD" ]] || {
  echo 'RECOVERY FAILED: recovered signing credentials are empty.' >&2
  exit 1
}
export STORE_PASSWORD KEY_PASSWORD
# A matching public certificate without its private key is not recoverable.
# Verify the PKCS12 alias is a PrivateKeyEntry, not a trusted cert-only entry.
key_entry="$(keytool -list -v -alias lanu \
  -keystore "$scratch/signing/lanu-stable.jks" -storepass:env STORE_PASSWORD)"
[[ "$key_entry" == *"Entry type: PrivateKeyEntry"* ]] || {
  unset key_entry
  echo 'RECOVERY FAILED: signing private-key entry is unavailable.' >&2
  exit 1
}
unset key_entry
# Use the same alias as bootstrap_stable_signing.sh. JDK auto-detects the
# actual JKS/PKCS12 keystore format; a filename suffix is not proof of format.
# Key data and credentials never appear in process arguments, stdout,
# GitHub artifacts or network traffic.
actual="$(keytool -exportcert -rfc -alias lanu \
  -keystore "$scratch/signing/lanu-stable.jks" -storepass:env STORE_PASSWORD |
  openssl x509 -noout -fingerprint -sha256 | sed 's/^[^=]*=//' |
  tr -d ':\r\n' | tr '[:upper:]' '[:lower:]')"
[[ "$actual" =~ ^[0-9a-f]{64}$ && "$actual" == "$expected" ]] || {
  echo 'RECOVERY FAILED: recovered signing certificate differs from trusted stable APK signer.' >&2
  exit 1
}

# Verify that the recovered PRIVATE key can actually sign a fresh payload.
# This proof uses only an isolated disposable JAR; it never modifies the
# owner's keystore, logs a password, exports keys or uploads anything.
# A matching public certificate alone is insufficient disaster recovery.
printf '%s\n' 'LANU isolated signing recovery challenge' > "$scratch/proof.txt"
jar cf "$scratch/challenge-unsigned.jar" -C "$scratch" proof.txt >/dev/null 2>&1 || {
  echo 'RECOVERY FAILED: cannot prepare offline signing challenge.' >&2
  exit 1
}
jarsigner -keystore "$scratch/signing/lanu-stable.jks" \
  -storepass:env STORE_PASSWORD -keypass:env KEY_PASSWORD \
  -signedjar "$scratch/challenge-signed.jar" \
  "$scratch/challenge-unsigned.jar" lanu >"$scratch/sign.log" 2>&1 || {
    echo 'RECOVERY FAILED: recovered private key could not sign offline challenge.' >&2
    exit 1
  }
verification="$(jarsigner -verify "$scratch/challenge-signed.jar" 2>/dev/null)" || {
  echo 'RECOVERY FAILED: offline signature did not verify.' >&2
  exit 1
}
[[ "$verification" == *"jar verified."* ]] || {
  echo 'RECOVERY FAILED: offline challenge is unsigned or invalid.' >&2
  exit 1
}
unset verification

echo 'PASS: encrypted backup restored into an isolated directory and its certificate matches the trusted stable APK release.'
echo 'Original keys/installed apps were not modified. Store the encrypted backup OFF DEVICE and its password separately.'
