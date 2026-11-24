#!/usr/bin/env python3
"""Clean tafsir passages and choose which ones to show under a hadith-verse link.

Deterministic. What it fixes, each seen in the 2026-11-24 spot check:

* Quranic Reflections pages open with a seasonal hadith or an advert before the
  reflection begins ("Bismillah. Prophet Muhammad (s): And when it is night of Qadr…",
  "Check our new online courses…"). Everything up to the verse citation line goes.
* Enlightening Commentary and al-Mizan open by repeating the verse in Arabic and in
  translation, which the page already shows above the commentary. That goes too.
* A passage split out of a multi-verse section is often about a neighbouring verse.
  It is kept only if it quotes the verse or names its number, else used as a last resort.
* The same text stored under several titles is shown once.
* Pooya is shown only when no other English commentary covers the verse.
"""

import re
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from quoted_verses import tokens  # noqa: E402

ARABIC_CHAR = re.compile(r"[؀-ۿ]")
LATIN_CHAR = re.compile(r"[A-Za-z]")
QR_CITATION = re.compile(
    r"^\(?\s*S[uū]ra(?:t|tu|h)?\b[^\n]{0,60}?(?:No\.?\s*\d+|\d+\s*[:،,]\s*\d+)[^\n]{0,40}?[AĀ]y[aā]t?\b[^\n]{0,40}\)?\s*$"
    r"|^\(?\s*S[uū]ra(?:t|tu|h)?\b[^\n]{0,60}?\(No\.?\s*\d+\)[^\n]{0,40}$",
    re.IGNORECASE)
# The same citation closing a paragraph that also holds the transliteration and translation:
# "… Sūratul Wāqi‘ah (No. 56), Āyats 10-11" / "(Sūrat al-Nisā, No 4, Āyat 36)".
QR_CITATION_TAIL = re.compile(
    r"S[uū]r[a-zāʿ‘’'\s.-]{0,40}?[^\n]{0,50}?No\.?\s*\d+\)?,?\s*[AĀ]y[aā]t?s?\.?\s*[\d\s:&,–-]+\)?\.?\s*$",
    re.IGNORECASE)
PROMO = re.compile(
    r"^(check our new online course|click here|order (a )?printed cop|have you visited|holy ramadan gift"
    r"|join our|register (now|here)|subscribe|download (the|our))", re.IGNORECASE)
SURAH_OPENING = re.compile(r"^\s*In\s+the\s+Name\s+of\s+Allah\b", re.IGNORECASE)
# A seasonal saying with its source, which some Quranic Reflections issues open with
# without a verse citation after it: "Imam al-Sādiq … said: “…” Bihār al-Anwār, vol. 93".
SEASONAL_SAYING = re.compile(
    r"^(bismill[āa]h\.?\s*)?(the\s+)?(prophet|imam|amīr|amir|lady|sayyida|holy prophet|rasūl|rasul)\b[^\n]{0,80}?\bsa(id|ys)\b",
    re.IGNORECASE)
SAYING_SOURCE = re.compile(r"(bih[āa]r|al-k[āa]f[īi]|[āa]m[āa]l[īi]|wass[āa]’?il|mustadrak|nahj|ghurar|mizan|tuhaf|khis[āa]l|\bvol\.?\s*\d|\bH\s?\d+)", re.IGNORECASE)
# The same saying run into the reflection's first paragraph: cut through its citation.
INLINE_SAYING = re.compile(
    r"^\s*(bismill[āa]h\.?\s*)?(the\s+)?(prophet|imam|amīr|amir|holy prophet)\b.{0,700}?"
    r"(bih[āa]r al-anw[āa]r|al-k[āa]f[īi]|[āa]m[āa]l[īi])[^\n]{0,40}?\d+(\s*[,:]\s*(p\.?\s*)?\d+)?\)?\s*",
    re.IGNORECASE | re.S)
VERSE_NUMBER_LINE = re.compile(r"^\s*\d{1,3}\s*[.)-]\s")
MIZAN_VERSE_LINE = re.compile(r"^\s*Qur['’]an\s*:", re.IGNORECASE)
SPLIT_TITLE = re.compile(r"\(verse\s+(\d+)\)\s*$", re.IGNORECASE)

ENGLISH_PRIORITY = ["enlightening-commentary", "al-mizan", "quranic-reflections", "imam-askari",
                    "fatima-zahra", "khomeini-hamd", "al-bayan", "divine-lights"]
ARABIC_PRIORITY = ["ar-amthal", "ar-tafsir-al-qummi", "ar-kanz-al-daqaiq"]
FALLBACK_ONLY = {"pooya-mir-ahmad-ali"}
MIN_CLEAN_CHARS = 160
MAX_SNIPPETS = 3


def paragraphs(text):
    return [p for p in re.split(r"\n\s*\n", text or "")]


def arabic_share(p):
    a, l = len(ARABIC_CHAR.findall(p)), len(LATIN_CHAR.findall(p))
    return a / (a + l) if a + l else 0.0


def leading_drop(slug, paras):
    """How many leading paragraphs are header, advert or a repeat of the verse."""
    n = 0
    if slug == "quranic-reflections":
        limit = max(1, int(len(paras) * 0.6))
        for i, p in enumerate(paras[:limit]):
            if QR_CITATION.match(p.strip()) or (len(p) < 1200 and QR_CITATION_TAIL.search(p.strip())):
                n = i + 1
                break
    # Adverts and repeats of the verse, in any source, at the very top.
    while n < len(paras):
        p = paras[n].strip()
        if not p or PROMO.match(p) or SURAH_OPENING.match(p):
            n += 1
        elif slug == "quranic-reflections" and n < 2 and SEASONAL_SAYING.match(p) and SAYING_SOURCE.search(p[-200:]):
            n += 1
        elif n < 3 and arabic_share(p) > 0.6 and not slug.startswith("ar-"):
            n += 1                                   # the verse in Arabic
        elif n < 4 and (VERSE_NUMBER_LINE.match(p) or MIZAN_VERSE_LINE.match(p)) and len(p) < 900:
            n += 1                                   # "12. And when the hypocrites…" / "Qur'an: …"
        elif slug == "divine-lights" and n < 4 and re.search(r"\(\d+:\d+\)\.?\s*$", p) and len(p) < 600:
            n += 1                                   # Divine Lights restates each verse in translation
        else:
            break
    return n


def clean(slug, text, highlighted=None):
    """(clean_text, clean_highlighted). The highlighted copy loses the same paragraphs,
    so its <em> span survives; if the two do not line up it is dropped, not guessed."""
    if slug == "quranic-reflections":
        m = INLINE_SAYING.match(text)
        if m and len(text) - m.end() > MIN_CLEAN_CHARS:
            cut = m.group(0)
            text = text[m.end():]
            if highlighted and highlighted.startswith(cut):
                highlighted = highlighted[len(cut):]
            elif highlighted:
                highlighted = None
    paras = paragraphs(text)
    n = leading_drop(slug, paras)
    body = "\n\n".join(p for p in paras[n:] if not PROMO.match(p.strip())).strip()
    hl = None
    if highlighted and "<em>" in highlighted:
        hparas = paragraphs(highlighted)
        if len(hparas) == len(paras):
            hl = "\n\n".join(p for p, q in zip(hparas[n:], paras[n:]) if not PROMO.match(q.strip())).strip()
            if "<em>" not in hl:
                hl = None
    return body, hl


def anchored(doc, verse_key, verse_arabic):
    """A passage split out of a multi-verse section must show it is about this verse."""
    m = SPLIT_TITLE.search(doc.get("section_title") or "")
    if not m:
        return True
    text = doc.get("commentary_text") or ""
    ayah = verse_key.split(":")[1]
    if re.search(rf"\b(verse|ayah|āyah|āyat|ayat)\s+{ayah}\b|\b{re.escape(verse_key)}\b|\({ayah}\)", text, re.I):
        return True
    vw = [t[0] for t in tokens(verse_arabic)]
    tw = [t[0] for t in tokens(text)]
    grams = {tuple(vw[i:i + 3]) for i in range(len(vw) - 2)}
    return any(tuple(tw[i:i + 3]) in grams for i in range(len(tw) - 2))


def norm_key(text):
    return re.sub(r"\W+", "", (text or "").lower())[:600]


def choose(verse_key, verse_arabic, docs, live_snippets=None):
    """The snippets to show for one link, best first.

    docs: tafsir docs covering the verse. live_snippets: what the live index shows for this
    link now, with any <em> highlight chosen for this hadith; their highlight is reused
    when the same passage is chosen again."""
    live_hl = {}
    for s in live_snippets or []:
        h = s.get("commentary_text_highlighted")
        if h and "<em>" in h:
            live_hl[norm_key(s.get("commentary_text"))] = h

    scored = []
    for d in docs:
        slug = d.get("tafsir_slug")
        if slug in (None, "test"):
            continue
        raw = d.get("commentary_text") or ""
        body, hl = clean(slug, raw, live_hl.get(norm_key(raw)))
        if len(body) < MIN_CLEAN_CHARS:
            continue
        is_anchored = anchored(d, verse_key, verse_arabic)
        if slug in ARABIC_PRIORITY:
            lang, rank = "ar", ARABIC_PRIORITY.index(slug)
        elif slug in FALLBACK_ONLY:
            lang, rank = "en", 50
        else:
            lang, rank = "en", ENGLISH_PRIORITY.index(slug) if slug in ENGLISH_PRIORITY else 40
        scored.append({
            "lang": lang,
            "order": (0 if is_anchored else 1, 0 if hl else 1, rank),
            "slug": slug,
            "doc": d,
            "text": body,
            "highlighted": hl,
            "anchored": is_anchored,
        })
    scored.sort(key=lambda s: s["order"])

    chosen, seen = [], set()
    english = [s for s in scored if s["lang"] == "en"]
    have_other_english = any(s["slug"] not in FALLBACK_ONLY and s["anchored"] for s in english)
    for s in english:
        if s["slug"] in FALLBACK_ONLY and have_other_english:
            continue
        # A passage split from a multi-verse section that never names this verse may still
        # be about it (Verses 183-185 on 2:184 is), or about its neighbour (Verses 5-11 on
        # 76:8 was). One such passage is shown, after every passage that does name it.
        if not s["anchored"] and any(not c["anchored"] for c in chosen):
            continue
        k = norm_key(s["text"])
        if k in seen:
            continue
        seen.add(k)
        chosen.append(s)
        if len(chosen) == MAX_SNIPPETS:
            break
    arabic = next((s for s in scored if s["lang"] == "ar" and s["anchored"]), None)
    return chosen, arabic


SENTENCE = re.compile(r"(?<=[.!?؟])\s+")
MIN_PREVIEW = 120   # a preview shorter than this is a phrase, not an explanation


def preview(text, highlighted, max_chars=420):
    """The part of a passage to show before "read more": the highlighted span with the
    sentence around it, else the opening sentences."""
    if highlighted:
        m = re.search(r"<em>(.+?)</em>", highlighted, re.S)
        if m:
            span = re.sub(r"\s+", " ", m.group(1)).strip()
            if len(span) >= MIN_PREVIEW:
                if len(span) <= max_chars * 1.5:
                    return span
                return span[:max_chars].rsplit(" ", 1)[0] + " …"
            # A highlight of a few words ("month of Ramadan") is no preview on its own:
            # show the sentence it sits in, and the ones after it up to the limit.
            plain = re.sub(r"\s+", " ", re.sub(r"</?em>", "", highlighted)).strip()
            sentences = SENTENCE.split(plain)
            at = next((i for i, x in enumerate(sentences) if span[:40] in x), None)
            if at is not None:
                out = ""
                for sent in sentences[at:]:
                    if out and len(out) + len(sent) > max_chars:
                        break
                    out = (out + " " + sent).strip()
                if len(out) >= MIN_PREVIEW:
                    return out if len(out) <= max_chars * 1.3 else out[:max_chars].rsplit(" ", 1)[0] + " …"
    out = ""
    for sent in SENTENCE.split(re.sub(r"\s+", " ", text).strip()):
        if out and len(out) + len(sent) > max_chars:
            break
        out = (out + " " + sent).strip()
    return out if len(out) <= max_chars * 1.3 else out[:max_chars].rsplit(" ", 1)[0] + " …"
