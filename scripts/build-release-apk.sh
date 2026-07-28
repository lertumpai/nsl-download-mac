#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_root="$(cd -- "${script_dir}/.." && pwd)"
android_dir="${project_root}/android"
source_apk="${android_dir}/app/build/outputs/apk/release/app-release.apk"
destination_dir="${NSL_APK_DEST_DIR:-${HOME}/Desktop}"
destination_apk="${destination_dir}/nsl_browser.apk"
temporary_apk="${destination_dir}/.nsl_browser.apk.$$"

cleanup() {
  rm -f -- "${temporary_apk}"
}
trap cleanup EXIT

if [[ ! -x "${android_dir}/gradlew" ]]; then
  echo "Error: Gradle wrapper not found at ${android_dir}/gradlew" >&2
  exit 1
fi

mkdir -p -- "${destination_dir}"

echo "Building signed release APK..."
(
  cd -- "${android_dir}"
  ./gradlew :app:assembleRelease
)

if [[ ! -f "${source_apk}" ]]; then
  echo "Error: build completed but APK was not found at ${source_apk}" >&2
  exit 1
fi

cp -- "${source_apk}" "${temporary_apk}"
chmod 0644 "${temporary_apk}"
mv -f -- "${temporary_apk}" "${destination_apk}"

echo "APK ready: ${destination_apk}"
