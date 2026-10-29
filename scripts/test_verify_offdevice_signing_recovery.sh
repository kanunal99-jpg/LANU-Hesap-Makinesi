#!/usr/bin/env bash
# Uses a GENERATED DUMMY key. Never reads the owner's real signing directory.
set -Eeuo pipefail
umask 077
for command in script gpg tar keytool openssl mktemp grep cmp jar jarsigner; do
  command -v "$command" >/dev/null || { echo "CI dependency missing: $command" >&2; exit 1; }
done
root="$(cd "$(dirname "$0")/.." && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf -- "$tmp"; unset TEST_STORE_PASSWORD TEST_KEY_PASSWORD' EXIT
mkdir -p "$tmp/home/signing" "$tmp/home/.gnupg"
chmod 700 "$tmp/home/.gnupg"
export TEST_STORE_PASSWORD='LANU_ONLY_PUBLIC_TEST_STORE_PASSWORD_2026'
keytool -genkeypair -noprompt -storetype PKCS12 \
  -keystore "$tmp/home/signing/lanu-stable.jks" -alias lanu \
  -keyalg RSA -keysize 2048 -validity 365 \
  -dname "CN=CI GENERATED DUMMY, O=LANU TEST" \
  -storepass:env TEST_STORE_PASSWORD -keypass:env TEST_STORE_PASSWORD >/dev/null 2>&1
printf '%s' "$TEST_STORE_PASSWORD" > "$tmp/home/signing/store-password"
printf '%s' "$TEST_STORE_PASSWORD" > "$tmp/home/signing/key-password"
fingerprint="$(keytool -exportcert -rfc -alias lanu \
  -keystore "$tmp/home/signing/lanu-stable.jks" -storepass:env TEST_STORE_PASSWORD |
  openssl x509 -noout -fingerprint -sha256 | cut -d= -f2 |
  tr -d ':\r\n' | tr '[:upper:]' '[:lower:]')"
[[ "$fingerprint" =~ ^[0-9a-f]{64}$ ]] || { echo 'Cannot create dummy cert.' >&2; exit 1; }
cat > "$tmp/trusted.signer.txt" <<EOF
apk=app-debug.apk
signing_mode=stable
certificate_sha256=$fingerprint
upgrade_verified_on_device=false
EOF
dummy_pass='LANU_ONLY_PUBLIC_TEST_GPG_PASSWORD_2026_LONG'
tar -C "$tmp/home" -czf - \
  signing/lanu-stable.jks signing/store-password signing/key-password |
  gpg --homedir "$tmp/home/.gnupg" --quiet --batch --yes --pinentry-mode loopback \
      --no-tty --symmetric --cipher-algo AES256 --passphrase-fd 3 \
      --output "$tmp/signing.tar.gz.gpg" 3<<<"$dummy_pass"

verify() {
  printf '%s\n' "$dummy_pass" |
    script -q -e -c "env GNUPGHOME=$tmp/home/.gnupg bash $root/scripts/verify_offdevice_signing_recovery.sh $tmp/signing.tar.gz.gpg $tmp/trusted.signer.txt" /dev/null > "$tmp/result.log" 2>&1
}
verify || { echo 'Valid generated-key recovery must pass.' >&2; exit 1; }
grep -q '^PASS: encrypted backup restored into an isolated directory' "$tmp/result.log" || {
  echo 'Missing independent cert match confirmation.' >&2; exit 1;
}
# A different trusted release certificate MUST prevent acceptance.
sed -i "s/certificate_sha256=$fingerprint/certificate_sha256=$(printf 'f%.0s' {1..64})/" "$tmp/trusted.signer.txt"
if verify; then echo 'Mismatched APK certificate was accepted!' >&2; exit 1; fi
grep -q 'RECOVERY FAILED' "$tmp/result.log" || { echo 'Missing certificate mismatch rejection.' >&2; exit 1; }
# An unexpected archive member cannot be silently extracted.
printf 'NOT_ALLOWED' > "$tmp/home/signing/extra"
tar -C "$tmp/home" -czf - \
  signing/lanu-stable.jks signing/store-password signing/key-password signing/extra |
  gpg --homedir "$tmp/home/.gnupg" --quiet --batch --yes --pinentry-mode loopback \
      --no-tty --symmetric --cipher-algo AES256 --passphrase-fd 3 \
      --output "$tmp/signing.tar.gz.gpg" 3<<<"$dummy_pass"
sed -i "s/certificate_sha256=$(printf 'f%.0s' {1..64})/certificate_sha256=$fingerprint/" "$tmp/trusted.signer.txt"
if verify; then echo 'Unexpected signed-backup archive member was accepted!' >&2; exit 1; fi
grep -q 'unexpected/duplicate archive paths' "$tmp/result.log" || {
  echo 'Missing archive path safety rejection.' >&2; exit 1;
}
# A highly compressible 3 MiB dummy keystore MUST NOT be unpacked,
# even though its encrypted gzip backup is much smaller than 4 MiB.
mkdir -p "$tmp/oversize/signing"
cp "$tmp/home/signing/store-password" "$tmp/oversize/signing/store-password"
cp "$tmp/home/signing/key-password" "$tmp/oversize/signing/key-password"
dd if=/dev/zero of="$tmp/oversize/signing/lanu-stable.jks" bs=1048576 count=3 status=none
tar -C "$tmp/oversize" -czf - \
  signing/lanu-stable.jks signing/store-password signing/key-password |
  gpg --homedir "$tmp/home/.gnupg" --quiet --batch --yes --pinentry-mode loopback \
      --no-tty --symmetric --cipher-algo AES256 --passphrase-fd 3 \
      --output "$tmp/signing.tar.gz.gpg" 3<<<"$dummy_pass"
if verify; then echo 'Compressed oversized keystore was extracted!' >&2; exit 1; fi
grep -q 'expands beyond safe file limits' "$tmp/result.log" || {
  echo 'Missing decompression-bomb rejection.' >&2; exit 1
}
# Restore the original generated dummy backup fixture for remaining checks.
tar -C "$tmp/home" -czf - \
  signing/lanu-stable.jks signing/store-password signing/key-password |
  gpg --homedir "$tmp/home/.gnupg" --quiet --batch --yes --pinentry-mode loopback \
      --no-tty --symmetric --cipher-algo AES256 --passphrase-fd 3 \
      --output "$tmp/signing.tar.gz.gpg" 3<<<"$dummy_pass"
# A misleading archive with a valid cert but a corrupt private-key
# credential must not be called recoverable.
rm -- "$tmp/home/signing/extra"
printf 'INCORRECT_DUMMY_KEY_CREDENTIAL' > "$tmp/home/signing/key-password"
tar -C "$tmp/home" -czf - \
  signing/lanu-stable.jks signing/store-password signing/key-password |
  gpg --homedir "$tmp/home/.gnupg" --quiet --batch --yes --pinentry-mode loopback \
      --no-tty --symmetric --cipher-algo AES256 --passphrase-fd 3 \
      --output "$tmp/signing.tar.gz.gpg" 3<<<"$dummy_pass"
if verify; then echo 'Corrupted signing private-key credential was accepted!' >&2; exit 1; fi
grep -q 'recovered private key could not sign offline challenge' "$tmp/result.log" || {
  echo 'Missing private-key credential validation.' >&2; exit 1;
}
# The archive format must not silently require matching credentials. A real
# JKS keystore can use independent store and private-key passwords; it is a
# negative regression for a verifier that incorrectly compares file bytes.
# Only generated CI test material is used. The owner's signer is untouched.
export TEST_KEY_PASSWORD='LANU_PUBLIC_DIFFERENT_KEY_PASS_2026'
rm -- "$tmp/home/signing/lanu-stable.jks"
keytool -genkeypair -noprompt -storetype JKS \
  -keystore "$tmp/home/signing/lanu-stable.jks" -alias lanu \
  -keyalg RSA -keysize 2048 -validity 365 \
  -dname "CN=CI GENERATED DISTINCT PASSWORDS, O=LANU TEST" \
  -storepass:env TEST_STORE_PASSWORD -keypass:env TEST_KEY_PASSWORD >/dev/null 2>&1
printf '%s' "$TEST_STORE_PASSWORD" > "$tmp/home/signing/store-password"
printf '%s' "$TEST_KEY_PASSWORD" > "$tmp/home/signing/key-password"
jks_fingerprint="$(keytool -exportcert -rfc -alias lanu \
  -keystore "$tmp/home/signing/lanu-stable.jks" -storepass:env TEST_STORE_PASSWORD |
  openssl x509 -noout -fingerprint -sha256 | cut -d= -f2 |
  tr -d ':\\r\\n' | tr '[:upper:]' '[:lower:]')"
[[ "$jks_fingerprint" =~ ^[0-9a-f]{64}$ ]] || { echo 'Invalid distinct-password dummy certificate.' >&2; exit 1; }
sed -i "s/certificate_sha256=$fingerprint/certificate_sha256=$jks_fingerprint/" "$tmp/trusted.signer.txt"
tar -C "$tmp/home" -czf - \
  signing/lanu-stable.jks signing/store-password signing/key-password |
  gpg --homedir "$tmp/home/.gnupg" --quiet --batch --yes --pinentry-mode loopback \
    --no-tty --symmetric --cipher-algo AES256 --passphrase-fd 3 \
    --output "$tmp/signing.tar.gz.gpg" 3<<<"$dummy_pass"
verify || { echo 'Distinct key/store passwords must permit a valid isolated signing recovery.' >&2; exit 1; }
grep -q '^PASS: encrypted backup restored into an isolated directory' "$tmp/result.log" || {
  echo 'Missing successful independent-password signing challenge.' >&2
  exit 1
}
# Now deliberately corrupt ONLY the key credential: public cert still matches,
# but the real signing challenge MUST fail without changing the source key.
printf 'WRONG_PUBLIC_DUMMY_KEY_PASSWORD' > "$tmp/home/signing/key-password"
tar -C "$tmp/home" -czf - \
  signing/lanu-stable.jks signing/store-password signing/key-password |
  gpg --homedir "$tmp/home/.gnupg" --quiet --batch --yes --pinentry-mode loopback \
    --no-tty --symmetric --cipher-algo AES256 --passphrase-fd 3 \
    --output "$tmp/signing.tar.gz.gpg" 3<<<"$dummy_pass"
if verify; then echo 'Wrong distinct key password was accepted!' >&2; exit 1; fi
grep -q 'recovered private key could not sign offline challenge' "$tmp/result.log" || {
  echo 'Missing wrong-key rejection for independent credentials.' >&2
  exit 1
}
# The verifier did not alter the generated source keystore, and the test
# never accessed ~/.lanu/signing.
keytool -list -keystore "$tmp/home/signing/lanu-stable.jks" \
  -storepass:env TEST_STORE_PASSWORD -alias lanu >/dev/null
echo 'Off-device dummy encrypted signer recovery, mismatch and traversal: PASS'
