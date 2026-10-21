"""The «س» al-Fihrist lost, the marks OpenITI left in a name, and what to leave alone.

Run from the repository root:

    python3 -m unittest discover -s scripts/narrators -p 'test_mend_narrator_text.py'
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from extract_openiti import MANGLED_SIN                           # noqa: E402
from mend_narrator_text import mend_text, mend_value              # noqa: E402


def mend(text):
    return MANGLED_SIN.sub("س", text)


class SeveredSinTest(unittest.TestCase):
    def test_mends_a_severed_name(self):
        self.assertEqual(mend("سليم بن قيأآلهتنا الهلالي"), "سليم بن قيس الهلالي")
        self.assertEqual(mend("أحمد بن فارأآلهتنا بن زكريا"), "أحمد بن فارس بن زكريا")
        self.assertEqual(mend("مالك بن انأآلهتنا"), "مالك بن انس")

    def test_mends_a_severed_word_in_running_text(self):
        self.assertEqual(mend("اجلأآلهتنا في مسجد"), "اجلس في مسجد")
        self.assertEqual(mend("وليأآلهتنا من أصحابنا"), "وليس من أصحابنا")

    def test_mends_what_a_space_or_a_line_break_split(self):
        # Rijal al-Tusi, entry 976
        self.assertEqual(mend("الحلا أآلهتنا بن عمرو"), "الحلاس بن عمرو")
        # «أرسطاطاليس», severed across a line with OpenITI's continuation mark
        self.assertEqual(mend("أرسطا طاليأ\n~~آلهتنا"), "أرسطا طاليس")


class WordsItMustNotTouchTest(unittest.TestCase):
    def test_leaves_the_quran_alone(self):
        """«أَآلِهَتُنَا خَيْرٌ أَمْ هُوَ» — Qur'an 43:58.

        A real word, written exactly as the damage is. The diacritics part them here, and
        nothing else would: this is why the rule is pointed at two rijāl files by name and
        never let near narration text.
        """
        verse = "وَقَالُوا أَآلِهَتُنَا خَيْرٌ أَمْ هُوَ"
        self.assertEqual(mend(verse), verse)

    def test_leaves_the_word_standing_on_its_own(self):
        self.assertEqual(mend("هذه آلهتنا المحسوسة"), "هذه آلهتنا المحسوسة")


class MilestoneTest(unittest.TestCase):
    """Jami' al-Ruwat's names were cut out of raw OpenITI, marks and all."""

    def test_closes_the_name_over_a_milestone(self):
        self.assertEqual(mend_text("إسماعيل بن دينار ms0154 كوفي")[0],
                         "إسماعيل بن دينار كوفي")
        self.assertEqual(mend_text("جعفر بن عبد ms0241 الرحمن الكاهلي")[0],
                         "جعفر بن عبد الرحمن الكاهلي")

    def test_closes_the_name_over_a_page_mark(self):
        # the gap the mark leaves at the end is closed when a whole field is mended, not
        # while a file is being read in pieces, where the end of a piece is not the end of
        # anything
        self.assertEqual(mend_text("داود بن أبي يزيد الكوفي العطار مولى PageV01P301")[0],
                         "داود بن أبي يزيد الكوفي العطار مولى ")
        self.assertEqual(mend_value("داود بن أبي يزيد الكوفي العطار مولى PageV01P301")[0],
                         "داود بن أبي يزيد الكوفي العطار مولى")

    def test_a_whole_field_loses_the_gap_a_leading_mark_left(self):
        self.assertEqual(mend_value("ms0179 أيوب بن الحر")[0], "أيوب بن الحر")

    def test_leaves_other_spacing_alone(self):
        """Tidying every double space is another tool's job, and in a file read in pieces it
        would trim at the seams."""
        self.assertEqual(mend_text("أبو  علي   الكوفي")[0], "أبو  علي   الكوفي")

    def test_counts_what_it_changed(self):
        self.assertEqual(mend_text("إسماعيل ms0154 بن قيأآلهتنا")[1], 2)
        self.assertEqual(mend_text("إسماعيل بن دينار كوفي")[1], 0)


if __name__ == "__main__":
    unittest.main()
