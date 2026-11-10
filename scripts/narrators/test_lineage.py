"""python3 -m pytest scripts/narrators/test_lineage.py"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lineage import foreign, opening_name  # noqa: E402


BARQI = ["أحمد بن محمد بن خالد البرقي", "أحمد بن أبي عبد الله البرقي"]


def test_another_mans_full_name_is_foreign():
    assert foreign("محمد بن عيسى", BARQI)
    assert foreign("محمّد بن علي", ["محمد بن يحيى أبو جعفر العطار القمي"])
    assert foreign("أبان بن عثمان", ["أبان بن تغلب بن رباح"])


def test_a_title_and_kunya_before_the_name_do_not_hide_it():
    assert foreign("الشيخ أبي الحسين أحمد بن الحسين بن عبيد اللّه الغضائري",
                   ["إسماعيل بن مهران بن أبي نصر السكوني"])


def test_his_own_names_are_his():
    assert not foreign("أحمد بن أبي عبد الله البرقي", BARQI)
    assert not foreign("سهل ابن زياد", ["سهل بن زياد"])


def test_a_copyists_variant_is_his():
    # taṣḥīf under the dots, one letter, the prefix
    assert not foreign("جرير بن عبد الله السجستاني", ["حريز بن عبد الله السجستاني"])
    assert not foreign("الحسين بن محبوب", ["الحسن بن محبوب السراد"])
    assert not foreign("الفضل بن صالح", ["مفضل بن صالح"])


def test_a_generation_skipped_or_added_is_his():
    assert not foreign("محمد بن الحسن بن سنان", ["محمد بن سنان"])
    assert not foreign("إسماعيل بن زياد السكوني", ["اسماعيل بن ابي زياد السكوني"])


def test_nothing_to_judge_by_is_not_foreign():
    assert not foreign("أبو بصير", ["يحيى بن القاسم"])
    assert not foreign("زرارة", ["زرارة بن أعين"])
    assert not foreign("محمد بن مسلم", ["الثقفي"])


def test_a_passage_opening_with_another_heading_is_caught():
    passage = "الحسين (الحسن) بن زيدان الصرمي له نوادر، أخبرنا محمد بن علي"
    assert foreign(opening_name(passage), ["الحسين بن سعيد"])
    assert not foreign(opening_name("الحسين بن سعيد بن حماد، ثقة"), ["الحسين بن سعيد"])


def test_english_another_mans_name_is_foreign():
    from lineage import foreign_en
    attar = ["Muhammad ibn Yahya Abu Ja'far al-'Attar"]
    assert foreign_en("Muhammad ibn Ali", attar)
    assert not foreign_en("Muhammad b. Yahya al-Attar", attar)
    assert not foreign_en("Abdullah ibn Jafar al-Himyari", ["'Abd Allah ibn Ja'far al-Hamiri"])
    assert not foreign_en("Ma'ruf ibn Kharrabudh", ["Ma'ruf bin Kharbud"])


def test_a_son_of_somebody_else_is_not_his_name():
    from lineage import foreign_ibn
    isms = {"سعيد", "جبله", "زياد", "علي"}
    others = {"بن عقده"}.__contains__
    assert foreign_ibn("ابن عقدة", ["إبراهيم بن عمر اليماني"], [], others, isms)
    assert foreign_ibn("ابن سعيد", ["أبان بن تغلب بن رباح"], [], others, isms)
    assert foreign_ibn("ابن أخي", ["علي بن إسماعيل بن جعفر"], [], others, isms)
    # his own heading, his own lineage, a laqab of his family
    assert not foreign_ibn("ابن عقدة", ["أحمد بن محمد بن سعيد"], ["ابن عقدة"], others, isms)
    assert not foreign_ibn("ابن زياد", ["سهل بن زياد"], [], others, isms)
    assert not foreign_ibn("ابن السراج", ["أحمد بن إبراهيم بن المعلى"], [], others, isms)


def test_chain_words_are_not_part_of_a_name():
    from lineage import without_chain_words
    assert without_chain_words("حدثني محمد بن قولويه") == "محمد بن قولويه"
    assert without_chain_words("عن هشام بن سالم") == "هشام بن سالم"
    assert without_chain_words("عن سمرة، عن أبي سعيد") is None
    assert without_chain_words("عنبسة بن مصعب") == "عنبسة بن مصعب"


def test_the_nisbas_a_name_ends_in():
    from lineage import nisbas, nisbas_en
    assert nisbas("محمد بن الحسن بن علي الطوسي") == ["الطوسي"]
    assert nisbas("عمار بن موسى الساباطي") == ["الساباطي"]
    assert nisbas("الحسين بن سعيد بن حماد بن سعيد الأهوازي") == ["الأهوازي"]
    assert nisbas("علي بن الحسن") == []
    assert nisbas("محمد بن يحيى أبو جعفر العطار القمي") == ["العطار", "القمي"]
    assert nisbas("عبد الله بن عبد الرحمن") == []
    assert nisbas("على بن محمد بن قتيبة النيشابوري عليه اعتمد أبو عمرو الكشي") == ["النيشابوري"]
    assert nisbas("أبو عمرو الكشي") == ["الكشي"]
    assert nisbas_en("Muhammad ibn al-Hasan ibn Ali al-Tusi") == ["al-Tusi"]
    assert nisbas_en("Ali ibn al-Hasan") == []
