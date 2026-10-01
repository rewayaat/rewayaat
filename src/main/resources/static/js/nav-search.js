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
            persist: false,
            create: true,
            createOnBlur: false,
            hideSelected: true,
            closeAfterSelect: true,
            maxItems: null,
            dropdownParent: 'body',
            placeholder: placeholder,
            loadThrottle: 250,
            load: function (query, callback) {
                var term = (query || '').trim();
                // One word, two letters: the same floor the search page uses, so the
                // endpoint is not asked for completions of a phrase.
                if (term.length < 2 || term.indexOf(' ') >= 0) {
                    return callback();
                }
                fetch(SUGGEST_URL + encodeURIComponent(term.replace(/["']/g, '')))
                    .then(function (response) { return response.json(); })
                    .then(function (data) {
                        callback((data || []).map(function (item) {
                            return { value: item, text: item };
                        }));
                    })
                    .catch(function () { callback(); });
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
