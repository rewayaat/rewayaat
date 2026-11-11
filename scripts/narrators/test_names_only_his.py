"""python3 -m pytest scripts/narrators/test_names_only_his.py"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from index_people import names_only_his  # noqa: E402


def test_one_authoritys_name_comes_off_another_man():
    qulawayh = {"person_id": "n000339", "headings_ar": ["جعفر بن محمد بن قولويه"],
                "names_ar": ["ابن قولويه", "الشيخ الصدوق"], "normalized_ar": ["الشيخ الصدوق"],
                "names_en": ["Ibn Qulawayh", "al-Shaykh al-Saduq"], "titles": []}
    saduq = {"person_id": "n001075", "headings_ar": ["الصدوق"], "names_ar": ["الصدوق"],
             "names_en": ["Shaykh al-Saduq"], "titles": ["الشيخ الصدوق"]}
    names_only_his([qulawayh, saduq])
    assert qulawayh["names_ar"] == ["ابن قولويه"]
    assert qulawayh["normalized_ar"] == []
    assert qulawayh["names_en"] == ["Ibn Qulawayh"]
    assert saduq["titles"] == ["الشيخ الصدوق"]
    assert saduq["names_en"] == ["Shaykh al-Saduq"]


def test_whose_teacher_he_was_is_not_his_title():
    walid = {"person_id": "n001068", "headings_ar": ["محمد بن الحسن بن الوليد"],
             "titles": ["شيخ الصدوق", "شيخ القميين"]}
    names_only_his([walid])
    assert walid["titles"] == ["شيخ القميين"]
