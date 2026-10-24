#!/usr/bin/env python3
"""Renders the RIJAL panel's clip frame by frame, the way the connector guides are made.

A screen recording of this panel is choppy however it is captured, and the reason is not
the capture. Playwright emits a clean 25fps, but the panel is still for most of a take and
then changes violently: switching a tab replaces its contents in a single frame. Measured
on a recording, 14% of frames carried any change and the largest was 71.7 out of 255,
against 13.9 for the hand-rendered connector clips.

So this does what scripts/connector-guides/render-compare.cjs does — compute the state at
i/FPS and screenshot it, so every frame is unique and the easing is ours — except that it
drives the REAL page rather than a mock of it. The page's own CSS animations are switched
off and each frame's opacity and offset are set here, which costs nothing in fidelity: the
pixels are the site's, down to the font and the badge.

    python3 scripts/rijal-clip/render_chain.py OUT_DIR [--fps 30] [--base http://localhost:8010]
                                               [--locale en|ar]

The Arabic site is the same pages under /ar/, and its layout mirrors: the sidecar moves to
the right edge and the narration to its left. A clip shot on the English page and shown to
Arabic readers shows them a screen that is not theirs, so each locale gets its own take.
"""
import argparse
import json
import os
import subprocess
import sys

from playwright.sync_api import sync_playwright

HADITH = "/hadith/Al-Kafi-Volume-1-Kulayni:66"
PREFIX = {"en": "", "ar": "/ar"}
# Which narration each locale films. English keeps Kulayni:66. Arabic cannot: its narration is
# only three lines, so the tag chips ride at y=823 and every card the page can open from a name
# in a 1440px window (it is 428-560px tall and hangs from the name's line at y=400) ends on or
# past them; measured for names 1-4 of :66, :5, :11, :43 and :55, none cleared. Kulayni:76 has a
# five-line matn, the chips sit at y=922, and its first name's card ends at y=861. It is a
# four-link chain with all four narrators identified, like :66.
HADITH_BY_LOCALE = {"en": HADITH, "ar": "/hadith/Al-Kafi-Volume-1-Kulayni:76"}
W, H = 2160, 1200           # laid out at 2x so the clip is 1440x928 of real pixels
CLIP = {"width": 1440, "height": 928}
DURATION = 7.0

# The beats, in seconds. Kept slow enough to read: a reader meets the narration, the panel
# changes to the chain, the links land in turn, and one name opens where it stands.
SWITCH = 0.90               # the rail moves to RIJAL
PANEL_IN = 0.18             # how long the panel takes to appear
FIRST = 1.25                # the first link lands
STAGGER = 0.22
LAND = 0.50
CARD_AT = 3.45              # the narrator's card opens over the narration
CARD_IN = 0.45

STILL = "*, *::before, *::after { animation: none !important; transition: none !important; }"

# Everything the frames need, applied in the page. Written as one function so a frame is a
# single round trip rather than a dozen.
STATE = """(a) => {
  const t = a.t;
  const ease = x => (x <= 0 ? 0 : x >= 1 ? 1 : 1 - Math.pow(1 - x, 3));
  const span = (at, len) => ease((t - at) / len);

  const rail = document.querySelectorAll('.hadith-sidecar__rail-btn');
  rail.forEach(b => {
    const on = b.getAttribute('data-hub-panel') === (t < a.SWITCH ? 'metadata' : 'rijal');
    b.classList.toggle('is-active', on);
    b.setAttribute('aria-selected', on ? 'true' : 'false');
  });

  const meta = document.querySelector('[data-hub-panel-body="metadata"]');
  const chain = document.querySelector('[data-hub-panel-body="rijal"]');
  meta.classList.toggle('d-none', t >= a.SWITCH);
  chain.classList.toggle('d-none', t < a.SWITCH);
  chain.style.opacity = span(a.SWITCH, a.PANEL_IN);

  // The whole <li> is faded, not just its card: the mark and the connector drawn as its
  // ::before are children of it, so they land with the name instead of waiting for it.
  document.querySelectorAll('.rijal-link').forEach((li, i) => {
    const p = span(a.FIRST + i * a.STAGGER, a.LAND);
    li.style.opacity = p;
    li.style.transform = 'translateY(' + ((1 - p) * 16).toFixed(2) + 'px)';
  });

  const card = document.querySelector('.narrator-preview');
  if (card) {
    const p = span(a.CARD_AT, a.CARD_IN);
    card.style.display = p > 0 ? '' : 'none';
    card.style.opacity = p;
    card.style.transform = 'translateY(' + ((1 - p) * 10).toFixed(2) +
                           'px) scale(' + (0.985 + 0.015 * p).toFixed(4) + ')';
    card.style.transformOrigin = a.rtl ? 'top right' : 'top left';
  }
  // The highlight on the name is no class at all - it is :focus-visible left behind by
  // the click, which the browser grants or withholds on its own and so cannot be timed.
  // The anchor is blurred during setup and the same two declarations are written here
  // instead, so the name lights as its card arrives rather than before it.
  const name = document.querySelector('a.chain-name[data-clip-name]');
  if (name) {
    const q = (0.1 * span(a.CARD_AT - 0.08, 0.25)).toFixed(3);
    name.style.backgroundColor = 'rgba(200, 162, 61, ' + q + ')';
    name.style.boxShadow = '0 0 0 2px rgba(200, 162, 61, ' + q + ')';
  }
  return true;
}"""

UNZOOM = """() => {
  const c = document.querySelector('.narrator-preview');
  if (!c || c.dataset.unzoomed) { return null; }
  c.style.left = (parseFloat(c.style.left) / 2) + 'px';
  c.style.top = (parseFloat(c.style.top) / 2) + 'px';
  c.dataset.unzoomed = '1';
  const b = c.getBoundingClientRect();
  return {x: b.x, y: b.y, w: b.width, h: b.height};
}"""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("out")
    ap.add_argument("--fps", type=int, default=30)
    ap.add_argument("--base", default="http://localhost:8010")
    ap.add_argument("--hadith", default=None)
    ap.add_argument("--locale", choices=sorted(PREFIX), default="en")
    ap.add_argument("--poster-at", type=float, default=5.2)
    args = ap.parse_args()
    rtl = args.locale == "ar"
    args.hadith = args.hadith or HADITH_BY_LOCALE[args.locale]
    os.makedirs(args.out, exist_ok=True)
    mp4 = os.path.join(args.out, "chain.mp4")
    jpg = os.path.join(args.out, "chain.jpg")

    with sync_playwright() as p:
        browser = p.chromium.launch()
        ctx = browser.new_context(viewport={"width": W, "height": H}, device_scale_factor=1)
        page = ctx.new_page()
        def prepare():
            page.goto(args.base + PREFIX[args.locale] + args.hadith, wait_until="networkidle")
            page.evaluate("document.documentElement.style.zoom = '2'")
            for close in ("[data-announcement-close]", ".announcement__close",
                          "button[aria-label*='Dismiss']", "button[aria-label*='Close']"):
                try:
                    if page.locator(close).first.is_visible(timeout=600):
                        page.locator(close).first.click()
                        break
                except Exception:
                    pass
            page.locator(".hadith-sidecar").first.scroll_into_view_if_needed()
            page.evaluate("window.scrollBy(0, -112)")
            page.wait_for_timeout(400)
            # Open both panels once so their contents exist, and open the card so it is in
            # the document with its own markup; from here on nothing is clicked and every
            # frame is set by hand.
            page.locator("[data-hub-panel='rijal']").first.click()
            page.wait_for_timeout(1200)

        prepare()

        # The window is a fixed 1440x928 of real pixels and is never resized: the page is
        # laid out at 2x so that 1440 is 720 CSS pixels, sharp and native, and an earlier
        # Arabic take that widened the window to 2010px and scaled it down lost 28% of its
        # type size and sat beside the English clip looking soft. A region that does not
        # fit is re-framed, not shrunk.
        #
        # It is anchored on the sidecar's OUTER edge: its left edge in English, its right
        # edge in Arabic, where the panel sits against the right of the page and a window
        # started 32px left of it would run past the 2160px viewport and lose the narration.
        side = page.locator(".hadith-sidecar").first.bounding_box()
        y0 = max(0, int(side["y"]) - 8)
        if rtl:
            right = min(W, int(side["x"] + side["width"]) + 32)
            clip = dict(CLIP, x=right - CLIP["width"], y=y0)
        else:
            clip = dict(CLIP, x=max(0, int(side["x"]) - 32), y=y0)
        AIR = 32

        # Which name to open. Every name in the NARRATION (not the copies in the sidecar's
        # cards) that lies inside the window is tried in turn: the page is reloaded, the
        # name clicked, and the card's box measured, because the page places the card
        # beside its name and knows nothing of this window. The first name whose card sits
        # wholly inside the window with 32px of air, and ends clear of the tag chips, wins.
        # The two languages can land on different names for a reason that is not taste:
        # the English card opens to the right of its name and the Arabic card, in the
        # mirrored layout, to the left of it, and the Arabic narration is short, so the tag
        # row rides high and a card opened from a name on the first line of
        # Kulayni:66 came down over the chips (see HADITH_BY_LOCALE). Which names clear
        # them depends on where the name falls in the line and how tall that narrator's
        # card is, which only a measurement shows. If nothing fits in English the first
        # name stands and the window is widened to hold its card (below); if nothing fits
        # in Arabic the script stops, since the answer there is another narration.
        def in_text_names():
            out = []
            names = page.locator("a.chain-name")
            for i in range(names.count()):
                el = names.nth(i)
                if el.evaluate("e => !!e.closest('.hadith-sidecar')"):
                    continue
                box = el.bounding_box()
                if (box and box["x"] >= clip["x"] and box["x"] + box["width"] <= clip["x"] + clip["width"]
                        and box["y"] < 800):
                    out.append(i)
            return out

        def trial(i):
            prepare()
            page.locator("a.chain-name").nth(i).click()
            page.wait_for_timeout(800)
            c = page.evaluate(UNZOOM)
            tags = page.evaluate("""() => {
                const e = document.querySelector('.topic-tag-pills');
                if (!e) { return null; }
                const r = e.getBoundingClientRect();
                return r.y;
            }""")
            return c, tags

        candidates = in_text_names()
        if not candidates:
            sys.exit("no in-text chain name in frame")
        chosen = None
        clear_of_tags = None
        for i in candidates:
            c, tags = trial(i)
            if not c:
                continue
            tags_ok = tags is None or c["y"] + c["h"] <= tags - 16
            inside = (c["x"] >= clip["x"] + AIR and c["x"] + c["w"] <= clip["x"] + clip["width"] - AIR
                      and c["y"] + c["h"] <= clip["y"] + clip["height"] - AIR)
            print(json.dumps({"try": i, "card": c, "tags_y": tags, "inside": inside, "tags_ok": tags_ok}))
            if inside and tags_ok:
                chosen = i
                break
            if tags_ok and clear_of_tags is None:
                clear_of_tags = i
        if chosen is None:
            if rtl:
                sys.exit("no name's card fits the Arabic window; try another narration (--hadith)")
            chosen = clear_of_tags if clear_of_tags is not None else candidates[0]
        prepare()
        picked = page.locator("a.chain-name").nth(chosen)
        picked.evaluate("el => el.setAttribute('data-clip-name', '1')")
        picked.click()
        page.wait_for_timeout(900)
        picked.evaluate("el => el.blur()")
        # The pointer is still resting on the name from the click, and the page's own :hover
        # colours it gold and underlines it from the first frame. Frame 0 of an earlier take
        # showed the name already lit seven seconds before its card. The pointer is
        # parked on an empty part of the page so the only highlight is the one STATE writes.
        page.mouse.move(8, H - 8)
        page.wait_for_timeout(300)
        card = page.evaluate(UNZOOM)
        # English only, and only when no name's card fits: opened from the first name the
        # card ran from x=835 to x=1571 against a window that ended at 1488, so the take
        # cut its "Open his page" link in half. The window is then widened to hold the card
        # with 32px of air, and made as tall as its own proportions ask (or as the page
        # leaves) so the clip never becomes a letterbox. This is the one case that is
        # scaled to 1440 wide afterwards, by 7%.
        if card and not rtl:
            x0 = min(clip["x"], max(0, int(card["x"]) - 32))
            x1 = max(clip["x"] + clip["width"], min(W, int(card["x"] + card["w"]) + 32))
            clip["x"], clip["width"] = x0, x1 - x0
            want = -(-clip["width"] * CLIP["height"] // CLIP["width"])
            clip["height"] = min(H - clip["y"], max(clip["height"], want))
        lit = page.evaluate("""() => {
            const a = document.querySelector('a.chain-name[class*="is-"]');
            if (!a) { return 'is-open'; }
            return (a.className.split(/\\s+/).find(c => c.startsWith('is-')) || 'is-open');
        }""")
        page.add_style_tag(content=STILL)

        print(json.dumps({"sidecar": side, "card": card, "clip": clip, "lit": lit}))

        knobs = {"SWITCH": SWITCH, "PANEL_IN": PANEL_IN, "FIRST": FIRST, "STAGGER": STAGGER,
                 "LAND": LAND, "CARD_AT": CARD_AT, "CARD_IN": CARD_IN, "lit": lit, "rtl": rtl}

        page.evaluate(STATE, dict(knobs, t=args.poster_at))
        poster = page.screenshot(type="png", clip=clip)
        from io import BytesIO
        from PIL import Image
        img = Image.open(BytesIO(poster)).convert("RGB")
        if img.width != CLIP["width"]:
            img = img.resize((CLIP["width"], round(img.height * CLIP["width"] / img.width / 2) * 2),
                             Image.LANCZOS)
        img.save(jpg, "JPEG", quality=88)

        frames = int(round(DURATION * args.fps))
        ff = subprocess.Popen(
            ["ffmpeg", "-y", "-v", "error", "-f", "image2pipe", "-framerate", str(args.fps),
             "-c:v", "png", "-i", "-", "-vf", "scale=%d:-2:flags=lanczos" % CLIP["width"],
             "-c:v", "libx264", "-preset", "slow", "-crf", "22",
             "-pix_fmt", "yuv420p", "-an", "-movflags", "+faststart", mp4],
            stdin=subprocess.PIPE)
        for i in range(frames):
            page.evaluate(STATE, dict(knobs, t=i / args.fps))
            ff.stdin.write(page.screenshot(type="png", clip=clip))
            if i % 30 == 0:
                print(f"  frame {i}/{frames}", flush=True)
        ff.stdin.close()
        ff.wait()
        ctx.close()
        browser.close()

    print("wrote", mp4, os.path.getsize(mp4), "bytes;", jpg, os.path.getsize(jpg), "bytes")


if __name__ == "__main__":
    main()
