"""python3 -m pytest scripts/narrators/test_page_text_tidy.py

The words a narrator page prints about a man, as the extraction left them and as the page
should print them: his city or tribe, his other names, his English heading, the Imams in his
teachers list and the rows that are himself.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import index_people as ip  # noqa: E402
from relations_join import not_himself  # noqa: E402

ASAD = [{"type": "place", "en": "Kufa", "ar": "الكوفة"},
        {"type": "tribe", "en": "Banu Asad", "ar": "بني أسد"},
        {"type": "client", "en": "Banu Udayy", "ar": "بني عدي"}]


def test_the_city_reads_as_a_sentence_in_both_languages():
    assert ip.city_phrase(ASAD) == "of Kufa, of Banu Asad, a client of Banu Udayy"
    assert ip.city_phrase(ASAD, arabic=True) == "من الكوفة، من بني أسد، مولى بني عدي"


def test_the_panel_names_a_tribe_as_it_stands_alone():
    assert ip.city_label(ASAD) == "Kufa, Banu Asad, client of Banu Udayy"
    assert ip.city_label(ASAD, arabic=True) == "الكوفة، بنو أسد، مولى بني عدي"


def test_a_part_with_no_arabic_is_left_off_the_arabic_page():
    parts = [{"type": "place", "en": "Kufa", "ar": "الكوفة"},
             {"type": "tribe", "en": "Hamran", "ar": None}]
    assert ip.city_label(parts, arabic=True) == "الكوفة"
    assert ip.city_label(parts) == "Kufa, Hamran"


def test_a_value_the_table_has_not_read_is_dropped_when_it_hedges():
    ip.CITY_CLEAN.clear()
    assert ip.city_parts("Kufa (implied by Asadi)") == []
    assert ip.city_parts("Samarra?") == []
    assert ip.city_parts("Kufa (city)") == [{"type": "place", "en": "Kufa", "ar": "الكوفة"}]


def test_a_note_comes_off_an_alias_and_a_slip_is_marked():
    assert ip.without_note("موسى بن زيد (variant reading)") == ("موسى بن زيد", False)
    assert ip.without_note("الحسين بن الحسن اللؤلؤي (erroneous)") == ("الحسين بن الحسن اللؤلؤي", True)
    assert ip.without_note("إبراهيم بن عيسى أبو أيوب (المتقدم)") == ("إبراهيم بن عيسى أبو أيوب", False)
    assert ip.without_note("الخزاز (الخراز) الكوفي") == ("الخزاز (الخراز) الكوفي", False)


def test_what_is_not_a_name_is_no_alias():
    assert ip.without_note("أبيه (in context of son)") is None
    assert ip.without_note("Ibrahim ibn Musa ibn ukht al-Waqidi") is None
    assert ip.without_note("Muhammad ibn Dawawayh (Rawzawi?)", arabic=False) == \
        ("Muhammad ibn Dawawayh", False)


def test_the_english_heading_loses_its_guess_and_keeps_one_honorific():
    assert ip.english_heading_tidy("Sulayman al-Himmar (The Donkey Driver?)") == "Sulayman al-Himmar"
    assert ip.english_heading_tidy("Zaynab bint Ali (A)") == "Zaynab bint Ali (ʿa)"
    assert ip.english_heading_tidy("Muhammad ibn Yahya ibn Salman (Sulayman)") == \
        "Muhammad ibn Yahya ibn Salman (Sulayman)"


def test_an_english_kunya_with_its_blessing_is_the_imam():
    assert ip.which_imam("Abu al-Hasan (AS)", {7}) == "i07"
    assert ip.which_imam("Abu Ja'far (AS)", {5, 6}) == "i05"
    assert ip.which_imam("Abu al-Hasan (al-Kazhim)", {6, 7}) == "i07"
    assert ip.which_imam("Amir al-Mu'minin (Ali)", {1}) == "i01"


def test_a_man_with_an_imams_kunya_stays_a_man():
    assert ip.which_imam("Abu al-Hasan al-Sawaq", {6}) is None


def test_a_row_that_is_himself_goes_and_one_naming_his_son_loses_the_link():
    aban = {"person_id": "n000023", "display_ar": "أبان بن تغلب", "names_ar": [],
            "headings_ar": ["أبان بن تغلب"], "display_en": "Aban ibn Taghlib", "names_en": []}
    rows = [{"name": "أبان بن تغلب", "person_id": "n000023"},
            {"name": "أبان بن محمد بن أبان بن تغلب (his son)", "person_id": "n000023",
             "slug": "aban"},
            {"name": "Ibrahim ibn 'Umar", "person_id": "n000023"},
            {"name": "زرارة", "person_id": "n000488"}]
    kept = not_himself(aban, "narrated_to", rows[:2] + rows[3:])
    assert [r["name"] for r in kept] == ["أبان بن محمد بن أبان بن تغلب (his son)", "زرارة"]
    assert "person_id" not in kept[0] and "slug" not in kept[0]
    assert not_himself(aban, "chain_from", [rows[2]]) == []


def test_an_imams_title_outranks_the_guard_for_ordinary_names():
    # a companion of al-Ṣādiq whose teacher is «Abu al-Hasan (al-Kazhim)»: the title says
    # which Imam, though the man's own generations stop before al-Kāẓim
    assert ip.titled("Abu al-Hasan (al-Kazhim)", "i07")
    assert ip.titled("al-Riḍā (Imam)", "i08")
    assert not ip.titled("Abu al-Hasan (AS)", "i07")
    assert not ip.titled("al-Husayn", "i03")
