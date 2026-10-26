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

    /*
     * The cue that points at the site search. The narrators page has no field of its
     * own, so the control here hands the reader the real one: scroll it into view,
     * focus it, and leave whatever they had typed alone.
     */
    function wireCue() {
        var buttons = document.querySelectorAll("[data-focus-site-search]");
        for (var i = 0; i < buttons.length; i++) {
            buttons[i].addEventListener("click", function () {
                // nav-search.js upgrades the plain input to a Tom Select control,
                // which replaces it: after that [data-nav-search-input] matches a
                // hidden <select> that cannot take focus, and on the home page there
                // is no nav field at all, only the hero's. Take whichever is really
                // there, preferring the upgraded control's own text box.
                var field = document.querySelector(".nav-search .ts-control input")
                    || document.querySelector(".ts-control input")
                    || document.querySelector("[data-nav-search-input]:not(.tomselected)")
                    || document.querySelector("[data-nav-search-input]");
                if (!field) {
                    return;
                }
                window.scrollTo({ top: 0, behavior: "smooth" });
                // After the scroll, or the focus drags the page back itself on some
                // browsers and the smooth scroll is never seen.
                window.setTimeout(function () {
                    field.focus();
                    if (typeof field.select === "function") {
                        field.select();
                    }
                }, 220);
            });
        }
    }

    function start() {
        var lists = document.querySelectorAll(".browse-narrator-list");
        for (var i = 0; i < lists.length; i++) {
            fit(lists[i]);
        }
        wireCue();
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", start);
    } else {
        start();
    }
}());
