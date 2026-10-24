"""The four ways a name nobody is filed under is looked up again, and what they must not do."""
import json
import os
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import chain_resolve as cr  # noqa: E402


def man(pid, *names, teachers=(), students=()):
    return {"person_id": pid, "books": ["najashi"], "headings_ar": list(names),
            "display_ar": names[0], "names_ar": list(names),
            "narrated_from": [{"name_ar": n} for n in teachers],
            "narrated_to": [{"name_ar": n} for n in students]}


def index(*people):
    with tempfile.NamedTemporaryFile("w", suffix=".json", delete=False) as handle:
        json.dump(list(people), handle, ensure_ascii=False)
    try:
        return cr.load_people(handle.name, cr.CLASSICAL)
    finally:
        os.unlink(handle.name)


def read(data, *texts):
    """The outcome and person for each name of one chain, as the resolver gives them."""
    mentions = []
    at = 0
    for text in texts:
        mentions.append({"text": text, "kind": "name", "start": at, "end": at + len(text)})
        at += len(text) + 5
    rows = cr.resolve_chain(mentions, data, "Al-Kāfi", {}, "")
    return [(row["outcome"], row.get("person_id", "")) for row in rows], rows


def test_the_definite_article_is_folded_but_not_off_a_short_word():
    """«القاسم بن يحيى» was out of reach of «قاسم بن يحيى» and the reverse. The floor is five
    letters: «الله» would become «له», and 842 steps were rescued at a floor of 1 as at 3."""
    data = index(man("n1", "القاسم بن يحيى"), man("n2", "عبد الله بن سنان"))
    assert read(data, "قاسم بن يحيى")[0] == [("one", "n1")]
    assert cr.loosen(["عبد", "الله", "الي", "القاسم"]) == "عبد الله الي قاسم"
    cr.RECALL.discard("article")
    try:
        assert read(index(man("n1", "القاسم بن يحيى")), "قاسم بن يحيى")[0] == [("none", "")]
    finally:
        cr.RECALL.add("article")


def test_ibn_spelled_out_is_ibn_as_a_particle():
    data = index(man("n1", "محمد بن الحسن الصفار"))
    assert read(data, "محمد ابن الحسن الصفار")[0] == [("one", "n1")]


def test_a_leading_relative_word_is_dropped_from_the_key_and_not_from_the_span():
    data = index(man("n1", "الحسن بن راشد"))
    outcomes, rows = read(data, "جده الحسن بن راشد")
    assert outcomes == [("one", "n1")]
    assert (rows[0]["start"], rows[0]["end"], rows[0]["text"]) == (0, len("جده الحسن بن راشد"), "جده الحسن بن راشد")


def test_a_relative_word_alone_stays_unresolved_and_a_kunya_is_not_read_as_one():
    """«أبي» opens men as often as it means a father: «أبي الصباح الكناني» is not the father of
    «الصباح الكناني», and a span that is nothing but a relative word names nobody."""
    data = index(man("n1", "الصباح الكناني"), man("n2", "جده"))
    assert read(data, "جده")[0] == [("none", "")]
    assert read(data, "ابي الصباح الكناني")[0] == [("none", "")]


def test_trailing_nisbas_come_off_the_chains_name_and_a_generation_never_does():
    data = index(man("n1", "سعد بن عبد الله بن أبي خلف"), man("n2", "أحمد بن محمد بن عيسى"))
    assert read(data, "سعد بن عبد الله بن أبي خلف الأشعري")[0] == [("one", "n1")]
    # «أحمد بن محمد الأشعري» has one nisba to lose and then nothing but a «بن»: the books
    # file only Ibn ʿĪsā, and dropping a generation is how five men become one.
    assert read(data, "احمد بن محمد بن خالد البرقي")[0] == [("none", "")]
    assert list(cr.trims("سعد بن عبد الله بن ابي خلف الاشعري".split())) == [
        "سعد بن عبد الله بن ابي خلف".split()]


def test_a_widened_key_that_finds_several_men_is_several():
    data = index(man("n1", "القاسم بن يحيى"), man("n2", "قاسم بن يحيى"))
    outcomes, rows = read(data, "القاسم بن يحيى الكوفي")
    assert outcomes == [("several", "")]
    assert sorted(rows[0]["candidates"]) == ["n1", "n2"]


def test_a_name_the_exact_key_finds_is_untouched():
    data = index(man("n1", "قاسم بن يحيى"), man("n2", "القاسم بن يحيى بن الحسن"))
    assert read(data, "قاسم بن يحيى")[0] == [("one", "n1")]


def test_an_imams_name_is_not_widened_to_a_narrator():
    data = index(man("n1", "موسى بن جعفر البغدادي"))
    assert read(data, "ابيه موسى بن جعفر")[0] == [("none", "")]
