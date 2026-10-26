"""The roster's chain forms, and the ones it refuses on purpose."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import imams  # noqa: E402
from chain_resolve import key  # noqa: E402


def ident(text):
    return imams.identify(key(text))


def test_title_first_and_name_first_both_resolve():
    """The books write both orders. Only one was listed, so 251 chains naming
    al-Sadiq in full, 4 naming al-Sajjad and 4 naming al-Husayn went unresolved."""
    assert ident("جعفر بن محمد الصادق") == ("i06",)
    assert ident("الصادق جعفر بن محمد") == ("i06",)
    assert ident("زين العابدين علي بن الحسين") == ("i04",)
    assert ident("سيد الشهداء الحسين بن علي") == ("i03",)


def test_bare_kunya_and_title_still_resolve():
    assert ident("ابي عبد الله") == ("i06",)
    assert ident("الصادق") == ("i06",)
    assert ident("الرضا") == ("i08",)


def test_shared_kunyas_are_not_guessed():
    """Two Imams are Abu Ja'far and three are Abu al-Hasan. A chain that gives
    only the kunya has not said which, and the resolver must not decide for it."""
    assert ident("ابي جعفر") == ("i05", "i09")
    assert ident("ابي الحسن") == ("i07", "i08", "i10")
    assert ident("احدهما") == ("i05", "i06")


def test_a_name_that_is_nobody_on_the_roster():
    assert ident("محمد بن سنان") == ()
    assert ident("") == ()
