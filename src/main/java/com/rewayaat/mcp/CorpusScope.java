package com.rewayaat.mcp;

/**
 * The sentence every tool description has to carry.
 *
 * <p>The evaluation behind issue #66 found that a model cannot tell "exists in the Shia
 * tradition" from "exists in this corpus": one claim was rated high-confidence and was not
 * in these books at all. A tool that answers "no results" without saying what it searched
 * invites exactly that error to be restated as fact.
 *
 * <p>So the boundary is stated in the description of every tool, and again in the payload of
 * every empty result. We do not control the host's system prompt; the tool surface is the
 * only channel we have.
 */
public final class CorpusScope {

    /** Number of books indexed. Asserted against the live index by the MCP tool tests. */
    public static final int BOOK_COUNT = 18;

    public static final String SCOPE_SENTENCE =
            "This searches a closed corpus of " + BOOK_COUNT + " Shia hadith books "
            + "(Al-Kāfi, Man Lā Yaḥḍuruh al-Faqīh, Nahj al-Balāgha, Al-Amālī, Al-Khiṣāl, "
            + "Kitāb al-Ghayba, Thawāb al-Aʿmāl, ʿUyūn akhbār al-Riḍā, Maʿānī al-ʾAkhbār, "
            + "Kāmil al-Ziyārāt, Al-Tawḥīd, Muʿjam al-Aḥādīth al-Muʿtabara, Kitāb al-Zuhd, "
            + "Kitāb al-Ḍuʿafāʾ, Kitāb al-Muʾmin, Ṣifāt al-Shīʿa, Risālat al-Ḥuqūq, "
            + "Faḍaʾil al-Shīʿa). A narration that is absent is absent FROM THESE BOOKS; "
            + "that is not evidence it does not exist elsewhere in the tradition, and must "
            + "not be reported as such.";

    /** The note attached to an empty result set, where the distinction actually bites. */
    public static final String NO_RESULTS_NOTE =
            "No match in these " + BOOK_COUNT + " books. This is an authoritative negative "
            + "for this corpus only - do not restate it as 'this narration does not exist'.";

    private CorpusScope() {
    }
}
