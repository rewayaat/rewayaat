"""What the served chains add to a man's isnād lists, and how each row is counted.

Two rules are under test, and they differ because the evidence differs. A pairing a rijālī
index already prints is counted whichever way round the corpus has it and however few
times — the index is the support. A pairing no index names is counted only in the
direction the chains give it, and only once the corpus repeats it.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import isnad_neighbours  # noqa: E402
from index_people import attach_served  # noqa: E402


def table_for(*narrations):
    """Build the neighbour table off a stand-in corpus: each narration is a chain of ids."""
    def served(host, index):
        for chain in narrations:
            yield [{"s": n, "p": who} for n, who in enumerate(chain)]
    reading = isnad_neighbours._chains
    isnad_neighbours._chains = served
    try:
        table, _ = isnad_neighbours.build("http://es")
    finally:
        isnad_neighbours._chains = reading
    return table


def book_row(name, pid, book="Khoei"):
    return {"name": name, "name_ar": name, "person_id": pid, "slug": pid,
            "books": [book], "books_ar": [book], "kind": "chain",
            "stated": False, "unsaid": False}


def man(pid):
    return {"person_id": pid, "slug": pid, "display_en": pid, "display_ar": pid}


def test_beside_holds_a_pairing_the_corpus_has_only_once():
    table = table_for(("a", "b"))
    assert table["a"]["after"] == {}          # the floor holds for the direction
    assert table["a"]["beside"] == {"b": 1}   # and not for the pairing itself


def test_beside_counts_both_directions_as_one_pairing():
    table = table_for(("a", "b"), ("a", "b"), ("b", "a"))
    assert table["a"]["after"] == {"b": 2}
    assert table["a"]["before"] == {}        # the one chain the other way is under the floor
    assert table["a"]["beside"] == {"b": 3}  # three chains stand them together, all the same


def test_the_imams_are_left_out_of_beside_too():
    table = table_for(("a", "i06"), ("a", "i06"), ("a", "b"))
    assert table["a"]["beside"] == {"b": 1}
    assert "i06" not in table["a"]["after"]


def test_a_book_row_takes_the_count_the_other_way_round():
    """The books put ʿAlī b. al-Ḥasan above Aḥmad; the corpus has him below, 51 times."""
    table = table_for(*[("a", "b")] * 51)
    doc = {"person_id": "b", "chain_from": [book_row("A", "a")], "chain_to": []}
    marked, placed = attach_served(doc, table["b"], {"a": man("a")})
    assert (marked, placed) == (1, 0)
    assert doc["chain_from"][0]["narrations"] == 51
    assert doc["chain_to"] == []              # not printed a second time


def test_a_book_row_takes_a_single_narration():
    table = table_for(("a", "b"))
    doc = {"person_id": "a", "chain_to": [book_row("B", "b")], "chain_from": []}
    marked, placed = attach_served(doc, table["a"], {"b": man("b")})
    assert (marked, placed) == (1, 0)
    assert doc["chain_to"][0]["narrations"] == 1


def test_a_man_no_book_names_still_needs_the_corpus_to_repeat_him():
    table = table_for(("a", "b"), ("a", "c"), ("a", "c"))
    doc = {"person_id": "a", "chain_from": [], "chain_to": []}
    marked, placed = attach_served(doc, table["a"], {"b": man("b"), "c": man("c")})
    assert (marked, placed) == (0, 1)
    assert [(r["person_id"], r["narrations"]) for r in doc["chain_to"]] == [("c", 2)]


def test_a_man_only_a_book_names_keeps_no_count_at_all():
    table = table_for(("a", "b"), ("a", "b"))
    doc = {"person_id": "a", "chain_from": [book_row("Z", "z")], "chain_to": []}
    marked, placed = attach_served(doc, table["a"], {"b": man("b"), "z": man("z")})
    assert "narrations" not in doc["chain_from"][0]
    assert placed == 1 and marked == 0


def test_the_headline_count_is_taken_again_after_the_corpus_rows_land():
    table = table_for(("a", "b"), ("a", "b"))
    doc = {"person_id": "a", "chain_from": [book_row("Z", "z")], "chain_to": [],
           "chain_only_count": 1}
    attach_served(doc, table["a"], {"b": man("b"), "z": man("z")})
    assert doc["chain_only_count"] == 2


def test_a_man_a_book_places_elsewhere_is_not_added_to_the_chains():
    """He is already named as a teacher; the isnād list does not print him again."""
    table = table_for(("b", "a"), ("b", "a"))
    doc = {"person_id": "a", "narrated_from": [book_row("B", "b")],
           "chain_from": [], "chain_to": []}
    marked, placed = attach_served(doc, table["a"], {"b": man("b")})
    assert (marked, placed) == (0, 0)
    assert doc["chain_from"] == [] and doc["chain_to"] == []
