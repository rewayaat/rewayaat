/*
 * Who was that man? — the card a reader gets when he taps a name in an isnād.
 *
 * A chain names eight men and a reader knows three of them. Until now the only way to ask
 * about the fourth was to leave the hadith, read his page and come back, which nobody does
 * mid-sentence. This answers in place: what the books call him, when and where he lived, how
 * much is written about him, and a way through to the whole page if he turns out to matter.
 *
 * It shows what a book says and names the book; it never grades a man itself, and it never
 * colours a name in the isnād by his standing. A reader must be able to read the chain as the
 * compiler wrote it, not as this site scores it.
 *
 * One delegated listener, so it works identically on a hadith page, a narrator's narrations
 * and the search results the client renders itself. A modified click is left alone — ctrl,
 * cmd, shift and the middle button still open his page in a tab, because a reader who asked
 * for that asked for that.
 */
(function () {
    "use strict";

    // person_id -> the brief, fetched once a session. Kept on window because the rijāl panel
    // fetches the briefs of a whole chain in one request; filling this same map is what makes
    // the card for any of those men open without asking again.
    var held = window.narratorBriefs = window.narratorBriefs || {};
    var card = null;
    var openedFrom = null;

    function arabic() {
        return (window.I18N_LOCALE || "en") === "ar";
    }

    function rtl() {
        return (window.I18N_DIR || "ltr") === "rtl";
    }

    function prefix() {
        return arabic() ? "/ar" : "";
    }

    function t(key, fallback) {
        return (window.I18N || {})[key] || fallback;
    }

    function escaped(text) {
        var out = document.createElement("span");
        out.textContent = text == null ? "" : String(text);
        return out.innerHTML;
    }

    function digits(value) {
        if (!arabic() || value == null) {
            return String(value == null ? "" : value);
        }
        return String(value).replace(/[0-9]/g, function (d) {
            return String.fromCharCode(0x0660 + Number(d));
        });
    }

    /* «Kufa (Shayban/Mawla)» carries his tribe, which the page has room for and six lines
       do not. The card keeps the town. */
    function town(name) {
        return name ? String(name).replace(/\s*\(.*\)\s*$/, "") : "";
    }

    /** The life line: the Imams he transmitted under, when he died, where he lived. */
    function lifeLine(life) {
        if (!life) {
            return "";
        }
        var parts = [];
        var eras = (arabic() ? life.imams_ar : life.imams) || [];
        if (eras.length) {
            parts.push(escaped(eras.slice(0, 3).join(" · ")));
        }
        var died = arabic()
            ? (life.died_ar || life.died_reported_ar)
            : (life.died || life.died_reported);
        if (died) {
            parts.push(escaped(t("narrators.died", "d. {0} AH").replace("{0}", died)));
        }
        var where = arabic()
            ? town(life.city_ar || life.city_reported_ar)
            : town(life.city || life.city_reported);
        if (where) {
            parts.push(escaped(where));
        }
        return parts.join('<span class="np__dot">·</span>');
    }

    /* «1 books» is wrong in English and «١١ كتب» is wrong in Arabic, which counts one,
       two, a few (three to ten) and many (eleven up) differently. The bare key carries the
       many form; the rest hang off it. Exposed below, because the search results count the
       same things in the same words. */
    function countPhrase(howMany, base) {
        var form = base;
        if (howMany === 1) {
            form = base + "One";
        } else if (howMany === 2) {
            form = base + "Two";
        } else if (howMany >= 3 && howMany <= 10) {
            form = base + "Few";
        }
        var pattern = (window.I18N || {})[form] || (window.I18N || {})[base]
            || "{0} " + base.replace("narrators.", "");
        return pattern.replace("{0}", digits(howMany));
    }

    /* The books' own word first. A rijāl reader looks for «ثقة» or «كذاب», not for an
       English gloss, so the Arabic term leads even on the English site and the gloss
       follows it. Some entries carry «thiqa — trustworthy» in one string and some only
       «a liar», so where the Arabic term already stands beside it the transliterated
       half is dropped and only the plain-language half kept. */
    function gloss(said) {
        if (!said) {
            return "";
        }
        var halves = String(said).split(" \u2014 ");
        return halves[halves.length - 1];
    }

    /** What the books say, in their own words, with the books that say it. */
    function saidRows(said) {
        // An ungraded man gets no line about it; the empty space is the same statement
        // and the box is small.
        if (!said || !said.length) {
            return "";
        }
        return said.slice(0, 3).map(function (row) {
            var books = (arabic() && row.books_ar && row.books_ar.length)
                ? row.books_ar : (row.books || []);
            var shown = books.slice(0, 2).join(" · ");
            var rest = books.length - 2;
            if (rest > 0) {
                shown += " " + t("narrators.andMore", "+{0}").replace("{0}", digits(rest));
            }
            var word = row.said_ar
                ? '<span class="np__word" dir="rtl" lang="ar">'
                    + escaped(row.said_ar) + "</span>"
                : "";
            if (!arabic() && (row.said || !word)) {
                word += '<span class="np__gloss">'
                    + escaped(word ? gloss(row.said) : row.said) + "</span>";
            }
            return '<p class="np__said np__said--' + escaped(row.leaning) + '">'
                + word
                + '<span class="np__books">' + escaped(shown) + "</span></p>";
        }).join("");
    }

    function body(man) {
        var href = prefix() + "/narrator/" + man.person_id
            + (man.slug ? "/" + man.slug : "");
        var rests = man.standing_rests_on;
        var caveat = (rests && rests !== "early")
            ? '<p class="np__rests">'
                + escaped(t("narrator.standing.restsOn." + rests, "")) + "</p>"
            : "";
        var weight = man.entry_count
            ? '<p class="np__weight">'
                + escaped(countPhrase(man.entry_count, "narrators.entries"))
                + '<span class="np__dot">·</span>'
                + escaped(countPhrase(man.book_count, "narrators.books"))
                + "</p>"
            : "";
        // The name sits on the dark panel the rest of the site names things on. A white
        // card over a white page is a blur with a shadow; this gives it an edge to start at.
        return '<div class="np__head">'
            + '<p class="np__name" dir="rtl" lang="ar">' + escaped(man.display_ar) + "</p>"
            + (!arabic() && man.display_en
                ? '<p class="np__name-en">' + escaped(man.display_en) + "</p>" : "")
            + "</div>"
            + '<div class="np__rest">'
            + (saidRows(man.said_by) + caveat
                ? '<div class="np__said-rows">' + saidRows(man.said_by) + caveat + "</div>" : "")
            + '<p class="np__life">' + lifeLine(man.life) + "</p>"
            + weight
            + '<a class="np__go" href="' + escaped(href) + '">'
            + escaped(t("js.preview.open", "Open his page")) + "</a>"
            + "</div>";
    }

    function shut() {
        if (!card || !card.classList.contains("is-open")) {
            return;
        }
        card.classList.remove("is-open");
        if (openedFrom && typeof openedFrom.focus === "function") {
            openedFrom.focus();
        }
        openedFrom = null;
    }

    function frame() {
        if (card) {
            return card;
        }
        card = document.createElement("div");
        card.className = "narrator-preview";
        card.setAttribute("role", "dialog");
        card.setAttribute("aria-label", t("js.preview", "Preview"));
        card.innerHTML = '<button class="np__shut" type="button" aria-label="'
            + escaped(t("js.close", "Close")) + '">&times;</button>'
            + '<div class="np__body"></div>';
        document.body.appendChild(card);
        card.querySelector(".np__shut").addEventListener("click", shut);
        return card;
    }

    /** Beside the name on a wide screen; a sheet along the bottom on a narrow one. */
    function place(anchor) {
        if (window.matchMedia("(max-width: 640px)").matches) {
            card.classList.add("is-sheet");
            card.style.top = "";
            card.style.left = "";
            return;
        }
        card.classList.remove("is-sheet");
        var box = anchor.getBoundingClientRect();
        var width = card.offsetWidth || 300;
        var left = rtl() ? box.right - width : box.left;
        left = Math.max(12, Math.min(left, window.innerWidth - width - 12));
        var tall = card.offsetHeight;
        var below = box.bottom + window.scrollY + 10;
        var above = box.top + window.scrollY - tall - 10;
        var room = window.innerHeight - box.bottom;
        card.style.left = left + "px";
        card.style.top = (room < tall + 24 && above > window.scrollY ? above : below) + "px";
    }

    function show(anchor, man) {
        frame();
        card.querySelector(".np__body").innerHTML = body(man);
        card.classList.add("is-open");
        openedFrom = anchor;
        place(anchor);
        var go = card.querySelector(".np__go");
        if (go) {
            go.focus({preventScroll: true});
        }
    }

    function ask(anchor, id) {
        if (held[id]) {
            show(anchor, held[id]);
            return;
        }
        frame().classList.add("is-waiting");
        fetch("/v1/narrators/" + encodeURIComponent(id) + "/preview")
            .then(function (response) { return response.ok ? response.json() : null; })
            .then(function (payload) {
                card.classList.remove("is-waiting");
                if (!payload || !payload.narrator) {
                    return;
                }
                held[id] = payload.narrator;
                show(anchor, payload.narrator);
            })
            .catch(function () {
                // The link underneath still works; there is nothing to say here.
                card.classList.remove("is-waiting");
            });
    }

    function nameUnder(target) {
        if (!target || !target.closest) {
            return null;
        }
        return target.closest("a.chain-name");
    }

    document.addEventListener("click", function (event) {
        var name = nameUnder(event.target);
        if (name) {
            // A reader who ctrl-clicked asked for a new tab, not for a card.
            if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) {
                return;
            }
            var tail = (name.getAttribute("href") || "").split("/narrator/")[1];
            var id = tail ? tail.split("/")[0].split("?")[0] : "";
            if (!id) {
                return;
            }
            event.preventDefault();
            if (openedFrom === name && card && card.classList.contains("is-open")) {
                shut();
                return;
            }
            ask(name, id);
            return;
        }
        if (card && card.classList.contains("is-open")
                && !event.target.closest(".narrator-preview")) {
            shut();
        }
    });

    document.addEventListener("keydown", function (event) {
        if (event.key === "Escape") {
            shut();
        }
    });

    // The search results count entries and books too, and count them the same way.
    window.narratorCountPhrase = countPhrase;

    window.addEventListener("resize", shut);
    window.addEventListener("hashchange", shut);
}());
