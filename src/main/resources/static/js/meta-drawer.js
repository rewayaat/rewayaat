/*
 * On a phone, the server-rendered card shows two metadata rows and a button for the rest.
 *
 * The search page's card already does this — see `visibleMetadataItems` in
 * vue-components.js, which slices the list to two under 768px. The Thymeleaf card is the
 * same card by the sync contract, but it had no script, so a chapter page put seven rows
 * of dark panel (hadith number, book, volume, section, part, chapter, source) between the
 * reader and every narration's text.
 *
 * The rows are rendered by the server and merely hidden here, so a crawler and a reader
 * without scripts get all of them, which is the right failure. Only the static card is
 * touched: Vue owns its own and would end up with two buttons.
 */
(function () {
    "use strict";

    var SHOWN = 2;
    var PHONE = "(max-width: 768px)";

    function t(key, fallback) {
        return (window.I18N || {})[key] || fallback;
    }

    function drawer(details) {
        var items = details.querySelectorAll(".meta-item");
        if (items.length <= SHOWN) {
            return null;
        }
        var toggle = document.createElement("button");
        toggle.type = "button";
        toggle.className = "hadith-details__mobile-toggle";
        var word = document.createElement("span");
        var caret = document.createElement("i");
        caret.setAttribute("aria-hidden", "true");
        toggle.appendChild(word);
        toggle.appendChild(caret);
        details.appendChild(toggle);

        var open = false;

        function draw(phone) {
            var i;
            for (i = 0; i < items.length; i++) {
                items[i].hidden = phone && !open && i >= SHOWN;
            }
            toggle.hidden = !phone;
            toggle.setAttribute("aria-expanded", open ? "true" : "false");
            word.textContent = open
                ? t("js.showFewerDetails", "Show fewer details")
                : t("js.showAllDetails", "Show all details");
            caret.className = open ? "fa fa-angle-up" : "fa fa-angle-down";
        }

        toggle.addEventListener("click", function () {
            open = !open;
            draw(true);
        });
        return draw;
    }

    function start() {
        var found = document.querySelectorAll(
            ".hadith-card--static .hadith-details:not([data-meta-drawer])");
        if (!found.length) {
            return;
        }
        var query = window.matchMedia(PHONE);
        var draws = [];
        Array.prototype.forEach.call(found, function (details) {
            details.dataset.metaDrawer = "1";
            var draw = drawer(details);
            if (draw) {
                draws.push(draw);
                draw(query.matches);
            }
        });
        if (!draws.length) {
            return;
        }
        // One listener for the page, not one per card: a chapter lists forty of these.
        var onChange = function (event) {
            draws.forEach(function (draw) { draw(event.matches); });
        };
        if (query.addEventListener) {
            query.addEventListener("change", onChange);
        } else {
            query.addListener(onChange);
        }
    }

    // The narrator tabs swap the page's main region in place, so the cards that arrive
    // that way need their drawers too.
    document.addEventListener("rewayaat:view-swapped", start);

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", start);
    } else {
        start();
    }
}());
