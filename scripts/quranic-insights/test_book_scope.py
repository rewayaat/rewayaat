"""The Qur'anic-connection stages can run on a staging index, and stage 1 on chosen books.
Run from the repository root:

    python3 -m unittest discover -s scripts/quranic-insights -p 'test_book_scope.py'
"""
import importlib.util
import os
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
STAGED = {"generate_strict_judge_batches.py": "INDEX", "apply_strict_judge_filter.py": "INDEX",
          "generate_highlight_batches.py": "INDEX", "apply_highlighting.py": "LIGHT_INDEX",
          "build_quranic_light_index.py": "LIGHT_INDEX"}


def load(name):
    spec = importlib.util.spec_from_file_location(Path(name).stem, HERE / name)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class StagingIndexTest(unittest.TestCase):

    def tearDown(self):
        os.environ.pop("QURANIC_LIGHT_INDEX", None)

    def test_the_live_index_is_the_default(self):
        os.environ.pop("QURANIC_LIGHT_INDEX", None)
        for name, attr in STAGED.items():
            self.assertEqual(getattr(load(name), attr), "rewayaat_quranic_light_filtered", name)

    def test_a_staging_index_is_honoured(self):
        os.environ["QURANIC_LIGHT_INDEX"] = "rewayaat_quranic_light_v1restore"
        for name, attr in STAGED.items():
            self.assertEqual(getattr(load(name), attr), "rewayaat_quranic_light_v1restore", name)

    def test_stage_one_scopes_books(self):
        module = load("build_quranic_light_index.py")
        self.assertEqual(module.books_query(None), {"match_all": {}})
        self.assertEqual(module.books_query("Ghurar al-Ḥikam"), {"terms": {"book": ["Ghurar al-Ḥikam"]}})


if __name__ == "__main__":
    unittest.main()
