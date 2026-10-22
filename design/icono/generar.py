#!/usr/bin/env python3
"""The launcher's icon, in the family of Ginebra's «G▶» (~/dev/personal/ginebra/diseno/ginebra/icono/).

Same tile (#141a26, rx 30), same faceted play (pink and violets, cut like a triforce), and on the left
a glyph in off-white instead of the G. The TV is called Hollywood, so the candidates are:

  hollywood-h.svg         «H▶»  the H, Nunito Sans Black, like the G of Ginebra
  hollywood-estrella.svg  «★▶»  a Walk of Fame star
  hollywood-claqueta.svg  «🎬▶» a clapperboard
  hollywood-h-estrella.svg «H★» the H, with a faceted star where the play would be
  (earlier round, for «Inicio»: inicio-i.svg «I▶», inicio-casa.svg «⌂▶»)

Plus banner-*.svg (320×180, the Android TV launcher banner): the tile, «Hollywood» and «LAUNCHER».

Usage: generar.py <NunitoSans[YTLC,opsz,wdth,wght].ttf>   (writes the SVGs next to this file)
PNGs with rsvg-convert (qlmanage loses transparency):
    rsvg-convert -w 512 -h 512 hollywood-h.svg -o hollywood-h-512.png
"""
import os
import sys

from fontTools.pens.boundsPen import BoundsPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

HERE = os.path.dirname(os.path.abspath(__file__))
BG, WHITE = "#141a26", "#f2f7ff"
TOP, TIP, BOTTOM, CENTRE, SUBTITLE = "#f472b6", "#a78bfa", "#7c3aed", "#c084fc", "#c084fc"

GLYPH_H, GAP, PLAY_H = 54, 7, 52


def glyph_path(font, char, x_left, cy, cap_height):
    """SVG path of `char` with its ink's left edge at x_left, vertically centred on cy by its ink."""
    glyphs = font.getGlyphSet()
    name = font.getBestCmap()[ord(char)]
    pen = BoundsPen(glyphs)
    glyphs[name].draw(pen)
    xmin, ymin, xmax, ymax = pen.bounds
    scale = cap_height / font["OS/2"].sCapHeight
    base = cy + (ymax + ymin) * scale / 2
    svg = SVGPathPen(glyphs)
    glyphs[name].draw(TransformPen(svg, (scale, 0, 0, -scale, x_left - xmin * scale, base)))
    return svg.getCommands(), (xmax - xmin) * scale


def text_path(font, text, x_left, baseline, cap_height):
    """SVG path of `text` starting at x_left (ink), on `baseline`."""
    glyphs = font.getGlyphSet()
    cmap = font.getBestCmap()
    scale = cap_height / font["OS/2"].sCapHeight
    names = [cmap[ord(c)] for c in text]
    first = BoundsPen(glyphs)
    glyphs[names[0]].draw(first)
    x = x_left - first.bounds[0] * scale
    parts = []
    for n in names:
        pen = SVGPathPen(glyphs)
        glyphs[n].draw(TransformPen(pen, (scale, 0, 0, -scale, x, baseline)))
        parts.append(pen.getCommands())
        x += glyphs[n].width * scale
    return " ".join(parts)


def house(x_left, cy, height):
    """A house the same height as the cap: pitched roof and a body, rounded like the play's corners."""
    width = height * 0.9
    r = height * 0.16
    top, eave, bottom = cy - height / 2 + r / 2, cy - height * 0.08, cy + height / 2 - r / 2
    left, right, mid = x_left + r / 2, x_left + width - r / 2, x_left + width / 2
    door_w, door_h = width * 0.26, height * 0.34
    body = f"M{mid:.2f} {top:.2f} L{right:.2f} {eave:.2f} L{right:.2f} {bottom:.2f} L{left:.2f} {bottom:.2f} L{left:.2f} {eave:.2f} Z"
    door = (f"M{mid - door_w / 2:.2f} {bottom + r:.2f} L{mid - door_w / 2:.2f} {bottom - door_h + door_w / 2:.2f} "
            f"A{door_w / 2:.2f} {door_w / 2:.2f} 0 0 1 {mid + door_w / 2:.2f} {bottom - door_h + door_w / 2:.2f} "
            f"L{mid + door_w / 2:.2f} {bottom + r:.2f} Z")
    shape = (f'<path d="{body}" fill="{WHITE}" stroke="{WHITE}" stroke-width="{r:.1f}" stroke-linejoin="round"/>'
             f'<path d="{door}" fill="{BG}"/>')
    return shape, width


def play(x_left, cy, height, mask_id):
    """The faceted play from Ginebra's icon: three corners and the centre, cut along the midpoints."""
    stroke = height * .16
    width = height * .82
    x0 = x_left + stroke / 2
    a = (x0, cy - height / 2 + stroke / 2)
    b = (x0 + width - stroke, cy)
    c = (x0, cy + height / 2 - stroke / 2)
    mid = lambda p, q: ((p[0] + q[0]) / 2, (p[1] + q[1]) / 2)
    ab, bc, ca = mid(a, b), mid(b, c), mid(c, a)
    cg = ((a[0] + b[0] + c[0]) / 3, (a[1] + b[1] + c[1]) / 3)
    out = lambda p: (p[0] + (p[0] - cg[0]) * .3, p[1] + (p[1] - cg[1]) * .3)
    path = lambda *ps: "M" + " L".join(f"{px:.2f} {py:.2f}" for px, py in ps) + " Z"
    mask = (f'<mask id="{mask_id}"><path d="{path(a, b, c)}" fill="#fff" stroke="#fff" '
            f'stroke-width="{stroke:.1f}" stroke-linejoin="round"/></mask>')
    pieces = (f'<g mask="url(#{mask_id})">'
              f'<path d="{path(out(a), out(ab), out(ca))}" fill="{TOP}"/>'
              f'<path d="{path(out(b), out(bc), out(ab))}" fill="{TIP}"/>'
              f'<path d="{path(out(c), out(ca), out(bc))}" fill="{BOTTOM}"/>'
              f'<path d="{path(ab, bc, ca)}" fill="{CENTRE}"/>'
              f'<path d="{path(ab, bc, ca)}" fill="none" stroke="{BG}" stroke-width="1.8"/></g>')
    return mask, pieces, width


def star_points(cx, cy, r_out, r_in, n=5):
    import math
    pts = []
    for i in range(n * 2):
        r = r_out if i % 2 == 0 else r_in
        ang = -math.pi / 2 + i * math.pi / n
        pts.append((cx + r * math.cos(ang), cy + r * math.sin(ang)))
    return pts


def star(x_left, cy, height, fill=WHITE):
    """A five-point star, a Walk of Fame star, rounded at the tips like the play."""
    r = height * 0.10
    r_out = (height - r) / 1.9      # a star's height is about 1.9 × its outer radius
    width = 2 * r_out * 0.951 + r
    cx = x_left + width / 2
    pts = star_points(cx, cy + r_out * 0.1, r_out, r_out * 0.45)
    d = "M" + " L".join(f"{x:.2f} {y:.2f}" for x, y in pts) + " Z"
    return f'<path d="{d}" fill="{fill}" stroke="{fill}" stroke-width="{r:.1f}" stroke-linejoin="round"/>', width


def faceted_star(x_left, cy, height, mask_id):
    """The star again, cut into five tips around a pentagon, in the play's pink and violets."""
    import math
    r = height * 0.10
    r_out = (height - r) / 1.9
    width = 2 * r_out * 0.951 + r
    cx, c_y = x_left + width / 2, cy + r_out * 0.1
    pts = star_points(cx, c_y, r_out, r_out * 0.45)
    path = lambda *ps: "M" + " L".join(f"{x:.2f} {y:.2f}" for x, y in ps) + " Z"
    mask = (f'<mask id="{mask_id}"><path d="{path(*pts)}" fill="#fff" stroke="#fff" '
            f'stroke-width="{r:.1f}" stroke-linejoin="round"/></mask>')
    out = lambda q: (q[0] + (q[0] - cx) * .35, q[1] + (q[1] - c_y) * .35)
    colours = [TOP, TIP, BOTTOM, BOTTOM, TIP]
    inner = pts[1::2]
    tips = "".join(
        f'<path d="{path(out(pts[2 * i]), out(inner[i]), inner[i - 1] if i else inner[-1])}" fill="{colours[i]}"/>'
        .replace(f'{path(out(pts[2 * i]), out(inner[i]), inner[i - 1] if i else inner[-1])}',
                 path(out(inner[i - 1]), out(pts[2 * i]), out(inner[i])))
        for i in range(5)
    )
    centre = path(*inner)
    body = (f'<g mask="url(#{mask_id})">{tips}<path d="{centre}" fill="{CENTRE}"/>'
            f'<path d="{centre}" fill="none" stroke="{BG}" stroke-width="1.8"/></g>')
    return mask, body, width


def clapperboard(x_left, cy, height):
    """A clapperboard: the slate, and the clapper on top tilted open, with stripes."""
    width = height * 0.98
    r = height * 0.08
    top_h = height * 0.24
    gap = height * 0.07
    body_top = cy - height / 2 + top_h + gap
    bottom = cy + height / 2
    left, right = x_left, x_left + width
    slate = (f'<rect x="{left:.2f}" y="{body_top:.2f}" width="{width:.2f}" height="{bottom - body_top:.2f}" '
             f'rx="{r:.2f}" fill="{WHITE}"/>')
    # the clapper, hinged on the left and opened a few degrees
    stripes = ""
    n = 4
    step = width / n
    for i in range(n):
        x0 = left + i * step + step * 0.45
        stripes += (f'<path d="M{x0:.2f} {cy - height / 2:.2f} L{x0 + step * 0.42:.2f} {cy - height / 2:.2f} '
                    f'L{x0 + step * 0.42 - top_h * 0.6:.2f} {cy - height / 2 + top_h:.2f} '
                    f'L{x0 - top_h * 0.6:.2f} {cy - height / 2 + top_h:.2f} Z" fill="{BG}"/>')
    clapper = (f'<g transform="rotate(-10 {left:.2f} {cy - height / 2 + top_h:.2f})">'
               f'<rect x="{left:.2f}" y="{cy - height / 2:.2f}" width="{width:.2f}" height="{top_h:.2f}" rx="{r:.2f}" fill="{WHITE}"/>'
               f'{stripes}</g>')
    # stripes on the slate's top band too, so it reads as a clapperboard at 16 px
    band = ""
    for i in range(n):
        x0 = left + i * step + step * 0.45
        band += (f'<path d="M{x0:.2f} {body_top:.2f} L{x0 + step * 0.42:.2f} {body_top:.2f} '
                 f'L{x0 + step * 0.42 - top_h * 0.6:.2f} {body_top + top_h:.2f} '
                 f'L{x0 - top_h * 0.6:.2f} {body_top + top_h:.2f} Z" fill="{BG}"/>')
    clip_id = f"slate{int(x_left * 100)}"
    return (f'<defs><clipPath id="{clip_id}"><rect x="{left:.2f}" y="{body_top:.2f}" width="{width:.2f}" '
            f'height="{bottom - body_top:.2f}" rx="{r:.2f}"/></clipPath></defs>'
            f'{slate}<g clip-path="url(#{clip_id})">{band}</g>{clapper}'), width


def mark(font, kind, ox, oy, mask_id):
    """Glyph + play (or star) centred in a 128×128 box at (ox, oy). Returns (defs, body)."""
    cy = oy + 64
    letter = {"i": "I", "h": "H", "h-estrella": "H"}.get(kind)
    if letter:
        _, glyph_w = glyph_path(font, letter, 0, cy, GLYPH_H)
    elif kind == "casa":
        glyph_w = GLYPH_H * 0.9
    elif kind == "estrella":
        glyph_w = star(0, cy, GLYPH_H + 4)[1]
    else:
        glyph_w = clapperboard(0, cy, GLYPH_H)[1]
    right_w = faceted_star(0, cy, PLAY_H + 6, "x")[2] if kind == "h-estrella" else PLAY_H * .82
    x = ox + (128 - (glyph_w + GAP + right_w)) / 2
    if letter:
        d, _ = glyph_path(font, letter, x, cy, GLYPH_H)
        glyph = f'<path d="{d}" fill="{WHITE}"/>'
    elif kind == "casa":
        glyph, _ = house(x, cy, GLYPH_H)
    elif kind == "estrella":
        glyph, _ = star(x, cy, GLYPH_H + 4)
    else:
        glyph, _ = clapperboard(x, cy, GLYPH_H)
    if kind == "h-estrella":
        mask, pieces, _ = faceted_star(x + glyph_w + GAP, cy, PLAY_H + 6, mask_id)
    else:
        mask, pieces, _ = play(x + glyph_w + GAP, cy, PLAY_H, mask_id)
    return mask, glyph + pieces


def icon(font, kind, label):
    defs, body = mark(font, kind, 0, 0, "play")
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 128 128" role="img" aria-label="{label}">'
            f'<defs>{defs}</defs><rect width="128" height="128" rx="30" fill="{BG}"/>{body}</svg>\n')


def banner(font, light, kind, name_text):
    """320×180 TV banner: the tile on the left, the name and «LAUNCHER» to its right (text as paths)."""
    tile, ox = 92, 26
    oy = (180 - tile) / 2
    s = tile / 128
    defs, body = mark(font, kind, 0, 0, "play")
    cap = 21 if len(name_text) > 7 else 34
    name = text_path(font, name_text, ox + tile + 16, 96, cap)
    subtitle = text_path(light, "LAUNCHER", ox + tile + 17, 118, 9)
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 320 180" role="img" aria-label="{name_text}">'
            f'<defs>{defs}</defs><rect width="320" height="180" fill="{BG}"/>'
            f'<g transform="translate({ox} {oy}) scale({s:.4f})"><rect width="128" height="128" rx="30" fill="#1c2434"/>{body}</g>'
            f'<path d="{name}" fill="{WHITE}"/><path d="{subtitle}" fill="{SUBTITLE}"/></svg>\n')


def main():
    font = TTFont(sys.argv[1])
    instantiateVariableFont(font, {"wght": 1000, "wdth": 100, "opsz": 12, "YTLC": 500}, inplace=True)
    light = TTFont(sys.argv[1])
    instantiateVariableFont(light, {"wght": 700, "wdth": 100, "opsz": 12, "YTLC": 500}, inplace=True)
    sets = [("inicio", "Inicio", ["i", "casa"]), ("hollywood", "Hollywood", ["h", "estrella", "claqueta", "h-estrella"])]
    for prefix, name, kinds in sets:
        for kind in kinds:
            with open(os.path.join(HERE, f"{prefix}-{kind}.svg"), "w") as f:
                f.write(icon(font, kind, name))
            with open(os.path.join(HERE, f"banner-{prefix}-{kind}.svg"), "w") as f:
                f.write(banner(font, light, kind, name))
    print("listo")


if __name__ == "__main__":
    main()
