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
"""
import argparse
import json
import os
import subprocess
import sys

from playwright.sync_api import sync_playwright

HADITH = "/hadith/Al-Kafi-Volume-1-Kulayni:66"
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
    card.style.transformOrigin = 'top left';
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
    ap.add_argument("--poster-at", type=float, default=5.2)
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    mp4 = os.path.join(args.out, "chain.mp4")
    jpg = os.path.join(args.out, "chain.jpg")

    with sync_playwright() as p:
        browser = p.chromium.launch()
        ctx = browser.new_context(viewport={"width": W, "height": H}, device_scale_factor=1)
        page = ctx.new_page()
        page.goto(args.base + HADITH, wait_until="networkidle")
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

        # Open both panels once so their contents exist, and open the card so it is in the
        # document with its own markup; from here on nothing is clicked and every frame is
        # set by hand.
        page.locator("[data-hub-panel='rijal']").first.click()
        page.wait_for_timeout(1200)
        names = page.locator("a.chain-name")
        picked = None
        for i in range(names.count()):
            box = names.nth(i).bounding_box()
            if box and 700 < box["x"] < 1400 and box["y"] < 800:
                picked = names.nth(i)
                break
        if picked is None:
            sys.exit("no in-text chain name in frame")
        picked.evaluate("el => el.setAttribute('data-clip-name', '1')")
        picked.click()
        page.wait_for_timeout(900)
        picked.evaluate("el => el.blur()")
        card = page.evaluate(UNZOOM)
        lit = page.evaluate("""() => {
            const a = document.querySelector('a.chain-name[class*="is-"]');
            if (!a) { return 'is-open'; }
            return (a.className.split(/\\s+/).find(c => c.startsWith('is-')) || 'is-open');
        }""")
        page.add_style_tag(content=STILL)

        side = page.locator(".hadith-sidecar").first.bounding_box()
        clip = dict(CLIP, x=max(0, int(side["x"]) - 32), y=max(0, int(side["y"]) - 8))
        print(json.dumps({"sidecar": side, "card": card, "clip": clip, "lit": lit}))

        knobs = {"SWITCH": SWITCH, "PANEL_IN": PANEL_IN, "FIRST": FIRST, "STAGGER": STAGGER,
                 "LAND": LAND, "CARD_AT": CARD_AT, "CARD_IN": CARD_IN, "lit": lit}

        page.evaluate(STATE, dict(knobs, t=args.poster_at))
        page.screenshot(path=jpg, type="jpeg", quality=88, clip=clip)

        frames = int(round(DURATION * args.fps))
        ff = subprocess.Popen(
            ["ffmpeg", "-y", "-v", "error", "-f", "image2pipe", "-framerate", str(args.fps),
             "-c:v", "png", "-i", "-", "-c:v", "libx264", "-preset", "slow", "-crf", "22",
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
