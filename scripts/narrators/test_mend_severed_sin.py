"""The «س» al-Fihrist lost, and the word the repair must never take for it.

Run from the repository root:

    python3 -m unittest discover -s scripts/narrators -p 'test_mend_severed_sin.py'
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from extract_openiti import MANGLED_SIN                           # noqa: E402


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


if __name__ == "__main__":
    unittest.main()
