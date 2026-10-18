#!/usr/bin/env python3
"""Decides which man each name in an isnād is, and refuses to decide where the books do not.

The cutter (`chain_mentions.py`) gives spans of text. This gives them people, or says it
cannot. Four outcomes, and only the first two become a link:

- `one` — exactly one man in the rijāl books is filed under that name. Nothing to decide.
- `chosen` — several are, and one of them is attested transmitting with the name beside him in
  this very chain: «أحمد بن محمد» after «محمد بن يحيى» is the Aḥmad whom a book shows
  receiving from that Muḥammad. The reason is recorded with the link.
- `several` — several men hold the name and nothing in the books chooses between them. This is
  the honest answer for «أحمد بن محمد» standing alone: al-Bazanṭī, al-Barqī and Ibn ʿĪsā are
  all called that, and a reader is better served by the list than by our guess.
- `none` — no entry anywhere is filed under that name.

Two rules keep the candidate lists from filling with rubbish. Only men with an entry in a
classical book are targets: Khoei's Muʿjam indexes chains as well as men, so a Khoei-only
one-line record is usually the chain mention itself rather than a biography — eight of the
thirteen men holding the name «علي بن إبراهيم» are of that kind. And a name is matched in
the direction the isnād states it: for «X عن Y», Y is X's teacher, so the man before is his
student and the man after is his teacher. Matching without direction was what made the
relation gate necessary in the first place.

Usage:
    python3 scripts/narrators/chain_resolve.py --show 25
    python3 scripts/narrators/chain_resolve.py --out tmp/narrators_chains/resolved.jsonl
"""
import argparse
from collections import Counter, defaultdict
import json
import os
import re
import sys

W = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '..'))
sys.path.insert(0, f'{W}/scripts/narrators')
import imams  # noqa: E402
from narrator_schema import normalize_arabic  # noqa: E402

TMP = f'{W}/tmp'
CONVENTIONS = f'{W}/scripts/data/narrators/chain_conventions.json'
MENTIONS = f'{TMP}/narrators_chains/mentions.jsonl'
PEOPLE = f'{TMP}/narrators_chains/people_index.json'

# The six books that file men as men. Khoei and Māmaqānī index narrations too, so a record
# holding nothing but a chain clause is not a man anybody can be linked to.
CLASSICAL = {"tusi", "duafa", "najashi", "ardabili", "kashshi", "fihrist"}

_PUNCT = re.compile(r"[،؛؟.:()\[\]\"'«»/\\|]")
_PERSIAN = str.maketrans({"ی": "ي", "ک": "ك", "ۀ": "ه", "ە": "ه", "ي": "ي"})


def key(name):
    """One folded key for a name, whether it came from a chain or from a book's heading."""
    if not name:
        return ""
    return normalize_arabic(_PUNCT.sub(" ", name).translate(_PERSIAN))


def same_name(one, other):
    """Whether two keys name the same man: equal, or one the opening of the other.

    A chain writes «صفوان» where a book writes «صفوان بن يحيى»; the shorter is the opening of
    the longer, which is how Arabic names shorten. One token is allowed to stand for a longer
    name only when it is the whole of what the book itself recorded, so «محمد» never matches
    «محمد بن يحيى».
    """
    if not one or not other:
        return False
    if one == other:
        return True
    first, second = one.split(), other.split()
    short, long = (first, second) if len(first) < len(second) else (second, first)
    return len(short) >= 2 and long[:len(short)] == short


# «أبيه» names nobody. As a teacher's name it would hand a man to whichever candidate was
# listed first, and as a name in its own right it was pointing six chains at a Qummi shaykh
# who happens to carry «أبيه» among his aliases. It is refused in both places.
RELATIVE = {"ابيه", "ابيها", "عمه", "جده", "اخيه", "ابنه", "امه", "ابي", "جدي", "ابايه",
            "ابوه", "اخوه", "ابويه", "عمته", "خاله", "عميه", "ابائه", "اجداده"}


GLUE = {"بن", "ابن", "ابي", "ابو", "ابا", "عبد", "ال", "بنت", "ام"}
LINK = ("بن", "ابن")


def nasabs(person):
    """What each of a man's headings says his father was called, as token lists."""
    out = []
    for name in list(person.get("headings_ar") or []) + [person.get("display_ar") or ""]:
        tokens = key(name).split()
        if "بن" in tokens:
            out.append(tokens[tokens.index("بن") + 1:])
    return out


def generations(tokens):
    """A nasab as a list of generations, each the set of words naming it.

    «محمد بن عيسى بن عبيد اليقطيني» -> [{محمد}, {عيسى}, {عبيد, اليقطيني}]. Sets rather than
    first words, because one book writes a generation «إبراهيم» and another «أبو إسحاق
    إبراهيم», and those are the same man.
    """
    gens, current = [], []
    for token in tokens:
        if token in LINK:
            if current:
                gens.append(set(current))
            current = []
        else:
            current.append(token)
    if current:
        gens.append(set(current))
    return gens


def father_names(person):
    """Every reading of «his father» the man's own nasab supports, longest first.

    The rule here used to take the longest heading and everything after its first «بن»,
    which is the worst of both: the longest heading is the one most likely to carry the
    son's own kunya and nisba, and they ended up inside his father's name. «على بن إبراهيم
    بن هاشم القمي أبو الحسن» yielded «ابراهيم بن هاشم القمي ابو الحسن», whom nobody is
    filed under — so «عن أبيه» after ʿAlī b. Ibrāhīm, 3,745 of them, named nobody at all.

    Every heading contributes instead, and each contributes the prefixes of its remainder
    that drop only a trailing nisba or kunya. A cut before a «بن» is refused: dropping a
    generation does not shorten a name, it names somebody else.
    """
    found = []
    for rest in nasabs(person):
        for cut in range(len(rest), 0, -1):
            if cut < len(rest) and rest[cut] in LINK:
                continue
            candidate = rest[:cut]
            if candidate[-1] in ("بن", "ابن", "ابي", "ابو"):
                continue
            if len([t for t in candidate if t not in GLUE]) < 2:
                continue
            phrase = " ".join(candidate)
            if phrase not in found:
                found.append(phrase)
    found.sort(key=lambda phrase: -len(phrase.split()))
    return found


def could_be_father(person, candidate, father):
    """Whether this man can be the father, given what both their own nasabs say.

    Two ways he cannot be. He may *contradict* the son: the son's fullest nasab gives his
    father as «محمد بن عيسى بن عبد الله بن سعد …», and the man filed under the bare «محمد
    بن عيسى» is «… بن عبيد اليقطيني». They share no word at the third generation, so he is
    a different Muḥammad b. ʿĪsā and the link would have been confidently wrong.

    Or he may say *less* than the son's shortest reading requires: «علي بن محمد بن إبراهيم
    بن أبان» names a grandfather Ibrāhīm b. Abān, and a man filed under a bare «محمد بن
    إبراهيم» has not been shown to be him.
    """
    readings = [generations(rest) for rest in nasabs(person)]
    if not readings:
        return False
    if len(generations(candidate.split())) < min(len(r) for r in readings):
        return False
    longest = max(readings, key=len)
    # The son's nasab has to name his father with a grandfather behind him. «القاسم بن
    # محمد الجوهري» says only «his father is Muḥammad the jeweller», which cannot pick one
    # Muḥammad out of the books — and picking one gave him a father called al-Qāsim b. Yaḥyā.
    if len(longest) < 2:
        return False
    names = list(father.get("headings_ar") or []) + [father.get("display_ar") or ""]
    his = max((generations(key(name).split()) for name in names if name), key=len, default=[])
    if not his:
        return False
    # Only the first three generations are compared. They are where the discrimination is —
    # al-Yaqṭīnī parts from al-Ashʿarī at the third — and below that the books themselves
    # transpose names: al-Ḥimyarī's forebears Jāmiʿ and Mālik stand in one order in his
    # son's heading and the other in his own.
    depth = min(len(his), len(longest), 3)
    return all(his[i] & longest[i] for i in range(depth))


def load_people(path, books):
    """Name → the men filed under it, and each man's attested teachers, students and Imams.

    The names are kept in two tiers, because they are not equal evidence. A heading is what a
    rijālī filed the man under; an alias is any other name his entry mentions, and those lists
    carry names lifted out of chains quoted inside the entry. Isḥāq b. ʿAmmār holds «ابن أبي
    عمير» among his aliases, Zurāra holds «محمد بن مسلم», and the audit caught both of them
    being handed somebody else's name. So an alias is consulted only when no heading matches.
    """
    with open(path) as handle:
        people = json.load(handle)
    headings, aliases = defaultdict(set), defaultdict(set)
    teachers, students, by_id = {}, {}, {}
    imams_of, fathers, is_imam = {}, {}, {}
    for person in people:
        if books and not books & set(person.get("books") or []):
            continue
        pid = person["person_id"]
        by_id[pid] = person
        own = set(person.get("headings_ar") or [])
        if person.get("display_ar"):
            own.add(person["display_ar"])
        for name in own:
            folded = key(name)
            if folded and folded not in RELATIVE:
                headings[folded].add(pid)
        for name in person.get("names_ar") or []:
            folded = key(name)
            if folded and folded not in RELATIVE and folded not in headings:
                aliases[folded].add(pid)
        teachers[pid] = {key(r.get("name_ar") or r.get("name"))
                         for field in ("narrated_from", "chain_from", "unsaid_from")
                         for r in person.get(field) or []}
        students[pid] = {key(r.get("name_ar") or r.get("name"))
                         for field in ("narrated_to", "chain_to", "unsaid_to")
                         for r in person.get(field) or []}
        teachers[pid].discard("")
        students[pid].discard("")
        # A rijālī who catalogues the companions catalogues the Imams among them, so a few
        # of the fourteen hold narrator records: al-Ṭūsī files al-Riḍā, and a chain naming
        # him in ʿUyūn — the book written about him — was landing on a three-entry stub
        # instead of his own page. Recorded here and swapped at the end of a chain, rather
        # than read off the name: «علي» and «جعفر بن محمد» name an Imam in some chains and
        # an ordinary narrator in a great many others, and only the record settles it.
        found = imams.identify(key(person.get("display_ar") or ""))
        if len(found) == 1:
            is_imam[pid] = found[0]
        imams_of[pid] = set()
        for field in ("imams_from", "imams_to"):
            for relation in person.get(field) or []:
                name = relation.get("name_ar") or relation.get("name") or ""
                imams_of[pid] |= imams.from_relation(name, key(name))

    # Every shorter reading of a name, for the men the books connect to somebody. A rijālī
    # often files one man twice — al-Ṭūsī's one-line «الحسين بن محمد بن عمران» beside
    # al-Najāshī's «الحسين بن محمد بن عمران بن أبي بكر الأشعري القمي» — and the short filing,
    # having no relations recorded against it, is the one that owns the short name. This maps
    # a short name to the fuller records that spell it out, so a stub can be looked past.
    # Only men with a teacher or a student are indexed: the point is to find a record that
    # can be checked against the chain, and a second stub answers nothing.
    extends = defaultdict(set)
    for pid, person in by_id.items():
        if not (teachers.get(pid) or students.get(pid)):
            continue
        forms = set(person.get("headings_ar") or []) | set(person.get("names_ar") or [])
        if person.get("display_ar"):
            forms.add(person["display_ar"])
        for name in forms:
            words = key(name).split()
            for count in range(2, len(words)):
                extends[" ".join(words[:count])].add(pid)

    # Whose father is whom, read off the nasabs once the headings are all in. A second
    # pass because it needs the whole index: the father is identified by being filed under
    # a name the son's own heading spells out.
    for pid, person in by_id.items():
        for candidate in father_names(person):
            held = headings.get(candidate)
            if not held:
                continue
            fits = [who for who in sorted(held)
                    if who != pid and could_be_father(person, candidate, by_id[who])]
            if len(fits) == 1:
                fathers[pid] = fits[0]
            # Only the fullest reading anyone is filed under is tried. Falling through to
            # a shorter one would trade a father nobody holds for a namesake somebody does.
            break
    return {"headings": headings, "aliases": aliases, "teachers": teachers,
            "students": students, "by_id": by_id, "imams_of": imams_of, "fathers": fathers,
            "is_imam": is_imam, "extends": extends}


def held_by(folded, data):
    """The men filed under a name — by their own heading, or failing that by an alias."""
    if not folded:
        return []
    found = data["headings"].get(folded)
    if found:
        return sorted(found)
    return sorted(data["aliases"].get(folded, ()))


def by_heading(folded, data):
    """Whether a rijālī filed anybody under this name, or it is only quoted inside entries."""
    return bool(folded and data["headings"].get(folded))


def support(pid, before, after, teachers, students):
    """Why this candidate and not the others: a book shows him beside the name next to him."""
    if before in RELATIVE:
        before = ""
    if after in RELATIVE:
        after = ""
    for name in teachers.get(pid, ()):  # the man after him in the chain is his teacher
        if name not in RELATIVE and same_name(name, after):
            return f"narrated from {name}"
    for name in students.get(pid, ()):  # the man before him received from him
        if name not in RELATIVE and same_name(name, before):
            return f"narrated to {name}"
    return ""


def past_the_stub(folded, lone, before, after, data):
    """A fuller record of the same man, where the only one filed is a stub nobody connects.

    A single candidate is normally the end of the matter: one man holds the name, so he is
    the answer. But `one` is the one outcome that asks for no corroboration at all, and that
    is exactly wrong when the single record is a stub — a rijālī's one-line notice with not
    one teacher and not one student recorded against it. Nothing about such a record can be
    checked, and nothing about it was.

    «الحسين بن محمد» in al-Kāfī is the case that found this. 608 chains answered it with a
    three-entry Ṭūsī notice, «الحسين بن محمد بن عمران», holding no relations whatever, while
    al-Najāshī's record of the same man — «الحسين بن محمد بن عمران بن أبي بكر الأشعري القمي»
    — says in so many words that he narrated to al-Kulaynī, and the chains have him handing
    on to Muʿallā b. Muḥammad and ʿAbd Allāh b. ʿĀmir, both of them in his own list.

    So the stub is looked past, but only where the chain itself vouches for the alternative:
    a fuller record spelling out the same name, which a book puts beside the very man
    standing next to him here. One such record or none — two would be a question, not an
    answer.

    Three conditions keep this to a truncated filing and away from two men who merely share
    a name, each of them learnt from a reading this rule got wrong:

    - The name must be a nasab. «أبي بصير» is a kunya borne by Yaḥyā b. al-Qāsim al-Asadī
      and by Layth al-Murādī, two men the rijālīs argue over, and nothing about one record
      lacking relations settles which of them a chain means. Kunyas are refused outright.
    - The fuller record must spell out the stub's own fullest name, not merely the short key.
      «الحسين بن محمد بن عمران» opens al-Najāshī's «الحسين بن محمد بن عمران بن أبي بكر
      الأشعري القمي», so they are one man; «الحسين بن يزيد بن محمد النوفلي» is a different
      man an alias had filed under the same short form.
    - The stub must be the lone holder of the short name, which is the caller's condition.
    """
    if data["teachers"].get(lone) or data["students"].get(lone):
        return "", ""
    if "بن" not in folded.split():
        return "", ""
    stub = data["by_id"].get(lone) or {}
    forms = [key(n) for n in [stub.get("display_ar") or ""]
             + list(stub.get("headings_ar") or []) + list(stub.get("names_ar") or [])]
    # Three generations of nasab, «س بن ص بن ع», is enough to say two filings are one man.
    # Two is not: «محمد بن إسماعيل» is borne by al-Kulaynī's own shaykh al-Nīsābūrī and by
    # Ibn Bazīʿ a century before him, and a short record of the one must not be read as a
    # short record of the other. Any of the stub's own readings may carry the match, not
    # merely its longest — al-Ṭūsī files this man as «الحسين بن محمد بن عمران كوفي», and a
    # trailing nisba one book adds is not a reason to miss the nasab they share.
    # A man's ism does not change between two filings of him, so a reading that opens on a
    # different name is an alias the books attached in error, not a shorter form of him:
    # «إبراهيم بن عبد الله القاري» is filed with «عبيد الله بن عبد الله الدهقان» among his
    # names, and without this the one would have been read as a short form of the other.
    ism = key(stub.get("display_ar") or "").split()[:1]
    specific = [f for f in forms if len(f.split()) >= 5 and f.split()[:1] == ism]
    if not specific:
        return "", ""
    backed = []
    for pid in sorted(data["extends"].get(folded, ())):
        other = data["by_id"].get(pid) or {}
        if pid == lone or not any(spells_out(f, other) for f in specific):
            continue
        why = support(pid, before, after, data["teachers"], data["students"])
        if why:
            backed.append((pid, why))
    return backed[0] if len(backed) == 1 else ("", "")


def spells_out(fullest, person):
    """Whether this record's name carries the stub's fullest name word for word, and goes on."""
    want = fullest.split()
    for name in ([person.get("display_ar") or ""] + list(person.get("headings_ar") or [])):
        words = key(name).split()
        if len(words) > len(want) and words[:len(want)] == want:
            return True
    return False


def one_of(candidates, data):
    """A single man, or nothing: the holder index's answer when it is not ambiguous."""
    return candidates[0] if len(candidates) == 1 else ""


def contexts(path):
    """What each book's own fuller forms say about reading its bare ones.

    Built by `chain_contexts.py` from the corpus itself rather than written by hand: where
    al-Kāfī spells «أحمد بن محمد بن خالد» out in a slot 113 times and never spells Ibn ʿĪsā
    there, the bare name in that slot is al-Barqī on the book's own word. See that script for
    why this is narrow on purpose.
    """
    try:
        with open(path) as handle:
            table = json.load(handle)
    except FileNotFoundError:
        return {"contexts": {}, "disambiguated": set()}
    return {"contexts": table.get("contexts") or {},
            "disambiguated": set(table.get("disambiguated") or ())}


def neighbours(mentions):
    """Each step's neighbours among the named steps, the way chain_contexts.py counts them.

    Only named steps, because that is what the table was built over; reading them differently
    here would look the answers up under keys nobody filed them under.
    """
    named = [at for at, m in enumerate(mentions) if m.get("kind") != "text"]
    found = {}
    for place, at in enumerate(named):
        before = key(mentions[named[place - 1]]["text"]) if place else "<head>"
        after = (key(mentions[named[place + 1]]["text"])
                 if place + 1 < len(named) else "<end>")
        found[at] = (before, after)
    return found


def conventions(path):
    """The chain conventions the auditors named, as the resolver reads them.

    A convention is not a fifth kind of evidence smuggled in: it is what the rijālī literature
    says about reading a particular author's isnāds — «محمد بن يحيى» opening a chain in al-Kāfī
    is al-ʿAṭṭār — written down only where the auditors agreed and the man they named resolves
    to one person. Where the file is absent the resolver simply refuses those names as before.
    """
    try:
        with open(path) as handle:
            table = json.load(handle)
    except FileNotFoundError:
        return {"settles": {}, "corrects": {}, "everywhere": {}}
    return table


def by_convention(table, book, folded):
    """The man a book's own practice names, the reason to show, and how many said so."""
    row = (table.get("settles") or {}).get(f"{book}\t{folded}")
    if row and row.get("applied", True):
        return row["person_id"], f"{book} reads this name as one man", row.get("voices", 1)
    person = (table.get("everywhere") or {}).get(folded)
    if person:
        return person, "the books read this name as one man", 99
    return "", "", 0


# When each compiler died, for the books in this corpus. A chain cannot name a man who
# outlived the author who wrote it down, so this is a hard limit on every link in a book —
# the one fact about an isnād that needs no rijālī to adjudicate it.
#
# It caught a convention we had promoted ourselves. «محمد بن الحسن» in al-Ṣadūq's books is
# Ibn al-Walīd, which eight auditors agreed on and which is right; the promotion rule then
# carried that reading into every book, and al-Kāfī's 113 occurrences of the name were
# answered with a man who died in 343 — fourteen years after al-Kulaynī, and who was
# al-Ṣadūq's own teacher, a generation downstream. In al-Kāfī the name is al-Ṣaffār's
# (d. 290) or Ibn Shamūn's (d. 258); the chains cannot say which, so it now says neither.
# This is the same failure the kunya promotion made and the same guard answers it.
#
# Only compilers whose death year is settled and uncontested are listed. Ḥusayn b. Saʿīd
# al-Ahwāzī and al-Ghaḍāʾirī are left out because theirs are not, and a limit we are unsure
# of would refuse good links: on a guessed 254 for al-Ahwāzī this check called Ibn Abī
# al-Khaṭṭāb (d. 262) impossible in Kitāb al-Zuhd, which he is not. Al-Muḥsinī is modern
# and constrains nobody.
COMPILER_DIED = {
    "Shaykh Muḥammad b. Yaʿqūb al-Kulaynī": 329,
    "Shaykh Muḥammad b. ʿAlī al-Ṣaduq": 381,
    "al-Sharīf al-Raḍī": 406,
    "Shaykh Muḥammad b. al-Ḥasan al-Ṭūsī": 460,
    "Shaykh Jaʿfar b. Muḥammad al-Qummī": 368,
    "Abū ʿAbd Allah Muḥammad b. Ibrāhīm al-Nuʿmānī": 360,
    "Shaykh Muḥammad b. Muḥammad al-Mufīd": 413,
}


def outlived(pid, compiler, data):
    """Whether this man died after the compiler did, so no chain of his can reach him.

    The earliest year any rijālī gives is the one tested, and a man with no recorded year
    is never refused: the point is to throw out the impossible, not to demand a date.
    """
    limit = COMPILER_DIED.get(compiler)
    if limit is None:
        return False
    years = [y.get("value") for y in (data["by_id"].get(pid, {}).get("death_years") or [])]
    years = [y for y in years if isinstance(y, int)]
    return bool(years) and min(years) > limit


# Whose father «حدثني أبي» is, at the head of a chain. The phrase is the compiler's own
# voice — it is first person, and it stands first — so the answer is a fact about the book,
# not about the chain: in al-Ṣadūq's books it is ʿAlī b. al-Ḥusayn b. Mūsā b. Bābawayh, and
# in Kāmil al-Ziyārāt it is Muḥammad b. Qūlūya. 1,461 chains open this way.
#
# Keyed by the compiler rather than the book, because Al-Amālī here is two different men's.
# Al-Mufīd and al-Ṭūsī are deliberately absent: their fathers are not narrators the books
# file, so «أبي» in their chains stays unnamed rather than being guessed at.
COMPILERS_FATHER = {
    "Shaykh Muḥammad b. ʿAlī al-Ṣaduq": "n000710",
    "Shaykh Jaʿfar b. Muḥammad al-Qummī": "n001996",
}


def resolve_chain(mentions, data, book="", table=None, compiler="", seen=None):
    """Give each mention in one chain an outcome, in three passes over the same chain.

    Names first, because the other two kinds are read off their neighbours: an Imam's kunya is
    settled by which Imams the books say the narrator beside him transmitted from, and «أبيه»
    is settled by whose father he is.
    """
    keys = [key(m["text"]) for m in mentions]
    seen = seen or {"contexts": {}, "disambiguated": set()}
    near = neighbours(mentions)
    rows = [dict(m) for m in mentions]
    table = table or {}
    # Whether each name was matched under somebody's own heading or only among the names
    # quoted inside entries. It decides whose word beats whose below, and is not published.
    filed = [by_heading(folded, data) for folded in keys]

    for position, row in enumerate(rows):
        if row["kind"] != "name":
            row["outcome"] = row["kind"]
            continue
        row["target"] = "narrator"
        candidates = held_by(keys[position], data)
        # Dating is evidence like any other, and the cheapest kind: a candidate who outlived
        # the compiler is not a reading of the name, so he is taken out before the others are
        # weighed. Where he was the only candidate the name goes unanswered, which is the
        # right answer — somebody holds that name and it is not a man this book could reach.
        candidates = [pid for pid in candidates if not outlived(pid, compiler, data)]
        before = keys[position - 1] if position else ""
        after = keys[position + 1] if position + 1 < len(keys) else ""
        if not candidates:
            row["outcome"] = "none"
        elif len(candidates) == 1:
            row["outcome"], row["person_id"] = "one", candidates[0]
            fuller, why = past_the_stub(keys[position], candidates[0], before, after, data)
            if fuller:
                row["outcome"], row["person_id"] = "chosen", fuller
                row["because"] = (f"{why}; the one man filed under this name alone is a "
                                  f"notice with no teacher and no student recorded")
        else:
            backed = [(pid, support(pid, before, after, data["teachers"], data["students"]))
                      for pid in candidates]
            backed = [(pid, why) for pid, why in backed if why]
            if len(backed) == 1:
                row["outcome"], row["person_id"], row["because"] = "chosen", *backed[0]
            else:
                row["outcome"], row["candidates"] = "several", candidates
                if backed:
                    row["backed"] = [pid for pid, _ in backed]

    for position, row in enumerate(rows):
        # A name the text salutes with «عليه السلام» is an Imam's, and the roster is
        # asked before the narrators are. «موسى بن جعفر عليهما السلام» was being matched
        # against the 15,518 and coming back as Mūsā b. Jaʿfar al-Baghdādī, because the
        # Imams were only looked for behind a kunya — «أبي عبد الله», «الصادق» — and never
        # behind a full name. The salutation is the text's own word for who this is.
        saluted = row.get("saluted") and imams.identify(keys[position])
        if row["kind"] != "imam" and not saluted and not (
                keys[position] in imams.CHAIN_FORMS
                and row.get("outcome") in ("several", "none")):
            continue
        found = imams.identify(keys[position])
        if not found:
            # saluted, but not one of the fourteen: «جبريل عليه السلام», «فاطمة عليها
            # السلام». A name mention keeps whatever the narrators made of it; only a
            # mention read as an Imam and answering to none is left with nobody.
            if row["kind"] == "imam":
                row["outcome"] = "none"
            continue
        row["target"] = "imam"
        # And it is read as an Imam from here on, not as a name that happens to answer
        # to one: the card draws an Imam differently, and asks the rijāl books nothing
        # about him.
        row["kind"] = "imam"
        row.pop("candidates", None)
        row.pop("backed", None)
        # Whatever the narrators made of it is withdrawn. The loader reads person_id
        # before imam_id, so leaving it would draw the link to Mūsā b. Jaʿfar
        # al-Baghdādī while the row said, correctly, that this is the seventh Imam.
        row.pop("person_id", None)
        if len(found) == 1:
            row["outcome"], row["imam_id"] = "one", found[0]
            continue
        narrower = set()
        for neighbour in (position - 1, position + 1):
            if 0 <= neighbour < len(rows):
                pid = rows[neighbour].get("person_id")
                if pid:
                    narrower |= data["imams_of"].get(pid, set())
        backed = [i for i in found if i in narrower]
        if len(backed) == 1:
            row["outcome"], row["imam_id"] = "chosen", backed[0]
            row["because"] = "the books file the narrator beside him with this Imam"
        else:
            row["outcome"], row["candidates"] = "several", list(found)

    for position, row in enumerate(rows):
        if row.get("kind") not in ("name", "imam"):
            continue
        if row["outcome"] in ("several", "none") or (
                row["outcome"] == "chosen" and row.get("kind") == "name"):
            # Where a convention and a `chosen` name different men, the audit has said three
            # times that the convention was right, and never the other way. A `chosen` rests
            # on one pairing found in one entry, and it rests on nothing at all when nobody is
            # filed under the name: «محمد بن مسلم» went to Zurāra three times because his own
            # entry quotes a chain holding it. So a convention takes a `chosen` when the name
            # is not anybody's heading, or when more than one auditor reached the same man —
            # «محمد بن يحيى» opening a chain in al-Kāfī, which fifteen of them named, was
            # going to al-Khazzāz, a Kufan of al-Ṣādiq's generation, in 160 of al-Kulaynī's
            # chains. A single voice does not outrank a heading match.
            # Three readings can speak here, and they do not rank equally.
            #
            # A `settles` entry is this book's own auditors on this book's own practice —
            # «محمد بن يحيى» opening a chain in al-Kāfī is al-ʿAṭṭār, which fifteen of them
            # named — and nothing below outranks that. An `everywhere` entry is a reading
            # promoted from the books that *did* vote into every book that did not, and for
            # «أحمد بن محمد» the voters were four books holding 125 occurrences between them
            # while al-Kāfī, holding 3,158, was never examined. That is the one a book's own
            # usage may overrule.
            #
            # So: the auditors first, then what the book writes out, and where the book
            # disambiguates this name for itself but not in this slot, its silence stands.
            # «عدة من أصحابنا عن أحمد بن محمد عن علي بن الحكم» is 19 Ibn ʿĪsā against 14
            # al-Barqī in al-Kāfī's own hand, and it is not ours to call.
            where = near.get(position)
            audited = f"{book}\t{keys[position]}" in (table.get("settles") or {})
            if where is not None and seen["contexts"] and not audited:
                found = seen["contexts"].get("\t".join((book, keys[position], *where)))
                if found:
                    row["outcome"] = "convention"
                    row["because"] = f"{book} writes this name out where it stands here"
                    row.pop("candidates", None)
                    row.pop("backed", None)
                    row.pop("imam_id", None)
                    row["target"] = "narrator"
                    row["person_id"] = found["person_id"]
                    continue
                if f"{book}\t{keys[position]}" in seen["disambiguated"]:
                    row["refused"] = "the book writes this name out, but not here"
                    continue
            person, why, voices = by_convention(table, book, keys[position])
            if row["outcome"] == "chosen" and filed[position] and voices < 2:
                continue
            # A convention is the auditors' reading of one author's practice, and the
            # promotion rule carries a reading seen in two books into all of them. Dating is
            # what tells it where it may not go: no agreement among readers can put a man in
            # a book written before he died. This is the gate that keeps al-Ṣadūq's Ibn
            # al-Walīd out of al-Kulaynī's chains.
            if person and outlived(person, compiler, data):
                row["refused"] = "the man outlived the compiler"
                continue
            if person and person != (row.get("person_id") or row.get("imam_id")):
                row["outcome"] = "convention"
                row["because"] = why
                row.pop("candidates", None)
                row.pop("backed", None)
                row["target"] = "imam" if person in imams.BY_ID else "narrator"
                row["imam_id" if person in imams.BY_ID else "person_id"] = person
            continue
        fix = (table.get("corrects") or {}).get(f"{book}\t{keys[position]}")
        if fix and fix.get("applied") and row["outcome"] in ("one", "chosen"):
            # the audit found this link wrong and named the man; a wrong link is worse than
            # a missing one, so the correction stands over our own match
            row["outcome"] = "convention"
            row["because"] = "the audit read this chain and named the man"
            row.pop("person_id", None)
            row.pop("imam_id", None)
            row["target"] = "imam" if fix["person_id"] in imams.BY_ID else "narrator"
            row["imam_id" if fix["person_id"] in imams.BY_ID else "person_id"] = fix["person_id"]

    for position, row in enumerate(rows):
        if row["kind"] != "relative":
            continue
        folded = keys[position]
        steps = 2 if folded in ("جده", "جدي") else 1
        if folded not in ("ابيه", "ابيها", "ابي", "جده", "جدي", "ابوه"):
            row["outcome"] = "relative"
            continue
        if position == 0 and folded == "ابي":
            # «حدثني أبي» with nobody before it: the compiler is speaking, so this is his
            # father and not anyone in the chain. Only at the head — further down, «أبي»
            # inside a chain the compiler is quoting belongs to whoever is quoted.
            father = COMPILERS_FATHER.get(compiler, "")
            if father and father in data["by_id"]:
                row["target"], row["outcome"], row["person_id"] = "narrator", "convention", father
                row["because"] = f"«أبي» at the head of a chain in a book by {compiler}"
            else:
                row["outcome"] = "relative"
            continue
        previous = rows[position - 1] if position else {}
        if previous.get("imam_id"):
            imam = previous["imam_id"]
            for _ in range(steps):
                imam = imams.FATHER_OF.get(imam, "")
            if imam:
                row["target"], row["outcome"], row["imam_id"] = "imam", "chosen", imam
                kin = "father" if steps == 1 else "grandfather"
                row["because"] = (f"the {kin} of "
                                  f"{imams.BY_ID[previous['imam_id']]['name_en']}")
                continue
        pid = previous.get("person_id")
        # «جده» is his father's father, so the same answer applied twice.
        for _ in range(steps):
            pid = data["fathers"].get(pid, "") if pid else ""
        if not pid:
            row["outcome"] = "relative"
            continue
        row["target"], row["outcome"], row["person_id"] = "narrator", "chosen", pid
        kin = "father" if steps == 1 else "grandfather"
        row["because"] = (f"the nasab of the man before him names his {kin} "
                          f"«{data['by_id'][pid].get('display_ar', '')}»")

    # Last, because any of the passes above can have landed on one: a record that is one of
    # the fourteen is not a narrator page, whatever the books filed it as.
    for row in rows:
        imam = data["is_imam"].get(row.get("person_id"))
        if imam:
            row.pop("person_id")
            row["target"], row["imam_id"] = "imam", imam

    return rows


ANAPHOR = ("backref_man", "backref_chain")
LINKED_OUTCOMES = ("one", "chosen", "convention")


def follow_backrefs(rows, by_id):
    """Settle the chains that open by pointing at the narration before them.

    «عنه» at the head of a chain is al-Kulaynī's own shorthand: *from him*, the shaykh the
    chain above opens with, not repeated. «وبهذا الإسناد» is the whole of that chain over
    again. Al-Khūʾī calls the form taʿlīq and reads it the same way — «سهل مبدوء به الكلام
    وتعليق على سابقه، فيكون الراوي عنه عدة من أصحابنا». Neither can be settled from inside
    its own chain, which is why this runs over the book in order rather than per chain.

    The sequence is keyed by compiler as well as by book: two different books here are
    called Kitāb al-Ghayba, al-Ṭūsī's and al-Nuʿmānī's, and reading one as the continuation
    of the other pointed a chain at a man in somebody else's work.

    A pointer resolves against the last chain that had a head of its own, so a run of them
    — «عنه» after «عنه» — all reach the same man, which is what the printed page means.

    Two books' worth of exceptions, because the form does not mean the same thing everywhere:

    A chain that is *quoted* answers to the book it came from, not to this one. Muḥsinī's
    Muʿjam al-Aḥādīth al-Muʿtabara prints «[231/2] الكافي:» and then al-Kāfī's isnād, and the
    «وعنه» in it is al-Kulaynī's pointer to whatever stands at al-Kāfī 2/231 — not to the
    narration Muḥsinī happens to have put above it, which is quoted from al-Kāfī 1/320 or
    from al-Tahdhīb. 22 of 26 pointers in that book crossed from one cited book into
    another before this guard; the chain they point at is not printed here at all, so they
    are left unanswered and the page says which book holds the answer.

    A chain whose own first man got away — «ما رواه سعد بن عبد الله عن جعفر بن محمد بن مالك»,
    where the cutter holds Jaʿfar and not Saʿd — must not serve as a head either, or «عنه»
    below it is answered with the second man of the chain above instead of the first.
    """
    def ordered(row):
        try:
            return int(re.sub(r"\D", "", row.get("number") or "") or -1)
        except ValueError:
            return -1

    books = defaultdict(list)
    for row in rows:
        books[(row["book"], row.get("source", ""), row.get("volume", ""))].append(row)

    settled = Counter()
    for sequence in books.values():
        sequence.sort(key=ordered)
        head, head_id, printed = "", "", ""
        for row in sequence:
            mentions = row["mentions"]
            pointer = mentions[0] if mentions and mentions[0]["kind"] in ANAPHOR else None
            if row.get("quoted"):
                # Quoted, so neither end of the pointer belongs to this book: it cannot be
                # answered from here, and it cannot answer for the chain below it.
                head, head_id, printed = "", "", ""
                if pointer is not None:
                    pointer["quoted_from"] = row["quoted"]
                    pointer["because"] = (f"quoted from «{row['quoted']}», and it points back "
                                          f"inside that book, not inside this one")
                    settled["pointing inside the book it is quoted from"] += 1
                continue
            if pointer is None:
                # An ordinary chain: its own first man is what the next pointer refers
                # back to. The first *man* — a collective is skipped, because «عنه» is
                # singular and «عدة من أصحابنا عن سهل» is picked up by «عنه» at Sahl. And
                # if that man is one no book identifies, the head is cleared rather than
                # left standing: a pointer must not reach over him to the chain before.
                printed = row["id"]
                head, head_id = "", ""
                if row.get("head_lost"):
                    # The chain is here and «بهذا الإسناد» may point at it, but we do not
                    # hold its first man, so «عنه» must not be answered from it.
                    settled["chain kept, but its first man got away"] += 1
                    continue
                for mention in mentions:
                    if mention["kind"] not in ("name", "imam", "relative"):
                        continue
                    who = mention.get("person_id") or mention.get("imam_id")
                    if who and mention["outcome"] in LINKED_OUTCOMES:
                        head, head_id = who, row["id"]
                    break
                continue
            if pointer["kind"] == "backref_chain":
                # The chain above, entire. Which narration that is does not depend on
                # whether we could name the men in it, so this is answered wherever there
                # is a printed chain to point at.
                if printed:
                    pointer["refers_to"] = printed
                    settled["«بهذا الإسناد» pointed at its chain"] += 1
                else:
                    settled["pointing at nothing we hold"] += 1
                continue
            if not head:
                settled["pointing at nothing we hold"] += 1
                continue
            pointer["refers_to"] = head_id
            if pointer["kind"] == "backref_man":
                pointer["outcome"] = "convention"
                pointer["target"] = "imam" if head in imams.BY_ID else "narrator"
                pointer["imam_id" if head in imams.BY_ID else "person_id"] = head
                name = (imams.BY_ID[head]["name_ar"] if head in imams.BY_ID
                        else by_id.get(head, {}).get("display_ar", ""))
                pointer["because"] = f"the chain before this one opens with «{name}»"
                settled["«عنه» given the man above"] += 1
            # the pointer inherits the head, so a run of them all reach the same man

    for label, count in settled.most_common():
        print(f"  {count:7,}  {label}")
    return settled


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--mentions", default=MENTIONS)
    parser.add_argument("--people", default=PEOPLE)
    parser.add_argument("--out", default=f'{TMP}/narrators_chains/resolved.jsonl')
    parser.add_argument("--conventions", default=CONVENTIONS)
    parser.add_argument("--contexts", default=f'{W}/scripts/data/narrators/chain_contexts.json')
    parser.add_argument("--all-books", action="store_true",
                        help="let Khoei-only and Māmaqānī-only records be link targets too")
    parser.add_argument("--show", type=int, default=0, help="print N of each outcome")
    parser.add_argument("--dry-run", action="store_true", help="measure, write nothing")
    args = parser.parse_args()

    books = set() if args.all_books else CLASSICAL
    data = load_people(args.people, books)
    table = conventions(args.conventions)
    seen = contexts(args.contexts)
    print(f"  {len(table.get('settles') or {}):7,}  names a book's own practice settles, "
          f"{len(table.get('everywhere') or {}):,} of them in every book")
    by_id = data["by_id"]
    print(f"  {len(by_id):7,}  men can be linked to"
          f"{'' if args.all_books else ' (an entry in a classical book)'}")
    print(f"  {len(data['headings']):7,}  names a book files one of them under, "
          f"{len(data['aliases']):,} more they are called elsewhere")

    outcomes, examples, chains = Counter(), defaultdict(list), 0
    by_kind, per_book = Counter(), defaultdict(Counter)
    rows = []
    with open(args.mentions) as handle:
        for line in handle:
            row = json.loads(line)
            chains += 1
            resolved = resolve_chain(row["mentions"], data, row["book"], table,
                                     row.get("source", ""), seen)
            for mention in resolved:
                outcomes[mention["outcome"]] += 1
                by_kind[(mention["kind"], mention["outcome"])] += 1
                per_book[row["book"]][mention["outcome"] in
                                     ("one", "chosen", "convention")] += 1
                pool = examples[mention["outcome"]]
                if len(pool) < max(args.show, 0):
                    pool.append((row["book"], mention))
            rows.append({"id": row["id"], "book": row["book"],
                         "source": row.get("source", ""),
                         "volume": row.get("volume", ""), "number": row.get("number", ""),
                         "quoted": row.get("quoted", ""),
                         "head_lost": row.get("head_lost", False),
                         "mentions": resolved})

    pointed = follow_backrefs(rows, by_id)

    names = sum(outcomes[k] for k in ("one", "chosen", "convention", "several", "none"))
    print(f"\n  {chains:7,}  chains, {sum(outcomes.values()):,} mentions, {names:,} of them names")
    for outcome in ("one", "chosen", "convention", "several", "none"):
        count = outcomes[outcome]
        print(f"      {count:7,}  {100 * count / names:5.1f}%  {outcome}")
    linked = outcomes["one"] + outcomes["chosen"] + outcomes["convention"]
    print(f"      {linked:7,}  {100 * linked / names:5.1f}%  become a link")
    for outcome in ("relative", "collective", "text"):
        print(f"      {outcomes[outcome]:7,}         {outcome} (not a link)")

    print("\n  by what the mention is:")
    for kind in ("name", "imam", "relative", "collective", "text"):
        held = sum(c for (k, _), c in by_kind.items() if k == kind)
        if not held:
            continue
        parts = " ".join(f"{o} {by_kind[(kind, o)]:,}"
                         for o in ("one", "chosen", "convention", "several", "none", kind)
                         if by_kind[(kind, o)])
        linked = (by_kind[(kind, "one")] + by_kind[(kind, "chosen")]
                  + by_kind[(kind, "convention")])
        print(f"      {kind:11s} {held:7,}  {100 * linked / held:5.1f}% linked   {parts}")

    print("\n  by book:")
    for book, counts in sorted(per_book.items(), key=lambda kv: -sum(kv[1].values())):
        held = sum(counts.values())
        print(f"      {counts[True]:7,}/{held:7,}  {100 * counts[True] / held:5.1f}%  {book}")

    for outcome in ("one", "chosen", "convention", "several", "none"):
        if not examples[outcome]:
            continue
        print(f"\n  {outcome}:")
        for book, mention in examples[outcome]:
            who = mention.get("person_id") or mention.get("imam_id", "")
            name = (by_id[who]["display_ar"] if who in by_id
                    else imams.BY_ID[who]["name_ar"] if who in imams.BY_ID else "")
            extra = mention.get("because", "") or f"{len(mention.get('candidates', []))} men"
            print(f"      «{mention['text'][:40]}» {book[:18]:18s} {who:8s} {name[:28]:28s} {extra}")

    if args.dry_run:
        return
    os.makedirs(os.path.dirname(args.out), exist_ok=True)
    with open(args.out, "w") as handle:
        for row in rows:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")
    print(f"\nwrote {len(rows):,} chains -> {args.out}")


if __name__ == "__main__":
    main()
