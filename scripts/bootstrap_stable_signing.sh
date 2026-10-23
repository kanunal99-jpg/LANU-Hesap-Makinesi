#!/usr/bin/env bash
# Run on the repository owner's trusted computer with JDK, OpenSSL and GitHub CLI.
# Keystore and passwords NEVER leave the machine except as encrypted GitHub Secrets.
set -euo pipefail
umask 077
export LC_ALL=C

REPOSITORY="kanunal99-jpg/LANU-Hesap-Makinesi"
SIGN_DIR="$HOME/.lanu/signing"
KEYSTORE="$SIGN_DIR/lanu-stable.jks"
STORE_FILE="$SIGN_DIR/store-password"
KEY_FILE="$SIGN_DIR/key-password"
ALIAS="lanu"

for cmd in gh keytool openssl base64 tr; do
  command -v "$cmd" >/dev/null || { echo "Missing requirement: $cmd" >&2; exit 1; }
done
gh auth status >/dev/null
mkdir -p "$SIGN_DIR"
chmod 700 "$SIGN_DIR"

if [ -e "$KEYSTORE" ]; then
  if [ ! -s "$STORE_FILE" ] || [ ! -s "$KEY_FILE" ]; then
    echo "Existing keystore has missing local recovery credentials. Refusing to rotate or overwrite." >&2
    exit 1
  fi
  echo "Reusing the existing LANU signing key; never rotating automatically."
else
  if [ -e "$STORE_FILE" ] || [ -e "$KEY_FILE" ]; then
    echo "Credentials already exist without the keystore. Refusing unsafe regeneration." >&2
    exit 1
  fi
  openssl rand -hex 24 > "$STORE_FILE"
  openssl rand -hex 24 > "$KEY_FILE"
  chmod 600 "$STORE_FILE" "$KEY_FILE"
  export STORE_PASSWORD="$(cat "$STORE_FILE")"
  export KEY_PASSWORD="$(cat "$KEY_FILE")"
  keytool -genkeypair -noprompt -keystore "$KEYSTORE" \
    -storetype PKCS12 -alias "$ALIAS" -keyalg RSA -keysize 3072 \
    -validity 12000 -dname "CN=LANU Android App,O=LANU,C=TR" \
    -storepass:env STORE_PASSWORD -keypass:env KEY_PASSWORD >/dev/null
  chmod 600 "$KEYSTORE"
  echo "Created a new, user-owned signing key (secure local folder)."
fi

export STORE_PASSWORD="$(cat "$STORE_FILE")"
export KEY_PASSWORD="$(cat "$KEY_FILE")"
FINGERPRINT="$(keytool -exportcert -rfc -alias "$ALIAS" -keystore "$KEYSTORE" \
  -storepass:env STORE_PASSWORD | openssl x509 -noout -fingerprint -sha256 |
  cut -d= -f2 | tr -d ':\r\n' | tr '[:upper:]' '[:lower:]')"
if ! [[ "$FINGERPRINT" =~ ^[0-9a-f]{64}$ ]]; then
  echo "Unable to validate the locally generated signing certificate." >&2
  exit 1
fi

# GitHub CLI encrypts secrets client-side. Never echo passwords, encode a key
# into a commit, or persist them in any GitHub release/artifact.
base64 < "$KEYSTORE" | tr -d '\r\n' |
  gh secret set LANU_SIGNING_KEYSTORE_BASE64 --repo "$REPOSITORY"
printf %s "$STORE_PASSWORD" |
  gh secret set LANU_SIGNING_STORE_PASSWORD --repo "$REPOSITORY"
printf %s "$KEY_PASSWORD" |
  gh secret set LANU_SIGNING_KEY_PASSWORD --repo "$REPOSITORY"
printf %s "$ALIAS" |
  gh secret set LANU_SIGNING_KEY_ALIAS --repo "$REPOSITORY"
printf %s "$FINGERPRINT" |
  gh secret set LANU_SIGNING_CERT_SHA256 --repo "$REPOSITORY"

echo "GitHub Actions: all five protected signing values uploaded."
echo "Public certificate SHA-256: $FINGERPRINT"
echo "IMPORTANT: Make an offline backup of $SIGN_DIR and do not share this folder."
# A manual dispatch from main forces a NEW run number and a new signed release,
# unlike re-running an older ephemeral build.
gh workflow run android.yml --ref main --repo "$REPOSITORY"
echo "Stable signing build requested. Review Actions and the new signed release."
unset STORE_PASSWORD KEY_PASSWORD
