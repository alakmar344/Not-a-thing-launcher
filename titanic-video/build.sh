#!/usr/bin/env bash
# Builds titanic.mp4 from scratch: narration -> score -> frames -> mux.
set -euo pipefail
cd "$(dirname "$0")"
PY=${PYTHON:-python3}
WORKERS=${WORKERS:-2}   # ~500 MB RAM per worker

# fonts used by the titles/subtitles (OFL, from Google Fonts)
mkdir -p ~/.fonts && cp -n fonts/*.ttf ~/.fonts/ && fc-cache -f >/dev/null

$PY tts.py                      # neural narration + timeline.json
$PY music.py                    # procedural score, SFX, ducking, final mix
rm -rf build/segs
$PY render.py --workers "$WORKERS"   # cairo frames + post FX -> build/video.mp4

ffmpeg -v error -y -i build/video.mp4 -i build/soundtrack.wav \
  -c:v libx264 -preset veryfast -crf 21 -maxrate 6M -bufsize 12M -pix_fmt yuv420p \
  -c:a aac -b:a 256k -af "loudnorm=I=-16:TP=-1.5:LRA=11" \
  -shortest -movflags +faststart titanic.mp4
echo "done -> $(pwd)/titanic.mp4"
