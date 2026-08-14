#!/bin/bash

set -euo pipefail

KEY_ALIAS="hrv-upload"
KEYCHAIN_SERVICE="quest.byai.hrv.upload-key"
KEYSTORE_DIRECTORY="${HOME}/.android/keystores"
KEYSTORE_PATH="${KEYSTORE_DIRECTORY}/hrv-upload.jks"
CURRENT_USER="${USER:?Current macOS user is unavailable}"

if [[ -e "${KEYSTORE_PATH}" ]]; then
  echo "Upload keystore already exists at ${KEYSTORE_PATH}; nothing was changed."
  exit 1
fi

if security find-generic-password -a "${CURRENT_USER}" -s "${KEYCHAIN_SERVICE}" >/dev/null 2>&1; then
  echo "A Keychain password already exists for ${KEYCHAIN_SERVICE}; nothing was changed."
  exit 1
fi

mkdir -p "${KEYSTORE_DIRECTORY}"
UPLOAD_PASSWORD="$(openssl rand -hex 32)"
trap 'unset UPLOAD_PASSWORD' EXIT

keytool -genkeypair \
  -alias "${KEY_ALIAS}" \
  -dname "CN=HRV AI, O=byAI, C=US" \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -keystore "${KEYSTORE_PATH}" \
  -storetype PKCS12 \
  -storepass "${UPLOAD_PASSWORD}" \
  -keypass "${UPLOAD_PASSWORD}"

security add-generic-password \
  -a "${CURRENT_USER}" \
  -s "${KEYCHAIN_SERVICE}" \
  -w "${UPLOAD_PASSWORD}"

echo "Created the HRV AI upload keystore at ${KEYSTORE_PATH}."
echo "Its password is stored in macOS Keychain under ${KEYCHAIN_SERVICE}."
echo "Back up the keystore before uploading the first release."
