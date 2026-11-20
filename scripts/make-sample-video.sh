#!/bin/sh
# エミュレーターのカメラに見せる、合成したページの映像を作る（本物の本は使わない）。
# 使い方: scripts/make-sample-video.sh
# できるもの: core/build/samples/pages-portrait.mp4
# 見せ方: emulator -avd <名前> -camera-back videofile:<できた動画の絶対パス>
# 要るもの: JAVA_HOME（JDK 17）、ffmpeg
set -eu
cd "$(dirname "$0")/.."
./gradlew :core:test --tests '*SampleGenerator*' -Psamples=1 -q
S=core/build/samples
ffmpeg -y -loglevel error -framerate 15 -i "$S/video/f%05d.png" -vf "format=yuv420p" -c:v libx264 -crf 14 -r 30 "$S/pages-portrait.mp4"
echo "できました: $(pwd)/$S/pages-portrait.mp4（$(cat "$S/video-info.txt")）"
