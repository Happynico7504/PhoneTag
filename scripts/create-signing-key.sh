#!/usr/bin/env bash
# Creates the release signing key for PhoneTag APKs and prints the values to store as GitHub
# Actions secrets. Every APK must be signed with this same key, or Android refuses to install an
# update over an existing installation. Keep the .jks file and passwords somewhere safe: if the
# key is lost, players have to uninstall and reinstall once to switch to a new one.
#
#   scripts/create-signing-key.sh [output.jks]
set -euo pipefail

out="${1:-phonetag-release.jks}"
alias="phonetag"
[[ -e "$out" ]] && { echo "error: $out already exists; refusing to overwrite a signing key" >&2; exit 1; }
command -v keytool >/dev/null || { echo "error: keytool not found (install a JDK)" >&2; exit 1; }

password="$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 32 || true)"
keytool -genkeypair -v -keystore "$out" -storetype PKCS12 -alias "$alias" \
    -keyalg RSA -keysize 4096 -validity 10000 \
    -storepass "$password" -keypass "$password" \
    -dname "CN=PhoneTag, O=PhoneTag" >/dev/null 2>&1

cat <<INFO
Created $out (keep it, and this password, safe and out of git).

Add these GitHub Actions secrets (Settings → Secrets and variables → Actions):

  PHONETAG_KEYSTORE_BASE64    = (output of: base64 -w0 $out)
  PHONETAG_KEYSTORE_PASSWORD  = $password
  PHONETAG_KEY_ALIAS          = $alias

For a local signed build:
  PHONETAG_KEYSTORE_FILE=$out PHONETAG_KEYSTORE_PASSWORD=$password PHONETAG_KEY_ALIAS=$alias \\
    ./gradlew :app:assembleRelease
INFO
