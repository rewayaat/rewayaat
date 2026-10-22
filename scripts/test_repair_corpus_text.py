#!/usr/bin/env python3
"""What the corpus repair must and must not do.

    python3 -m unittest discover -s scripts -p 'test_repair_corpus_text.py'
"""
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import repair_corpus_text as R      # noqa: E402

ALL = set(R.CLASSES)


def mend(doc, cases=(), only=ALL):
    return R.repair(doc, list(cases), only)


class InvisibleCharacters(unittest.TestCase):

    def test_zero_width_joiner_inside_a_word_goes(self):
        new, counts = mend({"arabic": "أطال الله بقاء‌ه من إملاء"})
        self.assertEqual(new["arabic"], "أطال الله بقاءه من إملاء")
        self.assertEqual(counts["invisible"], 1)

    def test_bidi_embedding_controls_go(self):
        new, _ = mend({"english": "in‬‬ ‫‪the following terms"})
        self.assertEqual(new["english"], "in the following terms")

    def test_clean_text_is_left_alone(self):
        new, _ = mend({"arabic": "مُحَمَّدُ بْنُ يَحْيَى", "english": "Muhammad ibn Yahya"})
        self.assertEqual(new, {})


class Whitespace(unittest.TestCase):

    def test_a_tab_becomes_a_space_and_the_length_holds(self):
        new, _ = mend({"english": "16.\tIt was reported"})
        self.assertEqual(new["english"], "16. It was reported")

    def test_non_breaking_space_becomes_a_space(self):
        new, _ = mend({"arabic": "صمدا لَمْ يَلِدْ"})
        self.assertEqual(new["arabic"], "صمدا لَمْ يَلِدْ")


class MisplacedShadda(unittest.TestCase):

    def test_the_salutation_moves_its_shadda_to_the_sin(self):
        # الّسَلامُ -> السَّلامُ: the mark leaves the lām and lands on the sīn, before
        # the fatha, which is the order the rest of the corpus writes it in
        was = "\u0627\u0644\u0651\u0633\u064e\u0644\u0627\u0645\u064f"
        want = "\u0627\u0644\u0633\u0651\u064e\u0644\u0627\u0645\u064f"
        new, counts = mend({"arabic": f"\u0639\u064e\u0644\u064ePLACE {was} :"
                                      .replace("PLACE", "")})
        self.assertEqual(new["arabic"].split()[-2], want)
        self.assertEqual(counts["shadda"], 1)

    def test_the_relative_pronouns_keep_theirs(self):
        # «الّذي» and «الّتي» carry the mark on the lām correctly
        for word in ("الّذي", "الّتي", "الّذينَ", "والّذي"):
            new, _ = mend({"arabic": f"العلمُ {word} يُنفَعُ بِهِ"})
            self.assertEqual(new, {}, word)

    def test_a_doubled_letter_inside_a_word_is_left_alone(self):
        for word in ("محلّل", "استحلّت", "سلّطت", "اللّسان"):
            new, _ = mend({"arabic": f"وهو {word} عندنا"})
            self.assertEqual(new, {}, word)


class GluedWords(unittest.TestCase):

    def test_a_word_glued_after_ta_marbuta_is_split(self):
        new, counts = mend({"arabic": "المستحاضةوالشراب"})
        self.assertEqual(new["arabic"], "المستحاضة والشراب")
        self.assertEqual(counts["glue"], 1)

    def test_a_word_glued_after_alif_maqsura_is_split(self):
        new, _ = mend({"arabic": "يحيىالعطار"})
        self.assertEqual(new["arabic"], "يحيى العطار")

    def test_diacritics_stay_with_the_first_word(self):
        new, _ = mend({"arabic": "صَلَّىَاللهُ عَلَيْهِ"})
        self.assertEqual(new["arabic"], "صَلَّىَ اللهُ عَلَيْهِ")

    def test_shay_keeps_its_medial_alif_maqsura(self):
        # «شىء» spells shayʾ with an alif maqṣūra; it is one word
        new, _ = mend({"arabic": "ما من شىء إلا وفيه كتاب"})
        self.assertEqual(new, {})


class LostCharacters(unittest.TestCase):

    CASE = {"id": "x", "field": "arabic", "old": "رَس��ولِ", "new": "رَسُولِ"}

    def test_the_named_token_is_restored(self):
        new, counts = mend({"arabic": "شَكَا رَجُلٌ إِلَى رَس��ولِ اللَّهِ"},
                           [self.CASE])
        self.assertEqual(new["arabic"], "شَكَا رَجُلٌ إِلَى رَسُولِ اللَّهِ")
        self.assertEqual(counts["lost"], 1)

    def test_a_repair_already_made_is_left_alone(self):
        # a second run must neither undo the repair nor refuse the document over it
        new, counts = mend({"arabic": "شَكَا رَجُلٌ إِلَى رَسُولِ اللَّهِ"}, [self.CASE])
        self.assertEqual(new, {})
        self.assertEqual(counts["lost"], 0)

    def test_a_token_that_is_not_there_at_all_is_refused(self):
        with self.assertRaises(R.Refused):
            mend({"arabic": "حَدَّثَنَا أَبِي قَالَ"}, [self.CASE])

    def test_a_token_that_occurs_twice_is_refused(self):
        with self.assertRaises(R.Refused):
            mend({"arabic": "رَس��ولِ و رَس��ولِ"}, [self.CASE])

    def test_the_case_left_lost_is_not_touched(self):
        doc = {"arabic": "وإذ ير��نك ما تأتي"}
        new, _ = mend(doc, [{"id": "x", "field": "arabic", "old": "ير��نك",
                             "new": None, "skip": True}])
        self.assertEqual(new, {})


class Placeholders(unittest.TestCase):

    def test_the_placeholder_field_is_emptied(self):
        new, counts = mend({"arabic": "To be added", "english": "18. Muḥammad ibn Ḥasan"},
                           [{"id": "x", "field": "arabic", "blank": True}])
        self.assertEqual(new["arabic"], "")
        self.assertEqual(counts["placeholder"], 1)


class ChainSpans(unittest.TestCase):
    """The spans index into the segmenter's chain, so they move with the text."""

    NAME = "يُونُسَ"                  # يُونُسَ
    AN = "عن"                                                  # عن
    SAID = "قال"                                          # قال
    JOINER = "بقاء‌ه"                      # بقاء‌ه
    GLUED = ("المستحاضة"
             "والشراب")               # المستحاضةوالشراب

    def link(self, text, name=None, at=0):
        """A span over `name`, offset from a chain that begins at `at`."""
        name = name or self.NAME
        where = text.index(name)
        return {"p": "n1", "s": where - at, "e": where + len(name) - at, "t": name,
                "g": "yunus"}

    def covers(self, text, link, at=0):
        """What the span reads now, from a chain that begins at `at`."""
        return text[at + link["s"]:at + link["e"]]

    def test_a_span_before_the_repair_keeps_its_offsets(self):
        # the invisible character sits after the name, so nothing before it moves
        text = f"{self.AN} {self.NAME} {self.SAID} {self.JOINER}"
        new, _ = mend({"arabic": text, "chain_links": [self.link(text)]})
        link = new["chain_links"][0]
        self.assertEqual(link["s"], text.index(self.NAME))
        self.assertEqual(self.covers(new["arabic"], link), self.NAME)

    def test_a_span_after_a_shortening_repair_moves_back(self):
        text = f"{self.JOINER} {self.AN} {self.NAME}"
        new, _ = mend({"arabic": text, "chain_links": [self.link(text)]})
        link = new["chain_links"][0]
        self.assertEqual(self.covers(new["arabic"], link), self.NAME)
        self.assertEqual(link["s"], text.index(self.NAME) - 1)

    def test_a_span_after_a_lengthening_repair_moves_on(self):
        text = f"{self.GLUED} {self.AN} {self.NAME}"
        new, _ = mend({"arabic": text, "chain_links": [self.link(text)]})
        link = new["chain_links"][0]
        self.assertEqual(self.covers(new["arabic"], link), self.NAME)
        self.assertEqual(link["s"], text.index(self.NAME) + 1)

    def test_a_chain_that_does_not_start_at_the_first_character(self):
        # a leading space is not part of the segmenter's chain, so every offset is one short
        text = f" {self.AN} {self.NAME} {self.SAID} {self.JOINER}"
        new, _ = mend({"arabic": text, "chain_links": [self.link(text, at=1)]})
        link = new["chain_links"][0]
        self.assertEqual(link["s"], text.index(self.NAME) - 1)
        self.assertEqual(self.covers(new["arabic"], link, at=1), self.NAME)

    def test_a_repair_inside_a_name_mends_the_name_too(self):
        damaged = self.NAME.replace("س", "��")
        text = f"{self.AN} {damaged} {self.SAID}"
        new, _ = mend({"arabic": text, "chain_links": [self.link(text, damaged)]},
                      [{"id": "x", "field": "arabic", "old": damaged, "new": self.NAME}])
        link = new["chain_links"][0]
        self.assertEqual(link["t"], self.NAME)
        self.assertEqual(self.covers(new["arabic"], link), self.NAME)

    def test_a_document_whose_spans_do_not_fit_is_refused(self):
        text = f"{self.AN} {self.NAME} {self.JOINER}"
        ishaq = "إسحاق"
        new = {"arabic": text, "chain_links": [{"p": "n1", "s": 3, "e": 10, "t": ishaq,
                                                "g": "ishaq"}]}
        with self.assertRaises(R.Refused):
            mend(new)

    def test_the_english_spans_follow_the_english_field(self):
        text = "from‌ Yunus who said"
        where = text.index("Yunus")
        new, _ = mend({"english": text,
                       "chain_links_en": [{"p": "n1", "s": where, "e": where + 5,
                                           "t": "Yunus", "g": "yunus"}]})
        link = new["chain_links_en"][0]
        self.assertEqual(new["english"][link["s"]:link["e"]], "Yunus")
        self.assertEqual(link["s"], where - 1)


class Selection(unittest.TestCase):

    def test_only_the_asked_for_class_runs(self):
        doc = {"arabic": "بقاء‌ه المستحاضةوالشراب"}
        new, counts = mend(doc, only={"invisible"})
        self.assertEqual(new["arabic"], "بقاءه المستحاضةوالشراب")
        self.assertEqual(counts["glue"], 0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
