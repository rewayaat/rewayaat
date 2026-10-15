/*
 * The chronology band: drawn when it is scrolled to, and readable by pointer or keyboard.
 *
 * The band is rendered whole by the server, so none of its geometry is computed here — this
 * only starts the drawing and carries the reading of a panel. Without the file the band is
 * still correct and still legible; it simply stands still and falls back to the browser's own
 * tooltip. Nothing below may change what the band says.
 */
(function () {
    "use strict";

    var still = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;

    function draw(band) {
        if (still || !("IntersectionObserver" in window)) {
            return;
        }
        // Only now does the band agree to start hidden. Until this class is on it, the CSS
        // leaves it finished, so a script that never runs costs the reader nothing.
        band.classList.add("is-ready");
        var watcher = new IntersectionObserver(function (entries) {
            entries.forEach(function (entry) {
                if (!entry.isIntersecting) {
                    return;
                }
                // A rank for each panel, so the wipe runs along the band in time order
                // rather than all at once. The server numbers them; this only starts it.
                entry.target.classList.add("is-drawn");
                watcher.unobserve(entry.target);
            });
        }, { threshold: 0.25 });
        watcher.observe(band);
    }

    function reading(cell) {
        var text = cell.getAttribute("data-era") || cell.getAttribute("title") || "";
        if (cell.getAttribute("data-on") === "true") {
            var lived = cell.getAttribute("data-lived");
            if (lived) {
                text = text + " · " + lived;
            }
        }
        return text;
    }

    function speak(band) {
        var tip = document.createElement("span");
        tip.className = "chrono__tip";
        tip.setAttribute("role", "status");
        band.appendChild(tip);

        var plot = band.querySelector(".chrono__plot") || band;

        function show(cell) {
            tip.textContent = reading(cell);
            // Placed over the middle of its own panel, then pulled back inside the band at
            // either end: a tooltip that overhangs the page is worse than one that is offset.
            var box = cell.getBoundingClientRect();
            var frame = plot.getBoundingClientRect();
            var middle = box.left + box.width / 2 - frame.left;
            tip.style.insetInlineStart = "0";
            tip.style.left = Math.max(0, Math.min(frame.width, middle)) + "px";
            band.classList.add("is-telling");
        }

        function hide() {
            band.classList.remove("is-telling");
        }

        band.querySelectorAll(".chrono__cell").forEach(function (cell) {
            cell.addEventListener("mouseenter", function () { show(cell); });
            cell.addEventListener("focus", function () { show(cell); });
            cell.addEventListener("mouseleave", hide);
            cell.addEventListener("blur", hide);
            // The browser's own tooltip would open on top of ours a second later.
            cell.removeAttribute("title");
        });
        band.addEventListener("mouseleave", hide);
    }

    function start() {
        document.querySelectorAll("[data-chrono]").forEach(function (band) {
            draw(band);
            speak(band);
        });
    }

    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", start);
    } else {
        start();
    }
}());
