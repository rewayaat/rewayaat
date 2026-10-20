"""Quote-based highlighting wraps the reader's exact sentences and never changes the commentary.
Run from the repository root:

    python3 -m unittest discover -s scripts/quranic-insights -p 'test_apply_highlighting.py'
"""
import importlib.util
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("apply_highlighting", HERE / "apply_highlighting.py")
apply_highlighting = importlib.util.module_from_spec(spec)
spec.loader.exec_module(apply_highlighting)
wrap = apply_highlighting.wrap_quotes

TEXT = "God is near. He answers the caller when he calls. So let them respond to Me."


class WrapQuotesTest(unittest.TestCase):
    def test_wraps_each_quote_and_keeps_the_text(self):
        out = wrap(TEXT, ["He answers the caller when he calls.", "God is near."])
        self.assertEqual(out, "<em>God is near.</em> <em>He answers the caller when he calls.</em> "
                              "So let them respond to Me.")
        self.assertEqual(out.replace("<em>", "").replace("</em>", ""), TEXT)

    def test_a_quote_not_in_the_text_rejects_the_whole_entry(self):
        self.assertIsNone(wrap(TEXT, ["God is near.", "He answers every caller."]))

    def test_surrounding_whitespace_in_a_quote_is_ignored(self):
        self.assertEqual(wrap(TEXT, ["  God is near. "]), "<em>God is near.</em>" + TEXT[len("God is near."):])

    def test_a_repeated_quote_takes_the_next_occurrence(self):
        self.assertEqual(wrap("a. a.", ["a.", "a."]), "<em>a.</em> <em>a.</em>")

    def test_no_quotes_is_rejected(self):
        self.assertIsNone(wrap(TEXT, []))


if __name__ == "__main__":
    unittest.main()
