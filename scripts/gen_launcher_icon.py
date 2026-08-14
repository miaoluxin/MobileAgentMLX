# -*- coding: utf-8 -*-
"""Generate MLX launcher icon foreground (adaptive icon) + previews.

Design: pure black background, thin white ring, bold uppercase MLX,
flat horizontal tagline "MAKE LEARN EXTRAORDINARY" below it.
Flat 2D, no shadow, no gradient.

Adaptive icon geometry: 108dp layer -> 432px canvas.
Guaranteed safe zone = center 66dp circle (radius 132px @432).
All artwork is kept inside the safe zone so any launcher mask shape
(circle / squircle / teardrop) shows the full ring + text.

Rendered at 4x supersample, LANCZOS downscale for crisp edges.

Usage: python scripts/gen_launcher_icon.py
Writes:
  app/src/main/res/drawable-nodpi/ic_launcher_foreground.png  (432x432 RGBA)
  icon_preview_square.png    (1024x1024 brand lockup, rounded square)
  icon_preview_launcher.png  (432, simulated circular launcher mask)
"""
import os

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES_PNG = os.path.join(ROOT, "app", "src", "main", "res", "drawable-nodpi",
                       "ic_launcher_foreground.png")

WHITE = (255, 255, 255, 255)
BLACK = (0, 0, 0, 255)

FONT_CANDIDATES = [
    r"C:\Windows\Fonts\arialbd.ttf",   # Arial Bold
    r"C:\Windows\Fonts\segoeuib.ttf",  # Segoe UI Bold
    r"C:\Windows\Fonts\calibrib.ttf",
    r"C:\Windows\Fonts\ariblk.ttf",
]

_FONT_PATH = None


def load_font(size):
    global _FONT_PATH
    if _FONT_PATH is None:
        for p in FONT_CANDIDATES:
            if os.path.exists(p):
                _FONT_PATH = p
                break
        else:
            raise SystemExit("[ERR] no bold font found in C:\\Windows\\Fonts")
    return ImageFont.truetype(_FONT_PATH, size)


def ring_layer(size_px, cx, cy, r, w, ss):
    """White ring as RGBA layer.

    Drawn in single-channel 8x supersample: PIL's ellipse bbox is integer,
    so at low factors the circle center wobbles ~1px around the circumference.
    At 8x the residual wobble is ~0.1px -- keeps the thin stroke uniform.
    """
    layer = Image.new("L", (size_px * ss, size_px * ss), 0)
    rr = r * ss
    ImageDraw.Draw(layer).ellipse(
        [cx * ss - rr, cy * ss - rr, cx * ss + rr, cy * ss + rr],
        outline=255, width=int(round(w * ss)))
    layer = layer.resize((size_px, size_px), Image.LANCZOS)
    out = Image.new("RGBA", (size_px, size_px), WHITE)
    out.putalpha(layer)
    return out


def text_width(font, text, tracking):
    w = sum(font.getlength(c) for c in text)
    if len(text) > 1:
        w += tracking * (len(text) - 1)
    return w


def fit_font(text, target_w, tracking, lo=6.0, hi=600.0):
    """Binary-search font size so rendered width (incl. tracking) ~= target_w."""
    while hi - lo > 0.05:
        mid = (lo + hi) / 2.0
        if text_width(load_font(int(round(mid))), text, tracking) <= target_w:
            lo = mid
        else:
            hi = mid
    return load_font(int(round(lo)))


def render_text_layer(size, text, font, tracking, cx, cy):
    """Render text as a white layer, glyph block exactly centered on (cx, cy).

    Two passes: draw once, measure bbox, redraw shifted so the *ink* block
    (not the em box) is centered. Tagline is drawn char-by-char for tracking;
    MLX is drawn whole to keep kerning.
    """
    def draw(dst, ox, oy):
        d = ImageDraw.Draw(dst)
        if tracking == 0:
            d.text((cx + ox, cy + oy), text, font=font, anchor="mm", fill=WHITE)
        else:
            x = cx + ox - text_width(font, text, tracking) / 2.0
            for c in text:
                d.text((x, cy + oy), c, font=font, anchor="ls", fill=WHITE)
                x += font.getlength(c) + tracking

    scratch = Image.new("RGBA", size, (0, 0, 0, 0))
    draw(scratch, 0, 0)
    b = scratch.getbbox()
    if not b:
        raise SystemExit("[ERR] empty text layer: %r" % text)
    dx = cx - (b[0] + b[2]) / 2.0
    dy = cy - (b[1] + b[3]) / 2.0
    out = Image.new("RGBA", size, (0, 0, 0, 0))
    draw(out, dx, dy)
    return out


def gen_foreground():
    """Adaptive icon foreground: 432x432 (108dp layer), transparent bg."""
    s = 4                       # supersample factor
    size = (432 * s, 432 * s)
    cx = cy = 216 * s

    img = Image.new("RGBA", size, (0, 0, 0, 0))

    # bold MLX wordmark
    mlx_font = fit_font("MLX", 224 * s, tracking=0)
    img.alpha_composite(render_text_layer(size, "MLX", mlx_font, 0, cx, 200 * s))

    # flat tagline, letterspaced, strictly horizontal
    tag = "MAKE LEARN EXTRAORDINARY"
    tag_font = fit_font(tag, 210 * s, tracking=1.6 * s)
    img.alpha_composite(render_text_layer(size, tag, tag_font, 1.6 * s, cx, 255 * s))

    out = img.resize((432, 432), Image.LANCZOS)
    # thin white ring, fully inside 66dp safe zone (radius 132px @432)
    out.alpha_composite(ring_layer(432, 216, 216, 125, 5, ss=8))
    out.save(RES_PNG)

    # verify: ink must stay inside the safe-zone circle
    alpha = out.getchannel("A")
    px = alpha.load()
    max_r = 0.0
    for y in range(432):
        for x in range(432):
            if px[x, y]:
                max_r = max(max_r, ((x - 216.0) ** 2 + (y - 216.0) ** 2) ** 0.5)
    print("[OK] foreground 432x432 written: %s (%d bytes)" %
          (RES_PNG, os.path.getsize(RES_PNG)))
    print("[OK] ink bbox %s, max radius %.1fpx (safe zone limit 132)" %
          (str(alpha.getbbox()), max_r))
    if max_r > 132.5:
        raise SystemExit("[ERR] artwork exceeds adaptive-icon safe zone")


def gen_previews():
    """Brand lockup (rounded square, literal spec) + circular-mask simulation."""
    # 1) rounded-square brand icon
    n = 1024
    img = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle([0, 0, n - 1, n - 1], radius=224, fill=BLACK)
    img.alpha_composite(ring_layer(n, 512, 512, 430, 14, ss=8))
    mlx_font = fit_font("MLX", 770, tracking=0)
    img.alpha_composite(render_text_layer((n, n), "MLX", mlx_font, 0, 512, 490))
    tag = "MAKE LEARN EXTRAORDINARY"
    tag_font = fit_font(tag, 725, tracking=5.5)
    img.alpha_composite(render_text_layer((n, n), tag, tag_font, 5.5, 512, 610))
    p1 = os.path.join(ROOT, "icon_preview_square.png")
    img.save(p1)
    print("[OK] preview square: %s" % p1)

    # 2) what a circular-mask launcher actually displays (72dp of 108dp layer)
    fg = Image.open(RES_PNG).convert("RGBA")
    base = Image.new("RGBA", (432, 432), BLACK)
    base.alpha_composite(fg)
    mask = Image.new("L", (432, 432), 0)
    ImageDraw.Draw(mask).ellipse([216 - 144, 216 - 144, 216 + 144, 216 + 144],
                                 fill=255)
    base.putalpha(mask)
    p2 = os.path.join(ROOT, "icon_preview_launcher.png")
    base.save(p2)
    print("[OK] preview launcher mask: %s" % p2)


if __name__ == "__main__":
    print("[..] font: %s" % (load_font(12).path if hasattr(load_font(12), "path") else "?"))
    gen_foreground()
    gen_previews()
    print("[DONE]")
