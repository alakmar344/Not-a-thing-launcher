# Titanic: The Ship They Called Unsinkable

A ~4-minute animated documentary, written entirely in code (Python and cairo), with
neural text-to-speech narration and a procedurally generated score. The film explains how
Titanic was powered, why she was called unsinkable, and why she sank anyway.

This folder is self-contained and has nothing to do with the launcher app in the rest of
the repository.

## Chapters

| # | Scene | What it shows |
|---|-------|---------------|
| – | Intro | Titanic at dusk, title card |
| I | Belfast · 1909 | The Arrol gantry, the hull being plated, riveting sparks, key figures |
| II | The Machine | Cutaway: 29 boilers / 159 furnaces, steam pipes, triple-expansion engines, LP turbine, three propellers |
| III | Sixteen Compartments | 15 bulkheads, the bridge door switch, flooding demos ("any two" / "first four"), *practically unsinkable* |
| IV | The Fatal Flaw | Bulkheads open at the top, "ice cube tray" cascade, 20 lifeboats against 2,224 people |
| – | The Iceberg | 11:40 PM, alarm, top-down hard-a-starboard turn, buckled plates, six compartments breached |
| – | The Sinking | Bow down, lights fail, break-up, 2:20 AM, 1,500+ lives lost |
| V | Aftermath | Dawn, SOLAS 1914, descent to the 3,800 m wreck, end title |

## Pipeline

| File | Role |
|------|------|
| `script.py` | Narration text, voice settings, pauses |
| `tts.py` | Synthesises each sentence with Microsoft Edge neural TTS (`en-GB-RyanNeural`) and builds `build/timeline.json`, which drives scene lengths and subtitle timing |
| `music.py` | Score (string pads, piano, sub drone, engine pulse) and SFX (ocean, rivets, horn, door thuds, ship's bell, ice scrape, popping rivets, steel groans, break-up boom), with reverb and ducking under the voice |
| `common.py` | Drawing primitives: ship model, sky, stars, sea, smoke, sparks, text |
| `scenes.py` | The eight scenes |
| `render.py` | Frame renderer and post stack (bloom, filmic curve, split-tone grade, vignette, film grain, 2.39:1 letterbox, subtitles). Renders segments in parallel and pipes them to ffmpeg |
| `build.sh` | Runs everything end to end and writes `titanic.mp4` |

## Build

Needs Python 3.11 or newer, ffmpeg, libcairo2-dev, and network access for the TTS.

```bash
python3 -m venv .venv && . .venv/bin/activate
pip install numpy scipy pillow pycairo edge-tts
PYTHON=python ./build.sh            # WORKERS=6 by default
```

Preview single frames while you work:

```bash
python render.py --still 16 95.5    # -> build/still_016.00.png ...
python render.py --contact          # -> build/contact.png (4 frames per scene)
```

Output: 1920×1080, 30 fps, H.264 + AAC, loudness-normalised to −16 LUFS.
