#!/bin/bash

set -euo pipefail

KEY_ALIAS="hrv-upload"
KEYCHAIN_SERVICE="quest.byai.hrv.upload-key"
KEYSTORE_PATH="${HOME}/.android/keystores/hrv-upload.jks"
CURRENT_USER="${USER:?Current macOS user is unavailable}"

if [[ ! -f "${KEYSTORE_PATH}" ]]; then
  echo "Missing upload keystore at ${KEYSTORE_PATH}. Run scripts/setup-upload-key.sh first."
  exit 1
fi

UPLOAD_PASSWORD="$(security find-generic-password -w -a "${CURRENT_USER}" -s "${KEYCHAIN_SERVICE}")"
trap 'unset UPLOAD_PASSWORD' EXIT

HRV_UPLOAD_STORE_FILE="${KEYSTORE_PATH}" \
HRV_UPLOAD_STORE_PASSWORD="${UPLOAD_PASSWORD}" \
HRV_UPLOAD_KEY_ALIAS="${KEY_ALIAS}" \
HRV_UPLOAD_KEY_PASSWORD="${UPLOAD_PASSWORD}" \
./gradlew bundleRelease

jarsigner -verify -certs app/build/outputs/bundle/release/app-release.aab
echo "Signed bundle: app/build/outputs/bundle/release/app-release.aab"
