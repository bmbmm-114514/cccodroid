#!/bin/sh
# Android UI builds require JDK 17+ and Android SDK platform/build-tools 35.
# ARM hosts may supply a compatible native executable via AAPT2.
set -eu
android_dir=$(cd "$(dirname "$0")/../android" && pwd)
if [ -n "${AAPT2:-}" ]; then
  native_aapt2=$(command -v "$AAPT2") || { echo "AAPT2 必须是可执行文件路径: $AAPT2" >&2; exit 1; }
  set -- "-Pandroid.aapt2FromMavenOverride=$native_aapt2" "$@"
fi
exec "${GRADLE_BIN:-$android_dir/gradlew}" -p "$android_dir" "$@"
