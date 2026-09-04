/**
 * Auth and save-to-collection for the server-rendered pages (books, book, volume,
 * chapter, hadith).
 *
 * These pages deliberately do not load rewayaat.js. That bundle is 274KB and boots the
 * home page — it runs loadQuery() on ready, and expects Vue, tom-select, swal and the
 * search DOM to exist. Pulling it in here would cost the crawl budget these pages were
 * built to earn and would run a search app on a static document.
 *
 * So this talks to the same endpoints, reuses the same element ids and CSS classes as
 * the home page nav, and stays small: a reader signed in on the search page stays
 * signed in here, sees the same chip in the same place, and saves to the same
 * collections.
 */
(function () {
    'use strict';

    var authState = { authenticated: false, user: null };

    function apiJSON(url, options) {
        var opts = options || {};
        opts.credentials = 'same-origin';
        if (opts.body && !opts.headers) {
            opts.headers = { 'Content-Type': 'application/json' };
        }
        return fetch(url, opts).then(function (resp) {
            return resp.json()
                .catch(function () { return {}; })
                .then(function (data) { return { ok: resp.ok, status: resp.status, data: data }; });
        });
    }

    function el(id) { return document.getElementById(id); }

    /* ── Nav ────────────────────────────────────────────────────────────────── */

    function applyAuthState() {
        var authed = !!authState.authenticated;
        var signIn = el('authSignInBtn');
        var shell = el('authProfileShell');
        var name = el('authProfileName');
        var initial = el('authProfileInitial');

        if (signIn) { signIn.classList.toggle('d-none', authed); }
        if (shell) { shell.classList.toggle('d-none', !authed); }

        if (authed && authState.user) {
            var label = authState.user.name || authState.user.email || 'Account';
            if (name) { name.textContent = label; }
            if (initial) { initial.textContent = label.charAt(0).toUpperCase(); }
        }
        document.querySelectorAll('[data-requires-auth]').forEach(function (node) {
            node.classList.toggle('is-signed-out', !authed);
        });
    }

    function bindNav() {
        var signIn = el('authSignInBtn');
        if (signIn) {
            signIn.addEventListener('click', function () {
                // Come back to the page the reader was actually on.
                window.location.href = '/signin.html?returnTo=' + encodeURIComponent(window.location.pathname);
            });
        }

        var profileBtn = el('authProfileBtn');
        var menu = el('authProfileMenu');
        if (profileBtn && menu) {
            menu.innerHTML =
                '<a class="profile-dropdown__item" href="/#collections">' +
                '<i class="fa fa-bookmark" aria-hidden="true"></i> My Collections</a>' +
                '<button class="profile-dropdown__item" type="button" id="hubSignOut">' +
                '<i class="fa fa-right-from-bracket" aria-hidden="true"></i> Sign Out</button>';

            profileBtn.addEventListener('click', function (event) {
                event.stopPropagation();
                var open = menu.classList.toggle('d-none');
                profileBtn.setAttribute('aria-expanded', open ? 'false' : 'true');
                menu.setAttribute('aria-hidden', open ? 'true' : 'false');
            });
            document.addEventListener('click', function () {
                menu.classList.add('d-none');
                profileBtn.setAttribute('aria-expanded', 'false');
            });
            menu.addEventListener('click', function (event) { event.stopPropagation(); });
            menu.addEventListener('click', function (event) {
                if (event.target.closest('#hubSignOut')) {
                    apiJSON('/v1/auth/logout', { method: 'POST' }).then(function () {
                        window.location.reload();
                    });
                }
            });
        }
    }

    /* ── Save to collection ─────────────────────────────────────────────────── */

    function toast(message, tone) {
        var node = document.createElement('div');
        node.className = 'hub-toast' + (tone === 'error' ? ' hub-toast--error' : '');
        node.textContent = message;
        document.body.appendChild(node);
        requestAnimationFrame(function () { node.classList.add('is-visible'); });
        setTimeout(function () {
            node.classList.remove('is-visible');
            setTimeout(function () { node.remove(); }, 300);
        }, 2600);
    }

    function closeModal() {
        var existing = document.querySelector('.hub-modal-backdrop');
        if (existing) { existing.remove(); }
    }

    function openSaveModal(hadithId, label) {
        closeModal();
        var backdrop = document.createElement('div');
        backdrop.className = 'hub-modal-backdrop';
        backdrop.innerHTML =
            '<div class="hub-modal" role="dialog" aria-modal="true" aria-label="Save to a collection">' +
            '<div class="hub-modal__head">' +
            '<span class="hub-modal__title">Save narration</span>' +
            '<span class="hub-modal__sub"></span>' +
            '</div>' +
            '<div class="hub-modal__body">' +
            '<label class="hub-modal__label" for="hubCollectionSelect">Collection</label>' +
            '<select class="hub-modal__select" id="hubCollectionSelect"></select>' +
            '<input class="hub-modal__input d-none" id="hubCollectionNew" type="text" ' +
            'placeholder="New collection name" maxlength="80"/>' +
            '</div>' +
            '<div class="hub-modal__foot">' +
            '<button type="button" class="hub-modal__btn" id="hubSaveCancel">Cancel</button>' +
            '<button type="button" class="hub-modal__btn hub-modal__btn--primary" id="hubSaveConfirm">Save</button>' +
            '</div></div>';
        document.body.appendChild(backdrop);
        backdrop.querySelector('.hub-modal__sub').textContent = label || '';

        var select = el('hubCollectionSelect');
        var newInput = el('hubCollectionNew');
        var NEW_VALUE = '__new__';

        function syncNewInput() {
            newInput.classList.toggle('d-none', select.value !== NEW_VALUE);
            if (select.value === NEW_VALUE) { newInput.focus(); }
        }

        apiJSON('/v1/collections', { method: 'GET' }).then(function (resp) {
            var collections = (resp.data && resp.data.collections) || [];
            select.innerHTML = '';
            collections.forEach(function (collection) {
                var option = document.createElement('option');
                option.value = collection.name;
                option.textContent = collection.name;
                select.appendChild(option);
            });
            var newOption = document.createElement('option');
            newOption.value = NEW_VALUE;
            newOption.textContent = '+ New collection';
            select.appendChild(newOption);
            if (!collections.length) { select.value = NEW_VALUE; }
            syncNewInput();
        });

        select.addEventListener('change', syncNewInput);
        backdrop.addEventListener('click', function (event) {
            if (event.target === backdrop || event.target.closest('#hubSaveCancel')) { closeModal(); }
        });
        document.addEventListener('keydown', function onKey(event) {
            if (event.key === 'Escape') { closeModal(); document.removeEventListener('keydown', onKey); }
        });

        el('hubSaveConfirm').addEventListener('click', function () {
            var name = select.value === NEW_VALUE
                ? (newInput.value.trim() || 'New Collection')
                : select.value;
            apiJSON('/v1/collections/quick-save', {
                method: 'POST',
                body: JSON.stringify({ hadithId: hadithId, collectionName: name })
            }).then(function (resp) {
                if (!resp.ok || !resp.data || !resp.data.ok) {
                    toast((resp.data && resp.data.message) || 'Could not save the narration.', 'error');
                    return;
                }
                closeModal();
                toast('Saved to ' + name + '.');
            });
        });
    }

    function bindSaveButtons() {
        document.addEventListener('click', function (event) {
            var trigger = event.target.closest('[data-save-hadith]');
            if (!trigger) { return; }
            event.preventDefault();
            if (!authState.authenticated) {
                window.location.href = '/signin.html?returnTo=' + encodeURIComponent(window.location.pathname);
                return;
            }
            openSaveModal(trigger.getAttribute('data-save-hadith'), trigger.getAttribute('data-save-label'));
        });
    }

    document.addEventListener('DOMContentLoaded', function () {
        bindNav();
        bindSaveButtons();
        apiJSON('/v1/auth/me', { method: 'GET' })
            .then(function (resp) {
                var data = resp.data || {};
                authState.authenticated = !!data.authenticated;
                authState.user = data.user || null;
            })
            .catch(function () { authState.authenticated = false; })
            .then(applyAuthState);
    });
}());
