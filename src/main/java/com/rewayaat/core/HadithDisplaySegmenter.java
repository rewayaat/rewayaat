package com.rewayaat.core;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Splits narration text into chain (isnad) and content (matn) for display.
 */
public final class HadithDisplaySegmenter {

    private static final int MAX_SCAN_CHARS = 2200;
    private static final int MATN_LOOKAHEAD_CHARS = 220;
    private static final int MIN_CHAIN_VISIBLE_CHARS = 12;
    private static final int MIN_CONTENT_VISIBLE_CHARS = 8;
    private static final int NEAR_AN_OPENING_CHARS = 60;
    private static final int NAMED_WORDS = 3;
    private static final int MAX_CHAIN_HEAD_CHARS = 400;
    private static final double CHAIN_BAND_LOW = 0.6;
    private static final double CHAIN_BAND_HIGH = 4.0;
    private static final int CHAIN_BAND_FLOOR = 120;
    private static final int CHAIN_CUE_SPACING = 120;

    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern MULTISPACE_PATTERN = Pattern.compile("\\s+");
    private static final Pattern SIGNAL_DELIMITERS_PATTERN = Pattern.compile("[،,;:\\-]+");
    private static final Pattern ENGLISH_DIALOGUE_BOUNDARY_PATTERN = Pattern.compile(
            "(?i)(?:\\bthen\\s+i\\s+said\\b|\\bi\\s+said\\b|\\bthen\\s+i\\s+asked\\b|\\bi\\s+asked\\b|"
                    + "\\bi\\s+heard\\b|\\bhe\\s+heard\\b|\\bshe\\s+heard\\b|"
                    + "\\bi\\s+saw\\b|\\bi\\s+entered\\b|\\bi\\s+wrote\\b|\\bi\\s+was\\s+(?!told\\b|informed\\b)\\w|"
                    + "\\bmy\\s+father\\s+(?:once\\s+)?(?:asked|said|heard|saw|entered|wrote)\\b|"
                    + "\\ba\\s+man\\s+(?:entered|came)\\b|\\ba\\s+woman\\s+(?:entered|came)\\b|"
                    + "\\bwhen\\s+a\\s+man\\s+(?:entered|came)\\b|\\bon\\s+the\\s+day\\s+when\\b)");

    private static final String[] ENGLISH_SPLIT_MARKERS = new String[] {
            " [who narrated] that ",
            " who narrated that ",
            " that the ",
            " saying,",
            " saying: ",
            " who has said that he heard ",
            " who has said that ",
            " who has said the following ",
            " who has said the following:",
            " who has said the following.",
            " has narrated the following ",
            " has narrated the following:",
            " has narrated the following.",
            " who has said the ",
            " who has said:",
            " has said the following:",
            " has said the following.",
            " had said that ",
            " had said:",
            " had said,",
            " was asked by ",
            " was asked: ",
            " was asked, ",
            " who was asked: ",
            " who was asked, ",
            " asked: “",
            " asked: \"",
            " that i asked ",
            " that i wrote ",
            " that i said ",
            " that he asked ",
            " that he wrote ",
            " that she asked ",
            " that \"",
            " that “",
            " that '",
            " that ‘",
            " that: ",
            " that a man ",
            " the following is narrated: ",
            " who said:",
            " who said,",
            " the imam (as) said,",
            " the imam (a.s.) said,",
            " imam (as) said,",
            " imam (a.s.) said,",
            " said, “",
            " said, \"",
            " said, ‘",
            " said, '",
            " said to me:",
            " said to him:",
            " say, “",
            " say, \"",
            " say:",
            " he said:",
            " he has said:",
            " she said:",
            " they said:",
            " saying:",
            " said the following:",
            " said:",
            // "that he said:" / "that he said " — chain attribution ending before matn
            " that he said: ",
            " that he said ",
            // Split markers for "that he [narrative action]" where action ≠ said
            " that he mentioned ",
            " that he saw ",
            " that he heard ",
            " that he asked ",
            " that he wrote ",
            " that he entered ",
            " that he came ",
            " that she mentioned ",
            " that she saw ",
            " that she heard ",
            " that they mentioned ",
            " that they saw ",
            " that they heard ",
            // al-Saduq's translator closes a chain on the last link and runs straight into
            // the matn: «… on the authority of Ali (s) that God's Prophet said …». No marker
            // reached that, so those narrations came out with no English isnad at all and
            // not one of their names could be linked. A bare «that» was tried first and
            // measured: it gained 301 chains and reshaped 2,971, reading Ibn al-Ghada'iri's
            // preface and Ali's own words in Nahj al-Balagha as isnads. What is safe is the
            // blessing before it — «(s) that», «(as) that» — which closes a name and cannot
            // open a sermon.
            " (s) that ",
            " (as) that ",
            " (a.s.) that ",
            " (sa) that ",
            " (saw) that ",
            // Split marker for "It has been narrated/reported that [name] [action]"
            // When followed by proper name + action (not "said"), split at "that"
            "it has been narrated that ",
            "it has been reported that ",
            "it was narrated that ",
            "it is narrated that ",
            "it is narrated from ",
            "it is reported that "
    };

    private static final String[] ENGLISH_CHAIN_CUES = new String[] {
            " narrated ",
            " has narrated ",
            " narrated to us",
            " narrated to me",
            " narrated to him",
            " narrated from",
            " by this chain",
            " through this chain",
            " through the same chain",
            " by this isnad",
            " with this isnad",
            " chain of narrators",
            " from the previous narration",
            " from the previous hadith",
            " it has been narrated that ",
            " it has been reported that ",
            " it has been related that ",
            " it is narrated from ",
            " it is reported that ",
            " reported from",
            " on the authority of ",
            " were told by ",
            " was told by ",
            " told by ",
            " quoted ",
            " from "
    };

    private static final String[] ENGLISH_CHAIN_STRONG_CUES = new String[] {
            " narrated ",
            " has narrated ",
            " narrated to us",
            " narrated to me",
            " narrated to him",
            " narrated from",
            " narrated that ",
            " quoted ",
            " related ",
            " were told by ",
            " was told by ",
            " told by ",
            " by this chain",
            " through this chain",
            " through the same chain",
            " chain of narrators",
            " chain of transmission",
            " from the previous narration",
            " from the previous hadith",
            " by this isnad",
            " with this isnad",
            " it has been narrated that ",
            " it was narrated that ",
            " it has been reported that ",
            " it has been related that ",
            " it is narrated from ",
            " it is reported that ",
            " reported from",
            " reported by ",
            " on the authority of "
    };

    private static final String[] ARABIC_SPLIT_MARKERS = new String[] {
            "قالت:",
            "قالت ",
            "فقالت:",
            "فقالت ",
            "قالوا:",
            "قالوا ",
            "فقالوا:",
            "فقالوا ",
            // The dual. Two men at the end of a chain answer together — «عن أبي جعفر وأبي
            // عبد الله (عليهما السلام) قالا» — and «قالا» is neither «قال» nor «قالوا», so
            // 92 chains of this shape found no marker at all and came out with no chain.
            "قالا:",
            "قالا ",
            "فقالا:",
            "فقالا ",
            "قالتا:",
            "قالتا ",
            " قالت:",
            " قالت ",
            " فقالت:",
            " فقالت ",
            " قالوا:",
            " قالوا ",
            " فقالوا:",
            " فقالوا ",
            " قالا:",
            " قالا ",
            " فقالا:",
            " فقالا ",
            " قالتا:",
            " قالتا ",
            "قال:",
            "قال ",
            "فقال:",
            "فقال ",
            " قال:",
            " قال ",
            " فقال:",
            " فقال ",
            "سئل عليه السلام ",
            " سئل عليه السلام ",
            "سئل عليهما السلام ",
            " سئل عليهما السلام ",
            " أنه قال",
            " انه قال",
            "سمعت ",
            " سمعت ",
            "سمعته ",
            " سمعته ",
            "سالت ",
            " سالت ",
            "ساله ",
            " ساله ",
            "سالته ",
            " سالته ",
            "كنت ",
            " كنت ",
            "دخلت ",
            " دخلت ",
            "كتبت ",
            " كتبت ",
            "رايت ",
            " رايت ",
            "قلت:",
            "قلت ",
            " قلت:",
            " قلت "
    };

    private static final String[] ARABIC_CHAIN_CUES = new String[] {
            " وحدثنا ",
            " وحدّثنا ",
            " وحدثني ",
            " وحدّثني ",
            " حدثنا ",
            " حدّثنا ",
            " حدثني ",
            " حدّثني ",
            " واخبرنا ",
            " وأخبرنا ",
            " واخبرني ",
            " وأخبرني ",
            " اخبرنا ",
            " أخبرنا ",
            " اخبرني ",
            " أخبرني ",
            " بهذا الاسناد ",
            " وبهذا الاسناد ",
            " بهذا الإسناد ",
            " وبهذا الإسناد ",
            " بإسناده ",
            " وبإسناده ",
            " باسناده ",
            " وباسناده ",
            " وعن ",
            " وعنه ",
            " عنه ",
            " عن ",
            " روي ",
            " وروي ",
            " روى ",
            " رواه ",
            " ورواه ",
            " رفعه ",
            " رفعاه ",
            " سمعت "
    };

    // Stricter chain cues for tail analysis — excludes "عن" which is ambiguous in matn
    // (e.g., "سأله عن كذا" means "about", not a chain link)
    private static final String[] ARABIC_CHAIN_CUES_STRICT = new String[] {
            " وحدثنا ",
            " وحدّثنا ",
            " وحدثني ",
            " وحدّثني ",
            " حدثنا ",
            " حدّثنا ",
            " حدثني ",
            " حدّثني ",
            " واخبرنا ",
            " وأخبرنا ",
            " واخبرني ",
            " وأخبرني ",
            " اخبرنا ",
            " أخبرنا ",
            " اخبرني ",
            " أخبرني ",
            " بهذا الاسناد ",
            " وبهذا الاسناد ",
            " بهذا الإسناد ",
            " وبهذا الإسناد ",
            " بإسناده ",
            " وبإسناده ",
            " باسناده ",
            " وباسناده "
    };

    // How an entry opens, where the opening is itself the whole of what the book offers by
    // way of a chain. A prefix that begins this way needs one chain signal rather than two.
    private static final String[] ARABIC_CHAIN_OPENINGS = new String[] {
            "وعن",
            "عن",
            "عنه",
            "وبإسناده",
            "بإسناده",
            "وباسناده",
            "باسناده",
            "وبهذا الاسناد",
            "بهذا الاسناد",
            "وبهذا الإسناد",
            "بهذا الإسناد",
            "حدثنا",
            "حدّثنا",
            "حدثني",
            "حدّثني",
            "أخبرنا",
            "اخبرنا",
            "أخبرني",
            "اخبرني",
            "روي",
            "وروي",
            "روى",
            "وروى"
    };

    // A route the book quotes rather than walks: «و رواه صافي البرقي عن أبي عبد الله (ع)»,
    // «و في رواية زرارة عن أبي جعفر (عليه السلام)». Admitted on the same terms, but only
    // near the head of the entry — see minArabicChainScore.
    private static final String[] ARABIC_QUOTED_ROUTE_OPENINGS = new String[] {
            "رواه",
            "ورواه",
            "في رواية",
            "وفي رواية"
    };

    private static final String[] ARABIC_BLESSINGS = new String[] {
            "عليه السلام",
            "عليها السلام",
            "عليهما السلام",
            "عليهم السلام",
            "صلي الله عليه واله",
            "صلي الله عليه و اله",
            "صلوات الله عليه",
            "صلوات الله عليه واله",
            "رحمه الله",
            "ع",
            "ص"
    };

    // Written over a name rather than about a man, so none of it counts towards the
    // narration having begun — see saidByThePersonNamed.
    private static final String[] ARABIC_BLESSING_WORDS = new String[] {
            "الله", "عليه", "عليها", "عليهم", "عليهما", "السلام", "صلي", "صلوات", "وسلم",
            "واله", "اله", "و", "ع", "ص", "رحمه", "رحمة", "تعالي", "عز", "جل", "رضي",
            "عنه", "ره", "قدس", "سره", "لعنه", "عليك"
    };

    private static final String[] ARABIC_DIALOGUE_CUES = new String[] {
            "انه سمع",
            "اخبرني من",
            "في رجل",
            "ثم قلت",
            "فقلت",
            "قلت",
            "سالت",
            "ساله",
            "سالته",
            "سالني",
            "سمع",
            "سمعت",
            "سمعته",
            "بينما",
            "كان",
            "كانت",
            "كانوا",
            "دخلت",
            "كنت",
            "كتبت",
            "رايت",
            "بعث الي",
            "بعث اليه",
            "قال يا",
            "قال لي",
            "قال له",
            "قال لها",
            "قال لهم",
            "فدخل عليه رجل",
            "دخل عليه رجل",
            "قام رجل",
            "قامت امرأة",
            "سئل الصادق",
            "سئل ابو",
            "سئل ابا",
            // "أنه [action]" patterns where action starts narrative (not reporting)
            "أنه ذكر",
            "انه ذكر",
            "أنه رأى",
            "انه رأى",
            "أنه سأل",
            "انه سأل",
            "أنه كتب",
            "انه كتب",
            "أنه دخل",
            "انه دخل",
            "أنه صار",
            "انه صار",
            "أنه أقبل",
            "انه أقبل",
            "أنه ذهب",
            "انه ذهب",
            "أنه لما",
            "انه لما",
            "أنه قال",
            "انه قال"
    };

    private static final String[] ARABIC_DIRECT_CHAIN_CONTINUATIONS = new String[] {
            " حدثنا ",
            " حدّثنا ",
            " حدثني ",
            " حدّثني ",
            " اخبرنا ",
            " أخبرنا ",
            " اخبرني ",
            " أخبرني "
    };

    private HadithDisplaySegmenter() {
    }

    public static void enrich(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return;
        }
        // The Arabic goes first because the English split is measured against it: the
        // English isnād is a translation of the Arabic one, not a second discovery, so its
        // length is known within a factor before a single marker is looked for.
        splitAndAttach(source, "arabic", true);
        splitAndAttach(source, "english", false);
    }

    private static void splitAndAttach(Map<String, Object> source, String field, boolean arabic) {
        Object rawValue = source.get(field);
        if (!(rawValue instanceof String)) {
            return;
        }
        String raw = ((String) rawValue).trim();
        if (raw.isEmpty()) {
            return;
        }
        VisibleText visibleText = toVisibleText(raw);
        String visible = visibleText.visible();
        if (visible.isEmpty()) {
            return;
        }
        if (arabic) {
            SplitResult route = splitMashyakha(raw, visible, visibleText);
            if (route.hasSplit()) {
                source.put(field + "Chain", route.chain());
                source.put(field + "Content", route.content());
                return;
            }
        }
        int visibleSplit = arabic ? findArabicSplit(visible)
                : findEnglishSplit(visible, arabicChainChars(source));
        if (visibleSplit <= 0) {
            return;
        }
        int rawSplit = moveSplitBoundaryOutsideHtml(raw, visibleText.toRawBoundary(visibleSplit));
        SplitResult split = buildSplit(raw, rawSplit);
        if (!split.hasSplit()) {
            return;
        }
        source.put(field + "Chain", split.chain());
        source.put(field + "Content", split.content());
    }

    /**
     * al-Ṣadūq's mashyakha, where the chain stands after what it is a chain for.
     *
     * <p>Every other entry in the corpus runs isnād then matn, and the whole splitter is
     * built on finding where the first ends. The Faqīh's fifth volume runs the other way
     * round: «و ما كان فيه عن بشير النبّال فقد رويته عن محمّد بن عليّ ماجيلويه عن محمّد بن
     * يحيى العطّار عن إبراهيم بن هاشم عن محمّد بن سنان عن بشير النبّال» — first the men
     * whose narrations the route covers, then «فقد رويته عن» and the route itself. There is
     * no matn at all; the entry is nothing but a ṭarīq.
     *
     * <p>So it cannot be reached by a split marker, which always keeps the head as the
     * chain, and these 377 entries came out with no chain at all. They are the ṭuruq a
     * rijālī reaches for — al-Khoei's method leans on them — and leaving them unread was
     * the largest recoverable gap in the chain links.
     *
     * <p>The clause that names whom the route is for becomes the content, and everything
     * from «رويته عن» onward becomes the chain, so the offsets a link carries are offsets
     * into the route and nothing else.
     */
    private static SplitResult splitMashyakha(String raw, String visible, VisibleText visibleText) {
        ArabicMatchText matchText = normalizeArabicForMatching(visible);
        String normalized = matchText.normalized();
        if (normalized.isEmpty()) {
            return SplitResult.none();
        }
        // «و ما كان فيه عن فلان» is the usual opening, and «ما كان في هذا الكتاب عن فلان»
        // the fuller one. The clause has to stand at the head of the entry: the same words
        // in the middle of a narration are somebody talking, not al-Ṣadūq listing a route.
        int opening = -1;
        for (String cue : new String[] { "ما كان فيه عن ", "ما كان في هذا الكتاب عن ",
                                         "ما كان فيه من ", "ما كان في هذا الكتاب من " }) {
            int at = normalized.indexOf(cue);
            if (at >= 0 && at <= 40 && (opening < 0 || at < opening)) {
                opening = at;
            }
        }
        if (opening < 0) {
            return SplitResult.none();
        }
        int route = -1;
        for (String cue : new String[] { " فقد رويته عن ", " رويته عن ", " فقد رويت ذلك عن " }) {
            int at = normalized.indexOf(cue, opening);
            if (at >= 0 && (route < 0 || at < route)) {
                route = at + cue.length() - " عن ".length();
            }
        }
        if (route < 0) {
            return SplitResult.none();
        }
        int visibleBoundary = matchText.toRawBoundary(route);
        int rawBoundary = moveSplitBoundaryOutsideHtml(raw, visibleText.toRawBoundary(visibleBoundary));
        if (rawBoundary <= 0 || rawBoundary >= raw.length()) {
            return SplitResult.none();
        }
        String content = trimContent(raw.substring(0, rawBoundary));
        String chain = trimChain(raw.substring(rawBoundary));
        if (visibleLength(chain) < MIN_CHAIN_VISIBLE_CHARS
                || visibleLength(content) < MIN_CONTENT_VISIBLE_CHARS) {
            return SplitResult.none();
        }
        return new SplitResult(chain, content);
    }

    private static int findEnglishSplit(String raw, int arabicChainChars) {
        String lower = raw.toLowerCase(Locale.ROOT);
        int bestSplit = -1;
        int bestIndex = -1;
        int fallbackSplit = -1;
        int fallbackIndex = -1;
        for (String marker : ENGLISH_SPLIT_MARKERS) {
            int index = lower.indexOf(marker);
            while (index >= 0 && index <= MAX_SCAN_CHARS) {
                String prefix = lower.substring(0, index);
                int score = englishChainSignalScore(prefix);
                if (score >= minEnglishChainScore(prefix)) {
                    int split = index + marker.length();
                    int maxTailSignals = maxEnglishTailSignals(marker);
                    if (" that a man ".equals(marker)) {
                        split = index + " that ".length();
                    }
                    if (isLikelyMatnBoundary(lower, split, ENGLISH_CHAIN_CUES, maxTailSignals)) {
                        // Skip "said:" when it's part of "that he said:" / "who said:" / etc.
                        if (isSubsumedByLongerMarker(lower, index, marker)) {
                            index = lower.indexOf(marker, index + marker.length());
                            continue;
                        }
                        if (bestSplit < 0 || index < bestIndex || (index == bestIndex && split > bestSplit)) {
                            bestIndex = index;
                            bestSplit = split;
                        }
                    } else if (fallbackSplit < 0 || index < fallbackIndex || (index == fallbackIndex && split > fallbackSplit)) {
                        fallbackIndex = index;
                        fallbackSplit = split;
                    }
                }
                index = lower.indexOf(marker, index + marker.length());
            }
        }
        if (bestSplit < 0 && fallbackSplit < 0) {
            int quoteSplit = findEnglishQuotedBoundary(raw, lower);
            if (quoteSplit > 0) {
                bestSplit = quoteSplit;
            }
        }
        if (bestSplit < 0 && fallbackSplit < 0) {
            int colonSplit = findEnglishColonBoundary(lower);
            if (colonSplit > 0) {
                bestSplit = colonSplit;
            }
        }
        if (bestSplit < 0) {
            bestSplit = fallbackSplit;
        }
        SplitResult split = buildSplit(raw, bestSplit);
        if (!split.hasSplit() && fallbackSplit > 0 && fallbackSplit != bestSplit) {
            bestSplit = fallbackSplit;
            split = buildSplit(raw, fallbackSplit);
        }
        if (!split.hasSplit()) {
            return -1;
        }
        int adjustedBoundary = findEnglishDialogueBoundary(raw, bestSplit);
        if (adjustedBoundary > 0 && adjustedBoundary < bestSplit) {
            SplitResult adjusted = buildSplit(raw, adjustedBoundary);
            if (adjusted.hasSplit()) {
                bestSplit = adjustedBoundary;
            }
        }
        // Every marker above is a verb of saying or a formula of transmission, and English
        // matn is full of both, so the first one the gates admit can sit a thousand
        // characters into the narration: al-Tawhīd 362 read al-Ridā's whole answer on the
        // creation of the Empyrean as isnād, 2,006 characters of it, because the only
        // «said:» the gates let through was the last one. The tail test cannot tell that
        // apart — it counts the words an isnād is made of, and a translated matn carries
        // them too: «who seeks sustenance from Allah for the birds» scores three on its own.
        //
        // What does tell it apart is the Arabic. The English isnād is a translation of the
        // Arabic one, so its length is settled before any marker is looked for: across the
        // narrations where the two agree, the English runs between two thirds and twice the
        // Arabic, and only one in two hundred falls outside two thirds to four times. A
        // boundary outside that is not the end of this chain, and the chain's end is looked
        // for again among the candidates that are.
        if (!inChainBand(bestSplit, arabicChainChars)) {
            int rescued = findEnglishBandedBoundary(raw, lower, arabicChainChars,
                    runsPastTheArabic(bestSplit, arabicChainChars));
            if (rescued > 0) {
                return rescued;
            }
        }
        // Where the Arabic splitter found no chain at all there is nothing to measure
        // against, and 96 narrations took that silence as licence: al-Tawhīd's opening
        // praise of God, Nahj al-Balāgha's sermons, al-Ṭūsī's own argument for the Mahdī's
        // birth, the Faqīh's instructions for entering the Kaaba, and a chapter heading
        // were each read as isnād end to end, because a verb of saying stands somewhere
        // inside them all.
        //
        // Length alone does not tell those from a chain — ʿUyūn 38-1 names nine men over
        // 570 characters — but what fills the space does. An isnād is a list, and a list
        // says «on the authority of» or «from» every few words; a sermon of the same length
        // says it once or not at all.
        if (arabicChainChars <= 0 && bestSplit > MAX_CHAIN_HEAD_CHARS
                && !readsLikeAChain(lower, bestSplit)) {
            return -1;
        }
        return bestSplit;
    }

    /** Whether the words before a boundary are packed close enough to be a list of names. */
    private static boolean readsLikeAChain(String lower, int boundary) {
        int cues = chainSignalScore(lower.substring(0, boundary), ENGLISH_CHAIN_CUES);
        return cues > 0 && boundary <= cues * CHAIN_CUE_SPACING;
    }

    /** Whether a chain of this length is one the Arabic chain vouches for. */
    private static boolean inChainBand(int chainChars, int arabicChainChars) {
        if (chainChars <= 0) {
            return true;
        }
        if (arabicChainChars <= 0) {
            return true;            // nothing to measure against
        }
        int most = Math.max(CHAIN_BAND_FLOOR, (int) (CHAIN_BAND_HIGH * arabicChainChars));
        return chainChars >= CHAIN_BAND_LOW * arabicChainChars && chainChars <= most;
    }

    /** The visible length of the Arabic chain this narration has already been given. */
    private static int arabicChainChars(Map<String, Object> source) {
        Object chain = source.get("arabicChain");
        return chain instanceof String ? visibleLength((String) chain) : 0;
    }

    /**
     * The earliest boundary the Arabic chain's length vouches for.
     *
     * <p>Only consulted where the boundary the ordinary route found is outside that length,
     * so it cannot reshape a narration the two languages already agree on. The lower end of
     * the band is what keeps this from cutting mid-chain: al-Ṣadūq's translator repeats
     * «that he said:» between every link, and the first of five is a boundary three names
     * early — and far too short for the Arabic beside it.
     */
    /** Whether a chain of this length runs past the end of the Arabic one. */
    private static boolean runsPastTheArabic(int chainChars, int arabicChainChars) {
        return arabicChainChars > 0
                && chainChars > Math.max(CHAIN_BAND_FLOOR, (int) (CHAIN_BAND_HIGH * arabicChainChars));
    }

    private static int findEnglishBandedBoundary(String raw, String lower, int arabicChainChars,
                                                 boolean mayTrustTheArabicAlone) {
        int gated = bandedBoundary(raw, lower, arabicChainChars, true);
        if (gated > 0) {
            return gated;
        }
        if (!mayTrustTheArabicAlone) {
            // The chain found is shorter than the Arabic, not longer, and a translation is
            // allowed to drop what the Arabic names: al-Kāfī 4:163 ends its English chain on
            // «who has said the following» and says no more, three fifths of the Arabic beside
            // it. Where the gates agree with none of it, a short chain is left short rather
            // than lengthened into the matn on a measurement alone.
            return -1;
        }
        // The gates read the words standing before a boundary, and a short chain gives them
        // little to read: «71. Abaan, from Abu Baseer who said:» holds one «from» and scores
        // nothing at all, while «Hadith.2502 - It is narrated that» carries its whole signal
        // inside the marker rather than in front of it. Both came out with the narration
        // read as isnād end to end.
        //
        // Where the Arabic has a chain it has already answered the question the gates are
        // asking — whether these words are an isnād — and answered how long it runs besides.
        // So a boundary that fits the Arabic is admitted on the Arabic's word, and this pass
        // is reached only where the gated one found nothing that fits.
        return bandedBoundary(raw, lower, arabicChainChars, false);
    }

    private static int bandedBoundary(String raw, String lower, int arabicChainChars,
                                      boolean gated) {
        int best = -1;
        int bestIndex = -1;
        int fallback = -1;
        int fallbackIndex = -1;
        for (String marker : ENGLISH_SPLIT_MARKERS) {
            int index = lower.indexOf(marker);
            while (index >= 0 && index <= MAX_SCAN_CHARS) {
                String prefix = lower.substring(0, index);
                int split = " that a man ".equals(marker)
                        ? index + " that ".length() : index + marker.length();
                boolean scored = !gated
                        || englishChainSignalScore(prefix) >= minEnglishChainScore(prefix);
                if (scored && !isSubsumedByLongerMarker(lower, index, marker)
                        && inChainBand(split, arabicChainChars)
                        && buildSplit(raw, split).hasSplit()) {
                    // Two markers can start at the same word, one of them a prefix of the
                    // other — «who has said the » inside «who has said the following » — and
                    // the shorter one cuts in the middle of the phrase it shares.
                    if (isLikelyMatnBoundary(lower, split, ENGLISH_CHAIN_CUES,
                            maxEnglishTailSignals(marker))) {
                        if (best < 0 || index < bestIndex
                                || (index == bestIndex && split > best)) {
                            best = split;
                            bestIndex = index;
                        }
                    } else if (fallback < 0 || index < fallbackIndex
                            || (index == fallbackIndex && split > fallback)) {
                        fallback = split;
                        fallbackIndex = index;
                    }
                }
                index = lower.indexOf(marker, index + marker.length());
            }
        }
        if (best > 0) {
            return best;
        }
        // A colon or an opening quote closes an isnād the marker list has no word for —
        // «Hadith.1397 - It is narrated:», «… from Abu al-Hasan Imam Musa ibn Jafar (as):»,
        // «Davood al-Ruqi narrated, “One of the Kharajites asked me …».
        int punctuated = gated
                ? earlierOf(findEnglishQuotedBoundary(raw, lower), findEnglishColonBoundary(lower))
                : findEnglishPunctuatedBoundary(raw, lower, arabicChainChars);
        if (punctuated > 0 && inChainBand(punctuated, arabicChainChars)
                && buildSplit(raw, punctuated).hasSplit()) {
            return punctuated;
        }
        return fallback;
    }

    private static int earlierOf(int left, int right) {
        if (left <= 0) {
            return right;
        }
        return right <= 0 ? left : Math.min(left, right);
    }

    /** The first colon or opening quote the Arabic chain's length vouches for. */
    private static int findEnglishPunctuatedBoundary(String raw, String lower,
                                                     int arabicChainChars) {
        int best = -1;
        for (int at = 0; at < raw.length() && at <= MAX_SCAN_CHARS; at++) {
            char ch = raw.charAt(at);
            int boundary = ch == ':' ? at + 1 : (ch == '\u201c' || ch == '"' ? at : -1);
            if (boundary > 0 && inChainBand(boundary, arabicChainChars)
                    && buildSplit(raw, boundary).hasSplit()) {
                best = boundary;
                break;
            }
        }
        return best;
    }

    private static int findArabicSplit(String raw) {
        ArabicMatchText matchText = normalizeArabicForMatching(raw);
        String normalized = matchText.normalized();
        if (normalized.isEmpty()) {
            return -1;
        }
        int formula = findArabicOpeningFormulaBoundary(raw, matchText, normalized);
        if (formula > 0) {
            return formula;
        }
        int bestSplit = -1;
        int bestIndex = -1;
        int fallbackSplit = -1;
        int fallbackIndex = -1;
        for (String marker : ARABIC_SPLIT_MARKERS) {
            int index = normalized.indexOf(marker);
            while (index >= 0 && index <= MAX_SCAN_CHARS) {
                String prefix = normalized.substring(0, index);
                int score = arabicChainSignalScore(prefix);
                if (score >= minArabicChainScore(prefix)) {
                    int markerEnd = index + marker.length();
                    int split = matchText.toRawBoundary(markerEnd);
                    String tail = normalized.substring(Math.min(markerEnd, normalized.length()));
                    String rawTail = raw.substring(Math.min(split, raw.length()));
                    String trimmedRawTail = trimContent(rawTail);
                    int maxTailSignals = maxArabicTailSignals(marker);
                    boolean directChainContinuation = isBroadArabicQalaMarker(marker)
                            && (startsWithAnyCue(rawTail, ARABIC_DIRECT_CHAIN_CONTINUATIONS)
                            || startsWithAnyCue(trimmedRawTail, ARABIC_DIRECT_CHAIN_CONTINUATIONS));
                    if (!directChainContinuation
                            && isLikelyMatnBoundary(normalized, markerEnd, ARABIC_CHAIN_CUES_STRICT, maxTailSignals)) {
                        if (bestSplit < 0 || index < bestIndex || (index == bestIndex && split > bestSplit)) {
                            bestIndex = index;
                            bestSplit = split;
                        }
                    } else if (!startsWithAnyCue(tail, ARABIC_CHAIN_CUES)
                            && prefix.length() <= 200
                            && (fallbackSplit < 0 || index < fallbackIndex || (index == fallbackIndex && split > fallbackSplit))) {
                        fallbackIndex = index;
                        fallbackSplit = split;
                    }
                }
                index = normalized.indexOf(marker, index + marker.length());
            }
        }
        if (bestSplit < 0) {
            bestSplit = fallbackSplit;
        }
        SplitResult split = buildSplit(raw, bestSplit);
        if (!split.hasSplit() && fallbackSplit > 0 && fallbackSplit != bestSplit) {
            bestSplit = fallbackSplit;
            split = buildSplit(raw, fallbackSplit);
        }
        // A marker that passed the gate and then produced nothing usable used to end the
        // search here, so an entry whose only «قال» stands at the very end with two words
        // behind it — «...عن الحلبي عن أبي عبد الله (عليه السلام) في الرجل يقتل البقة
        // والبرغوث... قال لا.» — never reached the routes that can read it.
        if (!split.hasSplit()) {
            for (int candidate : new int[] { findArabicColonBoundary(raw),
                                             findArabicReportedClauseBoundary(raw, matchText, normalized),
                                             findArabicBlessingBoundary(raw, matchText, normalized) }) {
                SplitResult rescued = buildSplit(raw, candidate);
                if (rescued.hasSplit()) {
                    bestSplit = candidate;
                    split = rescued;
                    break;
                }
            }
        }
        if (!split.hasSplit()) {
            return -1;
        }
        int adjustedBoundary = findArabicDialogueBoundary(raw, bestSplit);
        if (adjustedBoundary > 0 && adjustedBoundary < bestSplit) {
            SplitResult adjusted = buildSplit(raw, adjustedBoundary);
            if (adjusted.hasSplit()) {
                return adjustedBoundary;
            }
        }
        // Extend past "قال لي/له [name with بن]:" chain continuations in the content
        int extended = extendPastQalaLiChainLinks(raw, bestSplit);
        if (extended > bestSplit) {
            SplitResult extendedSplit = buildSplit(raw, extended);
            if (extendedSplit.hasSplit()) {
                return extended;
            }
        }
        return bestSplit;
    }

    /**
     * The shorthand a book puts where a chain of names would stand, and the clause after it.
     *
     * <p>«10ـ وبهذا الإسناد أنّ أمير المؤمنين (عليه السلام) اشتكى عينه فعاده النبيّ (صلى الله
     * عليه وآله) فإذا هو يصيح فقال…» — «وبهذا الإسناد» is the whole of the isnād here, and
     * everything from «أنّ» is the narration. Read by markers alone the entry ends up with a
     * chain that runs to the Prophet's reply, because those two words satisfy the score gate
     * and the first «قال» is a hundred characters away. So where an entry opens on one of
     * these formulas and «أنّ» follows it directly, that is the boundary, ahead of any verb.
     *
     * <p>A formula followed by a name is an ordinary chain and is left alone: «و رواه صافي
     * البرقي عن أبي عبد الله (ع) قال» ends at the verb like every other chain does.
     */
    private static int findArabicOpeningFormulaBoundary(String raw, ArabicMatchText matchText,
                                                        String normalized) {
        for (String formula : new String[] { "وبهذا الاسناد", "و بهذا الاسناد",
                                             "بهذا الاسناد", "وباسناده", "و باسناده",
                                             "باسناده", "وروي", "و روي", "روي",
                                             "ورواه", "و رواه", "رواه" }) {
            int at = normalized.indexOf(formula);
            if (at < 0 || at > 12 || !hasCueBoundary(normalized, at, formula.length())) {
                continue;
            }
            int end = at + formula.length();
            // The editions set a colon, a guillemet or a dash between the formula and the
            // clause, and some set nothing at all. The boundary stays at the formula, so
            // the punctuation opens the narration as it does on every other entry.
            int clauseAt = end;
            while (clauseAt < normalized.length()
                    && " :«»\"'-\u2014\u2013".indexOf(normalized.charAt(clauseAt)) >= 0) {
                clauseAt++;
            }
            for (String clause : new String[] { "انهما ", "انهم ", "انها ", "انه ", "ان " }) {
                if (!normalized.startsWith(clause, clauseAt)) {
                    continue;
                }
                // «و بهذا الإسناد أنّ النبيّ (صلى الله عليه وآله) قال» names whom the
                // narration comes from, the way «عن أبي عبد الله (عليه السلام) قال» does,
                // and he belongs on the chain with everyone else a reader can follow. So
                // where a verb of saying stands close behind the clause, this stands aside
                // and lets the ordinary markers take the boundary after it.
                if (saidByThePersonNamed(normalized, clauseAt + clause.length())) {
                    break;
                }
                // A boundary maps back to the last letter it kept, which would leave the
                // vowel printed on «رُوِيَ» on the far side of the cut and drop it from
                // both, so the cut is taken past whatever is written over that letter.
                int split = matchText.toRawBoundary(end);
                while (split < raw.length()
                        && (isArabicDiacritic(raw.charAt(split)) || raw.charAt(split) == '\u0640')) {
                    split++;
                }
                split = moveSplitBoundaryOutsideHtml(raw, split);
                if (buildSplit(raw, split).hasSplit()) {
                    return split;
                }
            }
        }
        return -1;
    }

    /**
     * Whether the clause names a man and then has him speak, with nothing else in between.
     *
     * <p>«أنّ النبيّ (صلى الله عليه وآله) قال» is the book saying whom the narration comes
     * from, the way «عن أبي عبد الله (عليه السلام) قال» is, and he belongs on the chain with
     * everyone else a reader can follow. «أنّ عليّاً (عليه السلام) مرّ على منارة طويلة فأمر
     * بهدمها، ثمّ قال» is the narration itself, and the verb at the end of it says nothing
     * about where the isnād stopped. Distance does not tell them apart — both reach a verb
     * of saying inside sixty characters — but what stands between does: a name and the
     * blessing said over it, or a man doing things.
     */
    private static boolean saidByThePersonNamed(String normalized, int from) {
        int end = Math.min(normalized.length(), from + NEAR_AN_OPENING_CHARS);
        if (from >= end) {
            return false;
        }
        String window = normalized.substring(from, end);
        int said = -1;
        for (String verb : new String[] { "قال", "قالت", "قالوا", "قالا", "فقال", "يقول" }) {
            int at = findCueIndex(window, verb, 0);
            if (at >= 0 && (said < 0 || at < said)) {
                said = at;
            }
        }
        if (said < 0) {
            return false;
        }
        int named = 0;
        for (String word : window.substring(0, said).trim().split("\\s+")) {
            if (!word.isEmpty() && !isBlessing(word)) {
                named++;
            }
        }
        return named > 0 && named <= NAMED_WORDS;
    }

    private static boolean isBlessing(String word) {
        for (String blessing : ARABIC_BLESSING_WORDS) {
            if (word.equals(blessing)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The isnād that ends on the blessing said over the man it ends with, and no verb.
     *
     * <p>Most chains hand over with «قال». Some simply stop: «علي بن إبراهيم عن أبيه عن ابن
     * أبي عمير عن هشام بن الحكم عن أبي عبد الله (عليه السلام) في ميزابين سالا أحدهما بول
     * والآخر ماء المطر…» — the question is put without anyone being said to speak, and the
     * duʿāʾ entries and the «مثله وزاد فيه» ones do the same. Nothing in the entry marks
     * where the names end except the blessing itself.
     *
     * <p>Read last of all, after every marker and both other routes, so no chain that can
     * be read another way is reshaped by it. Three things are asked of the blessing before
     * it may end a chain: the names before it must score as a chain and not merely mention
     * somebody; nothing may follow it that carries the chain on, or that a marker would
     * have handled; and no verb of saying may stand in front of it, unless it is one chain
     * link handing to the next — «قال حدثني جعفر بن محمد» is the chain still going, while
     * «قال.بعث إليّ أبو الحسن» is the narration already begun.
     */
    private static int findArabicBlessingBoundary(String raw, ArabicMatchText matchText,
                                                  String normalized) {
        for (int at = 0; at >= 0 && at <= MAX_SCAN_CHARS; ) {
            int found = -1;
            int length = 0;
            for (String blessing : ARABIC_BLESSINGS) {
                int index = findCueIndex(normalized, blessing, at);
                if (index >= 0 && (found < 0 || index < found)) {
                    found = index;
                    length = blessing.length();
                }
            }
            if (found < 0) {
                return -1;
            }
            int end = found + length;
            at = end;
            String prefix = normalized.substring(0, found);
            if (prefix.length() > MAX_CHAIN_HEAD_CHARS) {
                return -1;
            }
            if (arabicChainSignalScore(prefix) < 3 || saysBeforeTheNames(prefix)) {
                continue;
            }
            // A chain of names does not end a sentence halfway along itself. Kitāb
            // al-Ḍuʿafāʾ's entries do: «يونس بن ظبيان، كوفي. غال، كذاب وضاع للحديث. روى
            // عن أبي عبد الله (عليه السلام)» is al-Ghaḍāʾirī's verdict on a man, not a
            // chain he stands in, and the book prints no isnāds at all.
            if (prefix.indexOf('.') >= 0 || prefix.indexOf('\u06D4') >= 0) {
                continue;
            }
            String tail = normalized.substring(Math.min(end, normalized.length()));
            if (startsWithAnyCue(tail, ARABIC_CHAIN_CUES)
                    || startsWithAnyCue(tail, ARABIC_SPLIT_MARKERS)) {
                continue;
            }
            int split = matchText.toRawBoundary(end);
            while (split < raw.length()
                    && (isArabicDiacritic(raw.charAt(split)) || raw.charAt(split) == '\u0640')) {
                split++;
            }
            split = moveSplitBoundaryOutsideHtml(raw, split);
            if (buildSplit(raw, split).hasSplit()) {
                return split;
            }
        }
        return -1;
    }

    /** Whether the narration has already begun, rather than one link handing to the next. */
    private static boolean saysBeforeTheNames(String prefix) {
        for (String verb : new String[] { "قال", "قالت", "قالوا", "قالا", "فقال", "يقول" }) {
            int at = findCueIndex(prefix, verb, 0);
            while (at >= 0) {
                if (!startsWithAnyCue(prefix.substring(at + verb.length()),
                                      ARABIC_DIRECT_CHAIN_CONTINUATIONS)) {
                    return true;
                }
                at = findCueIndex(prefix, verb, at + verb.length());
            }
        }
        return false;
    }

    private static int maxArabicTailSignals(String marker) {
        if (marker == null || marker.isEmpty()) {
            return 1;
        }
        String normalized = marker.replace(" ", "");
        // "قال" markers are very broad. If any explicit chain cue remains in the tail,
        // keep scanning to avoid cutting before the final "قال" in a multi-link isnad.
        if ((normalized.startsWith("قال") || normalized.startsWith("فقال"))
                && !normalized.contains("قلت")) {
            return 0;
        }
        return 1;
    }

    private static int findArabicReportedClauseBoundary(String raw, ArabicMatchText matchText, String normalized) {
        if (raw == null || raw.isEmpty() || normalized == null || normalized.isEmpty()) {
            return -1;
        }
        int bestSplit = -1;
        int bestIndex = -1;
        for (String marker : new String[] { " أنه ", " انه ", " انها ", " انهم ", " انهما ",
                                            " أن ", " ان " }) {
            int index = normalized.indexOf(marker);
            while (index >= 0 && index <= MAX_SCAN_CHARS) {
                String prefix = normalized.substring(0, index);
                if ((arabicChainSignalScore(prefix) >= 3 || isBareOpeningFormula(prefix))
                        && prefix.length() <= 200) {
                    int split = matchText.toRawBoundary(index + marker.length());
                    String tail = trimContent(raw.substring(Math.min(split, raw.length())));
                    if (!startsWithAnyCue(tail, ARABIC_CHAIN_CUES)) {
                        SplitResult candidate = buildSplit(raw, split);
                        if (candidate.hasSplit()
                                && (bestSplit < 0 || index < bestIndex || (index == bestIndex && split > bestSplit))) {
                            bestIndex = index;
                            bestSplit = split;
                        }
                    }
                }
                index = normalized.indexOf(marker, index + marker.length());
            }
        }
        return bestSplit;
    }

    /**
     * Whether a prefix is nothing but a book's shorthand for an isnād it has already given.
     *
     * <p>«و بهذا الإسناد», «و بإسناده», «و روي» — two or three words standing where a chain
     * of names would stand. They carry one chain signal and no nasab, so they can never
     * reach the three a reported clause normally asks for, and the clause after them is the
     * narration itself.
     */
    private static boolean isBareOpeningFormula(String prefix) {
        String trimmed = stripLeadingNarrationPrefix(normalizeSignalText(prefix)).trim();
        if (trimmed.startsWith("و ")) {
            trimmed = trimmed.substring(2).trim();
        }
        if (trimmed.length() > NEAR_AN_OPENING_CHARS) {
            return false;
        }
        for (String[] openings : new String[][] { ARABIC_CHAIN_OPENINGS,
                                                  ARABIC_QUOTED_ROUTE_OPENINGS }) {
            for (String opening : openings) {
                // Not «عن» or «عنه»: those open a chain of names, which reaches three
                // signals on its own and does not need this.
                if (opening.length() > 4
                        && (trimmed.equals(opening) || trimmed.startsWith(opening + " "))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int findArabicColonBoundary(String raw) {
        if (raw == null || raw.isEmpty()) {
            return -1;
        }
        int bestSplit = -1;
        int bestIndex = -1;
        int index = raw.indexOf(':');
        while (index >= 0 && index <= MAX_SCAN_CHARS) {
            String prefixRaw = raw.substring(0, index);
            String prefix = normalizeArabicForMatching(prefixRaw).normalized();
            boolean hasChainPrefix = arabicChainSignalScore(prefix) >= minArabicChainScore(prefix);
            boolean hasShortNamedNarrator = isShortArabicNamedQalaPrefix(prefixRaw);
            String tail = trimContent(raw.substring(index + 1));
            if ((hasChainPrefix || hasShortNamedNarrator) && !startsWithAnyCue(tail, ARABIC_CHAIN_CUES)) {
                int split = index + 1;
                SplitResult candidate = buildSplit(raw, split);
                if (candidate.hasSplit()
                        && (bestSplit < 0 || index < bestIndex || (index == bestIndex && split > bestSplit))) {
                    bestIndex = index;
                    bestSplit = split;
                }
            }
            index = raw.indexOf(':', index + 1);
        }
        return bestSplit;
    }

    private static boolean isShortArabicNamedQalaPrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            return false;
        }
        String simplified = normalizeWhitespace(SIGNAL_DELIMITERS_PATTERN.matcher(prefix).replaceAll(" ")).trim();
        String suffix = null;
        for (String candidate : new String[] { " قالوا", " قالت", " قال" }) {
            if (simplified.endsWith(candidate)) {
                suffix = candidate;
                break;
            }
        }
        if (suffix == null) {
            return false;
        }
        String subject = simplified.substring(0, simplified.length() - suffix.length()).trim();
        if (subject.isEmpty() || subject.length() > 80) {
            return false;
        }
        String normalizedSubject = normalizeSignalText(subject);
        int nasabLinks = countOccurrences(normalizedSubject, " بن ")
                + countOccurrences(normalizedSubject, " ابن ")
                + countOccurrences(normalizedSubject, " أبي ")
                + countOccurrences(normalizedSubject, " أبو ");
        return nasabLinks >= 1;
    }

    private static boolean isBroadArabicQalaMarker(String marker) {
        if (marker == null || marker.isEmpty()) {
            return false;
        }
        String normalized = marker.replace(" ", "");
        return (normalized.startsWith("قال") || normalized.startsWith("فقال"))
                && !normalized.contains("قلت");
    }

    private static int maxEnglishTailSignals(String marker) {
        if (marker == null || marker.isEmpty()) {
            return 1;
        }
        String normalized = marker.toLowerCase(Locale.ROOT);
        if (normalized.contains("the following")) {
            return 4;
        }
        // "said" markers (except "that he said") frequently precede matn that contains
        // narrative language (e.g., "The Prophet narrated to us that Allah said...").
        // Allow up to 2 tail chain signals to avoid rejecting the split point prematurely.
        if (normalized.contains("said") && !normalized.contains("that he said")) {
            return 2;
        }
        // A bare «that» is admitted only where the matn after it carries no chain language
        // at all, which is the whole of its safety: «on the authority of Ali (s) that God's
        // Prophet said» passes, and «Ahmad narrated that Ibrahim quoted» does not.
        if (normalized.endsWith(") that ")) {
            return 0;
        }
        // "that he said" pattern: allow 0 tail chain signals to ensure the split is at
        // the actual chain→content boundary, not mid-chain between narrators.
        if (normalized.contains("that he said")) {
            return 0;
        }
        return 1;
    }

    private static int findEnglishDialogueBoundary(String raw, int splitIndex) {
        if (raw == null || raw.isEmpty() || splitIndex <= 0) {
            return -1;
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        var matcher = ENGLISH_DIALOGUE_BOUNDARY_PATTERN.matcher(raw);
        int earliest = -1;
        while (matcher.find()) {
            int idx = matcher.start();
            if (idx > 0 && idx < splitIndex) {
                // Only consider dialogue boundary if there are enough chain signals
                // before this point — "I was told by my grandfather" is chain continuation,
                // not dialogue start.
                String prefix = lower.substring(0, idx);
                int chainScore = englishChainSignalScore(prefix);
                if (chainScore >= minEnglishChainScore(prefix)) {
                    if (earliest < 0 || idx < earliest) {
                        earliest = idx;
                    }
                }
            }
        }
        return earliest;
    }

    private static int findEnglishQuotedBoundary(String raw, String lower) {
        if (raw == null || raw.isEmpty() || lower == null || lower.isEmpty()) {
            return -1;
        }
        int best = -1;
        for (char quote : new char[] { '“', '"' }) {
            int index = raw.indexOf(quote);
            while (index >= 0 && index <= MAX_SCAN_CHARS) {
                String prefix = lower.substring(0, index);
                int required = Math.max(2, minEnglishChainScore(prefix) + 1);
                if (englishChainSignalScore(prefix) >= required
                        && isLikelyMatnBoundary(lower, index, ENGLISH_CHAIN_CUES, 1)) {
                    if (best < 0 || index < best) {
                        best = index;
                    }
                }
                index = raw.indexOf(quote, index + 1);
            }
        }
        return best;
    }

    private static int findEnglishColonBoundary(String lower) {
        if (lower == null || lower.isEmpty()) {
            return -1;
        }
        int best = -1;
        int index = lower.indexOf(':');
        while (index >= 0 && index <= MAX_SCAN_CHARS) {
            String prefix = lower.substring(0, index);
            int required = Math.max(2, minEnglishChainScore(prefix) + 1);
            if (englishChainSignalScore(prefix) >= required
                    && isLikelyMatnBoundary(lower, index + 1, ENGLISH_CHAIN_CUES, 1)) {
                int split = index + 1;
                if (best < 0 || split < best) {
                    best = split;
                }
            }
            index = lower.indexOf(':', index + 1);
        }
        return best;
    }

    private static int findArabicDialogueBoundary(String raw, int splitIndex) {
        if (raw == null || raw.isEmpty() || splitIndex <= 0) {
            return -1;
        }
        ArabicMatchText matchText = normalizeArabicForMatching(raw);
        String normalized = matchText.normalized();
        if (normalized.isEmpty()) {
            return -1;
        }
        int[] rawIndexByNormalized = matchText.rawIndexByNormalized();
        int earliest = -1;
        for (String cue : ARABIC_DIALOGUE_CUES) {
            int idx = findCueIndex(normalized, cue, 0);
            while (idx >= 0) {
                if (idx >= rawIndexByNormalized.length) {
                    break;
                }
                int rawBoundary = rawIndexByNormalized[idx];
                if (rawBoundary > 0 && rawBoundary < splitIndex) {
                    // Skip "قال لي/له" when followed by a name with "بن" — chain continuation
                    if (isQalaLiChainContinuation(normalized, idx, cue)) {
                        idx = findCueIndex(normalized, cue, idx + 1);
                        continue;
                    }
                    if (earliest < 0 || rawBoundary < earliest) {
                        earliest = rawBoundary;
                    }
                    break;
                }
                idx = findCueIndex(normalized, cue, idx + 1);
            }
        }
        return earliest;
    }

    /** Returns true if "قال لي/له" is followed by a name containing "بن" (chain link). */
    private static boolean isQalaLiChainContinuation(String normalized, int cueIndex, String cue) {
        String stripped = cue.replace(" ", "");
        if (!stripped.equals("قاللي") && !stripped.equals("قالله")
                && !stripped.equals("قاللها") && !stripped.equals("قاللهم")) {
            return false;
        }
        int afterCue = cueIndex + cue.length();
        if (afterCue >= normalized.length()) return false;
        String tail = normalized.substring(afterCue, Math.min(afterCue + 60, normalized.length()));
        // Check if the next ~60 chars contain " بن " or " ابن " — indicates a narrator name
        return tail.contains(" بن ") || tail.contains(" ابن ");
    }

    /**
     * Extends the split past "قال لي/له [name with بن]:" chain links in the content.
     * E.g., if content starts with "قال لي أبو علي بن الجنيد: قال لي أبو جعفر...: content",
     * moves the split past all "قال لي [name]:" chain continuations.
     */
    private static int extendPastQalaLiChainLinks(String raw, int splitIndex) {
        if (raw == null || splitIndex <= 0 || splitIndex >= raw.length()) {
            return splitIndex;
        }
        ArabicMatchText matchText = normalizeArabicForMatching(raw);
        String normalized = matchText.normalized();
        int[] rawIndexByNormalized = matchText.rawIndexByNormalized();

        // Map splitIndex (raw) to normalized position
        int normPos = 0;
        for (int i = 0; i < rawIndexByNormalized.length && rawIndexByNormalized[i] < splitIndex; i++) {
            normPos = i + 1;
        }

        int currentPos = normPos;
        int maxIterations = 5; // prevent infinite loops
        for (int iter = 0; iter < maxIterations; iter++) {
            // Skip whitespace in normalized
            while (currentPos < normalized.length() && normalized.charAt(currentPos) == ' ') {
                currentPos++;
            }
            // Check if current position starts with "قال لي" or "قال له"
            boolean found = false;
            for (String cue : new String[] { "قال لي", "قال له", "قال لها", "قال لهم" }) {
                if (normalized.startsWith(cue, currentPos)) {
                    int afterCue = currentPos + cue.length();
                    if (afterCue >= normalized.length()) break;
                    String tail = normalized.substring(afterCue, Math.min(afterCue + 80, normalized.length()));
                    // Must contain " بن " to be a name (chain link)
                    if (tail.contains(" بن ") || tail.contains(" ابن ")) {
                        // Find the colon after this name to skip past "قال لي [name]:"
                        int colonInTail = tail.indexOf(':');
                        if (colonInTail >= 0) {
                            currentPos = afterCue + colonInTail + 1;
                            found = true;
                            break;
                        }
                    }
                }
            }
            if (!found) break;
        }

        if (currentPos > normPos && currentPos < rawIndexByNormalized.length) {
            int newSplit = rawIndexByNormalized[currentPos];
            if (newSplit > splitIndex) {
                return newSplit;
            }
        }
        return splitIndex;
    }

    private static int arabicChainSignalScore(String prefix) {
        String normalized = normalizeSignalText(prefix);
        if (normalized.isEmpty()) {
            return 0;
        }
        int base = chainSignalScore(normalized, ARABIC_CHAIN_CUES);
        int nasabLinks = countOccurrences(normalized, " بن ") + countOccurrences(normalized, " ابن ");
        // "bin/ibn" should help only when chain-link signals already exist; otherwise
        // they over-match long matn passages that mention historical names.
        if (base >= 1 && nasabLinks > 0) {
            return base + Math.min(2, nasabLinks);
        }
        return base;
    }

    private static int minEnglishChainScore(String prefix) {
        String normalized = normalizeSignalText(prefix);
        if (normalized.isEmpty()) {
            return 2;
        }
        String simplified = stripLeadingNarrationPrefix(normalized);
        if (simplified.startsWith(" it has been narrated that ")
                || simplified.startsWith(" it was narrated that ")
                || simplified.startsWith(" it has been reported that ")
                || simplified.startsWith(" it has been related that ")
                || simplified.startsWith(" it is narrated from ")
                || simplified.startsWith(" it is reported that ")
                || simplified.startsWith(" a number of our people has narrated ")
                || simplified.startsWith(" by this isnad ")
                || simplified.startsWith(" with this isnad ")
                || simplified.startsWith(" and by this chain ")
                || simplified.startsWith(" and with the same chain of transmission ")
                || simplified.contains(" reported by ")
                || simplified.contains(" narrated that ")
                || simplified.contains(" narrated to me ")
                || simplified.contains(" on the authority of ")) {
            return 1;
        }
        return 2;
    }

    private static int englishChainSignalScore(String prefix) {
        String normalized = normalizeSignalText(prefix);
        if (normalized.isEmpty()) {
            return 0;
        }
        int strong = chainSignalScore(normalized, ENGLISH_CHAIN_STRONG_CUES);
        int fromLinks = countOccurrences(normalized, " from ");
        int saidLinks = countOccurrences(normalized, " said ");
        int score = strong * 2;
        if (fromLinks >= 2) {
            score += 2;
        }
        if (fromLinks >= 4) {
            score++;
        }
        if (saidLinks >= 2) {
            score++;
        }
        if (normalized.contains(" narrated that ")) {
            score++;
        }
        if (normalized.contains(" reported by ")) {
            score++;
        }
        return score;
    }

    private static int minArabicChainScore(String prefix) {
        String normalized = normalizeSignalText(prefix);
        if (normalized.isEmpty()) {
            return 2;
        }
        String simplified = stripLeadingNarrationPrefix(normalized);
        String trimmed = simplified.trim();
        if (opensOn(trimmed, ARABIC_CHAIN_OPENINGS)) {
            return 1;
        }
        // The editions set the conjunction loose — «و باسناده», «و بهذا الاسناد», «و روى
        // أحمد بن عائذ» — where these openings were written joined, so an entry opening on
        // its own route scored as ordinary prose. One waw is not a different opening.
        //
        // But a formula of two or three words vouches for a boundary standing next to it,
        // not for one far down the entry: «و روي: «أن عليا (عليه السلام) مر على منارة
        // طويلة فأمر بهدمها، ثم قال»» would otherwise read the whole act as isnād on the
        // strength of its first two words. So the licence the loose conjunction earns is
        // bounded, and the colon after «و روي» is left to be found instead.
        if (trimmed.startsWith("و ")) {
            String pastWaw = trimmed.substring(2).trim();
            if (pastWaw.length() <= NEAR_AN_OPENING_CHARS
                    && (opensOn(pastWaw, ARABIC_CHAIN_OPENINGS)
                        || opensOn(pastWaw, ARABIC_QUOTED_ROUTE_OPENINGS))) {
                return 1;
            }
        }
        if (opensOn(trimmed, ARABIC_QUOTED_ROUTE_OPENINGS)
                && trimmed.length() <= NEAR_AN_OPENING_CHARS) {
            return 1;
        }
        // For very long prefixes (likely book prefaces), require stronger chain signals
        // to avoid treating praise/preamble text as narrator chain.
        if (prefix.length() > 200) {
            return 5;
        }
        return 2;
    }

    private static boolean opensOn(String trimmed, String[] openings) {
        for (String opening : openings) {
            if (trimmed.startsWith(opening)) {
                return true;
            }
        }
        return false;
    }

    private static String stripLeadingNarrationPrefix(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return "";
        }
        String simplified = normalized.replaceFirst("^\\s*(?:[\\d\\[\\]()/\\\\.:\\-ـ]+\\s*)+", " ");
        return simplified.isBlank() ? normalized : simplified;
    }

    private static boolean isLikelyMatnBoundary(String text, int splitIndex, String[] cues, int maxTailSignals) {
        if (splitIndex <= 0 || splitIndex >= text.length()) {
            return false;
        }
        int lookAheadEnd = Math.min(text.length(), splitIndex + MATN_LOOKAHEAD_CHARS);
        String tail = text.substring(splitIndex, lookAheadEnd);
        return chainSignalScore(tail, cues) <= maxTailSignals;
    }

    private static boolean startsWithAnyCue(String text, String[] cues) {
        String normalized = normalizeWhitespace(SIGNAL_DELIMITERS_PATTERN.matcher(text == null ? "" : text).replaceAll(" "))
                .stripLeading();
        if (normalized.isEmpty()) {
            return false;
        }
        for (String cue : cues) {
            String normalizedCue = normalizeWhitespace(SIGNAL_DELIMITERS_PATTERN.matcher(cue == null ? "" : cue).replaceAll(" "))
                    .trim();
            if (!normalizedCue.isEmpty()
                    && (normalized.equals(normalizedCue) || normalized.startsWith(normalizedCue + " "))) {
                return true;
            }
        }
        return false;
    }

    private static int findCueIndex(String text, String cue, int fromIndex) {
        if (text == null || text.isEmpty() || cue == null || cue.isBlank()) {
            return -1;
        }
        String token = cue.trim();
        int index = Math.max(0, fromIndex);
        while (index >= 0) {
            index = text.indexOf(token, index);
            if (index < 0) {
                return -1;
            }
            if (hasCueBoundary(text, index, token.length())) {
                return index;
            }
            index += token.length();
        }
        return -1;
    }

    private static boolean hasCueBoundary(String text, int start, int length) {
        int before = start - 1;
        int after = start + length;
        return isCueBoundaryChar(text, before) && isCueBoundaryChar(text, after);
    }

    private static boolean isCueBoundaryChar(String text, int index) {
        if (index < 0 || index >= text.length()) {
            return true;
        }
        char ch = text.charAt(index);
        return !Character.isLetterOrDigit(ch);
    }

    private static SplitResult buildSplit(String raw, int splitIndex) {
        if (splitIndex <= 0 || splitIndex >= raw.length()) {
            return SplitResult.none();
        }
        String chain = trimChain(raw.substring(0, splitIndex));
        String content = trimContent(raw.substring(splitIndex));
        if (visibleLength(chain) < MIN_CHAIN_VISIBLE_CHARS || visibleLength(content) < MIN_CONTENT_VISIBLE_CHARS) {
            return SplitResult.none();
        }
        return new SplitResult(chain, content);
    }

    private static int chainSignalScore(String text, String[] cues) {
        String normalized = normalizeSignalText(text);
        if (normalized.isEmpty()) {
            return 0;
        }
        int score = 0;
        for (String cue : cues) {
            score += countOccurrences(normalized, cue);
        }
        return score;
    }

    private static String normalizeSignalText(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String delimiterSpaced = SIGNAL_DELIMITERS_PATTERN.matcher(value).replaceAll(" ");
        String normalized = normalizeWhitespace(delimiterSpaced);
        if (normalized.isEmpty()) {
            return "";
        }
        // Pad boundaries so space-delimited cue tokens match at the start/end.
        return " " + normalized + " ";
    }

    private static boolean isSubsumedByLongerMarker(String lower, int index, String marker) {
        // Check if "said:" at this position is part of a longer pattern like
        // "that he said:" or "who said:" which has its own dedicated split marker.
        if (" said:".equals(marker) || " he said:".equals(marker) || " she said:".equals(marker)) {
            // For " he said:", the marker starts at "he". Check if preceded by "that "
            // For " said:", the marker starts at the space before "said". Check if preceded by "that he"/"who"
            String before = lower.substring(Math.max(0, index - 25), index);
            if (" he said:".equals(marker) || " she said:".equals(marker)) {
                // Marker starts with space before pronoun. Position is at that space.
                // Text before position has "that" right before the space.
                return before.endsWith("that") || before.endsWith("who");
            }
            return before.endsWith("that he") || before.endsWith("that she")
                    || before.endsWith("who") || before.endsWith("who has")
                    || before.endsWith("he has") || before.endsWith("she has");
        }
        return false;
    }

    private static int countOccurrences(String text, String token) {
        if (text == null || text.isEmpty() || token == null || token.isEmpty()) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while (index >= 0) {
            index = text.indexOf(token, index);
            if (index >= 0) {
                count++;
                index += token.length();
            }
        }
        return count;
    }

    private static String trimChain(String value) {
        String result = value == null ? "" : value.trim();
        while (result.endsWith(":") || result.endsWith(",") || result.endsWith(";") || result.endsWith("-")
                || result.endsWith("،")) {
            result = result.substring(0, result.length() - 1).trim();
        }
        return result;
    }

    private static String trimContent(String value) {
        String result = value == null ? "" : value.trim();
        boolean changed = true;
        while (changed && !result.isEmpty()) {
            changed = false;
            while (result.startsWith(":") || result.startsWith(",") || result.startsWith(";") || result.startsWith("-")
                    || result.startsWith("،")) {
                result = result.substring(1).trim();
                changed = true;
            }
            while (!result.isEmpty() && (isArabicDiacritic(result.charAt(0)) || result.charAt(0) == '\u0640')) {
                result = result.substring(1).trim();
                changed = true;
            }
        }
        return result;
    }

    private static String normalizeWhitespace(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return MULTISPACE_PATTERN.matcher(value).replaceAll(" ");
    }

    private static VisibleText toVisibleText(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new VisibleText("", new int[0], 0);
        }
        StringBuilder visible = new StringBuilder(raw.length());
        int[] rawIndexByVisible = new int[raw.length()];
        int visibleLength = 0;
        boolean inTag = false;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (inTag) {
                if (ch == '>') {
                    inTag = false;
                }
                continue;
            }
            if (ch == '<') {
                inTag = true;
                continue;
            }
            visible.append(ch);
            rawIndexByVisible[visibleLength] = i;
            visibleLength++;
        }
        int[] compact = new int[visibleLength];
        System.arraycopy(rawIndexByVisible, 0, compact, 0, visibleLength);
        return new VisibleText(visible.toString(), compact, raw.length());
    }

    private static int moveSplitBoundaryOutsideHtml(String raw, int splitIndex) {
        if (raw == null || raw.isEmpty() || splitIndex <= 0 || splitIndex >= raw.length()) {
            return splitIndex;
        }
        int adjusted = splitIndex;
        int lastOpen = raw.lastIndexOf('<', adjusted - 1);
        int lastClose = raw.lastIndexOf('>', adjusted - 1);
        if (lastOpen > lastClose) {
            int tagEnd = raw.indexOf('>', adjusted);
            if (tagEnd < 0) {
                return raw.length();
            }
            adjusted = tagEnd + 1;
        }
        while (adjusted < raw.length() && raw.startsWith("</", adjusted)) {
            int tagEnd = raw.indexOf('>', adjusted);
            if (tagEnd < 0) {
                return raw.length();
            }
            adjusted = tagEnd + 1;
        }
        return adjusted;
    }

    private static ArabicMatchText normalizeArabicForMatching(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new ArabicMatchText("", new int[0], 0);
        }
        StringBuilder normalized = new StringBuilder(raw.length());
        int[] rawIndexByNormalized = new int[raw.length()];
        int normalizedLength = 0;
        for (int i = 0; i < raw.length(); i++) {
            char ch = raw.charAt(i);
            if (isArabicDiacritic(ch) || ch == '\u0640') {
                continue;
            }
            // Strip brackets so [قال] matches split markers, and sentence punctuation so
            // «قال.بعث» — an edition setting no space after the verb — still matches «قال ».
            if (ch == '[' || ch == ']' || ch == '(' || ch == ')' || ch == '\u300A' || ch == '\u300B'
                    ) {
                normalized.append(' ');
                rawIndexByNormalized[normalizedLength] = i;
                normalizedLength++;
                continue;
            }
            if (ch == '\u0623' || ch == '\u0625' || ch == '\u0622') {
                ch = '\u0627';
            } else if (ch == '\u0649') {
                ch = '\u064A';
            }
            normalized.append(ch);
            rawIndexByNormalized[normalizedLength] = i;
            normalizedLength++;
        }
        int[] compact = new int[normalizedLength];
        System.arraycopy(rawIndexByNormalized, 0, compact, 0, normalizedLength);
        return new ArabicMatchText(normalized.toString(), compact, raw.length());
    }

    private static boolean isArabicDiacritic(char ch) {
        return (ch >= '\u0610' && ch <= '\u061A')
                || (ch >= '\u064B' && ch <= '\u065F')
                || ch == '\u0670'
                || (ch >= '\u06D6' && ch <= '\u06ED');
    }

    private static int visibleLength(String value) {
        if (value == null || value.trim().isEmpty()) {
            return 0;
        }
        String withoutTags = HTML_TAG_PATTERN.matcher(value).replaceAll(" ").trim();
        return withoutTags.length();
    }

    private record ArabicMatchText(String normalized, int[] rawIndexByNormalized, int rawLength) {
        private int toRawBoundary(int normalizedBoundary) {
            if (normalizedBoundary <= 0) {
                return 0;
            }
            if (normalizedBoundary >= rawIndexByNormalized.length) {
                return rawLength;
            }
            return rawIndexByNormalized[normalizedBoundary - 1] + 1;
        }
    }

    private record VisibleText(String visible, int[] rawIndexByVisible, int rawLength) {
        private int toRawBoundary(int visibleBoundary) {
            if (visibleBoundary <= 0) {
                return 0;
            }
            if (visibleBoundary >= rawIndexByVisible.length) {
                return rawLength;
            }
            return rawIndexByVisible[visibleBoundary - 1] + 1;
        }
    }

    private record SplitResult(String chain, String content) {
        private static SplitResult none() {
            return new SplitResult("", "");
        }

        private boolean hasSplit() {
            return !chain.isEmpty() && !content.isEmpty();
        }
    }
}
