"""Tests for apply_v1_restore_translations.py. Run from the repository root:

    python3 -m unittest discover -s scripts/ingest -p 'test_apply_v1_restore_translations.py'
"""
import unittest

from apply_v1_restore_translations import citation_ar, partial_update

BOOKS_AR = {"Al-Kafi": "الكافي", "Nahj al-Balagha": "نهج البلاغة", "Ghurar al-Hikam": "غرر الحكم",
            "Bihar al-Anwar": "بحار الأنوار", "Kanz al-Ummal": "كنز العمال",
            "Ibid.": None}


class CitationArTest(unittest.TestCase):

    def test_parsed(self):
        cases = {
            "Al-Kafi, volume:2, p. 491, number:9": "الكافي، ج 2، ص 491، ح 9",
            "Nahj al-Balagha, Sermon 198": "نهج البلاغة، الخطبة 198",
            "Nahj al-Balagha, Letter 31": "نهج البلاغة، الكتاب 31",
            "Nahj al-Balagha, Saying 147": "نهج البلاغة، الحكمة 147",
            "Nahj al-Balagha, Sayings 147 and 148": "نهج البلاغة، الحكمة 147، 148",
            "Ghurar al-Hikam, nos. 7921; 7922": "غرر الحكم، ح 7921، 7922",
            "Ghurar al-Hikam, nos. 7921 - 7922": "غرر الحكم، ح 7921 - 7922",
            "Bihar al-Anwar, v.94, p. 98, number:13": "بحار الأنوار، ج 94، ص 98، ح 13",
            "Bihar al-Anwar, volume:94. p. 98, number:13": "بحار الأنوار، ج 94، ص 98، ح 13",
            "Kanz al-Ummal number:43833": "كنز العمال، ح 43833",
            "Kanz al-Ummal,number:43833 and 43834": "كنز العمال، ح 43833، 43834",
            "Al-Kafi, p. 12, supplication 3": "الكافي، ص 12، الدعاء 3",
            "Al-Kafi": "الكافي",
        }
        for english, arabic in cases.items():
            self.assertEqual(citation_ar(english, BOOKS_AR), arabic, english)

    def test_unparsed(self):
        for english in ("Ibid.", "Mighty and Exalted", "Quran 3:180", "Al-Kafi, number:3 al-Kafi",
                        "Tuhaf al-Uqul, p. 3"):
            self.assertIsNone(citation_ar(english, BOOKS_AR), english)


class PartialUpdateTest(unittest.TestCase):
    TRANSLATIONS = {"chapter": {"Aashura": "عاشوراء"}, "part": {"Prayer": "الصلاة"}}

    def test_only_missing_fields_are_written(self):
        src = {"book": "Mīzān al-Ḥikma", "chapter": "Aashura", "part": "Prayer", "part_ar": "الصَّلاةُ",
               "cited_from": "Al-Kafi, number:9"}
        self.assertEqual(partial_update(src, self.TRANSLATIONS, BOOKS_AR),
                         {"chapter_ar": "عاشوراء", "chapter_ar_source": "llm-translated",
                          "cited_from_ar": "الكافي، ح 9"})

    def test_nothing_to_write(self):
        src = {"book": "Ghurar al-Ḥikam", "chapter": "Parents", "chapter_ar": "الآباء"}
        self.assertEqual(partial_update(src, self.TRANSLATIONS, BOOKS_AR), {})


if __name__ == "__main__":
    unittest.main()
