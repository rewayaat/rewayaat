/*
 * A type-to-filter box over a long list of chapters, parts or volumes.
 *
 * A book with eight volumes is read at a glance; Maʿānī al-Akhbār's four hundred and
 * twenty-seven chapters are not, and ctrl-F was the only way anyone found one. The server
 * decides whether a list is long enough to deserve this (see the hubfilter fragment) and
 * renders the whole list either way: this only hides rows, so with scripts off every
 * chapter is still there and still crawlable, which is the right failure.
 *
 * Matching is done on folded text. A reader typing «كتاب» must reach «كِتَابُ», and one
 * typing "wudu" must reach "Wuḍūʾ", so diacritics come off both sides — Arabic marks by
 * range, Latin ones through NFD — along with the hamza seats and the two Persian letters
 * that turn up in our Arabic. Arabic-Indic digits fold to Latin so a chapter number typed
 * either way finds the same row.
 *
 * Folding is done one character at a time, keeping the index each folded character came
 * from, so the hit can be marked in the text the reader is actually looking at. Folding
 * the string in one pass would have given a position in a string nobody can see: «كِتَابُ»
 * is seven characters on screen and four folded, and the mark would land two letters off.
 */
(function () {
    "use strict";

    var ARABIC_MARK = /[ً-ْٰـۖ-ۭ]/;
    var LATIN_MARK = /[̀-ͯ]/g;
    var NOT_WORD = /[^\p{L}\p{N}]/u;
    var SEATS = { "أ": "ا", "إ": "ا", "آ": "ا", "ٱ": "ا", "ى": "ي", "ی": "ي",
                  "ک": "ك", "ؤ": "و", "ئ": "ي", "ة": "ه" };

    function t(key, fallback) {
        return (window.I18N || {})[key] || fallback;
    }

    function digits(n) {
        return (window.I18N_LOCALE === "ar") ? Number(n).toLocaleString("ar-EG") : String(n);
    }

    /* One character, folded. Returns "" for anything that should vanish (a diacritic),
       " " for a separator, and otherwise the single character to match on. */
    function foldChar(ch) {
        if (ARABIC_MARK.test(ch)) {
            return "";
        }
        var code = ch.charCodeAt(0);
        if (code >= 0x0660 && code <= 0x0669) {
            return String(code - 0x0660);
        }
        if (code >= 0x06F0 && code <= 0x06F9) {
            return String(code - 0x06F0);
        }
        if (SEATS[ch]) {
            return SEATS[ch];
        }
        var bare = ch.normalize("NFD").replace(LATIN_MARK, "");
        if (!bare) {
            return "";
        }
        bare = bare.toLowerCase();
        return NOT_WORD.test(bare.charAt(0)) ? " " : bare;
    }

    /* The folded text, and for each folded character the index it came from. Runs of
       separators collapse to one space, and leading space is dropped, exactly as the
       needle is folded, so the two can be compared with indexOf. */
    function fold(text) {
        var out = "", at = [], i, piece, j;
        for (i = 0; i < text.length; i++) {
            piece = foldChar(text.charAt(i));
            if (piece === " ") {
                if (!out || out.charAt(out.length - 1) === " ") {
                    continue;
                }
                out += " ";
                at.push(i);
                continue;
            }
            for (j = 0; j < piece.length; j++) {
                out += piece.charAt(j);
                at.push(i);
            }
        }
        while (out.charAt(out.length - 1) === " ") {
            out = out.slice(0, -1);
            at.pop();
        }
        return { text: out, at: at };
    }

    function escape(raw) {
        return raw.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
    }

    /* The parts of a row that are matched and marked: its title, and its number where it
       has one. Never its hadith count — matching the whole row made "12" answer every
       chapter that happens to hold twelve narrations. */
    function fields(row) {
        var nodes = row.querySelectorAll(
            ".hub-chapter__title, .hub-chapter__index, .hub-card__title");
        if (!nodes.length) {
            nodes = [row];
        }
        return Array.prototype.map.call(nodes, function (node) {
            var raw = node.textContent;
            var folded = fold(raw);
            return { node: node, raw: raw, folded: folded.text, at: folded.at };
        });
    }

    function mark(field, needle) {
        var hit = needle ? field.folded.indexOf(needle) : -1;
        if (hit < 0) {
            if (field.node.firstElementChild || field.node.textContent !== field.raw) {
                field.node.textContent = field.raw;
            }
            return false;
        }
        var from = field.at[hit];
        var to = field.at[hit + needle.length - 1] + 1;
        field.node.innerHTML = escape(field.raw.slice(0, from))
            + '<mark class="hub-filter__hit">' + escape(field.raw.slice(from, to))
            + "</mark>" + escape(field.raw.slice(to));
        return true;
    }

    function attach(input) {
        var group = input.closest(".hub-group");
        if (!group) {
            return;
        }
        var list = group.querySelector(".hub-chapters, .hub-grid");
        if (!list) {
            return;
        }
        var rows = Array.prototype.map.call(list.children, function (row) {
            return { node: row, fields: fields(row) };
        });
        var status = group.querySelector("[data-hub-filter-count]");
        var empty = document.createElement("p");
        empty.className = "hub-filter__empty";
        empty.hidden = true;
        list.parentNode.insertBefore(empty, list.nextSibling);

        function run() {
            var needle = fold(input.value).text;
            var shown = 0;
            rows.forEach(function (row) {
                var hit = !needle;
                row.fields.forEach(function (field) {
                    if (mark(field, needle)) {
                        hit = true;
                    }
                });
                row.node.hidden = !hit;
                if (hit) {
                    shown++;
                }
            });
            if (status) {
                status.textContent = needle
                    ? t("hub.filter.showing", "{0} of {1}")
                        .replace("{0}", digits(shown)).replace("{1}", digits(rows.length))
                    : "";
            }
            empty.hidden = !(needle && shown === 0);
            if (!empty.hidden) {
                empty.textContent = t("hub.filter.none", "Nothing here matches “{0}”.")
                    .replace("{0}", input.value.trim());
            }
        }

        input.addEventListener("input", run);
        // A search input clears from its own control, which fires `search`, not `input`.
        input.addEventListener("search", run);
        if (input.value) {
            run();
        }
    }

    function start() {
        Array.prototype.forEach.call(
            document.querySelectorAll("[data-hub-filter]"), attach);
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", start);
    } else {
        start();
    }
}());
