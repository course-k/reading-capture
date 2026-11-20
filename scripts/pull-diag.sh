#!/bin/sh
# 端末から検知の記録（数値だけ）を取り出して、まとめを出す。
# 使い方: scripts/pull-diag.sh [年-月-日]   （省くと今日）
set -eu
cd "$(dirname "$0")/.."

ADB="${ANDROID_HOME:-}/platform-tools/adb"
[ -x "$ADB" ] || ADB="$(command -v adb || true)"
if [ -z "$ADB" ]; then
  SDK="$(sed -n 's/^sdk\.dir=//p' local.properties 2>/dev/null || true)"
  ADB="$SDK/platform-tools/adb"
fi
DAY="${1:-$(date +%F)}"
OUT="build/diag"
mkdir -p "$OUT"
"$ADB" exec-out run-as app.readingcapture cat "files/diag/$DAY.jsonl" > "$OUT/$DAY.jsonl"
echo "取り出しました: $OUT/$DAY.jsonl（$(wc -l < "$OUT/$DAY.jsonl" | tr -d ' ') 行）"
python3 scripts/summarize_diag.py "$OUT/$DAY.jsonl"
