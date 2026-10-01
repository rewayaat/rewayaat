/*
 * The header's menu on a phone.
 *
 * Every page has one of these and they all behave the same, so it is one script
 * rather than a copy in rewayaat.js and another in hub-pages.js — which is how the
 * header ended up with four hand-rolled versions of itself in the first place.
 *
 * It has no idea whether anyone is signed in. The sign-in item is shown or hidden by
 * a class on <body> that the two auth scripts already set, so this file never has to
 * wait for /v1/auth/me and never disagrees with the chip in the corner about who is
 * logged in.
 */
(function () {
    'use strict';

    function panelOf(menu) {
        return menu.querySelector('[data-nav-menu-panel]');
    }

    function close(menu) {
        var panel = panelOf(menu);
        var toggle = menu.querySelector('[data-nav-menu-toggle]');
        if (panel) { panel.hidden = true; }
        if (toggle) { toggle.setAttribute('aria-expanded', 'false'); }
        menu.classList.remove('is-open');
    }

    function closeAll(except) {
        document.querySelectorAll('[data-nav-menu]').forEach(function (menu) {
            if (menu !== except) { close(menu); }
        });
    }

    document.addEventListener('click', function (event) {
        var toggle = event.target.closest('[data-nav-menu-toggle]');
        if (toggle) {
            event.preventDefault();
            var menu = toggle.closest('[data-nav-menu]');
            var panel = panelOf(menu);
            if (!panel) { return; }
            var opening = panel.hidden;
            closeAll(menu);
            panel.hidden = !opening;
            toggle.setAttribute('aria-expanded', opening ? 'true' : 'false');
            menu.classList.toggle('is-open', opening);
            return;
        }
        // A click on an item is a navigation; anything else outside closes.
        if (!event.target.closest('[data-nav-menu-panel]')) {
            closeAll(null);
        }
    });

    document.addEventListener('keydown', function (event) {
        if (event.key === 'Escape') { closeAll(null); }
    });

    // The menu is positioned under its button. A resize past the breakpoint that
    // hides the button would otherwise leave an orphaned panel open on the page.
    window.addEventListener('resize', function () { closeAll(null); });
}());
