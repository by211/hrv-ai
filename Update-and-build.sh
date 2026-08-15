#!/usr/bin/env bash

set -euo pipefail

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_directory="$script_directory"
build_file="$project_directory/app/build.gradle.kts"
signed_bundle="$project_directory/app/build/outputs/bundle/release/app-release.aab"
play_console_url="https://play.google.com/console/u/0/developers/5964918318669525217/app/4973133755529193628/app-dashboard"

cd "$project_directory"

require_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Required command not found: $1" >&2
    exit 1
  fi
}

require_command open
require_command pbcopy
require_command python3
require_command jarsigner
require_command security

if [[ ! -f "$build_file" ]]; then
  echo "Android build file not found: $build_file" >&2
  exit 1
fi

build_file_backup="$(mktemp "${TMPDIR:-/tmp}/hrv-build-gradle.XXXXXX")"
cp "$build_file" "$build_file_backup"
build_succeeded=false

cleanup() {
  if [[ "$build_succeeded" != true ]]; then
    cp "$build_file_backup" "$build_file"
    echo "Build failed; restored the previous app version." >&2
  fi
  rm -f "$build_file_backup"
}
trap cleanup EXIT

version_update="$(python3 - "$build_file" <<'PYTHON'
import re
import sys
from pathlib import Path

build_file = Path(sys.argv[1])
contents = build_file.read_text()

version_code_match = re.search(r"versionCode\s*=\s*(\d+)", contents)
version_name_match = re.search(r'versionName\s*=\s*"(\d+)\.(\d+)\.(\d+)"', contents)
if version_code_match is None or version_name_match is None:
    raise SystemExit("Could not find versionCode and semantic versionName in app/build.gradle.kts")

current_version_code = int(version_code_match.group(1))
current_version_name = ".".join(version_name_match.groups())
new_version_code = current_version_code + 1
major, minor, patch = map(int, version_name_match.groups())
new_version_name = f"{major}.{minor}.{patch + 1}"

contents, code_replacements = re.subn(
    r"versionCode\s*=\s*\d+",
    f"versionCode = {new_version_code}",
    contents,
    count=1,
)
contents, name_replacements = re.subn(
    r'versionName\s*=\s*"\d+\.\d+\.\d+"',
    f'versionName = "{new_version_name}"',
    contents,
    count=1,
)
if code_replacements != 1 or name_replacements != 1:
    raise SystemExit("Version update did not make exactly one replacement for each value")

build_file.write_text(contents)
print(f"{current_version_code}|{new_version_code}|{current_version_name}|{new_version_name}")
PYTHON
)"

IFS='|' read -r current_version_code new_version_code current_version_name new_version_name <<< "$version_update"
echo "Updated versionCode from $current_version_code to $new_version_code"
echo "Updated version from $current_version_name to $new_version_name"

"$project_directory/scripts/build-signed-bundle.sh"

if [[ ! -f "$signed_bundle" ]]; then
  echo "Signed bundle was not created at $signed_bundle" >&2
  exit 1
fi

build_succeeded=true
printf '%s' "$signed_bundle" | pbcopy

echo "Signed bundle: $signed_bundle"
echo "The bundle path has been copied to the clipboard."
echo "Opening the HRV AI Play Console dashboard in Google Chrome..."
open -a "Google Chrome" "$play_console_url"
