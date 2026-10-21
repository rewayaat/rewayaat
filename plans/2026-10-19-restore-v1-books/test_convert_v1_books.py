"""Tests for convert_v1_books.py. Run from the repository root:

    python3 -m unittest discover -s scripts/ingest -p 'test_convert_v1_books.py'
"""
import unittest

import convert_v1_books as c


def row(v1_id, book, number, chapter, arabic="عربي", english="English", **extra):
    return {"_id": v1_id, "book": book, "number": str(number), "chapter": chapter,
            "arabic": arabic, "english": english, **extra}


NO_OVERRIDES = {"chapter": {}, "part": {}}


class SplitBilingualTest(unittest.TestCase):

    def test_english_then_arabic(self):
        self.assertEqual(c.split_bilingual("THIS WORLD الدنيا"), ("THIS WORLD", "الدنيا"))

    def test_slash_separated(self):
        self.assertEqual(c.split_bilingual("CHAPTER 3 - On Hypocrisy /  في الرياء"),
                         ("CHAPTER 3 - On Hypocrisy", "في الرياء"))

    def test_arabic_first(self):
        self.assertEqual(c.split_bilingual("﻿الحَديث THE PROPHETIC TRADITION (HADITH)"),
                         ("THE PROPHETIC TRADITION (HADITH)", "الحَديث"))

    def test_english_only(self):
        self.assertEqual(c.split_bilingual("Banes"), ("Banes", ""))


class TitleCaseTest(unittest.TestCase):

    def test_all_caps_is_title_cased_with_small_words_lower(self):
        self.assertEqual(c.title_case("THE BROTHER, THE FRIEND AND THE COMPANION"),
                         "The Brother, the Friend and the Companion")

    def test_hyphens_and_brackets(self):
        self.assertEqual(c.title_case("SELF-RESTRAINT (PATIENCE)"), "Self-Restraint (Patience)")

    def test_mixed_case_is_left_alone(self):
        self.assertEqual(c.title_case("CHAPTER 1 - On Unity of God"), "CHAPTER 1 - On Unity of God")


class ConvertTest(unittest.TestCase):

    def test_ids_fields_and_v1_map(self):
        docs, v1_ids, excluded, report = c.convert(
            [row("25023", "Ghurar Al-Hikam", 417, "THIS WORLD الدنيا")], NO_OVERRIDES)
        self.assertEqual(v1_ids, {"25023": "Ghurar-al-Hikam-Amidi:417"})
        src = docs[0]["_source"]
        self.assertEqual(src["book"], "Ghurar al-Ḥikam")
        self.assertEqual(src["chapter"], "This World")
        self.assertEqual(src["chapter_ar"], "الدنيا")
        self.assertEqual(src["chapter_ar_source"], "rewayaat-v1")
        self.assertEqual(src["volume"], "1")
        self.assertEqual(src["publisher_ar"], "أنصاريان")
        self.assertNotIn("part", src)
        self.assertNotIn("history", src)
        self.assertEqual(report["blocking"], [])

    def test_mizan_section_becomes_part_and_source_becomes_cited_from(self):
        docs, _, _, _ = c.convert([row("17921", "Mizan Al-Hikmah", 4115,
                                       "Supplication is not Without Effect عَدَمُ",
                                       section="SUPPLICATION الدُّعاء",
                                       source="Al-Kafi, volume:2, p. 491, number:9")],
                                  NO_OVERRIDES)
        src = docs[0]["_source"]
        self.assertEqual((src["part"], src["part_ar"]), ("Supplication", "الدُّعاء"))
        self.assertEqual(src["cited_from"], "Al-Kafi, volume:2, p. 491, number:9")
        self.assertEqual(src["source"], "Muḥammad Muḥammadī Rayshahrī")

    def test_mishkat_fragment_with_colliding_number_and_no_arabic_is_excluded(self):
        book = "Mishkat Al-Anwar Fi Ghurar il-Akhbar / مشكاة الأنوار في غرر الأخبار"
        docs, v1_ids, excluded, _ = c.convert([
            row("7331", book, 1, "CHAPTER 1 - On Unity of God / في التوحيد", section="SECTION 1 / الباب"),
            row("16481", book, 1, "CHAPTER 4 - On Rights / في الحقوق", arabic="", section="SECTION 2 / الباب"),
        ], NO_OVERRIDES)
        self.assertEqual(list(v1_ids), ["7331"])
        self.assertEqual(excluded[0]["v1_id"], "16481")
        self.assertEqual(excluded[0]["reason"], "list-fragment")

    def test_same_english_with_two_arabic_titles_is_disambiguated(self):
        docs, _, _, report = c.convert([
            row("1", "Ghurar Al-Hikam", 1, "GENEROSITY الجود"),
            row("2", "Ghurar Al-Hikam", 2, "GENEROSITY السخاء"),
        ], NO_OVERRIDES)
        self.assertEqual({d["_source"]["chapter"] for d in docs},
                         {"Generosity (الجود)", "Generosity (السخاء)"})
        self.assertEqual(len(report["disambiguated"]), 2)

    def test_narration_text_in_chapter_blocks_until_overridden(self):
        rows = [row("1", "Mizan Al-Hikmah", 1, "Knowledge العلم", section="KNOWLEDGE العِلمُ"),
                row("2", "Mizan Al-Hikmah", 2, "فقالَ: قَليلَةٌ", section="KNOWLEDGE العِلمُ")]
        _, _, _, report = c.convert(rows, NO_OVERRIDES)
        self.assertEqual([b["problems"] for b in report["blocking"]], [["no-english"]])

        overrides = {"chapter": {"فقالَ: قَليلَةٌ": {"inherit": "previous"}}, "part": {}}
        docs, _, _, report = c.convert(rows, overrides)
        self.assertEqual(report["blocking"], [])
        self.assertEqual(docs[1]["_source"]["chapter"], "Knowledge")
        self.assertEqual(docs[1]["_source"]["chapter_ar"], "العلم")

    def test_untranslated_chapter_is_reported_but_not_blocking(self):
        docs, _, _, report = c.convert([row("1", "Mizan Al-Hikmah", 1, "Banes",
                                            section="BANES الآفات")], NO_OVERRIDES)
        self.assertEqual(report["blocking"], [])
        self.assertEqual(len(report["untranslated"]), 1)
        self.assertNotIn("chapter_ar", docs[0]["_source"])

    def test_books_outside_the_three_are_ignored(self):
        docs, _, _, _ = c.convert([row("9", "Al-Kafi | الكافي", 1, "Faith الإيمان")], NO_OVERRIDES)
        self.assertEqual(docs, [])


if __name__ == "__main__":
    unittest.main()
