"""One-off: renders assets/endcard.png (icon + wordmark + slogan on #0A0A0A). Needs Pillow. Run: python3 make_endcard.py"""
import math
import os
from PIL import Image, ImageDraw, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
BG = (10, 10, 10)
W, H = 1920, 1080


def quad(p0, c, p1, n=14):
    return [((1 - t) ** 2 * p0[0] + 2 * (1 - t) * t * c[0] + t * t * p1[0],
             (1 - t) ** 2 * p0[1] + 2 * (1 - t) * t * c[1] + t * t * p1[1]) for t in [i / n for i in range(1, n + 1)]]


def arc_top(cx, cy, r, n=24):
    return [(cx + r * math.cos(math.pi + math.pi * i / n), cy + r * math.sin(math.pi + math.pi * i / n)) for i in range(n + 1)]


def icon(height, ss=4):
    """Jelly Tip from design/splash.html logoSVG (viewBox 100x170), bite marks cut to the card background."""
    s = height * ss / 170
    w, h = int(100 * s), int(170 * s)
    im = Image.new("RGB", (w, h), BG)
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
    d.ellipse([34 * s, 23 * s, 46 * s, 29 * s], fill="#ee5f7e")  # highlight pre-blended at 70% over jelly
    for cx, cy, r in [(80, 30, 11), (70, 18, 10)]:
        d.ellipse([(cx - r) * s, (cy - r) * s, (cx + r) * s, (cy + r) * s], fill=BG)
    return im.resize((w // ss, h // ss), Image.LANCZOS)


def text_tracked(draw, cx, y, text, font, fill, tracking):
    widths = [draw.textlength(ch, font=font) for ch in text]
    total = sum(widths) + tracking * (len(text) - 1)
    x = cx - total / 2
    for ch, wd in zip(text, widths):
        draw.text((x, y), ch, font=font, fill=fill)
        x += wd + tracking


def main():
    card = Image.new("RGB", (W, H), BG)
    ic = icon(250)
    card.paste(ic, ((W - ic.width) // 2, 292))
    d = ImageDraw.Draw(card)
    word = ImageFont.truetype(os.path.join(HERE, "assets/InstrumentSans.ttf"), 92)
    word.set_variation_by_axes([100, 400])
    text_tracked(d, W / 2, 580, "jellytip", word, (240, 236, 228), 6)
    sl = ImageFont.truetype(os.path.join(HERE, "assets/InstrumentSans.ttf"), 30)
    sl.set_variation_by_axes([100, 400])
    text_tracked(d, W / 2, 712, "World famous in your lounge", sl, (140, 136, 130), 3)
    card.save(os.path.join(HERE, "assets/endcard.png"), optimize=True)


if __name__ == "__main__":
    main()
