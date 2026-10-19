"""Copy the rendered covers into the site, with the gilding of each pulled out on its own.

Usage: python3 export.py   (after covers.py)

Writes, under src/main/resources/static/img/books/:
  covers/<site slug>.webp       600 x 900, for 2x screens and the enlarged chosen book
  covers/<site slug>-400.webp   400 x 600
  gilt/<site slug>.webp         600 x 900, the gold alone on transparency; the shelf
                                brightens it when a book is chosen, and masks the
                                passing light with it so only the gold catches it
"""
from pathlib import Path

import numpy as np
from PIL import Image

from leather import BOOKS

HERE = Path(__file__).parent
STATIC = HERE.parents[1] / "src/main/resources/static/img/books"

# cover slug -> (slug of the book's page on the site, English title set on the cover)
SITE = {
    "al-kafi": ("al-kafi", "Al-Kāfi"),
    "man-la-yahduruh-al-faqih": ("man-la-yahduruh-al-faqih", "Man Lā Yaḥḍuruh al-Faqīh"),
    "nahj-al-balagha": ("nahj-al-balagha", "Nahj al-Balāgha"),
    "al-amali": ("al-amali", "Al-Amālī"),
    "al-khisal": ("al-khisal", "Al-Khiṣāl"),
    "kitab-al-ghayba": ("kitab-al-ghayba", "Kitāb al-Ghayba"),
    "thawab-al-amal": ("thawab-al-a-mal-wa-iqab-al-a-mal", "Thawāb al-Aʿmāl"),
    "uyun-akhbar-al-rida": ("uyun-akhbar-al-rida", "ʿUyūn akhbār al-Riḍā"),
    "maani-al-akhbar": ("ma-ani-al-akhbar", "Maʿānī al-Akhbār"),
    "kamil-al-ziyarat": ("kamil-al-ziyarat", "Kāmil al-Ziyārāt"),
    "al-tawhid": ("al-tawhid", "Al-Tawḥīd"),
    "mujam-al-ahadith-al-mutabara": ("mu-jam-al-ahadith-al-mu-tabara", "Muʿjam al-Aḥādīth al-Muʿtabara"),
    "kitab-al-zuhd": ("kitab-al-zuhd", "Kitāb al-Zuhd"),
    "kitab-al-duafa": ("kitab-al-du-afa", "Kitāb al-Ḍuʿafāʾ"),
    "kitab-al-mumin": ("kitab-al-mu-min", "Kitāb al-Muʾmin"),
    "sifat-al-shia": ("sifat-al-shi-a", "Ṣifāt al-Shīʿa"),
    "risalat-al-huquq": ("risalat-al-huquq", "Risālat al-Ḥuqūq"),
    "fadail-al-shia": ("fada-il-al-shi-a", "Faḍāʾil al-Shīʿa"),
}


def gilt(cover):
    """Gold pixels keep their colour and the rest goes transparent."""
    rgb = np.asarray(cover.convert("RGB"), dtype=np.float32) / 255
    alpha_in = np.asarray(cover.getchannel("A"), dtype=np.float32) / 255
    hsv = np.asarray(cover.convert("RGB").convert("HSV"), dtype=np.float32) / 255
    hue, sat, val = hsv[..., 0] * 360, hsv[..., 1], hsv[..., 2]
    # gold sits at hue ~35-55 with real saturation and brightness; ramp the edges for soft anti-aliasing
    ramp = lambda x, lo, hi: np.clip((x - lo) / (hi - lo), 0, 1)
    a = (ramp(hue, 24, 34) * (1 - ramp(hue, 58, 68)) * ramp(sat, .22, .36) * ramp(val, .3, .45)) * alpha_in
    return Image.fromarray((np.dstack([rgb, a]) * 255).astype(np.uint8), "RGBA")


if __name__ == "__main__":
    (STATIC / "covers").mkdir(parents=True, exist_ok=True)
    (STATIC / "gilt").mkdir(parents=True, exist_ok=True)
    for slug, *_ in BOOKS:
        site = SITE[slug][0]
        cover = Image.open(HERE / "build" / f"{slug}.png")
        cover.resize((600, 900), Image.LANCZOS).save(STATIC / "covers" / f"{site}.webp", quality=88, method=6)
        cover.resize((400, 600), Image.LANCZOS).save(STATIC / "covers" / f"{site}-400.webp", quality=86, method=6)
        gilt(cover).resize((600, 900), Image.LANCZOS).save(STATIC / "gilt" / f"{site}.webp", quality=86, method=6)
