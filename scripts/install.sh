#!/bin/sh
# USB でつないだ端末にアプリを入れる。
# 使い方: scripts/install.sh
# 要るもの: JAVA_HOME（JDK 17）と、adb（ANDROID_HOME/platform-tools、または PATH の上）
set -eu
cd "$(dirname "$0")/.."

ADB="${ANDROID_HOME:-}/platform-tools/adb"
[ -x "$ADB" ] || ADB="$(command -v adb || true)"
if [ -z "$ADB" ]; then
  # local.properties の sdk.dir から探す
  SDK="$(sed -n 's/^sdk\.dir=//p' local.properties 2>/dev/null || true)"
  ADB="$SDK/platform-tools/adb"
fi
[ -x "$ADB" ] || { echo "adb が見つかりません" >&2; exit 1; }

DEVICES="$("$ADB" devices | awk 'NR>1 && $2=="device" {print $1}')"
if [ -z "$DEVICES" ]; then
  echo "つながっている端末がありません。" >&2
  echo "スマホで USB デバッグを有効にし、つないだときに出る「USB デバッグを許可しますか？」で許可してください。" >&2
  "$ADB" devices >&2
  exit 1
fi

./gradlew :app:assembleDebug -q
"$ADB" install -r app/build/outputs/apk/debug/app-debug.apk
echo "入れました: $("$ADB" shell getprop ro.product.model | tr -d '\r')（Android $("$ADB" shell getprop ro.build.version.release | tr -d '\r')）"
