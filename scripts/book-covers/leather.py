"""The leather every cover shares: grain, wear, blind-stamped border and gilt lettering.

One deep leather colour per book, gold only for the lettering and a few rules, and
moderate wear. Each book's wear is seeded by its slug, so a re-render is identical.
covers.py lays a house layout over this; it is not run on its own.
"""
import random

W, H = 400, 600

# cloth, worn-edge tone
CLOTHS = {
    "black":    ("#191817", "#5a554f"),
    "crimson":  ("#5c1416", "#8e4a42"),
    "burgundy": ("#45121b", "#7a4650"),
    "navy":     ("#16213d", "#4c5878"),
    "midnight": ("#111827", "#465066"),
    "green":    ("#142c20", "#4a6555"),
}

SADUQ = ("الشيخ الصدوق<br>محمد بن علي بن بابويه القمي", "المتوفى سنة ٣٨١ هـ")
AHWAZI = ("الحسين بن سعيد الأهوازي", "من أعلام القرن الثالث الهجري")

BOOKS = [
    ("al-kafi", "الكافي", "green", "تأليف", ("ثقة الإسلام<br>محمد بن يعقوب الكليني", "المتوفى سنة ٣٢٩ هـ")),
    ("man-la-yahduruh-al-faqih", "من لا يحضره<br>الفقيه", "crimson", "تأليف", SADUQ),
    ("nahj-al-balagha", "نهج البلاغة", "black", "جمع", ("الشريف الرضي<br>محمد بن الحسين الموسوي", "المتوفى سنة ٤٠٦ هـ")),
    ("al-amali", "الأمالي", "navy", "تأليف", SADUQ),
    ("al-khisal", "الخصال", "burgundy", "تأليف", SADUQ),
    ("kitab-al-ghayba", "كتاب الغيبة", "midnight", "تأليف", ("شيخ الطائفة<br>محمد بن الحسن الطوسي", "المتوفى سنة ٤٦٠ هـ")),
    ("thawab-al-amal", "ثواب الأعمال<br>وعقاب الأعمال", "crimson", "تأليف", SADUQ),
    ("uyun-akhbar-al-rida", "عيون أخبار<br>الرضا", "navy", "تأليف", SADUQ),
    ("maani-al-akhbar", "معاني الأخبار", "black", "تأليف", SADUQ),
    ("kamil-al-ziyarat", "كامل الزيارات", "green", "تأليف", ("جعفر بن محمد بن قولويه<br>القمي", "المتوفى سنة ٣٦٨ هـ")),
    ("al-tawhid", "التوحيد", "black", "تأليف", SADUQ),
    ("mujam-al-ahadith-al-mutabara", "معجم الأحاديث<br>المعتبرة", "burgundy", "تأليف", ("الشيخ محمد آصف المحسني", "المتوفى سنة ١٤٤٠ هـ")),
    ("kitab-al-zuhd", "كتاب الزهد", "midnight", "تأليف", AHWAZI),
    ("kitab-al-duafa", "كتاب الضعفاء", "black", "تأليف", ("أحمد بن الحسين<br>ابن الغضائري", "من أعلام القرن الخامس الهجري")),
    ("kitab-al-mumin", "كتاب المؤمن", "crimson", "تأليف", AHWAZI),
    ("sifat-al-shia", "صفات الشيعة", "navy", "تأليف", SADUQ),
    ("risalat-al-huquq", "رسالة الحقوق", "burgundy", "المنسوبة إلى", ("الإمام علي بن الحسين<br>زين العابدين عليه السلام", "")),
    ("fadail-al-shia", "فضائل الشيعة", "green", "تأليف", SADUQ),
]


def f(v):
    return f"{v:.2f}".rstrip("0").rstrip(".")


def wear(rng):
    out = []
    for _ in range(rng.randint(2, 5)):
        x, y = rng.uniform(40, 360), rng.uniform(40, 560)
        dx, dy = rng.uniform(-50, 50), rng.uniform(-25, 25)
        out.append(f"<path d='M{f(x)} {f(y)} q{f(dx/2)} {f(dy + rng.uniform(-6, 6))} {f(dx)} {f(dy)}' fill='none' "
                   f"stroke='#f0e2c8' stroke-opacity='{f(rng.uniform(.05, .1))}' stroke-width='{f(rng.uniform(.5, 1.1))}' stroke-linecap='round'/>")
    for _ in range(rng.randint(2, 4)):   # creases in the leather: dark fold, lit edge beside it
        x, y = rng.uniform(30, 370), rng.uniform(30, 570)
        dx, dy = rng.uniform(-70, 70), rng.uniform(-70, 70)
        cx, cy = x + dx / 2 + rng.uniform(-15, 15), y + dy / 2 + rng.uniform(-15, 15)
        d = f"M{f(x)} {f(y)} Q{f(cx)} {f(cy)} {f(x+dx)} {f(y+dy)}"
        out.append(f"<path d='{d}' fill='none' stroke='#000' stroke-opacity='.28' stroke-width='.9' stroke-linecap='round'/>"
                   f"<path d='{d}' transform='translate(.8 .9)' fill='none' stroke='#fff' stroke-opacity='.07' stroke-width='.8' stroke-linecap='round'/>")
    for _ in range(rng.randint(1, 3)):   # rubbed patches where the grain has worn flat
        x, y = rng.uniform(30, 370), rng.uniform(30, 570)
        out.append(f"<ellipse cx='{f(x)}' cy='{f(y)}' rx='{f(rng.uniform(10, 28))}' ry='{f(rng.uniform(5, 14))}' "
                   f"transform='rotate({f(rng.uniform(0, 180))} {f(x)} {f(y)})' fill='url(#rubbed)' opacity='.55' filter='url(#ragged)'/>")
    if rng.random() < 0.5:
        x, y, r = rng.choice([rng.uniform(20, 70), rng.uniform(330, 380)]), rng.uniform(60, 540), rng.uniform(40, 70)
        out.append(f"<circle cx='{f(x)}' cy='{f(y)}' r='{f(r)}' fill='url(#stain)' opacity='{f(rng.uniform(.3, .5))}' filter='url(#ragged)'/>")
    return "".join(out)


def build_svg(slug, cloth):
    rng = random.Random(slug)
    seed = rng.randint(1, 999)
    base, worn = CLOTHS[cloth]
    k = [rng.uniform(.6, 1.0) for _ in range(4)]
    corners = [(0, 0), (W, 0), (0, H), (W, H)]
    rub = "".join(f"<circle cx='{x}' cy='{y}' r='{f(80*s)}' fill='url(#rubbed)'/>" for (x, y), s in zip(corners, k))
    loss = "".join(f"<circle cx='{x}' cy='{y}' r='{f(110*s)}'/>" for (x, y), s in zip(corners, k))
    # blind-stamped corner fleuron, drawn for the top-left inner corner
    fleuron = ("<path d='M0 0 H40 A40 40 0 0 1 0 40 Z' fill='none' stroke-width='1.1'/>"
               "<path d='M0 0 H33 A33 33 0 0 1 0 33 Z' fill='none' stroke-width='.8'/>"
               + "".join(f"<ellipse cx='0' cy='-16' rx='4.2' ry='11' transform='rotate({a}) scale(-1 -1)' stroke='none'/>" for a in (-22.5 + 0, -45, -67.5))
               + "<circle r='5' stroke='none'/>")
    return f"""
<svg viewBox="0 0 {W} {H}" width="{W}" height="{H}" xmlns="http://www.w3.org/2000/svg">
  <defs>
    <linearGradient id="gold" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="{W}" y2="{H}">
      <stop offset="0" stop-color="#7d6224"/><stop offset=".25" stop-color="#d9c084"/>
      <stop offset=".5" stop-color="#9a7b30"/><stop offset=".75" stop-color="#cfb474"/><stop offset="1" stop-color="#86692a"/>
    </linearGradient>
    <radialGradient id="rubbed"><stop offset="0" stop-color="{worn}" stop-opacity=".7"/><stop offset=".5" stop-color="{worn}" stop-opacity=".22"/><stop offset="1" stop-color="{worn}" stop-opacity="0"/></radialGradient>
    <radialGradient id="stain"><stop offset=".72" stop-color="#000" stop-opacity="0"/><stop offset=".9" stop-color="#000" stop-opacity=".2"/><stop offset=".97" stop-color="#000" stop-opacity=".28"/><stop offset="1" stop-color="#000" stop-opacity="0"/></radialGradient>
    <radialGradient id="vignette" cx=".5" cy=".45" r=".78"><stop offset=".55" stop-color="#000" stop-opacity="0"/><stop offset="1" stop-color="#000" stop-opacity=".42"/></radialGradient>
    <radialGradient id="edgeLoss"><stop offset="0" stop-color="#000" stop-opacity=".95"/><stop offset=".4" stop-color="#000" stop-opacity=".5"/><stop offset="1" stop-color="#000" stop-opacity="0"/></radialGradient>
    <!-- leather: pebbled grain under a low light, soft mottling, a diagonal sheen -->
    <filter id="leatherGrain" x="0" y="0" width="100%" height="100%">
      <feTurbulence type="fractalNoise" baseFrequency=".95" numOctaves="3" seed="{seed}" result="n"/>
      <feDiffuseLighting in="n" surfaceScale="1.15" lighting-color="#fff" result="lit"><feDistantLight azimuth="235" elevation="60"/></feDiffuseLighting>
      <feBlend in="SourceGraphic" in2="lit" mode="multiply" result="grain"/>
      <feTurbulence type="fractalNoise" baseFrequency=".2" numOctaves="2" seed="{seed+5}" result="p"/>
      <feDiffuseLighting in="p" surfaceScale="2.4" lighting-color="#fff" result="pebble"><feDistantLight azimuth="235" elevation="66"/></feDiffuseLighting>
      <feBlend in="grain" in2="pebble" mode="multiply" result="g2"/>
      <feTurbulence type="fractalNoise" baseFrequency=".011" numOctaves="3" seed="{seed+1}" result="m"/>
      <feColorMatrix in="m" type="matrix" values="0 0 0 0 0  0 0 0 0 0  0 0 0 0 0  0 0 0 -.7 .38" result="mottle"/>
      <feComposite in="mottle" in2="g2" operator="over"/>
    </filter>
    <linearGradient id="sheen" x1="0" y1="0" x2="1" y2="1">
      <stop offset=".1" stop-color="#fff" stop-opacity="0"/><stop offset=".38" stop-color="#fff" stop-opacity=".07"/>
      <stop offset=".55" stop-color="#fff" stop-opacity="0"/>
    </linearGradient>
    <linearGradient id="hinge" x1="0" x2="1">
      <stop offset="0" stop-color="#000" stop-opacity="0"/><stop offset=".45" stop-color="#000" stop-opacity=".6"/>
      <stop offset=".6" stop-color="#fff" stop-opacity=".14"/><stop offset="1" stop-color="#000" stop-opacity="0"/>
    </linearGradient>
    <filter id="ragged" x="-20%" y="-20%" width="140%" height="140%">
      <feTurbulence type="fractalNoise" baseFrequency=".05" numOctaves="2" seed="{seed+2}"/>
      <feDisplacementMap in="SourceGraphic" scale="16" xChannelSelector="R" yChannelSelector="G"/>
    </filter>
    <filter id="roughEdge" x="-5%" y="-5%" width="110%" height="110%">
      <feTurbulence type="fractalNoise" baseFrequency=".09" numOctaves="2" seed="{seed+3}"/>
      <feDisplacementMap in="SourceGraphic" scale="4" xChannelSelector="R" yChannelSelector="G"/>
    </filter>
    <filter id="flake" x="0" y="0" width="100%" height="100%">
      <feTurbulence type="fractalNoise" baseFrequency=".55" numOctaves="2" seed="{seed+4}"/>
      <feColorMatrix type="luminanceToAlpha"/>
      <feComponentTransfer><feFuncA type="discrete" tableValues="0 0 0 0 0 0 0 0 0 0 0 0 0 0 1 1 1 1 1 1"/></feComponentTransfer>
    </filter>
    <mask id="giltWear" maskUnits="userSpaceOnUse" x="0" y="0" width="{W}" height="{H}">
      <rect width="{W}" height="{H}" fill="#fff"/>
      <rect width="{W}" height="{H}" fill="#000" filter="url(#flake)" opacity=".6"/>
      <g fill="url(#edgeLoss)">{loss}</g>
    </mask>
    <!-- blind stamping: pressed into the cloth, dark floor with a lit lower lip -->
    <filter id="blind" x="-3%" y="-3%" width="106%" height="106%">
      <feColorMatrix in="SourceAlpha" type="matrix" values="0 0 0 0 0  0 0 0 0 0  0 0 0 0 0  0 0 0 .3 0" result="floor"/>
      <feOffset in="SourceAlpha" dx=".7" dy=".9" result="o"/>
      <feComposite in="SourceAlpha" in2="o" operator="out" result="topEdge"/>
      <feColorMatrix in="topEdge" type="matrix" values="0 0 0 0 0  0 0 0 0 0  0 0 0 0 0  0 0 0 .55 0" result="shade"/>
      <feOffset in="SourceAlpha" dx="-.6" dy="-.8" result="o2"/>
      <feComposite in="SourceAlpha" in2="o2" operator="out" result="lowEdge"/>
      <feColorMatrix in="lowEdge" type="matrix" values="0 0 0 0 1  0 0 0 0 .95  0 0 0 0 .85  0 0 0 .16 0" result="lip"/>
      <feMerge><feMergeNode in="floor"/><feMergeNode in="shade"/><feMergeNode in="lip"/></feMerge>
    </filter>
    <filter id="stamped" x="-2%" y="-2%" width="104%" height="104%">
      <feOffset dx=".5" dy=".8" in="SourceAlpha" result="o"/>
      <feFlood flood-color="#000" flood-opacity=".5"/><feComposite in2="o" operator="in" result="sh"/>
      <feMerge><feMergeNode in="sh"/><feMergeNode in="SourceGraphic"/></feMerge>
    </filter>
    <!-- border band: interlaced eight-point stars, as on the classic Beirut and Qom bindings -->
    <pattern id="band" width="16" height="16" patternUnits="userSpaceOnUse" x="24" y="24">
      <g fill="none" stroke="#000" stroke-width="1.3">
        <rect x="4" y="4" width="8" height="8"/><rect x="4" y="4" width="8" height="8" transform="rotate(45 8 8)"/>
      </g>
      <circle cx="8" cy="8" r="1.3" fill="#000"/>
      <path d="M0 0 L2.4 0 L0 2.4Z M16 0 L13.6 0 L16 2.4Z M0 16 L2.4 16 L0 13.6Z M16 16 L13.6 16 L16 13.6Z" fill="#000"/>
    </pattern>
    <clipPath id="coverShape"><rect width="{W}" height="{H}" rx="6"/></clipPath>
  </defs>

  <g clip-path="url(#coverShape)">
    <g filter="url(#roughEdge)"><rect x="1" y="1" width="{W-2}" height="{H-2}" rx="7" fill="{base}" filter="url(#leatherGrain)"/></g>
    <rect width="{W}" height="{H}" fill="url(#sheen)"/>
    <rect x="{W-17}" y="0" width="9" height="{H}" fill="url(#hinge)"/>

    <g filter="url(#blind)">
      <rect x="17" y="17" width="{W-34}" height="{H-34}" fill="none" stroke="#000" stroke-width="1.6"/>
      <path d="M22 22 H{W-22} V{H-22} H22 Z M42 42 V{H-42} H{W-42} V42 Z" fill="url(#band)" fill-rule="evenodd"/>
      <rect x="22" y="22" width="{W-44}" height="{H-44}" fill="none" stroke="#000" stroke-width="1"/>
      <rect x="42" y="42" width="{W-84}" height="{H-84}" fill="none" stroke="#000" stroke-width="1"/>
      <g fill="#000" stroke="#000">
        <g transform="translate(58 58)">{fleuron}</g>
        <g transform="translate({W-58} 58) scale(-1 1)">{fleuron}</g>
        <g transform="translate(58 {H-58}) scale(1 -1)">{fleuron}</g>
        <g transform="translate({W-58} {H-58}) scale(-1 -1)">{fleuron}</g>
      </g>
    </g>
    <path d="M78 175 C94 146 106 130 128 128 H272 C294 130 306 146 322 175 C306 204 294 220 272 222 H128 C106 220 94 204 78 175Z"
          fill="#000" opacity=".22"/>

    <g mask="url(#giltWear)" filter="url(#stamped)" fill="url(#gold)" stroke="url(#gold)">
      <rect x="49" y="49" width="{W-98}" height="{H-98}" fill="none" stroke-width="1.6"/>
      <rect x="53" y="53" width="{W-106}" height="{H-106}" fill="none" stroke-width=".7"/>
      <path d="M78 175 C94 146 106 130 128 128 H272 C294 130 306 146 322 175 C306 204 294 220 272 222 H128 C106 220 94 204 78 175Z" fill="none" stroke-width="1"/>
      <!-- small rule ornament under the title -->
      <g transform="translate(200 268)" stroke="none">
        <path d="M-56 -.6 H-14 V.6 H-56Z M14 -.6 H56 V.6 H14Z"/>
        <path d="M-9 0 L0 -5 L9 0 L0 5Z"/><circle cx="-60" r="1.8"/><circle cx="60" r="1.8"/>
      </g>
      <!-- device at the foot -->
      <g transform="translate(200 478)" stroke="none">
        <path d="M0 -13 L4 -4 L13 0 L4 4 L0 13 L-4 4 L-13 0 L-4 -4Z"/>
        <circle r="3" fill="{base}"/>
        <circle r="17" fill="none" stroke="url(#gold)" stroke-width=".8"/>
      </g>
    </g>

    <g>{rub}</g>
    {wear(rng)}
    <rect width="{W}" height="{H}" fill="url(#vignette)"/>
    <rect x=".5" y=".5" width="{W-1}" height="{H-1}" rx="6" fill="none" stroke="{worn}" stroke-opacity=".5" stroke-width="2.2" filter="url(#roughEdge)"/>
  </g>
</svg>"""
