"""The book filter and index defaults the restore of the v1 books relies on. Run from the
repository root:

    python3 -m unittest discover -s scripts/similar -p 'test_book_scope.py'
"""
import importlib.util
import os
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SCOPED = ["scripts/tagging/export_for_tagging.py", "scripts/similar/llm_similar_hadith.py",
          "scripts/quranic-insights/build_quranic_light_index.py"]


def load(path):
    spec = importlib.util.spec_from_file_location(Path(path).stem, ROOT / path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class BookScopeTest(unittest.TestCase):

    def test_unset_means_every_book(self):
        for path in SCOPED:
            self.assertEqual(load(path).books_query(None), {"match_all": {}}, path)

    def test_a_list_of_books_limits_the_scroll(self):
        for path in SCOPED:
            self.assertEqual(load(path).books_query("Ghurar al-Ḥikam|Mīzān al-Ḥikma"),
                             {"terms": {"book": ["Ghurar al-Ḥikam", "Mīzān al-Ḥikma"]}}, path)

    def test_defaults(self):
        os.environ.pop("LLM_SIMILAR_PREFILTER_TOP", None)
        os.environ.pop("REWAYAAT_INDEX", None)
        self.assertEqual(load("scripts/similar/llm_similar_hadith.py").PREFILTER_TOP, 30)
        self.assertEqual(load("scripts/similar/load_llm_similar_to_es.py").ES_INDEX, "rewayaat_hadith")
        self.assertEqual(load("scripts/similar/llm_similar_hadith.py").ES_INDEX, "rewayaat_hadith")


if __name__ == "__main__":
    unittest.main()
