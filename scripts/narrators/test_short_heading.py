"""A page's heading is the name a book files a man under, not the lineage an entry opens on."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from index_people import english_for_heading, short_heading  # noqa: E402
import transliterate  # noqa: E402

TABLE = transliterate.load()

# al-Najāshī 787, cut after eight links; the real entry runs to ʿAdnān.
ADHINAH = ("عمر بن محمد بن عبد الرحمن بن أذينة بن سلمة بن الحارث بن خالد "
           "بن عائذ بن سعد بن ثعلبة")
ADHINAH_HEADINGS = ["ابن أذينة", "ابن أذينة البصري", "عمر بن أذينة",
                    "عمر بن محمد بن عبد الرحمن بن أذينة"]


def test_a_lineage_gives_way_to_the_name_the_books_cite():
    assert short_heading(ADHINAH, ADHINAH_HEADINGS) == "عمر بن أذينة"


def test_a_name_of_a_names_length_is_left_alone():
    name = "إبراهيم بن سليمان بن عبد الله"
    assert short_heading(name, ["إبراهيم"]) == name


def test_the_fullest_heading_that_fits_wins_over_the_shortest():
    """Two men are «إسماعيل بن محمد»; only the longer heading tells them apart."""
    long_name = "إسماعيل بن محمد بن إسحاق بن جعفر بن محمد بن علي بن الحسين"
    headings = ["إسماعيل بن محمد", "إسماعيل بن محمد بن إسحاق",
                "إسماعيل بن محمد بن إسحاق بن جعفر"]
    assert short_heading(long_name, headings) == "إسماعيل بن محمد بن إسحاق بن جعفر"


def test_a_heading_adding_a_nisba_the_nasab_lacks_is_not_promoted():
    assert short_heading(ADHINAH, ["عمر بن أذينة الكوفي"]) == ADHINAH


def test_a_heading_under_another_ism_is_not_his_short_name():
    assert short_heading(ADHINAH, ["محمد بن عبد الرحمن بن أذينة"]) == ADHINAH


def test_a_bare_house_name_is_not_enough():
    assert short_heading(ADHINAH, ["ابن أذينة"]) == ADHINAH


def test_no_heading_qualifying_leaves_the_lineage_rather_than_cutting_it():
    assert short_heading(ADHINAH, []) == ADHINAH


def test_prose_in_a_heading_disqualifies_it():
    headings = ["عمر بن أذينة وسبب خروجه إلى الموضع الذي"]
    assert short_heading(ADHINAH, headings) == ADHINAH


def test_a_books_own_spelling_is_preferred_to_ours():
    english, made = english_for_heading("عمر بن أذينة",
                                        ["Umar ibn Adhinah", "Ibn Adina"], TABLE)
    assert english == "Umar ibn Adhinah"
    assert made is False


def test_we_spell_it_ourselves_when_no_book_did():
    english, made = english_for_heading("عمر بن أذينة", ["Ibn Adina"], TABLE)
    assert english == "Umar ibn Adhinah"
    assert made is True


def test_al_khoei_s_nisba_after_the_colon_is_his_heading():
    """«عمرو بن شمر: اليماني:» is a second man, told from al-Juʿfī by the nisba alone."""
    from index_people import khoei_nisba
    entry = {"book": "khoei", "heading_ar": "عمرو بن شمر",
             "text_ar": "عمرو بن شمر: اليماني: يرفع الحديث إلى علي عليه السلام"}
    assert khoei_nisba(entry) == ["اليماني"]
    entry = {"book": "khoei", "heading_ar": "إبراهيم بن سليمان القطيفي",
             "text_ar": "إبراهيم بن سليمان القطيفي: الشيخ: فاضل، عالم"}
    assert khoei_nisba(entry) == []
    entry = {"book": "khoei", "heading_ar": "أبو الحسين بن الشيبة",
             "text_ar": "أبو الحسين بن الشيبة: العلوي، الزيدي المذهب: ذكره النجاشي"}
    assert khoei_nisba(entry) == ["العلوي"]


def test_shimr_is_spelled_shimr():
    assert transliterate.respell("عمرو بن شمر", "Amr ibn Shumar") == "Amr ibn Shimr"
    assert transliterate.respell("أبو شمر بن أبرهة بن الصباح", "Abu Shamar") == "Abu Shamar"
