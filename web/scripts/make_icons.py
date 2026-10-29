"""Render the PWA icons (public/icons/) from the Jelly Tip art: same shapes as design/splash.html logoSVG and the
intro end card (jellytip-intro/make_endcard.py icon()). Needs Pillow. Run: python3 scripts/make_icons.py"""
import math
import os

from PIL import Image, ImageDraw

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "public", "icons")
BG = (10, 10, 10)


def quad(p0, c, p1, n=14):
    return [((1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * c[0] + t * t * p1[0],
             (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * c[1] + t * t * p1[1]) for t in [i / n for i in range(1, n + 1)]]


def arc_top(cx, cy, r, n=24):
    return [(cx + r * math.cos(math.pi + math.pi * i / n), cy + r * math.sin(math.pi + math.pi * i / n)) for i in range(n + 1)]


def jellytip(height, ss=4):
    """The ice block on transparent, bite marks cut out (viewBox 100x170)."""
    s = height * ss / 170
    w, h = int(100 * s), int(170 * s)
    im = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    P = lambda pts: [(x * s, y * s) for x, y in pts]
    d.rounded_rectangle([43 * s, 112 * s, 57 * s, 166 * s], 7 * s, fill="#d9a86c")
    d.rounded_rectangle([18 * s, 18 * s, 82 * s, 122 * s], 30 * s, fill="#f7e8c4")
    choc = [(18, 70), (18, 48)] + arc_top(50, 48, 32) + [(82, 70)]
    for c, e in [((74, 78), (66, 70)), ((58, 62), (50, 70)), ((42, 78), (34, 70)), ((26, 62), (18, 70))]:
        choc += quad(choc[-1], c, e)
    d.polygon(P(choc), fill="#5b3421")
    jelly = arc_top(50, 36, 23)
    for c, e in [((67, 42), (61, 36)), ((56, 31), (50, 36)), ((44, 42), (39, 36)), ((33, 31), (27, 36))]:
        jelly += quad(jelly[-1], c, e)
    d.polygon(P(jelly), fill="#e2264d")
    d.ellipse([(34 - 3.2) * s, (42 - 4.6) * s, (34 + 3.2) * s, (42 + 4.6) * s], fill="#e2264d")
    d.polygon(P([(44, 84), (62, 96), (44, 108)]), fill="#5b3421")
    d.ellipse([34 * s, 23 * s, 46 * s, 29 * s], fill="#ee5f7e")
    bite = Image.new("L", im.size, 0)
    bd = ImageDraw.Draw(bite)
    for cx, cy, r in [(80, 30, 11), (70, 18, 10)]:
        bd.ellipse([(cx - r) * s, (cy - r) * s, (cx + r) * s, (cy + r) * s], fill=255)
    im.putalpha(Image.composite(Image.new("L", im.size, 0), im.getchannel("A"), bite))
    return im.resize((w // ss, h // ss), Image.LANCZOS)


def tile(size, art_frac, radius_frac=0.0):
    """Dark square (optionally rounded) with the ice block centred at art_frac of the height."""
    im = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    ImageDraw.Draw(im).rounded_rectangle([0, 0, size - 1, size - 1], int(size * radius_frac), fill=BG + (255,))
    art = jellytip(int(size * art_frac))
    im.alpha_composite(art, ((size - art.width) // 2, (size - art.height) // 2))
    return im


def main():
    os.makedirs(OUT, exist_ok=True)
    for n in (192, 512):
        tile(n, 0.78, 0.22).save(os.path.join(OUT, f"icon-{n}.png"))
        # Maskable: full-bleed background, art inside the central 80% safe circle
        tile(n, 0.58).convert("RGB").save(os.path.join(OUT, f"maskable-{n}.png"))
    tile(180, 0.72).convert("RGB").save(os.path.join(OUT, "apple-touch-icon.png"))  # iOS rounds it itself


if __name__ == "__main__":
    main()
