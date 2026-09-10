/*
 * Folds long narrations on the server-rendered chapter pages.
 *
 * The search page does this in Vue, through isNarrationCollapsible and a clamp class on
 * .hadith-reading-scroll. The chapter pages render the same card from the Thymeleaf
 * fragment, which has the same structure but ran no script at all, so a chapter like
 * Kāmil al-Ziyārāt's ziyarah of the Commander of the Believers listed 45 narrations at
 * full length - the longest of them nearly thirteen thousand characters - with nothing to
 * fold them.
 *
 * This adds only the clamp and the control. The markup and every class it uses already
 * exist and are already styled; nothing here is a second implementation of the card.
 *
 * Progressive enhancement on purpose: with no script the page is long but whole, which is
 * what the crawlable pages are for. Nothing is hidden from a reader who cannot run this.
 */
(function () {
    'use strict';

    // The same threshold the search page uses, so a narration that folds in one place
    // folds in the other.
    var COLLAPSE_THRESHOLD = 700;

    function textLength(el) {
        if (!el) {
            return 0;
        }
        return (el.textContent || '').trim().length;
    }

    function longest(scroll) {
        var most = 0;
        var blocks = scroll.querySelectorAll('.hadith-english, .hadith-arabic');
        for (var i = 0; i < blocks.length; i++) {
            most = Math.max(most, textLength(blocks[i]));
        }
        return most;
    }

    function button(expanded) {
        var b = document.createElement('button');
        b.type = 'button';
        b.className = 'text-toggle';
        b.setAttribute('aria-expanded', expanded ? 'true' : 'false');
        var span = document.createElement('span');
        span.textContent = expanded ? 'Show less' : 'Show more';
        var icon = document.createElement('i');
        icon.className = 'fa ' + (expanded ? 'fa-chevron-up' : 'fa-chevron-down');
        icon.setAttribute('aria-hidden', 'true');
        b.appendChild(span);
        b.appendChild(icon);
        return b;
    }

    function attach(scroll) {
        if (longest(scroll) <= COLLAPSE_THRESHOLD) {
            return;
        }
        scroll.classList.add('hadith-reading-scroll--clamped');

        var foot = document.createElement('div');
        foot.className = 'hadith-reading-foot';
        var b = button(false);
        foot.appendChild(b);
        scroll.parentNode.insertBefore(foot, scroll.nextSibling);

        b.addEventListener('click', function () {
            var expanding = scroll.classList.contains('hadith-reading-scroll--clamped');
            // Collapsing from below the fold would otherwise leave the reader somewhere
            // further down the page than the narration they just closed. Hold the control
            // where it is on screen, as the search card does: it sits under the text, so
            // it is what moves. The text's own top edge never moves on a collapse, and
            // holding that corrected by nothing - a reader who had read to the end was
            // left thousands of pixels past the narration.
            var before = expanding ? null : b.getBoundingClientRect().top;

            scroll.classList.toggle('hadith-reading-scroll--clamped', !expanding);
            b.setAttribute('aria-expanded', expanding ? 'true' : 'false');
            b.querySelector('span').textContent = expanding ? 'Show less' : 'Show more';
            b.querySelector('i').className =
                'fa ' + (expanding ? 'fa-chevron-up' : 'fa-chevron-down');

            if (before !== null) {
                window.scrollBy(0, b.getBoundingClientRect().top - before);
            }
        });
    }

    function start() {
        var scrolls = document.querySelectorAll('.hadith-card .hadith-reading-scroll');
        for (var i = 0; i < scrolls.length; i++) {
            attach(scrolls[i]);
        }
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', start);
    } else {
        start();
    }
})();
