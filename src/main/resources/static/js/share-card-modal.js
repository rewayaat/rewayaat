/*
 * The share-card preview.
 *
 * Copying an image you have never seen is a strange thing to ask of anyone, and it was
 * the only thing the share menu offered: a toast said "Dark image copied" and you found
 * out what you had copied by pasting it somewhere. This shows the card first.
 *
 * It lives in its own file because both cards need it and they are rendered by different
 * machinery — Thymeleaf on the book and narration pages, Vue in the search app. Anything
 * kept in one of those two ends up in only one of them, which is the drift
 * HadithCardParityTest exists to catch.
 */
(function () {
    'use strict';

    var lengthCheck = 0;
    var chainCheck = 0;
    var state = {
        id: null, label: '', root: null, lastFocus: null,
        theme: 'dark',      // dark | light
        lang: 'both',       // both | ar | en
        full: false,        // false trims to the Open Graph ratio, true fits the whole text
        chain: false        // the isnad in front of the matn, off because it is long and
                            // near-identical across thousands of narrations
    };

    // Each control is a named set of choices, so adding one is a row here rather than a
    // new branch in the URL builder and the render loop.
    var CONTROLS = [
        {key: 'theme', label: 'Theme', options: [['dark', 'Dark'], ['light', 'Light']]},
        {key: 'lang', label: 'Text', options: [['both', 'Both'], ['ar', 'Arabic'], ['en', 'English']]},
        {key: 'full', label: 'Length',
         options: [[false, 'Trimmed'], [true, 'Full']]},
        {key: 'chain', label: 'Chain',
         options: [[false, 'Matn only'], [true, 'With isnād']]}
    ];

    function cardUrl() {
        // A colon is legal in a path segment, and the og:image tag emits it raw. Encoding
        // it here would hand the newsletter a different address for the same image.
        var url = '/hadith/' + encodeURIComponent(state.id).replace(/%3A/g, ':') + '/card.png';
        var query = [];
        if (state.theme === 'light') { query.push('theme=light'); }
        if (state.lang !== 'both') { query.push('lang=' + state.lang); }
        if (state.full) { query.push('full=true'); }
        if (state.chain) { query.push('chain=true'); }
        return url + (query.length ? '?' + query.join('&') : '');
    }

    function absolute(url) {
        return window.location.origin + url;
    }

    /* ── Feedback ───────────────────────────────────────────────────────────── */

    function say(message, isError) {
        // Reuse whichever toast the host page already has, so the message looks native.
        if (window.hubToast) { window.hubToast(message, isError ? 'error' : 'success'); return; }
        if (window.showToast) { window.showToast(message, isError ? 'error' : 'success'); return; }
        var note = state.root && state.root.querySelector('[data-share-note]');
        if (!note) { return; }
        note.textContent = message;
        note.classList.toggle('is-error', !!isError);
        note.classList.add('is-visible');
        window.clearTimeout(note._timer);
        note._timer = window.setTimeout(function () { note.classList.remove('is-visible'); }, 3200);
    }

    /* ── The three things you can do with a card ────────────────────────────── */

    function copyImage() {
        var url = cardUrl();
        if (!window.ClipboardItem || !navigator.clipboard || !navigator.clipboard.write) {
            say('This browser cannot copy images; use Download instead.', true);
            return;
        }
        // Safari needs the ClipboardItem constructed synchronously with a promise inside,
        // or the write is rejected as being outside a user gesture.
        try {
            var item = new window.ClipboardItem({
                'image/png': fetch(url).then(function (r) {
                    if (!r.ok) { throw new Error('card request failed'); }
                    return r.blob();
                })
            });
            navigator.clipboard.write([item])
                .then(function () { say('Image copied.'); })
                .catch(function () { say('Could not copy the image.', true); });
        } catch (e) {
            fetch(url)
                .then(function (r) { return r.blob(); })
                .then(function (blob) {
                    var payload = {};
                    payload[blob.type || 'image/png'] = blob;
                    return navigator.clipboard.write([new window.ClipboardItem(payload)]);
                })
                .then(function () { say('Image copied.'); })
                .catch(function () { say('Could not copy the image.', true); });
        }
    }

    function copyImageUrl() {
        var url = absolute(cardUrl());
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(url)
                .then(function () { say('Image address copied.'); })
                .catch(function () { say('Could not copy.', true); });
            return;
        }
        var ta = document.createElement('textarea');
        ta.value = url;
        ta.style.cssText = 'position:fixed;left:-9999px';
        document.body.appendChild(ta);
        ta.select();
        try { document.execCommand('copy'); say('Image address copied.'); }
        catch (e) { say('Could not copy.', true); }
        document.body.removeChild(ta);
    }

    function download() {
        var a = document.createElement('a');
        a.href = cardUrl();
        a.download = String(state.id).replace(/[^A-Za-z0-9._-]+/g, '-')
            + '-' + state.theme + '-' + state.lang + (state.full ? '-full' : '')
            + (state.chain ? '-isnad' : '') + '.png';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
    }

    /* ── The dialog ─────────────────────────────────────────────────────────── */

    function refresh() {
        var img = state.root.querySelector('[data-share-image]');
        var frame = state.root.querySelector('[data-share-frame]');
        var url = cardUrl();
        frame.classList.add('is-loading');
        // A full card is far taller than 1200x630, so the fixed-ratio window that keeps
        // the dialog from jumping would shrink it to a stripe. Let it scroll instead.
        frame.classList.toggle('is-full', state.full);
        img.src = url;
        state.root.querySelectorAll('[data-share-choice]').forEach(function (btn) {
            var on = String(state[btn.getAttribute('data-share-control')])
                     === btn.getAttribute('data-share-choice');
            btn.classList.toggle('is-active', on);
            btn.setAttribute('aria-pressed', on ? 'true' : 'false');
        });
        state.root.querySelector('[data-share-address]').value = absolute(url);
        updateLengthControl(url);
        updateChainControl(url);
    }

    /**
     * Trimmed and Full are the same image for most narrations — they are short enough to
     * fit either way — and a control that changes nothing is worse than no control. The
     * server says which case this is, per language, in X-Card-Trimmed.
     */
    function updateLengthControl(url) {
        var group = state.root.querySelector('[data-share-group="full"]');
        if (!group) { return; }
        var token = ++lengthCheck;
        fetch(url, {method: 'HEAD'})
            .then(function (response) {
                // A stale answer must not decide the current one: the reader may have
                // switched language while this was in flight.
                if (token !== lengthCheck || !state.root) { return; }
                var trimmed = response.headers.get('X-Card-Trimmed') === 'true';
                group.hidden = !trimmed;
                if (!trimmed && state.full) {
                    state.full = false;
                    refresh();
                }
            })
            .catch(function () { /* leave the control as it is */ });
    }

    /**
     * The isnād is separated from the matn per language, and that detection does not
     * always succeed on both sides. Where only the Arabic chain was found, turning the
     * toggle on changes the Arabic and leaves the English untouched - and on an
     * English-only card it does nothing at all, which reads as a broken switch. The
     * server reports what it managed to separate for the current language in
     * X-Card-Chain, and the control is offered only when it will visibly do something.
     */
    function updateChainControl(url) {
        var group = state.root.querySelector('[data-share-group="chain"]');
        if (!group) { return; }
        var token = ++chainCheck;
        fetch(url, {method: 'HEAD'})
            .then(function (response) {
                if (token !== chainCheck || !state.root) { return; }
                var available = response.headers.get('X-Card-Chain') === 'true';
                group.hidden = !available;
                if (!available && state.chain) {
                    state.chain = false;
                    refresh();
                }
            })
            .catch(function () { /* leave the control as it is */ });
    }

    function close() {
        if (!state.root) { return; }
        document.removeEventListener('keydown', onKeydown, true);
        state.root.remove();
        state.root = null;
        if (state.lastFocus && state.lastFocus.focus) { state.lastFocus.focus(); }
    }

    function onKeydown(event) {
        if (event.key === 'Escape') { event.stopPropagation(); close(); return; }
        if (event.key !== 'Tab' || !state.root) { return; }
        var focusable = state.root.querySelectorAll('button, [href], input, [tabindex]:not([tabindex="-1"])');
        if (!focusable.length) { return; }
        var first = focusable[0], last = focusable[focusable.length - 1];
        if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus(); }
        else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus(); }
    }

    function controlsMarkup() {
        return '<div class="share-card-modal__controls">' + CONTROLS.map(function (control) {
            return '<div class="share-card-modal__control" data-share-group="' + control.key + '">'
                + '<span class="share-card-modal__control-label" id="share-ctl-' + control.key + '">'
                + control.label + '</span>'
                + '<div class="share-card-modal__segmented" role="group" '
                + 'aria-labelledby="share-ctl-' + control.key + '">'
                + control.options.map(function (option) {
                    return '<button type="button" class="share-card-modal__seg" '
                        + 'data-share-control="' + control.key + '" '
                        + 'data-share-choice="' + option[0] + '" '
                        + 'aria-pressed="false">' + option[1] + '</button>';
                  }).join('')
                + '</div></div>';
        }).join('') + '</div>';
    }

    function build() {
        var root = document.createElement('div');
        root.className = 'share-card-modal';
        root.setAttribute('role', 'dialog');
        root.setAttribute('aria-modal', 'true');
        root.setAttribute('aria-label', 'Share this narration as an image');
        root.innerHTML =
            '<div class="share-card-modal__backdrop" data-share-close></div>' +
            '<div class="share-card-modal__panel">' +
              '<div class="share-card-modal__head">' +
                '<div>' +
                  '<div class="share-card-modal__eyebrow">Share as image</div>' +
                  '<div class="share-card-modal__title" data-share-label></div>' +
                '</div>' +
                '<button type="button" class="share-card-modal__close" data-share-close ' +
                        'aria-label="Close">&times;</button>' +
              '</div>' +
              '<div class="share-card-modal__frame" data-share-frame>' +
                '<img alt="Preview of the share card for this narration" data-share-image/>' +
              '</div>' +
              controlsMarkup() +
              '<label class="share-card-modal__address">' +
                '<span>Image address</span>' +
                '<input type="text" readonly data-share-address ' +
                       'aria-label="Direct address of this image"/>' +
              '</label>' +
              '<p class="share-card-modal__hint">Paste the address into an email template as ' +
                 'an image source; copy or download the file to drop it straight into a message.</p>' +
              '<div class="share-card-modal__actions">' +
                '<button type="button" class="share-card-modal__btn" data-share-copy-url>' +
                  '<i class="fa fa-link" aria-hidden="true"></i> Copy address</button>' +
                '<button type="button" class="share-card-modal__btn" data-share-download>' +
                  '<i class="fa fa-download" aria-hidden="true"></i> Download</button>' +
                '<button type="button" class="share-card-modal__btn share-card-modal__btn--primary" ' +
                        'data-share-copy>' +
                  '<i class="fa fa-copy" aria-hidden="true"></i> Copy image</button>' +
              '</div>' +
              '<div class="share-card-modal__note" data-share-note role="status" aria-live="polite"></div>' +
            '</div>';

        root.addEventListener('click', function (event) {
            if (event.target.closest('[data-share-close]')) { close(); return; }
            var choice = event.target.closest('[data-share-choice]');
            if (choice) {
                var key = choice.getAttribute('data-share-control');
                var value = choice.getAttribute('data-share-choice');
                state[key] = value === 'true' ? true : (value === 'false' ? false : value);
                refresh();
                return;
            }
            if (event.target.closest('[data-share-copy]')) { copyImage(); return; }
            if (event.target.closest('[data-share-copy-url]')) { copyImageUrl(); return; }
            if (event.target.closest('[data-share-download]')) { download(); }
        });

        var img = root.querySelector('[data-share-image]');
        var frame = root.querySelector('[data-share-frame]');
        img.addEventListener('load', function () { frame.classList.remove('is-loading'); });
        img.addEventListener('error', function () {
            frame.classList.remove('is-loading');
            say('Could not render this card.', true);
        });
        root.querySelector('[data-share-address]').addEventListener('focus', function () {
            this.select();
        });
        return root;
    }

    function open(id, label) {
        if (!id) { return; }
        close();
        state.id = id;
        state.label = label || '';
        state.lastFocus = document.activeElement;
        state.root = build();
        document.body.appendChild(state.root);
        state.root.querySelector('[data-share-label]').textContent = state.label;
        state.theme = 'dark';
        state.lang = 'both';
        state.full = false;
        state.chain = false;
        refresh();
        document.addEventListener('keydown', onKeydown, true);
        var first = state.root.querySelector('[data-share-copy]');
        if (first) { first.focus(); }
    }

    window.HadithShareCard = { open: open, close: close };
}());
