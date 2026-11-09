"""Synthesise the narration with a neural TTS voice and build the timeline.

Outputs (in build/):
  voice/<scene>_<n>.mp3   one clip per sentence
  narration.wav           all clips placed on the master timeline
  timeline.json           scene start/end + subtitle cues, consumed by render.py
"""
import asyncio
import json
import os
import subprocess

import edge_tts
import numpy as np

from script import GAP, LEAD_IN, PAUSES, PITCH, RATE, SCENES, TAIL, VOICE

SR = 48000
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "build")
VOICE_DIR = os.path.join(OUT, "voice")


async def synth(text, path, sem):
    async with sem:
        if os.path.exists(path) and os.path.getsize(path) > 0:
            return
        for attempt in range(5):
            try:
                await edge_tts.Communicate(text, VOICE, rate=RATE, pitch=PITCH).save(path)
                return
            except Exception as exc:  # network hiccups
                print("retry", path, exc)
                await asyncio.sleep(2 + attempt)
        raise RuntimeError(f"TTS failed for {path}")


def decode(path):
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", path, "-f", "f32le", "-ac", "1", "-ar", str(SR), "-"],
        check=True, capture_output=True).stdout
    return np.frombuffer(raw, dtype=np.float32)


async def main():
    os.makedirs(VOICE_DIR, exist_ok=True)
    sem = asyncio.Semaphore(6)
    jobs = []
    for name, lines in SCENES:
        for i, line in enumerate(lines):
            jobs.append(synth(line, os.path.join(VOICE_DIR, f"{name}_{i}.mp3"), sem))
    await asyncio.gather(*jobs)

    clips, scenes, t = [], [], 0.0
    for name, lines in SCENES:
        start = t
        t += LEAD_IN
        cues = []
        for i, line in enumerate(lines):
            audio = decode(os.path.join(VOICE_DIR, f"{name}_{i}.mp3"))
            dur = len(audio) / SR
            clips.append((t, audio))
            cues.append({"start": round(t, 3), "end": round(t + dur, 3), "text": line})
            t += dur + GAP + PAUSES.get((name, i), 0.0)
        t += TAIL - GAP
        scenes.append({"name": name, "start": round(start, 3), "end": round(t, 3), "cues": cues})

    total = t
    mix = np.zeros(int(total * SR) + SR, dtype=np.float32)
    for start, audio in clips:
        s = int(start * SR)
        mix[s:s + len(audio)] += audio
    peak = np.abs(mix).max() or 1.0
    mix = (mix / peak * 0.89).astype(np.float32)
    write_wav(os.path.join(OUT, "narration.wav"), mix)

    with open(os.path.join(OUT, "timeline.json"), "w") as fh:
        json.dump({"duration": round(total, 3), "scenes": scenes}, fh, indent=1)
    print(f"narration: {total:.1f}s across {len(scenes)} scenes")


def write_wav(path, mono):
    import wave
    pcm = (np.clip(mono, -1, 1) * 32767).astype(np.int16)
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())


if __name__ == "__main__":
    asyncio.run(main())
