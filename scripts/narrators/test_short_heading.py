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


# ── Run-on headings: the name with the entry's text cut off it ──────────────────────────
from index_people import name_only  # noqa: E402


def test_a_citation_is_cut_off_al_ardabilis_heading():
    """n035229 was published as this whole line, slug and all."""
    assert name_only("يحيى بن حبيب في الكافي عدة من أصحابنا عن سهل بن زياد عن أحمد بن عيسى"
                     ) == "يحيى بن حبيب"


def test_the_man_who_reports_from_him_is_cut_off_with_the_chain():
    assert name_only("سوادة القطان الحسن بن علي بن فضال عنه عن أبي الحسن الرضا عليه السلام في"
                     ) == "سوادة القطان"
    assert name_only("أبو بكر بن عبد الله الأشعري عبد المطلب بن زياد عنه عن أبي عبد الله"
                     ) == "أبو بكر بن عبد الله الأشعري"
    assert name_only("شرحبيل الكندي ابن مسكان عنه عن أبي عبد الله عليه السلام في") == "شرحبيل الكندي"


def test_a_name_repeated_after_an_is_where_the_subject_ends():
    assert name_only("يحيى الحذا زكريا الخزاز عن يحيى الحذا") == "يحيى الحذا"
    assert name_only("على بن عبد الله البجلي عمرو بن سعيد عن علي بن عبد الله عن أبي الحسن"
                     ) == "على بن عبد الله البجلي"


def test_a_reporter_named_after_anhu_is_not_the_subjects_name():
    assert name_only("محمد بن الحسين بن هارون الكندي الطحان الكوفي أبو جعفر عنه التلعكبري"
                     ) == "محمد بن الحسين بن هارون الكندي الطحان الكوفي أبو جعفر"


def test_a_report_leaves_a_one_word_name():
    assert name_only("واصل قال الكشي قال محمد بن مسعود حدثني أبو على المحمودي") == "واصل"


def test_a_describing_phrase_on_a_one_word_name_stays():
    assert name_only("إسحاق صاحب الحيتان") == "إسحاق صاحب الحيتان"
    assert name_only("يحيى أخو آدم") == "يحيى أخو آدم"


def test_the_later_books_praise_is_cut_off():
    assert name_only("مصطفى بن الحسن الحسيني التفرشي السيد الاجل جليل القدر رفيع الشأن"
                     ) == "مصطفى بن الحسن الحسيني التفرشي"


def test_the_printed_pages_marks_are_dropped_not_cut_at():
    assert name_only("سليمان بن المتوكل الغزال الكناسي - الكوفي") == "سليمان بن المتوكل الغزال الكناسي الكوفي"
    assert name_only("عبد الملك بن ms0871 المعافى") == "عبد الملك بن المعافى"


def test_a_variant_reading_in_quotation_marks_is_dropped():
    assert name_only('أحمر " أحمد " بن معاوية') == "أحمر بن معاوية"


def test_the_salutation_inside_a_name_is_not_prose():
    name = "أبو رافع مولى رسول اللّه صلّى اللّه عليه و آله و سلم"
    assert name_only(name).startswith("أبو رافع مولى رسول اللّه صلّى اللّه عليه و آله")


def test_a_compound_ism_is_not_prose():
    assert name_only("عبد الجليل بن أبي الحسين القزويني الشيخ الواعظ").startswith("عبد الجليل بن")


def test_a_long_lineage_is_left_alone():
    name = "سلامة بن محمد بن إسماعيل بن عبد الله بن موسى بن أبي الاكرم"
    assert name_only(name) == name
