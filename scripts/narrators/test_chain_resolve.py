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


def test_a_kunya_set_as_one_word_is_read_as_two():
    """«أبوسعيد الادمي» in al-Khiṣāl is Sahl b. Ziyād, whose books write «أبو سعيد الآدمي»."""
    data = index(man("n1", "سهل بن زياد", "أبو سعيد الآدمي"), man("n2", "أبان بن عثمان"))
    assert read(data, "أبوسعيد الادمي")[0] == [("one", "n1")]
    assert read(data, "أبان بن عثمان")[0] == [("one", "n2")]
    # The relatives keep their pronoun: «أبويهما» is "their parents", nobody's kunya.
    assert cr.key("أبويهما") == "ابويهما"
    assert cr.key("أبيهما") == "ابيهما"
    assert cr.key("أبيه") == "ابيه"
    assert cr.key("أبوالحسن علي") == "ابو الحسن علي"
    # A kunya with nothing after it is left as written: alone it names the wrong man.
    assert cr.key("أبويزيد") == "ابويزيد"
    assert cr.key("أبوعبد الله") == "ابوعبد الله"



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


def imams_read(data, *texts):
    """The outcome and Imam for each step of one chain."""
    mentions = [{"text": t, "kind": "name", "start": 0, "end": len(t)} for t in texts]
    rows = cr.resolve_chain(mentions, data, "ʿUyūn akhbār al-Riḍā", {}, "")
    return [(row["outcome"], row.get("imam_id") or row.get("person_id", "")) for row in rows]


def test_a_father_and_a_son_in_two_links_are_the_imams():
    """The golden chain, written out with no blessing on any step. «موسى بن جعفر» alone went
    to the narrator of that name, and the five steps behind it were left with nobody."""
    data = index(man("n1", "موسى بن جعفر البغدادي", "موسى بن جعفر"))
    assert imams_read(data, "موسى بن جعفر", "ابيه جعفر بن محمد", "ابيه محمد بن علي",
                      "ابيه علي بن الحسين") == [
        ("chosen", "i07"), ("chosen", "i06"), ("chosen", "i05"), ("chosen", "i04")]


def test_one_full_name_on_its_own_is_still_the_narrator():
    """Nothing above may be read off a single link: it is the pair that settles it."""
    data = index(man("n1", "موسى بن جعفر البغدادي", "موسى بن جعفر"))
    assert imams_read(data, "موسى بن جعفر") == [("one", "n1")]


def test_a_title_with_a_full_name_needs_no_blessing():
    """«الصادق جعفر بن محمد» says the laqab and the nasab both, and no narrator answers to
    the pair; bare «جعفر بن محمد» is several men and is left alone."""
    data = index(man("n1", "جعفر بن محمد بن يونس", "جعفر بن محمد"))
    assert imams_read(data, "الصادق جعفر بن محمد") == [("one", "i06")]
    assert imams_read(data, "جعفر بن محمد") == [("one", "n1")]


def test_a_kunya_in_front_of_a_full_nasab_comes_off():
    """«الشيخ الجليل أبو جعفر محمد بن علي بن بابويه» is filed under the nasab alone."""
    data = index(man("n1", "محمد بن علي بن بابويه القمي"))
    assert read(data, "الشيخ الجليل أبو جعفر محمد بن علي بن بابويه القمي")[0] == [("one", "n1")]
    assert cr.unkunya("ابو القاسم جعفر بن محمد بن قولويه".split()) == (
        "جعفر بن محمد بن قولويه".split())


def test_a_kunya_that_is_the_whole_name_stays():
    """«أبي الصباح الكناني» and «أبي خديجة» are what the books file those men under; there is
    no nasab behind the kunya, so nothing comes off."""
    for name in ("ابي الصباح الكناني", "ابي خديجة", "ابي عبيدة الحذاء"):
        assert cr.unkunya(name.split()) == [], name


def test_a_heading_outranks_an_alias_whichever_form_reached_it():
    """Ibn Qūlawayh at the head of his own book: the trimmed form is among his aliases and
    the kunya-less form is a heading, and the alias used to answer first and then fail the
    gate an alias must pass."""
    data = index(man("n1", "جعفر بن محمد بن قولويه القمي"),
                 man("n2", "رجل آخر", teachers=()))
    data["aliases"]["ابو القاسم جعفر بن محمد"] = {"n2"}
    data["loose"]["aliases"]["ابو القاسم جعفر بن محمد"] = {"n2"}
    assert read(data, "ابو القاسم جعفر بن محمد بن قولويه القمي الفقيه")[0] == [("one", "n1")]


def filed(pid, heading, *aliases, teachers=(), students=()):
    """A man filed under one heading whose entry also mentions other names.

    `man` puts every name in both lists, which is the ordinary case. This separates them,
    because whether a name is a heading or an alias is the whole question below.
    """
    return {"person_id": pid, "books": ["najashi"], "headings_ar": [heading],
            "display_ar": heading, "names_ar": [heading] + list(aliases),
            "narrated_from": [{"name_ar": n} for n in teachers],
            "narrated_to": [{"name_ar": n} for n in students]}


def test_a_relationless_stub_is_looked_past_to_the_man_the_chain_attests():
    """«يونس» was the whole heading of a Ṭūsī notice with no relation recorded, and an alias
    of Yūnus b. ʿAbd al-Raḥmān, whose own student stands next to it here. 482 chains took
    the notice, which `held_by` reached because it stops at the headings."""
    data = index(filed("n1", "يونس"),
                 filed("n2", "يونس بن عبد الرحمن", "يونس", students=["محمد بن عيسى"]))
    outcomes, rows = read(data, "محمد بن عيسى", "يونس")
    assert outcomes[1] == ("chosen", "n2"), outcomes
    assert "no teacher and no student recorded" in rows[1]["because"]


def test_a_filed_man_the_books_connect_is_not_displaced_by_an_alias():
    """The guard that keeps the rule narrow. Zurāra carries «محمد بن مسلم» among his names;
    the man filed under it has relations of his own, so the alias does not reach him."""
    data = index(filed("n1", "محمد بن مسلم", teachers=["حمران"]),
                 filed("n2", "زرارة بن أعين", "محمد بن مسلم", students=["ابن أبي عمير"]))
    outcomes, _ = read(data, "ابن أبي عمير", "محمد بن مسلم")
    assert outcomes[1] == ("one", "n1"), outcomes


def test_two_attested_namesakes_leave_the_name_unanswered():
    data = index(filed("n1", "يونس"),
                 filed("n2", "يونس بن عبد الرحمن", "يونس", students=["محمد بن عيسى"]),
                 filed("n3", "يونس بن يعقوب", "يونس", students=["محمد بن عيسى"]))
    outcomes, rows = read(data, "محمد بن عيسى", "يونس")
    assert outcomes[1][0] == "several", outcomes
    assert set(rows[1]["candidates"]) == {"n1", "n2", "n3"}


def test_an_unattested_namesake_does_not_displace_the_stub():
    """The rule rests on the chain's word. A fuller man who answers to the name but whom no
    book places beside this neighbour changes nothing."""
    data = index(filed("n1", "يونس"),
                 filed("n2", "يونس بن عبد الرحمن", "يونس", students=["هشام بن الحكم"]))
    outcomes, _ = read(data, "محمد بن عيسى", "يونس")
    assert outcomes[1] == ("one", "n1"), outcomes


def test_a_one_records_how_many_other_men_answer_to_the_name():
    """`sole_name` tells a reader only one man bears the name. Where a filing won it instead,
    the count is what keeps the published claim honest."""
    data = index(filed("n1", "محمد بن مسلم", teachers=["حمران"]),
                 filed("n2", "زرارة بن أعين", "محمد بن مسلم", students=["ابن أبي عمير"]))
    _, rows = read(data, "ابن أبي عمير", "محمد بن مسلم")
    assert rows[1]["also"] == 1, rows[1]

    alone = index(man("n1", "الحسين بن سعيد الأهوازي"))
    _, rows = read(alone, "الحسين بن سعيد الأهوازي")
    assert "also" not in rows[0], rows[0]


def test_the_alias_table_does_not_depend_on_the_order_people_are_read():
    """An alias is dropped when the name is somebody's heading. Asking that of a table still
    being filled made «يونس» an alias of n2 or not according to which man came first."""
    stub = filed("n1", "يونس")
    fuller = filed("n2", "يونس بن عبد الرحمن", "يونس")
    one_way = index(stub, fuller)
    other_way = index(fuller, stub)
    assert one_way["aliases"].get("يونس") == other_way["aliases"].get("يونس")
    assert one_way["answers_to"]["يونس"] == other_way["answers_to"]["يونس"] == {"n1", "n2"}
