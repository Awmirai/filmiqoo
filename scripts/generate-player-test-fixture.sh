#!/usr/bin/env bash
set -euo pipefail
# Owned, deterministic video/audio fixture; never stream third-party media in CI.
if ! command -v ffmpeg >/dev/null; then
  sudo apt-get update -qq
  sudo apt-get install -y -qq ffmpeg
fi
mkdir -p app/src/androidTest/assets
ffmpeg -hide_banner -loglevel error -y \
  -f lavfi -i 'testsrc2=size=320x180:rate=24' \
  -f lavfi -i 'sine=frequency=440:sample_rate=44100' \
  -t 15 -c:v libx264 -pix_fmt yuv420p -c:a aac -movflags +faststart \
  app/src/androidTest/assets/player-fixture.mp4
