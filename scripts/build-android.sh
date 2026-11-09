#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export JAVA_HOME="${JAVA_HOME:-/home/box/opt/jdk-17}"
export ANDROID_HOME="${ANDROID_HOME:-/home/box/opt/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$PATH"
if [ ! -x "$JAVA_HOME/bin/java" ]; then
  echo "找不到 JDK 17，請設定 JAVA_HOME" >&2
  exit 1
fi
if [ ! -d "$ANDROID_HOME/platforms/android-34" ]; then
  echo "找不到 Android SDK platform 34，請設定 ANDROID_HOME" >&2
  exit 1
fi
printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$ROOT/android/local.properties"
cd "$ROOT/android"
./gradlew assembleDebug
mkdir -p /workspace/ptt-mobile-release
cp -f app/build/outputs/apk/debug/app-debug.apk /workspace/ptt-mobile-release/ptt-mobile-debug.apk
echo "APK: $ROOT/android/app/build/outputs/apk/debug/app-debug.apk"
echo "Copy: /workspace/ptt-mobile-release/ptt-mobile-debug.apk"
