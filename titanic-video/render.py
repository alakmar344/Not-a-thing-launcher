"""Render the film: draws every frame with cairo, applies a cinematic post stack in numpy,
and streams the frames into ffmpeg in parallel segments.

  python render.py                 # full render -> build/video.mp4 (silent)
  python render.py --still 12.5    # preview a single frame -> build/still_12.50.png
  python render.py --contact       # contact sheet of a few frames per scene
"""
import argparse
import json
import math
import os
import subprocess
import sys
from multiprocessing import Pool

import cairo
import numpy as np
from PIL import Image, ImageFilter

import scenes
from common import BAR, FPS, H, SERIF_BODY, W, clamp, ramp, text, text_width

ROOT = os.path.dirname(os.path.abspath(__file__))
BUILD = os.path.join(ROOT, "build")

# per-scene grade: (shadow tint BGR, highlight tint BGR, saturation, contrast, bloom)
GRADES = {
    "intro": ((0.06, 0.03, 0.0), (0.0, 0.03, 0.06), 1.05, 0.35, 0.55),
    "build": ((0.05, 0.03, 0.0), (0.0, 0.03, 0.07), 0.95, 0.35, 0.6),
    "engines": ((0.06, 0.03, 0.0), (0.0, 0.03, 0.06), 1.05, 0.3, 0.7),
    "compartments": ((0.06, 0.03, 0.0), (0.0, 0.02, 0.04), 1.0, 0.3, 0.45),
    "flaw": ((0.07, 0.03, 0.0), (0.0, 0.02, 0.05), 0.95, 0.35, 0.5),
    "iceberg": ((0.08, 0.04, 0.0), (0.03, 0.02, 0.0), 0.9, 0.35, 0.6),
    "sinking": ((0.08, 0.04, 0.0), (0.0, 0.02, 0.05), 0.9, 0.4, 0.65),
    "legacy": ((0.06, 0.03, 0.0), (0.0, 0.03, 0.06), 1.0, 0.3, 0.55),
}

_vig = None
_grain = None
_luts = {}


def vignette():
    """Vignette as 8.8 fixed point (256 = 1.0)."""
    global _vig
    if _vig is None:
        y, x = np.mgrid[0:H, 0:W].astype(np.float32)
        d = np.sqrt(((x - W / 2) / (W / 2)) ** 2 + ((y - H / 2) / (H / 2)) ** 2 * 0.8)
        v = 1 - 0.55 * np.clip((d - 0.45) / 0.9, 0, 1) ** 1.6
        _vig = (v * 256).astype(np.int32)[..., None]
    return _vig


def grain_tex():
    global _grain
    if _grain is None:
        r = np.random.default_rng(3)
        g = r.normal(0, 1, (H + 64, W + 64)).astype(np.float32)
        # soften to film-like grain clumps
        im = Image.fromarray(((g * 40) + 128).clip(0, 255).astype(np.uint8))
        im = im.filter(ImageFilter.GaussianBlur(0.6))
        _grain = ((np.asarray(im).astype(np.float32) - 128) / 40 * 7.5).astype(np.int32)
    return _grain


def tone_luts(grade, fade):
    """Per-channel LUT over 0..511 (picture + bloom): filmic shoulder, S-curve, split tone."""
    key = (grade, round(fade, 3))
    if key not in _luts:
        sh, hi, sat, con, _ = grade
        v = np.arange(512, dtype=np.float64) / 255
        v = v / (1 + 0.15 * v)
        sc = np.clip(v * v * (3 - 2 * v), 0, 1)
        v = v + (sc - v) * con
        luts = []
        for c in range(3):
            o = v + sh[c] * (1 - np.clip(v, 0, 1)) ** 2 + hi[c] * np.clip(v, 0, 1) ** 2
            luts.append(np.clip(o * fade * 255 + 0.5, 0, 255).astype(np.int32))
        if len(_luts) > 64:
            _luts.clear()
        _luts[key] = luts
    return _luts[key]


def post(buf, fi, grade, fade):
    """Cinematic post stack, integer/LUT based so it is cheap on a single core."""
    sat, bloom_amt = grade[2], grade[4]
    rgb = buf[..., :3]
    # bloom: threshold + blur at quarter / sixteenth resolution
    small = np.asarray(Image.fromarray(np.ascontiguousarray(rgb)).reduce(4)).astype(np.float32) / 255
    lum = small.max(2, keepdims=True)
    bright = Image.fromarray((small * np.clip((lum - 0.55) / 0.45, 0, 1) * 255).astype(np.uint8))
    b1 = np.asarray(bright.filter(ImageFilter.GaussianBlur(5))).astype(np.float32)
    b2 = np.asarray(bright.reduce(4).filter(ImageFilter.GaussianBlur(4))
                    .resize((W // 4, H // 4), Image.BILINEAR)).astype(np.float32)
    bl = np.clip((b1 * 0.8 + b2 * 1.1) * bloom_amt, 0, 255).astype(np.uint8)
    blf = np.asarray(Image.fromarray(bl).resize((W, H), Image.BILINEAR))
    x = rgb.astype(np.int32) + blf
    np.minimum(x, 511, out=x)
    luts = tone_luts(grade, fade)
    out = np.empty((H, W, 3), np.int32)
    for c in range(3):
        out[..., c] = luts[c][x[..., c]]
    if abs(sat - 1) > 0.01:
        L = (out[..., 0] * 29 + out[..., 1] * 150 + out[..., 2] * 77) >> 8
        q = int(sat * 256)
        out = L[..., None] + (((out - L[..., None]) * q) >> 8)
    out *= vignette()
    out >>= 8
    g = grain_tex()
    ox, oy = (fi * 37) % 64, (fi * 53) % 64
    out += g[oy:oy + H, ox:ox + W, None] if fade > 0.999 else (g[oy:oy + H, ox:ox + W, None] * fade).astype(np.int32)
    np.clip(out, 0, 255, out=out)
    buf[..., :3] = out


def wrap(ctx, s, size, maxw):
    words, lines, cur = s.split(), [], ""
    for w in words:
        trial = (cur + " " + w).strip()
        if text_width(ctx, trial, size, font=SERIF_BODY, italic=True) > maxw and cur:
            lines.append(cur)
            cur = w
        else:
            cur = trial
    lines.append(cur)
    if len(lines) == 2 and len(lines[1].split()) <= 2:
        # balance a widow onto two even lines
        ws = s.split()
        mid = len(ws) // 2
        lines = [" ".join(ws[:mid]), " ".join(ws[mid:])]
    return lines


def overlay(ctx, t, tl):
    ctx.set_source_rgb(0, 0, 0)
    ctx.rectangle(0, 0, W, BAR)
    ctx.rectangle(0, H - BAR, W, BAR)
    ctx.fill()
    for sc in tl["scenes"]:
        for c in sc["cues"]:
            if c["start"] - 0.25 <= t <= c["end"] + 0.35:
                a = ramp(t, c["start"] - 0.25, c["start"] + 0.1) * (1 - ramp(t, c["end"], c["end"] + 0.35))
                lines = wrap(ctx, c["text"], 34, 1500)
                y0 = H - BAR / 2 + 11 - (len(lines) - 1) * 21
                for i, ln in enumerate(lines):
                    text(ctx, ln, W / 2, y0 + i * 42, 34, font=SERIF_BODY, italic=True,
                         color=(0.93, 0.9, 0.82), alpha=a * 0.95, shadow=0)


def scene_at(tl, t):
    for i, sc in enumerate(tl["scenes"]):
        if t < sc["end"] or i == len(tl["scenes"]) - 1:
            return i, sc
    return 0, tl["scenes"][0]


def render_frame(fi, tl, surf=None):
    t = fi / FPS
    idx, sc = scene_at(tl, t)
    lt = t - sc["start"]
    dur = sc["end"] - sc["start"]
    surf = surf or cairo.ImageSurface(cairo.FORMAT_RGB24, W, H)
    ctx = cairo.Context(surf)
    ctx.set_source_rgb(0, 0, 0)
    ctx.paint()
    info = {"dur": dur, "cues": [(c["start"] - sc["start"], c["end"] - sc["start"]) for c in sc["cues"]],
            "abs": t}
    ctx.save()
    getattr(scenes, "scene_" + sc["name"])(ctx, lt, info)
    ctx.restore()
    surf.flush()
    buf = np.ndarray((H, W, 4), np.uint8, surf.get_data())
    fin = 2.2 if idx == 0 else 0.9
    fout = 3.0 if idx == len(tl["scenes"]) - 1 else 0.8
    fade = ramp(lt, 0, fin) * (1 - ramp(lt, dur - fout, dur))
    post(buf, fi, GRADES[sc["name"]], fade)
    surf.mark_dirty()
    ctx = cairo.Context(surf)
    overlay(ctx, t, tl)
    surf.flush()
    return surf, buf


def load_tl():
    with open(os.path.join(BUILD, "timeline.json")) as fh:
        return json.load(fh)


def render_segment(args):
    seg, f0, f1 = args
    tl = load_tl()
    out = os.path.join(BUILD, "segs", f"seg_{seg:03d}.mp4")
    if os.path.exists(out + ".done"):
        return out
    cmd = ["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "bgra", "-s", f"{W}x{H}",
           "-r", str(FPS), "-i", "-", "-c:v", "libx264", "-preset", "faster", "-crf", "17",
           "-tune", "film", "-pix_fmt", "yuv420p", "-threads", "1", "-x264-params", "rc-lookahead=20", out]
    p = subprocess.Popen(cmd, stdin=subprocess.PIPE)
    surf = cairo.ImageSurface(cairo.FORMAT_RGB24, W, H)
    for fi in range(f0, f1):
        _, buf = render_frame(fi, tl, surf)
        p.stdin.write(buf.tobytes())
    p.stdin.close()
    p.wait()
    if p.returncode:
        raise RuntimeError(f"ffmpeg failed on segment {seg}")
    open(out + ".done", "w").close()
    print(f"segment {seg} done ({f0}-{f1})", flush=True)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--still", type=float, nargs="*")
    ap.add_argument("--contact", action="store_true")
    ap.add_argument("--workers", type=int, default=6)
    ap.add_argument("--seconds", type=float, default=None, help="render only the first N seconds")
    a = ap.parse_args()
    tl = load_tl()

    if a.still:
        for s in a.still:
            surf, _ = render_frame(int(round(s * FPS)), tl)
            path = os.path.join(BUILD, f"still_{s:06.2f}.png")
            surf.write_to_png(path)
            print(path)
        return
    if a.contact:
        frames = []
        for sc in tl["scenes"]:
            for k in (0.15, 0.4, 0.65, 0.9):
                frames.append(sc["start"] + (sc["end"] - sc["start"]) * k)
        with Pool(min(a.workers, len(frames))) as pool:
            ims = pool.map(_still_small, frames)
        sheet = Image.new("RGB", (480 * 4, 270 * len(tl["scenes"])))
        for i, im in enumerate(ims):
            sheet.paste(im, ((i % 4) * 480, (i // 4) * 270))
        sheet.save(os.path.join(BUILD, "contact.png"))
        print(os.path.join(BUILD, "contact.png"))
        return

    total = int(math.ceil((a.seconds or tl["duration"]) * FPS))
    os.makedirs(os.path.join(BUILD, "segs"), exist_ok=True)
    chunk = 120
    jobs = [(i, f0, min(total, f0 + chunk)) for i, f0 in enumerate(range(0, total, chunk))]
    with Pool(a.workers) as pool:
        outs = list(pool.imap(render_segment, jobs))
    lst = os.path.join(BUILD, "segs", "list.txt")
    with open(lst, "w") as fh:
        for o in outs:
            fh.write(f"file '{os.path.basename(o)}'\n")
    subprocess.run(["ffmpeg", "-v", "error", "-y", "-f", "concat", "-safe", "0", "-i", lst,
                    "-c", "copy", os.path.join(BUILD, "video.mp4")], check=True)
    print("video ->", os.path.join(BUILD, "video.mp4"))


def _still_small(s):
    surf, _ = render_frame(int(round(s * FPS)), load_tl())
    buf = np.ndarray((H, W, 4), np.uint8, surf.get_data())
    return Image.fromarray(buf[..., 2::-1].copy()).resize((480, 270), Image.LANCZOS)


if __name__ == "__main__":
    sys.exit(main())
