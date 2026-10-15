"""Genera el icono (palomitas) y el banner de TV como VectorDrawables de Android."""
import sys
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen

FONT = TTFont('/usr/share/fonts/truetype/dejavu/DejaVuSerif-Bold.ttf')
GS = FONT.getGlyphSet(); CMAP = FONT.getBestCmap(); UPM = FONT['head'].unitsPerEm
HMTX = FONT['hmtx']

AMBER = '#FFE0A04A'; AMBER_DARK = '#FFB07628'; CREAM = '#FFF3EDE4'; CREAM2 = '#FFFFF6E2'
SHADE = '#FFF2C27E'; DARK = '#FF17120E'; INK = '#FF0D0B09'

def text_path(s, x, baseline, size, spacing=0.0):
    """Texto -> pathData (escala size = alto del em)."""
    k = size / UPM; out = []
    for ch in s:
        g = CMAP.get(ord(ch))
        if ch == ' ':
            x += size * 0.32; continue
        pen = SVGPathPen(GS)
        tp = TransformPen(pen, (k, 0, 0, -k, x, baseline))
        GS[g].draw(tp)
        out.append(pen.getCommands())
        x += HMTX[g][0] * k + spacing * size
    return ''.join(out), x

def text_width(s, size, spacing=0.0):
    _, end = text_path(s, 0, 0, size, spacing); return end - spacing * size

def f(v): return f'{v:.2f}'.rstrip('0').rstrip('.')

def popcorn(cx, top, w, h, label_text=False):
    """Caja de palomitas: devuelve lista de (pathData, color)."""
    out = []
    tw = w; bw = w * 0.72                       # ancho arriba / abajo
    y0 = top + h * 0.30; y1 = top + h        # caja
    xl0, xr0 = cx - tw/2, cx + tw/2; xl1, xr1 = cx - bw/2, cx + bw/2
    # Palomitas (detrás del borde superior de la caja)
    r = w * 0.135
    kernels = [(-0.38, 0.30), (0.38, 0.30), (-0.20, 0.12), (0.20, 0.12), (0.0, 0.20), (-0.30, -0.12), (0.30, -0.10), (-0.08, -0.22), (0.12, -0.18), (0.0, 0.02)]
    for i, (dx, dy) in enumerate(kernels):
        x = cx + dx * w; y = top + h * 0.17 + dy * h * 0.30
        rr = r * (1.0 if i % 3 else 1.12)
        out.append((f'M{f(x+rr*0.18)},{f(y+rr*0.25)}m-{f(rr)},0a{f(rr)},{f(rr)} 0,1 0,{f(2*rr)},0a{f(rr)},{f(rr)} 0,1 0,-{f(2*rr)},0z', SHADE))
        out.append((f'M{f(x)},{f(y)}m-{f(rr)},0a{f(rr)},{f(rr)} 0,1 0,{f(2*rr)},0a{f(rr)},{f(rr)} 0,1 0,-{f(2*rr)},0z', CREAM2))
    # Rayas verticales
    n = 5
    for i in range(n):
        a0, a1 = i / n, (i + 1) / n
        p = [(xl0 + (xr0 - xl0) * a0, y0), (xl0 + (xr0 - xl0) * a1, y0),
             (xl1 + (xr1 - xl1) * a1, y1), (xl1 + (xr1 - xl1) * a0, y1)]
        d = 'M' + 'L'.join(f'{f(px)},{f(py)}' for px, py in p) + 'Z'
        out.append((d, AMBER if i % 2 == 0 else CREAM))
    # Borde superior de la caja
    rim = h * 0.07
    out.append((f'M{f(xl0 - w*0.03)},{f(y0)}L{f(xr0 + w*0.03)},{f(y0)}L{f(xr0 + w*0.01)},{f(y0 + rim)}L{f(xl0 - w*0.01)},{f(y0 + rim)}Z', AMBER_DARK))
    # Etiqueta con "play"
    if label_text:
        lw, lh = w * 0.82, h * 0.36; ly = y0 + (y1 - y0) * 0.26
    else:
        lw, lh = w * 0.50, h * 0.26; ly = y0 + (y1 - y0) * 0.42
    lx = cx - lw / 2; rad = lh * 0.22
    out.append((f'M{f(lx+rad)},{f(ly)}h{f(lw-2*rad)}q{f(rad)},0 {f(rad)},{f(rad)}v{f(lh-2*rad)}q0,{f(rad)} -{f(rad)},{f(rad)}h-{f(lw-2*rad)}q-{f(rad)},0 -{f(rad)},-{f(rad)}v-{f(lh-2*rad)}q0,-{f(rad)} {f(rad)},-{f(rad)}z', DARK))
    if label_text:
        # "RETRO" e "IPTV" en dos líneas dentro de la etiqueta
        for word, color, frac in (("RETRO", CREAM, 0.46), ("IPTV", AMBER, 0.88)):
            size = lh * 0.40
            tw = text_width(word, size, 0.02)
            maxw = lw * 0.84
            if tw > maxw: size *= maxw / tw; tw = maxw
            d, _ = text_path(word, cx - tw / 2, ly + lh * frac, size, 0.02)
            out.append((d, color))
        return out
    t = lh * 0.55; tx = cx - t * 0.35; ty = ly + lh / 2
    out.append((f'M{f(tx)},{f(ty - t/2)}v{f(t)}l{f(t*0.85)},-{f(t/2)}z', AMBER))
    return out

def vector(w, h, vw, vh, paths, comment, bg=None):
    s = ['<?xml version="1.0" encoding="utf-8"?>', f'<!-- {comment} -->',
         '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
         '    xmlns:aapt="http://schemas.android.com/aapt"',
         f'    android:width="{w}dp" android:height="{h}dp"',
         f'    android:viewportWidth="{vw}" android:viewportHeight="{vh}">']
    if bg: s.append(bg)
    for d, c in paths:
        s.append(f'    <path android:fillColor="{c}" android:pathData="{d}" />')
    s.append('</vector>'); return '\n'.join(s) + '\n'

def main(outdir, title):
    # Icono adaptable 108x108 (zona segura: círculo de 66 en el centro)
    icon = popcorn(54, 20, 54, 68, label_text=True)
    open(f'{outdir}/ic_launcher_foreground.xml', 'w').write(
        vector(108, 108, 108, 108, icon, 'Icono: caja de palomitas en ámbar y crema'))
    # Banner 320x180 para el lanzador de Android TV
    bg = '''    <path android:pathData="M0,0h320v180h-320z">
        <aapt:attr name="android:fillColor">
            <gradient android:type="radial" android:centerX="120" android:centerY="70" android:gradientRadius="240">
                <item android:offset="0" android:color="#FF3A2C1F" />
                <item android:offset="1" android:color="#FF0D0B09" />
            </gradient>
        </aapt:attr>
    </path>'''
    paths = popcorn(66, 26, 66, 124)
    size = 44; sp = 0.06
    tw = text_width(title, size, sp)
    x0 = 124 + (176 - tw) / 2 if tw < 176 else 122
    if tw > 176: size = size * 176 / tw; tw = 176; x0 = 122
    d, _ = text_path(title, x0, 98, size, sp); paths.append((d, CREAM))
    paths.append((f'M{f(x0)},110h{f(tw)}v1.6h-{f(tw)}z', AMBER))
    tag = 'TU SALA DE CINE EN CASA'; ts = 9.2; tsp = 0.14
    tww = text_width(tag, ts, tsp)
    d, _ = text_path(tag, x0 + (tw - tww) / 2, 128, ts, tsp); paths.append((d, SHADE))
    open(f'{outdir}/tv_banner.xml', 'w').write(
        vector(320, 180, 320, 180, paths, 'Banner 320x180 del lanzador de Android TV', bg))

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
