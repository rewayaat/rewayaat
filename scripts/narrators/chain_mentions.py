#!/usr/bin/env python3
"""Cuts each isnād into the names it is made of, keeping the place of each one in the text.

Stage 8 has to turn every name in every chain into a link. A link needs two things the chain
text does not give by itself: where the name begins and ends, and which man it is. This script
does the first half. It reads the chains exported by `ChainExportTool` — the same
`arabicChain` the hadith card renders, so an offset here is an offset into what the reader
sees — and cuts each one into mentions at the words that join names rather than belong to
them: «عن», «حدثنا», «قال», «جميعا».

What it does not do is decide anything about identity. A mention is a span of text and a
guess at its *kind*, and the kinds that matter are the ones that must never become a link:

- `collective` — «بعض أصحابنا», «عدة من أصحابنا», «غير واحد», «رجل». A chain convention
  standing for people the author does not name. There is nobody to link to.
- `relative` — «أبيه», «عمه», «جده». A name only in relation to the man before it; who it
  is depends on that man, which is work for the resolver, not for the cutter.
- `honorific` — a mention that carries «عليه السلام» or «صلى الله عليه وآله». Almost always
  an Imam, named by kunya rather than by name, and «أبي جعفر» alone is two different Imams.
- `name` — everything else, which is what the resolver tries to match.

Usage:
    python3 scripts/narrators/chain_mentions.py --limit 200 --show 40
    python3 scripts/narrators/chain_mentions.py            # writes mentions.jsonl
"""
import argparse
from collections import Counter
import json
import os
import re
import sys

W = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
sys.path.insert(0, f'{W}/scripts/narrators')

TMP = f'{W}/tmp'
CHAINS = f'{TMP}/narrators_chains/chains.jsonl'

_TASHKEEL = re.compile(r"[ً-ْٰـ­ۖ-ۭ]")
# ی and ک are the Persian letters: the Qom editions of several of these books are typeset
# with them, and «أَبِیهِ» went unrecognised as «أبيه» until they were folded — six of the
# audit's wrong links were that one slip, each of them «his father» pointing at a Qummi shaykh.
_FOLD = str.maketrans({"أ": "ا", "إ": "ا", "آ": "ا", "ٱ": "ا", "ى": "ي", "ئ": "ي", "ؤ": "و",
                       "ی": "ي", "ک": "ك", "ۀ": "ه", "ە": "ه", "ھ": "ه"})
_NOT_ARABIC = re.compile(r"[^ء-ي]")

# Words that join one name to the next, or end the chain. None of them is part of a name.
JOINERS = {
    # the relation itself
    "عن", "وعن", "فعن", "عنه", "وعنه", "عنهم", "عنهما", "عمن", "ممن",
    # hearing and writing
    "حدثنا", "وحدثنا", "فحدثنا", "حدثني", "وحدثني", "حدثن", "حدثناه", "اخبرنا", "واخبرنا",
    "اخبرني", "واخبرني", "انبانا", "سمعت", "وسمعت", "سمعته", "قرات", "كتبت", "كتب", "اجازة",
    "املاء", "قراءة", "رفعه", "يرفعه", "مرفوعا", "باسناده", "وباسناده", "باسنادنا", "بهذا",
    "الاسناد", "الاسنادين", "مثله", "نحوه", "جميعا", "معا", "كلهم", "قالا", "قالوا",
    # the words that end a chain and open the body
    "قال", "فقال", "قالت", "قلت", "يقول", "انه", "اني", "ان", "في", "ثم", "وقال",
    "دخلت", "سالت", "وسالته", "سالته", "كنت", "ذكر", "وذكر", "يقال",
}

# «عليه السلام» and its kin: not names, but their presence marks the mention as an Imam's.
BLESSINGS = {
    "عليه", "عليها", "عليهم", "عليهما", "السلام", "صلى", "واله", "وسلم", "وعلى", "رضي",
    "رحمه", "تعالى", "ع", "عج", "عليهالسلام", "صلواته", "صلوات", "اجمعين", "لعنه",
}

# Chain conventions standing for unnamed people. Nobody to link.
COLLECTIVE = {"بعض", "عدة", "اصحابنا", "اصحابه", "اصحابي", "واحد", "غير", "رجل", "رجال",
              "جماعة", "ذكره", "الكوفيين", "اخبرني بعض", "اصحاب", "عدتي"}

# Words no name contains. When the chain's «عن» is the preposition "about" rather than the
# relation — «سألته عن قول الله عز وجل الذين ملكت أيمانكم» — the cut lands in the body of the
# narration, and what follows is a question, not a man. Note that «على» folds to «علي», which
# is a name, so the preposition cannot be listed.
FUNCTION = {"الذي", "التي", "الذين", "يا", "ايها", "كيف", "لم", "لا", "ما", "هل", "اذا",
            "كان", "وكان", "كانت", "مع", "عند", "بين", "قد", "كذا", "الي", "في", "من",
            "هذا", "هذه", "هو", "هي", "انا", "نحن", "لك", "له", "لها", "به", "بها", "عليك",
            "يصلح", "يطاف", "ارايت", "اراه", "تلد", "يتزوج", "فقلت", "حتي", "او", "ايضا"}

# The words that hold a name together and so do not count towards its length.
NAME_GLUE = {"بن", "ابن", "ابي", "ابو", "ابا", "بنت", "عبد", "ال", "مولي", "اخي", "ام"}
MAX_NAME_WORDS = 5

# A name only in relation to the man before it.
RELATIVE = {"ابيه", "ابيها", "ابويه", "عمه", "جده", "اخيه", "ابنه", "عمته", "خاله", "امه",
            "عميه", "جدي", "ابي", "ابوه", "اخوه", "ابايه", "ابائه", "اجداده", "ابويها"}

# و-prefixed co-narrator: «محمد بن يحيى وأحمد بن محمد» is two men, but «وهب» is one name
# that merely starts with the same letter. We split only before a word whose remainder after
# the و is itself a name the corpus uses to open a name.
OPENERS = {"محمد", "احمد", "علي", "الحسن", "الحسين", "جعفر", "عبد", "ابي", "ابو", "ابن",
           "ابراهيم", "موسي", "يحيي", "سعد", "سعيد", "صفوان", "حماد", "عثمان", "يونس",
           "هشام", "اسماعيل", "اسحاق", "القاسم", "عمر", "عمرو", "زرارة", "سهل", "سليمان",
           "يعقوب", "الفضل", "العباس", "جميل", "منصور", "معاوية", "بكر", "حفص", "خالد",
           "داود", "زيد", "سلمة", "عيسي", "غياث", "فضال", "مسعدة", "نوح", "هارون", "الهيثم"}


def bare(word):
    """Strip vowels and fold the letters that vary, so a joiner can be recognised."""
    return _NOT_ARABIC.sub("", _TASHKEEL.sub("", word).translate(_FOLD))


# The sets above are written the way the words are spelled; they are matched against folded
# text, so they are folded once here («صلى» would otherwise never match «صلي»).
JOINERS = {bare(w) for w in JOINERS}
BLESSINGS = {bare(w) for w in BLESSINGS}
COLLECTIVE = {bare(w) for w in COLLECTIVE if " " not in w}
FUNCTION = {bare(w) for w in FUNCTION}
NAME_GLUE = {bare(w) for w in NAME_GLUE}
RELATIVE = {bare(w) for w in RELATIVE}
OPENERS = {bare(w) for w in OPENERS}


def kind_of(words, blessed):
    """What sort of mention a run of words is — the kinds that must not become links."""
    folded = [bare(w) for w in words]
    if any(w in COLLECTIVE for w in folded):
        return "collective"
    if " ".join(folded) in RELATIVE:
        return "relative"
    if any(w in FUNCTION for w in folded):
        return "text"
    if len([w for w in folded if w not in NAME_GLUE]) > MAX_NAME_WORDS:
        return "text"
    if blessed and "بن" not in folded and "ابن" not in folded and len(folded) <= 4:
        # «أبي عبد الله عليه السلام», «الصادق عليه السلام» — an Imam, named by kunya or
        # title rather than by name, and «أبي جعفر» alone is two different Imams.
        return "imam"
    return "name"


# Muʿjam al-Aḥādīth al-Muʿtabara prints the book it is quoting before the isnād, with the
# volume and page in brackets: «[6/162] رجال الكشي: حمدويه، عن محمد بن عيسى …». That is a
# citation, not a step, and the cutter read «رجال الكشي حمدويه» as one run — «رجال» is in
# COLLECTIVE, so the whole thing became an unnamed group and Ḥamdawayh never became a link.
# 551 chains open this way; the names after the colon are ordinary names.
CITATION = re.compile(r"^\s*\d*\s*[-ـ]?\s*\[[^\]]{1,14}\]\s*[^:：]{1,40}?\s*[:：]")


def cited(chain):
    """Where the isnād starts, past a «[vol/page] Book:» citation if there is one."""
    found = CITATION.match(chain)
    return found.end() if found else 0


# A chain that opens by pointing at the one before it instead of naming anybody. Two kinds,
# and they do not mean the same thing: «عنه» is one man — the shaykh the previous chain
# opens with — while «بهذا الإسناد» is the whole of that chain over again. Both are read as
# joiners anywhere else in a chain, which is right; only at the head do they stand for a
# step. 1,548 chains here begin this way, and until now they began with nothing at all.
BACKREF_MAN = (("وعنه",), ("عنه",))
BACKREF_CHAIN = (("وبهذا", "الاسناد"), ("بهذا", "الاسناد"),
                 ("وبهذا", "الاسنادين"), ("بهذا", "الاسنادين"),
                 ("وبالاسناد",), ("بالاسناد",), ("وباسناده",), ("باسناده",),
                 ("وبه",), ("به",))


def backref(folded):
    """How many opening words point backwards, and at what — a man or a whole chain."""
    for forms, kind in ((BACKREF_MAN, "backref_man"), (BACKREF_CHAIN, "backref_chain")):
        for form in forms:
            if tuple(folded[:len(form)]) == form:
                return len(form), kind
    return 0, ""


def segment(chain):
    """Cut one chain into mentions, each with its offsets into the chain text as rendered.

    A blessing ends the name it follows: «صلى الله عليه وآله» holds the word «الله», which is
    name material anywhere else, so everything from the first blessing word onward is set
    aside rather than read as part of the name. The span stops at the last name word, because
    that is what a link should cover.
    """
    opening = cited(chain)
    words = [(opening + m.start(), opening + m.end(), m.group())
             for m in re.finditer(r"\S+", chain[opening:])]
    mentions, run = [], []

    # The hadith number and the dash before it are not words of the chain.
    first = next((i for i, w in enumerate(words) if bare(w[2])), len(words))
    taken, pointing = backref([bare(w[2]) for w in words[first:]])
    if taken:
        span = words[first:first + taken]
        mentions.append({"start": span[0][0], "end": span[-1][1],
                         "text": chain[span[0][0]:span[-1][1]],
                         "blessed": False, "kind": pointing})
        words = words[first + taken:]

    def close():
        if not run:
            return
        named, blessed = [], False
        for word in run:
            if bare(word[2]) in BLESSINGS:
                blessed = True
            if not blessed and bare(word[2]):
                named.append(word)
        if named:
            mentions.append({"start": named[0][0], "end": named[-1][1],
                             "text": chain[named[0][0]:named[-1][1]],
                             "blessed": blessed,
                             "kind": kind_of([w[2] for w in named], blessed)})
        run.clear()

    for start, end, word in words:
        folded = bare(word)
        if not folded:
            continue
        if folded in JOINERS:
            close()
            continue
        if folded == "و" or (folded.startswith("و") and folded[1:] in OPENERS and run):
            # a co-narrator standing beside the one before him
            close()
            if folded == "و":
                continue
            start += word.index("و") + 1
            word = chain[start:end]
        run.append((start, end, word))
    close()
    return mentions


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--chains", default=CHAINS)
    parser.add_argument("--out", default=f'{TMP}/narrators_chains/mentions.jsonl')
    parser.add_argument("--limit", type=int, default=0, help="read only the first N chains")
    parser.add_argument("--show", type=int, default=0, help="print the N commonest mentions")
    parser.add_argument("--dry-run", action="store_true", help="count, write nothing")
    args = parser.parse_args()

    kinds, forms, per_chain = Counter(), Counter(), Counter()
    rows, chains = [], 0
    with open(args.chains) as handle:
        for line in handle:
            row = json.loads(line)
            if not row["chain_ar"].strip():
                continue
            chains += 1
            if args.limit and chains > args.limit:
                break
            mentions = segment(row["chain_ar"])
            per_chain[len(mentions)] += 1
            for mention in mentions:
                kinds[mention["kind"]] += 1
                forms[(mention["kind"], " ".join(bare(w) for w in mention["text"].split()))] += 1
            # Volume and number travel with the mentions because the resolver has to read
            # the book in printed order: a chain that opens «عنه» means the narration
            # above it, and the export comes back in whatever order the index scrolls.
            rows.append({"id": row["id"], "book": row["book"],
                         "source": row.get("source", ""),
                         "volume": row.get("volume", ""), "number": row.get("number", ""),
                         "mentions": mentions})

    total = sum(kinds.values())
    print(f"  {chains:7,}  chains with an Arabic isnād")
    print(f"  {total:7,}  mentions cut out of them "
          f"({total / max(chains, 1):.1f} a chain)")
    for kind, count in kinds.most_common():
        print(f"      {count:7,}  {100 * count / total:5.1f}%  {kind}")
    print("  names a chain:", ", ".join(f"{n}×{c:,}" for n, c in sorted(per_chain.items())[:12]))

    if args.show:
        for kind in ("name", "imam", "collective", "relative", "text"):
            pool = [(f, c) for (k, f), c in forms.items() if k == kind]
            pool.sort(key=lambda fc: -fc[1])
            print(f"\n  commonest {kind} mentions ({len(pool):,} distinct):")
            for form, count in pool[:args.show]:
                print(f"      {count:6,}  «{form}»")

    if args.dry_run:
        return
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")
    print(f"\nwrote {len(rows):,} chains -> {args.out}")


if __name__ == "__main__":
    main()
