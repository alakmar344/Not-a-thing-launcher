"""Shared drawing primitives: easing, text, sky, sea, the ship itself, particles."""
import math

import cairo
import numpy as np

W, H = 1920, 1080
FPS = 30
BAR = 132  # letterbox bar height (~2.39:1 picture)

SERIF_TITLE = "Cinzel"
SERIF_BODY = "Cormorant Garamond"


# ----------------------------------------------------------------- maths helpers
def clamp(x, a=0.0, b=1.0):
    return a if x < a else b if x > b else x


def lerp(a, b, t):
    return a + (b - a) * t


def lerpc(c1, c2, t):
    return tuple(lerp(a, b, t) for a, b in zip(c1, c2))


def smooth(t):
    t = clamp(t)
    return t * t * (3 - 2 * t)


def ease_io(t):
    t = clamp(t)
    return 0.5 - 0.5 * math.cos(math.pi * t)


def ease_out(t):
    t = clamp(t)
    return 1 - (1 - t) ** 3


def ease_in(t):
    t = clamp(t)
    return t ** 3


def ramp(t, a, b):
    if b <= a:
        return 1.0 if t >= a else 0.0
    return smooth((t - a) / (b - a))


def window(t, a, b, fi=0.6, fo=0.6):
    return ramp(t, a, a + fi) * (1 - ramp(t, b - fo, b))


def hash01(*args):
    x = 0.0
    for i, a in enumerate(args):
        x += a * (12.9898 + 31.7 * i) + 78.233 * i
    s = math.sin(x) * 43758.5453
    return s - math.floor(s)


def hexc(h):
    h = h.lstrip("#")
    return tuple(int(h[i:i + 2], 16) / 255 for i in (0, 2, 4))


def keyframes(t, keys):
    """keys: [(time, (values...)), ...] -> eased interpolation."""
    if t <= keys[0][0]:
        return keys[0][1]
    for (t0, v0), (t1, v1) in zip(keys, keys[1:]):
        if t <= t1:
            k = ease_io((t - t0) / (t1 - t0)) if t1 > t0 else 1
            return tuple(lerp(a, b, k) for a, b in zip(v0, v1))
    return keys[-1][1]


def camera(ctx, cx, cy, z, sx=0.0, sy=0.0, rot=0.0):
    ctx.translate(W / 2 + sx, H / 2 + sy)
    if rot:
        ctx.rotate(rot)
    ctx.scale(z, z)
    ctx.translate(-cx, -cy)


def rgba(ctx, c, a=1.0):
    ctx.set_source_rgba(c[0], c[1], c[2], a)


# ----------------------------------------------------------------- text
def text(ctx, s, x, y, size, font=SERIF_TITLE, color=(1, 1, 1), alpha=1.0, align="center",
         spacing=0.0, italic=False, bold=False, glow=0.0, glow_color=None, shadow=0.5):
    if alpha <= 0.002 or not s:
        return 0
    ctx.save()
    ctx.select_font_face(font, cairo.FONT_SLANT_ITALIC if italic else cairo.FONT_SLANT_NORMAL,
                         cairo.FONT_WEIGHT_BOLD if bold else cairo.FONT_WEIGHT_NORMAL)
    ctx.set_font_size(size)
    adv = [ctx.text_extents(ch).x_advance for ch in s]
    total = sum(adv) + spacing * (len(s) - 1)
    x0 = x - total / 2 if align == "center" else (x - total if align == "right" else x)
    ctx.new_path()
    cx = x0
    for ch, a in zip(s, adv):
        ctx.move_to(cx, y)
        ctx.text_path(ch)
        cx += a + spacing
    path = ctx.copy_path()
    if shadow:
        ctx.save()
        ctx.translate(0, size * 0.04)
        ctx.new_path()
        ctx.append_path(path)
        ctx.set_source_rgba(0, 0, 0, alpha * shadow)
        ctx.fill()
        ctx.restore()
    if glow > 0:
        gc = glow_color or color
        for i, wdt in enumerate((size * 0.35, size * 0.2, size * 0.1)):
            ctx.new_path()
            ctx.append_path(path)
            ctx.set_line_width(wdt)
            ctx.set_line_join(cairo.LINE_JOIN_ROUND)
            rgba(ctx, gc, alpha * glow * (0.05 + 0.05 * i))
            ctx.stroke()
    ctx.new_path()
    ctx.append_path(path)
    rgba(ctx, color, alpha)
    ctx.fill()
    ctx.restore()
    return total


def text_width(ctx, s, size, font=SERIF_TITLE, spacing=0.0, italic=False, bold=False):
    ctx.save()
    ctx.select_font_face(font, cairo.FONT_SLANT_ITALIC if italic else cairo.FONT_SLANT_NORMAL,
                         cairo.FONT_WEIGHT_BOLD if bold else cairo.FONT_WEIGHT_NORMAL)
    ctx.set_font_size(size)
    w = sum(ctx.text_extents(ch).x_advance for ch in s) + spacing * (len(s) - 1)
    ctx.restore()
    return w


def chapter_card(ctx, t, numeral, title, start=0.3, end=5.0, y=250):
    """Small cinematic chapter heading: numeral, gold rules, letter-spaced title."""
    a = window(t, start, end, 1.0, 1.0)
    if a <= 0:
        return
    gold = hexc("#d9b46a")
    spread = ease_out((t - start) / 1.6)
    text(ctx, numeral, W / 2, y - 46, 26, color=gold, alpha=a * 0.9, spacing=6)
    w = text_width(ctx, title, 34, spacing=10)
    half = (w / 2 + 40) * spread
    ctx.save()
    ctx.set_line_width(1.2)
    for sgn in (-1, 1):
        g = cairo.LinearGradient(W / 2, 0, W / 2 + sgn * half, 0)
        g.add_color_stop_rgba(0, gold[0], gold[1], gold[2], 0.0)
        g.add_color_stop_rgba(0.5, gold[0], gold[1], gold[2], 0.8 * a)
        g.add_color_stop_rgba(1, gold[0], gold[1], gold[2], 0.0)
        ctx.set_source(g)
        ctx.move_to(W / 2, y + 22)
        ctx.line_to(W / 2 + sgn * half, y + 22)
        ctx.stroke()
    ctx.restore()
    text(ctx, title, W / 2, y, 34, color=(0.96, 0.93, 0.86), alpha=a, spacing=10 + 6 * (1 - spread))


def label(ctx, s, x, y, alpha, size=24, color=(0.92, 0.9, 0.84), align="left", line_to=None,
          accent=None):
    """Annotation label with optional leader line to a point."""
    if alpha <= 0:
        return
    accent = accent or hexc("#d9b46a")
    if line_to is not None:
        ctx.save()
        ctx.set_line_width(1.4)
        rgba(ctx, accent, 0.8 * alpha)
        lx, ly = line_to
        sx = x - 12 if align == "left" else x + 12
        ctx.move_to(sx, y - size * 0.35)
        ctx.line_to(lx, ly)
        ctx.stroke()
        ctx.arc(lx, ly, 4, 0, 2 * math.pi)
        ctx.fill()
        ctx.restore()
    text(ctx, s, x, y, size, color=color, alpha=alpha, align=align, spacing=3)


# ----------------------------------------------------------------- sky & stars
_STARS = {}


def stars_data(seed=7, n=900):
    if seed not in _STARS:
        r = np.random.default_rng(seed)
        xs = r.uniform(-500, W + 500, n)
        # denser band (milky way) running diagonally
        band = r.random(n) < 0.45
        ys = np.where(band, (xs * 0.28 + 80 + r.normal(0, 70, n)), r.uniform(-300, 760, n))
        size = r.power(4.5, n) * 2.2 + 0.4
        size = np.where(r.random(n) < 0.97, size * 0.6, size * 1.4)
        phase = r.uniform(0, 2 * math.pi, n)
        temp = r.random(n)
        _STARS[seed] = (xs, ys, size, phase, temp)
    return _STARS[seed]


def draw_sky(ctx, hy, stops, x0=-800, x1=W + 800, y0=-800):
    g = cairo.LinearGradient(0, y0 if y0 > -799 else hy - 1100, 0, hy)
    for off, col in stops:
        g.add_color_stop_rgb(off, *col)
    ctx.set_source(g)
    ctx.rectangle(x0, y0, x1 - x0, hy - y0 + 2)
    ctx.fill()


def draw_glow(ctx, x, y, r, col, a):
    g = cairo.RadialGradient(x, y, 0, x, y, r)
    g.add_color_stop_rgba(0, col[0], col[1], col[2], a)
    g.add_color_stop_rgba(0.35, col[0], col[1], col[2], a * 0.35)
    g.add_color_stop_rgba(1, col[0], col[1], col[2], 0)
    ctx.set_source(g)
    ctx.arc(x, y, r, 0, 2 * math.pi)
    ctx.fill()


def draw_stars(ctx, t, alpha, hy, seed=7, n=900, milky=0.0, reflect=0.0):
    if alpha <= 0:
        return
    xs, ys, size, phase, temp = stars_data(seed, n)
    if milky > 0:
        # soft milky-way glow band
        ctx.save()
        ctx.translate(W / 2, W / 2 * 0.28 + 80)
        ctx.rotate(math.atan(0.28))
        ctx.scale(1.0, 0.16)
        g = cairo.RadialGradient(0, 0, 0, 0, 0, 1300)
        g.add_color_stop_rgba(0, 0.55, 0.6, 0.75, 0.16 * milky * alpha)
        g.add_color_stop_rgba(0.5, 0.4, 0.45, 0.6, 0.07 * milky * alpha)
        g.add_color_stop_rgba(1, 0.3, 0.35, 0.5, 0)
        ctx.set_source(g)
        ctx.arc(0, 0, 1300, 0, 2 * math.pi)
        ctx.fill()
        ctx.restore()
    for i in range(len(xs)):
        y = ys[i]
        if y > hy - 4:
            continue
        tw = 0.65 + 0.35 * math.sin(t * (1.3 + temp[i] * 2) + phase[i])
        fade = clamp((hy - y) / 120)
        a = alpha * tw * fade * (0.35 + 0.65 * min(1, size[i] / 1.5))
        col = lerpc((0.75, 0.83, 1.0), (1.0, 0.92, 0.8), temp[i])
        rgba(ctx, col, a)
        ctx.arc(xs[i], y, size[i] * 0.75, 0, 2 * math.pi)
        ctx.fill()
        if size[i] > 2.0:
            draw_glow(ctx, xs[i], y, size[i] * 5, col, a * 0.35)
        if reflect > 0:
            ry = hy + (hy - y) * 0.55
            if ry < H + 200:
                wob = math.sin(t * 2 + i) * 1.5
                rgba(ctx, col, a * reflect * (0.6 - 0.4 * clamp((ry - hy) / 400)))
                ctx.rectangle(xs[i] - size[i] * 1.4 + wob, ry, size[i] * 2.8, 0.9)
                ctx.fill()


def draw_moon(ctx, x, y, r, a=1.0):
    draw_glow(ctx, x, y, r * 9, (0.65, 0.72, 0.9), 0.22 * a)
    g = cairo.RadialGradient(x - r * 0.3, y - r * 0.3, r * 0.1, x, y, r)
    g.add_color_stop_rgba(0, 1, 0.99, 0.95, a)
    g.add_color_stop_rgba(1, 0.82, 0.84, 0.88, a)
    ctx.set_source(g)
    ctx.arc(x, y, r, 0, 2 * math.pi)
    ctx.fill()
    # maria
    for dx, dy, rr in ((-0.3, -0.2, 0.25), (0.25, 0.1, 0.2), (-0.05, 0.35, 0.15)):
        rgba(ctx, (0.6, 0.63, 0.7), 0.25 * a)
        ctx.arc(x + dx * r, y + dy * r, rr * r, 0, 2 * math.pi)
        ctx.fill()


# ----------------------------------------------------------------- sea
def draw_sea(ctx, t, hy, top, bot, line_col=(0.6, 0.7, 0.85), line_alpha=0.18, x0=-800,
             x1=W + 800, ybot=H + 500, rows=64, speed=1.0, amp=1.0, glints=()):
    g = cairo.LinearGradient(0, hy, 0, ybot)
    g.add_color_stop_rgb(0, *top)
    g.add_color_stop_rgb(1, *bot)
    ctx.set_source(g)
    ctx.rectangle(x0, hy, x1 - x0, ybot - hy)
    ctx.fill()
    ctx.save()
    ctx.set_line_cap(cairo.LINE_CAP_ROUND)
    for i in range(rows):
        d = (i + 0.5) / rows
        y = hy + (ybot - hy) * d ** 2.3
        step = 26 + 150 * d
        seg = step * (0.35 + 0.25 * hash01(i, 3))
        off = (t * speed * (8 + 50 * d) + hash01(i) * 500) % step
        a = line_alpha * (0.35 + 0.65 * d)
        ctx.set_line_width(0.5 + 2.4 * d)
        rgba(ctx, line_col, a)
        x = x0 - step + off
        k = 0
        while x < x1:
            hk = hash01(i, k)
            if hk > 0.25:
                wy = y + math.sin(k * 1.7 + t * 1.2 * speed + i) * amp * (0.5 + 4 * d)
                ctx.move_to(x, wy)
                ctx.curve_to(x + seg * 0.33, wy - amp * (0.4 + 2 * d), x + seg * 0.66,
                             wy - amp * (0.4 + 2 * d), x + seg * hk, wy)
            x += step
            k += 1
        ctx.stroke()
    for gx, gcol, spread, inten in glints:
        for i in range(rows):
            d = (i + 0.5) / rows
            y = hy + (ybot - hy) * d ** 2.3
            n = 5
            for k in range(n):
                hk = hash01(i, k, 9)
                fl = 0.5 + 0.5 * math.sin(t * (2 + 3 * hk) + hk * 40)
                w = spread * (0.15 + 1.1 * d) * (0.3 + hk)
                x = gx + (hk - 0.5) * spread * (0.4 + 2.2 * d) + math.sin(t + i) * 4
                a = inten * fl * (1 - abs(hk - 0.5) * 1.4) * (0.4 + 0.6 * (1 - d))
                if a <= 0.01:
                    continue
                rgba(ctx, gcol, a)
                ctx.set_line_width(0.8 + 3.0 * d)
                ctx.move_to(x - w / 2, y)
                ctx.line_to(x + w / 2, y)
                ctx.stroke()
    ctx.restore()


def draw_haze(ctx, hy, col, a, h=90, x0=-800, x1=W + 800):
    g = cairo.LinearGradient(0, hy - h, 0, hy + h * 0.6)
    g.add_color_stop_rgba(0, col[0], col[1], col[2], 0)
    g.add_color_stop_rgba(0.62, col[0], col[1], col[2], a)
    g.add_color_stop_rgba(1, col[0], col[1], col[2], 0)
    ctx.set_source(g)
    ctx.rectangle(x0, hy - h, x1 - x0, h * 1.6)
    ctx.fill()


# ----------------------------------------------------------------- the ship
FUNNELS = [690, 580, 470, 360]          # forward -> aft (the aft one was a dummy)
BULKHEADS = [962, 905, 848, 790, 735, 676, 617, 560, 503, 446, 390, 332, 274, 214, 140]


def _bez(p0, p1, p2, p3, n=200):
    s = np.linspace(0, 1, n)[:, None]
    pts = ((1 - s) ** 3) * p0 + 3 * ((1 - s) ** 2) * s * p1 + 3 * (1 - s) * s * s * p2 + s ** 3 * p3
    return pts[:, 0], pts[:, 1]


_SX, _SY = _bez(np.array([14, -74.0]), np.array([300, -68.0]), np.array([720, -68.0]),
                np.array([1000, -82.0]))


def deck_y(x):
    return float(np.interp(x, _SX, _SY))


def hull_path(ctx):
    ctx.move_to(14, -74)
    ctx.curve_to(300, -68, 720, -68, 1000, -82)
    ctx.line_to(993, 10)
    ctx.curve_to(990, 36, 978, 42, 955, 42)
    ctx.line_to(95, 42)
    ctx.curve_to(70, 42, 55, 30, 50, 10)
    ctx.curve_to(46, -8, 30, -22, 6, -30)
    ctx.curve_to(0, -46, 4, -66, 14, -74)
    ctx.close_path()


_WINDOWS = None


def _windows():
    global _WINDOWS
    if _WINDOWS is None:
        out = []
        rows = [(-61, 40, 975, 8.0, 0.55), (-47, 60, 970, 7.0, 0.5), (-33, 80, 960, 7.5, 0.45),
                (-19, 110, 940, 9.0, 0.25), (-80, 180, 845, 6.0, 0.7), (-92, 190, 840, 5.0, 0.75),
                (-110, 790, 845, 6.0, 0.8)]
        for ri, (y, xa, xb, step, p) in enumerate(rows):
            x = xa
            k = 0
            while x < xb:
                if hash01(ri, k, 5) < p + 0.25:
                    out.append((x, y, 3.2 if y < -70 else 2.6, 2.6 if y < -70 else 2.4,
                                hash01(ri, k, 11)))
                x += step
                k += 1
        _WINDOWS = out
    return _WINDOWS


def ship_palette(night):
    return {
        "hull": lerpc((0.07, 0.075, 0.085), (0.025, 0.03, 0.04), night),
        "hull_hi": lerpc((0.22, 0.23, 0.25), (0.08, 0.09, 0.12), night),
        "red": lerpc((0.48, 0.12, 0.09), (0.18, 0.05, 0.05), night),
        "white": lerpc((0.93, 0.91, 0.86), (0.26, 0.29, 0.36), night),
        "white_sh": lerpc((0.72, 0.71, 0.68), (0.17, 0.19, 0.25), night),
        "funnel": lerpc((0.84, 0.6, 0.25), (0.30, 0.22, 0.13), night),
        "funnel_sh": lerpc((0.62, 0.42, 0.15), (0.17, 0.12, 0.07), night),
        "gold": lerpc((0.8, 0.65, 0.3), (0.42, 0.34, 0.18), night),
        "line": lerpc((0.15, 0.15, 0.16), (0.07, 0.08, 0.1), night),
        "deck": lerpc((0.62, 0.58, 0.5), (0.18, 0.18, 0.2), night),
    }


def draw_ship(ctx, t, night=0.0, lights=1.0, smoke=0.0, speed=40.0, funnels=True, masts=True,
              super_=True, hull_alpha=1.0, wreck=0.0, flicker=0.0, lit_col=(1.0, 0.82, 0.5),
              smoke_col=None):
    """Draw Titanic in local coords: x 0 (stern) .. 1000 (bow), waterline y=0, up is -y."""
    P = ship_palette(night)
    if wreck > 0:
        rust = (0.32, 0.17, 0.09)
        for k in ("hull", "hull_hi", "white", "white_sh", "red", "deck"):
            P[k] = lerpc(P[k], rust if k != "hull_hi" else (0.45, 0.25, 0.12), wreck * 0.85)
    ctx.save()
    ctx.set_line_join(cairo.LINE_JOIN_ROUND)

    # masts & rigging go behind everything
    if masts:
        ctx.set_line_cap(cairo.LINE_CAP_ROUND)
        rgba(ctx, P["line"], hull_alpha)
        ctx.set_line_width(4)
        ctx.move_to(905, deck_y(905))
        ctx.line_to(888, -345)
        ctx.move_to(178, -74)
        ctx.line_to(163, -330)
        ctx.stroke()
        ctx.set_line_width(1.0)
        rgba(ctx, P["line"], 0.85 * hull_alpha)
        ctx.move_to(888, -345)
        ctx.line_to(1000, -82)
        ctx.move_to(888, -345)
        ctx.line_to(845, -112)
        ctx.move_to(163, -330)
        ctx.line_to(14, -74)
        ctx.move_to(163, -330)
        ctx.line_to(210, -104)
        ctx.stroke()
        ctx.set_line_width(0.8)
        for dy in (0, 6):
            ctx.move_to(888, -338 + dy)
            ctx.line_to(163, -323 + dy)
        ctx.stroke()
        # crow's nest
        rgba(ctx, P["line"], hull_alpha)
        ctx.rectangle(891, -262, 14, 10)
        ctx.fill()

    # smoke behind funnels
    if smoke > 0 and funnels:
        draw_smoke(ctx, t, smoke, speed, night, smoke_col)

    # funnels
    if funnels:
        for i, fx in enumerate(FUNNELS):
            x0, x1 = fx - 21, fx + 21
            g = cairo.LinearGradient(x0 - 22, 0, x1, 0)
            g.add_color_stop_rgba(0, *P["funnel_sh"], hull_alpha)
            g.add_color_stop_rgba(0.45, *P["funnel"], hull_alpha)
            g.add_color_stop_rgba(1, *P["funnel_sh"], hull_alpha)
            ctx.set_source(g)
            ctx.move_to(x0, -104)
            ctx.line_to(x1, -104)
            ctx.line_to(x1 - 22, -252)
            ctx.line_to(x0 - 22, -252)
            ctx.close_path()
            ctx.fill()
            rgba(ctx, (0.02, 0.02, 0.025), hull_alpha)
            ctx.move_to(x0 - 16.3, -214)
            ctx.line_to(x1 - 16.3, -214)
            ctx.line_to(x1 - 22, -252)
            ctx.line_to(x0 - 22, -252)
            ctx.close_path()
            ctx.fill()
            # steam pipe
            rgba(ctx, P["funnel_sh"], hull_alpha)
            ctx.set_line_width(2.5)
            ctx.move_to(x1 + 4, -104)
            ctx.line_to(x1 - 15, -244)
            ctx.stroke()
            # guy wires
            ctx.set_line_width(0.6)
            rgba(ctx, P["line"], 0.6 * hull_alpha)
            ctx.move_to(fx - 18, -235)
            ctx.line_to(fx - 55, -106)
            ctx.move_to(fx - 18, -235)
            ctx.line_to(fx + 30, -106)
            ctx.stroke()

    # superstructure
    if super_:
        g = cairo.LinearGradient(0, -118, 0, -70)
        g.add_color_stop_rgba(0, *P["white"], hull_alpha)
        g.add_color_stop_rgba(1, *P["white_sh"], hull_alpha)
        ctx.set_source(g)
        ctx.move_to(175, deck_y(175) + 1)
        ctx.line_to(175, -104)
        ctx.line_to(850, -104)
        ctx.curve_to(858, -104, 862, -98, 862, -90)
        ctx.line_to(862, deck_y(862) + 1)
        ctx.close_path()
        ctx.fill()
        # bridge/officers' house
        ctx.rectangle(770, -116, 82, 12)
        ctx.fill()
        # deck houses between funnels
        for xa, xb in ((300, 380), (420, 500), (530, 610), (640, 720)):
            ctx.rectangle(xa, -112, xb - xa, 8)
            ctx.fill()
        # deck lines
        ctx.set_line_width(0.8)
        rgba(ctx, P["white_sh"], 0.9 * hull_alpha)
        for y in (-86, -98):
            ctx.move_to(180, y)
            ctx.line_to(858, y)
        ctx.stroke()
        # lifeboats
        for xa, xb in ((190, 330), (705, 845)):
            x = xa
            while x < xb:
                rgba(ctx, P["white"], hull_alpha)
                ctx.move_to(x, -113)
                ctx.line_to(x + 17, -113)
                ctx.curve_to(x + 17, -108, x + 14, -106, x + 8.5, -106)
                ctx.curve_to(x + 3, -106, x, -108, x, -113)
                ctx.fill()
                rgba(ctx, P["line"], 0.7 * hull_alpha)
                ctx.set_line_width(0.7)
                ctx.move_to(x - 1, -104)
                ctx.curve_to(x - 1, -116, x + 2, -117, x + 4, -116)
                ctx.move_to(x + 18, -104)
                ctx.curve_to(x + 18, -116, x + 15, -117, x + 13, -116)
                ctx.stroke()
                x += 23

    # hull
    hull_path(ctx)
    path = ctx.copy_path()
    g = cairo.LinearGradient(0, -82, 0, 42)
    g.add_color_stop_rgba(0, *P["hull_hi"], hull_alpha)
    g.add_color_stop_rgba(0.25, *P["hull"], hull_alpha)
    g.add_color_stop_rgba(0.62, *P["hull"], hull_alpha)
    g.add_color_stop_rgba(0.64, *P["red"], hull_alpha)
    g.add_color_stop_rgba(1, *lerpc(P["red"], (0, 0, 0), 0.4), hull_alpha)
    ctx.set_source(g)
    ctx.fill()
    # plate seams
    ctx.save()
    ctx.append_path(path)
    ctx.clip()
    ctx.set_line_width(0.5)
    rgba(ctx, P["hull_hi"], 0.35 * hull_alpha)
    for y in (-52, -38, -24, -10, 12, 26):
        ctx.move_to(0, y)
        ctx.line_to(1000, y)
    for x in range(30, 1000, 28):
        ctx.move_to(x, -80)
        ctx.line_to(x, 42)
    ctx.stroke()
    ctx.restore()
    # gold sheer line
    rgba(ctx, P["gold"], 0.9 * hull_alpha)
    ctx.set_line_width(1.3)
    ctx.move_to(20, deck_y(20) + 5)
    for x in range(40, 1000, 20):
        ctx.line_to(x, deck_y(x) + 5)
    ctx.line_to(996, deck_y(996) + 5)
    ctx.stroke()
    # anchor
    rgba(ctx, P["hull_hi"], hull_alpha)
    ctx.rectangle(962, -58, 9, 10)
    ctx.fill()
    # rudder
    rgba(ctx, P["red"], hull_alpha)
    ctx.move_to(38, -4)
    ctx.line_to(48, -4)
    ctx.line_to(48, 40)
    ctx.line_to(30, 40)
    ctx.close_path()
    ctx.fill()

    # lit windows
    if lights > 0:
        on = []
        for (x, y, w, h, r) in _windows():
            if not super_ and y < -70:
                continue
            if r < lights:
                on.append((x, y, w, h, r))
        if on:
            fl = 1.0
            if flicker > 0:
                fl = 1 - flicker * (0.5 + 0.5 * math.sin(t * 37) * math.sin(t * 13.7))
            rgba(ctx, lit_col, 0.95 * fl * hull_alpha)
            for (x, y, w, h, r) in on:
                ctx.rectangle(x, y, w, h)
            ctx.fill()
            rgba(ctx, lit_col, 0.10 * fl * hull_alpha)
            for (x, y, w, h, r) in on:
                ctx.rectangle(x - 3, y - 3, w + 6, h + 6)
            ctx.fill()
            # deck lights
            rgba(ctx, (1, 0.9, 0.7), 0.9 * fl)
            for x in (888,):
                ctx.arc(x + 2, -240, 2.2, 0, 2 * math.pi)
                ctx.fill()
    ctx.restore()
    return path


def draw_smoke(ctx, t, amount, speed, night, col=None):
    col = col or lerpc((0.35, 0.33, 0.32), (0.12, 0.13, 0.16), night)
    dt = 0.11
    life = 7.0
    for fi, fx in enumerate(FUNNELS[:3]):
        k1 = int(t / dt)
        k0 = int((t - life) / dt)
        for k in range(k0, k1 + 1):
            age = t - k * dt
            if age < 0 or age > life:
                continue
            h1, h2, h3 = hash01(fi, k), hash01(k, fi, 2), hash01(k, 7, fi)
            u = age / life
            x = fx - 22 - age * (speed * 0.9 + 12) + math.sin(k * 0.7 + age) * 6 * u + (h1 - .5) * 8
            y = -252 - age * 26 * (1 - 0.35 * u) - (h2 - 0.5) * 18 * u
            r = 9 + age * 13 * (0.8 + 0.4 * h3)
            a = amount * 0.38 * (1 - u) ** 1.6 * min(1, age * 4)
            g = cairo.RadialGradient(x, y, 0, x, y, r)
            g.add_color_stop_rgba(0, col[0], col[1], col[2], a)
            g.add_color_stop_rgba(1, col[0], col[1], col[2], 0)
            ctx.set_source(g)
            ctx.arc(x, y, r, 0, 2 * math.pi)
            ctx.fill()


def ship_world_matrix(x, y, s, angle_deg=0.0, pivot=(0.0, 0.0)):
    m = cairo.Matrix()
    m.translate(x, y)
    m.rotate(math.radians(angle_deg))
    m.scale(s, s)
    m.translate(-pivot[0], -pivot[1])
    return m


def light_reflections(ctx, t, pts, water_y, col=(1.0, 0.8, 0.5), a=0.25, length=90):
    """Shimmering vertical reflection streaks under lit points (world coords)."""
    ctx.save()
    for i, x in enumerate(pts):
        for k in range(9):
            yy = water_y + 4 + k * length / 9
            fl = 0.5 + 0.5 * math.sin(t * 3 + i * 1.3 + k * 2.1)
            w = 4 + 10 * hash01(i, k) + 3 * k
            rgba(ctx, col, a * fl * (1 - k / 9))
            ctx.rectangle(x - w / 2 + math.sin(t * 2 + k + i) * 3, yy, w, 1.6 + k * 0.25)
            ctx.fill()
    ctx.restore()


# ----------------------------------------------------------------- particles
def sparks(ctx, t, x, y, seed, rate=40, life=0.9, power=1.0, col=(1.0, 0.75, 0.3), grav=380):
    k1 = int(t * rate)
    k0 = int((t - life) * rate)
    ctx.save()
    ctx.set_line_cap(cairo.LINE_CAP_ROUND)
    for k in range(k0, k1 + 1):
        age = t - k / rate
        if age < 0 or age > life:
            continue
        a1, a2, a3 = hash01(seed, k), hash01(k, seed, 3), hash01(seed, k, 8)
        ang = -math.pi / 2 + (a1 - 0.5) * 2.6
        v = (120 + 260 * a2) * power
        vx, vy = math.cos(ang) * v, math.sin(ang) * v
        px, py = x + vx * age, y + vy * age + 0.5 * grav * age * age
        tx, ty = px - vx * 0.03, py - (vy + grav * age) * 0.03
        u = age / life
        ctx.set_line_width(1.2 + 1.4 * a3)
        rgba(ctx, lerpc((1, 0.95, 0.75), col, u), (1 - u) ** 1.5)
        ctx.move_to(tx, ty)
        ctx.line_to(px, py)
        ctx.stroke()
    ctx.restore()
    fl = 0.6 + 0.4 * math.sin(t * 47 + seed) * math.sin(t * 23)
    draw_glow(ctx, x, y, 60 * power, (1.0, 0.75, 0.4), 0.35 * fl)


def dust(ctx, t, n, seed, col, a, x0=0, x1=W, y0=0, y1=H, drift=(6, -4), size=2.0):
    """Slow floating particles (dust in light / marine snow / bokeh)."""
    for i in range(n):
        h1, h2, h3 = hash01(seed, i), hash01(i, seed, 4), hash01(seed, i, 9)
        x = x0 + ((h1 * (x1 - x0) + drift[0] * t * (0.5 + h3)) % (x1 - x0))
        y = y0 + ((h2 * (y1 - y0) + drift[1] * t * (0.5 + h3)) % (y1 - y0))
        tw = 0.5 + 0.5 * math.sin(t * (0.6 + h3) + h1 * 30)
        r = size * (0.4 + h3 * 1.4)
        rgba(ctx, col, a * tw * (0.3 + 0.7 * h3))
        ctx.arc(x, y, r, 0, 2 * math.pi)
        ctx.fill()


def light_rays(ctx, t, x, y, col, a, n=7, spread=0.9, length=1400, base_angle=math.pi / 2):
    ctx.save()
    for i in range(n):
        h = hash01(i, 77)
        ang = base_angle + (i / max(1, n - 1) - 0.5) * spread + math.sin(t * 0.3 + i) * 0.03
        w = 0.025 + 0.04 * h
        al = a * (0.5 + 0.5 * math.sin(t * 0.5 + h * 9))
        g = cairo.LinearGradient(x, y, x + math.cos(ang) * length, y + math.sin(ang) * length)
        g.add_color_stop_rgba(0, col[0], col[1], col[2], al)
        g.add_color_stop_rgba(1, col[0], col[1], col[2], 0)
        ctx.set_source(g)
        ctx.move_to(x, y)
        ctx.line_to(x + math.cos(ang - w) * length, y + math.sin(ang - w) * length)
        ctx.line_to(x + math.cos(ang + w) * length, y + math.sin(ang + w) * length)
        ctx.close_path()
        ctx.fill()
    ctx.restore()


def bird(ctx, x, y, s, phase, col=(0.05, 0.05, 0.07), a=1.0):
    f = math.sin(phase)
    rgba(ctx, col, a)
    ctx.set_line_width(1.6 * s)
    ctx.set_line_cap(cairo.LINE_CAP_ROUND)
    ctx.move_to(x - 12 * s, y - 4 * s * f)
    ctx.curve_to(x - 7 * s, y - 6 * s * f - 2 * s, x - 3 * s, y - 2 * s, x, y)
    ctx.curve_to(x + 3 * s, y - 2 * s, x + 7 * s, y - 6 * s * f - 2 * s, x + 12 * s, y - 4 * s * f)
    ctx.stroke()
