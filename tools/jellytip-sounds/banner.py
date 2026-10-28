#!/usr/bin/env python3
"""Render the Android TV banner (320x180 dp) for Jellytip: logo + "jellytip" wordmark on the night background.

    python3 tools/jellytip-sounds/banner.py

Writes app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_banner.png using the OFL fonts bundled in
res/font (Bungee Shade, VT323). Geometry mirrors design/splash.html logoSVG (viewBox 100x170).
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / "app/src/main/res"
NIGHT = (0x0E, 0x0B, 0x1C)
PURPLE = (0x2A, 0x1A, 0x48)
VANILLA = (0xF7, 0xE8, 0xC4)
CHOC = (0x5B, 0x34, 0x21)
JELLY = (0xE2, 0x26, 0x4D)
STICK = (0xD9, 0xA8, 0x6C)
NEON = (0x2B, 0xE3, 0xD2)


def arc(cx, cy, r, a0, a1, n=48):
    import math
    pts = []
    for i in range(n + 1):
        a = math.radians(a0 + (a1 - a0) * i / n)
        pts.append((cx + r * math.cos(a), cy - r * math.sin(a)))
    return pts


def quad(p0, c, p1, n=12):
    return [((1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * c[0] + t * t * p1[0],
             (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * c[1] + t * t * p1[1]) for t in [i / n for i in range(1, n + 1)]]


def logo(d, ox, oy, s):
    def P(pts):
        return [(ox + x * s, oy + y * s) for x, y in pts]

    d.rounded_rectangle([ox + 43 * s, oy + 112 * s, ox + 57 * s, oy + 166 * s], radius=7 * s, fill=STICK)
    d.rounded_rectangle([ox + 18 * s, oy + 18 * s, ox + 82 * s, oy + 122 * s], radius=30 * s, fill=VANILLA)
    choc = arc(50, 48, 32, 180, 0) + [(82, 70)]
    choc += quad((82, 70), (74, 78), (66, 70)) + quad((66, 70), (58, 62), (50, 70))
    choc += quad((50, 70), (42, 78), (34, 70)) + quad((34, 70), (26, 62), (18, 70))
    d.polygon(P(choc), fill=CHOC)
    jelly = arc(50, 36, 23, 180, 0)
    jelly += quad((73, 36), (67, 42), (61, 36)) + quad((61, 36), (56, 31), (50, 36))
    jelly += quad((50, 36), (44, 42), (39, 36)) + quad((39, 36), (33, 31), (27, 36))
    d.polygon(P(jelly), fill=JELLY)
    d.ellipse([ox + 30.8 * s, oy + 37.4 * s, ox + 37.2 * s, oy + 46.6 * s], fill=JELLY)
    d.polygon(P([(44, 84), (62, 96), (44, 108)]), fill=CHOC)


def render(w=1280, h=720):
    img = Image.new("RGB", (w, h), NIGHT)
    # soft purple glow, like the logo frame background
    glow = Image.new("RGB", (w, h), NIGHT)
    gd = ImageDraw.Draw(glow)
    for i in range(40, 0, -1):
        f = i / 40
        c = tuple(int(NIGHT[k] + (PURPLE[k] - NIGHT[k]) * (1 - f)) for k in range(3))
        gd.ellipse([w * (0.5 - 0.7 * f), h * (0.4 - 0.7 * f), w * (0.5 + 0.7 * f), h * (0.4 + 0.7 * f)], fill=c)
    img.paste(glow)
    d = ImageDraw.Draw(img)

    ls = h * 0.74 / 170  # logo height = 74% of banner
    logo(d, w * 0.07, h * 0.13 - 0.0, ls)

    shade = ImageFont.truetype(str(RES / "font/bungee_shade_regular.ttf"), int(h * 0.20))
    text = "jellytip"
    tw = d.textlength(text, font=shade)
    maxw = w * 0.60
    if tw > maxw:
        shade = ImageFont.truetype(str(RES / "font/bungee_shade_regular.ttf"), int(h * 0.20 * maxw / tw))
        tw = d.textlength(text, font=shade)
    x0 = w * 0.34
    y0 = h * 0.30
    off = h * 0.012
    d.text((x0 + 2 * off, y0 + 2 * off), text, font=shade, fill=CHOC)
    d.text((x0 + off, y0 + off), text, font=shade, fill=JELLY)
    d.text((x0, y0), text, font=shade, fill=VANILLA)

    tag = ImageFont.truetype(str(RES / "font/vt323_regular.ttf"), int(h * 0.075))
    d.text((x0 + off, h * 0.60), "WORLD FAMOUS IN YOUR LOUNGE", font=tag, fill=NEON)

    return img


def main():
    out = render()
    for dens, f in (("mdpi", 1), ("hdpi", 1.5), ("xhdpi", 2), ("xxhdpi", 3), ("xxxhdpi", 4)):
        size = (int(320 * f), int(180 * f))
        d = RES / f"mipmap-{dens}"
        d.mkdir(exist_ok=True)
        out.resize(size, Image.LANCZOS).save(d / "ic_banner.png", optimize=True)
        print(d / "ic_banner.png", size)


if __name__ == "__main__":
    main()
