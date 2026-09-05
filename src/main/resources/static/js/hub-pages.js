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

    /* ── Card menus, copy and share ─────────────────────────────────────────── */

    function closeMenus(except) {
        document.querySelectorAll('[data-hub-menu]').forEach(function (menu) {
            if (menu === except) { return; }
            menu.classList.remove('show');
            var trigger = menu.parentElement.querySelector('[data-hub-menu-trigger]');
            if (trigger) { trigger.setAttribute('aria-expanded', 'false'); }
        });
    }

    function cardData(node) {
        var card = node.closest('.hadith-card');
        var script = card && card.querySelector('.hub-card-data');
        if (!script) { return {}; }
        try { return JSON.parse(script.textContent || '{}'); } catch (e) { return {}; }
    }

    function copyText(text, label) {
        if (!text) { toast('Nothing to copy.', 'error'); return; }
        if (navigator.clipboard && navigator.clipboard.writeText) {
            navigator.clipboard.writeText(text)
                .then(function () { toast(label + ' copied.'); })
                .catch(function () { toast('Could not copy.', 'error'); });
            return;
        }
        var ta = document.createElement('textarea');
        ta.value = text;
        ta.style.cssText = 'position:fixed;left:-9999px';
        document.body.appendChild(ta);
        ta.select();
        try { document.execCommand('copy'); toast(label + ' copied.'); }
        catch (e) { toast('Could not copy.', 'error'); }
        document.body.removeChild(ta);
    }

    function bindCardActions() {
        document.addEventListener('click', function (event) {
            // Bootstrap's JS is not loaded on these pages; two menus do not justify it.
            var trigger = event.target.closest('[data-hub-menu-trigger]');
            if (trigger) {
                event.preventDefault();
                var menu = trigger.parentElement.querySelector('[data-hub-menu]');
                var open = menu.classList.contains('show');
                closeMenus();
                menu.classList.toggle('show', !open);
                trigger.setAttribute('aria-expanded', open ? 'false' : 'true');
                return;
            }

            var copyField = event.target.closest('[data-copy-field]');
            if (copyField) {
                var field = copyField.getAttribute('data-copy-field');
                var data = cardData(copyField);
                copyText(data[field], field === 'arabic' ? 'Arabic' : 'English');
                closeMenus();
                return;
            }

            var copyUrl = event.target.closest('[data-copy-url]');
            if (copyUrl) {
                copyText(copyUrl.getAttribute('data-copy-url'), 'Link');
                closeMenus();
                return;
            }

            closeMenus();
        });

        document.addEventListener('keydown', function (event) {
            if (event.key === 'Escape') { closeMenus(); }
        });
    }

    /* ── Related and Tafsir panels ──────────────────────────────────────────── */

    function escapeHtml(value) {
        return String(value == null ? '' : value)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    /**
     * Both panels emit the markup the Vue card emits — the same accordion, the same class
     * names, the same match-type chip and tafsir source tree. Anything less and a reader
     * can tell which renderer painted the page, which is the whole thing we are trying to
     * avoid.
     */
    function accordion(label, items, renderBody, itemClass) {
        if (!items.length) {
            return '<div class="text-muted py-2">' + label + '</div>';
        }
        var shown = items.slice(0, 10);
        var html = '<div class="hadith-sidecar__list hadith-sidecar__accordion" role="listbox">'
            + shown.map(function (item, idx) {
                return '<div class="hadith-sidecar__accordion-item' + (idx === 0 ? ' is-active' : '') + '"'
                    + ' data-hub-acc-item="' + idx + '">'
                    + '<button type="button" class="hadith-sidecar__list-item '
                    + 'hadith-sidecar__accordion-toggle hadith-sidecar__list-item--' + itemClass
                    + (idx === 0 ? ' is-active' : '') + '"'
                    + ' aria-expanded="' + (idx === 0) + '" data-hub-acc-toggle="' + idx + '">'
                    + item.line
                    + '</button>'
                    + '<div class="hadith-sidecar__accordion-body hadith-sidecar__accordion-body--' + itemClass + '"'
                    + (idx === 0 ? '' : ' hidden') + '>' + renderBody(item.raw, idx) + '</div>'
                    + '</div>';
            }).join('');
        if (items.length > 10) {
            html += '<button type="button" class="hadith-sidecar__show-more" data-hub-show-all>'
                + 'Show all ' + items.length + ' <i class="fa fa-chevron-down" aria-hidden="true"></i></button>';
        }
        return html + '</div>';
    }

    function sep() {
        return '<span class="hadith-sidecar__list-separator" aria-hidden="true">\u2022</span>';
    }

    function renderSimilar(body, data) {
        var items = (data && data.collection) || [];
        var rows = items.map(function (item, idx) {
            var book = item.book || ('Similar hadith ' + (idx + 1));
            var num = item.number ? '#' + item.number : ('Similar hadith ' + (idx + 1));
            var line = '<span class="hadith-sidecar__list-line">'
                + '<span class="hadith-sidecar__list-eyebrow">' + escapeHtml(book) + '</span>'
                + sep()
                + '<span class="hadith-sidecar__list-text">' + escapeHtml(num) + '</span>'
                + (item.matchType
                    ? sep() + '<span class="hadith-sidecar__list-meta match-type-badge match-type--'
                      + escapeHtml(item.matchType) + '">' + escapeHtml(item.matchType) + '</span>'
                    : '')
                + '</span>';
            return {line: line, raw: item};
        });
        body.innerHTML = accordion('No similar hadith were found for this narration.', rows,
            function (item) {
                var id = item._id || item.id || '';
                return (item.matchReason
                        ? '<div class="similar-reason-text">'
                          + '<span class="similar-reason-label">Why this matched:</span> '
                          + escapeHtml(item.matchReason) + '</div>'
                        : '')
                    + '<a class="quranic-verse-link" href="/hadith/' + encodeURIComponent(id) + '">'
                    + 'Read this narration <i class="fa fa-external-link-alt fa-xs"></i></a>';
            }, 'similar');
    }

    function alIslamSurahSlug(item) {
        return String(item.surah_name_english || item.surahNameEnglish || '')
            .toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
    }

    function renderQuran(body, data) {
        var items = (data && (data.insights || data.candidates || data.items)) || [];
        var rows = items.map(function (item, idx) {
            var ref = item.verse_key || item.reference || ('Verse ' + (idx + 1));
            var surah = item.surah_name_english || 'Quranic verse';
            var line = '<span class="hadith-sidecar__list-line">'
                + '<span class="hadith-sidecar__list-eyebrow">' + escapeHtml(ref) + '</span>'
                + sep()
                + '<span class="hadith-sidecar__list-text">' + escapeHtml(surah) + '</span>'
                + '</span>';
            return {line: line, raw: item};
        });
        body.innerHTML = accordion('No Quranic insights were found for this narration.', rows,
            function (item) {
                var sources = sourceOptions(item);
                var tree = sources.length
                    ? '<div class="hadith-sidecar__source-tree">'
                      + '<div class="hadith-sidecar__source-tree-label">Tafsir Sources</div>'
                      + '<div class="hadith-sidecar__source-tree-children">'
                      + sources.map(function (source) {
                          return '<span class="hadith-sidecar__source-node">'
                              + '<span class="hadith-sidecar__source-branch" aria-hidden="true"></span>'
                              + '<span class="hadith-sidecar__source-node-icon" aria-hidden="true">'
                              + '<i class="fa fa-file-lines"></i></span>'
                              + '<span class="hadith-sidecar__source-name">'
                              + escapeHtml(source.label) + '</span>'
                              + (source.count ? '<span class="hadith-sidecar__source-count">'
                                                + escapeHtml(String(source.count)) + '</span>' : '')
                              + '</span>';
                      }).join('')
                      + '</div></div>'
                    : '';
                return (item.text_english
                        ? '<div class="quranic-verse-english">' + escapeHtml(item.text_english) + '</div>' : '')
                    + (item.text_arabic
                        ? '<div class="quranic-verse-arabic arabic-text">' + escapeHtml(item.text_arabic) + '</div>' : '')
                    + (item.surah_number && item.ayah_number
                        ? '<a class="quranic-verse-link" target="_blank" rel="noopener" href="'
                          + 'https://al-islam.org/quran/surah/' + encodeURIComponent(item.surah_number)
                          + '/' + alIslamSurahSlug(item) + '/ayat/' + encodeURIComponent(item.ayah_number)
                          + '">View on Al-Islam.org <i class="fa fa-external-link-alt fa-xs"></i></a>'
                        : '')
                    + tree;
            }, 'quran');
    }

    /** Renders the chosen Related narration into the main column. */
    function inlineSimilar(item) {
        var title = [item.book, item.number ? '#' + item.number : ''].filter(Boolean).join(' ');
        // Book, volume and chapter each have a page; the URLs are filled in by
        // linkMetaSegments once the server has resolved them, so the slugs stay
        // server-side. Section has no page and stays plain text.
        var meta = [
            {level: 'book', text: item.book || ''},
            {level: 'volume', text: item.volume ? 'Volume ' + item.volume : ''},
            {level: 'section', text: item.section ? 'Section ' + item.section : ''},
            {level: 'chapter', text: item.chapter || ''}
        ].filter(function (seg) { return seg.text; });
        var id = item._id || item.id || '';
        return '<div class="hadith-inline-context__meta-band hadith-inline-context__meta-band--compact">'
            + '<div class="hadith-inline-context__title-block">'
            + '<div class="hadith-inline-context__eyebrow">Similar Hadith</div>'
            + '<a class="hadith-inline-context__title-link" href="/hadith/' + encodeURIComponent(id) + '">'
            + escapeHtml(title || 'Similar narration') + '</a>'
            + (meta.length ? '<div class="hadith-inline-context__meta-line" data-hub-meta-line>'
                + meta.map(function (seg, i) {
                    return (i ? '<span class="hadith-inline-context__meta-separator" aria-hidden="true">\u2022</span>' : '')
                        + '<span class="hadith-inline-context__meta-inline-text" data-hub-seg="'
                        + seg.level + '">' + escapeHtml(seg.text) + '</span>';
                  }).join('') + '</div>' : '')
            + '</div></div>'
            + '<div class="hadith-inline-context__scroll">'
            + '<div class="row g-4 hadith-inline-context__row">'
            + '<div class="col-12 col-lg-6"><div class="hadith-inline-context__panel hadith-inline-context__panel--similar">'
            + '<div class="hadith-inline-context__body">'
            + (item.englishChain ? '<div class="hadith-chain">' + item.englishChain + '</div>' : '')
            + '<div class="similar-full-english">' + (item.englishContent || item.english || '') + '</div>'
            + '</div></div></div>'
            + '<div class="col-12 col-lg-6"><div class="hadith-inline-context__panel hadith-inline-context__panel--similar">'
            + '<div class="hadith-inline-context__body arabic-text">'
            + (item.arabicChain ? '<div class="hadith-chain hadith-chain--arabic">' + item.arabicChain + '</div>' : '')
            + '<div class="similar-full-arabic">' + (item.arabicContent || item.arabic || '') + '</div>'
            + '</div></div></div>'
            + '</div></div>';
    }

    /**
     * The tafsir sources for a verse, as {slug, label, url, count}.
     *
     * <p>Mirrors buildSourceOptions in rewayaat.js. The API's `sources` is a list of
     * plain NAMES, not objects — reading .label off it, as this used to, yields blank
     * nodes. The snippets carry the slug and URL, so the two are merged, keyed on a
     * normalised name so a source named in both does not appear twice.
     */
    function sourceOptions(item) {
        var options = [];
        var byAlias = {};
        function key(v) { return String(v || '').trim().toLowerCase(); }
        function ensure(slug, label, url) {
            var existing = byAlias[key(slug)] || byAlias[key(label)];
            if (!existing) {
                existing = {slug: String(slug || label || '').trim(),
                            label: String(label || slug || '').trim(), url: '', count: 0};
                options.push(existing);
            }
            if (key(slug)) { byAlias[key(slug)] = existing; }
            if (key(label)) { byAlias[key(label)] = existing; }
            if (url && !existing.url) { existing.url = url; }
            return existing;
        }
        (item.tafsir_snippets || []).forEach(function (sn) {
            ensure(sn.tafsir_slug, sn.tafsir_name, sn.source_url).count += 1;
        });
        // Names the API lists without a snippet attached still belong in the tree.
        (item.sources || []).forEach(function (name) { ensure('', name, ''); });
        return options.filter(function (o) { return o.label; });
    }

    /** Renders the chosen verse and its tafsir snippets into the main column. */
    function inlineQuran(item) {
        var snippets = item.tafsir_snippets || [];
        var head = '<div class="hadith-inline-context__meta-band hadith-inline-context__meta-band--compact '
            + 'hadith-inline-context__meta-band--quran">'
            + '<div class="hadith-inline-context__title-block">'
            + '<div class="hadith-inline-context__eyebrow">Quranic Insight</div>'
            + '<div class="hadith-inline-context__meta-line">'
            + '<span class="hadith-inline-context__meta-inline-text">'
            + escapeHtml(item.verse_key || '') + '</span>'
            + (item.surah_name_english
                ? '<span class="hadith-inline-context__meta-separator" aria-hidden="true">\u2022</span>'
                  + '<span class="hadith-inline-context__meta-inline-text">'
                  + escapeHtml(item.surah_name_english) + '</span>'
                : '')
            + '</div></div></div>';

        if (!snippets.length) {
            return head + '<div class="hadith-inline-context__snippets">'
                + '<div class="text-muted py-2">No tafsir snippets are attached to this verse yet.</div>'
                + '</div>';
        }
        return head + '<div class="hadith-inline-context__snippets">'
            + '<div class="quranic-section__label">Tafsir Sources</div>'
            + snippets.map(function (sn) {
                var name = sn.tafsir_name || sn.tafsir_slug || 'Tafsir';
                return '<div class="quranic-snippet">'
                    + '<div class="quranic-snippet__head">'
                    + (sn.source_url
                        ? '<a class="quranic-snippet__source-link" target="_blank" rel="noopener" href="'
                          + escapeHtml(sn.source_url) + '">' + escapeHtml(name) + '</a>'
                        : '<span class="quranic-snippet__source">' + escapeHtml(name) + '</span>')
                    + (sn.section_title
                        ? '<span class="quranic-snippet__section">&mdash; ' + escapeHtml(sn.section_title) + '</span>'
                        : '')
                    + '</div>'
                    + '<div class="quranic-snippet__text">' + (sn.commentary_text || '') + '</div>'
                    + '</div>';
              }).join('')
            + '</div>';
    }

    var INLINE = {similar: inlineSimilar, quran: inlineQuran};

    /**
     * Turns the inline panel's meta-line into links, the way the search card's is.
     * One round trip: /v1/browse/page answers with every level that has a page, so
     * the slugs are never rebuilt here. Segments stay as text until it answers, and
     * stay as text if it fails, so nothing on screen is ever a dead link.
     */
    function linkMetaSegments(host, item) {
        var line = host.querySelector('[data-hub-meta-line]');
        if (!line || !item.book) { return; }
        var params = new URLSearchParams();
        ['book', 'volume', 'part', 'section', 'chapter'].forEach(function (key) {
            if (item[key]) { params.set(key, item[key]); }
        });
        apiJSON('/v1/browse/page?' + params.toString(), {method: 'GET'}).then(function (resp) {
            var d = resp.data || {};
            var urls = {book: d.bookUrl, volume: d.volumeUrl, chapter: d.chapterUrl};
            line.querySelectorAll('[data-hub-seg]').forEach(function (span) {
                var url = urls[span.getAttribute('data-hub-seg')];
                if (!url) { return; }
                var a = document.createElement('a');
                a.className = 'hadith-inline-context__meta-inline-link';
                a.setAttribute('href', url);
                a.textContent = span.textContent;
                span.replaceWith(a);
            });
        }).catch(function () { /* leave them as text */ });
    }

    /** Remembers what each panel loaded, so selecting a row can render it inline. */
    var loaded = new WeakMap();

    function showInline(card, kind, index) {
        card.querySelectorAll('[data-hub-inline]').forEach(function (host) {
            host.classList.toggle('d-none', host.getAttribute('data-hub-inline') !== kind);
        });
        var host = card.querySelector('[data-hub-inline="' + kind + '"]');
        var store = loaded.get(card) || {};
        var items = store[kind] || [];
        if (!host || !items[index]) {
            if (host) { host.classList.add('d-none'); }
            return;
        }
        host.innerHTML = INLINE[kind](items[index]);
        if (kind === 'similar') {
            linkMetaSegments(host, items[index]);
        }
    }

    /** The accordion behaves the way the Vue one does: one open at a time. */
    function bindAccordions() {
        document.addEventListener('click', function (event) {
            var showAll = event.target.closest('[data-hub-show-all]');
            if (showAll) {
                event.preventDefault();
                showAll.remove();
                return;
            }
            var toggle = event.target.closest('[data-hub-acc-toggle]');
            if (!toggle) { return; }
            event.preventDefault();
            var list = toggle.closest('.hadith-sidecar__accordion');
            var item = toggle.closest('.hadith-sidecar__accordion-item');
            var card = toggle.closest('.hadith-card');
            var body = toggle.closest('[data-hub-panel-body]');
            if (card && body) {
                showInline(card, body.getAttribute('data-hub-panel-body'),
                        parseInt(toggle.getAttribute('data-hub-acc-toggle'), 10));
            }
            list.querySelectorAll('.hadith-sidecar__accordion-item').forEach(function (node) {
                var active = node === item;
                node.classList.toggle('is-active', active);
                node.querySelector('.hadith-sidecar__accordion-toggle').classList.toggle('is-active', active);
                node.querySelector('.hadith-sidecar__accordion-toggle')
                    .setAttribute('aria-expanded', active ? 'true' : 'false');
                node.querySelector('.hadith-sidecar__accordion-body').hidden = !active;
            });
        });
    }

    var PANELS = {
        similar: {url: function (id) { return '/v1/narrations/similar?id=' + encodeURIComponent(id) + '&per_page=10'; },
                  render: renderSimilar,
                  items: function (d) { return (d && d.collection) || []; }},
        quran:   {url: function (id) { return '/v1/narrations/quranic_insights?id=' + encodeURIComponent(id) + '&all=true'; },
                  render: renderQuran,
                  items: function (d) { return (d && (d.insights || d.candidates || d.items)) || []; }}
    };

    function currentIndex(card, kind) {
        var active = card.querySelector('[data-hub-panel-body="' + kind + '"] .hadith-sidecar__accordion-item.is-active');
        return active ? parseInt(active.getAttribute('data-hub-acc-item'), 10) : 0;
    }

    function bindSidecarPanels() {
        document.addEventListener('click', function (event) {
            var btn = event.target.closest('[data-hub-panel]');
            if (!btn) { return; }
            event.preventDefault();
            var card = btn.closest('.hadith-card');
            var wanted = btn.getAttribute('data-hub-panel');

            card.querySelectorAll('[data-hub-panel]').forEach(function (b) {
                var active = b === btn;
                b.classList.toggle('is-active', active);
                b.setAttribute('aria-selected', active ? 'true' : 'false');
            });
            card.querySelectorAll('[data-hub-panel-body]').forEach(function (body) {
                body.classList.toggle('d-none', body.getAttribute('data-hub-panel-body') !== wanted);
            });
            // The card's own class drives the sidecar's width and colouring.
            card.className = card.className.replace(/hadith-card--(metadata|similar|quran)/, 'hadith-card--' + wanted);
            // The sidecar panel's own class drives its layout per tab, as it does in Vue.
            var panel = card.querySelector('.hadith-sidecar__panel');
            if (panel) {
                panel.className = panel.className.replace(/is-(metadata|similar|quran)/, 'is-' + wanted);
            }
            if (wanted === 'metadata') {
                card.querySelectorAll('[data-hub-inline]').forEach(function (h) { h.classList.add('d-none'); });
            } else {
                showInline(card, wanted, currentIndex(card, wanted));
            }

            var spec = PANELS[wanted];
            if (!spec) { return; }
            var body = card.querySelector('[data-hub-panel-body="' + wanted + '"]');
            if (body.dataset.loaded) { return; }
            body.dataset.loaded = '1';
            body.innerHTML = '<div class="similar-loading-state py-2" role="status" aria-live="polite">'
                + '<div class="similar-tab-body similar-tab-body--loading mt-2">'
                + '<div class="hadith-loading-label">Fetching...</div>'
                + '<div class="hadith-loading-skeleton" style="margin-top: 12px;">'
                + '<div class="hadith-skeleton-bar hadith-skeleton-bar--short"></div>'
                + '<div class="hadith-skeleton-bar hadith-skeleton-bar--long"></div>'
                + '<div class="hadith-skeleton-bar hadith-skeleton-bar--medium"></div>'
                + '</div></div></div>';
            apiJSON(spec.url(card.getAttribute('data-hadith-id')), {method: 'GET'})
                .then(function (resp) {
                    spec.render(body, resp.data);
                    var store = loaded.get(card) || {};
                    store[wanted] = spec.items(resp.data);
                    loaded.set(card, store);
                    showInline(card, wanted, 0);
                })
                .catch(function () {
                    body.dataset.loaded = '';
                    body.innerHTML = '<div class="alert alert-warning py-2 my-2" role="alert">'
                        + 'Could not load this panel.</div>';
                });
        });
    }

    /* ── Tag overflow ───────────────────────────────────────────────────────── */

    var VISIBLE_TAGS = 4;

    /**
     * A narration with many tags pushed the card's footer into a wall of pills on a
     * phone. The search card collapses them behind a toggle; so does this.
     */
    function bindTagOverflow() {
        document.querySelectorAll('.hadith-card__tags').forEach(function (group) {
            var pills = Array.prototype.slice.call(group.querySelectorAll('.topic-pill'));
            if (pills.length <= VISIBLE_TAGS || group.dataset.bound) { return; }
            group.dataset.bound = '1';
            pills.slice(VISIBLE_TAGS).forEach(function (p) { p.classList.add('topic-pill--overflow'); });

            var toggle = document.createElement('button');
            toggle.type = 'button';
            toggle.className = 'hadith-tags-toggle';
            toggle.textContent = 'Show all ' + pills.length;
            toggle.addEventListener('click', function () {
                var open = group.classList.toggle('is-expanded');
                toggle.textContent = open ? 'Show fewer' : 'Show all ' + pills.length;
            });
            group.appendChild(toggle);
        });
    }

    /* ── Sidecar resize ─────────────────────────────────────────────────────── */

    var SIDECAR_MIN = 248, SIDECAR_MAX = 560;

    /**
     * The resizer is part of the card's grid, so it rendered here whether or not it did
     * anything — a handle that looks draggable and is not is worse than no handle.
     */
    function bindSidecarResize() {
        document.addEventListener('pointerdown', function (event) {
            var handle = event.target.closest('.hadith-card__resizer');
            if (!handle) { return; }
            var card = handle.closest('.hadith-card');
            var aside = card && card.querySelector('.hadith-sidecar');
            if (!aside) { return; }
            event.preventDefault();
            var startX = event.clientX;
            var startWidth = aside.getBoundingClientRect().width;
            handle.setPointerCapture(event.pointerId);
            card.classList.add('is-resizing');

            function move(e) {
                var next = Math.min(SIDECAR_MAX, Math.max(SIDECAR_MIN, startWidth + (e.clientX - startX)));
                card.style.setProperty('--hadith-sidecar-width', next + 'px');
            }
            function stop() {
                card.classList.remove('is-resizing');
                handle.removeEventListener('pointermove', move);
                handle.removeEventListener('pointerup', stop);
                handle.removeEventListener('pointercancel', stop);
            }
            handle.addEventListener('pointermove', move);
            handle.addEventListener('pointerup', stop);
            handle.addEventListener('pointercancel', stop);
        });

        // Double-click resets, as it does on the search page.
        document.addEventListener('dblclick', function (event) {
            var handle = event.target.closest('.hadith-card__resizer');
            if (!handle) { return; }
            var card = handle.closest('.hadith-card');
            if (card) { card.style.removeProperty('--hadith-sidecar-width'); }
        });
    }

    /* ── Export ─────────────────────────────────────────────────────────────── */

    /**
     * Prints the narrations on the page, the way the search results export does: build
     * the document in a hidden iframe and let the browser produce the PDF. No library,
     * and it works off the text already rendered rather than re-fetching it.
     */
    function bindPrintExport() {
        document.addEventListener('click', function (event) {
            var trigger = event.target.closest('[data-hub-print]');
            if (!trigger) { return; }
            event.preventDefault();

            var title = trigger.getAttribute('data-print-title') || document.title;
            var rows = Array.prototype.map.call(
                document.querySelectorAll('.hadith-card'),
                function (card) {
                    var num = (card.querySelector('.hadith-card__result-num') || {}).textContent || '';
                    var en = (card.querySelector('.hadith-english') || {}).innerHTML || '';
                    var ar = (card.querySelector('.hadith-arabic') || {}).innerHTML || '';
                    var chain = (card.querySelector('.hadith-chain') || {}).innerHTML || '';
                    return '<article class="n">'
                        + '<div class="num">' + escapeHtml(num.trim()) + '</div>'
                        + (chain ? '<div class="chain">' + chain + '</div>' : '')
                        + '<div class="en">' + en + '</div>'
                        + '<div class="ar" dir="rtl">' + ar + '</div>'
                        + '</article>';
                }).join('');

            var frame = document.createElement('iframe');
            frame.setAttribute('aria-hidden', 'true');
            frame.style.cssText = 'position:fixed;right:0;bottom:0;width:0;height:0;border:0;opacity:0';
            document.body.appendChild(frame);
            var doc = frame.contentWindow && frame.contentWindow.document;
            if (!doc) { frame.remove(); return; }

            doc.open();
            doc.write('<!doctype html><html><head><meta charset="utf-8"><title>'
                + escapeHtml(title) + '</title><style>'
                + 'body{font-family:Georgia,serif;line-height:1.6;margin:2rem;color:#1a1a2e}'
                + 'h1{font-size:1.4rem;margin-bottom:0.25rem}'
                + '.src{color:#666;font-size:0.85rem;margin-bottom:1.5rem}'
                + '.n{padding:1rem 0;border-bottom:1px solid #ddd;page-break-inside:avoid}'
                + '.num{font-size:0.75rem;letter-spacing:0.08em;text-transform:uppercase;color:#777}'
                + '.chain{font-size:0.85rem;color:#555;margin:0.35rem 0}'
                + '.en{margin:0.35rem 0}'
                + '.ar{font-family:"Scheherazade New",serif;font-size:1.25rem;line-height:2;margin-top:0.5rem}'
                + '</style></head><body><h1>' + escapeHtml(title) + '</h1>'
                + '<div class="src">' + escapeHtml(window.location.href) + '</div>'
                + rows + '</body></html>');
            doc.close();

            frame.contentWindow.focus();
            setTimeout(function () {
                frame.contentWindow.print();
                setTimeout(function () { frame.remove(); }, 1000);
            }, 250);
        });
    }

    document.addEventListener('DOMContentLoaded', function () {
        bindNav();
        bindSaveButtons();
        bindCardActions();
        bindSidecarPanels();
        bindAccordions();
        bindTagOverflow();
        bindSidecarResize();
        bindPrintExport();
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
