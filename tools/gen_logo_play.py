"""Logos de la versión Google Play ("Retro Player").

Genera, con la misma caja de palomitas que la versión familiar pero con un botón de "play"
en la etiqueta (sin texto, así no depende de fuentes):
  - app/src/play/res/drawable/ic_launcher_foreground.xml   icono adaptable (vector)
  - app/src/play/res/drawable-xhdpi/tv_banner_play.png      banner del lanzador de Android TV (640x360)
  - docs/play/icono-512.png                                 icono de la ficha de Play (512x512)
  - docs/play/grafico-destacado-1024x500.png                gráfico destacado de la ficha

Uso (desde la raíz del repo, en Windows):  python tools/gen_logo_play.py
Necesita Pillow. La letra es Georgia Negrita de Windows (C:/Windows/Fonts/georgiab.ttf).
"""
import os
from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONT_PATH = os.environ.get("LOGO_FONT", "C:/Windows/Fonts/georgiab.ttf")

TITLE = "RETRO PLAYER"
TAG = "TU REPRODUCTOR DE LISTAS"

AMBER = (0xE0, 0xA0, 0x4A); AMBER_DARK = (0xB0, 0x76, 0x28); CREAM = (0xF3, 0xED, 0xE4)
CREAM2 = (0xFF, 0xF6, 0xE2); SHADE = (0xF2, 0xC2, 0x7E); DARK = (0x17, 0x12, 0x0E)
BG_IN = (0x3A, 0x2C, 0x1F); BG_OUT = (0x0D, 0x0B, 0x09)
ICON_BG = (0x17, 0x12, 0x0E)   # mismo fondo oscuro que el icono familiar


def hexc(c):
    return "#FF%02X%02X%02X" % c


def popcorn_shapes(cx, top, w, h):
    """Formas de la caja de palomitas con botón de play: ('circle', x, y, r, color) / ('poly', pts, color)."""
    out = []
    tw = w; bw = w * 0.72
    y0 = top + h * 0.30; y1 = top + h
    xl0, xr0 = cx - tw / 2, cx + tw / 2; xl1, xr1 = cx - bw / 2, cx + bw / 2
    r = w * 0.135
    kernels = [(-0.38, 0.30), (0.38, 0.30), (-0.20, 0.12), (0.20, 0.12), (0.0, 0.20), (-0.30, -0.12),
               (0.30, -0.10), (-0.08, -0.22), (0.12, -0.18), (0.0, 0.02)]
    for i, (dx, dy) in enumerate(kernels):
        x = cx + dx * w; y = top + h * 0.17 + dy * h * 0.30
        rr = r * (1.0 if i % 3 else 1.12)
        out.append(("circle", x + rr * 0.18, y + rr * 0.25, rr, SHADE))
        out.append(("circle", x, y, rr, CREAM2))
    n = 5
    for i in range(n):
        a0, a1 = i / n, (i + 1) / n
        pts = [(xl0 + (xr0 - xl0) * a0, y0), (xl0 + (xr0 - xl0) * a1, y0),
               (xl1 + (xr1 - xl1) * a1, y1), (xl1 + (xr1 - xl1) * a0, y1)]
        out.append(("poly", pts, AMBER if i % 2 == 0 else CREAM))
    rim = h * 0.07
    out.append(("poly", [(xl0 - w * 0.03, y0), (xr0 + w * 0.03, y0), (xr0 + w * 0.01, y0 + rim),
                         (xl0 - w * 0.01, y0 + rim)], AMBER_DARK))
    # Etiqueta redonda oscura con un triángulo de "play"
    lr = w * 0.26; lcx = cx; lcy = y0 + (y1 - y0) * 0.52
    out.append(("circle", lcx, lcy, lr, DARK))
    t = lr * 1.05
    out.append(("poly", [(lcx - t * 0.38, lcy - t / 2), (lcx - t * 0.38, lcy + t / 2), (lcx + t * 0.52, lcy)], AMBER))
    return out


def f(v):
    return ("%.2f" % v).rstrip("0").rstrip(".")


def to_vector_paths(shapes):
    paths = []
    for s in shapes:
        if s[0] == "circle":
            _, x, y, r, c = s
            d = f"M{f(x - r)},{f(y)}a{f(r)},{f(r)} 0,1 0,{f(2 * r)},0a{f(r)},{f(r)} 0,1 0,-{f(2 * r)},0z"
        else:
            _, pts, c = s
            d = "M" + "L".join(f"{f(px)},{f(py)}" for px, py in pts) + "Z"
        paths.append((d, c))
    return paths


def draw_shapes(draw, shapes, scale=1.0, ox=0.0, oy=0.0):
    for s in shapes:
        if s[0] == "circle":
            _, x, y, r, c = s
            x, y, r = ox + x * scale, oy + y * scale, r * scale
            draw.ellipse([x - r, y - r, x + r, y + r], fill=c)
        else:
            _, pts, c = s
            draw.polygon([(ox + px * scale, oy + py * scale) for px, py in pts], fill=c)


def radial_bg(w, h, cx, cy, radius):
    img = Image.new("RGB", (w, h), BG_OUT)
    px = img.load()
    for yy in range(h):
        for xx in range(w):
            t = min(1.0, ((xx - cx) ** 2 + (yy - cy) ** 2) ** 0.5 / radius)
            px[xx, yy] = tuple(int(BG_IN[k] + (BG_OUT[k] - BG_IN[k]) * t) for k in range(3))
    return img


def fit_font(text, max_w, size, spacing):
    while size > 8:
        font = ImageFont.truetype(FONT_PATH, size)
        w = text_w(text, font, spacing)
        if w <= max_w:
            return font, w
        size -= 1
    return ImageFont.truetype(FONT_PATH, size), text_w(text, ImageFont.truetype(FONT_PATH, size), spacing)


def text_w(text, font, spacing):
    return sum(font.getlength(ch) for ch in text) + spacing * (len(text) - 1)


def draw_spaced(draw, x, y, text, font, spacing, fill):
    for ch in text:
        draw.text((x, y), ch, font=font, fill=fill)
        x += font.getlength(ch) + spacing


def banner(w, h):
    """Banner con la caja a la izquierda y el nombre a la derecha (proporción 16:9 o similar)."""
    s = h / 180.0
    img = radial_bg(w, h, 120 * s, 70 * s, 240 * s)
    d = ImageDraw.Draw(img)
    draw_shapes(d, popcorn_shapes(66, 26, 66, 124), scale=s)
    area_x = 124 * s; area_w = w - area_x - 14 * s
    font, tw = fit_font(TITLE, area_w, int(40 * s), 2.2 * s)
    x0 = area_x + (area_w - tw) / 2
    draw_spaced(d, x0, 62 * s, TITLE, font, 2.2 * s, CREAM)
    d.rectangle([x0, 110 * s, x0 + tw, 111.6 * s], fill=AMBER)
    tfont, tww = fit_font(TAG, tw, int(9.5 * s), 1.2 * s)
    draw_spaced(d, x0 + (tw - tww) / 2, 120 * s, TAG, tfont, 1.2 * s, SHADE)
    return img


def main():
    play_res = os.path.join(ROOT, "app", "src", "play", "res")
    docs = os.path.join(ROOT, "docs", "play")
    os.makedirs(os.path.join(play_res, "drawable"), exist_ok=True)
    os.makedirs(os.path.join(play_res, "drawable-xhdpi"), exist_ok=True)
    os.makedirs(docs, exist_ok=True)

    # 1) Icono adaptable (vector 108x108, zona segura: círculo de 66 en el centro)
    icon = popcorn_shapes(54, 20, 54, 68)
    lines = ['<?xml version="1.0" encoding="utf-8"?>',
             '<!-- Icono de Retro Player (Google Play): caja de palomitas con botón de play. Generado con tools/gen_logo_play.py -->',
             '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
             '    android:width="108dp" android:height="108dp"',
             '    android:viewportWidth="108" android:viewportHeight="108">']
    for dpath, c in to_vector_paths(icon):
        lines.append(f'    <path android:fillColor="{hexc(c)}" android:pathData="{dpath}" />')
    lines.append("</vector>")
    with open(os.path.join(play_res, "drawable", "ic_launcher_foreground.xml"), "w", encoding="utf-8") as fh:
        fh.write("\n".join(lines) + "\n")

    # 2) Banner de Android TV (320x180 dp → 640x360 px en xhdpi)
    banner(640, 360).save(os.path.join(play_res, "drawable-xhdpi", "tv_banner_play.png"), optimize=True)

    # 3) Icono de la ficha de Play: 512x512, fondo opaco (Play redondea las esquinas él solo)
    big = Image.new("RGB", (512, 512), ICON_BG)
    k = 512 / 108.0
    draw_shapes(ImageDraw.Draw(big), icon, scale=k)
    big.save(os.path.join(docs, "icono-512.png"), optimize=True)

    # 4) Gráfico destacado 1024x500
    banner(1024, 500).save(os.path.join(docs, "grafico-destacado-1024x500.png"), optimize=True)
    print("ok")


if __name__ == "__main__":
    main()
