/*
 * Term suggestions and chips for the header's search field.
 *
 * The field ships as a plain <input> in a GET form, which works with no script at all
 * and is what the server renders. This upgrades it in place to the same Tom Select
 * control the search page uses, so a reader typing in the header of a book page gets
 * the suggestions and the chips they get everywhere else — rather than a box that
 * looks identical and behaves like a different product.
 *
 * It is an upgrade rather than the markup itself for two reasons: the form keeps
 * working if the CDN is slow or blocked, and a bare <select multiple> in the header
 * would be worse than an input for anyone who never gets the script.
 */
(function () {
    'use strict';

    var SUGGEST_URL = '/v1/terms/top?term=';

    function t(key, fallback) {
        var table = window.I18N || {};
        var value = table[key];
        return (typeof value === 'string' && value.length) ? value : fallback;
    }

    /** Digits in the page's own numerals, matching the rest of the Arabic site. */
    function arabicSite() {
        return window.I18N_LOCALE === 'ar';
    }

    /**
     * The suggestion list opens under the caret.
     *
     * Tom Select puts the dropdown on <body> and positions it from the field's left
     * edge, which is the wrong end of the field in Arabic. The search page solves this
     * the same way; the two are separate because neither script loads the other.
     */
    function alignDropdown(control, shell) {
        if (!arabicSite() || !control.dropdown || !shell) {
            return;
        }
        var apply = function () {
            var dropdown = control.dropdown;
            if (!dropdown || dropdown.style.display === 'none') {
                return;
            }
            var width = dropdown.getBoundingClientRect().width;
            if (!width) {
                return;
            }
            var edge = shell.getBoundingClientRect().right + window.scrollX;
            dropdown.style.setProperty('left', Math.round(edge - width - 13) + 'px', 'important');
        };
        requestAnimationFrame(apply);
        setTimeout(apply, 0);
    }

    /**
     * One narrator, as a row in the list.
     *
     * The value is what gets searched. On the Arabic site that is the Arabic name: the
     * list used to put the transliteration first in both languages, so «زرارة» typed
     * into the Arabic box answered «Zurarah ibn A'yan» and then searched for it.
     */
    function narratorOption(man) {
        var ar = man.display_ar || '';
        var en = man.display_en || '';
        var label = arabicSite() ? (ar || en) : (en || ar);
        var other = arabicSite() ? '' : (ar ? '  ' + ar : '');
        var era = (man.life && (arabicSite() ? man.life.imams_ar : man.life.imams)) || [];
        var life = era.slice(0, 2).join(arabicSite() ? '\u060c ' : ', ');
        return {
            value: label,
            text: label + other + (life ? '  \u00b7 ' + life : ''),
            group: 'narrators'
        };
    }

    function submit(control, form) {
        // Whatever is half-typed counts as a term, the way Enter behaves on the
        // search page: one key, one term, and the search runs.
        var pending = (control.control_input.value || '').trim();
        if (pending) {
            if (!control.options[pending]) {
                control.addOption({ value: pending, text: pending });
            }
            control.addItem(pending, true);
            control.setTextboxValue('');
        }
        var terms = (control.items || []).slice();
        if (!terms.length) {
            return;
        }
        var action = form.getAttribute('action') || '/';
        window.location.href = action + '?q=' + encodeURIComponent(terms.join(' '));
    }

    function upgrade(form) {
        if (form.dataset.navSearchUpgraded || typeof window.TomSelect !== 'function') {
            return;
        }
        var input = form.querySelector('[data-nav-search-input]');
        var shell = form.querySelector('.search-entry-shell');
        if (!input) {
            return;
        }
        form.dataset.navSearchUpgraded = '1';

        var placeholder = input.getAttribute('placeholder') || '';
        var select = document.createElement('select');
        select.multiple = true;
        select.className = 'form-control';
        input.parentNode.insertBefore(select, input);
        input.remove();

        var control = new window.TomSelect(select, {
            // Without this a term can be added here and not taken back out: the chip
            // renders with no remove control, and the reader only gets one after
            // pressing enter, because the search page builds its own control that has
            // always carried the plugin. Every other Tom Select on the site has it.
            plugins: { remove_button: { title: t('js.remove', 'Remove') } },
            persist: false,
            create: true,
            createOnBlur: false,
            hideSelected: true,
            closeAfterSelect: true,
            maxItems: null,
            dropdownParent: 'body',
            placeholder: placeholder,
            // Two kinds of suggestion, kept apart and labelled, exactly as the search
            // page does them. This control offered only words: a reader typing a man's
            // name into the field that says it searches the narrators was shown eight
            // spellings of the word and no man.
            optgroups: [
                { value: 'narrators', label: t('js.suggest.narrators', 'Narrators') },
                { value: 'terms', label: t('js.suggest.terms', 'Terms in the narrations') }
            ],
            optgroupField: 'group',
            lockOptgroupOrder: true,
            loadThrottle: 250,
            load: function (query, callback) {
                var term = (query || '').trim();
                // One word, two letters: the same floor the search page uses, so the
                // endpoint is not asked for completions of a phrase.
                if (term.length < 2 || term.indexOf(' ') >= 0) {
                    return callback();
                }
                var clean = term.replace(/["']/g, '');
                // Asked for together so the list does not jump as a second answer lands;
                // either side may fail without costing the reader the other.
                var terms = fetch(SUGGEST_URL + encodeURIComponent(clean))
                    .then(function (response) { return response.json(); })
                    .catch(function () { return []; });
                var men = fetch('/v1/narrators?per_page=4&q=' + encodeURIComponent(clean))
                    .then(function (response) { return response.ok ? response.json() : null; })
                    .then(function (payload) {
                        // A name three thousand men share names nobody — the same floor
                        // the strip over the results uses, for the same reason.
                        if (!payload || !payload.narrators || payload.total > 150) {
                            return [];
                        }
                        return payload.narrators.filter(function (man) {
                            return man && man.entry_count > 0;
                        });
                    })
                    .catch(function () { return []; });
                Promise.all([men, terms]).then(function (both) {
                    var results = both[0].map(narratorOption);
                    var seen = {};
                    results.forEach(function (row) { seen[row.value.toLowerCase()] = true; });
                    (Array.isArray(both[1]) ? both[1] : []).forEach(function (item) {
                        if (!seen[String(item).toLowerCase()]) {
                            results.push({ value: item, text: item, group: 'terms' });
                        }
                    });
                    callback(results);
                }).catch(function () { callback(); });
            },
            render: {
                option_create: function (data, escape) {
                    return '<div class="create">'
                        + t('js.addTerm', 'Add {0}…')
                            .replace('{0}', '<strong>' + escape(data.input) + '</strong>')
                        + '</div>';
                },
                no_results: function () {
                    return '<div class="no-results">'
                        + t('js.noSuggestions', 'No results found') + '</div>';
                }
            },
            onDropdownOpen: function () { alignDropdown(this, shell); },
            onType: function () { alignDropdown(this, shell); }
        });

        /*
         * The placeholder, with the two words a reader needs emphasised.
         *
         * An HTML placeholder is plain text: it takes a colour and a weight as a whole
         * and cannot carry markup, so «narrations» and «narrators» could not be picked
         * out of it. This is a real element laid over the field, shown only while the
         * control is empty, and it is aria-hidden because the input keeps its own
         * placeholder and label for anyone not looking at it.
         */
        var hint = document.createElement('span');
        hint.className = 'nav-search__hint';
        hint.setAttribute('aria-hidden', 'true');
        hint.innerHTML = t('search.navPlaceholder.html', '');
        if (hint.innerHTML && shell) {
            shell.appendChild(hint);
            // The native placeholder has to go, not merely be made transparent: a
            // ::placeholder rule was losing to something more specific and the two
            // strings printed on top of each other. The input keeps its aria-label,
            // so clearing the attribute costs a screen reader nothing.
            // The placeholder was this field's accessible name, so it is moved to an
            // aria-label before being taken away rather than simply dropped.
            if (!control.control_input.getAttribute('aria-label')) {
                control.control_input.setAttribute('aria-label', placeholder);
            }
            control.control_input.removeAttribute('placeholder');
            // Gone the moment the field is live, not when the suggestion list catches
            // up. Tom Select's own `type` event is throttled with the loader, so the
            // first few keystrokes were landing on top of the hint and the two strings
            // overlapped until the dropdown opened. The raw `input` event fires on
            // every keystroke, and focus hides it outright: a focused field has a
            // caret in it, which says what the placeholder was there to say.
            var focused = false;
            var syncHint = function () {
                var typed = (control.control_input && control.control_input.value) || '';
                var empty = !focused && !typed && !(control.items && control.items.length);
                shell.classList.toggle('has-hint', empty);
            };
            syncHint();
            control.control_input.addEventListener('input', syncHint);
            control.on('item_add', syncHint);
            control.on('item_remove', syncHint);
            // On the text box itself, not on Tom Select's wrapper: its focus event did
            // not fire for a caret placed in the field, so the hint stayed up under
            // the typing.
            control.control_input.addEventListener('focus', function () {
                focused = true; syncHint();
            });
            control.control_input.addEventListener('blur', function () {
                focused = false; syncHint();
            });
            hint.addEventListener('mousedown', function (e) {
                e.preventDefault();
                control.focus();
            });
        }

        // Tom Select highlights the first suggestion as soon as the list opens, so
        // "is a suggestion active" is true from the first keystroke and cannot stand
        // in for "the reader chose one". Track the arrow keys instead, the way the
        // search page does: Enter submits unless they actually moved to an option.
        var arrowed = false;
        control.control_input.addEventListener('keydown', function (event) {
            if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
                arrowed = true;
                return;
            }
            if (event.key === 'Escape') {
                arrowed = false;
                return;
            }
            if (event.key !== 'Enter') {
                return;
            }
            if (arrowed && control.isOpen && control.activeOption) {
                arrowed = false;
                return;
            }
            event.preventDefault();
            event.stopPropagation();
            submit(control, form);
        }, true);

        control.control_input.addEventListener('input', function () { arrowed = false; });

        form.addEventListener('submit', function (event) {
            event.preventDefault();
            submit(control, form);
        });

        window.addEventListener('resize', function () { alignDropdown(control, shell); });
    }

    function upgradeAll() {
        document.querySelectorAll('form[data-nav-search]').forEach(upgrade);
    }

    document.addEventListener('DOMContentLoaded', function () {
        upgradeAll();
        // The library is loaded with defer from a CDN and may land after this does.
        if (typeof window.TomSelect !== 'function') {
            var tries = 0;
            var wait = setInterval(function () {
                if (typeof window.TomSelect === 'function' || ++tries > 40) {
                    clearInterval(wait);
                    upgradeAll();
                }
            }, 150);
        }
    });
}());
