/*
 * Switching tabs on a narrator's page without fetching his page again.
 *
 * The tabs are ordinary links to ordinary URLs — «?tab=books» — and they must stay that
 * way: a crawler follows them, a reader shares them, and a reader without script needs
 * them to work. But the whole page was being rebuilt to change the half of it below the
 * tab strip, so his name, his standing, the ledger and the chronology were thrown away and
 * drawn again on every click, and the page flashed white in between.
 *
 * So the link is still a link, and this only shortens the journey: fetch the page the link
 * points at, take the one region the tabs switch, put it in place of the current one, and
 * tell the history where we are. Anything that goes wrong — a slow network, a 500, a
 * response that does not parse — falls back to letting the browser follow the link itself.
 */
(function () {
    "use strict";

    var REGION = "narrator-view";
    var region = document.getElementById(REGION);
    if (!region) {
        return;
    }

    function tabs() {
        return Array.prototype.slice.call(document.querySelectorAll(".narrator-tab"));
    }

    /* Scripts that bind to elements rather than to the document — the fold on a long
       entry, for one — have to be told that the elements under them are new. */
    function announce() {
        document.dispatchEvent(new CustomEvent("rewayaat:view-swapped",
                                               {detail: {root: region}}));
    }

    function mark(url) {
        var here = url.split("#")[0];
        tabs().forEach(function (tab) {
            var mine = (tab.getAttribute("href") || "").split("#")[0];
            tab.classList.toggle("is-active", mine === here);
        });
    }

    function swap(html, url) {
        var parsed = new DOMParser().parseFromString(html, "text/html");
        var incoming = parsed.getElementById(REGION);
        if (!incoming) {
            return false;
        }
        region.innerHTML = incoming.innerHTML;
        if (parsed.title) {
            document.title = parsed.title;
        }
        mark(url);
        announce();
        return true;
    }

    var inFlight = null;

    function go(url, push) {
        if (inFlight) {
            inFlight.abort();
        }
        var controller = new AbortController();
        inFlight = controller;
        region.classList.add("is-loading");
        fetch(url, {signal: controller.signal, headers: {"X-Requested-With": "fetch"}})
            .then(function (response) {
                if (!response.ok) {
                    throw new Error(String(response.status));
                }
                return response.text();
            })
            .then(function (html) {
                inFlight = null;
                region.classList.remove("is-loading");
                if (!swap(html, url)) {
                    window.location.href = url;
                    return;
                }
                if (push) {
                    window.history.pushState({narratorView: url}, "", url);
                }
                // The reader asked for a different reading of the same man, so put him at
                // the top of it rather than wherever he happened to be scrolled to.
                var strip = document.querySelector(".narrator-tabs");
                if (strip && strip.getBoundingClientRect().top < 0) {
                    strip.scrollIntoView({block: "start", behavior: "smooth"});
                }
            })
            .catch(function (error) {
                if (error && error.name === "AbortError") {
                    return;
                }
                inFlight = null;
                region.classList.remove("is-loading");
                window.location.href = url;
            });
    }

    document.addEventListener("click", function (event) {
        var tab = event.target.closest && event.target.closest(".narrator-tab");
        if (!tab || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) {
            return;
        }
        var url = tab.getAttribute("href");
        if (!url || url.charAt(0) !== "/") {
            return;
        }
        event.preventDefault();
        if (tab.classList.contains("is-active")) {
            return;
        }
        go(url, true);
    });

    // Back and forward move between tabs the same way they would have if each had been a
    // page load, because as far as the history is concerned each of them was one. The entry
    // the reader arrived on carries no state of its own, so it is given one here: without
    // it, going back from the second tab changed the URL and left the first tab unopened.
    window.history.replaceState({narratorView: window.location.pathname
                                 + window.location.search}, "");

    window.addEventListener("popstate", function (event) {
        if (event.state && event.state.narratorView) {
            go(event.state.narratorView, false);
        }
    });
}());
