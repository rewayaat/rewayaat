#!/usr/bin/env python3
"""Reads a book's bare names by the company they keep, using the book's own fuller forms.

A chain writes «أحمد بن محمد» and means one of five men. Nothing about the name settles it,
and the rijāl records cannot either — Ibn ʿĪsā and al-Barqī share 45 teachers and 20 students
in our index, so no lookup of who-narrated-from-whom tells them apart.

But the book tells us. Al-Kulaynī writes the name out whenever he thinks it needs writing out,
and those places say who he means *in that position, between those neighbours*:

    عدة من أصحابنا عن أحمد بن محمد بن خالد عن عثمان بن عيسى     103 times
    عدة من أصحابنا عن أحمد بن محمد       عن عثمان بن عيسى      and bare

Al-Kāfī never once writes Ibn ʿĪsā in that slot. So the bare one is al-Barqī, on the book's
own word rather than on ours. Measured against the explicit mentions themselves, leaving each
out in turn, the pair of neighbours predicts the right man 99.5% of the time.

This is deliberately narrow in three ways, because a wrong link is worse than a missing one:

- **Only the book's own usage counts.** A convention belongs to an author's practice, not to
  the tradition. Al-Kāfī reads «عدة من أصحابنا عن أحمد بن محمد» as al-Barqī; that says nothing
  about how al-Ṣadūq reads the same words.
- **Only `one` answers are evidence.** A fuller form that was itself guessed at cannot testify
  about anything, so only the forms a single rijālī heading claims outright are counted.
- **A split context answers nothing.** Where a book writes both men in the same slot in real
  numbers — «عدة من أصحابنا عن أحمد بن محمد عن علي بن الحكم» is 19 Ibn ʿĪsā against 14 al-Barqī
  — the bare one is left unanswered. It is not ours to break the tie.

Usage:
    python3 scripts/narrators/chain_contexts.py --show احمد\\ بن\\ محمد
    python3 scripts/narrators/chain_contexts.py --build
"""
import argparse
from collections import Counter, defaultdict
import json
import os
import sys

W = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
sys.path.insert(0, f'{W}/scripts/narrators')
from chain_resolve import key  # noqa: E402
from identity import write_json_atomic  # noqa: E402

TMP = f'{W}/tmp/narrators_chains'
TABLE = f'{W}/scripts/data/narrators/chain_contexts.json'

HEAD, END = "<head>", "<end>"

# The names this has been audited for, and the only ones it answers. The method is general
# and the table is built over the whole corpus so it can be read and extended, but it is only
# as good as the `one` answers feeding it, and those are not uniformly sound: «الحسين بن محمد
# الأشعري» resolves to a Hāshimī of Ibn ʿAbd al-Muṭṭalib's line who is no Ashʿarī at all,
# because a bad heading on his record outranks the correct name on al-Ashʿarī al-Qummī's. A
# context built on that would answer 40 chains with the wrong man, confidently.
#
# So a name is admitted only once somebody has read its contexts and the forms behind them.
# «أحمد بن محمد» was audited on 2026-10-18: five men are written that way, 2,015 explicit
# mentions in al-Kāfī alone, and the pair of neighbours predicts them at 99.5% leave-one-out.
AUDITED = {"احمد بن محمد"}
LEAST = 5        # explicit mentions a context needs before it may answer anything
PURE = 0.95      # how far they must agree
SHORTEST = 3     # the shortest bare name worth disambiguating, in folded words


def prefixes(folded):
    """Every shorter reading of a name that someone might write instead of it."""
    words = folded.split()
    return [" ".join(words[:n]) for n in range(SHORTEST, len(words))]


def steps(row):
    """The named steps of one chain, with each one's neighbours."""
    named = [m for m in row["mentions"] if m.get("kind") != "text"]
    for at, mention in enumerate(named):
        before = key(named[at - 1].get("text", "")) if at else HEAD
        after = key(named[at + 1].get("text", "")) if at + 1 < len(named) else END
        yield mention, before, after


def gather(path):
    """What each book writes out, and where it leaves the name bare.

    `said` counts the fuller forms by the man they name; `bare` counts the short readings
    waiting on an answer. Both are keyed by the book, the short name, and the neighbours.
    """
    said, bare = defaultdict(Counter), Counter()
    with open(path) as handle:
        for line in handle:
            row = json.loads(line)
            book = row.get("book", "")
            for mention, before, after in steps(row):
                folded = key(mention.get("text", ""))
                if not folded:
                    continue
                person = mention.get("person_id") or ""
                # Only a name a single rijālī heading claims outright may testify. A form we
                # chose between candidates, or answered from a convention, is our reading and
                # would only be telling us back what we already said.
                if person and mention.get("outcome") == "one":
                    for short in prefixes(folded):
                        said[(book, short, before, after)][person] += 1
                if len(folded.split()) >= SHORTEST:
                    bare[(book, folded, before, after)] += 1
    return said, bare


def settled(said, least=LEAST, pure=PURE):
    """The contexts a book answers plainly enough to go on, and the man each names."""
    answers = {}
    for where, people in said.items():
        total = sum(people.values())
        if total < least:
            continue
        person, count = people.most_common(1)[0]
        if count / total >= pure:
            answers[where] = (person, count, total)
    return answers


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--resolved", default=f'{TMP}/resolved.jsonl')
    parser.add_argument("--out", default=TABLE)
    parser.add_argument("--least", type=int, default=LEAST)
    parser.add_argument("--pure", type=float, default=PURE)
    parser.add_argument("--show", default="", help="print one folded name's contexts")
    parser.add_argument("--build", action="store_true", help="write the table")
    args = parser.parse_args()

    said, bare = gather(args.resolved)
    answers = settled(said, args.least, args.pure)

    if args.show:
        want = key(args.show)
        rows = sorted((w for w in said if w[1] == want),
                      key=lambda w: -sum(said[w].values()))
        print(f"\n  «{want}» — what each book writes out, by the company it keeps\n")
        for where in rows[:40]:
            book, _, before, after = where
            people = said[where]
            total = sum(people.values())
            waiting = bare.get(where, 0)
            verdict = "answers" if where in answers else "SPLIT — answers nothing"
            named = ", ".join(f"{p} ×{n}" for p, n in people.most_common(3))
            print(f"   {book[:22]:22s} «{before[:22]}» -> «{after[:22]}»")
            print(f"       {named}   of {total}   bare waiting: {waiting}   {verdict}")
        held = sum(bare[w] for w in bare if w[1] == want)
        answered = sum(bare[w] for w in bare if w[1] == want and w in answers)
        print(f"\n   bare mentions of this name: {held:,}; "
              f"a context answers {answered:,}, leaving {held - answered:,} unanswered")
        return

    # Where a book writes a name out often enough, its silence in one slot is itself a fact:
    # the book does disambiguate this name, it simply does not disambiguate it *here*. That
    # must not be filled in by a convention promoted from somewhere else, so the pairs are
    # recorded and the resolver refuses the blanket reading for them.
    spoken = Counter()
    for (book, short, _, _), people in said.items():
        spoken[(book, short)] += sum(people.values())
    disambiguated = sorted(f"{book}\t{short}" for (book, short), n in spoken.items()
                           if n >= args.least and short in AUDITED)

    kept, reach = {}, 0
    for where, (person, count, total) in answers.items():
        waiting = bare.get(where, 0)
        if not waiting or where[1] not in AUDITED:
            continue        # nothing waiting on it, or nobody has read it yet
        book, short, before, after = where
        kept["\t".join((book, short, before, after))] = {
            "person_id": person, "said": count, "of": total, "bare": waiting}
        reach += waiting
    held = sum(n for (book, short, _, _), n in bare.items()
               if f"{book}\t{short}" in set(disambiguated))
    print(f"  {len(said):,} contexts seen, {len(answers):,} settled, {len(kept):,} with a "
          f"bare name waiting on them")
    print(f"  {reach:,} bare mentions a book's own usage can answer")
    print(f"  {len(disambiguated):,} book-and-name pairs the book disambiguates for itself, "
          f"holding {held:,} bare mentions; {held - reach:,} of those it leaves unanswered")

    if args.build:
        write_json_atomic(args.out, {
            "note": ("Built by chain_contexts.py from the corpus's own fuller forms. "
                     "Keys are '<book>\\t<short name>\\t<name before>\\t<name after>'. "
                     "Do not hand-edit; rebuild it."),
            "least": args.least, "pure": args.pure, "contexts": kept,
            "disambiguated": disambiguated})
        print(f"  wrote {args.out}")


if __name__ == "__main__":
    main()
