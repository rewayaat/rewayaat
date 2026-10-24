/*
 * A phone shows the first four narrator cards and a button for the rest.
 *
 * The home page serves twelve, which is a reasonable first screen on a desktop and
 * three full screens of scrolling on a phone before the next section begins. The
 * bookcase already solves this for books (see populateBrowseBooks in rewayaat.js);
 * this is the same bargain for the men, and the same two message keys' shape.
 *
 * Server-rendered cards, so nothing is built here: the overflow is hidden in CSS and
 * this only adds the control and flips one class. With scripts off every card shows,
 * which is the right failure.
 */
(function () {
    "use strict";

    var MOBILE_LIMIT = 4;

    function t(key, fallback) {
        return (window.I18N || {})[key] || fallback;
    }

    function digits(n) {
        return (window.I18N_LOCALE === "ar") ? Number(n).toLocaleString("ar-EG") : String(n);
    }

    function fit(list) {
        var cards = list.querySelectorAll(".ncard");
        if (cards.length <= MOBILE_LIMIT || list.querySelector(".narrator-drawer-toggle")) {
            return;
        }
        var hidden = cards.length - MOBILE_LIMIT;
        var toggle = document.createElement("button");
        toggle.className = "narrator-drawer-toggle";
        toggle.type = "button";
        toggle.setAttribute("aria-expanded", "false");

        function label(expanded) {
            toggle.textContent = expanded
                ? t("js.showFewerNarrators", "Show fewer narrators")
                : t("js.showAllNarrators", "Show {0} more").replace("{0}", digits(hidden));
        }

        label(false);
        toggle.addEventListener("click", function () {
            var expanded = list.classList.toggle("is-expanded");
            toggle.setAttribute("aria-expanded", expanded ? "true" : "false");
            label(expanded);
        });
        list.appendChild(toggle);
    }

    function start() {
        var lists = document.querySelectorAll(".browse-narrator-list");
        for (var i = 0; i < lists.length; i++) {
            fit(lists[i]);
        }
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", start);
    } else {
        start();
    }
}());
