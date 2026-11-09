"""The eight scenes of the film. Each scene_* function gets (ctx, local_time, info) where
info["cues"] holds the (start, end) of every narrated sentence relative to the scene."""
import math
from contextlib import contextmanager

import cairo

from common import (BAR, BULKHEADS, FUNNELS, H, SERIF_BODY, W, bird, camera, chapter_card, clamp,
                    deck_y, draw_glow, draw_haze, draw_moon, draw_sea, draw_ship, draw_sky,
                    draw_stars, dust, ease_in, ease_io, ease_out, hash01, hexc, hull_path,
                    keyframes, label, lerp, lerpc, light_reflections, light_rays, ramp, rgba,
                    sparks, text, text_width, window, ship_world_matrix)

GOLD = hexc("#d9b46a")
CREAM = (0.95, 0.92, 0.85)
CYAN = (0.55, 0.85, 1.0)
RED = (1.0, 0.25, 0.18)
WATER = (0.12, 0.42, 0.75)

# compartments as (aft x, fwd x), bow first
COMPS = [(BULKHEADS[0], 1000)] + [(BULKHEADS[i + 1], BULKHEADS[i]) for i in range(14)] + \
        [(0, BULKHEADS[14])]
BH_TOP = [-64, -50, -50, -42, -42, -36, -36, -36, -36, -36, -36, -42, -42, -42, -50]


@contextmanager
def local(ctx, m):
    ctx.save()
    ctx.transform(m)
    try:
        yield
    finally:
        ctx.restore()


@contextmanager
def group(ctx, alpha):
    ctx.push_group()
    try:
        yield
    finally:
        ctx.pop_group_to_source()
        ctx.paint_with_alpha(clamp(alpha))


def to_screen(ctx, m, x, y):
    wx, wy = m.transform_point(x, y)
    return ctx.user_to_device(wx, wy)


def counter(v, frac, fmt="{:,}"):
    return fmt.format(int(round(v * ease_out(frac))))


def stat(ctx, x, y, big, small, a, align="left", size=64):
    if a <= 0:
        return
    text(ctx, big, x, y, size, color=CREAM, alpha=a, align=align, spacing=4, glow=0.4,
         glow_color=GOLD)
    text(ctx, small, x, y + 40, 20, color=GOLD, alpha=a * 0.9, align=align, spacing=6)


def night_sea_bg(ctx, t, hy, milky=1.0, star_a=1.0, reflect=0.6, speed=0.5, top=None, bot=None):
    draw_sky(ctx, hy, [(0, (0.0, 0.005, 0.02)), (0.6, (0.02, 0.035, 0.08)),
                       (1, (0.07, 0.1, 0.17))])
    draw_stars(ctx, t, star_a, hy, milky=milky, reflect=reflect)
    draw_sea(ctx, t, hy, top or (0.03, 0.05, 0.09), bot or (0.0, 0.005, 0.015),
             line_col=(0.45, 0.55, 0.7), line_alpha=0.10, speed=speed, amp=0.5)
    draw_haze(ctx, hy, (0.12, 0.16, 0.24), 0.35, h=70)


def ship_above_water(ctx, t, m, water_y, under_alpha=0.0, **kw):
    """Draw the ship clipped to above the waterline (world y), with an optional ghost below."""
    ctx.save()
    ctx.rectangle(-3000, -3000, 9000, water_y + 3000)
    ctx.clip()
    with local(ctx, m):
        draw_ship(ctx, t, **kw)
    ctx.restore()
    if under_alpha > 0:
        ctx.save()
        ctx.rectangle(-3000, water_y, 9000, 5000)
        ctx.clip()
        with group(ctx, under_alpha):
            with local(ctx, m):
                kw2 = dict(kw)
                kw2["lights"] = kw.get("lights", 0) * 0.4
                kw2["smoke"] = 0
                draw_ship(ctx, t, **kw2)
        ctx.restore()


def ship_reflection(ctx, t, x, y, s, a, **kw):
    ctx.save()
    ctx.rectangle(-3000, y, 9000, 3000)
    ctx.clip()
    with group(ctx, a):
        ctx.translate(x, y)
        ctx.scale(s, -s * 0.85)
        draw_ship(ctx, t, **kw)
    ctx.restore()


def wake(ctx, t, stern_x, y, s, a=0.5, length=600):
    ctx.save()
    ctx.set_line_cap(cairo.LINE_CAP_ROUND)
    for i in range(14):
        h = hash01(i, 41)
        u = ((t * 0.25 + h) % 1.0)
        x = stern_x - u * length
        w = (30 + 120 * u) * s
        rgba(ctx, (0.85, 0.9, 0.95), a * (1 - u) * 0.5)
        ctx.set_line_width(1.5 + 2 * (1 - u))
        ctx.move_to(x - w, y + 3 + u * 6)
        ctx.line_to(x + w * 0.3, y + 3 + u * 6)
        ctx.stroke()
    # bright churn at the stern
    draw_glow(ctx, stern_x - 10 * s, y + 4, 60 * s, (0.8, 0.85, 0.9), a * 0.35)
    ctx.restore()


# ===================================================================== 1. INTRO
def scene_intro(ctx, t, S):
    dur, c = S["dur"], S["cues"]
    k = t / dur
    hy, wl, s = 690, 760, 1.05
    ctx.save()
    camera(ctx, lerp(930, 1030, ease_io(k)), lerp(560, 600, ease_io(k)), lerp(1.0, 1.14, ease_io(k)))
    draw_sky(ctx, hy, [(0, (0.01, 0.015, 0.05)), (0.5, (0.04, 0.07, 0.16)),
                       (0.82, (0.28, 0.2, 0.3)), (0.95, (0.78, 0.42, 0.26)),
                       (1, (0.98, 0.62, 0.32))])
    draw_stars(ctx, t, 0.55, hy - 260, milky=0.3)
    draw_glow(ctx, 1560, hy + 20, 700, (1.0, 0.55, 0.25), 0.45)
    draw_glow(ctx, 1560, hy + 10, 220, (1.0, 0.8, 0.5), 0.5)
    # distant clouds
    for i in range(6):
        cx = (i * 410 + t * 6) % 2600 - 400
        cy = 520 + 60 * hash01(i, 2)
        ctx.save()
        ctx.translate(cx, cy)
        ctx.scale(1, 0.12)
        g = cairo.RadialGradient(0, 0, 0, 0, 0, 380)
        g.add_color_stop_rgba(0, 0.12, 0.08, 0.14, 0.55)
        g.add_color_stop_rgba(1, 0.12, 0.08, 0.14, 0)
        ctx.set_source(g)
        ctx.arc(0, 0, 380, 0, 2 * math.pi)
        ctx.fill()
        ctx.restore()
    draw_sea(ctx, t, hy, (0.2, 0.13, 0.15), (0.01, 0.015, 0.04), line_col=(0.9, 0.6, 0.45),
             line_alpha=0.14, glints=[(1560, (1.0, 0.7, 0.4), 260, 0.55)])
    draw_haze(ctx, hy, (0.85, 0.5, 0.35), 0.35)
    x = 260 + 20 * t
    kw = dict(night=0.55, lights=1.0, smoke=1.3, speed=20 / s, lit_col=(1.0, 0.8, 0.5),
              smoke_col=(0.42, 0.33, 0.36))
    ship_reflection(ctx, t, x, wl, s, 0.16, **kw)
    light_reflections(ctx, t, [x + s * xx for xx in range(90, 960, 45)], wl, a=0.22)
    wake(ctx, t, x + 40 * s, wl, s)
    ship_above_water(ctx, t, ship_world_matrix(x, wl, s), wl, **kw)
    # bow wave
    rgba(ctx, (0.9, 0.92, 0.95), 0.5)
    ctx.set_line_width(2)
    for j in range(3):
        bx = x + 1000 * s + 4 + j * 9
        ctx.move_to(bx - 30, wl + 2 + j * 3)
        ctx.curve_to(bx - 10, wl - 2, bx, wl + 1, bx + 14, wl + 4 + j * 2)
    ctx.stroke()
    for i in range(5):
        ph = t * 7 + i * 1.7
        bird(ctx, 400 + i * 70 + t * 30 + 20 * math.sin(i), 420 + 30 * math.sin(i * 2.1 + t * 0.4),
             1.1, ph, a=0.85)
    ctx.restore()

    a = window(t, 0.8, c[1][1] - 0.6, 1.2, 1.2)
    text(ctx, "SOUTHAMPTON  ·  10 APRIL 1912", W / 2, 230, 26, color=GOLD, alpha=a, spacing=8)
    ta = ramp(t, c[2][0] - 0.2, c[2][0] + 1.4)
    if ta > 0:
        sp = lerp(70, 34, ease_out((t - c[2][0] + 0.2) / 3.5))
        text(ctx, "TITANIC", W / 2, 320, 150, color=CREAM, alpha=ta, spacing=sp, glow=0.8,
             glow_color=(1.0, 0.75, 0.45))
    sa = ramp(t, c[3][0], c[3][0] + 1.2)
    if sa > 0:
        text(ctx, "THE SHIP THEY CALLED UNSINKABLE", W / 2, 380, 28, color=GOLD, alpha=sa,
             spacing=lerp(18, 11, ease_out((t - c[3][0]) / 3)))


# ===================================================================== 2. BELFAST
def draw_gantry(ctx, x0, x1, ground, top, col, a, front=False):
    ctx.save()
    rgba(ctx, col, a)
    step = 95
    ctx.set_line_width(5 if front else 4)
    xs = list(range(int(x0), int(x1) + 1, step))
    for x in xs:
        ctx.move_to(x, ground)
        ctx.line_to(x, top)
    ctx.stroke()
    ctx.set_line_width(1.4 if front else 1.1)
    levels = [top + i * (ground - top) / 6 for i in range(7)]
    for y in levels:
        ctx.move_to(xs[0], y)
        ctx.line_to(xs[-1], y)
    for xa, xb in zip(xs, xs[1:]):
        for ya, yb in zip(levels, levels[1:]):
            ctx.move_to(xa, ya)
            ctx.line_to(xb, yb)
            ctx.move_to(xb, ya)
            ctx.line_to(xa, yb)
    ctx.stroke()
    ctx.set_line_width(10)
    ctx.move_to(xs[0] - 20, top)
    ctx.line_to(xs[-1] + 20, top)
    ctx.stroke()
    ctx.restore()
    return xs


def scene_build(ctx, t, S):
    dur, c = S["dur"], S["cues"]
    k = t / dur
    ground = 800
    ctx.save()
    camera(ctx, lerp(760, 1180, ease_io(k)), lerp(560, 540, ease_io(k)), lerp(1.22, 1.06, ease_io(k)))
    draw_sky(ctx, ground, [(0, (0.04, 0.05, 0.07)), (0.55, (0.15, 0.16, 0.19)),
                           (0.85, (0.36, 0.3, 0.28)), (1, (0.62, 0.45, 0.32))])
    # clouds
    for i in range(9):
        cx = (i * 330 + t * 10) % 3000 - 600
        cy = 240 + 160 * hash01(i, 5)
        ctx.save()
        ctx.translate(cx, cy)
        ctx.scale(1, 0.25)
        g = cairo.RadialGradient(0, 0, 0, 0, 0, 420)
        g.add_color_stop_rgba(0, 0.25, 0.24, 0.26, 0.45)
        g.add_color_stop_rgba(1, 0.25, 0.24, 0.26, 0)
        ctx.set_source(g)
        ctx.arc(0, 0, 420, 0, 2 * math.pi)
        ctx.fill()
        ctx.restore()
    # Cave Hill and the city skyline
    rgba(ctx, (0.17, 0.15, 0.16))
    ctx.move_to(-600, 720)
    ctx.curve_to(0, 610, 300, 650, 600, 690)
    ctx.curve_to(900, 720, 1400, 650, 1800, 700)
    ctx.line_to(2600, 720)
    ctx.line_to(2600, ground)
    ctx.line_to(-600, ground)
    ctx.fill()
    rgba(ctx, (0.1, 0.09, 0.1))
    for i in range(60):
        bx = -600 + i * 55
        bh = 20 + 40 * hash01(i, 8)
        ctx.rectangle(bx, ground - 40 - bh, 50, bh + 40)
        if hash01(i, 9) > 0.7:
            ctx.rectangle(bx + 20, ground - 120 - bh, 8, 80)
    ctx.fill()
    for i in range(60):
        if hash01(i, 9) > 0.7:
            for j in range(10):
                age = (t * 0.4 + j / 10) % 1
                sx = -600 + i * 55 + 24 + age * 80
                sy = ground - 125 - 20 * hash01(i, 8) - age * 160
                rgba(ctx, (0.3, 0.28, 0.28), 0.25 * (1 - age))
                ctx.arc(sx, sy, 8 + age * 30, 0, 2 * math.pi)
                ctx.fill()
    draw_haze(ctx, ground - 40, (0.5, 0.4, 0.35), 0.45, h=120)

    s = 1.35
    m = ship_world_matrix(250, ground - 57 * s - 14, s, -0.8)
    gx0, gx1, gtop = 150, 1770, 300
    draw_gantry(ctx, gx0, gx1, ground, gtop, (0.09, 0.09, 0.1), 0.55)
    # hanging work lamps
    lamps = [(x, 420 + 40 * hash01(i, 3)) for i, x in enumerate(range(260, 1700, 190))]
    for i, (lx, ly) in enumerate(lamps):
        rgba(ctx, (0.1, 0.1, 0.1), 0.8)
        ctx.set_line_width(1)
        ctx.move_to(lx, gtop + 40)
        ctx.line_to(lx, ly)
        ctx.stroke()
        light_rays(ctx, t, lx, ly, (1.0, 0.8, 0.5), 0.05, n=5, spread=0.7, length=420)
        draw_glow(ctx, lx, ly, 40, (1.0, 0.85, 0.55), 0.8)

    # slipway, keel blocks
    rgba(ctx, (0.12, 0.1, 0.09))
    ctx.rectangle(-600, ground - 8, 3200, 400)
    ctx.fill()
    for bx in range(300, 1600, 40):
        rgba(ctx, (0.2, 0.15, 0.1))
        ctx.rectangle(bx, ground - 22, 22, 16)
        ctx.fill()

    # hull under construction
    prog = lerp(0.22, 1.02, ease_io(t / (dur - 1)))
    fronts = []
    with local(ctx, m):
        hull_path(ctx)
        hp = ctx.copy_path()
        ctx.save()
        ctx.clip()
        rgba(ctx, (0.08, 0.06, 0.05))
        ctx.paint()
        rgba(ctx, (0.42, 0.24, 0.14), 0.95)
        ctx.set_line_width(1.6)
        for x in range(10, 1000, 10):
            ctx.move_to(x, -90)
            ctx.line_to(x, 45)
        ctx.stroke()
        ctx.set_line_width(1.0)
        for y in (-60, -35, -10, 15):
            ctx.move_to(0, y)
            ctx.line_to(1000, y)
        ctx.stroke()
        rows = 9
        for r in range(rows):
            y0 = -86 + r * 14.5
            fx = prog * 1100 - 90 * hash01(r, 4) - 40
            fronts.append((fx, y0 + 7))
            g = cairo.LinearGradient(0, y0, 0, y0 + 14.5)
            g.add_color_stop_rgb(0, 0.36, 0.33, 0.31)
            g.add_color_stop_rgb(1, 0.2, 0.18, 0.17)
            ctx.set_source(g)
            # stagger plates
            x = 0
            pi = 0
            while x < fx:
                pw = 38 + 6 * hash01(r, pi)
                ctx.rectangle(x + (7 if r % 2 else 0), y0, min(pw, fx - x) - 0.6, 14)
                x += pw
                pi += 1
            ctx.fill()
            rgba(ctx, (0.55, 0.52, 0.48), 0.45)
            x = 3
            while x < fx - 2:
                ctx.rectangle(x, y0 + 1.2, 0.9, 0.9)
                ctx.rectangle(x, y0 + 12.4, 0.9, 0.9)
                x += 3.2
            ctx.fill()
        ctx.restore()
        ctx.append_path(hp)
        rgba(ctx, (0.3, 0.28, 0.27), 0.9)
        ctx.set_line_width(1.2)
        ctx.stroke()
    # riveting sparks at the plating front
    for i, (fx, fy) in enumerate(fronts):
        if 30 < fx < 990 and i % 2 == 0:
            wx, wy = m.transform_point(fx, fy)
            if hash01(i, int(t * 1.5)) > 0.25:
                sparks(ctx, t + i * 3.1, wx, wy, i, rate=34, power=0.8)
    rgba(ctx, (0.02, 0.02, 0.025))
    for x in range(gx0 + 40, gx1 + 41, 190):
        ctx.rectangle(x - 5, gtop + 20, 10, ground + 40 - gtop)
    ctx.rectangle(gx0 + 20, gtop + 14, gx1 - gx0 + 40, 14)
    ctx.fill()
    dust(ctx, t, 90, 4, (1.0, 0.85, 0.6), 0.3, x0=-200, x1=2200, y0=250, y1=820, size=1.6)
    # dimension line (world)
    da = window(t, c[2][0], dur - 0.4, 0.8, 0.8)
    if da > 0:
        L = ease_out((t - c[2][0]) / 2.2)
        y = ground + 40
        xa, _ = m.transform_point(6, 0)
        xb, _ = m.transform_point(1000, 0)
        mid = (xa + xb) / 2
        half = (xb - xa) / 2 * L
        rgba(ctx, GOLD, da)
        ctx.set_line_width(2)
        ctx.move_to(mid - half, y)
        ctx.line_to(mid + half, y)
        for sx in (mid - half, mid + half):
            ctx.move_to(sx, y - 14)
            ctx.line_to(sx, y + 14)
        ctx.stroke()
        rgba(ctx, (0, 0, 0), 0.6 * da)
        ctx.rectangle(mid - 160, y - 22, 320, 44)
        ctx.fill()
        text(ctx, counter(269, L) + " METRES", mid, y + 11, 30, color=CREAM, alpha=da, spacing=5)
    ctx.restore()

    chapter_card(ctx, t, "CHAPTER  I", "BELFAST  ·  1909", end=c[0][1] - 2.5)
    stat(ctx, 240, 280, counter(15000, (t - c[1][0]) / 2), "SHIPYARD WORKERS",
         window(t, c[1][0], c[1][1] + 0.4))
    stat(ctx, W - 240, 280, "1909 – 1912", "MARCH 1909  ·  KEEL LAID", window(t, c[1][0] + 1.2,
                                                                        c[1][1] + 0.4),
         align="right")
    stat(ctx, W - 240, 280, counter(46328, (t - c[2][0] - 2.4) / 2) + " t", "GROSS TONNAGE",
         window(t, c[2][0] + 2.4, c[2][1] + 0.4), align="right")
    stat(ctx, W / 2, 280, counter(3000000, (t - c[3][0]) / 2.6), "RIVETS",
         window(t, c[3][0], dur - 0.3), align="center", size=78)


# ===================================================================== 3. ENGINES
def boiler_rooms():
    return COMPS[4:10]  # boiler rooms 6..1


def draw_blueprint_bg(ctx, t, x0=-400, x1=W + 400, y0=-300, y1=H + 300):
    g = cairo.LinearGradient(0, y0, 0, y1)
    g.add_color_stop_rgb(0, 0.02, 0.045, 0.085)
    g.add_color_stop_rgb(1, 0.0, 0.01, 0.03)
    ctx.set_source(g)
    ctx.rectangle(x0, y0, x1 - x0, y1 - y0)
    ctx.fill()
    ctx.set_line_width(0.6)
    rgba(ctx, CYAN, 0.045)
    for x in range(int(x0) // 40 * 40, int(x1), 40):
        ctx.move_to(x, y0)
        ctx.line_to(x, y1)
    for y in range(int(y0) // 40 * 40, int(y1), 40):
        ctx.move_to(x0, y)
        ctx.line_to(x1, y)
    ctx.stroke()


def draw_machinery(ctx, t, show_boilers=1.0, show_steam=1.0, show_engine=1.0, show_turbine=1.0,
                   show_shafts=1.0):
    """Engine-room cutaway, in ship-local coords (call inside local(ctx, m) and hull clip)."""
    ctx.save()
    # decks
    ctx.set_line_width(0.5)
    rgba(ctx, CYAN, 0.25)
    for y in (-61, -47, -33, -19):
        ctx.move_to(0, y)
        ctx.line_to(1000, y)
    ctx.stroke()
    # bulkheads
    ctx.set_line_width(1.0)
    rgba(ctx, CYAN, 0.35)
    for i, bx in enumerate(BULKHEADS):
        ctx.move_to(bx, 42)
        ctx.line_to(bx, BH_TOP[i])
    ctx.stroke()
    # boilers
    if show_boilers > 0:
        for ri, (xa, xb) in enumerate(boiler_rooms()):
            ra = show_boilers * ramp(show_boilers * 6 - ri * 0.8, 0, 1)
            if ra <= 0:
                continue
            bw = (xb - xa - 9) / 2
            for bi in range(2):
                bx = xa + 3 + bi * (bw + 3)
                g = cairo.LinearGradient(0, -22, 0, 34)
                g.add_color_stop_rgba(0, 0.32, 0.34, 0.38, ra)
                g.add_color_stop_rgba(1, 0.12, 0.13, 0.15, ra)
                ctx.set_source(g)
                rr = 6
                ctx.new_sub_path()
                ctx.arc(bx + rr, -20 + rr, rr, math.pi, 1.5 * math.pi)
                ctx.arc(bx + bw - rr, -20 + rr, rr, 1.5 * math.pi, 2 * math.pi)
                ctx.arc(bx + bw - rr, 33 - rr, rr, 0, 0.5 * math.pi)
                ctx.arc(bx + rr, 33 - rr, rr, 0.5 * math.pi, math.pi)
                ctx.close_path()
                ctx.fill()
                rgba(ctx, CYAN, 0.35 * ra)
                ctx.set_line_width(0.5)
                for yy in (-8, 4, 16):
                    ctx.move_to(bx + 2, yy)
                    ctx.line_to(bx + bw - 2, yy)
                ctx.stroke()
                for fi in range(3):
                    fx = bx + bw * (fi + 0.5) / 3
                    fl = 0.65 + 0.35 * math.sin(t * (9 + fi) + ri * 3 + bi) * math.sin(t * 5.3 + fi)
                    draw_glow(ctx, fx, 26, 11, (1.0, 0.45, 0.12), 0.8 * fl * ra)
                    rgba(ctx, (1.0, 0.7, 0.3), fl * ra)
                    ctx.arc(fx, 26, 2.6, 0, 2 * math.pi)
                    ctx.fill()
            # coal bunker
            rgba(ctx, (0.05, 0.05, 0.06), 0.9 * ra)
            ctx.move_to(xa, 40)
            ctx.curve_to(xa + 3, 30, xa + 6, 30, xa + 8, 40)
            ctx.fill()
            # ember particles
            for e in range(6):
                age = (t * 0.6 + hash01(ri, e)) % 1
                ex = xa + (xb - xa) * hash01(e, ri, 3) + math.sin(t * 3 + e) * 2
                ey = 24 - age * 30
                rgba(ctx, (1.0, 0.6, 0.2), ra * (1 - age) * 0.8)
                ctx.arc(ex, ey, 0.7, 0, 2 * math.pi)
                ctx.fill()
    # steam pipes boilers -> engines
    if show_steam > 0:
        ctx.set_line_width(2.2)
        ctx.set_line_cap(cairo.LINE_CAP_ROUND)
        for ri, (xa, xb) in enumerate(boiler_rooms()):
            cx = (xa + xb) / 2
            rgba(ctx, (0.6, 0.6, 0.62), 0.7 * show_steam)
            ctx.move_to(cx, -20)
            ctx.line_to(cx, -50 - ri * 0.4)
            ctx.stroke()
        rgba(ctx, (0.6, 0.6, 0.62), 0.7 * show_steam)
        ctx.move_to(790, -50)
        ctx.line_to(430, -50)
        ctx.line_to(430, -45)
        ctx.move_to(430, -50)
        ctx.line_to(355, -50)
        ctx.line_to(355, -45)
        ctx.stroke()
        ctx.save()
        ctx.set_dash([5, 6], -t * 40)
        rgba(ctx, (0.85, 0.97, 1.0), 0.95 * show_steam)
        ctx.set_line_width(1.2)
        for ri, (xa, xb) in enumerate(boiler_rooms()):
            cx = (xa + xb) / 2
            ctx.move_to(cx, -20)
            ctx.line_to(cx, -50)
        ctx.stroke()
        ctx.move_to(790, -50)
        ctx.line_to(430, -50)
        ctx.line_to(430, -45)
        ctx.move_to(430, -50)
        ctx.line_to(355, -50)
        ctx.line_to(355, -45)
        ctx.stroke()
        ctx.restore()
    th = t * 2 * math.pi * 1.25
    # reciprocating engines
    if show_engine > 0:
        a = show_engine
        cyl = [362, 384, 406, 428]
        widths = [14, 16, 18, 18]
        for i, (cx, cw) in enumerate(zip(cyl, widths)):
            ph = th + i * math.pi / 2
            g = cairo.LinearGradient(cx - cw / 2, 0, cx + cw / 2, 0)
            g.add_color_stop_rgba(0, 0.25, 0.27, 0.3, a)
            g.add_color_stop_rgba(0.5, 0.5, 0.53, 0.58, a)
            g.add_color_stop_rgba(1, 0.22, 0.24, 0.27, a)
            ctx.set_source(g)
            ctx.rectangle(cx - cw / 2, -45, cw, 24)
            ctx.fill()
            # frame
            rgba(ctx, (0.2, 0.22, 0.25), a)
            ctx.rectangle(cx - cw / 2 + 1, -21, 2, 36)
            ctx.rectangle(cx + cw / 2 - 3, -21, 2, 36)
            ctx.fill()
            crank = (cx + 5 * math.cos(ph), 24 + 5 * math.sin(ph))
            head_y = -8 + 5 * math.sin(ph) - 1
            rgba(ctx, (0.85, 0.85, 0.82), a)
            ctx.set_line_width(2)
            ctx.move_to(cx, -21)
            ctx.line_to(cx, head_y)
            ctx.stroke()
            rgba(ctx, (0.75, 0.7, 0.55), a)
            ctx.set_line_width(2.4)
            ctx.move_to(cx, head_y)
            ctx.line_to(*crank)
            ctx.stroke()
            ctx.rectangle(cx - 4, head_y - 2, 8, 4)
            ctx.fill()
            rgba(ctx, (0.35, 0.37, 0.4), a)
            ctx.arc(cx, 24, 7, 0, 2 * math.pi)
            ctx.fill()
            rgba(ctx, (0.9, 0.8, 0.5), a)
            ctx.arc(*crank, 1.8, 0, 2 * math.pi)
            ctx.fill()
        rgba(ctx, (0.18, 0.2, 0.22), a)
        ctx.rectangle(350, 31, 92, 7)
        ctx.fill()
    if show_turbine > 0:
        a = show_turbine
        g = cairo.LinearGradient(0, -6, 0, 30)
        g.add_color_stop_rgba(0, 0.55, 0.58, 0.62, a)
        g.add_color_stop_rgba(0.5, 0.3, 0.32, 0.36, a)
        g.add_color_stop_rgba(1, 0.15, 0.16, 0.18, a)
        ctx.set_source(g)
        ctx.move_to(282, 2)
        ctx.line_to(330, -6)
        ctx.line_to(330, 30)
        ctx.line_to(282, 24)
        ctx.close_path()
        ctx.fill()
        rgba(ctx, CYAN, 0.7 * a)
        ctx.set_line_width(0.7)
        sp = 3.0
        off = (t * 2.75 * 18) % sp
        x = 284 + off
        while x < 329:
            ctx.move_to(x, 3 - (x - 282) / 6)
            ctx.line_to(x, 23 + (x - 282) / 7)
            x += sp
        ctx.stroke()
        # exhaust steam engine -> turbine
        ctx.save()
        rgba(ctx, (0.6, 0.6, 0.62), 0.7 * a)
        ctx.set_line_width(2.2)
        ctx.move_to(350, -38)
        ctx.curve_to(340, -38, 335, -20, 330, 4)
        ctx.stroke()
        ctx.set_dash([4, 5], -t * 30)
        rgba(ctx, (1.0, 0.75, 0.45), 0.95 * a)
        ctx.set_line_width(1.1)
        ctx.move_to(350, -38)
        ctx.curve_to(340, -38, 335, -20, 330, 4)
        ctx.stroke()
        ctx.restore()
    if show_shafts > 0:
        a = show_shafts
        ctx.save()
        rgba(ctx, (0.55, 0.55, 0.57), a)
        ctx.set_line_width(2.4)
        ctx.move_to(350, 24)
        ctx.line_to(58, 27)
        ctx.move_to(282, 14)
        ctx.line_to(52, 33)
        ctx.stroke()
        ctx.set_dash([2, 6], -t * 25)
        rgba(ctx, (1, 1, 1), 0.6 * a)
        ctx.set_line_width(1)
        ctx.move_to(350, 24)
        ctx.line_to(58, 27)
        ctx.move_to(282, 14)
        ctx.line_to(52, 33)
        ctx.stroke()
        ctx.restore()
    ctx.restore()


def draw_props_side(ctx, t, a):
    ctx.save()
    for hx, hy, R, nb, rate in ((60, 27, 13, 3, 1.25), (50, 33, 9, 4, 2.75)):
        th = t * 2 * math.pi * rate
        for b in range(nb):
            ang = th + b * 2 * math.pi / nb
            tip = R * math.cos(ang)
            wdt = 2 + 2.5 * abs(math.sin(ang))
            rgba(ctx, (0.85, 0.7, 0.4), a * (0.6 + 0.4 * abs(math.sin(ang))))
            ctx.set_line_width(wdt)
            ctx.set_line_cap(cairo.LINE_CAP_ROUND)
            ctx.move_to(hx, hy)
            ctx.line_to(hx - 1.5 * math.sin(ang), hy + tip)
            ctx.stroke()
        rgba(ctx, (0.7, 0.6, 0.35), a)
        ctx.arc(hx, hy, 2.4, 0, 2 * math.pi)
        ctx.fill()
    ctx.restore()


def prop_inset(ctx, t, cx, cy, a):
    if a <= 0:
        return
    ctx.save()
    rgba(ctx, (0.0, 0.03, 0.07), 0.75 * a)
    ctx.arc(cx, cy, 165, 0, 2 * math.pi)
    ctx.fill()
    rgba(ctx, CYAN, 0.5 * a)
    ctx.set_line_width(1.2)
    ctx.arc(cx, cy, 165, 0, 2 * math.pi)
    ctx.stroke()
    for px, py, R, nb, rate in ((cx - 75, cy + 20, 58, 3, 1.25), (cx + 75, cy + 20, 58, 3, -1.25),
                                (cx, cy - 30, 40, 4, 2.75)):
        th = t * 2 * math.pi * rate * 0.35
        for b in range(nb):
            ang = th + b * 2 * math.pi / nb
            ctx.save()
            ctx.translate(px, py)
            ctx.rotate(ang)
            g = cairo.LinearGradient(0, 0, R, 0)
            g.add_color_stop_rgba(0, 0.95, 0.78, 0.45, a)
            g.add_color_stop_rgba(1, 0.65, 0.48, 0.22, a)
            ctx.set_source(g)
            ctx.move_to(0, 0)
            ctx.curve_to(R * 0.3, -R * 0.45, R * 0.95, -R * 0.4, R, 0)
            ctx.curve_to(R * 0.9, R * 0.25, R * 0.3, R * 0.2, 0, 0)
            ctx.fill()
            ctx.restore()
        rgba(ctx, (0.45, 0.35, 0.2), a)
        ctx.arc(px, py, R * 0.14, 0, 2 * math.pi)
        ctx.fill()
    text(ctx, "VIEW FROM ASTERN", cx, cy + 130, 16, color=CYAN, alpha=0.9 * a, spacing=5)
    ctx.restore()


def scene_engines(ctx, t, S):
    dur, c = S["dur"], S["cues"]
    s, ox, wl = 1.75, 85, 560
    m = ship_world_matrix(ox, wl, s)

    def W_(x):
        return ox + s * x

    keys = [(0, (960, 470, 1.0)), (c[1][0] - 0.6, (960, 470, 1.0)), (c[1][0] + 1.4, (W_(618), 560, 2.0)),
            (c[2][0] - 0.2, (W_(600), 560, 2.05)), (c[2][0] + 1.6, (W_(400), 580, 2.3)),
            (c[3][0] - 0.2, (W_(380), 580, 2.3)), (c[3][0] + 1.6, (W_(200), 600, 2.1)),
            (c[4][0] - 0.2, (W_(180), 600, 2.1)), (c[4][0] + 2.2, (960, 470, 1.0)),
            (dur, (960, 460, 1.04))]
    cx, cy, z = keyframes(t, keys)
    ctx.save()
    camera(ctx, cx, cy, z)
    draw_blueprint_bg(ctx, t)
    # water
    g = cairo.LinearGradient(0, wl, 0, H + 300)
    g.add_color_stop_rgba(0, 0.05, 0.22, 0.4, 0.55)
    g.add_color_stop_rgba(1, 0.0, 0.03, 0.08, 0.8)
    ctx.set_source(g)
    ctx.rectangle(-400, wl, W + 800, H + 300)
    ctx.fill()
    with local(ctx, m):
        draw_ship(ctx, t, night=0.6, lights=0.35, smoke=0.6, speed=30, hull_alpha=0.5)
        hull_path(ctx)
        hp = ctx.copy_path()
        rgba(ctx, (0.02, 0.04, 0.07), 0.75)
        ctx.fill()
        ctx.save()
        ctx.append_path(hp)
        ctx.clip()
        draw_machinery(ctx, t, show_boilers=ramp(t, c[1][0], c[1][0] + 3),
                       show_steam=ramp(t, c[2][0] - 0.5, c[2][0] + 1.5),
                       show_engine=ramp(t, c[2][0], c[2][0] + 1.5),
                       show_turbine=ramp(t, c[3][0], c[3][0] + 1.5),
                       show_shafts=ramp(t, c[2][0] + 2, c[2][0] + 4))
        ctx.restore()
        ctx.append_path(hp)
        rgba(ctx, CYAN, 0.6)
        ctx.set_line_width(1.0 / s * 1.6)
        ctx.stroke()
        draw_props_side(ctx, t, ramp(t, c[2][0] + 2, c[2][0] + 4))
    # waterline
    rgba(ctx, CYAN, 0.35)
    ctx.set_line_width(1.2)
    ctx.move_to(-400, wl)
    ctx.line_to(W + 400, wl)
    ctx.stroke()
    # prop wash bubbles
    pa = ramp(t, c[2][0] + 2, c[2][0] + 4)
    for i in range(40):
        age = (t * 0.7 + hash01(i, 6)) % 1
        bx = W_(52) - age * 260
        by = wl + 30 * s + (hash01(i, 2) - 0.5) * 40 * (0.3 + age) - age * 25
        rgba(ctx, (0.8, 0.95, 1.0), pa * 0.5 * (1 - age))
        ctx.arc(bx, by, 1 + 2.5 * hash01(i, 9), 0, 2 * math.pi)
        ctx.fill()
    dust(ctx, t, 50, 12, CYAN, 0.25, x0=-300, x1=2200, y0=0, y1=1100, size=1.4)
    ctx.restore()

    # screen-space annotations
    def ann(s_, lx, ly, a, dx=-160, dy=-170):
        if a <= 0:
            return
        sx, sy = to_screen(ctx, m, lx, ly)
        label(ctx, s_, sx + dx, sy + dy, a, size=22, align="right" if dx < 0 else "left",
              line_to=(sx, sy))
    ann("29 BOILERS  ·  159 FURNACES", 700, -10, window(t, c[1][0] + 1.8, c[2][0] + 0.2), dx=-140,
        dy=-190)
    ann("COAL SHOVELLED BY HAND, DAY AND NIGHT", 560, 26, window(t, c[1][0] + 4.5, c[2][0] + 0.2),
        dx=-60, dy=200)
    ann("2 × FOUR-CYLINDER TRIPLE-EXPANSION ENGINES", 395, -42, window(t, c[2][0] + 1.4, c[3][0] + 0.2),
        dx=-100, dy=-200)
    ann("OUTER PROPELLERS", 60, 27, window(t, c[2][0] + 3.5, c[3][0] + 0.2), dx=200, dy=200)
    ann("LOW-PRESSURE TURBINE", 306, 8, window(t, c[3][0] + 1.0, c[4][0] + 0.2), dx=-130, dy=-230)
    ann("CENTRE PROPELLER", 50, 33, window(t, c[3][0] + 2.5, c[4][0] + 0.2), dx=200, dy=180)
    prop_inset(ctx, t, 1580, 700, window(t, c[3][0] + 2.0, c[4][0] + 0.8, 0.8, 0.8))

    chapter_card(ctx, t, "CHAPTER  II", "THE MACHINE", end=c[0][1] + 0.4)
    sa = window(t, c[4][0] + 2.0, dur - 0.2)
    stat(ctx, W / 2 - 300, 250, counter(50000, (t - c[4][0] - 2) / 2.2), "HORSEPOWER", sa,
         align="center", size=70)
    stat(ctx, W / 2 + 300, 250, counter(23, (t - c[4][0] - 3) / 1.8) + " KN", "SERVICE SPEED",
         window(t, c[4][0] + 3.0, dur - 0.2), align="center", size=70)


# ===================================================================== 4. COMPARTMENTS
def flood(ctx, cam_m, m, comp, level_y, alpha=0.85, t=0.0):
    """Fill compartment `comp` with water up to world height level_y (horizontal in world)."""
    xa, xb = COMPS[comp]
    ctx.save()
    ctx.transform(m)
    hull_path(ctx)
    ctx.clip()
    ctx.rectangle(xa + 0.6, -90, xb - xa - 1.2, 140)
    ctx.clip()
    ctx.set_matrix(cam_m)
    g = cairo.LinearGradient(0, level_y, 0, level_y + 160)
    g.add_color_stop_rgba(0, 0.25, 0.6, 0.95, alpha)
    g.add_color_stop_rgba(1, 0.05, 0.2, 0.45, alpha)
    ctx.set_source(g)
    ctx.rectangle(-2000, level_y, 6000, 3000)
    ctx.fill()
    rgba(ctx, (0.75, 0.92, 1.0), alpha)
    ctx.set_line_width(2)
    ctx.move_to(-2000, level_y + math.sin(t * 3) * 1.0)
    ctx.line_to(4000, level_y + math.sin(t * 3 + 1) * 1.0)
    ctx.stroke()
    ctx.restore()


def comp_bottom_top(m, comp):
    """World y of the lowest point of a compartment and of the top of its aft bulkhead."""
    xa, xb = COMPS[comp]
    bottom = max(m.transform_point(xa, 42)[1], m.transform_point(xb, 42)[1])
    if xb >= 999:
        bottom = max(bottom, m.transform_point(990, 30)[1])
    bi = BULKHEADS.index(xa) if xa in BULKHEADS else None
    top = m.transform_point(xa, BH_TOP[bi])[1] if bi is not None else m.transform_point(xa, -70)[1]
    return bottom, top


def cutaway_ship(ctx, t, m, bulk_reveal=1.0, door_close=0.0, night=0.3, lights=0.6,
                 interior=(0.06, 0.08, 0.1), highlight=None, numbers=0.0, tops=0.0):
    with local(ctx, m):
        draw_ship(ctx, t, night=night, lights=lights, smoke=0.0)
        hull_path(ctx)
        hp = ctx.copy_path()
        ctx.save()
        ctx.clip()
        ctx.rectangle(0, -66, 1000, 120)
        ctx.clip()
        rgba(ctx, interior, 0.92)
        ctx.paint()
        rgba(ctx, CYAN, 0.12)
        ctx.set_line_width(0.4)
        for y in (-61, -47, -33, -19, -5, 9, 23):
            ctx.move_to(0, y)
            ctx.line_to(1000, y)
        ctx.stroke()
        if highlight:
            for ci, a in highlight:
                xa, xb = COMPS[ci]
                rgba(ctx, RED, 0.35 * a)
                ctx.rectangle(xa, -70, xb - xa, 120)
                ctx.fill()
        ctx.restore()
        # bulkheads
        n = len(BULKHEADS)
        for i, bx in enumerate(BULKHEADS):
            a = ramp(bulk_reveal * (n + 2) - i, 0, 1.2)
            if a <= 0:
                continue
            grow = ease_out(a)
            top = BH_TOP[i]
            y_top = lerp(42, top, grow)
            rgba(ctx, (0.85, 0.93, 1.0), 0.9 * a)
            ctx.set_line_width(2.0)
            ctx.move_to(bx, 40)
            ctx.line_to(bx, y_top)
            ctx.stroke()
            draw_glow(ctx, bx, y_top, 10, CYAN, 0.6 * a * (1 - grow * 0.6))
            # watertight door
            if door_close > 0 or bulk_reveal >= 1:
                d = ramp(door_close * (n + 3) - i, 0, 1)
                dy = lerp(14, 26, ease_in(d))
                rgba(ctx, lerpc((0.9, 0.75, 0.3), RED, d), 0.95 * a)
                ctx.rectangle(bx - 2.2, dy, 4.4, 12)
                ctx.fill()
                if 0 < d < 1:
                    draw_glow(ctx, bx, 30, 18, RED, 0.9)
            if tops > 0:
                draw_glow(ctx, bx, top, 16, (1.0, 0.45, 0.3), 0.8 * tops)
                rgba(ctx, (1.0, 0.6, 0.4), tops)
                ctx.set_dash([2, 2])
                ctx.set_line_width(0.8)
                ctx.move_to(bx, top)
                ctx.line_to(bx, deck_y(bx) + 2)
                ctx.stroke()
                ctx.set_dash([])
        ctx.append_path(hp)
        rgba(ctx, (0.75, 0.88, 1.0), 0.5)
        ctx.set_line_width(0.8)
        ctx.stroke()
    if numbers > 0:
        for ci, (xa, xb) in enumerate(COMPS):
            sx, sy = ctx.user_to_device(*m.transform_point((xa + xb) / 2, 34))
            a = numbers * ramp(numbers * 18 - ci, 0, 1)
            ctx.save()
            ctx.identity_matrix()
            text(ctx, str(ci + 1), sx, sy, 15, color=CREAM, alpha=0.8 * a, shadow=0)
            ctx.restore()


def underwater_overlay(ctx, t, wl, a=0.5, x0=-600, x1=W + 600):
    g = cairo.LinearGradient(0, wl, 0, H + 300)
    g.add_color_stop_rgba(0, 0.05, 0.3, 0.45, a * 0.55)
    g.add_color_stop_rgba(1, 0.0, 0.04, 0.1, a)
    ctx.set_source(g)
    ctx.rectangle(x0, wl, x1 - x0, H + 600)
    ctx.fill()
    ctx.save()
    ctx.rectangle(x0, wl, x1 - x0, H + 600)
    ctx.clip()
    for i in range(6):
        light_rays(ctx, t, 200 + i * 340, wl - 40, (0.6, 0.85, 1.0), 0.05, n=3, spread=0.25,
                   length=700, base_angle=math.pi / 2 + 0.15)
    ctx.restore()
    rgba(ctx, (0.8, 0.95, 1.0), 0.55 * a)
    ctx.set_line_width(1.5)
    ctx.move_to(x0, wl)
    x = x0
    while x <= x1:
        ctx.line_to(x, wl + math.sin(x * 0.02 + t * 1.5) * 1.5)
        x += 20
    ctx.stroke()


def scene_compartments(ctx, t, S):
    dur, c = S["dur"], S["cues"]
    s, ox, wl = 1.7, 110, 600
    t_demo_a, t_demo_b = c[3][0], c[3][0] + 2.8
    # flood demo choreography
    fa = ramp(t, t_demo_a + 0.2, t_demo_a + 1.8) * (1 - ramp(t, t_demo_b - 0.4, t_demo_b))
    fb = ramp(t, t_demo_b + 0.2, t_demo_b + 2.0) * (1 - ramp(t, c[4][0] + 0.4, c[4][0] + 1.6))
    sink = 5 * fa + 7 * fb
    trim = 1.6 * fb
    m = ship_world_matrix(ox + 500 * s, wl + sink, s, trim, pivot=(500, 0))
    ctx.save()
    camera(ctx, lerp(960, 980, t / dur), lerp(520, 540, t / dur), lerp(1.0, 1.05, ease_io(t / dur)))
    cam_m = ctx.get_matrix()
    draw_sky(ctx, wl, [(0, (0.03, 0.05, 0.09)), (0.7, (0.12, 0.17, 0.24)), (1, (0.36, 0.38, 0.4))])
    draw_haze(ctx, wl, (0.5, 0.5, 0.5), 0.25)
    rev = ramp(t, c[1][0], c[1][1] - 0.5)
    hl = []
    cutaway_ship(ctx, t, m, bulk_reveal=rev, door_close=ramp(t, c[2][0] + 2.0, c[2][0] + 5.5),
                 night=0.35, lights=0.5, numbers=ramp(t, c[1][0] + 2, c[1][1]))
    for comp in (7, 8):
        b, tp = comp_bottom_top(m, comp)
        if fa > 0:
            flood(ctx, cam_m, m, comp, lerp(b, wl + 8, fa), 0.85 * min(1, fa * 3), t)
    for comp in (0, 1, 2, 3):
        b, tp = comp_bottom_top(m, comp)
        if fb > 0:
            flood(ctx, cam_m, m, comp, lerp(b, wl + 14, fb), 0.85 * min(1, fb * 3), t)
    underwater_overlay(ctx, t, wl, 0.55)
    ctx.restore()

    # bridge switch inset
    ia = window(t, c[2][0] + 0.2, c[2][1] + 0.4)
    if ia > 0:
        x, y = 260, 300
        rgba(ctx, (0.02, 0.03, 0.05), 0.8 * ia)
        ctx.rectangle(x - 110, y - 90, 220, 180)
        ctx.fill()
        rgba(ctx, GOLD, 0.8 * ia)
        ctx.set_line_width(1.2)
        ctx.rectangle(x - 110, y - 90, 220, 180)
        ctx.stroke()
        flip = ease_out((t - c[2][0] - 1.4) / 0.4)
        ang = lerp(-0.7, 0.7, flip)
        rgba(ctx, (0.5, 0.42, 0.3), ia)
        ctx.arc(x, y + 20, 18, 0, 2 * math.pi)
        ctx.fill()
        ctx.set_line_width(7)
        ctx.set_line_cap(cairo.LINE_CAP_ROUND)
        rgba(ctx, (0.85, 0.75, 0.5), ia)
        ctx.move_to(x, y + 20)
        ctx.line_to(x + 60 * math.sin(ang), y + 20 - 60 * math.cos(ang))
        ctx.stroke()
        lamp = RED if flip > 0.5 else (0.3, 0.1, 0.1)
        draw_glow(ctx, x + 70, y - 55, 30, lamp, ia * (0.9 if flip > 0.5 else 0.2))
        text(ctx, "BRIDGE CONTROL", x, y + 75, 16, color=GOLD, alpha=ia, spacing=4)
        text(ctx, "DOORS CLOSED" if flip > 0.5 else "DOORS OPEN", x, y - 60, 15,
             color=RED if flip > 0.5 else CREAM, alpha=ia, spacing=3)

    chapter_card(ctx, t, "CHAPTER  III", "SIXTEEN COMPARTMENTS", end=c[0][1] + 0.5)
    text(ctx, "15 WATERTIGHT BULKHEADS", W / 2, 230, 34, color=CREAM, alpha=window(t, c[1][0] + 0.6,
                                                                                    c[1][1] + 0.3),
         spacing=8)
    text(ctx, "ANY TWO FLOODED  —  STILL AFLOAT", W / 2, 230, 32, color=CREAM,
         alpha=window(t, t_demo_a + 0.8, t_demo_b - 0.2), spacing=6)
    text(ctx, "FIRST FOUR FLOODED  —  STILL AFLOAT", W / 2, 230, 32, color=CREAM,
         alpha=window(t, t_demo_b + 0.8, c[4][0] + 0.5), spacing=6)

    # headline
    ha = window(t, c[4][0] + 0.8, dur - 0.1, 1.0, 0.5)
    if ha > 0:
        rgba(ctx, (0, 0, 0), 0.78 * ha)
        ctx.rectangle(0, 0, W, H)
        ctx.fill()
        drop = ramp(t, c[4][0] + 4.4, c[4][0] + 5.6)
        text(ctx, "THE SHIPBUILDER  ·  1911", W / 2, 380, 22, color=GOLD, alpha=ha * (1 - drop),
             spacing=8)
        word = "PRACTICALLY"
        pw = text_width(ctx, word + " ", 70, spacing=6)
        uw = text_width(ctx, "UNSINKABLE", 70, spacing=6)
        total = lerp(pw + uw, uw, ease_io(drop))
        x0 = W / 2 - total / 2
        text(ctx, word, x0, 530, 70, color=CREAM, alpha=ha * (1 - drop), align="left", spacing=6)
        if drop > 0:
            rgba(ctx, RED, ha * (1 - drop) * 0.9)
            ctx.set_line_width(5)
            ctx.move_to(x0 - 10, 508)
            ctx.line_to(x0 - 10 + (pw + 10) * ease_out(drop * 3), 508)
            ctx.stroke()
        size = lerp(70, 96, ease_io(drop))
        text(ctx, "UNSINKABLE", x0 + lerp(pw, 0, ease_io(drop)) +
             (lerp(uw, text_width(ctx, "UNSINKABLE", size, spacing=6), ease_io(drop)) - uw) * 0 -
             (text_width(ctx, "UNSINKABLE", size, spacing=6) - uw) / 2 * drop,
             530, size, color=CREAM, alpha=ha, align="left", spacing=6, glow=0.8 * drop,
             glow_color=GOLD)
        text(ctx, "“…designed to be practically unsinkable.”", W / 2, 640, 30, font=SERIF_BODY,
             italic=True, color=(0.8, 0.78, 0.72), alpha=ha * (1 - drop))


# ===================================================================== 5. THE FLAW
def scene_flaw(ctx, t, S):
    dur, c = S["dur"], S["cues"]
    split = c[3][0] - 0.3
    pa = 1 - ramp(t, split - 0.8, split + 0.4)
    pb = ramp(t, split - 0.4, split + 0.8)
    if pa > 0:
        with group(ctx, pa):
            flaw_part_a(ctx, t, S)
    if pb > 0:
        with group(ctx, pb):
            lifeboats_part(ctx, t - split, dur - split)


def flaw_part_a(ctx, t, S):
    c = S["cues"]
    s, wl = 2.7, 640
    trim = lerp(2.0, 5.0, ease_io(t / 18))
    m = ship_world_matrix(1100, wl + 30, s, trim, pivot=(800, 0))
    ctx.save()
    camera(ctx, lerp(1250, 1300, t / 20), lerp(540, 600, t / 20), lerp(1.0, 1.08, ease_io(t / 20)))
    cam_m = ctx.get_matrix()
    night_sea_bg(ctx, t, wl, milky=0.6, star_a=0.7, reflect=0.3)
    tops = window(t, c[1][0] + 0.8, c[2][0] + 1.0, 1.0, 1.0)
    cutaway_ship(ctx, t, m, bulk_reveal=1.0, door_close=1.0, night=0.75, lights=0.8,
                 interior=(0.05, 0.06, 0.08), tops=tops)
    # ice-cube-tray cascade
    t0 = c[2][0] + 0.3
    per = 1.25
    for comp in range(7):
        f = clamp((t - (t0 + comp * per)) / per)
        if f <= 0:
            continue
        b, tp = comp_bottom_top(m, comp)
        level = lerp(b, tp, ease_io(f))
        flood(ctx, cam_m, m, comp, level, 0.85, t)
        # spill-over stream into the next compartment
        nf = clamp((t - (t0 + (comp + 1) * per)) / per)
        if f >= 1 and comp < 6:
            xa = COMPS[comp][0]
            bi = BULKHEADS.index(xa)
            sx, sy = m.transform_point(xa, BH_TOP[bi])
            nb, ntp = comp_bottom_top(m, comp + 1)
            nlevel = lerp(nb, ntp, ease_io(nf))
            if nf < 1:
                ctx.save()
                ctx.set_line_cap(cairo.LINE_CAP_ROUND)
                rgba(ctx, (0.3, 0.65, 1.0), 0.9)
                ctx.set_line_width(9)
                ctx.move_to(sx + 6, sy - 3)
                ctx.curve_to(sx - 10, sy - 6, sx - 22, sy + 10, sx - 26, nlevel)
                ctx.stroke()
                ctx.set_dash([10, 14], -t * 120)
                rgba(ctx, (0.85, 0.95, 1.0), 0.8)
                ctx.set_line_width(3)
                ctx.move_to(sx + 6, sy - 3)
                ctx.curve_to(sx - 10, sy - 6, sx - 22, sy + 10, sx - 26, nlevel)
                ctx.stroke()
                ctx.restore()
                for j in range(10):
                    age = (t * 2 + j / 10) % 1
                    rgba(ctx, (0.85, 0.95, 1.0), 0.7 * (1 - age))
                    ctx.arc(sx - 26 + (hash01(j) - 0.5) * 30 * age, nlevel - 8 * math.sin(age * 3),
                            2, 0, 2 * math.pi)
                    ctx.fill()
    underwater_overlay(ctx, t, wl, 0.6, x0=-400, x1=3000)
    ctx.restore()
    chapter_card(ctx, t, "CHAPTER  IV", "THE FATAL FLAW", end=c[0][1] + 0.6)
    if tops > 0:
        bi = 4
        sx, sy = to_screen(ctx, m, BULKHEADS[bi], BH_TOP[bi])
        label(ctx, "BULKHEADS OPEN AT THE TOP", sx - 140, sy - 170, tops, size=24, align="right",
              line_to=(sx, sy))
    text(ctx, "LIKE AN ICE CUBE TRAY", W / 2, 230, 34, color=CREAM,
         alpha=window(t, c[2][0] + 0.8, c[3][0] - 0.6), spacing=10)


def boat_icon(ctx, x, y, w, a, col):
    rgba(ctx, col, a)
    ctx.move_to(x - w / 2, y)
    ctx.line_to(x + w / 2, y)
    ctx.curve_to(x + w / 2 - 4, y + w * 0.3, x + w * 0.2, y + w * 0.32, x, y + w * 0.32)
    ctx.curve_to(x - w * 0.2, y + w * 0.32, x - w / 2 + 4, y + w * 0.3, x - w / 2, y)
    ctx.fill()


def lifeboats_part(ctx, t, dur):
    g = cairo.RadialGradient(W / 2, H / 2, 100, W / 2, H / 2, 1200)
    g.add_color_stop_rgb(0, 0.05, 0.07, 0.11)
    g.add_color_stop_rgb(1, 0.0, 0.0, 0.02)
    ctx.set_source(g)
    ctx.paint()
    dust(ctx, t, 60, 21, (0.7, 0.8, 1.0), 0.25, size=1.5)
    text(ctx, "20 LIFEBOATS", W / 2, 215, 40, color=CREAM, alpha=ramp(t, 0.3, 1.3), spacing=10)
    for i in range(20):
        a = ramp(t, 0.4 + i * 0.06, 0.8 + i * 0.06)
        boat_icon(ctx, W / 2 - 19 * 33 / 2 + i * 33, 250, 26, a, GOLD)
    cols, rows, sp = 80, 28, 13.5
    x0 = W / 2 - (cols - 1) * sp / 2
    y0 = 330
    hi = ramp(t, 3.0, 4.6)
    total = 2224
    for i in range(total):
        r, cidx = divmod(i, cols)
        a = ramp(t, 1.0 + r * 0.05, 1.6 + r * 0.05)
        if a <= 0:
            continue
        seated = i < 1178
        col = lerpc((0.55, 0.6, 0.7), (1.0, 0.8, 0.45) if seated else (0.3, 0.36, 0.48), hi)
        rgba(ctx, col, a * (0.95 if seated or hi < 0.5 else 0.6))
        ctx.arc(x0 + cidx * sp, y0 + r * sp, 4.0, 0, 2 * math.pi)
        ctx.fill()
    la = ramp(t, 3.4, 4.4)
    yb = y0 + rows * sp + 34
    text(ctx, "1,178 SEATS", W / 2 - 300, yb, 30, color=(1.0, 0.82, 0.5), alpha=la, spacing=6)
    text(ctx, "2,224 ON BOARD", W / 2 + 300, yb, 30, color=(0.65, 0.72, 0.85), alpha=la, spacing=6)


# ===================================================================== 6. ICEBERG
ICE = [(0, 0), (40, -60), (70, -95), (95, -88), (120, -150), (150, -175), (175, -140), (205, -128),
       (230, -90), (262, -70), (290, -30), (310, 0)]


def draw_iceberg(ctx, x, y, s, a, t, rim=0.4):
    ctx.save()
    ctx.translate(x, y)
    ctx.scale(s, s)
    ctx.move_to(*ICE[0])
    for p in ICE[1:]:
        ctx.line_to(*p)
    ctx.close_path()
    g = cairo.LinearGradient(0, -175, 0, 0)
    g.add_color_stop_rgba(0, 0.22, 0.27, 0.34, a)
    g.add_color_stop_rgba(1, 0.06, 0.08, 0.12, a)
    ctx.set_source(g)
    ctx.fill()
    # facets
    rgba(ctx, (0.55, 0.65, 0.8), rim * a)
    ctx.set_line_width(1.2 / s)
    for (ax, ay), (bx, by) in ((ICE[4], (140, -60)), (ICE[5], (160, -40)), (ICE[2], (90, -20)),
                               (ICE[7], (215, -30))):
        ctx.move_to(ax, ay)
        ctx.line_to(bx, by)
    ctx.stroke()
    ctx.move_to(*ICE[0])
    for p in ICE[1:]:
        ctx.line_to(*p)
    rgba(ctx, (0.7, 0.8, 0.95), rim * a * 1.2)
    ctx.set_line_width(1.5 / s)
    ctx.stroke()
    # underwater bulk
    ctx.move_to(0, 0)
    ctx.curve_to(-60, 120, 380, 160, 310, 0)
    ctx.close_path()
    rgba(ctx, (0.15, 0.22, 0.3), 0.25 * a)
    ctx.fill()
    ctx.restore()


def scene_iceberg(ctx, t, S):
    dur, c = S["dur"], S["cues"]
    tb = c[3][0] - 0.4      # top-down
    tc = c[4][0] - 0.3      # close-up / damage
    a1 = 1 - ramp(t, tb - 0.6, tb + 0.2)
    a2 = ramp(t, tb - 0.4, tb + 0.4) * (1 - ramp(t, tc - 0.6, tc + 0.2))
    a3 = ramp(t, tc - 0.4, tc + 0.4)
    if a1 > 0:
        with group(ctx, a1):
            iceberg_wide(ctx, t, S)
    if a2 > 0:
        with group(ctx, a2):
            iceberg_top(ctx, t - tb, c[4][0] - c[3][0])
    if a3 > 0:
        with group(ctx, a3):
            iceberg_damage(ctx, t - tc, dur - tc)


def iceberg_wide(ctx, t, S):
    c = S["cues"]
    hy = 640
    alarm = c[2][0]
    sh = 0.0
    if t > alarm:
        sh = 6 * math.exp(-(t - alarm) * 1.5)
    keys = [(0, (960, 560, 1.0)), (c[1][0], (1080, 560, 1.06)), (c[1][0] + 4, (1380, 590, 1.45)),
            (alarm, (1420, 590, 1.5)), (alarm + 0.4, (1440, 590, 1.62)), (c[3][0], (1460, 590, 1.66))]
    cx, cy, z = keyframes(t, keys)
    ctx.save()
    camera(ctx, cx, cy, z, sx=sh * math.sin(t * 60), sy=sh * math.cos(t * 47))
    night_sea_bg(ctx, t, hy, milky=1.0, star_a=1.0, reflect=0.8, speed=0.25)
    s = 0.85
    x = 120 + 26 * t
    ia = ramp(t, c[1][0] - 1, c[1][0] + 3)
    draw_iceberg(ctx, 1730, hy + 6, 0.95, ia, t, rim=0.35 + 0.2 * ia)
    kw = dict(night=0.9, lights=1.0, smoke=0.5, speed=26 / s, smoke_col=(0.1, 0.11, 0.14))
    ship_reflection(ctx, t, x, hy + 40, s, 0.12, **kw)
    light_reflections(ctx, t, [x + s * xx for xx in range(90, 960, 50)], hy + 40, a=0.18, length=70)
    wake(ctx, t, x + 40 * s, hy + 40, s, a=0.3)
    ship_above_water(ctx, t, ship_world_matrix(x, hy + 40, s), hy + 40, **kw)
    ctx.restore()
    if t > alarm:
        k = math.exp(-(t - alarm) * 0.9) * (0.6 + 0.4 * math.sin((t - alarm) * 12))
        g = cairo.RadialGradient(W / 2, H / 2, 300, W / 2, H / 2, 1100)
        g.add_color_stop_rgba(0, 0.8, 0.05, 0.02, 0)
        g.add_color_stop_rgba(1, 0.8, 0.05, 0.02, 0.55 * k)
        ctx.set_source(g)
        ctx.paint()
    ta = window(t, c[0][0], c[2][1] + 1.0, 1.0, 1.0)
    text(ctx, "14 APRIL 1912  ·  NORTH ATLANTIC", 200, 220, 22, color=GOLD, alpha=ta, align="left",
         spacing=6)
    clock = "11:39 PM" if t < c[1][0] + 1.5 else "11:40 PM"
    text(ctx, clock, 200, 290, 56, color=CREAM, alpha=ta, align="left", spacing=4)
    text(ctx, "41° 46′ N   50° 14′ W", 200, 330, 20, color=(0.7, 0.75, 0.85), alpha=ta * 0.8,
         align="left", spacing=4)


def draw_ship_top(ctx, L, B, lights=1.0, damage=0.0):
    """Top-down Titanic, stern at x=0, bow at x=L, centreline y=0, starboard +y."""
    ctx.save()
    ctx.move_to(0, -B * 0.32)
    ctx.curve_to(L * 0.05, -B / 2, L * 0.2, -B / 2, L * 0.4, -B / 2)
    ctx.line_to(L * 0.75, -B / 2)
    ctx.curve_to(L * 0.9, -B / 2, L * 0.97, -B * 0.2, L, 0)
    ctx.curve_to(L * 0.97, B * 0.2, L * 0.9, B / 2, L * 0.75, B / 2)
    ctx.line_to(L * 0.4, B / 2)
    ctx.curve_to(L * 0.2, B / 2, L * 0.05, B / 2, 0, B * 0.32)
    ctx.curve_to(-L * 0.015, B * 0.1, -L * 0.015, -B * 0.1, 0, -B * 0.32)
    ctx.close_path()
    hp = ctx.copy_path()
    rgba(ctx, (0.1, 0.1, 0.11))
    ctx.fill()
    ctx.append_path(hp)
    ctx.save()
    ctx.clip()
    rgba(ctx, (0.28, 0.24, 0.19))
    ctx.rectangle(L * 0.02, -B / 2 + 4, L * 0.96, B - 8)
    ctx.fill()
    rgba(ctx, (0.33, 0.34, 0.37))
    ctx.rectangle(L * 0.17, -B * 0.36, L * 0.69, B * 0.72)
    ctx.fill()
    rgba(ctx, (0.2, 0.17, 0.14), 0.6)
    ctx.set_line_width(0.6)
    for yy in range(-int(B / 2), int(B / 2), 4):
        ctx.move_to(0, yy)
        ctx.line_to(L, yy)
    ctx.stroke()
    ctx.restore()
    for fx in FUNNELS:
        x = L * fx / 1000 - 12
        ctx.save()
        ctx.translate(x, 0)
        ctx.scale(1.0, 0.75)
        rgba(ctx, (0.55, 0.38, 0.15))
        ctx.arc(0, 0, B * 0.2, 0, 2 * math.pi)
        ctx.fill()
        rgba(ctx, (0.02, 0.02, 0.02))
        ctx.arc(0, 0, B * 0.14, 0, 2 * math.pi)
        ctx.fill()
        ctx.restore()
    for xa, xb in ((0.19, 0.33), (0.7, 0.845)):
        x = L * xa
        while x < L * xb:
            for side in (-1, 1):
                rgba(ctx, (0.75, 0.74, 0.7))
                ctx.rectangle(x, side * B * 0.4 - 3, L * 0.018, 6)
                ctx.fill()
            x += L * 0.023
    if lights > 0:
        for i in range(24):
            x = L * (0.1 + 0.8 * i / 23)
            for side in (-1, 1):
                draw_glow(ctx, x, side * B * 0.48, 10, (1.0, 0.8, 0.5), 0.35 * lights)
    if damage > 0:
        ctx.set_line_cap(cairo.LINE_CAP_ROUND)
        x_end = L * 0.995 - L * 0.33 * damage
        rgba(ctx, RED, 0.95)
        ctx.set_line_width(5)
        ctx.move_to(L * 0.96, B * 0.3)
        ctx.curve_to(L * 0.9, B / 2, L * 0.85, B / 2, x_end, B / 2)
        ctx.stroke()
        draw_glow(ctx, x_end, B / 2, 30, RED, 0.7)
    ctx.restore()


def iceberg_top(ctx, t, dur):
    # dark water from above
    g = cairo.RadialGradient(W / 2, H / 2, 100, W / 2, H / 2, 1200)
    g.add_color_stop_rgb(0, 0.02, 0.05, 0.09)
    g.add_color_stop_rgb(1, 0.0, 0.01, 0.025)
    ctx.set_source(g)
    ctx.paint()
    ctx.set_line_width(1)
    for i in range(160):
        x = (hash01(i, 1) * 2400 - t * 40) % 2400 - 240
        y = hash01(i, 2) * H
        rgba(ctx, (0.5, 0.6, 0.75), 0.06 + 0.06 * hash01(i, 3))
        ctx.move_to(x, y)
        ctx.line_to(x + 20 + 40 * hash01(i, 4), y)
        ctx.stroke()
    draw_stars(ctx, t, 0.25, H + 400, seed=11, n=300)
    L, B = 900, 94
    turn = ease_io((t - 0.3) / 3.5)
    heading = -lerp(0, 16, turn)
    sx, sy = 960 - 450, 600
    # iceberg position relative to ship (ship-frame)
    xr = 1150 - 160 * t
    contact = clamp((1000 - xr) / 330)
    yr = lerp(30, 115, ease_io(clamp((1100 - xr) / 520)))
    damage = ease_out(clamp((960 - xr) / 300))
    ctx.save()
    ctx.translate(sx + L / 2, sy)
    ctx.rotate(math.radians(heading))
    ctx.translate(-L / 2, 0)
    # wake
    for i in range(26):
        u = i / 26
        rgba(ctx, (0.75, 0.85, 0.95), 0.18 * (1 - u))
        ctx.set_line_width(2)
        ctx.move_to(-u * 700, -B * 0.25 - u * 90 + math.sin(t * 2 + i) * 3)
        ctx.line_to(-u * 700 - 20, -B * 0.25 - u * 90)
        ctx.move_to(-u * 700, B * 0.25 + u * 90 + math.sin(t * 2 + i) * 3)
        ctx.line_to(-u * 700 - 20, B * 0.25 + u * 90)
        ctx.stroke()
    draw_ship_top(ctx, L, B, lights=0.8, damage=damage if damage > 0.01 else 0)
    # iceberg from above
    ctx.save()
    ctx.translate(xr, B / 2 + yr - 50)
    ctx.rotate(0.4)
    ctx.move_to(-80, 0)
    for k in range(1, 13):
        ang = k / 12 * 2 * math.pi
        r = 70 + 30 * hash01(k, 31)
        ctx.line_to(r * math.cos(ang + math.pi), r * 0.8 * math.sin(ang + math.pi))
    ctx.close_path()
    g = cairo.RadialGradient(-10, -10, 10, 0, 0, 110)
    g.add_color_stop_rgb(0, 0.8, 0.88, 0.95)
    g.add_color_stop_rgb(1, 0.35, 0.45, 0.58)
    ctx.set_source(g)
    ctx.fill()
    ctx.restore()
    if 0.05 < contact < 0.98 and damage < 0.99:
        px = L * 0.995 - L * 0.33 * damage
        ctx.save()
        ctx.translate(px, B / 2)
        sparks(ctx, t, 0, 0, 5, rate=60, life=0.6, power=0.7, col=(0.85, 0.95, 1.0), grav=0)
        ctx.restore()
        for j in range(14):
            age = (t * 1.5 + j / 14) % 1
            rgba(ctx, (0.9, 0.95, 1.0), 0.8 * (1 - age))
            ctx.arc(px - age * 90, B / 2 + 10 + age * 60 * hash01(j, 3), 2 + 3 * hash01(j), 0,
                    2 * math.pi)
            ctx.fill()
    ctx.restore()
    a = ramp(t, 0.3, 1.2)
    text(ctx, "HARD-A-STARBOARD  ·  SHIP SWINGS TO PORT", W / 2, 230, 26, color=GOLD,
         alpha=a * (1 - ramp(t, 4.0, 4.8)), spacing=6)
    if damage > 0.05:
        text(ctx, "≈ 90 METRES OF STARBOARD HULL", W / 2, 230, 30, color=CREAM,
             alpha=ramp(t, 4.6, 5.4), spacing=6)


def iceberg_damage(ctx, t, dur):
    split = 3.6
    pa = 1 - ramp(t, split - 0.4, split + 0.4)
    pb = ramp(t, split - 0.4, split + 0.4)
    if pa > 0:
        with group(ctx, pa):
            plates_closeup(ctx, t)
    if pb > 0:
        with group(ctx, pb):
            s, wl = 1.7, 600
            m = ship_world_matrix(110, wl + 6, s, 0.8, pivot=(0, 0))
            ctx.save()
            camera(ctx, 960, 530, 1.0 + 0.03 * t)
            night_sea_bg(ctx, t, wl, milky=0.7, star_a=0.7, reflect=0.3)
            hl = [(ci, ramp(t, split + 0.4 + ci * 0.25, split + 0.8 + ci * 0.25)) for ci in range(6)]
            cutaway_ship(ctx, t, m, night=0.85, lights=0.9, highlight=hl)
            with local(ctx, m):
                rgba(ctx, RED, ramp(t, split + 0.3, split + 1.2))
                ctx.set_line_width(2.4)
                ctx.set_dash([5, 3])
                ctx.move_to(990, 20)
                ctx.line_to(COMPS[5][0] + 8, 26)
                ctx.stroke()
            underwater_overlay(ctx, t, wl, 0.55)
            ctx.restore()
            text(ctx, "SIX COMPARTMENTS OPEN TO THE SEA", W / 2, 230, 34, color=CREAM,
                 alpha=ramp(t, split + 1.8, split + 2.6), spacing=8)


def plates_closeup(ctx, t):
    g = cairo.LinearGradient(0, 0, 0, H)
    g.add_color_stop_rgb(0, 0.09, 0.1, 0.12)
    g.add_color_stop_rgb(1, 0.03, 0.035, 0.045)
    ctx.set_source(g)
    ctx.paint()
    ctx.save()
    camera(ctx, 960, 540, 1.0 + 0.05 * t)
    ph, pw = 170, 380
    seam_y = 540
    open_ = ease_out((t - 0.6) / 2.0)
    for r in range(-1, 8):
        for col in range(-1, 7):
            x = col * pw + (pw / 2 if r % 2 else 0) - 100
            y = 130 + r * ph
            buck = 0
            if abs(y + ph / 2 - seam_y) < ph * 1.2:
                buck = open_ * 10 * (hash01(r, col) - 0.3)
            g = cairo.LinearGradient(x, y, x + pw, y + ph)
            g.add_color_stop_rgb(0, 0.2 + 0.03 * hash01(r, col, 1), 0.21, 0.23)
            g.add_color_stop_rgb(1, 0.11, 0.115, 0.13)
            ctx.set_source(g)
            ctx.move_to(x + 3, y + 3 + buck)
            ctx.line_to(x + pw - 3, y + 3 - buck * 0.5)
            ctx.line_to(x + pw - 3, y + ph - 3 + buck)
            ctx.line_to(x + 3, y + ph - 3)
            ctx.close_path()
            ctx.fill()
            for k in range(1, 18):
                rx = x + k * pw / 18
                for ry in (y + 10, y + ph - 10):
                    popped = (abs(ry - seam_y) < 40) and hash01(k, r, col) < open_ * 0.9
                    if popped:
                        continue
                    g2 = cairo.RadialGradient(rx - 1.5, ry - 1.5, 0.5, rx, ry, 5)
                    g2.add_color_stop_rgb(0, 0.55, 0.56, 0.58)
                    g2.add_color_stop_rgb(1, 0.12, 0.12, 0.14)
                    ctx.set_source(g2)
                    ctx.arc(rx, ry, 5, 0, 2 * math.pi)
                    ctx.fill()
    # seam crack and jets
    if open_ > 0:
        ctx.set_line_width(4 + 6 * open_)
        rgba(ctx, (0.0, 0.0, 0.0), 0.9)
        ctx.move_to(-100, seam_y)
        x = -100
        while x < W + 100:
            ctx.line_to(x, seam_y + 6 * math.sin(x * 0.05) * open_)
            x += 25
        ctx.stroke()
        for j in range(16):
            jx = 80 + j * 120 + 30 * hash01(j)
            if hash01(j, 4) > open_ + 0.1:
                continue
            for p in range(26):
                age = (t * 1.8 + p / 26 + hash01(j, p)) % 1
                ang = -math.pi / 2 + (hash01(j, p, 2) - 0.5) * 1.2 + (1 if j % 2 else -1) * 0.3
                d = age * (160 + 120 * hash01(p, j))
                px = jx + math.cos(ang) * d * 0.6
                py = seam_y + math.sin(ang) * d * 0.3 + age * age * 260
                rgba(ctx, (0.75, 0.9, 1.0), (1 - age) * 0.7)
                ctx.arc(px, py, 1.5 + 3 * age, 0, 2 * math.pi)
                ctx.fill()
            draw_glow(ctx, jx, seam_y, 40, (0.6, 0.8, 1.0), 0.25)
    ctx.restore()
    # popped rivet heads flying
    for k in range(12):
        st = 0.8 + k * 0.18
        if t < st:
            continue
        age = t - st
        x = 200 + k * 130 + age * (80 * (hash01(k) - 0.5))
        y = 540 - age * 300 + age * age * 500
        rgba(ctx, (0.6, 0.6, 0.62), clamp(1.5 - age))
        ctx.arc(x, y, 6, 0, 2 * math.pi)
        ctx.fill()
    text(ctx, "BUCKLED PLATES  ·  POPPED RIVETS", W / 2, 230, 30, color=CREAM,
         alpha=window(t, 0.8, 3.6, 0.6, 0.4), spacing=8)


# ===================================================================== 7. SINKING
def scene_sinking(ctx, t, S):
    dur, c = S["dur"], S["cues"]
    hy = 610
    tbreak = c[2][0] + 3.6
    s = 1.18
    # intact pose keyframes (angle, sink)
    intact = keyframes(t, [(0, (1.5, 8)), (c[1][0], (3.0, 18)), (c[2][0], (10.0, 45)),
                           (tbreak, (24.0, 80))])
    lights = 1.0 - ramp(t, tbreak - 1.2, tbreak - 0.2)
    flick = window(t, tbreak - 2.6, tbreak - 0.2, 0.3, 0.3)
    end_a = ramp(t, c[3][0] - 1.5, c[3][0] + 0.5)
    ctx.save()
    camera(ctx, lerp(960, 980, t / dur), lerp(470, 520, t / dur), lerp(1.0, 0.95, t / dur))
    night_sea_bg(ctx, t, hy, milky=1.2, star_a=1.0, reflect=0.7, speed=0.2)
    pivot_x = 520
    X = 960
    kw = dict(night=0.9, lights=lights, smoke=0.0, flicker=flick)
    wl = hy + 2
    if t < tbreak:
        ang, sink = intact
        m = ship_world_matrix(X, hy + sink * 0.5, s, ang, pivot=(pivot_x, 0))
        light_reflections(ctx, t, [m.transform_point(xx, 0)[0] for xx in range(120, 900, 60)],
                          wl, a=0.2 * lights, length=80)
        ship_above_water(ctx, t, m, wl, under_alpha=0.14, **kw)
        foam_at(ctx, t, m, wl, 0, 1000)
        xr = ramp(t, 1.4, 2.4) * (1 - ramp(t, c[1][0] - 0.6, c[1][0] + 0.4))
        if xr > 0:
            with group(ctx, xr):
                with local(ctx, m):
                    for ci in range(6):
                        xa, xb = COMPS[ci]
                        ctx.save()
                        hull_path(ctx)
                        ctx.clip()
                        rgba(ctx, RED, 0.28)
                        ctx.rectangle(xa, -70, xb - xa, 120)
                        ctx.fill()
                        rgba(ctx, RED, 0.9)
                        ctx.set_line_width(1.2)
                        ctx.move_to(xa, 42)
                        ctx.line_to(xa, -60)
                        ctx.stroke()
                        ctx.restore()
    else:
        u = t - tbreak
        # stern: falls back, then rises to vertical and slides under
        st_ang = keyframes(u, [(0, (24,)), (1.6, (9,)), (4.2, (78,)), (6.0, (86,))])[0]
        bp = (420, 0)
        m0 = ship_world_matrix(X, hy + 80 * 0.5, s, 24.0, pivot=(pivot_x, 0))
        ax, ay = m0.transform_point(*bp)
        mb = ship_world_matrix(ax + 60 * ease_io(u / 3), ay + ease_in(u / 3.2) * 520, s,
                               24 + 34 * ease_io(u / 3), pivot=bp)
        st_dy = keyframes(u, [(0, (0,)), (1.6, (-12,)), (4.2, (50,)), (5.2, (95,)), (8.5, (900,))])[0]
        ms = ship_world_matrix(ax - 20 * ease_io(u / 3), ay + st_dy, s, st_ang, pivot=bp)
        kw_st = dict(kw)
        kw_st["funnels"] = u < 0.9
        # bow section (mostly underwater)
        ctx.save()
        _cm = ctx.get_matrix()
        ctx.transform(mb)
        if True:
            ctx.move_to(420, -400)
            ctx.line_to(1100, -400)
            ctx.line_to(1100, 100)
            ctx.line_to(420, 100)
            for i in range(10):
                ctx.line_to(420 + (8 if i % 2 else -6), 100 - i * 50)
            ctx.close_path()
            ctx.clip()
        ctx.set_matrix(_cm)
        ship_above_water(ctx, t, mb, wl, under_alpha=0.12 * (1 - ramp(u, 2, 4)), **kw)
        ctx.restore()
        ctx.save()
        _cm = ctx.get_matrix()
        ctx.transform(ms)
        if True:
            ctx.move_to(-200, -400)
            ctx.line_to(420, -400)
            for i in range(10):
                ctx.line_to(420 + (8 if i % 2 else -6), -400 + i * 50)
            ctx.line_to(420, 100)
            ctx.line_to(-200, 100)
            ctx.close_path()
            ctx.clip()
        ctx.set_matrix(_cm)
        ship_above_water(ctx, t, ms, wl, under_alpha=0.12 * (1 - ramp(u, 6, 8)), **kw_st)
        ctx.restore()
        foam_at(ctx, t, ms, wl, 0, 420, a=1 - ramp(u, 7, 9))
        # debris and splash at the break
        if u < 3:
            bx, by = ms.transform_point(420, -40)
            for j in range(30):
                age = (u * 1.3 + j / 30) % 1
                rgba(ctx, (0.75, 0.8, 0.85), 0.6 * (1 - age) * (1 - u / 3))
                ctx.arc(bx + (hash01(j) - 0.5) * 120 * age, by + age * 120 * hash01(j, 2) - 30 * age,
                        2, 0, 2 * math.pi)
                ctx.fill()
        # ripple rings after the stern disappears
        ra = ramp(u, 6.5, 7.5)
        if ra > 0:
            for i in range(4):
                rr = ((u - 6.5) * 60 + i * 50) % 260
                ctx.save()
                ctx.translate(X - 80, wl + 4)
                ctx.scale(1, 0.12)
                rgba(ctx, (0.7, 0.8, 0.95), 0.25 * ra * (1 - rr / 260))
                ctx.set_line_width(14)
                ctx.arc(0, 0, rr + 30, 0, 2 * math.pi)
                ctx.stroke()
                ctx.restore()
    # lifeboats in the foreground
    for i, (bx0, by) in enumerate(((240, hy + 120), (1580, hy + 90), (500, hy + 210), (1350, hy + 240))):
        bx = bx0 + (t * 6 * (1 if i % 2 else -1))
        rgba(ctx, (0.02, 0.025, 0.035), 1)
        ctx.move_to(bx - 40, by)
        ctx.line_to(bx + 40, by)
        ctx.curve_to(bx + 32, by + 10, bx + 10, by + 11, bx, by + 11)
        ctx.curve_to(bx - 10, by + 11, bx - 32, by + 10, bx - 40, by)
        ctx.fill()
        for p in range(5):
            rgba(ctx, (0.02, 0.025, 0.035), 1)
            ctx.arc(bx - 28 + p * 13, by - 4, 4, 0, 2 * math.pi)
            ctx.fill()
        draw_glow(ctx, bx + 30, by - 14, 24, (1.0, 0.75, 0.4), 0.55 + 0.1 * math.sin(t * 5 + i))
        light_reflections(ctx, t, [bx + 30], by + 10, a=0.25, length=40)
    ctx.restore()
    text(ctx, "15 APRIL 1912  ·  2:20 AM", W / 2, 230, 30, color=CREAM,
         alpha=window(t, c[2][0] + 1.0, c[2][1] + 2.5, 1.0, 1.0), spacing=8)
    if end_a > 0:
        rgba(ctx, (0, 0, 0), 0.35 * end_a)
        ctx.paint()
        text(ctx, "MORE THAN", W / 2, 420, 26, color=GOLD, alpha=end_a, spacing=12)
        text(ctx, "1,500", W / 2, 540, 130, color=CREAM, alpha=end_a, spacing=12, glow=0.4)
        text(ctx, "LIVES LOST", W / 2, 610, 32, color=GOLD, alpha=end_a, spacing=14)


def foam_at(ctx, t, m, wl, xa, xb, a=1.0):
    if a <= 0:
        return
    xs = []
    for x in range(int(xa), int(xb), 8):
        y1 = m.transform_point(x, -75)[1]
        y2 = m.transform_point(x, 40)[1]
        if min(y1, y2) <= wl <= max(y1, y2):
            # where does this vertical section cross the surface?
            k = (wl - y1) / (y2 - y1) if y2 != y1 else 0
            xs.append(m.transform_point(x, lerp(-75, 40, k))[0])
    if not xs:
        return
    x0, x1 = min(xs), max(xs)
    for i in range(int((x1 - x0) / 6) + 1):
        x = x0 + i * 6
        rgba(ctx, (0.85, 0.9, 0.95), a * 0.35 * (0.5 + 0.5 * math.sin(t * 6 + i)))
        ctx.save()
        ctx.translate(x, wl + 1)
        ctx.scale(1, 0.3)
        ctx.arc(0, 0, 8 + 6 * hash01(i), 0, 2 * math.pi)
        ctx.fill()
        ctx.restore()


# ===================================================================== 8. LEGACY
def scene_legacy(ctx, t, S):
    dur, c = S["dur"], S["cues"]
    t2 = c[2][0] - 0.6
    t3 = c[3][0] + 2.5
    a1 = 1 - ramp(t, t2 - 0.6, t2 + 0.4)
    a2 = ramp(t, t2 - 0.4, t2 + 0.6) * (1 - ramp(t, t3 - 0.8, t3 + 0.6))
    a3 = ramp(t, t3 - 0.4, t3 + 1.0)
    if a1 > 0:
        with group(ctx, a1):
            dawn(ctx, t, S)
    if a2 > 0:
        with group(ctx, a2):
            deep(ctx, t - t2, S)
    if a3 > 0:
        with group(ctx, a3):
            finale(ctx, t - t3)


def dawn(ctx, t, S):
    c = S["cues"]
    hy = 640
    ctx.save()
    camera(ctx, 960, 540 - t * 2, 1.0 + t * 0.006)
    draw_sky(ctx, hy, [(0, (0.06, 0.06, 0.15)), (0.5, (0.25, 0.19, 0.32)), (0.8, (0.62, 0.36, 0.34)),
                       (1, (0.9, 0.6, 0.4))])
    draw_stars(ctx, t, 0.25, hy - 300)
    draw_glow(ctx, 1250, hy, 600, (1.0, 0.6, 0.35), 0.28)
    g = cairo.RadialGradient(1250, hy + 10, 0, 1250, hy + 10, 46)
    g.add_color_stop_rgba(0, 1, 0.95, 0.8, 1)
    g.add_color_stop_rgba(1, 1, 0.8, 0.5, 0.9)
    ctx.set_source(g)
    ctx.arc(1250, hy + 10 - t * 1.5, 46, math.pi, 2 * math.pi)
    ctx.fill()
    draw_sea(ctx, t, hy, (0.42, 0.3, 0.32), (0.04, 0.05, 0.1), line_col=(1.0, 0.75, 0.55),
             line_alpha=0.18, glints=[(1250, (1.0, 0.8, 0.5), 300, 0.7)])
    draw_haze(ctx, hy, (1.0, 0.7, 0.5), 0.35)
    # Carpathia on the horizon
    with local(ctx, ship_world_matrix(420 + t * 3, hy + 6, 0.16)):
        draw_ship(ctx, t, night=0.8, lights=0.4, smoke=0.8, funnels=True)
    # ice floes
    for i in range(14):
        fx = (i * 173 + 60) % 2000 - 40
        fy = hy + 30 + (i * 37 % 300)
        sc = 0.12 + (fy - hy) / 600
        ctx.save()
        ctx.translate(fx, fy)
        ctx.scale(sc, sc * 0.3)
        rgba(ctx, (0.85, 0.72, 0.7), 0.55)
        ctx.move_to(-40, 0)
        for k in range(1, 8):
            ang = k / 8 * 2 * math.pi
            r = 40 + 20 * hash01(i, k)
            ctx.line_to(-r * math.cos(ang), r * math.sin(ang))
        ctx.close_path()
        ctx.fill()
        ctx.restore()
    for i, (bx, by) in enumerate(((700, hy + 70), (980, hy + 110))):
        rgba(ctx, (0.08, 0.05, 0.07))
        ctx.move_to(bx - 36, by)
        ctx.line_to(bx + 36, by)
        ctx.curve_to(bx + 28, by + 9, bx + 9, by + 10, bx, by + 10)
        ctx.curve_to(bx - 9, by + 10, bx - 28, by + 9, bx - 36, by)
        ctx.fill()
    ctx.restore()
    chapter_card(ctx, t, "CHAPTER  V", "AFTERMATH", end=c[0][1] + 0.4)
    ca = window(t, c[1][0], c[2][0], 0.8, 0.6)
    if ca > 0:
        rgba(ctx, (0, 0, 0), 0.45 * ca)
        ctx.paint()
        text(ctx, "SAFETY OF LIFE AT SEA  ·  1914", W / 2, 300, 28, color=GOLD, alpha=ca, spacing=8)
        items = ["LIFEBOATS FOR ALL", "24-HOUR RADIO WATCH", "INTERNATIONAL ICE PATROL"]
        for i, it in enumerate(items):
            a = ca * ramp(t, c[1][0] + 1.0 + i * 1.3, c[1][0] + 1.8 + i * 1.3)
            x = W / 2 + (i - 1) * 520
            y = 560
            rgba(ctx, (0.03, 0.04, 0.06), 0.7 * a)
            ctx.rectangle(x - 230, y - 170, 460, 300)
            ctx.fill()
            rgba(ctx, GOLD, 0.8 * a)
            ctx.set_line_width(1.2)
            ctx.rectangle(x - 230, y - 170, 460, 300)
            ctx.stroke()
            icon(ctx, i, x, y - 50, a, t)
            text(ctx, it, x, y + 90, 24, color=CREAM, alpha=a, spacing=4)


def icon(ctx, i, x, y, a, t):
    ctx.save()
    rgba(ctx, GOLD, a)
    ctx.set_line_width(3)
    if i == 0:
        for k in range(3):
            boat_icon(ctx, x - 60 + k * 60, y - 10 + (k % 2) * 30, 52, a, GOLD)
    elif i == 1:
        ctx.move_to(x, y + 50)
        ctx.line_to(x, y - 40)
        ctx.stroke()
        for k in range(3):
            r = 25 + k * 22 + (t * 30 % 22)
            rgba(ctx, GOLD, a * (1 - (r - 25) / 70))
            ctx.arc(x, y - 40, r, -math.pi * 0.8, -math.pi * 0.2)
            ctx.stroke()
    else:
        ctx.move_to(x - 70, y + 40)
        ctx.line_to(x - 30, y - 30)
        ctx.line_to(x - 5, y - 10)
        ctx.line_to(x + 20, y - 55)
        ctx.line_to(x + 70, y + 40)
        ctx.close_path()
        ctx.stroke()
        ctx.move_to(x - 90, y + 40)
        ctx.line_to(x + 90, y + 40)
        ctx.stroke()
    ctx.restore()


def draw_wreck(ctx, t, beam):
    ctx.save()
    with local(ctx, ship_world_matrix(1000, 790, 1.6, -3, pivot=(560, 0))):
        ctx.save()
        ctx.rectangle(560, -400, 600, 600)
        ctx.clip()
        draw_ship(ctx, t, night=0.4, lights=0, funnels=False, masts=False, wreck=1.0)
        # rusticles
        for i in range(60):
            x = 570 + i * 7
            ln = 4 + 10 * hash01(i, 5)
            rgba(ctx, (0.45, 0.22, 0.1), 0.8)
            ctx.rectangle(x, deck_y(x) + 4, 1.4, ln)
            ctx.fill()
        ctx.restore()
        # collapsed foremast
        rgba(ctx, (0.3, 0.18, 0.1))
        ctx.set_line_width(4)
        ctx.move_to(905, -75)
        ctx.line_to(700, -95)
        ctx.stroke()
        # railing
        ctx.set_line_width(0.8)
        rgba(ctx, (0.5, 0.3, 0.15), 0.9)
        for x in range(870, 1000, 6):
            ctx.move_to(x, deck_y(x))
            ctx.line_to(x, deck_y(x) - 6)
        ctx.move_to(870, deck_y(870) - 6)
        for x in range(870, 1000, 6):
            ctx.line_to(x, deck_y(x) - 6)
        ctx.stroke()
    ctx.restore()


def deep(ctx, t, S):
    D = 7.0
    depth = 3800 * ease_io(t / 5.0)
    k = depth / 3800
    top = lerpc((0.05, 0.3, 0.45), (0.0, 0.015, 0.03), k ** 0.5)
    bot = lerpc((0.02, 0.12, 0.22), (0.0, 0.0, 0.005), k ** 0.5)
    g = cairo.LinearGradient(0, 0, 0, H)
    g.add_color_stop_rgb(0, *top)
    g.add_color_stop_rgb(1, *bot)
    ctx.set_source(g)
    ctx.paint()
    if k < 0.6:
        light_rays(ctx, t, W / 2, -200, (0.6, 0.85, 1.0), 0.1 * (1 - k / 0.6), n=9, spread=1.0,
                   length=1600)
    # marine snow streaming upward as we descend
    for i in range(160):
        h1, h2, h3 = hash01(i, 61), hash01(i, 62), hash01(i, 63)
        x = h1 * W + math.sin(t * 0.5 + i) * 10
        y = (h2 * H - t * (60 + 260 * (1 - k)) * (0.5 + h3)) % H
        rgba(ctx, (0.75, 0.85, 0.9), 0.25 + 0.3 * h3)
        ctx.arc(x, y, 0.8 + 1.6 * h3, 0, 2 * math.pi)
        ctx.fill()
    wa = ramp(t, 3.8, 5.5)
    if wa > 0:
        # seabed
        with group(ctx, wa):
            g = cairo.LinearGradient(0, 760, 0, H)
            g.add_color_stop_rgb(0, 0.09, 0.09, 0.08)
            g.add_color_stop_rgb(1, 0.02, 0.02, 0.02)
            ctx.set_source(g)
            ctx.move_to(0, 800)
            ctx.curve_to(500, 770, 1200, 790, W, 780)
            ctx.line_to(W, H)
            ctx.line_to(0, H)
            ctx.close_path()
            ctx.fill()
            draw_wreck(ctx, t, 1)
            # sediment mound around the buried bow
            rgba(ctx, (0.07, 0.07, 0.065))
            ctx.move_to(1050, 860)
            ctx.curve_to(1300, 800, 1700, 790, 2000, 830)
            ctx.line_to(2000, H)
            ctx.line_to(1050, H)
            ctx.fill()
            # submersible lamp beam
            bx, by = 1850, 250
            ctx.save()
            ctx.set_operator(cairo.OPERATOR_ADD)
            light_rays(ctx, t, bx, by, (0.9, 0.95, 1.0), 0.12, n=4, spread=0.25, length=1100,
                       base_angle=math.radians(143))
            draw_glow(ctx, 1450, 650, 420, (0.7, 0.8, 0.85), 0.18)
            ctx.restore()
            draw_glow(ctx, bx, by, 40, (1, 1, 0.95), 0.9)
            rgba(ctx, (0.15, 0.15, 0.16))
            ctx.rectangle(bx - 10, by - 25, 70, 40)
            ctx.fill()
    ga = window(t, 0.3, D + 4, 0.6, 0.8)
    text(ctx, "DEPTH", 200, 300, 18, color=GOLD, alpha=ga, align="left", spacing=6)
    text(ctx, f"{int(depth):,} m", 200, 370, 60, color=CREAM, alpha=ga, align="left", spacing=4)
    text(ctx, "1 SEPTEMBER 1985  ·  WRECK DISCOVERED", 200, 420, 20, color=GOLD,
         alpha=ga * ramp(t, 4.8, 5.8), align="left", spacing=5)


def finale(ctx, t):
    g = cairo.LinearGradient(0, 0, 0, H)
    g.add_color_stop_rgb(0, 0.0, 0.005, 0.02)
    g.add_color_stop_rgb(1, 0.02, 0.035, 0.07)
    ctx.set_source(g)
    ctx.paint()
    draw_stars(ctx, t, 0.8, 1000, milky=0.6)
    draw_sea(ctx, t, 820, (0.03, 0.05, 0.09), (0.0, 0.0, 0.01), line_col=(0.5, 0.6, 0.75),
             line_alpha=0.1, speed=0.3, glints=[(W / 2, (0.8, 0.85, 1.0), 200, 0.3)])
    a = ramp(t, 0.4, 2.2)
    sp = lerp(60, 40, ease_out(t / 6))
    text(ctx, "TITANIC", W / 2, 470, 140, color=CREAM, alpha=a, spacing=sp, glow=0.7,
         glow_color=(0.7, 0.8, 1.0))
    text(ctx, "1912", W / 2, 545, 34, color=GOLD, alpha=ramp(t, 1.4, 3.0), spacing=20)
    text(ctx, "Never truly unsinkable.  Never forgotten.", W / 2, 640, 36, font=SERIF_BODY,
         italic=True, color=(0.82, 0.8, 0.75), alpha=ramp(t, 2.6, 4.2))
