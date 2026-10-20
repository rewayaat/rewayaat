"""Render the book covers: six house layouts over the shared leather.

Usage: python3 covers.py [slug ...]   (needs google-chrome; fonts come from Google Fonts)
Then run export.py to copy them into the site.

Real editions of these books came from different publishers, each with its
own cover: so the leather, wear and gilding stay shared, while the border,
title panel, corners and foot ornament vary by layout.
"""
import random
import subprocess
import sys
from pathlib import Path

from PIL import Image

import leather
from leather import BOOKS, CLOTHS, H, W, f

HERE = Path(__file__).parent
OUT = HERE / "build"

LAYOUT = {
    "al-kafi": "arch", "man-la-yahduruh-al-faqih": "cartouche", "nahj-al-balagha": "plain",
    "al-amali": "medallion", "al-khisal": "notched", "kitab-al-ghayba": "headband",
    "thawab-al-amal": "medallion", "uyun-akhbar-al-rida": "arch", "maani-al-akhbar": "cartouche",
    "kamil-al-ziyarat": "headband", "al-tawhid": "notched", "mujam-al-ahadith-al-mutabara": "arch",
    "kitab-al-zuhd": "cartouche", "kitab-al-duafa": "medallion", "kitab-al-mumin": "notched",
    "sifat-al-shia": "headband", "risalat-al-huquq": "plain", "fadail-al-shia": "plain",
    "ghurar-al-hikam": "cartouche", "mizan-al-hikma": "notched", "mishkat-al-anwar": "headband",
}

# extra blind border patterns (the stars are leather.py's #band)
PATTERNS = """
    <pattern id="rings" width="14" height="14" patternUnits="userSpaceOnUse" x="24" y="24">
      <g fill="none" stroke="#000" stroke-width="1.2"><circle cx="7" cy="7" r="5.2"/><circle cx="0" cy="0" r="5.2"/><circle cx="14" cy="0" r="5.2"/><circle cx="0" cy="14" r="5.2"/><circle cx="14" cy="14" r="5.2"/></g>
    </pattern>
    <pattern id="chevrons" width="12" height="20" patternUnits="userSpaceOnUse" x="24" y="24">
      <path d="M0 6 L6 0 L12 6 M0 13 L6 7 L12 13 M0 20 L6 14 L12 20" fill="none" stroke="#000" stroke-width="1.3"/>
    </pattern>
    <pattern id="dots" width="7" height="7" patternUnits="userSpaceOnUse" x="24" y="24">
      <circle cx="3.5" cy="3.5" r="1.5" fill="#000"/>
    </pattern>
"""

FAN = ("<path d='M0 0 H40 A40 40 0 0 1 0 40 Z' fill='none' stroke-width='1.1'/>"
       "<path d='M0 0 H33 A33 33 0 0 1 0 33 Z' fill='none' stroke-width='.8'/>"
       + "".join(f"<ellipse cx='0' cy='-16' rx='4.2' ry='11' transform='rotate({a}) scale(-1 -1)' stroke='none'/>" for a in (-22.5, -45, -67.5))
       + "<circle r='5' stroke='none'/>")


def corners(piece, inset):
    return "".join(f"<g transform='translate({x} {y}) scale({sx} {sy})'>{piece}</g>" for x, y, sx, sy in [
        (inset, inset, 1, 1), (W - inset, inset, -1, 1), (inset, H - inset, 1, -1), (W - inset, H - inset, -1, -1)])


def band(pattern, outer=22, inner=42):
    return (f"<rect x='17' y='17' width='{W-34}' height='{H-34}' fill='none' stroke='#000' stroke-width='1.6'/>"
            f"<path d='M{outer} {outer} H{W-outer} V{H-outer} H{outer} Z M{inner} {inner} V{H-inner} H{W-inner} V{inner} Z' fill='url(#{pattern})' fill-rule='evenodd'/>"
            f"<rect x='{outer}' y='{outer}' width='{W-2*outer}' height='{H-2*outer}' fill='none' stroke='#000' stroke-width='1'/>"
            f"<rect x='{inner}' y='{inner}' width='{W-2*inner}' height='{H-2*inner}' fill='none' stroke='#000' stroke-width='1'/>")


def gold_frame(a=49, b=53):
    return (f"<rect x='{a}' y='{a}' width='{W-2*a}' height='{H-2*a}' fill='none' stroke-width='1.6'/>"
            f"<rect x='{b}' y='{b}' width='{W-2*b}' height='{H-2*b}' fill='none' stroke-width='.7'/>")


def diamond_rule(y, half=56):
    return (f"<g transform='translate(200 {y})' stroke='none'><path d='M-{half} -.6 H-14 V.6 H-{half}Z M14 -.6 H{half} V.6 H14Z'/>"
            f"<path d='M-9 0 L0 -5 L9 0 L0 5Z'/><circle cx='-{half+4}' r='1.8'/><circle cx='{half+4}' r='1.8'/></g>")


def rosette(y, base):
    return (f"<g transform='translate(200 {y})' stroke='none'><path d='M0 -13 L4 -4 L13 0 L4 4 L0 13 L-4 4 L-13 0 L-4 -4Z'/>"
            f"<circle r='3' fill='{base}'/><circle r='17' fill='none' stroke='url(#gold)' stroke-width='.8'/></g>")


def layout(name, base):
    """Return (blind ornament, recessed panels, gilt ornament, text placement)."""
    if name == "cartouche":
        panel = "M78 175 C94 146 106 130 128 128 H272 C294 130 306 146 322 175 C306 204 294 220 272 222 H128 C106 220 94 204 78 175Z"
        return (band("band") + "<g fill='#000' stroke='#000'>" + corners(FAN, 58) + "</g>",
                f"<path d='{panel}'/>",
                gold_frame() + f"<path d='{panel}' fill='none' stroke-width='1'/>" + diamond_rule(282) + rosette(478, base),
                dict(top=125, h=100, side=90, maxw=210, credit=298, authw=250, eng=236))
    if name == "arch":
        arch = "M86 522 V206 C86 158 146 146 172 118 C188 102 198 90 200 76 C202 90 212 102 228 118 C254 146 314 158 314 206 V522 Z"
        return (band("rings"),
                f"<path d='{arch}'/>",
                gold_frame() + f"<path d='{arch}' fill='none' stroke-width='1.3'/>"
                + f"<path d='{arch}' fill='none' stroke-width='.6' transform='translate(200 300) scale(.955 .965) translate(-200 -300)'/>"
                + "<circle cx='200' cy='66' r='3' stroke='none'/>" + diamond_rule(326, 46)
                + "<g transform='translate(200 488)' stroke='none'><circle r='2.4'/><circle cx='-9' r='1.6'/><circle cx='9' r='1.6'/></g>",
                dict(top=160, h=112, side=104, maxw=180, credit=342, authw=200, engw=180, eng=280, font="'Aref Ruqaa', 'Amiri', serif", size=(54, 40)))
    if name == "medallion":
        r = (112 ** 2 + 150 ** 2) / (2 * 112)
        d = (r * r - 150 ** 2) ** .5
        lens = (f"M200 98 A{f(r)} {f(r)} 0 0 1 200 398 A{f(r)} {f(r)} 0 0 1 200 98Z")
        square = "<rect x='-7' y='-7' width='14' height='14' transform='rotate(45)'/><circle r='2.6' fill='{0}'/>"
        return ("".join(f"<rect x='{i}' y='{i}' width='{W-2*i}' height='{H-2*i}' fill='none' stroke='#000' stroke-width='1.2'/>" for i in (18, 24, 30)),
                f"<path d='{lens}'/>",
                gold_frame(40, 44) + f"<path d='{lens}' fill='none' stroke-width='1.4'/>"
                + f"<path d='{lens}' fill='none' stroke-width='.6' transform='translate(200 248) scale(.93 .95) translate(-200 -248)'/>"
                + "<circle cx='200' cy='86' r='3' stroke='none'/><circle cx='200' cy='410' r='3' stroke='none'/>"
                + "".join(f"<g transform='translate({x} {y})' stroke='none'>{square.format(base)}</g>" for x, y in [(62, 62), (W - 62, 62), (62, H - 62), (W - 62, H - 62)]),
                dict(top=176, h=112, side=106, maxw=172, credit=424, authw=250, engw=150, eng=300))
    if name == "notched":
        panel = "M104 110 H296 L316 130 V214 L296 234 H104 L84 214 V130 Z"
        inner = "M108 116 H292 L310 134 V210 L292 228 H108 L90 210 V134 Z"
        sq = "<rect x='-6' y='-6' width='12' height='12'/><path d='M0 -4 L4 0 L0 4 L-4 0Z' fill='{0}'/>"
        return (band("chevrons"),
                f"<path d='{panel}'/>",
                gold_frame() + f"<path d='{panel}' fill='none' stroke-width='1.3'/><path d='{inner}' fill='none' stroke-width='.6'/>"
                + "".join(f"<g transform='translate({x} {y})' stroke='none'>{sq.format(base)}</g>" for x, y in [(51, 51), (W - 51, 51), (51, H - 51), (W - 51, H - 51)])
                + diamond_rule(286, 40)
                + f"<g transform='translate(200 482)'><ellipse rx='26' ry='13' fill='none' stroke-width='.9'/><ellipse rx='21' ry='9' fill='none' stroke-width='.5'/><circle r='2.4' stroke='none'/></g>",
                dict(top=120, h=104, side=96, maxw=200, credit=306, authw=250, eng=242))
    if name == "plain":
        thick_thin = lambda y, s: (f"<rect x='92' y='{y}' width='216' height='2.4' stroke='none'/>"
                                   f"<rect x='92' y='{y + 5*s}' width='216' height='.8' stroke='none'/>")
        return ("".join(f"<rect x='{i}' y='{i}' width='{W-2*i}' height='{H-2*i}' fill='none' stroke='#000' stroke-width='{w}'/>" for i, w in ((16, 2), (22, .9), (27, .9))),
                "",
                f"<rect x='36' y='36' width='{W-72}' height='{H-72}' fill='none' stroke-width='1.1'/>"
                + thick_thin(130, 1) + thick_thin(252, -1)
                + "<g transform='translate(200 500)' stroke='none'><circle cy='-5' r='2.2'/><circle cx='-5' cy='3' r='2.2'/><circle cx='5' cy='3' r='2.2'/></g>",
                dict(top=136, h=106, side=62, maxw=250, credit=306, authw=260, eng=264, size=(58, 40)))
    if name == "headband":
        return (band("dots", 24, 40)
                + "<g fill='#000'><path d='M58 172 l10 -10 l10 10 l-10 10Z'/><path d='M342 172 l-10 -10 l-10 10 l10 10Z'/></g>",
                "<rect x='58' y='120' width='284' height='104'/>",
                gold_frame(47, 51)
                + "<rect x='58' y='116' width='284' height='1.6' stroke='none'/><rect x='58' y='120' width='284' height='.6' stroke='none'/>"
                + "<rect x='58' y='224' width='284' height='.6' stroke='none'/><rect x='58' y='227' width='284' height='1.6' stroke='none'/>"
                + diamond_rule(480, 64),
                dict(top=122, h=100, side=86, maxw=228, credit=292, authw=250, eng=238))
    raise ValueError(name)


def build_svg(slug, cloth, name):
    svg = leather.build_svg(slug, cloth)
    base = CLOTHS[cloth][0]
    blind, panels, gilt, text = layout(name, base)
    head, rest = svg.split('    <g filter="url(#blind)">', 1)
    rest = rest[rest.index("\n    <g><circle"):]
    head = head.replace("  </defs>", PATTERNS + "  </defs>")
    body = (f'    <g filter="url(#blind)">{blind}</g>\n'
            f'    <g fill="#000" opacity=".22">{panels}</g>\n'
            f'    <g mask="url(#giltWear)" filter="url(#stamped)" fill="url(#gold)" stroke="url(#gold)">{gilt}</g>')
    return head + body + rest, text


def english(slug):
    from export import SITE
    return SITE[slug][1]


def render(slug, title, cloth, lead, credit):
    author, death = credit
    two_lines = "<br>" in title
    svg, t = build_svg(slug, cloth, LAYOUT[slug])
    size = t.get("size", (50, 36))
    html = (HERE / "cover.html").read_text()
    for key, value in {
        "__SVG__": svg, "__TITLE__": title, "__LEAD__": lead, "__AUTHOR__": author, "__DEATH__": death,
        "__TITLE_SIZE__": str(size[1] if two_lines else size[0]),
        "__TOP__": str(t["top"]), "__H__": str(t["h"]), "__SIDE__": str(t["side"]),
        "__MAXW__": str(t["maxw"]), "__CREDIT__": str(t["credit"]), "__AUTHW__": str(t["authw"]),
        "__FONT__": t.get("font", "'Amiri', serif"),
        "__ENG__": english(slug), "__ENG_TOP__": str(t["eng"]), "__ENGW__": str(t.get("engw", 230)),
    }.items():
        html = html.replace(key, value)
    page = OUT / f"{slug}.html"
    page.write_text(html)
    png = OUT / f"{slug}.png"
    subprocess.run([
        "google-chrome", "--headless=new", "--disable-gpu", "--hide-scrollbars",
        "--force-device-scale-factor=2", "--window-size=700,900",
        "--virtual-time-budget=8000", "--default-background-color=00000000",
        f"--screenshot={png}", page.as_uri(),
    ], check=True, capture_output=True)
    im = Image.open(png).crop((0, 0, 800, 1200))
    im.save(png)
    im.save(OUT / f"{slug}.webp", quality=88, method=6)
    im.resize((400, 600), Image.LANCZOS).save(OUT / f"{slug}-400.webp", quality=86, method=6)


def contact_sheet():
    ims = [Image.open(OUT / f"{slug}.png").convert("RGBA").resize((240, 360), Image.LANCZOS) for slug, *_ in BOOKS]
    sheet = Image.new("RGBA", (6 * 250 + 10, 3 * 370 + 10), "#f3f1ec")
    for i, im in enumerate(ims):
        sheet.alpha_composite(im, (10 + (i % 6) * 250, 10 + (i // 6) * 370))
    sheet.convert("RGB").save(OUT / "contact-sheet.png")


if __name__ == "__main__":
    OUT.mkdir(exist_ok=True)
    only = set(sys.argv[1:])
    for book in BOOKS:
        if not only or book[0] in only:
            render(*book)
    if not only:
        contact_sheet()
