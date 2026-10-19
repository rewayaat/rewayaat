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


MISHKAT = "Mishkāt al-Anwār"
GHURAR = "Ghurar al-Ḥikam"
MIZAN = "Mīzān al-Ḥikma"


class SkeletonTest(unittest.TestCase):

    def test_skeleton_ignores_marks_spaces_latin_and_tatweel(self):
        self.assertEqual(c.skeleton("قالَ  عليٌّ H ـ"), c.skeleton("قال علي"))

    def test_skeleton_sees_a_changed_letter(self):
        self.assertNotEqual(c.skeleton("الجرب"), c.skeleton("الحرب"))


class CleanArabicTest(unittest.TestCase):

    def test_mishkat_diacritics_are_stripped(self):
        self.assertEqual(c.clean_arabic("إّني عبٍد", MISHKAT), "إني عبد")

    def test_vocalised_books_keep_their_marks(self):
        self.assertEqual(c.clean_arabic("بِرُّ الوالِدَيْنِ", GHURAR), "بِرُّ الوالِدَيْنِ")

    def test_detached_hamza_is_joined(self):
        self.assertEqual(c.clean_arabic("أصْدَقُ شَيْ ء اَلأجَلُ", GHURAR), "أصْدَقُ شَيْء اَلأجَلُ")

    def test_split_anna_is_joined(self):
        self.assertEqual(c.clean_arabic("يَتَمَنّى المؤمنُ أ نّه لم", MIZAN), "يَتَمَنّى المؤمنُ أنّه لم")

    def test_rlm_between_words_becomes_a_space_and_inside_a_word_goes(self):
        self.assertEqual(c.clean_arabic("الخَلائقِ\u200fإلَى اللّه\u200fِ", MIZAN), "الخَلائقِ إلَى اللّهِ")

    def test_tatweel_inside_a_word_goes_but_a_spaced_dash_stays(self):
        self.assertEqual(c.clean_arabic("المُجاهِـدُ ـ قال", MIZAN), "المُجاهِدُ ـ قال")

    def test_a_vowel_stranded_behind_an_rlm_returns_to_its_word(self):
        self.assertEqual(c.clean_arabic("إذا أرادَ اللّه \u200fُبعَبدٍ خَيراً", MIZAN),
                         "إذا أرادَ اللّهُ بعَبدٍ خَيراً")
        self.assertEqual(c.clean_arabic("رسولُ اللهِ \u200fِ (صَلَّيَ", MIZAN), "رسولُ اللهِ (صَلَّيَ")

    def test_a_compound_ghurar_citation_is_removed(self):
        self.assertEqual(c.clean_arabic("العِلمُ نورٌ (غرر الحكم: 6، عيون الحكم والمواعظ: 12)", MIZAN),
                         "العِلمُ نورٌ")

    def test_mizan_citation_parenthetical_is_removed(self):
        self.assertEqual(c.clean_arabic("الصَّبرُ مِفتاحُ الفَرَجِ (غرر الحکم: 345)", MIZAN), "الصَّبرُ مِفتاحُ الفَرَجِ")


class CleanEnglishTest(unittest.TestCase):

    def test_spacing(self):
        self.assertEqual(c.clean_english("deeds , and  association", GHURAR, []),
                         "deeds, and association")

    def test_heading_bleed_is_removed(self):
        text = "…even if it is bitter.” On Faith, Islam, and Related Issues"
        self.assertEqual(c.clean_english(text, MISHKAT, ["Section 1 - On Faith, Islam and Related Issues"]),
                         "…even if it is bitter.”")

    def test_a_sentence_ending_in_a_heading_phrase_is_not_cut(self):
        text = "Truthfulness is the cornerstone of faith and the adornment of the human being."
        self.assertEqual(c.clean_english(text, GHURAR, ["THE HUMAN BEING"]), text)
        self.assertEqual(c.clean_english(text, MISHKAT, ["The Human Being"]), text)

    def test_mishkat_footnote_digit_is_removed(self):
        self.assertEqual(c.clean_english("towards the Qibla1 and prayed", MISHKAT, []),
                         "towards the Qibla and prayed")

    def test_mizan_footnote_digits_are_removed(self):
        self.assertEqual(c.clean_english("embracing this matter87 and", MIZAN, []), "embracing this matter and")

    def test_digits_in_other_books_are_left(self):
        self.assertEqual(c.clean_english("number 40 of them", GHURAR, []), "number 40 of them")


class ValidateFixTest(unittest.TestCase):

    def test_rejoining_a_word_is_automatic(self):
        self.assertEqual(c.validate_fix("arabic", "قال أ  م ن رضي", "أ  م ن", "أمن"), "auto")

    def test_changing_a_letter_is_refused(self):
        with self.assertRaisesRegex(ValueError, "skeleton"):
            c.validate_fix("arabic", "نعوذ بالله من الجرب", "الجرب", "الحرب")

    def test_glyph_to_honorific_needs_review(self):
        self.assertEqual(c.validate_fix("arabic", "عن الحسين H قال", "الحسين H",
                                        "الحسين عليه السلام"), "review")

    def test_removing_the_books_own_heading_is_automatic(self):
        text = "قال: الصبر رأس الإيمان  في الإيمان والإسلام وما يتعلق بهما"
        self.assertEqual(c.validate_fix("arabic", text, "  في الإيمان والإسلام وما يتعلق بهما", "",
                                        headings=["الباب الأول في الإيمان والإسلام وما يتعلق بهما"]), "auto")

    def test_removing_other_text_is_refused(self):
        with self.assertRaisesRegex(ValueError, "skeleton"):
            c.validate_fix("arabic", "قال: الصبر رأس الإيمان", " رأس الإيمان", "", headings=["في الصبر"])

    def test_english_spacing_fix_is_automatic(self):
        self.assertEqual(c.validate_fix("english", "deeds , and", "deeds ,", "deeds,"), "auto")

    def test_english_reword_is_refused(self):
        with self.assertRaisesRegex(ValueError, "words"):
            c.validate_fix("english", "better to be crippled", "crippled", "disabled")

    def test_ambiguous_old_is_refused(self):
        with self.assertRaisesRegex(ValueError, "exactly once"):
            c.validate_fix("english", "the  the  end", "  ", " ")

    def test_mark_order_does_not_hide_a_fix(self):
        stored = "طالَّتِ"            # fatha before shadda, as the export stored it
        typed = "طالَّتِ".replace("\u064e\u0651", "\u0651\u064e")
        self.assertEqual(c.validate_fix("arabic", "قد " + stored + " الأيام", typed, "طالت"), "auto")

    def test_caret_glyph_to_honorific_needs_review(self):
        self.assertEqual(c.validate_fix("arabic", "الأئمَّةُ ^ قالوا", "الأئمَّةُ ^", "الأئمَّةُ عليهم السلام"),
                         "review")

    def test_broken_encoding_repair_needs_review(self):
        self.assertEqual(c.validate_fix("english", "the Im?m said", "Im?m", "Imam"), "review")
        with self.assertRaises(ValueError):
            c.validate_fix("english", "the Im?m said", "Im?m", "Caliph")

    def test_title_fields_are_not_fixed_here(self):
        with self.assertRaisesRegex(ValueError, "overrides"):
            c.validate_fix("chapter_ar", "فَضلُالصَّلاةِ", "فَضلُالصَّلاةِ", "فَضلُ الصَّلاةِ")


class FixesAndExclusionsTest(unittest.TestCase):

    def rows(self):
        return [row("25023", "Ghurar Al-Hikam", 417, "THIS WORLD الدنيا",
                    arabic="مَشْياًسَجْحاً", english="Imam Ali (AS) said, walk  gently.")]

    def test_valid_fix_is_applied_after_cleaning(self):
        fixes = {"Ghurar-al-Hikam-Amidi:417": [{"field": "arabic", "old": "مَشْياًسَجْحاً", "new": "مَشْياً سَجْحاً"}]}
        docs, _, _, _ = c.convert(self.rows(), NO_OVERRIDES, fixes=fixes)
        self.assertEqual(docs[0]["_source"]["arabic"], "مَشْياً سَجْحاً")
        self.assertEqual(docs[0]["_source"]["english"], "Imam Ali (AS) said, walk gently.")

    def test_invalid_fix_stops_the_conversion(self):
        fixes = {"Ghurar-al-Hikam-Amidi:417": [{"field": "arabic", "old": "سَجْحاً", "new": "سَهْلاً"}]}
        with self.assertRaises(ValueError):
            c.convert(self.rows(), NO_OVERRIDES, fixes=fixes)

    def test_review_fix_applies_only_when_approved(self):
        rows = [row("1", "Ghurar Al-Hikam", 1, "THIS WORLD الدنيا", arabic="عن الحسين H قال")]
        fix = {"field": "arabic", "old": "الحسين H", "new": "الحسين عليه السلام"}
        with self.assertRaises(ValueError):
            c.convert(rows, NO_OVERRIDES, fixes={"Ghurar-al-Hikam-Amidi:1": [fix]})
        docs, _, _, _ = c.convert(rows, NO_OVERRIDES, fixes={"Ghurar-al-Hikam-Amidi:1": [dict(fix, approved=True)]})
        self.assertEqual(docs[0]["_source"]["arabic"], "عن الحسين عليه السلام قال")

    def test_excluded_id_is_left_out_with_its_reason(self):
        docs, v1_ids, excluded, _ = c.convert(self.rows(), NO_OVERRIDES, exclusions={"25023": "misaligned"})
        self.assertEqual(docs, [])
        self.assertEqual(excluded[0]["reason"], "misaligned")

    def test_held_back_book_is_left_out_whole(self):
        book = "Mishkat Al-Anwar Fi Ghurar il-Akhbar / مشكاة الأنوار في غرر الأخبار"
        docs, _, excluded, _ = c.convert(self.rows() + [row("7331", book, 1, "CHAPTER 1 - On Unity / في التوحيد",
                                                            section="SECTION 1 / الباب")],
                                         NO_OVERRIDES, hold_back={MISHKAT})
        self.assertEqual([d["_source"]["book"] for d in docs], [GHURAR])
        self.assertEqual(excluded[0]["reason"], "held-back")


class TitleFixesTest(unittest.TestCase):

    def test_a_title_fix_applies_to_every_narration_of_the_chapter(self):
        rows = [row("1", "Ghurar Al-Hikam", 1, "REMEMBRANCE الذكرو المُذَكِّر"),
                row("2", "Ghurar Al-Hikam", 2, "REMEMBRANCE الذكرو المُذَكِّر")]
        fixes = {"chapter_ar": {"الذكرو المُذَكِّر": "الذكر و المُذَكِّر"}}
        docs, _, _, _ = c.convert(rows, NO_OVERRIDES, title_fixes=fixes)
        self.assertEqual({d["_source"]["chapter_ar"] for d in docs}, {"الذكر و المُذَكِّر"})

    def test_a_title_fix_that_changes_a_letter_is_refused(self):
        rows = [row("1", "Ghurar Al-Hikam", 1, "REMEMBRANCE الذكرو المُذَكِّر")]
        with self.assertRaises(ValueError):
            c.convert(rows, NO_OVERRIDES, title_fixes={"chapter_ar": {"الذكرو المُذَكِّر": "الفكر"}})

    def test_stray_pipe_and_asterisk_leave_english_titles(self):
        docs, _, _, _ = c.convert([row("1", "Mizan Al-Hikmah", 1, "This World | الدنيا",
                                       section="Ashura * عاشوراء")], NO_OVERRIDES)
        self.assertEqual((docs[0]["_source"]["chapter"], docs[0]["_source"]["part"]), ("This World", "Ashura"))


class SpeakerFixTest(unittest.TestCase):

    def test_an_approved_arabic_speaker_prefix_is_corrected(self):
        text = "رسولُ اللهِ (عَلَيهِ الّسَلامُ): العِلمُ نورٌ"
        self.assertEqual(c.validate_fix("arabic", text, "رسولُ اللهِ (عَلَيهِ الّسَلامُ)",
                                        "الإمامُ عليٌّ (عَلَيهِ الّسَلامُ)", kind="speaker"), "review")

    def test_a_speaker_fix_may_not_reach_past_the_prefix(self):
        text = "رسولُ اللهِ (عَلَيهِ الّسَلامُ): العِلمُ نورٌ"
        with self.assertRaises(ValueError):
            c.validate_fix("arabic", text, "العِلمُ نورٌ", "العِلمُ ظلامٌ", kind="speaker")

    def test_an_english_speaker_is_corrected_only_to_a_known_speaker(self):
        text = "The Prophet (SAWA) said, Knowledge is light."
        self.assertEqual(c.validate_fix("english", text, "The Prophet (SAWA)", "Imam Ali (AS)",
                                        kind="speaker"), "review")
        with self.assertRaises(ValueError):
            c.validate_fix("english", text, "The Prophet (SAWA)", "Someone else", kind="speaker")


class RepairsTest(unittest.TestCase):

    def rows(self):
        return [row("1", "Mizan Al-Hikmah", 1, "Trust الأمانة", arabic="لا تَخُنْ", english="Return the trust.",
                    section="TRUST الأمانة", source="Al-Kafi, number:1"),
                row("2", "Mizan Al-Hikmah", 2, "Trust الأمانة", arabic="أدُّوا الأمانةَ", english="Do not betray.",
                    section="TRUST الأمانة", source="Al-Kafi, number:2"),
                row("3", "Mizan Al-Hikmah", 3, "Sorrow الحزن", arabic="الحُزنُ", english="Sorrow.",
                    section="SORROW الحزن", source="Bihar, number:3")]

    def test_english_and_citation_move_between_records(self):
        repairs = {"english_moves": {
            "Mizan-al-Hikma-Rayshahri:1": {"english_from": "Mizan-al-Hikma-Rayshahri:2", "cited_from_from": "Mizan-al-Hikma-Rayshahri:2"},
            "Mizan-al-Hikma-Rayshahri:2": {"english_from": "Mizan-al-Hikma-Rayshahri:1", "cited_from_from": "Mizan-al-Hikma-Rayshahri:1"}}}
        docs, _, _, _ = c.convert(self.rows(), NO_OVERRIDES, repairs=repairs)
        by = {d["_id"]: d["_source"] for d in docs}
        self.assertEqual(by["Mizan-al-Hikma-Rayshahri:1"]["english"], "Do not betray.")
        self.assertEqual(by["Mizan-al-Hikma-Rayshahri:2"]["cited_from"], "Al-Kafi, number:1")

    def test_one_english_cannot_go_to_two_records(self):
        repairs = {"english_moves": {
            "Mizan-al-Hikma-Rayshahri:1": {"english_from": "Mizan-al-Hikma-Rayshahri:3"},
            "Mizan-al-Hikma-Rayshahri:2": {"english_from": "Mizan-al-Hikma-Rayshahri:3"}}}
        with self.assertRaises(ValueError):
            c.convert(self.rows(), NO_OVERRIDES, repairs=repairs)

    def test_relabel_takes_an_existing_label_with_its_arabic(self):
        repairs = {"relabels": {"Mizan-al-Hikma-Rayshahri:2": {"part": "Sorrow", "chapter": "Sorrow"}}}
        docs, _, _, _ = c.convert(self.rows(), NO_OVERRIDES, repairs=repairs)
        src = {d["_id"]: d["_source"] for d in docs}["Mizan-al-Hikma-Rayshahri:2"]
        self.assertEqual((src["part"], src["part_ar"], src["chapter"], src["chapter_ar"]),
                         ("Sorrow", "الحزن", "Sorrow", "الحزن"))

    def test_relabel_to_an_unknown_label_is_refused(self):
        with self.assertRaises(ValueError):
            c.convert(self.rows(), NO_OVERRIDES,
                      repairs={"relabels": {"Mizan-al-Hikma-Rayshahri:2": {"part": "Invented", "chapter": "New"}}})

    def test_an_excluded_record_can_still_hand_its_english_on(self):
        repairs = {"english_moves": {"Mizan-al-Hikma-Rayshahri:1": {"english_from": "Mizan-al-Hikma-Rayshahri:2"}}}
        docs, _, excluded, _ = c.convert(self.rows(), NO_OVERRIDES, exclusions={"2": "english-belongs-to-no-record"},
                                         repairs=repairs)
        by = {d["_id"]: d["_source"] for d in docs}
        self.assertNotIn("Mizan-al-Hikma-Rayshahri:2", by)
        self.assertEqual(by["Mizan-al-Hikma-Rayshahri:1"]["english"], "Do not betray.")
        self.assertEqual([e["v1_id"] for e in excluded], ["2"])

    def test_a_reader_retitle_sets_the_title_and_marks_its_source(self):
        repairs = {"retitles": {"Mizan-al-Hikma-Rayshahri:3": {"chapter": "Grief", "chapter_ar": "الغم"}}}
        docs, _, _, _ = c.convert(self.rows(), NO_OVERRIDES, repairs=repairs)
        src = {d["_id"]: d["_source"] for d in docs}["Mizan-al-Hikma-Rayshahri:3"]
        self.assertEqual((src["chapter"], src["chapter_ar"], src["chapter_ar_source"]), ("Grief", "الغم", "rewayaat-review"))

    def test_a_retitle_can_drop_a_wrong_arabic_title(self):
        repairs = {"retitles": {"Mizan-al-Hikma-Rayshahri:3": {"chapter_ar": None}}}
        docs, _, _, _ = c.convert(self.rows(), NO_OVERRIDES, repairs=repairs)
        src = {d["_id"]: d["_source"] for d in docs}["Mizan-al-Hikma-Rayshahri:3"]
        self.assertNotIn("chapter_ar", src)

    def test_a_retitle_may_only_touch_title_fields(self):
        with self.assertRaises(ValueError):
            c.convert(self.rows(), NO_OVERRIDES, repairs={"retitles": {"Mizan-al-Hikma-Rayshahri:3": {"arabic": "x"}}})


class CitationCleanTest(unittest.TestCase):

    def test_stray_brackets_and_punctuation_leave_a_citation(self):
        self.assertEqual(c.clean_citation("[Kan z al-Ummal, number:28666"), "Kan z al-Ummal, number:28666")
        self.assertEqual(c.clean_citation("Ghurar al-Hikam,. number:2142"), "Ghurar al-Hikam, number:2142")
        self.assertEqual(c.clean_citation("AeIlal al-Sharaia , p. 227, number:2"), "AeIlal al-Sharaia, p. 227, number:2")


if __name__ == "__main__":
    unittest.main()
