#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
ANDROID_DIR="$REPO_ROOT/android-app"
FIRMWARE_DIR="$REPO_ROOT/firmware"
SIGNING_ENV="$ANDROID_DIR/.env.release"
IDF_IMAGE="espressif/idf:v6.1-dev@sha256:d63012d35027ba995ffe3f1182407670cf84bd9e95b3242d3ac5e5d6d53b8ff9"

if [[ ! -f "$SIGNING_ENV" ]]; then
  echo "Missing Android signing configuration: $SIGNING_ENV" >&2
  exit 1
fi

set -a
# shellcheck disable=SC1090
source "$SIGNING_ENV"
set +a

: "${CITS_RELEASE_STORE_FILE:?Missing CITS_RELEASE_STORE_FILE in .env.release}"
: "${CITS_RELEASE_STORE_PASSWORD:?Missing CITS_RELEASE_STORE_PASSWORD in .env.release}"
: "${CITS_RELEASE_KEY_ALIAS:?Missing CITS_RELEASE_KEY_ALIAS in .env.release}"
: "${CITS_RELEASE_KEY_PASSWORD:?Missing CITS_RELEASE_KEY_PASSWORD in .env.release}"

if [[ ! -f "$CITS_RELEASE_STORE_FILE" ]]; then
  echo "Release keystore does not exist: $CITS_RELEASE_STORE_FILE" >&2
  exit 1
fi

for command_name in docker tshark; do
  if ! command -v "$command_name" >/dev/null 2>&1; then
    echo "Required command not found: $command_name" >&2
    echo "Run this script through the Android Nix shell as documented in README.md." >&2
    exit 1
  fi
done

APP_VERSION="$(sed -n "s/^[[:space:]]*versionName ['\"]\([^'\"]*\)['\"].*/\1/p" "$ANDROID_DIR/app/build.gradle")"
if [[ -z "$APP_VERSION" ]]; then
  echo "Could not read Android versionName" >&2
  exit 1
fi

RELEASE_DIR="${1:-$REPO_ROOT/dist/CITS-to-go-v$APP_VERSION}"
APK_NAME="CITS-to-go-v$APP_VERSION.apk"
FIRMWARE_NAME="CITS-to-go-firmware-v$APP_VERSION.bin"
mkdir -p "$RELEASE_DIR"
RELEASE_DIR="$(cd -- "$RELEASE_DIR" && pwd)"

echo "Building and testing Android v$APP_VERSION"
(
  cd "$ANDROID_DIR"
  ./gradlew testDebugUnitTest
  ./gradlew --stop
  ./gradlew assembleRelease
)

APK_SOURCE="$ANDROID_DIR/app/build/outputs/apk/release/app-release.apk"
if [[ ! -f "$APK_SOURCE" ]]; then
  echo "Signed release APK was not generated: $APK_SOURCE" >&2
  exit 1
fi

if [[ -n "${ANDROID_HOME:-}" && -x "$ANDROID_HOME/build-tools/35.0.0/apksigner" ]]; then
  APKSIGNER="$ANDROID_HOME/build-tools/35.0.0/apksigner"
elif command -v apksigner >/dev/null 2>&1; then
  APKSIGNER="$(command -v apksigner)"
else
  echo "Could not find apksigner" >&2
  exit 1
fi

"$APKSIGNER" verify --verbose --print-certs "$APK_SOURCE"
install -m 0644 "$APK_SOURCE" "$RELEASE_DIR/$APK_NAME"

echo "Building merged ESP32-C5 firmware"
docker run --rm \
  --user "$(id -u):$(id -g)" \
  --volume "$FIRMWARE_DIR:/project" \
  --volume "$RELEASE_DIR:/release" \
  --workdir /project \
  --env "FIRMWARE_NAME=$FIRMWARE_NAME" \
  "$IDF_IMAGE" \
  sh -c 'if grep -qx "CONFIG_IDF_TARGET=\"esp32c5\"" sdkconfig 2>/dev/null; then idf.py build; else idf.py set-target esp32c5 build; fi && cd build && esptool --chip esp32c5 merge-bin --format raw -o "/release/$FIRMWARE_NAME" @flash_args'

if [[ ! -s "$RELEASE_DIR/$FIRMWARE_NAME" ]]; then
  echo "Merged firmware was not generated" >&2
  exit 1
fi

(
  cd "$RELEASE_DIR"
  sha256sum "$APK_NAME" "$FIRMWARE_NAME" > SHA256sum.txt
)

echo
echo "Release artifacts are ready in: $RELEASE_DIR"
printf '  %s\n' "$APK_NAME" "$FIRMWARE_NAME" "SHA256sum.txt"
