"""Verbs and doxologies the cutter must not take for men, and names it must not refuse."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import chain_mentions as cm  # noqa: E402


def kinds(chain):
    return [(m["kind"], m["text"]) for m in cm.segment(chain)]


def test_a_reporting_verb_with_its_pronoun_is_not_a_man():
    """«سُئِلَ» 141, «حَدَّثَهُ» 133, «أَخْبَرَهُ» 71, «رَوَيْتُهُ» 41 and «سَمِعْتُهُ» 12 were read as
    names because the verb list stopped at a life notice and the pronoun stayed on the verb."""
    for word in ("سُئِلَ", "حَدَّثَهُ", "أَخْبَرَهُ", "رَوَيْتُهُ", "سَمِعْتُهُ", "أَسْأَلُكَ"):
        assert cm.alone_a_verb([cm.bare(word)]), word
        assert cm.not_a_man(word), word


def test_a_name_that_begins_like_a_verb_is_a_name():
    for word in ("سَمْعَان", "سَيْلَان", "سَالِم", "رَوْح", "حَدِيد"):
        assert not cm.alone_a_verb([cm.bare(word)]), word
        assert not cm.not_a_man(word), word


def test_the_doxology_after_a_verse_is_prose():
    assert cm.not_a_man("قَوْلِ اللَّهِ عَزَّ")
    assert cm.not_a_man("جَلَّ")
    assert cm.kind_of(["قول", "الله", "عز"], False) == "text"


def test_a_verb_ahead_of_a_name_is_not_part_of_it():
    found = kinds("عن سُئِلَ أَبُو الْحَسَنِ مُوسَى بْنُ جَعْفَرٍ عن عَلِيِّ بْنِ إِبْرَاهِيمَ")
    assert [t for k, t in found if k == "name"][-1] == "عَلِيِّ بْنِ إِبْرَاهِيمَ"
    assert not any(t.startswith("سُئِلَ") for _, t in found)


def test_a_collective_is_still_a_step_and_not_prose():
    """«عدة من أصحابنا» holds «من», a word no name contains. It is the order of the two
    questions that keeps it a collective, and that order is not to change."""
    assert not cm.not_a_man("عِدَّةٌ مِنْ أَصْحَابِنَا")
    assert cm.kind_of(["عدة", "من", "اصحابنا"], False) == "collective"


def test_an_abbreviated_blessing_ends_the_name():
    """«حدثني أبي ره» is al-Ṣadūq's father with «رحمه الله» shortened to two letters. Read as
    a name it was the commonest unresolved step in the corpus, 460 of them."""
    found = kinds("حدثني أَبِي ره عن سَعْدِ بْنِ عَبْدِ اللَّهِ")
    assert ("relative", "أَبِي") in found
    assert not any("ره" in t for _, t in found)


def test_a_persian_yeh_is_the_last_letter_of_a_name():
    """ی sits outside the ء-ي block, so the span's tail trimmer took it for punctuation and
    «أَبِی» was cut to «أَبِ», which is nobody. The Qom editions are typeset with it."""
    found = kinds("حدثني أَبِی ره عن مُحَمَّدِ بْنِ یَحْیَی")
    assert ("relative", "أَبِی") in found
    assert ("name", "مُحَمَّدِ بْنِ یَحْیَی") in found


def test_the_compilers_own_kin_are_relatives_not_names():
    """«عمي», «أخي», «خالي» said in the first person, as «أبي» is. 116 were read as names."""
    for word in ("عمي", "أخي", "خالي", "أمي"):
        assert cm.kind_of([cm.bare(word)], False) == "relative", word


def test_a_narration_of_a_man_names_the_man():
    """«رواية السكوني» is 239 spans and not one of them is a man called "narration"."""
    found = kinds("عن رواية السكوني عن جعفر بن محمد")
    assert ("name", "السكوني") in found
    assert ("text", "وفي رواية السكوني") in kinds("وفي رواية السكوني عن جعفر بن محمد")


def test_a_book_at_the_front_is_prose_and_not_a_man():
    """Trimming «كتاب» off the front leaves «المحاسن», a book read as a man. Held there, the
    word makes the run what it is."""
    assert not any(k == "name" for k, _ in kinds("ومن كتاب المحاسن عن أبيه"))
