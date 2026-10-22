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

    /** The life line: where he lived, when he died, the Imams he transmitted from. */
    function lifeLine(life) {
        if (!life) {
            return "";
        }
        var parts = [];
        var where = arabic()
            ? town(life.city_ar || life.city_reported_ar)
            : town(life.city || life.city_reported);
        if (where) {
            parts.push(escaped(where));
        }
        var died = arabic()
            ? (life.died_ar || life.died_reported_ar)
            : (life.died || life.died_reported);
        if (died) {
            parts.push(escaped(t("narrators.died", "d. {0} AH").replace("{0}", died)));
        }
        var eras = (arabic() ? life.imams_ar : life.imams) || [];
        if (eras.length) {
            parts.push(escaped(t("narrators.preview.with", arabic() ? "عن {0}" : "with {0}")
                .replace("{0}", eras.slice(0, 3).join(" · "))));
        }
        return parts.join('<span class="pv__dot">·</span>');
    }

    /* ── The seal ─────────────────────────────────────────────────────────────
       The same geometry as NarratorSeal.java, which draws it server-side for the cards;
       this copy exists because the preview is built in the browser. Keep the two in step. */
    var PIGMENT = [
        [220, 52, 38], [212, 50, 39], [202, 48, 38], [194, 46, 36], [186, 42, 35],
        [172, 38, 33], [152, 36, 33], [104, 32, 34], [58, 44, 35], [40, 58, 38],
        [26, 58, 38], [10, 52, 39], [348, 42, 40]
    ];
    var MAX_CHAINS = 8736;   // the most chains any narrator stands in
    var TRACK = "M31,5h38a26,26 0 0 1 26,26v38a26,26 0 0 1 -26,26h-38a26,26 0 0 1 -26,-26v-38a26,26 0 0 1 26,-26z";

    /* The lowest Imam ordinal he transmitted from; null when none is on record. */
    function era(man) {
        var ords = (man.imam_ordinals || []).filter(function (n) {
            return typeof n === "number" && n >= 0 && n < PIGMENT.length;
        });
        return ords.length ? Math.min.apply(null, ords) : null;
    }

    function pigment(index, deep) {
        if (index == null) {
            return deep ? "hsl(213 58% 22%)" : "hsl(213 52% 34%)";
        }
        var p = PIGMENT[index];
        return deep ? "hsl(" + p[0] + " " + (p[1] + 6) + "% " + (p[2] - 13) + "%)"
                    : "hsl(" + p[0] + " " + p[1] + "% " + p[2] + "%)";
    }

    function seal(man, size) {
        var index = era(man);
        var id = "pvs" + String(man.person_id).replace(/\W/g, "") + size;
        var big = size >= 40;   // the ring is only legible from 40px up
        var x = big ? 15 : 3, w = big ? 70 : 94, r = big ? 20 : 26;
        var chains = man.chains || 0;
        var ring = "";
        if (big) {
            var fill = Math.min(1, Math.log(1 + chains) / Math.log(1 + MAX_CHAINS));
            ring = '<path d="' + TRACK + '" pathLength="100" fill="none" class="nseal__track" stroke-width="5"/>'
                + '<path d="' + TRACK + '" pathLength="100" fill="none" class="nseal__ring" stroke-width="5"'
                + ' stroke-linecap="round" stroke-dasharray="' + (fill * 100).toFixed(1) + ' 100"/>';
        }
        var name = (man.display_ar || "").replace(/^\s*ال/, "").trim();
        var letter = name ? Array.from(name)[0] : "\u061F";
        return '<svg class="nseal" viewBox="0 0 100 100" width="' + size + '" height="' + size
            + '" aria-hidden="true" focusable="false"><defs><linearGradient id="' + id
            + '" x1="0" y1="0" x2="0.3" y2="1"><stop offset="0" stop-color="' + pigment(index, false)
            + '"/><stop offset="1" stop-color="' + pigment(index, true) + '"/></linearGradient></defs>'
            + ring
            + '<rect x="' + x + '" y="' + x + '" width="' + w + '" height="' + w + '" rx="' + r
            + '" fill="url(#' + id + ')"/>'
            + '<path d="M' + x + " " + (x + r) + "Q" + x + " " + x + " " + (x + r) + " " + x
            + "h" + (w - 2 * r) + "q" + r + " 0 " + r + " " + r + "v5L" + x + " " + (x + w * 0.52)
            + 'Z" fill="#fff" opacity="0.16"/>'
            + '<text x="50" y="' + (x + w * 0.76).toFixed(2) + '" text-anchor="middle"'
            + ' font-family="Scheherazade New, Amiri, serif" font-size="' + (w * 0.68).toFixed(2)
            + '" fill="#fff">' + escaped(letter) + "</text></svg>";
    }

    /* The generations strip: the Prophet and the twelve Imams as thirteen marks, his own lit
       in his pigment. It says in a glance what the life line says in words, and the words
       stay in the label for a screen reader. A man with no Imam on record gets no strip. */
    function strip(man) {
        var lit = (man.imam_ordinals || []).filter(function (n) {
            return typeof n === "number" && n >= 0 && n < PIGMENT.length;
        });
        if (!lit.length) {
            return "";
        }
        var names = ((arabic() ? (man.life || {}).imams_ar : (man.life || {}).imams) || []);
        var label = names.length
            ? t("narrators.preview.with", arabic() ? "عن {0}" : "with {0}")
                .replace("{0}", names.join(arabic() ? "، " : ", "))
            : "";
        var marks = "";
        for (var i = 0; i < PIGMENT.length; i++) {
            var on = lit.indexOf(i) >= 0;
            marks += '<i class="pv__gen' + (on ? " is-lit" : "") + '"'
                + (on ? ' style="background:hsl(' + PIGMENT[i][0] + " " + (PIGMENT[i][1] + 14) + "% "
                    + (PIGMENT[i][2] + 24) + '%)"' : "") + "></i>";
        }
        return '<div class="pv__gens" role="img"'
            + (label ? ' aria-label="' + escaped(label) + '"' : ' aria-hidden="true"') + ">"
            + marks + "</div>";
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

    /** One row per word: the books' own term, and the books that use it. Never averaged. */
    function saidRows(man) {
        var said = man.said_by || [];
        if (!said.length) {
            // Muhmal: the books hold him and state no word. Said, not left blank.
            return '<div class="pv__row"><span class="pv__w pv__w--none" lang="ar">\u0645\u0647\u0645\u0644</span>'
                + '<span class="pv__b">' + escaped(
                    t("narrator.standing.unstated", "mentioned, no judgement passed")
                        .split(/\s[\u2014\u2013-]\s/).pop()) + "</span></div>";
        }
        return said.map(function (row) {
            var books = (arabic() && row.books_ar && row.books_ar.length)
                ? row.books_ar : (row.books || []);
            var word = row.said_ar || row.said || "";
            return '<div class="pv__row"><span class="pv__w pv__w--' + escaped(row.leaning)
                + '" lang="ar">' + escaped(word) + "</span>"
                + '<span class="pv__b">' + escaped(books.join(" \u00b7 ")) + "</span></div>";
        }).join("");
    }

    function body(man) {
        var href = prefix() + "/narrator/" + man.person_id
            + (man.slug ? "/" + man.slug : "");
        var rests = man.standing_rests_on;
        var caveat = (rests && rests !== "early")
            ? '<p class="pv__rests">'
                + escaped(t("narrator.standing.restsOn." + rests, "")) + "</p>"
            : "";
        var index = era(man);
        var tint = index == null ? "transparent"
            : "hsl(" + PIGMENT[index][0] + " " + PIGMENT[index][1] + "% " + PIGMENT[index][2] + "% / 0.10)";
        var divided = (man.said_by || []).length > 1;
        var heading = divided
            ? t("narrators.preview.divide", arabic() ? "الكتب مختلفة فيه" : "The books divide")
            : t("narrators.preview.says", arabic() ? "ما تقوله الكتب" : "What the books say");
        var chains = man.chains
            ? '<b>' + escaped(digits(Number(man.chains).toLocaleString(arabic() ? "ar" : "en"))) + "</b> "
                + escaped(t("narrators.preview.narrations", arabic() ? "رواية" : "narrations"))
            : (man.book_count ? escaped(countPhrase(man.book_count, "narrators.books")) : "");
        // The name sits beside the seal; the seal's colour is his generation and its ring
        // the weight of the corpus that runs through him.
        return '<div class="pv__head" style="--tint:' + tint + '">'
            + seal(man, 52)
            + "<div>"
            + '<p class="pv__ar" dir="rtl" lang="ar">' + escaped(man.display_ar) + "</p>"
            + (!arabic() && man.display_en
                ? '<p class="pv__en">' + escaped(man.display_en) + "</p>" : "")
            + '<p class="pv__life">' + lifeLine(man.life) + "</p>"
            + strip(man)
            + "</div></div>"
            + '<div class="pv__says"><h3>' + escaped(heading) + "</h3>"
            + '<div class="pv__rows">' + saidRows(man) + "</div>" + caveat + "</div>"
            + '<div class="pv__foot"><span>' + chains + "</span>"
            + '<a class="np__go" href="' + escaped(href) + '">'
            + escaped(t("js.preview.open", "Open his page")) + "</a></div>";
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

    /* hadith.html, chapter.html and index.html build their own heads, so the card's
       stylesheet cannot be linked once for all of them. It is added here, after the page's
       own sheets, the first time a card is made. */
    function styled() {
        if (document.querySelector('link[href*="narrator-cards"]')) {
            return;
        }
        var link = document.createElement("link");
        link.rel = "stylesheet";
        link.href = "/css/narrator-cards.css";
        document.head.appendChild(link);
    }

    function frame() {
        if (card) {
            return card;
        }
        styled();
        card = document.createElement("div");
        card.className = "narrator-preview pv";
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
