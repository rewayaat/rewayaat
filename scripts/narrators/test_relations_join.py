"""One man written two ways is one row; two men are two."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from relations_join import arabic_key, join, join_rows, lookup, whose  # noqa: E402


def person(pid, display_ar, headings=(), names_ar=(), names_en=(), display_en=None, slug=None):
    return {"person_id": pid, "slug": slug or pid, "display_ar": display_ar,
            "headings_ar": list(headings), "names_ar": list(names_ar),
            "names_en": list(names_en), "display_en": display_en}


def row(name, book, name_ar=None):
    return {"name": name, "name_ar": name_ar, "kind": "person",
            "books": [book], "books_ar": [book], "stated": True, "unsaid": False}


PEOPLE = [
    person("n1", "هارون بن مسلم", names_en=["Harun ibn Muslim"], display_en="Harun ibn Muslim"),
    person("n2", "هلال الحفار", names_en=["Hilal al-Haffar"], display_en="Hilal al-Haffar"),
    person("n3", "أحمد بن محمد بن أبي نصر", names_ar=["البزنطي"],
           names_en=["Ibn Abi Nasr", "al-Bazanti"], display_en="Ibn Abi Nasr"),
    person("n4", "محمد بن عيسى بن زياد"),
    person("n5", "محمد بن عيسى بن عبيد"),
]
TABLES = lookup(PEOPLE)
SLUGS = {p["person_id"]: p["slug"] for p in PEOPLE}


def test_ibn_and_bin_are_one_word():
    assert arabic_key("هارون ابن مسلم") == arabic_key("هارون بن مسلم")


def test_a_name_and_its_transliteration_reach_one_man():
    assert whose(row("هلال الحفار", "A"), TABLES) == "n2"
    assert whose(row("Hilal al-Haffar", "B"), TABLES) == "n2"


def test_rows_in_two_scripts_become_one_row_with_both_books():
    joined = join_rows([row("هلال الحفار", "Al-Fihrist"),
                        row("Hilal al-Haffar", "Jāmiʿ al-Ruwāt")], TABLES, SLUGS)
    assert len(joined) == 1
    assert joined[0]["books"] == ["Al-Fihrist", "Jāmiʿ al-Ruwāt"]
    assert joined[0]["person_id"] == "n2"


def test_the_arabic_spelling_wins_the_display():
    joined = join_rows([row("Hilal al-Haffar", "B"), row("هلال الحفار", "A")], TABLES, SLUGS)
    assert joined[0]["name"] == "هلال الحفار"


def test_a_kunya_and_a_nisba_of_one_man_join():
    joined = join_rows([row("Ibn Abi Nasr", "A"), row("al-Bazanti", "B")], TABLES, SLUGS)
    assert len(joined) == 1
    assert joined[0]["books"] == ["A", "B"]


def test_two_men_sharing_the_opening_of_a_name_stay_apart():
    # «محمد بن عيسى» alone answers to both, so it names neither and nothing is merged.
    rows = [row("محمد بن عيسى بن زياد", "A"), row("محمد بن عيسى بن عبيد", "B")]
    assert len(join_rows(rows, TABLES, SLUGS)) == 2


def test_a_name_no_one_answers_to_is_left_alone():
    rows = [row("فلان بن فلان", "A"), row("علان بن علان", "B")]
    joined = join_rows(rows, TABLES, SLUGS)
    assert len(joined) == 2
    assert "person_id" not in joined[0]


def test_an_ambiguous_name_is_given_to_nobody():
    # «محمد بن عيسى» is the opening of two men's names; neither may claim it.
    assert whose(row("محمد بن عيسى", "A"), TABLES) is None


def test_a_resolved_row_carries_the_link_even_when_nothing_merged():
    joined = join_rows([row("هارون ابن مسلم", "A")], TABLES, SLUGS)
    assert joined[0]["person_id"] == "n1"
    assert joined[0]["slug"] == "n1"


def test_the_order_the_books_print_is_kept():
    rows = [row("هلال الحفار", "A"), row("هارون بن مسلم", "B"), row("Hilal al-Haffar", "C")]
    joined = join_rows(rows, TABLES, SLUGS)
    assert [r["name"] for r in joined] == ["هلال الحفار", "هارون بن مسلم"]


def test_the_arabic_is_carried_over_from_whichever_row_has_it():
    joined = join_rows([row("Hilal al-Haffar", "A"),
                        row("Hilal al-Haffar", "B", name_ar="هلال الحفار")], TABLES, SLUGS)
    assert joined[0]["name_ar"] == "هلال الحفار"


def test_join_reports_what_it_removed_and_leaves_other_fields_alone():
    doc = {"person_id": "x", "slug": "x", "display_ar": "س",
           "narrated_to": [row("هلال الحفار", "A"), row("Hilal al-Haffar", "B")],
           "chain_to": [row("هارون بن مسلم", "A")],
           "blurb": "untouched"}
    removed = join(PEOPLE + [doc])
    assert removed == 1
    assert len(doc["narrated_to"]) == 1
    assert doc["blurb"] == "untouched"


def test_the_two_halves_of_a_relation_are_never_mixed():
    # The same man appearing as a teacher and as a student stays in both lists.
    doc = {"person_id": "x", "slug": "x",
           "narrated_from": [row("هلال الحفار", "A")],
           "narrated_to": [row("Hilal al-Haffar", "B")]}
    join(PEOPLE + [doc])
    assert len(doc["narrated_from"]) == 1 and len(doc["narrated_to"]) == 1


def test_the_same_name_spelled_twice_joins_even_when_the_man_is_unknown():
    # «فلان بن فلان» reaches nobody, but two rows carrying it are still one row. This is
    # how the grouping behaved before resolution was added, and it must not be lost.
    joined = join_rows([row("فلان بن فلان", "A"), row("فلان ابن فلان", "B")], TABLES, SLUGS)
    assert len(joined) == 1
    assert joined[0]["books"] == ["A", "B"]
    assert "person_id" not in joined[0]


def test_an_unresolved_arabic_row_never_joins_an_unresolved_latin_one():
    joined = join_rows([row("فلان بن فلان", "A"), row("Fulan ibn Fulan", "B")], TABLES, SLUGS)
    assert len(joined) == 2


def test_the_better_pointed_spelling_is_the_one_shown():
    # The unpointed spelling is the longer of the two, so "pick the longer" printed it.
    joined = join_rows([row("احمد ابن ابي عبد الله", "A"),
                        row("أحمد بن أبي عبد الله", "B")], TABLES, SLUGS)
    assert len(joined) == 1
    assert joined[0]["name"] == "أحمد بن أبي عبد الله"


def test_the_fuller_of_two_different_names_is_still_the_one_shown():
    joined = join_rows([row("سليم بن قيس", "A", name_ar="سليم بن قيس"),
                        row("سليم بن قيس الهلالي", "B", name_ar="سليم بن قيس الهلالي")],
                       TABLES, SLUGS)
    # They reach nobody here, so they stay apart — but the rule itself is what is asserted.
    from relations_join import _fuller
    assert _fuller({"name": "سليم بن قيس"}, {"name": "سليم بن قيس الهلالي"})["name"] \
        == "سليم بن قيس الهلالي"


DISPLAYS = {p["person_id"]: {"display_ar": p["display_ar"], "display_en": p["display_en"]}
            for p in PEOPLE}


def test_a_resolved_row_carries_the_mans_own_names_in_both_scripts():
    # The row keeps the name its book printed; the man's own names ride alongside it so a
    # page can read in its own script. Khoei's index is read in English, so without this an
    # Arabic page prints "Harun ibn Muslim" in the middle of an Arabic column.
    joined = join_rows([row("Harun ibn Muslim", "Jami' al-Ruwat")], TABLES, SLUGS, DISPLAYS)
    assert len(joined) == 1
    assert joined[0]["name"] == "Harun ibn Muslim"
    assert joined[0]["display_ar"] == "هارون بن مسلم"
    assert joined[0]["display_en"] == "Harun ibn Muslim"


def test_a_row_naming_nobody_is_given_no_display_names():
    # Nothing to borrow them from, and inventing them is how a page states more than the
    # books do. The row keeps its own name and nothing else.
    joined = join_rows([row("فلان بن فلان", "A")], TABLES, SLUGS, DISPLAYS)
    assert len(joined) == 1
    assert "display_ar" not in joined[0] and "display_en" not in joined[0]


def test_the_display_names_are_optional():
    # join_rows is called without them in the tests above and by anything that only wants
    # the regrouping; passing nothing must not fail or invent keys.
    joined = join_rows([row("Harun ibn Muslim", "A")], TABLES, SLUGS)
    assert joined[0].get("person_id") == "n1"
    assert "display_ar" not in joined[0]


def test_join_puts_the_display_names_on_every_document():
    docs = [dict(PEOPLE[0], narrated_to=[row("Harun ibn Muslim", "A")])] + PEOPLE[1:]
    join(docs)
    assert docs[0]["narrated_to"][0]["display_ar"] == "هارون بن مسلم"
