package com.rewayaat.controllers;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the two hadith-card renderers from drifting apart.
 *
 * <p>The card is painted twice: by Vue from JSON on the search page (index.html), and by
 * the server from Elasticsearch on a chapter page (fragments/hadith-card.html). That
 * duplication is deliberate — the search page needs per-card client state for its
 * Related and Tafsir panels, and the chapter page must be readable by a crawler that
 * runs no scripts — but it is exactly the kind of duplication that rots.
 *
 * <p>Visual drift is already impossible: both use the same class names, so they share one
 * stylesheet. This covers the other half — structural drift. If the Vue card gains,
 * loses or renames a structural class, the server card has to keep up or this fails.
 *
 * <p>If you are here because this test broke: either mirror the change in the other
 * template, or, if the class genuinely belongs to only one of them, add it to
 * {@link #CLIENT_ONLY}.
 */
class HadithCardParityTest {

    /**
     * The search card is spread across two files: the outer markup sits in index.html,
     * while the metadata panel is the hadith-details Vue component. Both count.
     */
    private static final List<Path> SEARCH_CARD = List.of(
            Path.of("src/main/resources/templates/index.html"),
            Path.of("src/main/resources/static/js/vue-components.js"));

    private static final Path SERVER_CARD =
            Path.of("src/main/resources/templates/fragments/hadith-card.html");

    /** The classes that carry the card's structure, as opposed to its state or theming. */
    private static final List<String> STRUCTURAL = List.of(
            "hadith-card",
            "hadith-card__result-num",
            "card-body",
            "hadith-card__layout",
            "hadith-sidecar",
            "hadith-card__meta",
            "hadith-sidecar__rail",
            "hadith-sidecar__rail-btn",
            "hadith-sidecar__rail-indicator",
            "hadith-sidecar__rail-label",
            "hadith-sidecar__rail-icon",
            "hadith-sidecar__panel",
            "hadith-sidecar__panel-body",
            "hadith-details",
            "meta-item",
            "meta-icon",
            "meta-label",
            "meta-text",
            "hadith-card__resizer",
            "hadith-card__main",
            "hadith-content",
            "hadith-card__ornament",
            "hadith-card__ornament-line",
            "hadith-card__ornament-mark",
            "hadith-reading-scroll",
            "hadith-reading-row",
            "hadith-text-block",
            "hadith-text-block--primary",
            "hadith-text-block--arabic",
            "hadith-chain",
            "hadith-english",
            "hadith-arabic",
            "arabic-text",
            "hadith-notes",
            "hadith-notes__head",
            "hadith-notes__label",
            "hadith-notes__body",
            "hadith-card__footer",
            "topic-tag-pills",
            "hadith-card__tags",
            "topic-tag-pills__label",
            "topic-pill",
            "hadith-card__actions",
            "icon-action");

    /**
     * Classes the search card carries that the server card deliberately does not: they
     * belong to behaviour that needs a live Vue instance, and the server card links to
     * the narration's own page for that material instead.
     */
    private static final Set<String> CLIENT_ONLY = Set.of(
            "hadith-sidecar__rail-badge",     // live counts, fetched per card
            "hadith-reading-toggle",          // show-full-hadith clamp toggle
            "hadith-notes__toggle",           // show-more on long notes
            "hadith-tags-toggle",             // mobile tag overflow
            "hadith-copy-dropdown",           // copy menu
            "hadith-inline-context");         // the Related / Tafsir inline panels

    /**
     * Actions the search card carries that cannot mean anything on a server-rendered
     * page. Removing from a collection needs a collection to be open; nothing else
     * belongs here, and editing notably does not — it links to /edit.
     */
    private static final Set<String> SEARCH_ONLY_ACTIONS = Set.of("remove");

    private static final String SHARE_DIALOG = "share-card-modal.js";
    private static final String CARD_SCRIPT = "hub-pages.js";

    /**
     * Menu entries the search card offers that are not the card's to mirror. Exporting
     * a PDF is a page-level action on the hub pages, reached from the chapter toolbar
     * rather than from a narration's copy menu.
     */
    private static final Set<String> SEARCH_ONLY_MENU_ITEMS = Set.of("Export PDF");

    @Test
    void serverCardCarriesEveryStructuralClassTheSearchCardDoes() throws IOException {
        String index = readSearchCard();
        String card = read(SERVER_CARD);

        Set<String> missing = new LinkedHashSet<>();
        for (String cls : STRUCTURAL) {
            if (containsClass(index, cls) && !containsClass(card, cls) && !CLIENT_ONLY.contains(cls)) {
                missing.add(cls);
            }
        }

        assertTrue(missing.isEmpty(),
                "The search card uses these structural classes and the server card does not: " + missing
                        + "\nMirror them in fragments/hadith-card.html, or add them to CLIENT_ONLY "
                        + "if they genuinely belong only to the interactive card.");
    }

    /**
     * The reverse direction. A class only the server card has is usually a sign the two
     * have been edited independently, which is how they start to look different.
     */
    @Test
    void serverCardInventsNoStructuralClassesOfItsOwn() throws IOException {
        String index = readSearchCard();
        String card = read(SERVER_CARD);

        Set<String> extra = new LinkedHashSet<>();
        for (String cls : STRUCTURAL) {
            if (containsClass(card, cls) && !containsClass(index, cls)) {
                extra.add(cls);
            }
        }

        assertTrue(extra.isEmpty(),
                "The server card uses structural classes the search card does not: " + extra);
    }

    /**
     * The action rail is where a missing class costs a reader something they can name.
     * The list above is hand-maintained, so it only catches drift somebody remembered
     * to enumerate: the edit pencil went missing from the server card for an entire
     * release because {@code icon-action--edit} was never added to {@link #STRUCTURAL}.
     * This test derives the modifiers from the search card instead, so a new action
     * button has to be mirrored or excused on purpose.
     */
    @Test
    void everyCardActionIsOfferedOnServerRenderedPagesToo() throws IOException {
        Set<String> searchActions = actionModifiers(readSearchCard());
        Set<String> serverActions = actionModifiers(read(SERVER_CARD));

        assertFalse(searchActions.isEmpty(), "found no icon-action modifiers in the search card");

        Set<String> missing = new LinkedHashSet<>(searchActions);
        missing.removeAll(serverActions);
        missing.removeAll(SEARCH_ONLY_ACTIONS);

        assertTrue(missing.isEmpty(),
                "The search card offers these actions and the server card does not: " + missing
                        + "\nAdd them to fragments/hadith-card.html, or to SEARCH_ONLY_ACTIONS "
                        + "with a reason if they cannot work outside the search app.");
    }

    /**
     * The copy and share menus are the other half of the rail, and the same blind spot
     * applies: a "Copy image" added to one card and not the other reads as a feature
     * that works everywhere until somebody opens a chapter page. Labels are compared
     * because they are what the reader is promised, and both cards write them as plain
     * text in the same place.
     */
    @Test
    void everyCardMenuItemIsOfferedOnServerRenderedPagesToo() throws IOException {
        Set<String> searchItems = copyMenuItems(readSearchCard());
        Set<String> serverItems = copyMenuItems(read(SERVER_CARD));

        assertFalse(searchItems.isEmpty(), "found no copy-menu items in the search card");

        Set<String> missing = new LinkedHashSet<>(searchItems);
        missing.removeAll(serverItems);
        missing.removeAll(SEARCH_ONLY_MENU_ITEMS);

        assertTrue(missing.isEmpty(),
                "The search card's copy and share menus offer these and the server card does not: "
                        + missing + "\nMirror them in fragments/hadith-card.html, or add them to "
                        + "SEARCH_ONLY_MENU_ITEMS with a reason.");
    }

    /**
     * What every dropdown item inside a hadith-copy-menu offers.
     *
     * <p>Identified by its message key rather than its English label, now that both
     * cards render these through the bundle — one as {@code th:text="#{key}"} and one
     * as {@code tr('key', ...)}. The key is the stronger comparison anyway: two items
     * that read the same in English but resolve differently are not the same item.
     * An item with no key falls back to its text, so a literal added to one card and
     * not the other is still caught.
     */
    private static final Pattern MESSAGE_KEY =
            Pattern.compile("#\\{([a-zA-Z0-9_.]+)\\}|tr\\(\\s*'([a-zA-Z0-9_.]+)'");

    private static Set<String> copyMenuItems(String html) {
        Set<String> found = new LinkedHashSet<>();
        Matcher menu = Pattern.compile(
                "<ul[^>]*hadith-copy-menu[^>]*>(.*?)</ul>", Pattern.DOTALL).matcher(html);
        while (menu.find()) {
            Matcher item = Pattern.compile(
                    "<button[^>]*class=\"dropdown-item\"[^>]*>(.*?)</button>",
                    Pattern.DOTALL).matcher(menu.group(1));
            while (item.find()) {
                Matcher key = MESSAGE_KEY.matcher(item.group());
                if (key.find()) {
                    found.add(key.group(1) != null ? key.group(1) : key.group(2));
                    continue;
                }
                String label = item.group(1).replaceAll("<[^>]*>", " ")
                        .replaceAll("\\s+", " ").trim();
                if (!label.isEmpty()) {
                    found.add(label);
                }
            }
        }
        return found;
    }

    /** Whether the page actually pulls the script in, rather than merely naming it. */
    private static boolean loads(String html, String script) {
        return Pattern.compile("<script[^>]*\\b(?:src|th:src)\\s*=\\s*[\"'][^\"']*"
                        + Pattern.quote(script), Pattern.CASE_INSENSITIVE)
                .matcher(html).find();
    }

    private static Set<String> actionModifiers(String html) {
        Set<String> found = new LinkedHashSet<>();
        Matcher m = Pattern.compile("icon-action--([a-z][a-z-]*)").matcher(html);
        while (m.find()) {
            found.add(m.group(1));
        }
        return found;
    }

    /**
     * hub-pages.js binds the card's Share-as-image entry to a dialog that lives in
     * share-card-modal.js, so a page that loads one and not the other has a button that
     * does nothing at all — no error, no dialog. That is what /hadith/{id} shipped:
     * hadith.html builds its own head rather than taking fragments/site :: sitehead, so
     * it picked up hub-pages.js and missed the dialog.
     *
     * <p>Checking "does the page reference fragments/site" is not enough, and an earlier
     * version of this test made exactly that mistake: hadith.html does reference it, for
     * a breadcrumb, and the test passed while the button was dead. The dependency between
     * the two scripts is the thing worth asserting, because it holds however a page is
     * composed.
     */
    @Test
    void everyPageLoadingTheCardScriptAlsoLoadsTheShareDialog() throws IOException {
        Path templates = Path.of("src/main/resources/templates");
        Set<String> silent = new LinkedHashSet<>();
        int carriers = 0;

        try (Stream<Path> walk = Files.walk(templates)) {
            for (Path page : walk.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".html")).toList()) {
                String html = read(page);
                // A script tag, not a mention: the card fragment names hub-pages.js in a
                // comment explaining who drives its dropdowns.
                if (!loads(html, CARD_SCRIPT)) {
                    continue;
                }
                carriers++;
                if (!loads(html, SHARE_DIALOG)) {
                    silent.add(page.getFileName().toString());
                }
            }
        }

        assertTrue(carriers > 0, "no template loads " + CARD_SCRIPT + " any more");
        assertTrue(silent.isEmpty(),
                "These templates load " + CARD_SCRIPT + ", which binds the card's "
                        + "Share-as-image entry, but not " + SHARE_DIALOG + ": " + silent
                        + "\nThe menu entry renders and clicking it does nothing.");
    }

    /**
     * The tag list must collapse identically on both cards.
     *
     * <p>These are two implementations of one behaviour - Vue's
     * {@code visibleNarrationTopicTags} on the search page, {@code bindTagOverflow} in
     * hub-pages.js on the server-rendered ones - and they drifted: the static pages
     * collapsed after 4 tags at every width while the search card collapsed after 2 and
     * only below 768px. A narration therefore showed all its tags in search results and a
     * truncated list on its own page, which is the kind of difference a reader notices and
     * no test was watching.
     *
     * <p>Reading the numbers out of the source is deliberate. The alternative - asserting
     * the rendered output - needs both a browser and a narration with enough tags, and
     * would still not say which of the two was wrong.
     */
    @Test
    void bothCardsCollapseTagsAtTheSameCountAndBreakpoint() throws IOException {
        String searchJs = read(Path.of("src/main/resources/static/js/rewayaat.js"));
        String hubJs = read(Path.of("src/main/resources/static/js/hub-pages.js"));

        assertEquals(intConstant(searchJs, "MOBILE_VISIBLE_TAGS"),
                intConstant(hubJs, "VISIBLE_TAGS"),
                "the two cards collapse the tag list after a different number of tags");

        assertEquals(768, intConstant(hubJs, "TAG_COLLAPSE_MAX_WIDTH"),
                "hub-pages.js collapses at a different width than the search card's "
                        + "mobileViewport test (window.innerWidth <= 768)");
        assertTrue(searchJs.contains("window.innerWidth <= 768"),
                "the search card's breakpoint moved; hub-pages.js still says 768");
    }

    /**
     * The filter-by-tag pills must be built from the same classes on both surfaces.
     *
     * <p>They were not: the server-rendered chapter page put the count in
     * {@code .topic-pill__count}, plain dimmed text, while the search page put it in
     * {@code .tag-filter-bar__pill-count}, a filled badge. Same bar, same data, two
     * different-looking pills - and nothing failed, because each class existed and was
     * styled, just differently.
     */
    @Test
    void theTagFilterPillsUseTheSameClassesOnBothSurfaces() throws IOException {
        String search = read(Path.of("src/main/resources/templates/index.html"));
        String chapter = read(Path.of("src/main/resources/templates/chapter.html"));

        for (String cls : List.of("tag-filter-bar__pill", "tag-filter-bar__pill-label",
                "tag-filter-bar__pill-count")) {
            assertTrue(containsClass(search, cls), "the search page's filter pill lost " + cls);
            assertTrue(containsClass(chapter, cls),
                    "the chapter page's filter pill is missing " + cls
                            + ", so its pills will not look like the search page's");
        }
    }

    /** Reads {@code var NAME = <int>;} out of a script. */
    private static int intConstant(String source, String name) {
        Matcher m = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*=\\s*(\\d+)").matcher(source);
        assertTrue(m.find(), "could not find " + name + ", so this test is not checking "
                + "anything - fix the test rather than deleting it");
        return Integer.parseInt(m.group(1));
    }

    /** The classes the whole scheme rests on; if these vanish the sharing is over. */
    @Test
    void bothCardsShareTheLoadBearingClasses() throws IOException {
        String index = readSearchCard();
        String card = read(SERVER_CARD);

        for (String cls : List.of("hadith-card", "hadith-sidecar", "hadith-english", "hadith-arabic")) {
            assertTrue(containsClass(index, cls), "search card lost " + cls);
            assertTrue(containsClass(card, cls), "server card lost " + cls);
        }
    }

    private static boolean containsClass(String html, String cls) {
        Matcher m = Pattern.compile("class=\"([^\"]*)\"").matcher(html);
        while (m.find()) {
            for (String token : m.group(1).trim().split("\\s+")) {
                if (token.equals(cls)) {
                    return true;
                }
            }
        }
        // Thymeleaf and Vue also add classes through th:classappend / v-bind:class.
        return html.contains("'" + cls + "'") || html.contains("\"" + cls + "\"");
    }

    private static String readSearchCard() throws IOException {
        StringBuilder combined = new StringBuilder();
        for (Path path : SEARCH_CARD) {
            combined.append(read(path)).append('\n');
        }
        return combined.toString();
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    // ── The sidecar panels ──────────────────────────────────────────────────────
    //
    // The rail and its panels are the part of the card that has drifted most, because
    // each renderer builds them differently: the server card declares them as markup
    // with data-hub-panel, the search card switches on a tab key in Vue. Three bugs in
    // one day came from a change landing on one of them — the Tafsir rail hidden in
    // Arabic on one card and not the other, the related list rewritten on one and not
    // the other, a helper added to one bundle and not the other.

    private static final Pattern SERVER_PANEL =
            Pattern.compile("data-hub-panel=\"([a-z]+)\"");
    private static final Pattern SEARCH_PANEL =
            Pattern.compile("setNarrationSidecarTab\\(narration, '([a-z]+)'\\)");

    @Test
    void bothCardsOfferTheSameSidecarPanels() throws IOException {
        Set<String> server = matches(SERVER_PANEL, read(SERVER_CARD));
        Set<String> search = matches(SEARCH_PANEL, readSearchCard());

        assertEquals(server, search,
                "the two cards offer different sidecar panels.\n"
                        + "  server card: " + server + "\n"
                        + "  search card: " + search
                        + "\nA panel added to one is invisible on the other's pages.");
    }

    /**
     * A rule about language has to hold on both cards or on neither.
     *
     * <p>Each of these hides something on the Arabic site because there is no Arabic
     * version of it to show. Landing the rule on one renderer leaves the other showing
     * English on a page with no other English on it, which is only visible to someone
     * reading that page in Arabic — so it is found late, by a reader, rather than here.
     */
    @Test
    void everyArabicRuleHoldsOnBothCards() throws IOException {
        String server = read(SERVER_CARD);
        String search = readSearchCard();

        // marker -> what it is, for the failure message
        record Rule(String server, String search, String what) { }
        List<Rule> rules = List.of(
                new Rule("data-hub-panel=\"quran\"",
                         "setNarrationSidecarTab(narration, 'quran')",
                         "the Tafsir panel, whose commentary is English-only"),
                new Rule("hadith-english",
                         "similar-full-english",
                         "the English translation column"));

        List<String> broken = new ArrayList<>();
        for (Rule rule : rules) {
            boolean onServer = gatedOnArabic(server, rule.server());
            boolean onSearch = gatedOnArabic(search, rule.search());
            if (onServer != onSearch) {
                broken.add(rule.what() + " — hidden in Arabic on the "
                        + (onServer ? "server" : "search") + " card only");
            }
        }
        assertTrue(broken.isEmpty(),
                "a language rule landed on one card and not the other:\n  "
                        + String.join("\n  ", broken)
                        + "\nGate it in both, with th:unless=\"${isArabic}\" or th:if=\"${!isArabic}\".");
    }

    /**
     * Whether the element carrying {@code marker} is hidden on the Arabic site.
     *
     * <p>Read from the opening tag the marker sits in, so a gate on some unrelated
     * element further up the file does not count as gating this one.
     */
    private static boolean gatedOnArabic(String html, String marker) {
        int at = html.indexOf(marker);
        while (at >= 0) {
            int open = html.lastIndexOf('<', at);
            int close = html.indexOf('>', at);
            if (open >= 0 && close > open) {
                String tag = html.substring(open, close);
                if (tag.contains("th:unless=\"${isArabic}\"") || tag.contains("th:if=\"${!isArabic}\"")) {
                    return true;
                }
            }
            at = html.indexOf(marker, at + 1);
        }
        return false;
    }

    /**
     * The two script bundles carry the same i18n helpers.
     *
     * <p>hub-pages.js and rewayaat.js are loaded by different pages and neither can see
     * the other, so each writes its own t(), escapeHtml() and localeHref(). That is
     * survivable until a new one is added to just the first: the related panel on the
     * narration page rendered "Could not load this panel" for a day because
     * localeField() and localeDigits() existed only in rewayaat.js, and the failure was
     * silent everywhere except the Arabic pages hub-pages.js serves.
     */
    @Test
    void bothScriptBundlesCarryTheSameLocaleHelpers() throws IOException {
        Path hub = Path.of("src/main/resources/static/js/hub-pages.js");
        Path app = Path.of("src/main/resources/static/js/rewayaat.js");
        String hubJs = read(hub);
        String appJs = read(app);

        for (String helper : List.of("t", "escapeHtml", "localeHref", "localeField", "localeDigits")) {
            assertTrue(defines(hubJs, helper), "hub-pages.js does not define " + helper + "()");
            assertTrue(defines(appJs, helper), "rewayaat.js does not define " + helper + "()");
        }

        // Anything else named locale* has to exist in both too, so the next one added is
        // not a fourth copy of this bug.
        Set<String> hubLocale = matches(Pattern.compile("function (locale[A-Za-z]+)\\("), hubJs);
        Set<String> appLocale = matches(Pattern.compile("function (locale[A-Za-z]+)\\("), appJs);
        assertEquals(appLocale, hubLocale,
                "the two bundles define different locale helpers.\n"
                        + "  hub-pages.js: " + hubLocale + "\n"
                        + "  rewayaat.js:  " + appLocale
                        + "\nA page loading the bundle without it throws on the first call.");
    }

    private static boolean defines(String js, String name) {
        return Pattern.compile("function " + Pattern.quote(name) + "\\(").matcher(js).find();
    }

    private static Set<String> matches(Pattern pattern, String text) {
        Set<String> found = new java.util.TreeSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

}
