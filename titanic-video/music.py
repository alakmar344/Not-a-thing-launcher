"""Procedural score + sound design, mixed under the narration.

Reads build/timeline.json and build/narration.wav, writes build/soundtrack.wav (stereo, 48 kHz).
Everything is synthesised with numpy - no samples needed.
"""
import json
import os
import wave

import numpy as np
from scipy.signal import butter, fftconvolve, sosfilt

SR = 48000
ROOT = os.path.dirname(os.path.abspath(__file__))
BUILD = os.path.join(ROOT, "build")
rng = np.random.default_rng(1912)

CH = {
    "Dm": [38, 50, 57, 62, 65], "Bb": [34, 46, 53, 62, 65], "F": [29, 41, 53, 60, 69],
    "C": [36, 48, 55, 64, 67], "Gm": [31, 43, 55, 62, 70], "A": [33, 45, 57, 64, 73],
    "Eb": [39, 51, 58, 63, 67], "Am": [33, 45, 57, 64, 72], "Fmaj": [29, 41, 57, 65, 69],
}
PLAN = {
    #           chords                          pad  pulse piano
    "intro": (["Dm", "Bb", "F", "C"], 0.9, 0.0, 0.7),
    "build": (["F", "C", "Dm", "Bb", "F", "C"], 0.8, 0.35, 0.3),
    "engines": (["Dm", "Dm", "Bb", "C", "Dm", "Bb"], 0.75, 0.8, 0.0),
    "compartments": (["F", "C", "Bb", "F", "C", "F"], 0.85, 0.25, 0.5),
    "flaw": (["Dm", "Gm", "A", "Dm", "Gm"], 0.8, 0.0, 0.3),
    "iceberg": (["Dm", "Eb", "Dm", "A", "Eb", "A"], 0.9, 0.15, 0.0),
    "sinking": (["Dm", "Bb", "Gm", "A", "Dm"], 1.0, 0.0, 0.6),
    "legacy": (["Bb", "F", "C", "Dm", "Bb", "Fmaj"], 0.9, 0.0, 0.7),
}


def hz(m):
    return 440.0 * 2 ** ((m - 69) / 12)


def env_adsr(n, a, r):
    e = np.ones(n, np.float32)
    na, nr = int(a * SR), int(r * SR)
    na, nr = min(na, n // 2), min(nr, n // 2)
    if na:
        e[:na] = np.linspace(0, 1, na) ** 1.5
    if nr:
        e[-nr:] *= np.linspace(1, 0, nr) ** 1.5
    return e


def pad_note(m, n, bright=0.5, detune=(0.0, 0.07, -0.06)):
    t = np.arange(n, dtype=np.float32) / SR
    out = np.zeros((n, 2), np.float32)
    f0 = hz(m)
    vib = 1 + 0.0025 * np.sin(2 * np.pi * 4.8 * t + rng.uniform(0, 6))
    for vi, d in enumerate(detune):
        f = f0 * 2 ** (d / 12)
        sig = np.zeros(n, np.float32)
        for h in range(1, 9):
            if f * h > 9000:
                break
            amp = (1 / h) * np.exp(-(h - 1) * (1.1 - bright))
            sig += amp * np.sin(2 * np.pi * f * h * t * vib + rng.uniform(0, 6))
        pan = (vi - 1) * 0.6
        out[:, 0] += sig * (1 - pan) * 0.5
        out[:, 1] += sig * (1 + pan) * 0.5
    return out / len(detune)


def piano_note(m, dur=3.5):
    n = int(dur * SR)
    t = np.arange(n, dtype=np.float32) / SR
    f = hz(m)
    sig = np.zeros(n, np.float32)
    for h, a in ((1, 1.0), (2, 0.45), (3, 0.2), (4, 0.12), (5, 0.06)):
        sig += a * np.sin(2 * np.pi * f * h * 1.0005 ** h * t) * np.exp(-t * (1.2 + h * 0.9))
    sig *= np.minimum(1, t * 300)
    return sig


def bell(dur=4.0, f=520):
    n = int(dur * SR)
    t = np.arange(n, dtype=np.float32) / SR
    sig = np.zeros(n, np.float32)
    for r, a, d in ((1, 1, 0.9), (2.0, 0.6, 1.4), (2.76, 0.5, 1.8), (5.4, 0.25, 3), (8.9, 0.12, 4)):
        sig += a * np.sin(2 * np.pi * f * r * t) * np.exp(-t * d)
    return sig * np.minimum(1, t * 2000)


def clink(f):
    n = int(0.6 * SR)
    t = np.arange(n, dtype=np.float32) / SR
    sig = sum(a * np.sin(2 * np.pi * f * r * t) * np.exp(-t * (14 + 10 * r))
              for r, a in ((1, 1), (2.7, 0.5), (5.1, 0.3)))
    return sig * np.minimum(1, t * 3000)


def noise(n):
    return rng.standard_normal(n).astype(np.float32)


def bandpass(x, lo, hi, order=2):
    return sosfilt(butter(order, [lo, hi], btype="band", fs=SR, output="sos"), x).astype(np.float32)


def lowpass(x, f, order=2):
    return sosfilt(butter(order, f, btype="low", fs=SR, output="sos"), x).astype(np.float32)


def add(buf, start_s, sig, gain=1.0, pan=0.0):
    s = int(start_s * SR)
    if s >= len(buf):
        return
    if sig.ndim == 1:
        sig = np.stack([sig * (1 - pan) * 0.5 * 2 ** 0.5, sig * (1 + pan) * 0.5 * 2 ** 0.5], 1)
    if s < 0:
        sig, s = sig[-s:], 0
    e = min(len(buf), s + len(sig))
    buf[s:e] += sig[:e - s] * gain


def read_wav(path):
    with wave.open(path) as w:
        data = np.frombuffer(w.readframes(w.getnframes()), np.int16).astype(np.float32) / 32768
    return data


def write_wav(path, st):
    pcm = (np.clip(st, -1, 1) * 32767).astype(np.int16)
    with wave.open(path, "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())


def reverb_ir(seconds=3.2):
    n = int(seconds * SR)
    t = np.arange(n) / SR
    ir = np.stack([noise(n), noise(n)], 1) * np.exp(-t * 2.2)[:, None]
    ir = np.stack([lowpass(ir[:, 0], 5000), lowpass(ir[:, 1], 5000)], 1)
    ir[0] = 1.0
    return ir / np.abs(ir).sum(0) * 6


def main():
    tl = json.load(open(os.path.join(BUILD, "timeline.json")))
    total = tl["duration"] + 1.0
    N = int(total * SR)
    music = np.zeros((N, 2), np.float32)
    fx = np.zeros((N, 2), np.float32)
    sc = {s["name"]: s for s in tl["scenes"]}

    # ---------------- score
    for s in tl["scenes"]:
        chords, padg, pulse, piano = PLAN[s["name"]]
        st, en = s["start"], s["end"]
        seg = (en - st) / len(chords)
        for i, ch in enumerate(chords):
            t0 = st + i * seg - 0.8
            dur = seg + 1.8
            n = int(dur * SR)
            e = env_adsr(n, 1.4, 1.6)[:, None]
            for k, m in enumerate(CH[ch]):
                g = padg * (0.55 if k == 0 else 0.22) * (0.8 if m > 64 else 1.0)
                add(music, t0, pad_note(m, n, bright=0.35 + 0.15 * (k > 0)) * e, g)
            # sub drone
            tt = np.arange(n) / SR
            add(music, t0, np.sin(2 * np.pi * hz(CH[ch][0] - 12 if CH[ch][0] > 36 else CH[ch][0]) * tt)
                * env_adsr(n, 1.4, 1.6), 0.25 * padg)
            if piano > 0:
                notes = CH[ch][1:] + [CH[ch][2] + 12]
                step = seg / 6
                for j in range(6):
                    m = notes[j % len(notes)] + 12
                    add(music, t0 + 0.8 + j * step, piano_note(m), 0.16 * piano,
                        pan=0.3 * np.sin(j * 1.7))
        if pulse > 0:
            beat = 0.8
            t = st + 0.5
            while t < en - 0.5:
                n = int(0.5 * SR)
                tt = np.arange(n) / SR
                kick = np.sin(2 * np.pi * (55 + 60 * np.exp(-tt * 30)) * tt) * np.exp(-tt * 8)
                add(music, t, kick, 0.55 * pulse)
                t += beat

    # ---------------- sound design
    # ocean ambience everywhere except the engine-room cutaway and the abyss
    wv = lowpass(noise(N), 600)
    tt = np.arange(N) / SR
    swell = 0.55 + 0.45 * np.sin(2 * np.pi * tt / 7.3) * np.sin(2 * np.pi * tt / 3.1 + 1)
    ocean_gain = np.zeros(N, np.float32)
    for name, g in (("intro", 0.5), ("iceberg", 0.25), ("sinking", 0.35), ("flaw", 0.15),
                    ("compartments", 0.2), ("legacy", 0.35)):
        s = sc[name]
        a, b = int(s["start"] * SR), int(s["end"] * SR)
        ocean_gain[a:b] = g
    ocean_gain = lowpass(ocean_gain, 0.5, 1)
    fx[:, 0] += wv * swell * ocean_gain * 0.5
    fx[:, 1] += np.roll(wv, 4000) * swell * ocean_gain * 0.5

    def cue(name, i):
        return sc[name]["cues"][i]["start"]

    # intro: ship's horn
    for t0 in (cue("intro", 1) + 2.0,):
        n = int(3.2 * SR)
        t = np.arange(n) / SR
        horn = sum(np.sin(2 * np.pi * f * t) / (k + 1) for k, f in enumerate((110, 165, 220, 330)))
        add(fx, t0, lowpass(horn * env_adsr(n, 0.25, 1.0), 900), 0.25)
    # Belfast: rivet hammers
    s = sc["build"]
    t = s["start"] + 1
    while t < s["end"] - 1:
        add(fx, t, clink(rng.uniform(1800, 3200)), 0.10 * rng.uniform(0.4, 1), pan=rng.uniform(-.7, .7))
        t += rng.uniform(0.12, 0.5)
    # engines: machine rhythm + hiss
    s = sc["engines"]
    n = int((s["end"] - s["start"]) * SR)
    t = np.arange(n) / SR
    chug = (0.5 + 0.5 * np.sin(2 * np.pi * 5.0 * t)) ** 4
    mach = bandpass(noise(n), 80, 400) * chug + 0.3 * bandpass(noise(n), 2000, 6000) * 0.3
    add(fx, s["start"], mach * env_adsr(n, 1.5, 1.5), 0.35)
    # compartments: doors slamming
    s = sc["compartments"]
    for k in range(15):
        t0 = s["start"] + s["cues"][2]["start"] - s["start"] + 2.0 + k * 3.5 / 17
        n = int(0.4 * SR)
        tt = np.arange(n) / SR
        thud = np.sin(2 * np.pi * 70 * tt) * np.exp(-tt * 18) + 0.3 * bandpass(noise(n), 300, 2000) * np.exp(-tt * 40)
        add(fx, t0, thud, 0.35, pan=(k / 7 - 1) * 0.6)
    # flaw: water pouring
    s = sc["flaw"]
    t0 = s["cues"][2]["start"] + 0.3
    n = int(8 * SR)
    pour = bandpass(noise(n), 300, 3000) * env_adsr(n, 0.8, 2.0)
    add(fx, t0, pour, 0.18)
    # iceberg: three bells, then the scrape and groaning steel
    a = cue("iceberg", 2) - 0.5
    for k in range(3):
        add(fx, a + k * 0.45, bell(), 0.22, pan=0.2)
    st = sc["iceberg"]["cues"][3]["start"] + 1.5
    n = int(5 * SR)
    tt = np.arange(n) / SR
    grind = bandpass(noise(n), 150, 1800) * (0.6 + 0.4 * np.sin(2 * np.pi * 11 * tt) ** 2)
    add(fx, st, grind * env_adsr(n, 0.4, 1.5), 0.45)
    pop0 = sc["iceberg"]["cues"][4]["start"] + 0.4
    for k in range(14):
        add(fx, pop0 + k * 0.17 + rng.uniform(0, 0.1), clink(rng.uniform(900, 1600)), 0.25,
            pan=rng.uniform(-.6, .6))
    n = int(6 * SR)
    add(fx, pop0 + 0.3, bandpass(noise(n), 400, 5000) * env_adsr(n, 0.5, 2.5), 0.15)
    # sinking: groans and the break
    s = sc["sinking"]
    for k in range(4):
        t0 = s["start"] + 3 + k * 4
        n = int(3 * SR)
        tt = np.arange(n) / SR
        f = 60 + 25 * np.sin(2 * np.pi * 0.4 * tt + k)
        groan = np.sin(2 * np.pi * np.cumsum(f) / SR) * bandpass(noise(n), 40, 300) * 3
        add(fx, t0, groan * env_adsr(n, 0.8, 1.2), 0.25)
    tb = s["cues"][2]["start"] + 3.6
    n = int(5 * SR)
    tt = np.arange(n) / SR
    boom = np.sin(2 * np.pi * (35 + 40 * np.exp(-tt * 3)) * tt) * np.exp(-tt * 1.3)
    boom += lowpass(noise(n), 900) * np.exp(-tt * 2.5) * 0.8
    add(fx, tb, boom, 0.7)
    # legacy: underwater rumble during the descent
    s = sc["legacy"]
    t0 = s["cues"][2]["start"] - 0.6
    n = int(8 * SR)
    add(fx, t0, lowpass(noise(n), 180) * env_adsr(n, 1.5, 2.5) * 3, 0.25)
    for k in range(4):
        add(fx, t0 + 2 + k * 1.6, piano_note(86 + (k % 2) * 3, 2.5) * 0.5, 0.1, pan=0.5 - k * 0.3)

    # ---------------- reverb, ducking, mix
    ir = reverb_ir()
    wet = np.stack([fftconvolve(music[:, c], ir[:, c])[:N] for c in range(2)], 1)
    music = music * 0.55 + wet * 0.65
    fxw = np.stack([fftconvolve(fx[:, c], ir[:, c] * 0.5)[:N] for c in range(2)], 1)
    fx = fx * 0.8 + fxw * 0.35

    voice = read_wav(os.path.join(BUILD, "narration.wav"))
    voice = np.pad(voice, (0, max(0, N - len(voice))))[:N]
    env = lowpass(np.abs(voice), 3, 1)
    env = np.clip(env / (env.max() * 0.25), 0, 1)
    duck = 1 - 0.55 * env
    music *= duck[:, None]
    fx *= (1 - 0.35 * env)[:, None]

    def norm(x, peak):
        return x / (np.abs(x).max() + 1e-9) * peak

    music = norm(music, 0.32)
    fx = norm(fx, 0.38)
    voice_st = np.stack([voice, voice], 1) * 0.92
    # gentle global fade in/out
    fade = np.ones(N, np.float32)
    fade[: int(1.5 * SR)] = np.linspace(0, 1, int(1.5 * SR))
    fade[-int(4 * SR):] = np.linspace(1, 0, int(4 * SR))
    mix = (music + fx) * fade[:, None] + voice_st
    mix = np.tanh(mix * 1.1) / np.tanh(1.1)
    write_wav(os.path.join(BUILD, "soundtrack.wav"), mix)
    write_wav(os.path.join(BUILD, "music_only.wav"), (music + fx) * fade[:, None])
    print("soundtrack ->", os.path.join(BUILD, "soundtrack.wav"), f"{N / SR:.1f}s")


if __name__ == "__main__":
    main()
