"""Every check on the chain data that can be made without a human reading a chain.

The chain pipeline has been corrected twice by sampling — somebody reads a narration,
sees a name pointing at the wrong man, and a rule is fixed. Sampling finds the bug it
happens to land on. This reads all 26,616 chains and asks the same questions of every
one of them, so that «how far from right is this» is a number rather than an impression.

Nothing here is a judgement about a man. Every check is a contradiction: the data says
two things that cannot both be true, or it says something the chain text plainly does
not. A count going up is a regression; a count going down is the only evidence that a
fix was a fix.

Run it after chain_resolve.py and before loading anything into Elasticsearch:

    python3 chain_check.py              # the counts, and a few examples of each
    python3 chain_check.py --show 25    # more examples
    python3 chain_check.py --only span  # one check at a time
"""
import argparse
from collections import Counter, defaultdict
import json
import os
import sys

W = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from chain_mentions import BLESSINGS, FUNCTION, JOINERS, OPENERS, bare  # noqa: E402
from chain_resolve import COMPILER_DIED, GLUE, RELATIVE, key  # noqa: E402
from imams import ROSTER  # noqa: E402

TMP = f'{W}/tmp/narrators_chains'
CHAINS = f'{TMP}/chains_served.jsonl'
RESOLVED = f'{TMP}/resolved.jsonl'
PEOPLE = f'{TMP}/people_index.json'

# The outcomes that put a link on the page. A mention with any other outcome is drawn as
# plain text, so a wrong person behind it costs nothing; these are the ones that must hold.
LINKED = ("one", "chosen", "convention")
# Kinds whose text is not meant to be the person's name: a convention stands in for him.
BY_CONVENTION = ("relative", "backref_man", "backref_chain", "collective")

# What can stand between two names. A relation makes the second the first one's shaykh;
# «و» and «جميعا» make them co-narrators, two men at the same step who heard it from the
# same man below, and reading those as a pair invents a teacher. Al-Ṣadūq writes whole
# chains that way — «عن أبي؛ و محمد بن الحسن بن أحمد بن الوليد عن سعد بن عبد الله؛ و عبد
# الله بن جعفر الحميري جميعا عن يعقوب بن يزيد» is three steps, not six.
RELATION = {"عن", "وعن", "فعن", "عنه", "وعنه", "عمن", "حدثنا", "وحدثنا", "حدثني", "وحدثني",
            "اخبرنا", "واخبرنا", "اخبرني", "سمعت", "سمعته", "رفعه", "يرفعه", "قال",
            "روى", "وروى", "رواه", "باسناده", "عنهما", "عنهم"}
BESIDE = {"و", "جميعا", "معا", "كلهم", "جميعهم", "قالا", "قالوا"}
# «و رويته عن …» starts the chain again by another route. The last man of the first
# ṭarīq and the first of the second never met; they are not a pair of any kind.
RESTART = {"رويته", "ورويته", "رويت", "وقد", "وفي"}

# A name only tells two people apart if it belongs to few of them. «احمد بن محمد» is
# filed under 20 different men, «ابو عبد الله» under dozens, so finding that string in some
# man's list of teachers says nothing about which Aḥmad b. Muḥammad is meant, and a chain
# that is right was flagged against a stranger. A form counts as evidence of identity when
# no more than this many person records hold it, or when it runs to this many words, since
# a nasab that long names one man far more often than not.
MAX_HOLDERS = 3
SPECIFIC_WORDS = 4

# How far apart two men's Imams may be, in years, before no single life could span both.
# The rijāl books say which Imams a man met, not how long he went on living, so «companion
# of al-Ṣādiq» is a floor under when he was active and never a ceiling over it. Only a gap
# longer than a human lifespan is a contradiction; the value is chosen on the table that
# `--sensitivity` prints.
LIFESPAN_GAP = 120
# ordinal → the year that Imam died. The twelfth has none: he did not die within the record.
IMAM_DIED = {imam["ordinal"]: imam["died"] for imam in ROSTER
             if imam.get("ordinal") is not None and imam.get("died") is not None}


def people():
    """person_id → the names the books file him under, folded, and who he narrated from."""
    with open(PEOPLE) as handle:
        rows = json.load(handle)
    names, teachers, students, display, tabaqa, died = {}, {}, {}, {}, {}, {}
    for person in rows:
        pid = person["person_id"]
        display[pid] = person.get("display_ar") or pid
        years = [y.get("value") for y in (person.get("death_years") or [])]
        years = [y for y in years if isinstance(y, int)]
        if years:
            died[pid] = min(years)
        # Which Imams the books place him with, 0 for the Prophet through 12. Half the
        # men here have it, and it dates a man far better than a death year does: only
        # 133 of the 15,504 carry one of those.
        held_imams = [o for o in (person.get("imam_ordinals") or []) if isinstance(o, int)]
        if held_imams:
            tabaqa[pid] = (min(held_imams), max(held_imams))
        held = {key(n) for n in (person.get("names_ar") or [])}
        held |= {key(n) for n in (person.get("headings_ar") or [])}
        if person.get("display_ar"):
            held.add(key(person["display_ar"]))
        names[pid] = {n for n in held if n}
        teachers[pid] = {key(r.get("name_ar") or r.get("name") or "")
                         for field in ("narrated_from", "chain_from", "unsaid_from")
                         for r in person.get(field) or []} - {""}
        students[pid] = {key(r.get("name_ar") or r.get("name") or "")
                         for field in ("narrated_to", "chain_to", "unsaid_to")
                         for r in person.get(field) or []} - {""}
    return names, teachers, students, display, tabaqa, died


def holders_of(names):
    """folded name-form → how many person records hold it."""
    held = Counter()
    for forms in names.values():
        held.update(forms)
    return held


def discriminating(forms, holders):
    """The forms among these that identify a man rather than a kind of man."""
    return {f for f in forms
            if holders[f] <= MAX_HOLDERS or len(f.split()) >= SPECIFIC_WORDS}


def chains():
    """id → the Arabic chain as printed, for checking a span against the text it covers."""
    held = {}
    with open(CHAINS) as handle:
        for line in handle:
            row = json.loads(line)
            if row.get("chain_ar"):
                held[row["id"]] = row["chain_ar"]
    return held


def compilers():
    """id → when the man who wrote the book down died, where that year is settled."""
    held = {}
    with open(CHAINS) as handle:
        for line in handle:
            row = json.loads(line)
            limit = COMPILER_DIED.get(row.get("source") or "")
            if limit is not None:
                held[row["id"]] = limit
    return held


def his_name(folded, held):
    """Whether a chain's words are one of this man's names, shortened or entire.

    A chain shortens. Al-Ṣadūq writes «محمد بن الحسن» for «محمد بن الحسن بن أحمد بن
    الوليد» 621 times, and a shortened nasab is the same name, not another one — so the
    test is whether the words could be his, not whether a book spells him that way. A
    prefix of a name he is filed under is his; so is a subset of its words, which covers
    «محمد العطار» for «محمد بن يحيى أبو جعفر العطار القمي». Anything else is a man the
    chain does not name.
    """
    if folded in held:
        return True
    words = folded.split()
    for name in held:
        spelled = name.split()
        if spelled[:len(words)] == words or set(words) <= set(spelled):
            return True
    return False


def name_shaped(words):
    """Whether a run of words reads as a name — a nasab, or an opener with something after."""
    folded = [bare(w) for w in words if bare(w)]
    if not folded or any(w in FUNCTION for w in folded):
        return False
    if any(w in ("بن", "ابن") for w in folded):
        return True
    return len(folded) > 1 and folded[0] in OPENERS


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--show", type=int, default=4, help="examples to print per check")
    parser.add_argument("--only", default="", help="run one check by name")
    parser.add_argument("--sensitivity", action="store_true",
                        help="print how the two direction checks move with their settings")
    parser.add_argument("--resolved", default=RESOLVED,
                        help="check a candidate run before it replaces the live file")
    args = parser.parse_args()

    names, teachers, students, display, tabaqa, died = people()
    printed = chains()
    limits = compilers()
    holders = holders_of(names)
    # Kept only for --sensitivity: every pair's shared forms as (holders, words), and every
    # too-early candidate with its gap and whether the books confirm the pair by name.
    pair_log, gap_log = [], []

    found = Counter()
    examples = defaultdict(list)
    # The same words resolving to different men in different chains. Collected first and
    # judged at the end, because a disagreement is only visible across the whole corpus.
    resolves_to = defaultdict(Counter)

    def flag(check, chain_id, detail):
        found[check] += 1
        if len(examples[check]) < args.show:
            examples[check].append(f"{chain_id}  {detail}")

    with open(args.resolved) as handle:
        for line in handle:
            row = json.loads(line)
            chain_id = row["id"]
            text = printed.get(chain_id, "")
            steps = [m for m in row["mentions"] if m["kind"] != "text"]
            taken = []
            previous, previous_end = None, None

            if text and not steps:
                flag("empty", chain_id, "a chain with words in it and no step cut out of it")

            for mention in row["mentions"]:
                start, end = mention["start"], mention["end"]
                words = mention["text"].split()
                linked = mention["outcome"] in LINKED
                person = mention.get("person_id") or mention.get("imam_id") or ""

                # A span that does not cover what it says it covers would draw the link
                # over somebody else's words, or over nothing.
                if not (0 <= start < end <= len(text)) or text[start:end] != mention["text"]:
                    flag("span", chain_id, f"[{start}:{end}] «{mention['text']}»")

                if mention["kind"] == "text" and name_shaped(words):
                    flag("prose-is-a-name", chain_id,
                         f"read as prose, reads as a name: «{mention['text']}»")
                    continue
                if mention["kind"] == "text":
                    continue

                if linked:
                    for other in taken:
                        if start < other[1] and other[0] < end:
                            flag("overlap", chain_id, f"[{start}:{end}] over [{other[0]}:{other[1]}]")
                            break
                    taken.append((start, end))

                    folded = [bare(w) for w in words if bare(w)]
                    # A convention is a joiner that stands for a man on purpose — «عنه»
                    # is the shaykh above — so only an ordinary name is checked for one.
                    if (mention["kind"] not in BY_CONVENTION
                            and any(w in JOINERS for w in folded)):
                        flag("joiner-in-link", chain_id, f"«{mention['text']}»")
                    if any(w in BLESSINGS for w in folded):
                        flag("blessing-in-link", chain_id, f"«{mention['text']}»")
                    if any(w in FUNCTION for w in folded):
                        flag("prose-linked", chain_id, f"«{mention['text']}»")

                    # «عليه السلام» is said of an Imam and of nobody else in these books.
                    # A name the text salutes that way must not be drawn as a narrator,
                    # and must not keep a narrator id behind an Imam's answer.
                    if mention.get("saluted") and mention.get("imam_id") \
                            and mention.get("person_id"):
                        flag("imam-kept-a-narrator", chain_id,
                             f"«{mention['text']}» answers to an Imam and to "
                             f"{display.get(mention['person_id'], '')}")
                    # A name mention is linked because the words matched a name the books
                    # file him under. If they do not, the link came from somewhere else.
                    if mention["kind"] == "name" and person in names:
                        if not his_name(key(mention["text"]), names[person]):
                            flag("name-not-his", chain_id,
                                 f"«{mention['text']}» → {display.get(person, person)}")
                    if mention["kind"] not in BY_CONVENTION:
                        resolves_to[key(mention["text"])][person] += 1

                # Co-narrators are not a pair: nothing but «و» between two names means
                # they stand at the same step, so neither the self check nor the
                # direction check has anything to say about them.
                beside = restart = False
                if previous_end is not None:
                    between = [bare(w) for w in text[previous_end:start].split()]
                    between = [w for w in between if w]
                    if between and all(w in BESIDE for w in between):
                        beside = True
                    if any(w in RESTART for w in between):
                        restart = True
                if restart:
                    found["second-tariq"] += 1
                    previous, previous_end = None, end
                    continue
                if beside:
                    found["side-by-side"] += 1
                if person and previous and person == previous and not beside:
                    flag("self", chain_id, f"{display.get(person, person)} narrating from himself")
                # The one fact about an isnād that needs no rijālī to adjudicate it: a book
                # cannot name a man who outlived the author who wrote it down. Where both
                # years are known this is arithmetic, and it answers over the books' own
                # relation lists, over our conventions and over an audit's agreement.
                limit = limits.get(chain_id)
                if person and limit is not None and died.get(person, 0) > limit:
                    flag("outlived-the-compiler", chain_id,
                         f"{display.get(person, person)} d.{died[person]}, "
                         f"after the compiler d.{limit}"
                         + (f" — {mention['because']}" if mention.get("because") else ""))
                # The books' own word on the direction. A chain says A heard it from B; if
                # every book that mentions the pair says B heard it from A, one of them is
                # wrong, and it is worth a reader's eye either way.
                # «A عن B» makes B the teacher and A the student. The books are asked the
                # same question: do they have A among B's students, or among his teachers?
                # Both, or neither, says nothing. Only the second alone is a contradiction.
                # A chain says A heard it from B, so A belongs to B's generation or a
                # later one. Where the books place both men with Imams, a student whose
                # latest Imam is two or more before his teacher's earliest cannot have
                # met him: he was of an age with the teacher's grandfather. This is
                # evidence apart from the teacher and student lists, so where the two
                # agree the pair is wrong, and where they disagree the lists are.
                # The Imams a man met are a floor under when he lived, not a ceiling over
                # it. A rijāl book that files a man with al-Ṣādiq says he was of age by
                # al-Ṣādiq's time; it does not say he died with him, and a long-lived
                # companion went on transmitting for forty years more. Reading his latest
                # Imam as the end of his life flagged him against every later shaykh, and
                # when the ordinals were re-derived more carefully the count went up by 249
                # — better data, more flags, which is what a wrong rule looks like. So the
                # test is in years: the shaykh's earliest Imam died so long after the
                # student's latest that nobody could have lived across both. Anything
                # shorter is a long life and not a contradiction.
                if person and previous and not beside:
                    student, teacher = tabaqa.get(previous), tabaqa.get(person)
                    late, early = IMAM_DIED.get(student[1]) if student else None, \
                        IMAM_DIED.get(teacher[0]) if teacher else None
                    if late is not None and early is not None and early - late > LIFESPAN_GAP:
                        flag("too-early-for-his-shaykh", chain_id,
                             f"{display.get(previous, previous)} (Imams {student[0]}–{student[1]},"
                             f" last d.{late}) ← {display.get(person, person)}"
                             f" (Imams {teacher[0]}–{teacher[1]}, first d.{early})")
                    if args.sensitivity and late is not None and early is not None \
                            and early - late > 0:
                        confirmed = bool(discriminating(names.get(previous, set()), holders)
                                         & students.get(person, set()))
                        gap_log.append((early - late, confirmed))
                if (person and previous and not beside
                        and person in students and previous in names):
                    # Both sides are held to the same standard: a generic form must not
                    # contradict a pair any more than it may confirm one.
                    shared = discriminating(names[previous], holders)
                    as_student = shared & students[person]
                    as_teacher = shared & teachers[person]
                    if args.sensitivity:
                        pair_log.append((
                            [(holders[f], len(f.split())) for f in names[previous] & students[person]],
                            [(holders[f], len(f.split())) for f in names[previous] & teachers[person]]))
                    if as_student and not as_teacher:
                        found["direction-confirmed"] += 1
                    elif as_teacher and not as_student:
                        flag("reversed", chain_id,
                             f"{display.get(previous, previous)} ← {display.get(person, person)},"
                             f" but the books have that pair the other way")
                # Every step moves the cursor, named or not. Skipping an unidentified man
                # would pair the two names on either side of him, who never met: three of
                # the four pairs this check flagged were invented that way.
                previous = person or None
                previous_end = end

            if row.get("head_lost"):
                flag("head-lost", chain_id, "opens with a reporting verb whose name got away")
            if row.get("quoted") and any(m["kind"].startswith("backref") for m in row["mentions"]):
                found["quoted-pointer"] += 1

    for folded, who in resolves_to.items():
        if len(who) > 1:
            found["same-words-different-men"] += 1
            if len(examples["same-words-different-men"]) < args.show:
                shown = ", ".join(f"{display.get(p, p)} ×{n}" for p, n in who.most_common(3))
                examples["same-words-different-men"].append(f"«{folded}» → {shown}")

    order = ["direction-confirmed", "side-by-side", "second-tariq",
             "span", "overlap", "name-not-his", "imam-kept-a-narrator",
             "reversed", "too-early-for-his-shaykh", "outlived-the-compiler", "self",
             "prose-linked",
             "joiner-in-link", "blessing-in-link", "prose-is-a-name",
             "same-words-different-men", "head-lost", "quoted-pointer", "empty"]
    print(f"\n  {len(printed):,} chains read, {sum(found.values()):,} flags\n")
    if args.sensitivity:
        print("  too-early, by the gap in years between the two Imams' deaths")
        print("  gap>   flags  confirmed-by-a-discriminating-name  false-positive-rate")
        for limit in (0, 40, 60, 80, 100, 120, 150, 200):
            hit = [c for g, c in gap_log if g > limit]
            print(f"  {limit:4}  {len(hit):6}  {sum(hit):6}  {sum(hit) / max(len(hit), 1):7.1%}")
        print("\n  reversed / direction-confirmed, by what counts as a discriminating form")
        print("  holders<=  words>=  reversed  confirmed")
        for holders_max in (1, 2, 3, 5, 10, 10 ** 9):
            for words_min in (3, 4, 5, 10 ** 9):
                rev = con = 0
                for st, te in pair_log:
                    a = any(h <= holders_max or w >= words_min for h, w in st)
                    b = any(h <= holders_max or w >= words_min for h, w in te)
                    con += a and not b
                    rev += b and not a
                print(f"  {holders_max if holders_max < 10 ** 9 else 'any':>9}  "
                      f"{words_min if words_min < 10 ** 9 else 'never':>7}  {rev:8}  {con:9}")
    for check in order:
        if args.only and args.only != check:
            continue
        print(f"  {found[check]:7,}  {check}")
        for line in examples[check]:
            print(f"             {line}")


if __name__ == "__main__":
    main()
