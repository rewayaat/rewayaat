/*
 * The site-wide announcement bar.
 *
 * Content lives in /announcement.json rather than in markup, because the bar appears on
 * every page and the pages do not share a layout: index and hadith are Thymeleaf templates,
 * books/book/chapter/volume share a fragment, and updates is static HTML. Editing an
 * announcement should not mean editing five files and missing one.
 *
 * Dismissal is remembered per announcement `id`, so changing the id shows the bar again to
 * everyone who dismissed the previous one - which is what you want when the announcement is
 * genuinely new, and not what you want if you only fixed a typo.
 */
(function () {
    'use strict';

    var STORAGE_PREFIX = 'rewayaat.announcement.dismissed.';

    function dismissed(id) {
        try {
            return window.localStorage.getItem(STORAGE_PREFIX + id) === '1';
        } catch (e) {
            // Private browsing and blocked site data both throw here. A reader who cannot
            // store a dismissal should still see the announcement, not an error.
            return false;
        }
    }

    function remember(id) {
        try {
            window.localStorage.setItem(STORAGE_PREFIX + id, '1');
        } catch (e) {
            /* Nothing to do: the bar closes for this page view either way. */
        }
    }

    function element(tag, className, text) {
        var node = document.createElement(tag);
        if (className) { node.className = className; }
        if (text) { node.textContent = text; }
        return node;
    }

    function render(data) {
        var bar = element('div', 'site-announcement');
        bar.setAttribute('role', 'status');

        var inner = element('div', 'site-announcement__inner');

        if (data.icon) {
            var icon = element('i', 'fa ' + data.icon + ' site-announcement__icon');
            icon.setAttribute('aria-hidden', 'true');
            inner.appendChild(icon);
        }

        inner.appendChild(element('span', 'site-announcement__text', data.text));

        // Both links are optional, and the video one is expected to arrive after the
        // announcement itself: leaving videoUrl empty simply omits it, so publishing the
        // recording later is a one-line edit to the JSON rather than a code change.
        if (data.linkUrl && data.linkText) {
            var link = element('a', 'site-announcement__link', data.linkText);
            link.href = data.linkUrl;
            inner.appendChild(link);
        }
        if (data.videoUrl && data.videoText) {
            var video = element('a', 'site-announcement__link', data.videoText);
            video.href = data.videoUrl;
            video.target = '_blank';
            video.rel = 'noopener noreferrer';
            inner.appendChild(video);
        }

        bar.appendChild(inner);

        var close = element('button', 'site-announcement__close');
        close.type = 'button';
        close.setAttribute('aria-label', 'Dismiss announcement');
        close.innerHTML = '&times;';
        close.addEventListener('click', function () {
            remember(data.id);
            bar.parentNode.removeChild(bar);
        });
        bar.appendChild(close);

        // Above everything, including the sticky nav, so it reads as chrome rather than as
        // content that happens to be first.
        document.body.insertBefore(bar, document.body.firstChild);
    }

    function start() {
        fetch('/announcement.json', { cache: 'no-cache' })
            .then(function (response) { return response.ok ? response.json() : null; })
            .then(function (data) {
                if (!data || data.active !== true || !data.text || !data.id) { return; }
                if (dismissed(data.id)) { return; }
                render(data);
            })
            .catch(function () {
                /* An announcement is not worth a console error on every page. */
            });
    }

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', start);
    } else {
        start();
    }
}());
