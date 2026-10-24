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
