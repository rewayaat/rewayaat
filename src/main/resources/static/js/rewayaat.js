var vueApp;
var currentQueryText = '';
var bookBlurbs;
var readingFacetData = {};
var browseFacetConfig = [
    { key: 'volume', selectId: 'browseVolumeSelect', fieldAttr: 'data-browse-facet' },
    { key: 'part', selectId: 'browsePartSelect', fieldAttr: 'data-browse-facet' },
    { key: 'section', selectId: 'browseSectionSelect', fieldAttr: 'data-browse-facet' },
    { key: 'chapter', selectId: 'browseChapterSelect', fieldAttr: 'data-browse-facet' }
];
var readingFacetConfig = [
    { key: 'volume', selectId: 'readingVolumeSelect', fieldAttr: 'data-reading-facet' },
    { key: 'part', selectId: 'readingPartSelect', fieldAttr: 'data-reading-facet' },
    { key: 'section', selectId: 'readingSectionSelect', fieldAttr: 'data-reading-facet' },
    { key: 'chapter', selectId: 'readingChapterSelect', fieldAttr: 'data-reading-facet' }
];
var facetHierarchy = ['volume', 'part', 'section', 'chapter'];
var facetKeys = ['volume', 'part', 'section', 'chapter'];
var readingNavOrder = ['chapter', 'section', 'part', 'volume'];
var optionalFiltersHint = '';
var searchMatchMode = 'strict';
var suppressSearchGlow = false;
var welcomeContentLoading = false;
var welcomeContentInitialized = false;
var SEARCH_FETCH_LIMIT = 50;
var READING_PAGE_SIZE = 50;
var INITIAL_VISIBLE_NARRATIONS = 16;
var REVEAL_BATCH_SIZE = 12;
var INITIAL_VISIBLE_TAG_FILTERS = 15;
var similarLoadingMinDurationMs = 550;
var scopeFieldKeys = ['book', 'volume', 'part', 'section', 'chapter'];
var pendingSearchUpdateToast = null;
var pendingArabicSuggestionToast = null;
var authState = {
    authenticated: false,
    user: null
};
var userCollectionsCache = [];
var SIMILAR_ARABIC_DIACRITIC_PATTERN = /[\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06ED\u0640]/g;
var SIMILAR_NON_ARABIC_PATTERN = /[^\u0621-\u064A\u0660-\u0669\u06F0-\u06F90-9\s]/g;
var SIMILAR_MULTI_SPACE_PATTERN = /\s+/g;
var SIMILAR_ARABIC_TOKEN_PATTERN = /[\u0621-\u064A\u0660-\u0669\u06F0-\u06F90-9\u064B-\u065F\u0670\u06D6-\u06ED\u0640]+/g;
/**
 * Main entry point to the website. If does not exist, display default welcome
 * content. If there is a valid query, setup a Vue.js instance to display it.s
 */
function loadQuery(query, page = 1, sortFields) {
    query = resolveInitialQueryInput(query);
    if (isCollectionMode()) {
        $.getJSON("book_blurbs.json", function(book_blurbs) {
            bookBlurbs = book_blurbs;
            setupVue(query || '', page, sortFields);
            syncReadingModeUI(query || '', sortFields);
        });
        return;
    }
    if (query) {
        // validate query
        if (validQuery(query)) {
            // display query in search bar
            displayQuery(query);
            // load book blurbs
            $.getJSON("book_blurbs.json", function(book_blurbs) {
                bookBlurbs = book_blurbs
                // load the query
                setupVue(query, page, sortFields);
                syncReadingModeUI(query, sortFields);
            });
            // Update latest new bar
            //setLatestNewsBarHTML();
        } else {
            swal(
                "Invalid Query",
                "Please ensure the entered query is greater than three characters long!",
                "error");
            displayWelcomeContent();
        }
    } else {
        //setLatestNewsBarHTML('Our team has recently added <b><a target="_blank" style="color: black;text-decoration: underline;" href="' + window.location.href + '?q=book:%22al-amali%22">Al-Amali</a></b>, <b><a target="_blank" style="color: black;text-decoration: underline;" href="' + window.location.href + '?q=book%3A%22Khisal%22">Al Khisal</a></b> and <b><a target="_blank" style="color: black;text-decoration: underline;" href="' + window.location.href + '?q=book%3A%22Uyun%22">Uyun Akhbar Al-Rida</a></b> to our Collection!');
        // show default mark-down welcome page
        displayWelcomeContent();
        // load book blurbs
        $.getJSON("book_blurbs.json", function(book_blurbs) {
            bookBlurbs = book_blurbs
        });
    }
}

function resolveInitialQueryInput(fallbackQuery) {
    var urlQuery = '';
    try {
        var params = new URLSearchParams(window.location.search || '');
        urlQuery = params.get('q') || '';
    } catch (e) {
        urlQuery = '';
    }
    if (urlQuery && String(urlQuery).trim().length > 0) {
        return String(urlQuery);
    }
    return fallbackQuery == null ? '' : String(fallbackQuery);
}

function resolveCollectionIdParam() {
    try {
        return String(new URLSearchParams(window.location.search || '').get('collection_id') || '').trim();
    } catch (e) {
        return '';
    }
}

function isCollectionMode() {
    return !!resolveCollectionIdParam();
}

function getInitialTopicTags() {
    try {
        var params = new URLSearchParams(window.location.search || '');
        return params.getAll('topic_tags').map(function(tag) {
            return String(tag || '').trim();
        }).filter(function(tag) {
            return !!tag;
        });
    } catch (e) {
        return [];
    }
}

function setLatestNewsBarHTML(htmlCode) {
    document.getElementById("latest-news-bar").innerHTML = htmlCode;
}

function matchStart(params, data) {
    params.term = params.term || '';
    if (data.text.toUpperCase().indexOf(params.term.toUpperCase()) == 0) {
        return data;
    }
    return false;
}

$(document).ready(function() {
    // handle resize
    $(window).resize(changeCardWidth);
    changeCardWidth();
    // configure raven
    Raven.config('https://b0e8263fd0ca4b88b2c51043a51df738@sentry.io/289790').install()
    Raven.setDataCallback(function(data) {
        data.extra.sessionURL = LogRocket.sessionURL;
        return data;
    });
    setupGlobalSearchSubmit();
    setupSearchHelpHint();
    initAuthUI();
    handleAuthQueryActions();
    refreshAuthState();
});

var searchSelectControl = null;

function resetSearchSuggestionDropdown(control) {
    if (!control) {
        return;
    }
    if (typeof control.clearOptions === 'function') {
        control.clearOptions();
    }
    if (typeof control.clearActiveOption === 'function') {
        control.clearActiveOption();
    }
    if (typeof control.refreshOptions === 'function') {
        control.refreshOptions(false);
    }
    if (typeof control.close === 'function') {
        control.close();
    }
}

function updateSearchPlaceholder(control) {
    if (!control || !control.control_input) {
        return;
    }
    var hasItems = Array.isArray(control.items) && control.items.length > 0;
    control.control_input.placeholder = hasItems ? '' : '"Household of the prophet"  Ahlulbayt  "اهل البيت"';
    if (typeof control.inputState === 'function') {
        control.inputState();
    }
}

function setupSelect2EnterKeyListener(select2_id) {
    if (!searchSelectControl || !searchSelectControl.control_input) {
        return;
    }
    var input = searchSelectControl.control_input;
    if (input.dataset.boundSearchInput === 'true') {
        return;
    }
    input.dataset.boundSearchInput = 'true';
    input.addEventListener('keydown', function(e) {
        if (e.key === 'Enter') {
            e.preventDefault();
            e.stopPropagation();
            var pendingForEnter = (input.value || '').trim();
            if (pendingForEnter) {
                autocompletePendingSearchTerm(pendingForEnter);
                return;
            }
            submitSearchQuery();
            return;
        }
        if (e.key === 'Tab' && !e.shiftKey) {
            var pending = (input.value || '').trim();
            if (!pending) {
                return;
            }
            e.preventDefault();
            e.stopPropagation();
            autocompletePendingSearchTerm(pending);
        }
    });
    input.addEventListener('input', function() {
        if ((input.value || '').trim()) {
            indicatePendingSearchTerms();
        }
    });
}

function autocompletePendingSearchTerm(pendingText) {
    var pending = (pendingText || '').trim();
    if (!pending) {
        return;
    }
    resolveFirstSearchSuggestion(pending).then(function(suggested) {
        var token = (suggested || '').trim();
        if (!token) {
            token = pending;
        }
        commitPendingSearchTermsToControl([token]);
        indicatePendingSearchTerms();
        if (searchSelectControl && searchSelectControl.control_input) {
            searchSelectControl.control_input.focus();
        }
    });
}

function sanitizeAutocompleteSuggestionValue(value) {
    var token = (value || '').trim();
    if (!token) {
        return '';
    }
    if (token === '$create') {
        return '';
    }
    if (/^add\s+["'`]?/i.test(token)) {
        return '';
    }
    return token;
}

function resolveFirstSearchSuggestion(pending) {
    var fallback = Promise.resolve('');
    if (!searchSelectControl) {
        return fallback;
    }
    if (typeof searchSelectControl.refreshOptions === 'function') {
        searchSelectControl.refreshOptions(true);
    }
    var loaded = firstLoadedSearchSuggestion(searchSelectControl);
    if (loaded) {
        return Promise.resolve(loaded);
    }
    var trimmed = (pending || '').trim();
    if (trimmed.length < 2 || trimmed.indexOf(' ') >= 0) {
        return fallback;
    }
    var url = '/v1/terms/top?size=1&term=' + encodeURIComponent(trimmed.replace(/["']/g, ""));
    return fetch(url)
        .then(function(response) { return response.json(); })
        .then(function(data) {
            if (!Array.isArray(data) || !data.length) {
                return '';
            }
            return sanitizeAutocompleteSuggestionValue(String(data[0] || ''));
        })
        .catch(function() {
            return '';
        });
}

function firstLoadedSearchSuggestion(control) {
    if (!control) {
        return '';
    }
    if (control.currentResults && Array.isArray(control.currentResults.items) && control.currentResults.items.length) {
        for (var idx = 0; idx < control.currentResults.items.length; idx++) {
            var first = control.currentResults.items[idx];
            if (!first) {
                continue;
            }
            if (typeof first.id !== 'undefined' && first.id !== null) {
                var currentResultId = sanitizeAutocompleteSuggestionValue(String(first.id));
                if (currentResultId) {
                    return currentResultId;
                }
            }
            if (typeof first.value !== 'undefined' && first.value !== null) {
                var currentResultValue = sanitizeAutocompleteSuggestionValue(String(first.value));
                if (currentResultValue) {
                    return currentResultValue;
                }
            }
        }
    }
    var dropdown = control.dropdown_content;
    if (dropdown && dropdown.querySelector) {
        var optionNodes = dropdown.querySelectorAll('[data-selectable]');
        for (var i = 0; i < optionNodes.length; i++) {
            var optionNode = optionNodes[i];
            if (optionNode.classList && optionNode.classList.contains('create')) {
                continue;
            }
            var nodeValue = optionNode.getAttribute('data-value');
            if (nodeValue && nodeValue.trim()) {
                var dropdownValue = sanitizeAutocompleteSuggestionValue(nodeValue);
                if (dropdownValue) {
                    return dropdownValue;
                }
            }
        }
    }
    return '';
}

function isSearchBarFocused() {
    var active = document.activeElement;
    if (!active) {
        return false;
    }
    if (active.id === 'searchTerms') {
        return true;
    }
    if (active.closest && active.closest('#queryBar')) {
        return true;
    }
    return false;
}

function setupGlobalSearchSubmit() {
    document.addEventListener('keydown', function(e) {
        if (e.key !== 'Enter') {
            return;
        }
        if (isReadingMode()) {
            return;
        }
        if (isSearchBarFocused()) {
            return;
        }
        submitSearchQuery();
    });
}

function setupSearchHelpHint() {
    var btn = document.getElementById('searchModeHelpBtn');
    if (!btn || btn.dataset.bound === 'true') {
        return;
    }
    btn.addEventListener('click', function(e) {
        e.preventDefault();
        var msg = "Exact: exact words and exact phrases.\n\nFlexible: tolerates spelling variants and partial matches.";
        if (typeof swal === 'function') {
            swal('Search Modes', msg, 'info');
        } else {
            alert(msg);
        }
    });
    btn.dataset.bound = 'true';
}

function removeArabicText(text) {
    return text.replace(/[^\x00-\x7F]/g, "").trim();
}

function getSelectedSearchTerms() {
    if (searchSelectControl && Array.isArray(searchSelectControl.items)) {
        return searchSelectControl.items.slice();
    }
    var queryBar = document.getElementById("searchTerms");
    if (!queryBar) {
        return [];
    }
    var terms = [];
    for (var i = 0, len = queryBar.options.length; i < len; i++) {
        var opt = queryBar.options[i]
        if (opt.selected === true) {
            terms.push(opt.value);
        }
    }
    return terms;
}

function getPendingSearchTerms() {
    if (!searchSelectControl || !searchSelectControl.control_input) {
        return [];
    }
    var pending = (searchSelectControl.control_input.value || '').trim();
    if (!pending) {
        return [];
    }
    return splitQuery(pending)
        .map(function(term) { return (term || '').trim(); })
        .filter(function(term) { return term.length > 0; });
}

function mergeSearchTerms(selectedTerms, pendingTerms) {
    var merged = [];
    var seen = {};
    function addTerm(raw) {
        var term = (raw || '').trim();
        if (!term) {
            return;
        }
        var key = normalizeTermForCompare(term);
        if (seen[key]) {
            return;
        }
        seen[key] = true;
        merged.push(term);
    }
    (Array.isArray(selectedTerms) ? selectedTerms : []).forEach(addTerm);
    (Array.isArray(pendingTerms) ? pendingTerms : []).forEach(addTerm);
    return merged;
}

function commitPendingSearchTermsToControl(terms) {
    if (!searchSelectControl || !Array.isArray(terms) || !terms.length) {
        return;
    }
    terms.forEach(function(term) {
        if (!searchSelectControl.options[term]) {
            searchSelectControl.addOption({ value: term, text: term });
        }
        if (searchSelectControl.items.indexOf(term) === -1) {
            searchSelectControl.addItem(term, true);
        }
    });
    if (typeof searchSelectControl.clearTextbox === 'function') {
        searchSelectControl.clearTextbox();
    } else if (typeof searchSelectControl.setTextboxValue === 'function') {
        searchSelectControl.setTextboxValue('');
    }
    updateSearchPlaceholder(searchSelectControl);
    resetSearchSuggestionDropdown(searchSelectControl);
}

function apiJSON(url, options) {
    var opts = options || {};
    opts.credentials = 'same-origin';
    if (opts.body && (!opts.headers || !opts.headers['Content-Type'])) {
        opts.headers = opts.headers || {};
        opts.headers['Content-Type'] = 'application/json';
    }
    return fetch(url, opts).then(function(resp) {
        return resp.json().catch(function() { return {}; }).then(function(data) {
            return { ok: resp.ok, status: resp.status, data: data };
        });
    });
}

function showToast(message, type) {
    if (typeof Noty === 'undefined') {
        return;
    }
    new Noty({
        text: message,
        theme: 'mint',
        type: type || 'information',
        layout: 'topRight',
        timeout: 2600,
        progressBar: false,
        closeWith: ['click', 'button']
    }).show();
}

function showSearchUpdateToast() {
    if (typeof Noty === 'undefined') {
        return;
    }
    if (pendingSearchUpdateToast && typeof pendingSearchUpdateToast.close === 'function') {
        pendingSearchUpdateToast.close();
    }
    pendingSearchUpdateToast = new Noty({
        text: '<div class="search-update-toast"><span class="search-update-toast__label">Search changed</span><span class="search-update-toast__cta">Press Search to update your results</span></div>',
        theme: 'mint',
        type: 'information',
        layout: 'topRight',
        timeout: 7000,
        progressBar: false,
        closeWith: ['click', 'button'],
        callbacks: {
            onShow: function() {
                if (this.barDom) {
                    this.barDom.style.cursor = 'pointer';
                }
            },
            onClick: function() {
                submitSearchQuery();
            },
            onClose: function() {
                pendingSearchUpdateToast = null;
            }
        }
    }).show();
}

function closeArabicSuggestionToast() {
    if (pendingArabicSuggestionToast && typeof pendingArabicSuggestionToast.close === 'function') {
        pendingArabicSuggestionToast.close();
    }
    pendingArabicSuggestionToast = null;
}

function showArabicSuggestionToast(terms) {
    if (typeof Noty === 'undefined' || !Array.isArray(terms) || !terms.length) {
        return;
    }
    closeArabicSuggestionToast();
    var safeTerms = terms.slice(0, 3).map(function(term) {
        return String(term || '').trim();
    }).filter(function(term) {
        return !!term;
    });
    if (!safeTerms.length) {
        return;
    }
    var buttonsMarkup = safeTerms.map(function(term) {
        return '<button type="button" class="arabic-suggestion-toast__term" data-suggestion-term="' +
            escapeHtml(term) + '">' + escapeHtml(term) + '</button>';
    }).join('');
    pendingArabicSuggestionToast = new Noty({
        text: '<div class="arabic-suggestion-toast">' +
            '<span class="arabic-suggestion-toast__label">Suggested Arabic Terms</span>' +
            '<span class="arabic-suggestion-toast__hint">Add one to refine this search.</span>' +
            '<div class="arabic-suggestion-toast__terms">' + buttonsMarkup + '</div>' +
            '</div>',
        theme: 'mint',
        type: 'information',
        layout: 'topRight',
        timeout: 15000,
        progressBar: false,
        closeWith: ['click', 'button'],
        callbacks: {
            onShow: function() {
                if (!this.barDom) {
                    return;
                }
                Array.prototype.forEach.call(
                    this.barDom.querySelectorAll('[data-suggestion-term]'),
                    function(button) {
                        button.addEventListener('click', function(event) {
                            event.preventDefault();
                            event.stopPropagation();
                            if (vueApp && typeof vueApp.addArabicSuggestionToSearchBar === 'function') {
                                vueApp.addArabicSuggestionToSearchBar(button.getAttribute('data-suggestion-term'));
                            }
                            closeArabicSuggestionToast();
                        });
                    }
                );
            },
            onClose: function() {
                pendingArabicSuggestionToast = null;
            }
        }
    }).show();
}

function ensureTextValueNode(container, className) {
    if (!container) {
        return null;
    }
    var valueNode = container.querySelector('.' + className);
    if (valueNode) {
        return valueNode;
    }
    valueNode = document.createElement('span');
    valueNode.className = className;
    container.appendChild(valueNode);
    return valueNode;
}

function setContainerValueText(container, className, text) {
    if (!container) {
        return;
    }
    var valueNode = ensureTextValueNode(container, className);
    if (!valueNode) {
        return;
    }
    var nextText = text || '';
    valueNode.textContent = nextText;
    if (nextText) {
        container.setAttribute('title', nextText);
    } else {
        container.removeAttribute('title');
    }
}

function buildAuthPageUrl(mode, extraParams, options) {
    var url = new URL('/signin.html', window.location.origin);
    if (mode) {
        url.searchParams.set('mode', mode);
    }
    var opts = options || {};
    if (!opts.skipReturn) {
        var returnPath = window.location.pathname + window.location.search + window.location.hash;
        if (returnPath && returnPath !== '/signin.html') {
            url.searchParams.set('return', returnPath);
        }
    }
    if (extraParams && typeof extraParams === 'object') {
        Object.keys(extraParams).forEach(function(key) {
            var value = extraParams[key];
            if (value !== null && typeof value !== 'undefined' && String(value).trim() !== '') {
                url.searchParams.set(key, value);
            }
        });
    }
    return url.toString();
}

function redirectToAuthPage(mode, extraParams, options) {
    window.location.href = buildAuthPageUrl(mode, extraParams, options);
}

function initAuthUI() {
    var signInBtn = document.getElementById('authSignInBtn');
    var profileBtn = document.getElementById('authProfileBtn');
    var profileShell = document.getElementById('authProfileShell');
    var createCollectionBtn = document.getElementById('createCollectionBtn');
    var collectionsToggleBtn = document.getElementById('collectionsToggleBtn');

    if (signInBtn && !signInBtn.dataset.bound) {
        signInBtn.addEventListener('click', function() {
            redirectToAuthPage('login');
        });
        signInBtn.dataset.bound = 'true';
    }
    if (profileBtn && !profileBtn.dataset.bound) {
        profileBtn.addEventListener('click', function(event) {
            event.preventDefault();
            event.stopPropagation();
            toggleUserProfileMenu();
        });
        profileBtn.dataset.bound = 'true';
    }
    if (collectionsToggleBtn && !collectionsToggleBtn.dataset.bound) {
        collectionsToggleBtn.addEventListener('click', function() {
            var sidebar = document.getElementById('collectionSidebar');
            if (sidebar) {
                sidebar.classList.toggle('d-none');
                collectionsToggleBtn.classList.toggle('is-active');
            }
        });
        collectionsToggleBtn.dataset.bound = 'true';
    }
    if (profileShell && !profileShell.dataset.bound) {
        document.addEventListener('click', function(event) {
            if (!profileShell.contains(event.target)) {
                closeUserProfileMenu();
            }
        });
        document.addEventListener('keydown', function(event) {
            if (event.key === 'Escape') {
                closeUserProfileMenu();
            }
        });
        window.addEventListener('resize', positionUserProfileMenu);
        window.addEventListener('scroll', positionUserProfileMenu, true);
        profileShell.dataset.bound = 'true';
    }
    if (createCollectionBtn && !createCollectionBtn.dataset.bound) {
        createCollectionBtn.addEventListener('click', openCreateCollectionModal);
        createCollectionBtn.dataset.bound = 'true';
    }
}

function refreshAuthState() {
    return apiJSON('/v1/auth/me', { method: 'GET' })
        .then(function(resp) {
            var data = resp.data || {};
            authState.authenticated = !!data.authenticated;
            authState.user = data.user || null;
            applyAuthState();
            if (authState.authenticated) {
                loadAndRenderCollections(false);
            } else {
                userCollectionsCache = [];
                renderCollectionsSection([]);
            }
            return authState;
        })
        .catch(function() {
            authState.authenticated = false;
            authState.user = null;
            userCollectionsCache = [];
            applyAuthState();
            renderCollectionsSection([]);
            return authState;
        });
}

function applyAuthState() {
    var isAuthed = !!authState.authenticated;
    var signInBtn = document.getElementById('authSignInBtn');
    var profileShell = document.getElementById('authProfileShell');
    var profileBtn = document.getElementById('authProfileBtn');
    var profileName = document.getElementById('authProfileName');
    var profileInitial = document.getElementById('authProfileInitial');
    if (signInBtn) {
        signInBtn.classList.toggle('d-none', isAuthed);
        signInBtn.innerHTML = '<i class="fa fa-sign-in" aria-hidden="true"></i> Sign In';
    }
    if (profileShell) {
        profileShell.classList.toggle('d-none', !isAuthed);
    }

    // Show/hide collections toggle button
    var collectionsToggleBtn = document.getElementById('collectionsToggleBtn');
    if (collectionsToggleBtn) {
        collectionsToggleBtn.classList.toggle('d-none', !isAuthed);
    }

    if (profileBtn) {
        profileBtn.setAttribute('aria-expanded', 'false');
    }
    if (profileName) {
        profileName.textContent = isAuthed
            ? ((authState.user && authState.user.displayName) || 'Account')
            : 'Account';
    }
    if (profileInitial) {
        var source = isAuthed
            ? ((authState.user && (authState.user.displayName || authState.user.email)) || 'R')
            : 'R';
        profileInitial.textContent = String(source).charAt(0).toUpperCase();
    }

    // Update Vue app with collections - use proper reactivity
    if (window.vueApp) {
        window.vueApp.collections = userCollectionsCache || [];
        // Update authStateProxy reactively by copying properties
        if (window.vueApp.authStateProxy) {
            window.vueApp.authStateProxy.authenticated = authState.authenticated;
            window.vueApp.authStateProxy.user = authState.user;
        } else {
            window.vueApp.authStateProxy = authState;
        }
    }

    if (isAuthed) {
        // Pre-build profile menu for instant display
        ensureCollectionsLoaded().then(function(collections) {
            renderUserProfileMenu(Array.isArray(collections) ? collections : []);
        });
    } else {
        closeUserProfileMenu();
    }
    ensurePublicHomeVisible();
}

function closeUserProfileMenu() {
    var profileBtn = document.getElementById('authProfileBtn');
    var profileMenu = document.getElementById('authProfileMenu');
    if (profileBtn) {
        profileBtn.classList.remove('is-open');
        profileBtn.setAttribute('aria-expanded', 'false');
    }
    if (profileMenu) {
        profileMenu.classList.add('d-none');
        profileMenu.setAttribute('aria-hidden', 'true');
        profileMenu.style.top = '';
        profileMenu.style.left = '';
        profileMenu.style.maxHeight = '';
    }
}

function positionUserProfileMenu() {
    var profileBtn = document.getElementById('authProfileBtn');
    var profileMenu = document.getElementById('authProfileMenu');
    if (!profileBtn || !profileMenu || profileMenu.classList.contains('d-none')) {
        return;
    }
    var rect = profileBtn.getBoundingClientRect();
    var viewportWidth = window.innerWidth || document.documentElement.clientWidth || 0;
    var viewportHeight = window.innerHeight || document.documentElement.clientHeight || 0;
    var menuWidth = Math.min(300, Math.max(260, viewportWidth - 24));
    profileMenu.style.width = menuWidth + 'px';
    var menuRect = profileMenu.getBoundingClientRect();
    var left = Math.max(12, Math.min(rect.right - menuRect.width, viewportWidth - menuRect.width - 12));
    var top = rect.bottom + 10;
    var availableBelow = viewportHeight - top - 12;
    if (availableBelow < 220) {
        top = Math.max(12, rect.top - Math.min(menuRect.height || 360, viewportHeight - 24) - 10);
    }
    profileMenu.style.left = left + 'px';
    profileMenu.style.top = top + 'px';
    profileMenu.style.maxHeight = Math.max(180, viewportHeight - top - 12) + 'px';
}

function renderUserProfileMenu(collections) {
    var profileMenu = document.getElementById('authProfileMenu');
    if (!profileMenu) {
        return;
    }
    profileMenu.innerHTML = '';

    var panel = document.createElement('div');
    panel.className = 'profile-dropdown__panel';

    var header = document.createElement('div');
    header.className = 'profile-dropdown__header';
    header.innerHTML =
        '<div class="profile-dropdown__eyebrow">Signed in</div>' +
        '<div class="profile-dropdown__title">' + escapeHtml((authState.user && (authState.user.displayName || authState.user.email)) || 'Account') + '</div>' +
        '<div class="profile-dropdown__subtitle">' + escapeHtml((authState.user && authState.user.email) || '') + '</div>';
    panel.appendChild(header);

    var totalSaved = collections.reduce(function(total, item) {
        return total + (Array.isArray(item.hadithIds) ? item.hadithIds.length : 0);
    }, 0);
    var stats = document.createElement('div');
    stats.className = 'profile-dropdown__stats';
    stats.innerHTML =
        '<div class="profile-dropdown__stat"><span class="profile-dropdown__stat-value">' + collections.length + '</span><span class="profile-dropdown__stat-label">Collections</span></div>' +
        '<div class="profile-dropdown__stat"><span class="profile-dropdown__stat-value">' + totalSaved + '</span><span class="profile-dropdown__stat-label">Saved hadith</span></div>';
    panel.appendChild(stats);

    var list = document.createElement('div');
    list.className = 'profile-dropdown__collections';
    if (!collections.length) {
        var empty = document.createElement('div');
        empty.className = 'profile-dropdown__empty';
        empty.textContent = 'No collections yet. Save a hadith to start building your reading lists.';
        list.appendChild(empty);
    } else {
        // Create "View Collections" dropdown item for mobile
        var collectionsDropdown = document.createElement('div');
        collectionsDropdown.className = 'profile-dropdown__nested';

        var collectionsToggle = document.createElement('div');
        collectionsToggle.className = 'profile-dropdown__toggle';
        collectionsToggle.innerHTML = '<span>View Collection</span><i class="fa fa-angle-down"></i>';

        var collectionsSubmenu = document.createElement('div');
        collectionsSubmenu.className = 'profile-dropdown__submenu';

        collections.forEach(function(collection) {
            var item = document.createElement('div');
            item.className = 'profile-dropdown__submenu-item';
            var count = Array.isArray(collection.hadith_ids) ? collection.hadith_ids.length : 0;
            item.innerHTML =
                '<span class="profile-dropdown__collection-name">' + escapeHtml(collection.name || 'Collection') + '</span>' +
                '<span class="profile-dropdown__collection-meta">' + count + ' hadith</span>';
            item.addEventListener('click', function(event) {
                event.preventDefault();
                closeUserProfileMenu();
                openCollectionPage(collection.id, 1, []);
            });
            collectionsSubmenu.appendChild(item);
        });

        // Toggle submenu on click
        var isOpen = false;
        collectionsToggle.addEventListener('click', function(event) {
            event.preventDefault();
            isOpen = !isOpen;
            collectionsSubmenu.classList.toggle('is-open', isOpen);
            collectionsToggle.classList.toggle('is-open', isOpen);
        });

        collectionsDropdown.appendChild(collectionsToggle);
        collectionsDropdown.appendChild(collectionsSubmenu);
        list.appendChild(collectionsDropdown);
    }

    panel.appendChild(list);

    var footer = document.createElement('div');
    footer.className = 'profile-dropdown__footer';

    var createBtn = document.createElement('button');
    createBtn.type = 'button';
    createBtn.className = 'btn btn-link btn-sm px-0';
    createBtn.innerHTML = '<i class="fa fa-plus"></i> Create collection';
    createBtn.addEventListener('click', function(event) {
        event.preventDefault();
        closeUserProfileMenu();
        openCreateCollectionModal();
    });
    footer.appendChild(createBtn);

    var signOutBtn = document.createElement('button');
    signOutBtn.type = 'button';
    signOutBtn.className = 'btn btn-primary btn-sm collection-picker-modal__submit';
    signOutBtn.textContent = 'Sign Out';
    signOutBtn.addEventListener('click', function(event) {
        event.preventDefault();
        apiJSON('/v1/auth/logout', { method: 'POST' }).then(function() {
            closeUserProfileMenu();
            refreshAuthState();
            showToast('You have been signed out.', 'information');
        });
    });
    footer.appendChild(signOutBtn);

    panel.appendChild(footer);
    profileMenu.appendChild(panel);
}

function toggleUserProfileMenu() {
    var profileMenu = document.getElementById('authProfileMenu');
    var profileBtn = document.getElementById('authProfileBtn');

    // If menu is already open, close it
    if (profileMenu && !profileMenu.classList.contains('d-none')) {
        closeUserProfileMenu();
        return;
    }

    // If not authenticated, show login
    if (!authState.authenticated) {
        openLoginModal();
        return;
    }

    // Show pre-built menu instantly
    if (profileBtn) {
        profileBtn.classList.add('is-open');
        profileBtn.setAttribute('aria-expanded', 'true');
    }
    if (profileMenu) {
        profileMenu.classList.remove('d-none');
        profileMenu.setAttribute('aria-hidden', 'false');
    }
    positionUserProfileMenu();
}

function ensurePublicHomeVisible() {
    if ((getQueryStringValue('q') || '').trim() || isCollectionMode()) {
        return;
    }
    if (welcomeContentLoading) {
        return;
    }
    var welcome = document.getElementById('welcome');
    if (!welcome) {
        return;
    }
    var hasWelcome = welcome.innerHTML && welcome.innerHTML.trim().length > 0;
    if (hasWelcome || welcomeContentInitialized) {
        return;
    }
    displayWelcomeContent();
}

function openFormModal(title, fields, submitLabel, onSubmit, secondaryAction) {
    var wrapper = document.createElement('div');
    wrapper.className = 'auth-modal';

    var closeBtn = document.createElement('button');
    closeBtn.type = 'button';
    closeBtn.className = 'auth-modal-close';
    closeBtn.setAttribute('aria-label', 'Close');
    closeBtn.innerHTML = '&times;';
    closeBtn.addEventListener('click', function() {
        swal.close();
    });
    wrapper.appendChild(closeBtn);

    fields.forEach(function(field) {
        var label = document.createElement('label');
        label.className = 'auth-modal-label';
        label.textContent = field.label;
        var input = document.createElement('input');
        input.className = 'form-control form-control-sm auth-modal-input';
        input.type = field.type || 'text';
        input.placeholder = field.placeholder || '';
        input.value = field.value || '';
        input.id = field.id;
        wrapper.appendChild(label);
        wrapper.appendChild(input);
    });

    var actionRow = document.createElement('div');
    actionRow.className = 'auth-modal-actions';

    var secondaryActions = [];
    if (secondaryAction) {
        secondaryActions = Array.isArray(secondaryAction) ? secondaryAction : [secondaryAction];
    }
    secondaryActions.forEach(function(action) {
        if (!action || typeof action.onClick !== 'function') {
            return;
        }
        var secondaryBtn = document.createElement('button');
        secondaryBtn.type = 'button';
        secondaryBtn.className = 'btn btn-link btn-sm px-0';
        secondaryBtn.textContent = action.label || 'More';
        secondaryBtn.addEventListener('click', function() {
            swal.close();
            action.onClick();
        });
        actionRow.appendChild(secondaryBtn);
    });

    var submitBtn = document.createElement('button');
    submitBtn.type = 'button';
    submitBtn.className = 'btn btn-primary btn-sm';
    submitBtn.textContent = submitLabel;
    submitBtn.addEventListener('click', function() {
        var values = {};
        fields.forEach(function(field) {
            var input = document.getElementById(field.id);
            values[field.key] = input ? input.value.trim() : '';
        });
        onSubmit(values);
    });
    actionRow.appendChild(submitBtn);
    wrapper.appendChild(actionRow);

    swal({
        title: title,
        content: wrapper,
        buttons: false,
        closeOnClickOutside: false,
        closeOnEsc: false,
        className: 'auth-swal-modal'
    });
}

function openRegisterModal() {
    redirectToAuthPage('register');
}

function openLoginModal() {
    redirectToAuthPage('login');
}

function openResetRequestModal() {
    redirectToAuthPage('reset-request');
}

function openResetConfirmModal(rawToken) {
    if (rawToken) {
        redirectToAuthPage('reset-confirm', { reset_token: rawToken });
    }
}

function handleAuthQueryActions() {
    var verifyToken = getQueryStringValue('verify_token');
    var resetToken = getQueryStringValue('reset_token');
    if (verifyToken || resetToken) {
        var params = {};
        if (verifyToken) {
            params.verify_token = verifyToken;
        }
        if (resetToken) {
            params.reset_token = resetToken;
        }
        var mode = resetToken ? 'reset-confirm' : 'login';
        redirectToAuthPage(mode, params, { skipReturn: true });
    }
}

function removeQueryParam(param) {
    var url = new URL(window.location.href);
    if (!url.searchParams.has(param)) {
        return;
    }
    url.searchParams.delete(param);
    window.history.replaceState({}, '', url.toString());
}

function ensureCollectionsLoaded() {
    if (!authState.authenticated) {
        return Promise.resolve([]);
    }
    return apiJSON('/v1/collections', { method: 'GET' })
        .then(function(resp) {
            userCollectionsCache = (resp.data && resp.data.collections) ? resp.data.collections : [];
            renderCollectionsSection(userCollectionsCache);
            return userCollectionsCache;
        })
        .catch(function() {
            userCollectionsCache = [];
            renderCollectionsSection([]);
            return [];
        });
}

function createCollectionModalShell(title, subtitle) {
    var wrapper = document.createElement('div');
    wrapper.className = 'collection-picker-modal';

    var closeBtn = document.createElement('button');
    closeBtn.type = 'button';
    closeBtn.className = 'auth-modal-close';
    closeBtn.setAttribute('aria-label', 'Close');
    closeBtn.innerHTML = '&times;';
    closeBtn.addEventListener('click', function() {
        swal.close();
    });
    wrapper.appendChild(closeBtn);

    var titleNode = document.createElement('div');
    titleNode.className = 'collection-picker-modal__title';
    titleNode.textContent = title;
    wrapper.appendChild(titleNode);

    if (subtitle) {
        var subtitleNode = document.createElement('div');
        subtitleNode.className = 'collection-picker-modal__subtitle';
        subtitleNode.textContent = subtitle;
        wrapper.appendChild(subtitleNode);
    }

    return wrapper;
}

function openCollectionNameModal(options) {
    var opts = options || {};
    var wrapper = createCollectionModalShell(opts.title || 'Create Collection', opts.subtitle || '');
    var input = document.createElement('input');
    input.className = 'form-control auth-modal-input collection-picker-modal__input';
    input.type = 'text';
    input.placeholder = opts.placeholder || 'Collection name';
    input.value = opts.defaultValue || '';
    input.id = 'collectionModalNameInput';
    wrapper.appendChild(input);

    var actions = document.createElement('div');
    actions.className = 'collection-picker-modal__actions';

    var submitBtn = document.createElement('button');
    submitBtn.type = 'button';
    submitBtn.className = 'btn btn-primary btn-sm collection-picker-modal__submit';
    submitBtn.textContent = opts.submitLabel || 'Save';
    submitBtn.addEventListener('click', function() {
        opts.onSubmit((input.value || '').trim());
    });
    actions.appendChild(submitBtn);
    wrapper.appendChild(actions);

    swal({
        content: wrapper,
        buttons: false,
        closeOnClickOutside: false,
        closeOnEsc: true,
        className: 'auth-swal-modal'
    });
}

function openCollectionPickerModal(hadithId, collections) {
    var wrapper = createCollectionModalShell(
        'Save Hadith',
        'Choose an existing collection or create a new one.'
    );

    var label = document.createElement('label');
    label.className = 'auth-modal-label';
    label.textContent = 'Collection';
    wrapper.appendChild(label);

    // Create a select dropdown for existing collections
    var select = document.createElement('select');
    select.className = 'form-control auth-modal-input collection-picker-modal__input';
    select.id = 'collectionPickerSelect';
    select.style.marginBottom = '0.75rem';

    // Add "New Collection" option at the top
    var newOption = document.createElement('option');
    newOption.value = '';
    newOption.textContent = '+ New Collection';
    select.appendChild(newOption);

    // Add existing collections
    (Array.isArray(collections) ? collections : []).forEach(function(collection) {
        var option = document.createElement('option');
        option.value = collection.name || 'Collection';
        var count = Array.isArray(collection.hadith_ids) ? collection.hadith_ids.length : 0;
        option.textContent = (collection.name || 'Collection') + ' (' + count + ' hadith)';
        select.appendChild(option);
    });
    wrapper.appendChild(select);

    // Container for new collection name input (hidden by default)
    var newCollectionContainer = document.createElement('div');
    newCollectionContainer.id = 'newCollectionContainer';
    newCollectionContainer.style.display = 'none';
    newCollectionContainer.style.marginBottom = '0.75rem';

    var newLabel = document.createElement('label');
    newLabel.className = 'auth-modal-label';
    newLabel.textContent = 'New Collection Name';
    newLabel.style.fontSize = '0.85rem';
    newCollectionContainer.appendChild(newLabel);

    var newInput = document.createElement('input');
    newInput.className = 'form-control auth-modal-input collection-picker-modal__input';
    newInput.type = 'text';
    newInput.placeholder = 'New Collection';
    newInput.id = 'newCollectionInput';
    newCollectionContainer.appendChild(newInput);
    wrapper.appendChild(newCollectionContainer);

    var footer = document.createElement('div');
    footer.className = 'collection-picker-modal__actions';

    var manageBtn = document.createElement('button');
    manageBtn.type = 'button';
    manageBtn.className = 'btn btn-link btn-sm px-0';
    manageBtn.textContent = 'Manage collections';
    manageBtn.addEventListener('click', function() {
        swal.close();
        openUserProfileModal();
    });
    footer.appendChild(manageBtn);

    var submitBtn = document.createElement('button');
    submitBtn.type = 'button';
    submitBtn.className = 'btn btn-primary btn-sm collection-picker-modal__submit';
    submitBtn.textContent = 'Save Hadith';
    submitBtn.addEventListener('click', function() {
        var selectedValue = select.value;
        var newCollectionName = (newInput.value || '').trim();

        var collectionName;
        if (selectedValue === '' && newCollectionName) {
            // User selected "New Collection" and entered a name
            collectionName = newCollectionName;
        } else if (selectedValue !== '') {
            // User selected an existing collection
            collectionName = selectedValue;
        } else {
            // No selection made, use default
            collectionName = 'New Collection';
        }

        apiJSON('/v1/collections/quick-save', {
            method: 'POST',
            body: JSON.stringify({ hadithId: hadithId, collectionName: collectionName })
        }).then(function(resp) {
            if (!resp.ok || !resp.data.ok) {
                swal('Save failed', (resp.data && resp.data.message) || 'Could not save hadith.', 'error');
                return;
            }
            swal.close();
            loadAndRenderCollections(false);
            showToast('Saved to ' + (((resp.data.collection && resp.data.collection.name) || collectionName)) + '.', 'success');
        });
    });
    footer.appendChild(submitBtn);
    wrapper.appendChild(footer);

    // Show/hide new collection input based on dropdown selection
    select.addEventListener('change', function() {
        if (select.value === '') {
            // "New Collection" selected
            newCollectionContainer.style.display = 'block';
            newInput.focus();
        } else {
            newCollectionContainer.style.display = 'none';
        }
    });

    swal({
        content: wrapper,
        buttons: false,
        closeOnClickOutside: false,
        closeOnEsc: true,
        className: 'auth-swal-modal'
    });
}

function openUserProfileModal() {
    if (!authState.authenticated) {
        openLoginModal();
        return;
    }
    ensureCollectionsLoaded().then(function(collections) {
        var profileBtn = document.getElementById('authProfileBtn');
        var profileMenu = document.getElementById('authProfileMenu');
        renderUserProfileMenu(Array.isArray(collections) ? collections : []);
        if (profileBtn) {
            profileBtn.classList.add('is-open');
            profileBtn.setAttribute('aria-expanded', 'true');
        }
        if (profileMenu) {
            profileMenu.classList.remove('d-none');
            profileMenu.setAttribute('aria-hidden', 'false');
        }
        positionUserProfileMenu();
    });
}

function openCreateCollectionModal() {
    if (!authState.authenticated) {
        openLoginModal();
        return;
    }
    openCollectionNameModal({
        title: 'Create Collection',
        subtitle: 'Create a reading list you can revisit from your profile or the home page.',
        placeholder: 'e.g. Purification Narrations',
        submitLabel: 'Create',
        onSubmit: function(name) {
            apiJSON('/v1/collections', {
                method: 'POST',
                body: JSON.stringify({ name: name })
            }).then(function(resp) {
                if (!resp.ok || !resp.data.ok) {
                    swal('Unable to create', (resp.data && resp.data.message) || 'Could not create collection.', 'error');
                    return;
                }
                swal.close();
                loadAndRenderCollections(false);
                showToast('Collection created.', 'success');
            });
        }
    });
}

function openSaveHadithModal(hadithId) {
    if (!authState.authenticated) {
        showToast('Sign in to save hadith to your collections.', 'information');
        openLoginModal();
        return;
    }
    ensureCollectionsLoaded().then(function(collections) {
        openCollectionPickerModal(hadithId, collections);
    }).catch(function() {
        swal('Save unavailable', 'Unable to load your collections right now.', 'error');
    });
}

function openCollectionManageModal() {
    if (!authState.authenticated) {
        openLoginModal();
        return;
    }
    ensureCollectionsLoaded().then(function(collections) {
        if (!collections || !collections.length) {
            openCreateCollectionModal();
            return;
        }
        // Open the full collections modal
        openUserProfileModal();
    }).catch(function() {
        swal('Collections unavailable', 'Unable to load your collections right now.', 'error');
    });
}

var hadithEditorScalarFields = [
    { key: 'book', label: 'Book' },
    { key: 'number', label: 'Number' },
    { key: 'edition', label: 'Edition' },
    { key: 'source', label: 'Source' },
    { key: 'publisher', label: 'Publisher' },
    { key: 'volume', label: 'Volume' },
    { key: 'part', label: 'Part' },
    { key: 'section', label: 'Section' },
    { key: 'chapter', label: 'Chapter' }
];
var hadithEditorKnownKeys = [
    '_id',
    'book',
    'number',
    'edition',
    'source',
    'publisher',
    'volume',
    'part',
    'section',
    'chapter',
    'english',
    'arabic',
    'notes',
    'tags',
    'topic_tags',
    'gradings',
    'related',
    'history'
];

function currentUserCanEditHadith() {
    return !!(authState.authenticated && authState.user && authState.user.canEditHadith);
}

function uniqueArray(values) {
    return (Array.isArray(values) ? values : []).filter(function(value, index, items) {
        return items.indexOf(value) === index;
    });
}

function fetchTaxonomyMap() {
    return fetch('/taxonomy.json')
        .then(function(resp) { return resp.json(); })
        .then(function(data) {
            var map = {};
            (Array.isArray(data) ? data : []).forEach(function(item) {
                if (!item || !item.slug) {
                    return;
                }
                map[item.slug] = item;
            });
            return map;
        });
}

function createHadithEditorRowEditor(title, subtitle, fields, items) {
    var section = document.createElement('section');
    section.className = 'hadith-editor-modal__group';

    var head = document.createElement('div');
    head.className = 'hadith-editor-modal__repeatable-head';
    head.innerHTML =
        '<div>' +
            '<div class="hadith-editor-modal__section-title">' + escapeHtml(title) + '</div>' +
            (subtitle ? ('<div class="hadith-editor-modal__section-copy">' + escapeHtml(subtitle) + '</div>') : '') +
        '</div>';

    var addBtn = document.createElement('button');
    addBtn.type = 'button';
    addBtn.className = 'btn btn-outline-dark btn-sm';
    addBtn.textContent = 'Add';
    head.appendChild(addBtn);
    section.appendChild(head);

    var list = document.createElement('div');
    list.className = 'hadith-editor-modal__repeatable-list';
    section.appendChild(list);

    function appendRow(item) {
        var row = document.createElement('div');
        row.className = 'hadith-editor-modal__repeatable-row';
        fields.forEach(function(field) {
            var wrap = document.createElement('label');
            wrap.className = 'hadith-editor-modal__field';
            wrap.innerHTML = '<span class="hadith-editor-modal__label">' + escapeHtml(field.label) + '</span>';
            var input = document.createElement(field.multiline ? 'textarea' : 'input');
            input.className = 'form-control hadith-editor-modal__input';
            if (!field.multiline) {
                input.type = 'text';
            } else {
                input.rows = field.rows || 2;
            }
            input.dataset.editorField = field.key;
            input.value = (item && item[field.key]) ? String(item[field.key]) : '';
            wrap.appendChild(input);
            row.appendChild(wrap);
        });
        var actions = document.createElement('div');
        actions.className = 'hadith-editor-modal__row-actions';
        var removeBtn = document.createElement('button');
        removeBtn.type = 'button';
        removeBtn.className = 'btn btn-outline-danger btn-sm';
        removeBtn.textContent = 'Remove';
        removeBtn.addEventListener('click', function() {
            row.remove();
        });
        actions.appendChild(removeBtn);
        row.appendChild(actions);
        list.appendChild(row);
    }

    addBtn.addEventListener('click', function() {
        appendRow({});
    });

    if (Array.isArray(items) && items.length) {
        items.forEach(function(item) {
            appendRow(item || {});
        });
    } else {
        appendRow({});
    }

    return {
        element: section,
        value: function() {
            return Array.prototype.slice.call(list.querySelectorAll('.hadith-editor-modal__repeatable-row'))
                .map(function(row) {
                    var entry = {};
                    Array.prototype.slice.call(row.querySelectorAll('[data-editor-field]')).forEach(function(input) {
                        var key = input.dataset.editorField;
                        var value = (input.value || '').trim();
                        if (value) {
                            entry[key] = value;
                        }
                    });
                    return entry;
                })
                .filter(function(entry) {
                    return Object.keys(entry).length > 0;
                });
        }
    };
}

function normalizeHadithEditorTags(rawTags) {
    return (Array.isArray(rawTags) ? rawTags : []).map(function(tag) {
        if (tag == null) {
            return '';
        }
        if (typeof tag === 'string') {
            return tag.trim();
        }
        try {
            return JSON.stringify(tag);
        } catch (e) {
            return String(tag).trim();
        }
    }).filter(function(tag) {
        return !!tag;
    });
}

function parseHadithEditorTags(values) {
    var tags = [];
    (Array.isArray(values) ? values : []).forEach(function(raw) {
        var value = (raw || '').trim();
        if (!value) {
            return;
        }
        if ((value.charAt(0) === '{' && value.charAt(value.length - 1) === '}')
                || (value.charAt(0) === '[' && value.charAt(value.length - 1) === ']')) {
            try {
                tags.push(JSON.parse(value));
                return;
            } catch (e) {
                // fall through to plain string
            }
        }
        tags.push(value);
    });
    return tags;
}

function openHadithEditorModal(options) {
    var opts = options || {};
    var narration = opts.narration || {};
    var taxonomy = opts.taxonomy || {};
    var wrapper = createCollectionModalShell(
        'Edit Hadith',
        'Update the narration fields below. Saving replaces the stored document immediately.'
    );
    wrapper.classList.add('hadith-editor-modal');

    var meta = document.createElement('div');
    meta.className = 'hadith-editor-modal__meta';
    meta.innerHTML =
        '<span class="hadith-editor-modal__meta-id">ID ' + escapeHtml((narration._id || '').toString()) + '</span>' +
        '<span>' + escapeHtml(((narration.book || 'Narration') + (narration.number ? (' #' + narration.number) : '')).trim()) + '</span>';
    wrapper.appendChild(meta);

    var grid = document.createElement('div');
    grid.className = 'hadith-editor-modal__grid';
    var scalarInputs = {};
    hadithEditorScalarFields.forEach(function(field) {
        var label = document.createElement('label');
        label.className = 'hadith-editor-modal__field';
        label.innerHTML = '<span class="hadith-editor-modal__label">' + escapeHtml(field.label) + '</span>';
        var input = document.createElement('input');
        input.type = 'text';
        input.className = 'form-control hadith-editor-modal__input';
        input.value = narration[field.key] ? String(narration[field.key]) : '';
        label.appendChild(input);
        grid.appendChild(label);
        scalarInputs[field.key] = input;
    });
    wrapper.appendChild(grid);

    function appendTextareaField(labelText, key, rows) {
        var label = document.createElement('label');
        label.className = 'hadith-editor-modal__field hadith-editor-modal__field--full';
        label.innerHTML = '<span class="hadith-editor-modal__label">' + escapeHtml(labelText) + '</span>';
        var input = document.createElement('textarea');
        input.className = 'form-control hadith-editor-modal__input hadith-editor-modal__textarea';
        input.rows = rows;
        input.value = narration[key] ? String(narration[key]) : '';
        label.appendChild(input);
        wrapper.appendChild(label);
        scalarInputs[key] = input;
    }

    var tagsField = document.createElement('label');
    tagsField.className = 'hadith-editor-modal__field hadith-editor-modal__field--full';
    tagsField.innerHTML = '<span class="hadith-editor-modal__label">Tags</span><span class="hadith-editor-modal__help">Free-form tags. Paste a JSON object as one tag only if you need a legacy structured value.</span>';
    var tagsSelect = document.createElement('select');
    tagsSelect.multiple = true;
    normalizeHadithEditorTags(narration.tags).forEach(function(tag) {
        var option = document.createElement('option');
        option.value = tag;
        option.textContent = tag;
        option.selected = true;
        tagsSelect.appendChild(option);
    });
    tagsField.appendChild(tagsSelect);
    wrapper.appendChild(tagsField);

    var topicField = document.createElement('label');
    topicField.className = 'hadith-editor-modal__field hadith-editor-modal__field--full';
    topicField.innerHTML = '<span class="hadith-editor-modal__label">Topic Tags</span><span class="hadith-editor-modal__help">Controlled tags loaded from taxonomy.json.</span>';
    var topicSelect = document.createElement('select');
    topicSelect.multiple = true;
    var taxonomyEntries = Object.keys(taxonomy).map(function(slug) {
        return taxonomy[slug];
    }).sort(function(left, right) {
        var leftCategory = (left && left.category) ? left.category : 'other';
        var rightCategory = (right && right.category) ? right.category : 'other';
        if (leftCategory !== rightCategory) {
            return leftCategory.localeCompare(rightCategory);
        }
        return ((left && left.en) || left.slug).localeCompare(((right && right.en) || right.slug));
    });
    var groups = {};
    taxonomyEntries.forEach(function(entry) {
        var category = (entry && entry.category) ? entry.category : 'other';
        if (!groups[category]) {
            var optgroup = document.createElement('optgroup');
            optgroup.label = category;
            groups[category] = optgroup;
            topicSelect.appendChild(optgroup);
        }
        var option = document.createElement('option');
        option.value = entry.slug;
        option.textContent = entry.en ? (entry.en + ' (' + entry.slug + ')') : entry.slug;
        option.selected = (Array.isArray(narration.topic_tags) ? narration.topic_tags : []).indexOf(entry.slug) >= 0;
        groups[category].appendChild(option);
    });
    (Array.isArray(narration.topic_tags) ? narration.topic_tags : []).forEach(function(slug) {
        if (!slug || taxonomy[slug]) {
            return;
        }
        var option = document.createElement('option');
        option.value = slug;
        option.textContent = slug;
        option.selected = true;
        topicSelect.appendChild(option);
    });
    topicField.appendChild(topicSelect);
    wrapper.appendChild(topicField);

    appendTextareaField('English', 'english', 7);
    appendTextareaField('Arabic', 'arabic', 7);
    appendTextareaField('Notes', 'notes', 4);

    var historyField = document.createElement('label');
    historyField.className = 'hadith-editor-modal__field hadith-editor-modal__field--full';
    historyField.innerHTML = '<span class="hadith-editor-modal__label">History</span><span class="hadith-editor-modal__help">One line per entry.</span>';
    var historyInput = document.createElement('textarea');
    historyInput.className = 'form-control hadith-editor-modal__input hadith-editor-modal__textarea';
    historyInput.rows = 4;
    historyInput.value = (Array.isArray(narration.history) ? narration.history : []).join('\n');
    historyField.appendChild(historyInput);
    wrapper.appendChild(historyField);

    var gradingEditor = createHadithEditorRowEditor(
        'Gradings',
        'Use one row per grading.',
        [
            { key: 'grader', label: 'Grader' },
            { key: 'grading', label: 'Grading' },
            { key: 'rationale', label: 'Rationale', multiline: true, rows: 2 }
        ],
        narration.gradings
    );
    wrapper.appendChild(gradingEditor.element);

    var relatedEditor = createHadithEditorRowEditor(
        'Related Links',
        'Optional related resources for this narration.',
        [
            { key: 'title', label: 'Title' },
            { key: 'url', label: 'URL' },
            { key: 'description', label: 'Description', multiline: true, rows: 2 }
        ],
        narration.related
    );
    wrapper.appendChild(relatedEditor.element);

    var extraField = document.createElement('label');
    extraField.className = 'hadith-editor-modal__field hadith-editor-modal__field--full';
    extraField.innerHTML = '<span class="hadith-editor-modal__label">Additional JSON Properties</span><span class="hadith-editor-modal__help">Only for non-standard fields not covered above.</span>';
    var extraInput = document.createElement('textarea');
    extraInput.className = 'form-control hadith-editor-modal__input hadith-editor-modal__textarea hadith-editor-modal__code';
    extraInput.rows = 8;
    var extraProps = {};
    Object.keys(narration || {}).forEach(function(key) {
        if (hadithEditorKnownKeys.indexOf(key) >= 0) {
            return;
        }
        extraProps[key] = narration[key];
    });
    extraInput.value = Object.keys(extraProps).length ? JSON.stringify(extraProps, null, 2) : '{}';
    extraField.appendChild(extraInput);
    wrapper.appendChild(extraField);

    var status = document.createElement('div');
    status.className = 'hadith-editor-modal__status';
    wrapper.appendChild(status);

    var actions = document.createElement('div');
    actions.className = 'collection-picker-modal__actions hadith-editor-modal__actions';
    var cancelBtn = document.createElement('button');
    cancelBtn.type = 'button';
    cancelBtn.className = 'btn btn-link btn-sm px-0';
    cancelBtn.textContent = 'Cancel';
    cancelBtn.addEventListener('click', function() {
        if (!saveBtn.disabled) {
            swal.close();
        }
    });
    actions.appendChild(cancelBtn);

    var saveBtn = document.createElement('button');
    saveBtn.type = 'button';
    saveBtn.className = 'btn btn-primary btn-sm collection-picker-modal__submit';
    saveBtn.textContent = 'Save Changes';
    actions.appendChild(saveBtn);
    wrapper.appendChild(actions);

    swal({
        content: wrapper,
        buttons: false,
        closeOnClickOutside: false,
        closeOnEsc: true,
        className: 'auth-swal-modal'
    });

    var tagsControl = null;
    var topicControl = null;
    if (typeof TomSelect !== 'undefined') {
        tagsControl = new TomSelect(tagsSelect, {
            plugins: { remove_button: { title: 'Remove' } },
            persist: false,
            create: true,
            hideSelected: true,
            maxOptions: 200
        });
        topicControl = new TomSelect(topicSelect, {
            plugins: { remove_button: { title: 'Remove' } },
            persist: true,
            create: false,
            hideSelected: true,
            maxOptions: 400,
            searchField: ['text', 'value']
        });
    }

    saveBtn.addEventListener('click', function() {
        if (saveBtn.disabled || typeof opts.onSave !== 'function') {
            return;
        }
        var extraPropsPayload = {};
        status.textContent = '';
        status.classList.remove('is-error');
        try {
            extraPropsPayload = JSON.parse((extraInput.value || '{}').trim() || '{}');
        } catch (err) {
            status.textContent = 'Additional JSON must be valid JSON.';
            status.classList.add('is-error');
            return;
        }
        if (!extraPropsPayload || Array.isArray(extraPropsPayload) || typeof extraPropsPayload !== 'object') {
            status.textContent = 'Additional JSON must be an object.';
            status.classList.add('is-error');
            return;
        }
        hadithEditorKnownKeys.forEach(function(key) {
            delete extraPropsPayload[key];
        });
        delete extraPropsPayload.id;

        var payload = {};
        hadithEditorScalarFields.forEach(function(field) {
            var value = (scalarInputs[field.key].value || '').trim();
            payload[field.key] = value || null;
        });
        payload.english = (scalarInputs.english.value || '').trim() || null;
        payload.arabic = (scalarInputs.arabic.value || '').trim() || null;
        payload.notes = (scalarInputs.notes.value || '').trim() || null;
        payload.tags = parseHadithEditorTags(tagsControl ? tagsControl.items.slice() : Array.prototype.slice.call(tagsSelect.options).filter(function(option) {
            return option.selected;
        }).map(function(option) {
            return option.value;
        }));
        payload.topic_tags = uniqueArray((topicControl ? topicControl.items.slice() : Array.prototype.slice.call(topicSelect.options).filter(function(option) {
            return option.selected;
        }).map(function(option) {
            return option.value;
        })).map(function(tag) {
            return (tag || '').trim();
        }).filter(function(tag) {
            return !!tag;
        }));
        payload.history = (historyInput.value || '').split(/\r?\n/).map(function(line) {
            return line.trim();
        }).filter(function(line) {
            return !!line;
        });
        payload.gradings = gradingEditor.value();
        payload.related = relatedEditor.value();
        Object.keys(extraPropsPayload).forEach(function(key) {
            payload[key] = extraPropsPayload[key];
        });

        saveBtn.disabled = true;
        cancelBtn.disabled = true;
        status.textContent = 'Saving changes...';
        Promise.resolve(opts.onSave(payload))
            .then(function() {
                swal.close();
            })
            .catch(function(err) {
                status.textContent = err && err.message ? err.message : 'Unable to save this narration.';
                status.classList.add('is-error');
                saveBtn.disabled = false;
                cancelBtn.disabled = false;
            });
    });
}

function loadAndRenderCollections(forceModal) {
    if (!authState.authenticated) {
        userCollectionsCache = [];
        renderCollectionsSection([]);
        return Promise.resolve([]);
    }
    return ensureCollectionsLoaded().then(function(collections) {
        if (forceModal) {
            openUserProfileModal();
        }
        return collections;
    });
}

function renderCollectionsSection(collections) {
    var section = document.getElementById('userCollectionsSection');
    var list = document.getElementById('collectionsList');
    var empty = document.getElementById('collectionsEmpty');
    if (!section || !list || !empty) {
        return;
    }
    if (!authState.authenticated) {
        section.classList.add('d-none');
        list.innerHTML = '';
        empty.classList.remove('d-none');
        return;
    }
    section.classList.remove('d-none');
    list.innerHTML = '';
    if (!collections || !collections.length) {
        empty.classList.remove('d-none');
        return;
    }
    empty.classList.add('d-none');
    collections.forEach(function(collection) {
        var card = document.createElement('article');
        card.className = 'collection-card';
        var count = (collection.hadith_ids && collection.hadith_ids.length) ? collection.hadith_ids.length : 0;
        var updatedText = collection.updatedAt ? new Date(collection.updatedAt).toLocaleDateString() : '';
        card.innerHTML =
            '<div class="collection-title">' + escapeHtml(collection.name || 'Collection') + '</div>' +
            '<div class="collection-meta">' + count + ' hadith · updated ' + escapeHtml(updatedText) + '</div>';

        var actions = document.createElement('div');
        actions.className = 'collection-actions';

        var viewBtn = document.createElement('button');
        viewBtn.type = 'button';
        viewBtn.className = 'btn btn-outline-dark btn-sm';
        viewBtn.textContent = 'Open';
        viewBtn.addEventListener('click', function() {
            openCollectionPage(collection.id, 1, []);
        });
        actions.appendChild(viewBtn);

        var deleteBtn = document.createElement('button');
        deleteBtn.type = 'button';
        deleteBtn.className = 'btn btn-outline-danger btn-sm';
        deleteBtn.textContent = 'Delete';
        deleteBtn.addEventListener('click', function() {
            deleteCollection(collection.id);
        });
        actions.appendChild(deleteBtn);

        card.appendChild(actions);
        list.appendChild(card);
    });
}

function deleteCollection(collectionId, onSuccess) {
    if (!collectionId) {
        return;
    }
    apiJSON('/v1/collections/' + encodeURIComponent(collectionId), { method: 'DELETE' })
        .then(function(resp) {
            if (!resp.ok || !resp.data.ok) {
                swal('Delete failed', (resp.data && resp.data.message) || 'Unable to delete collection.', 'error');
                return;
            }
            loadAndRenderCollections(false);
            if (typeof onSuccess === 'function') {
                onSuccess();
            }
        });
}

function initSelect2(select2_id) {
    var selectEl = document.getElementById(select2_id);
    if (!selectEl) {
        return null;
    }
    if (selectEl.tomselect) {
        selectEl.tomselect.destroy();
    }
    searchSelectControl = new TomSelect(selectEl, {
        plugins: {
            remove_button: {
                title: 'Remove'
            }
        },
        persist: false,
        create: true,
        hideSelected: true,
        closeAfterSelect: true,
        dropdownParent: 'body',
        hidePlaceholder: true,
        maxItems: null,
        placeholder: '"Household of the prophet"  Ahlulbayt  "اهل البيت"',
        loadThrottle: 250,
        load: function(query, callback) {
            var term = (query || '').trim();
            if (term.length < 2) {
                return callback();
            }
            if (term.indexOf(' ') >= 0) {
                return callback();
            }
            var url = '/v1/terms/top?term=' + encodeURIComponent(term.replace(/["']/g, ""));
            fetch(url)
                .then(function(response) { return response.json(); })
                .then(function(data) {
                    var results = data.map(function(item) {
                        return { value: item, text: item };
                    });
                    callback(results);
                })
                .catch(function() {
                    callback();
                });
        },
        onItemAdd: function() {
            if (!suppressSearchGlow) {
                indicatePendingSearchTerms();
            }
            if (this && this.clearTextbox) {
                this.clearTextbox();
            } else if (this && this.setTextboxValue) {
                this.setTextboxValue('');
            }
            updateSearchPlaceholder(this);
            resetSearchSuggestionDropdown(this);
        },
        onItemRemove: function() {
            indicatePendingSearchTerms();
            updateSearchPlaceholder(this);
        },
        onInitialize: function() {
            updateSearchPlaceholder(this);
        }
    });
    return searchSelectControl;
}

function isArabic(text) {
    var pattern = /[\u0600-\u06FF\u0750-\u077F]/;
    result = pattern.test(text);
    return result;
}

function strip(html) {
    var tmp = document.createElement("DIV");
    tmp.innerHTML = html;
    return tmp.textContent || tmp.innerText || "";
}

function isNumeric(obj) {
    return !isNaN(obj - parseFloat(obj));
}

function getQueryStringValue(key) {
    try {
        var params = new URLSearchParams(window.location.search || '');
        return params.get(key) || '';
    } catch (e) {
        return '';
    }
}

function splitQuery(query) {
    var text = String(query || '').trim();
    if (!text) {
        return [];
    }
    var termsArr = [];
    var current = '';
    var inQuote = false;
    for (var i = 0; i < text.length; i++) {
        var ch = text.charAt(i);
        if (ch === '"') {
            inQuote = !inQuote;
            current += ch;
            continue;
        }
        if (!inQuote && /\s/.test(ch)) {
            if (current.trim()) {
                termsArr.push(current.trim());
                current = '';
            }
            continue;
        }
        current += ch;
    }
    if (current.trim()) {
        termsArr.push(current.trim());
    }
    return termsArr;
}

function isCharacterKeyPress(evt) {
    if (typeof evt.which == "undefined") {
        // This is IE, which only fires keypress events for printable keys
        return true;
    } else if (typeof evt.which == "number" && evt.which > 0) {
        // In other browsers except old versions of WebKit, evt.which is
        // only greater than zero if the keypress is a printable key.
        // We need to filter out backspace and ctrl/alt/meta key combinations
        return !evt.ctrlKey && !evt.metaKey && !evt.altKey && evt.which != 8;
    }
    return false;
}

function isArabic(text) {
    var pattern = /[\u0600-\u06FF\u0750-\u077F]/;
    result = pattern.test(text);
    return result;
}

function strip(html) {
    var tmp = document.createElement("DIV");
    tmp.innerHTML = html;
    return tmp.textContent || tmp.innerText || "";
}

function isNumeric(obj) {
    return !isNaN(obj - parseFloat(obj));
}

String.prototype.replaceAll = function(search, replacement) {
    var target = this;
    return target.replace(new RegExp(search, 'g'), replacement);
};

function getQueryStringValue(key) {
    try {
        var params = new URLSearchParams(window.location.search || '');
        return params.get(key) || '';
    } catch (e) {
        return '';
    }
}


// processes the user's query by redirecting with query parameter.
function submitSearchQuery() {
    clearPendingSearchTermsIndicator();
    // searchTerms corresponds to the main search bar
    var selectedTerms = getSelectedSearchTerms();
    var pendingTerms = getPendingSearchTerms();
    if (pendingTerms.length) {
        commitPendingSearchTermsToControl(pendingTerms);
    }
    var terms = mergeSearchTerms(selectedTerms, pendingTerms.length ? pendingTerms : getSelectedSearchTerms());
    var queryState = extractQueryState(getQueryStringValue('q') || '');
    if (!terms.length && !queryState.hasScope) {
        return;
    }
    var query = buildScopedQuery(terms, queryState.scopeFilters);
    if (!query) {
        return;
    }
    var nextEntry = queryState.hasScope ? 'browse' : resolveEntryContextParam();
    redirectToSearchResult(query, '', '', '', '', searchMatchMode, nextEntry);
}

function redirectToSearchResult(query, page, sortFields, mode, focusId, matchMode, entryContext) {
    var queryParamString = '?q=' + encodeURIComponent(query.trim())
    if (sortFields) {
        queryParamString += '&sort_fields=' + encodeURIComponent(sortFields.trim())
    }
    if (page) {
        queryParamString += '&page=' + page;
    }
    var modeValue = typeof mode === 'string' ? mode : resolveModeParam();
    if (modeValue) {
        queryParamString += '&mode=' + encodeURIComponent(modeValue);
    }
    var matchValue = normalizeSearchMatchMode(typeof matchMode === 'string' ? matchMode : resolveSearchMatchModeParam());
    queryParamString += '&match_mode=' + encodeURIComponent(matchValue);
    var entryValue = (entryContext || resolveEntryContextParam() || '').toLowerCase();
    if (entryValue === 'browse') {
        queryParamString += '&entry=browse';
    }
    if (focusId) {
        queryParamString += '&focus_id=' + encodeURIComponent(String(focusId).trim());
    }
    window.location.href = window.location.protocol + "//" +
        window.location.host + window.location.pathname + queryParamString;
}

function buildCollectionViewUrl(collectionId, page, topicTags) {
    if (!collectionId) {
        return window.location.protocol + "//" + window.location.host + window.location.pathname;
    }
    var url = new URL(window.location.pathname, window.location.origin);
    url.searchParams.set('collection_id', String(collectionId).trim());
    if (page && Number(page) > 1) {
        url.searchParams.set('page', String(page));
    }
    (Array.isArray(topicTags) ? topicTags : []).forEach(function(tag) {
        if (tag) {
            url.searchParams.append('topic_tags', String(tag).trim());
        }
    });
    return url.toString();
}

function openCollectionPage(collectionId, page, topicTags) {
    window.location.href = buildCollectionViewUrl(collectionId, page, topicTags);
}

function domSafeHadithId(id) {
    if (!id) {
        return '';
    }
    return String(id).replace(/[^a-zA-Z0-9_-]/g, '-');
}

function showBookBlurb(bookName) {
    for (blurb in bookBlurbs) {
        if (strip(bookName).toUpperCase().includes(bookBlurbs[blurb].book.toUpperCase())) {
            var wrapper = document.createElement("div");
            wrapper.innerHTML = bookBlurbs[blurb].blurb;
            swal({
                content: wrapper
            });
        }
    }
}

function displayWelcomeContent() {
    if (isCollectionMode()) {
        return;
    }
    if (welcomeContentLoading) {
        return;
    }
    welcomeContentLoading = true;
    var queryBar = document.getElementById('queryBar');
    if (queryBar) {
        queryBar.classList.remove('is-hidden');
        queryBar.classList.add('is-visible');
        queryBar.classList.remove('home-search');
    }
    document.getElementById('hadithView').innerHTML = '';
    vueApp = new Vue({
        el: '#hadithView'
    });
    $("#welcome").load("/welcome.html?v=16", function(responseData, statusText) {
        welcomeContentLoading = false;
        welcomeContentInitialized = statusText === 'success';
        var queryBar = document.getElementById('queryBar');
        var heroSlot = document.getElementById('hero-search-slot');
        if (queryBar) {
            if (heroSlot) {
                heroSlot.appendChild(queryBar);
                queryBar.classList.add('home-search');
            } else {
                queryBar.classList.remove('home-search');
            }
            queryBar.classList.remove('is-hidden');
            queryBar.classList.add('is-visible');
        }
        if (statusText !== 'success') {
            return;
        }
        // initialize search bar
        initSelect2('searchTerms');
        // setup select handler
        select2SelectHandler('searchTerms');
        // setup enter key listener
        setupSelect2EnterKeyListener('searchTerms');
        // refresh auth bindings for dynamically loaded welcome content
        initAuthUI();
        if (authState.authenticated) {
            loadAndRenderCollections(false);
        }
        // load recent updates
        loadRecentUpdates();
        // load browse data
        loadBrowseBooks();
    });
}

function indicatePendingSearchTerms() {
    toggleSearchBarPendingState(true);
    $("[id^=searchBtn]").addClass("button-glow");
}

function clearPendingSearchTermsIndicator() {
    toggleSearchBarPendingState(false);
    $("[id^=searchBtn]").removeClass("button-glow");
}

function indicateActionButtonPending(buttonId) {
    if (!buttonId) {
        return;
    }
    var button = document.getElementById(buttonId);
    if (!button || button.disabled) {
        return;
    }
    button.classList.add("needs-update");
    button.classList.remove("button-glow");
    if (typeof Noty !== 'undefined') {
        new Noty({
            text: 'Click <b>Update Results</b> to apply your changes',
            type: 'info',
            timeout: 3500,
            theme: 'mint',
            layout: 'bottomRight',
            killer: true
        }).show();
    }
}

function clearActionButtonPending(buttonId) {
    if (!buttonId) {
        return;
    }
    var button = document.getElementById(buttonId);
    if (!button) {
        return;
    }
    button.classList.remove("needs-update");
    button.classList.remove("button-glow");
}
// processes the user's query by redirecting with query parameter.
function submitSearchQuery() {
    clearPendingSearchTermsIndicator();
    // searchTerms corresponds to the main search bar
    var selectedTerms = getSelectedSearchTerms();
    var pendingTerms = getPendingSearchTerms();
    if (pendingTerms.length) {
        commitPendingSearchTermsToControl(pendingTerms);
    }
    var terms = mergeSearchTerms(selectedTerms, pendingTerms.length ? pendingTerms : getSelectedSearchTerms());
    var queryState = extractQueryState(getQueryStringValue('q') || '');
    if (!terms.length && !queryState.hasScope) {
        return;
    }
    var query = buildScopedQuery(terms, queryState.scopeFilters);
    if (!query) {
        return;
    }
    var nextEntry = queryState.hasScope ? 'browse' : resolveEntryContextParam();
    redirectToSearchResult(query, '', '', '', '', searchMatchMode, nextEntry);
}

function showBookBlurb(bookName) {
    for (blurb in bookBlurbs) {
        if (strip(bookName).toUpperCase().includes(bookBlurbs[blurb].book.toUpperCase())) {
            var wrapper = document.createElement("div");
            wrapper.innerHTML = bookBlurbs[blurb].blurb;
            swal({
                content: wrapper
            });
        }
    }
}

function indicatePendingSearchTerms() {
    toggleSearchBarPendingState(true);
    $("[id^=searchBtn]").addClass("button-glow");
}

function toggleSearchBarPendingState(isPending) {
    var queryBar = document.getElementById('queryBar');
    if (queryBar) {
        queryBar.classList.toggle('search-bar-glow', !!isPending);
    }
    var searchShell = queryBar ? queryBar.querySelector('.navbar-search__input') : null;
    if (searchShell) {
        searchShell.classList.toggle('search-bar-glow', !!isPending);
    }
    if (searchSelectControl && searchSelectControl.wrapper) {
        searchSelectControl.wrapper.classList.toggle('search-bar-glow', !!isPending);
    }
}


function select2SelectHandler(select2_id) {
    return;
}

function displayQuery(query) {
    // 1 --> split keyword query on spaces and quotes (scope filters stay outside the input)
    var queryState = extractQueryState(query);
    var searchTermsArray = queryState.keywordTerms;
    // 2 --> populate search bar with options
    var selectSearchTerms = document.getElementById("searchTerms");
    selectSearchTerms.innerHTML = '';
    for (var term = 0; term < searchTermsArray.length; term++) {
        var option = document.createElement("option");
        option.text = searchTermsArray[term];
        option.value = searchTermsArray[term];
        option.selected = true;
        selectSearchTerms.add(option);
    }
    // initialize select2
    initSelect2('searchTerms');
    if (searchSelectControl) {
        suppressSearchGlow = true;
        if (typeof searchSelectControl.clear === 'function') {
            searchSelectControl.clear(true);
        }
        searchTermsArray.forEach(function(term) {
            if (!searchSelectControl.options[term]) {
                searchSelectControl.addOption({ value: term, text: term });
            }
            if (searchSelectControl.items.indexOf(term) === -1) {
                searchSelectControl.addItem(term, true);
            }
        });
        suppressSearchGlow = false;
        updateSearchPlaceholder(searchSelectControl);
    }
    // setup select2 select handler
    select2SelectHandler('searchTerms');
    // display search bar
    var queryBar = document.getElementById("queryBar");
    queryBar.classList.add('is-visible');
    queryBar.classList.remove('is-hidden');
    // setup enter key listener
    setupSelect2EnterKeyListener('searchTerms');
}

function isReadingMode(query) {
    var modeParam = (resolveModeParam() || '').toLowerCase();
    if (modeParam === 'read') {
        return true;
    }
    var sourceQuery = typeof query === 'string' ? query : (getQueryStringValue('q') || '');
    if (!sourceQuery) {
        return false;
    }
    var state = extractQueryState(sourceQuery);
    return state.hasScope && state.keywordTerms.length === 0;
}

function normalizeSearchMatchMode(mode) {
    return (mode || '').toLowerCase() === 'permissive' ? 'permissive' : 'strict';
}

function resolveSearchMatchModeParam() {
    return normalizeSearchMatchMode(getQueryStringValue('match_mode') || searchMatchMode);
}

function resolveModeParam() {
    return (getQueryStringValue('mode') || '').trim();
}

function resolveEntryContextParam() {
    if (isCollectionMode()) {
        return 'collection';
    }
    var value = (getQueryStringValue('entry') || '').trim().toLowerCase();
    return value === 'browse' ? 'browse' : 'search';
}

function isScopeFieldName(name) {
    return scopeFieldKeys.indexOf((name || '').toLowerCase()) !== -1;
}

function isScopeTerm(term) {
    if (!term) {
        return false;
    }
    var idx = term.indexOf(':');
    if (idx === -1) {
        return false;
    }
    var field = term.substring(0, idx).trim().toLowerCase();
    return isScopeFieldName(field);
}

function extractQueryState(query) {
    var state = {
        scopeFilters: {
            book: '',
            volume: '',
            part: '',
            section: '',
            chapter: ''
        },
        keywordTerms: [],
        keywordQuery: '',
        hasScope: false
    };
    if (!query) {
        return state;
    }
    var filters = parseQueryFilters(query);
    var terms = splitQuery(query);
    terms.forEach(function(term) {
        var cleaned = (term || '').trim();
        if (!cleaned) {
            return;
        }
        if (isScopeTerm(cleaned)) {
            return;
        }
        if (cleaned === '*:*') {
            return;
        }
        state.keywordTerms.push(cleaned);
    });
    state.scopeFilters = filters;
    state.hasScope = !!(filters.book || filters.volume || filters.part || filters.section || filters.chapter);
    state.keywordQuery = state.keywordTerms.join(' ').trim();
    return state;
}

function buildScopedQuery(keywordTerms, scopeFilters) {
    var safeScope = scopeFilters || {};
    var scopeQuery = buildQueryFromFilters(safeScope);
    if (!scopeQuery || !scopeQuery.trim()) {
        // No scope filters, return keyword query only
        var terms = Array.isArray(keywordTerms) ? keywordTerms.filter(function(term) {
            return term && String(term).trim().length > 0;
        }) : [];
        return terms.join(' ').trim();
    }
    // Has scope filters - build combined query
    var terms = Array.isArray(keywordTerms) ? keywordTerms.filter(function(term) {
        return term && String(term).trim().length > 0;
    }) : [];
    var keywordQuery = terms.join(' ').trim();
    // Only add keyword query if it's not empty (don't add '*' placeholder)
    if (keywordQuery) {
        return keywordQuery + ' ' + scopeQuery;
    }
    return scopeQuery;
}

function stripWrappingQuotes(value) {
    return String(value || '').trim().replace(/^"+|"+$/g, '');
}

function normalizeTermForCompare(value) {
    return stripWrappingQuotes(value).toLowerCase();
}

function extractEnglishKeywordTerms(query) {
    var state = extractQueryState(query || '');
    var seen = {};
    return state.keywordTerms.map(function(term) {
        return String(term || '').trim();
    }).filter(function(term) {
        if (!term) {
            return false;
        }
        var normalized = normalizeTermForCompare(term);
        if (!normalized || normalized.length < 2) {
            return false;
        }
        if (seen[normalized]) {
            return false;
        }
        seen[normalized] = true;
        return !isArabic(normalized);
    });
}

function getSelectValue(id) {
    var select = document.getElementById(id);
    if (!select) {
        return '';
    }
    return (select.value || '').trim();
}

function formatFacetDisplay(key, value) {
    if (!value) {
        return '';
    }
    var label = String(value);
    if (key === 'volume' && label.toLowerCase().indexOf('volume') === -1) {
        return 'Volume ' + label;
    }
    return label;
}

function formatHadithCount(count) {
    var num = Number(count) || 0;
    return num + ' hadith';
}

function buildQueryFromFilters(filters) {
    var parts = [];
    if (filters.book) {
        parts.push('book:\"' + sanitizeQueryValue(filters.book) + '\"');
    }
    if (filters.volume) {
        parts.push('volume:\"' + sanitizeQueryValue(filters.volume) + '\"');
    }
    if (filters.part) {
        parts.push('part:\"' + sanitizeQueryValue(filters.part) + '\"');
    }
    if (filters.section) {
        parts.push('section:\"' + sanitizeQueryValue(filters.section) + '\"');
    }
    if (filters.chapter) {
        parts.push('chapter:\"' + sanitizeQueryValue(filters.chapter) + '\"');
    }
    return parts.join(' ');
}

function buildSortFields(filters) {
    var sortParts = [];
    if (filters.volume) {
        sortParts.push('volume:asc');
    }
    if (filters.part) {
        sortParts.push('part:asc');
    }
    if (filters.section) {
        sortParts.push('section:asc');
    }
    if (filters.chapter) {
        sortParts.push('chapter:asc');
    }
    if (sortParts.length === 0) {
        sortParts = ['volume:asc', 'part:asc', 'section:asc', 'chapter:asc'];
    }
    sortParts.push('number:asc');
    return sortParts.join(',');
}

function parseQueryFilters(query) {
    var filters = {
        book: '',
        volume: '',
        part: '',
        section: '',
        chapter: ''
    };
    if (!query) {
        return filters;
    }
    var terms = splitQuery(query);
    terms.forEach(function(term) {
        var idx = term.indexOf(':');
        if (idx === -1) {
            return;
        }
        var key = term.substring(0, idx).trim().toLowerCase();
        var value = term.substring(idx + 1).trim();
        if (!filters.hasOwnProperty(key)) {
            return;
        }
        value = value.replace(/^\"|\"$/g, '');
        filters[key] = value;
    });
    return filters;
}

function buildFacetsUrl(filters) {
    var queryParamString = '/v1/browse/facets?book=' + encodeURIComponent(filters.book);
    browseFacetConfig.forEach(function(config) {
        var value = filters[config.key];
        if (value) {
            queryParamString += '&' + config.key + '=' + encodeURIComponent(value);
        }
    });
    return queryParamString;
}

function normalizeFacetItemValue(item) {
    if (!item) {
        return '';
    }
    var raw = item.name || item.key || '';
    return String(raw).trim();
}

function cloneFacetSelections(source) {
    var cloned = {
        book: source && source.book ? source.book : '',
        volume: '',
        part: '',
        section: '',
        chapter: ''
    };
    facetKeys.forEach(function(key) {
        cloned[key] = (source && source[key]) ? String(source[key]).trim() : '';
    });
    return cloned;
}

function facetSelectionsChanged(before, after) {
    if (!before || !after) {
        return false;
    }
    for (var i = 0; i < facetKeys.length; i++) {
        var key = facetKeys[i];
        if ((before[key] || '') !== (after[key] || '')) {
            return true;
        }
    }
    return false;
}

function updateFacetSelect(config, items, selectedValue) {
    var select = document.getElementById(config.selectId);
    if (!select) {
        return '';
    }
    var field = document.querySelector('[' + config.fieldAttr + '="' + config.key + '"]');
    var placeholder = 'Select a ' + humanizeFacetLabel(config.key).toLowerCase();
    var normalizedItems = (items || []).map(function(item) {
        return {
            value: normalizeFacetItemValue(item),
            count: item && item.count ? item.count : 0
        };
    }).filter(function(item) {
        return item.value !== '';
    });
    var hasItems = normalizedItems.length > 0;
    select.innerHTML = '';
    if (normalizedItems.length > 1) {
        select.innerHTML = '<option value=\"\">' + placeholder + '</option>';
    }
    normalizedItems.forEach(function(item) {
        var option = document.createElement('option');
        option.value = item.value;
        option.textContent = formatFacetDisplay(config.key, item.value) + ' (' + formatHadithCount(item.count) + ')';
        select.appendChild(option);
    });
    select.disabled = !hasItems;
    if (field) {
        field.classList.toggle('is-hidden', !hasItems);
    }
    if (!hasItems) {
        return '';
    }
    if (normalizedItems.length === 1) {
        select.value = normalizedItems[0].value;
        select.disabled = true;
        return normalizedItems[0].value;
    }
    var normalizedSelected = (selectedValue || '').trim();
    if (normalizedSelected && normalizedItems.some(function(item) { return item.value === normalizedSelected; })) {
        select.value = normalizedSelected;
        return normalizedSelected;
    }
    select.value = '';
    select.disabled = false;
    return '';
}

function buildFacetSummary(facets) {
    var summary = [];
    browseFacetConfig.forEach(function(config) {
        var items = facets[config.key] || [];
        if (items.length) {
            summary.push(items.length + ' ' + humanizeFacetLabel(config.key).toLowerCase());
        }
    });
    return summary.join(' · ');
}

function syncReadingModeUI(query, sortFields) {
    var queryBar = document.getElementById('queryBar');
    var menuReadingScope = document.getElementById('menuReadingScope');
    var readingToolbar = document.getElementById('readingToolbar');
    var hadithView = document.getElementById('hadithView');
    var welcome = document.getElementById('welcome');
    var activeReadingMode = isReadingMode(query);
    var activeCollectionMode = isCollectionMode();
    if (hadithView) {
        hadithView.classList.toggle('is-reading-mode', activeReadingMode);
        hadithView.classList.toggle('is-collection-mode', activeCollectionMode);
    }
    if (document && document.body) {
        document.body.classList.toggle('is-reading-mode', activeReadingMode);
        document.body.classList.toggle('is-collection-mode', activeCollectionMode);
    }
    if (queryBar) {
        queryBar.classList.toggle('is-hidden', activeReadingMode || activeCollectionMode);
        queryBar.classList.toggle('is-visible', !activeReadingMode && !activeCollectionMode);
    }
    if (welcome) {
        welcome.classList.toggle('d-none', activeCollectionMode);
    }
    if (menuReadingScope) {
        menuReadingScope.classList.toggle('d-none', !activeReadingMode || activeCollectionMode);
        if (!activeReadingMode) {
            setContainerValueText(menuReadingScope, 'menu-reading-scope__value', '');
            menuReadingScope.classList.remove('is-empty');
        }
    }
    if (readingToolbar && (!activeReadingMode || activeCollectionMode)) {
        readingToolbar.classList.add('d-none');
    }
    if (activeReadingMode) {
        setupReadingMode(query, sortFields);
    } else if (activeCollectionMode) {
        setupSearchMatchToggle('', '');
    } else {
        setupSearchMatchToggle(query, sortFields);
    }
}

function setupModeSwitch(query, sortFields) {
    setupSearchMatchToggle(query, sortFields);
}

function setupSearchMatchToggle(query, sortFields) {
    // Legacy function - now handled by dropdown
    // This is kept for compatibility but doesn't do anything
    searchMatchMode = resolveSearchMatchModeParam();
}

function applySearchMatchMode(mode, query, sortFields) {
    var nextMode = normalizeSearchMatchMode(mode);
    if (searchMatchMode === nextMode) {
        return;
    }
    searchMatchMode = nextMode;
    var currentQuery = getQueryStringValue('q') || query || '';
    var currentSort = getQueryStringValue('sort_fields') || sortFields || '';

    // Update UI but don't redirect - the new dropdown handles this differently
    setupSearchMatchToggle(query, sortFields);
}

function setupReadingMode(query, sortFields) {
    var toolbar = document.getElementById('readingToolbar');
    if (!toolbar) {
        return;
    }
    toolbar.classList.remove('d-none');
    if (!toolbar.dataset.bound) {
        var applyBtn = document.getElementById('readingApplyBtn');
        if (applyBtn) {
            applyBtn.addEventListener('click', function() {
                var selections = getReadingSelections();
                if (!selections.book) {
                    return;
                }
                clearActionButtonPending('readingApplyBtn');
                var nextQuery = buildQueryFromFilters(selections);
                var nextSort = buildSortFields(selections);
                redirectToSearchResult(nextQuery, 1, nextSort, 'read');
            });
        }
        var bookSelect = document.getElementById('readingBookSelect');
        if (bookSelect) {
            bookSelect.addEventListener('change', function() {
                var selections = getReadingSelections();
                selections.volume = '';
                selections.part = '';
                selections.section = '';
                selections.chapter = '';
                indicateActionButtonPending('readingApplyBtn');
                updateReadingApplyState(selections);
                updateReadingPath(selections);
                if (selections.book) {
                    fetchReadingFacets(selections);
                } else {
                    resetReadingFacetSelects();
                }
            });
        }
        readingFacetConfig.forEach(function(config) {
            var select = document.getElementById(config.selectId);
            if (select) {
                select.addEventListener('change', function() {
                    var selections = getReadingSelections();
                    if (selections.book) {
                        fetchReadingFacets(selections);
                    }
                    indicateActionButtonPending('readingApplyBtn');
                    updateReadingApplyState(selections);
                    updateReadingPath(selections);
                });
            }
        });
        var prevBtn = document.getElementById('readingPrevBtn');
        if (prevBtn) {
            prevBtn.addEventListener('click', function() {
                handleReadingNav('prev');
            });
        }
        var nextBtn = document.getElementById('readingNextBtn');
        if (nextBtn) {
            nextBtn.addEventListener('click', function() {
                handleReadingNav('next');
            });
        }
        toolbar.dataset.bound = 'true';
    }
    var selections = parseQueryFilters(query);
    updateReadingApplyState(selections);
    updateReadingPath(selections);
    loadReadingBooks(selections.book);
    if (selections.book) {
        fetchReadingFacets(selections);
    } else {
        resetReadingFacetSelects();
        updateReadingMeta({});
    }
    updateReadingNav();
}

function loadReadingBooks(selectedBook) {
    var select = document.getElementById('readingBookSelect');
    if (!select) {
        return;
    }
    fetch('/v1/browse/books')
        .then(function(resp) { return resp.json(); })
        .then(function(data) {
            select.innerHTML = '<option value=\"\">Select a book</option>';
            (data || []).forEach(function(item) {
                var name = item.name || item.key || '';
                if (!name) {
                    return;
                }
                var count = item.count || 0;
                var option = document.createElement('option');
                option.value = name;
                option.textContent = name + ' (' + formatHadithCount(count) + ')';
                select.appendChild(option);
            });
            if (selectedBook) {
                select.value = selectedBook;
            }
        })
        .catch(function() {});
}

function resetReadingFacetSelects() {
    readingFacetConfig.forEach(function(config) {
        updateFacetSelect(config, [], '');
    });
    readingFacetData = {};
    updateReadingNav();
}

function getReadingSelections() {
    return {
        book: getSelectValue('readingBookSelect'),
        volume: getSelectValue('readingVolumeSelect'),
        part: getSelectValue('readingPartSelect'),
        section: getSelectValue('readingSectionSelect'),
        chapter: getSelectValue('readingChapterSelect')
    };
}

function fetchReadingFacets(filters) {
    var activeFilters = cloneFacetSelections(filters || getReadingSelections());
    activeFilters.book = (filters && filters.book) ? filters.book : getSelectValue('readingBookSelect');
    var url = buildFacetsUrl(activeFilters);
    fetch(url)
        .then(function(resp) { return resp.json(); })
        .then(function(data) {
            readingFacetData = (data && data.facets) ? data.facets : {};
            applyReadingFacets(data, activeFilters);
        })
        .catch(function() {
            updateReadingMeta({});
        });
}

function applyReadingFacets(data, selections) {
    var facets = (data && data.facets) ? data.facets : {};
    var currentSelections = cloneFacetSelections(selections || getReadingSelections());
    currentSelections.book = (selections && selections.book) ? selections.book : getSelectValue('readingBookSelect');
    var resolvedSelections = cloneFacetSelections(currentSelections);
    resolvedSelections.book = currentSelections.book;
    readingFacetConfig.forEach(function(config) {
        var items = facets[config.key] || [];
        var selectedValue = resolvedSelections[config.key];
        resolvedSelections[config.key] = updateFacetSelect(config, items, selectedValue);
    });
    updateReadingMeta(facets);
    updateReadingApplyState(resolvedSelections);
    updateReadingPath(resolvedSelections);
    updateReadingNav();
    if (facetSelectionsChanged(currentSelections, resolvedSelections)) {
        fetchReadingFacets(resolvedSelections);
    }
}

function updateReadingApplyState(selections) {
    var btn = document.getElementById('readingApplyBtn');
    if (!btn) {
        return;
    }
    btn.disabled = !selections.book;
    if (btn.disabled) {
        clearActionButtonPending('readingApplyBtn');
    }
}

function updateReadingPath(filters) {
    var path = document.getElementById('readingPath');
    var menuScope = document.getElementById('menuReadingScope');
    if (!path) {
        // continue to update navbar scope, if present.
    }
    var parts = [];
    if (filters.book) {
        parts.push(filters.book);
    }
    if (filters.volume) {
        parts.push(formatFacetDisplay('volume', filters.volume));
    }
    if (filters.part) {
        parts.push(filters.part);
    }
    if (filters.section) {
        parts.push(filters.section);
    }
    if (filters.chapter) {
        parts.push(filters.chapter);
    }
    var hasPath = parts.length > 0;
    var scopeText = hasPath ? parts.join(' · ') : 'Select a book to start reading.';
    if (path) {
        setContainerValueText(path, 'reading-path__value', scopeText);
        path.classList.toggle('is-empty', !hasPath);
    }
    if (menuScope) {
        setContainerValueText(menuScope, 'menu-reading-scope__value', scopeText);
        menuScope.classList.toggle('is-empty', !hasPath);
    }
    updateReadingScope(filters);
}

function updateReadingScope(filters) {
    var scope = document.getElementById('readingScope');
    if (!scope) {
        return;
    }
    if (!filters || !filters.book) {
        scope.innerHTML = '';
        return;
    }
    var items = [];
    items.push({ label: 'Book', value: filters.book });
    if (filters.volume) {
        items.push({ label: 'Volume', value: filters.volume });
    }
    if (filters.part) {
        items.push({ label: 'Part', value: filters.part });
    }
    if (filters.section) {
        items.push({ label: 'Section', value: filters.section });
    }
    if (filters.chapter) {
        items.push({ label: 'Chapter', value: filters.chapter });
    }
    scope.innerHTML = items.map(function(item) {
        return '<span class="reading-scope-chip"><span class="reading-scope-label">' +
            escapeHtml(item.label) + ':</span> ' + escapeHtml(item.value) + '</span>';
    }).join('');
}

function updateReadingMeta(facets) {
    var meta = document.getElementById('readingMeta');
    if (!meta) {
        return;
    }
    var summary = buildFacetSummary(facets || {});
    if (summary) {
        meta.textContent = 'Available: ' + summary + '. ' + optionalFiltersHint;
    } else {
        meta.textContent = optionalFiltersHint;
    }
}

function updateReadingNav() {
    var selections = getReadingSelections();
    var nav = document.getElementById('menuReadingNav');
    var prevBtn = document.getElementById('readingPrevBtn');
    var nextBtn = document.getElementById('readingNextBtn');
    if (!nav || !prevBtn || !nextBtn) {
        return;
    }
    var facet = getReadingNavFacet(selections);
    if (!selections.book || !facet) {
        nav.classList.add('d-none');
        prevBtn.disabled = true;
        nextBtn.disabled = true;
        return;
    }
    var list = readingFacetData[facet] || [];
    if (list.length <= 1) {
        nav.classList.add('d-none');
        prevBtn.disabled = true;
        nextBtn.disabled = true;
        return;
    }
    nav.classList.remove('d-none');
    var label = humanizeFacetLabel(facet);
    prevBtn.textContent = 'Previous ' + label;
    nextBtn.textContent = 'Next ' + label;
    var currentValue = selections[facet] || '';
    var idx = -1;
    for (var i = 0; i < list.length; i++) {
        if (normalizeFacetItemValue(list[i]) === currentValue) {
            idx = i;
            break;
        }
    }
    prevBtn.disabled = !(idx > 0);
    nextBtn.disabled = !(idx !== -1 && idx < (list.length - 1));
}

function getReadingNavFacet(selections) {
    var currentSelections = selections || getReadingSelections();
    if (!currentSelections.book) {
        return '';
    }
    for (var i = 0; i < readingNavOrder.length; i++) {
        var facet = readingNavOrder[i];
        var field = document.querySelector('[data-reading-facet="' + facet + '"]');
        if (field && field.classList.contains('is-hidden')) {
            continue;
        }
        var list = readingFacetData[facet] || [];
        if (list.length > 0) {
            return facet;
        }
    }
    return '';
}

function handleReadingNav(direction) {
    var selections = getReadingSelections();
    var facet = getReadingNavFacet(selections);
    if (!facet) {
        return;
    }
    var list = readingFacetData[facet] || [];
    if (!list.length || !selections[facet]) {
        return;
    }
    var idx = -1;
    for (var i = 0; i < list.length; i++) {
        if (normalizeFacetItemValue(list[i]) === selections[facet]) {
            idx = i;
            break;
        }
    }
    if (idx === -1) {
        return;
    }
    var nextIdx = direction === 'next' ? idx + 1 : idx - 1;
    if (nextIdx < 0 || nextIdx >= list.length) {
        return;
    }
    selections[facet] = list[nextIdx].name || list[nextIdx].key;
    clearLowerFacets(selections, facet);
    var nextQuery = buildQueryFromFilters(selections);
    var nextSort = buildSortFields(selections);
    redirectToSearchResult(nextQuery, 1, nextSort, 'read');
}

function clearLowerFacets(selections, facet) {
    var index = facetHierarchy.indexOf(facet);
    if (index === -1) {
        return;
    }
    for (var i = index + 1; i < facetHierarchy.length; i++) {
        selections[facetHierarchy[i]] = '';
    }
}

function loadRecentUpdates() {
    var container = document.getElementById('recentUpdatesList');
    if (!container) {
        return;
    }
    container.innerHTML = '<div class="text-muted">Loading updates...</div>';
    fetch('/recent_updates.json?v=2')
        .then(function(resp) { return resp.json(); })
        .then(function(items) {
            var updates = Array.isArray(items) ? items.slice() : [];
            updates.sort(function(a, b) {
                return String(b.date || '').localeCompare(String(a.date || ''));
            });
            if (!updates.length) {
                container.innerHTML = '<div class="text-muted">No updates yet.</div>';
                return;
            }
            container.innerHTML = '';
            updates.forEach(function(update) {
                var card = document.createElement('article');
                card.className = 'recent-update-card';
                var highlights = Array.isArray(update.highlights) ? update.highlights : [];
                card.innerHTML =
                    '<div class="recent-update-date">' + escapeHtml(update.date || '') + '</div>' +
                    '<h3 class="recent-update-title">' + escapeHtml(update.title || 'Update') + '</h3>' +
                    '<p class="recent-update-summary">' + escapeHtml(update.summary || '') + '</p>';
                if (highlights.length) {
                    var list = document.createElement('ul');
                    list.className = 'recent-update-list';
                    highlights.forEach(function(item) {
                        var li = document.createElement('li');
                        li.textContent = item;
                        list.appendChild(li);
                    });
                    card.appendChild(list);
                }
                container.appendChild(card);
            });
        })
        .catch(function() {
            container.innerHTML = '<div class="text-muted">Unable to load updates right now.</div>';
        });
}

function escapeHtml(value) {
    var div = document.createElement('div');
    div.textContent = value == null ? '' : String(value);
    return div.innerHTML;
}

function quranicCitationStyleAttr(source) {
    var text = String(source || '').trim();
    if (!text) {
        return '';
    }
    var hash = 0;
    for (var i = 0; i < text.length; i++) {
        hash = ((hash << 5) - hash + text.charCodeAt(i)) | 0;
    }
    var hue = Math.abs(hash) % 360;
    var bg = 'hsla(' + hue + ', 72%, 92%, 0.98)';
    var border = 'hsla(' + hue + ', 42%, 56%, 0.36)';
    var ink = 'hsl(' + hue + ', 58%, 34%)';
    return ' style="background:' + bg + ';border-color:' + border + ';color:' + ink + ';"';
}

function buildPdfExportMarkup(options) {
    var opts = options || {};
    var narrations = Array.isArray(opts.narrations) ? opts.narrations : [];
    var title = escapeHtml(opts.title || 'Hadith Export');
    var subtitle = escapeHtml(opts.subtitle || '');
    var metaLine = escapeHtml(opts.metaLine || '');
    var generatedAt = escapeHtml(new Date().toLocaleString());
    var cards = narrations.map(function(narration, index) {
        var number = escapeHtml(String(typeof opts.resultOrdinal === 'function'
            ? opts.resultOrdinal(narration, index)
            : (index + 1)));
        var reference = escapeHtml((typeof opts.referenceLine === 'function'
            ? opts.referenceLine(narration)
            : '') || '');
        var english = (narration && (narration.englishContent || narration.english)) || '';
        var arabic = (narration && (narration.arabicContent || narration.arabic)) || '';
        var tags = Array.isArray(narration && narration.topic_tags) ? narration.topic_tags : [];
        var tagsHtml = tags.length
            ? '<div class="pdf-card__tags">' + tags.map(function(tag) {
                var label = typeof opts.tagLabel === 'function' ? opts.tagLabel(tag) : tag;
                return '<span class="pdf-tag">' + escapeHtml(label || tag) + '</span>';
            }).join('') + '</div>'
            : '';
        return '' +
            '<article class="pdf-card">' +
                '<div class="pdf-card__top">' +
                    '<div class="pdf-card__index">Hadith ' + number + '</div>' +
                    (reference ? '<div class="pdf-card__reference">' + reference + '</div>' : '') +
                '</div>' +
                '<div class="pdf-card__body">' +
                    (english ? '<div class="pdf-card__english">' + english + '</div>' : '') +
                    (arabic ? '<div class="pdf-card__arabic" dir="rtl">' + arabic + '</div>' : '') +
                    tagsHtml +
                '</div>' +
            '</article>';
    }).join('');

    return '<!doctype html>' +
        '<html><head><meta charset="utf-8">' +
        '<title>' + title + '</title>' +
        '<link rel="preconnect" href="https://fonts.googleapis.com">' +
        '<link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>' +
        '<link href="https://fonts.googleapis.com/css2?family=Amiri:wght@400;700&family=Manrope:wght@500;700;800&family=Source+Serif+4:wght@400;600;700&display=swap" rel="stylesheet">' +
        '<style>' +
        ':root{color-scheme:light;--ink:#1d2f3f;--muted:#6e6251;--paper:#f8f1e4;--card:#fffdf8;--line:rgba(64,49,26,.14);--accent:#8b6b31;--accent-strong:#174d77;}' +
        '*{box-sizing:border-box;}html,body{margin:0;padding:0;background:var(--paper);color:var(--ink);font-family:"Source Serif 4",Georgia,serif;line-height:1.7;}' +
        'body{padding:28px;}body::before{content:"";position:fixed;inset:0;background:radial-gradient(circle at top left,rgba(139,107,49,.08),transparent 32%),radial-gradient(circle at top right,rgba(23,77,119,.06),transparent 28%);pointer-events:none;}' +
        '.pdf-shell{position:relative;max-width:980px;margin:0 auto;}' +
        '.pdf-header{padding:24px 28px;border:1px solid var(--line);border-radius:24px;background:linear-gradient(145deg,rgba(255,253,248,.98),rgba(244,232,207,.96));box-shadow:0 20px 45px rgba(57,42,21,.12);}' +
        '.pdf-eyebrow{font:800 11px/1 "Manrope",sans-serif;letter-spacing:.18em;text-transform:uppercase;color:var(--accent);}' +
        '.pdf-title{margin:8px 0 6px;font:800 34px/1.08 "Manrope",sans-serif;color:#163247;}' +
        '.pdf-subtitle{font-size:15px;color:#50473c;}' +
        '.pdf-meta{margin-top:10px;font:600 12px/1.4 "Manrope",sans-serif;letter-spacing:.04em;text-transform:uppercase;color:#7a6a55;}' +
        '.pdf-grid{display:grid;gap:18px;margin-top:22px;}' +
        '.pdf-card{break-inside:avoid;page-break-inside:avoid;border:1px solid var(--line);border-radius:22px;background:var(--card);padding:22px 24px;box-shadow:0 12px 28px rgba(57,42,21,.08);}' +
        '.pdf-card:not(:last-child){break-after:page;page-break-after:always;}' +
        '.pdf-card__top{display:flex;justify-content:space-between;gap:12px;align-items:flex-start;margin-bottom:12px;padding-bottom:10px;border-bottom:1px solid rgba(64,49,26,.09);}' +
        '.pdf-card__index{font:800 12px/1.2 "Manrope",sans-serif;letter-spacing:.14em;text-transform:uppercase;color:var(--accent-strong);}' +
        '.pdf-card__reference{font:600 12px/1.4 "Manrope",sans-serif;color:#617284;text-align:right;}' +
        '.pdf-card__english{font-size:16px;color:#243546;}' +
        '.pdf-card__arabic{margin-top:18px;padding-top:16px;border-top:1px dashed rgba(64,49,26,.12);font:700 24px/1.9 "Amiri","Scheherazade New",serif;color:#1c2f41;}' +
        '.pdf-card__tags{display:flex;flex-wrap:wrap;gap:8px;margin-top:16px;}' +
        '.pdf-tag{display:inline-flex;align-items:center;padding:4px 10px;border-radius:999px;background:rgba(23,77,119,.08);border:1px solid rgba(23,77,119,.12);font:700 11px/1.2 "Manrope",sans-serif;letter-spacing:.06em;text-transform:uppercase;color:#1f5c86;}' +
        '@page{size:auto;margin:16mm;}@media print{body{padding:0;background:#fff;}body::before{display:none;}.pdf-shell{max-width:none;}.pdf-header{box-shadow:none;border-radius:0;border:0;border-bottom:1px solid #ddd;padding:0 0 16px;background:#fff;}.pdf-grid{margin-top:16px;}.pdf-card{box-shadow:none;background:#fff;}.pdf-card:not(:last-child){break-after:page;page-break-after:always;}}' +
        '</style></head><body>' +
        '<div class="pdf-shell">' +
            '<header class="pdf-header">' +
                '<div class="pdf-eyebrow">Rewayaat Export</div>' +
                '<h1 class="pdf-title">' + title + '</h1>' +
                (subtitle ? '<div class="pdf-subtitle">' + subtitle + '</div>' : '') +
                '<div class="pdf-meta">' + metaLine + (metaLine ? ' · ' : '') + 'Generated ' + generatedAt + '</div>' +
            '</header>' +
            '<section class="pdf-grid">' + cards + '</section>' +
        '</div>' +
        '<script>window.addEventListener("load",function(){setTimeout(function(){window.print();},300);});<\/script>' +
        '</body></html>';
}

function openPdfExportWindow(options) {
    var frameId = 'rewayaatPdfExportFrame';
    var existing = document.getElementById(frameId);
    if (existing && existing.parentNode) {
        existing.parentNode.removeChild(existing);
    }
    var iframe = document.createElement('iframe');
    iframe.id = frameId;
    iframe.setAttribute('aria-hidden', 'true');
    iframe.style.position = 'fixed';
    iframe.style.right = '0';
    iframe.style.bottom = '0';
    iframe.style.width = '0';
    iframe.style.height = '0';
    iframe.style.border = '0';
    iframe.style.opacity = '0';
    iframe.style.pointerEvents = 'none';
    document.body.appendChild(iframe);

    var html = buildPdfExportMarkup(options);
    var frameWindow = iframe.contentWindow;
    var frameDocument = frameWindow ? frameWindow.document : null;
    if (!frameWindow || !frameDocument) {
        if (iframe.parentNode) {
            iframe.parentNode.removeChild(iframe);
        }
        if (typeof swal === 'function') {
            swal('Export unavailable', 'Unable to prepare the PDF export in this browser.', 'warning');
        }
        return;
    }

    iframe.onload = function() {
        try {
            frameWindow.focus();
            frameWindow.print();
        } finally {
            window.setTimeout(function() {
                if (iframe.parentNode) {
                    iframe.parentNode.removeChild(iframe);
                }
            }, 1000);
        }
    };
    frameDocument.open();
    frameDocument.write(html);
    frameDocument.close();
}

function normalizeArabicForSimilarHighlight(text) {
    if (text == null) {
        return '';
    }
    var normalized = String(text)
        .replace(SIMILAR_ARABIC_DIACRITIC_PATTERN, '')
        .replace(/أ/g, 'ا')
        .replace(/إ/g, 'ا')
        .replace(/آ/g, 'ا')
        .replace(/ٱ/g, 'ا')
        .replace(/ى/g, 'ي')
        .replace(/ؤ/g, 'و')
        .replace(/ئ/g, 'ي')
        .replace(/ة/g, 'ه')
        .replace(/ـ/g, '');
    normalized = normalized.replace(SIMILAR_NON_ARABIC_PATTERN, ' ');
    return normalized.replace(SIMILAR_MULTI_SPACE_PATTERN, ' ').trim();
}

function stripSimilarContentPrefixes(token) {
    var clean = token == null ? '' : String(token);
    for (var i = 0; i < 3; i++) {
        var stripped = clean;
        if (stripped.length > 5 && (stripped.indexOf('وال') === 0 || stripped.indexOf('فال') === 0
                || stripped.indexOf('بال') === 0 || stripped.indexOf('كال') === 0)) {
            stripped = stripped.substring(1);
        } else if (stripped.length > 4 && stripped.indexOf('لل') === 0 && stripped.indexOf('لله') !== 0) {
            stripped = stripped.substring(2);
        } else if (stripped.length > 4 && stripped.indexOf('ال') === 0 && stripped.indexOf('الله') !== 0) {
            stripped = stripped.substring(2);
        } else if (stripped.length > 4 && (stripped.indexOf('و') === 0 || stripped.indexOf('ف') === 0
                || stripped.indexOf('ب') === 0 || stripped.indexOf('ك') === 0)) {
            stripped = stripped.substring(1);
        }
        if (stripped === clean) {
            break;
        }
        clean = stripped;
    }
    return clean;
}

function normalizeDistinctiveArabicToken(token) {
    var normalized = normalizeArabicForSimilarHighlight(token);
    if (!normalized || normalized.indexOf(' ') >= 0) {
        return '';
    }
    normalized = stripSimilarContentPrefixes(normalized);
    if (!normalized || normalized === 'الله' || normalized.length < 3) {
        return '';
    }
    return normalized;
}

var ARABIC_SUGGESTION_STOPWORDS = new Set([
    'قال', 'قلت', 'يقول', 'فقال', 'قالت', 'يقولون', 'عليه', 'السلام', 'عليهم', 'من', 'في', 'على',
    'الى', 'عن', 'ما', 'لا', 'ان', 'اذا', 'ثم', 'كان', 'كانت', 'هذا', 'هذه', 'ذلك', 'تلك', 'كل',
    'قد', 'لم', 'لن', 'له', 'لها', 'لهم', 'عند', 'بعد', 'قبل', 'بين', 'يا', 'هو', 'هي', 'هم',
    'كما', 'به', 'بها', 'عنه', 'عنها', 'مع', 'او', 'اي', 'ايضا', 'عبد', 'ابو', 'ابن', 'بن', 'رسول',
    'الله', 'شيء', 'علي', 'ابي', 'فقلت', 'فقالوا', 'قالوا'
].map(function(term) {
    return normalizeDistinctiveArabicToken(term) || normalizeArabicForSimilarHighlight(term);
}).filter(function(term) {
    return !!term;
}));
var ARABIC_SUGGESTION_LIMIT = 3;
var ARABIC_SUGGESTION_FETCH_SIZE = 3;

function buildArabicSuggestionExistingMap(queryText) {
    var existing = Object.create(null);
    (extractQueryState(queryText || '').keywordTerms || []).forEach(function(term) {
        var normalized = normalizeDistinctiveArabicToken(stripWrappingQuotes(term || ''));
        if (normalized) {
            existing[normalized] = true;
        }
    });
    return existing;
}

function filterArabicSuggestionCandidates(candidates, queryText, limit) {
    var existing = buildArabicSuggestionExistingMap(queryText);
    var seen = Object.create(null);
    var maxItems = Math.max(1, Number(limit) || ARABIC_SUGGESTION_LIMIT);
    var suggestions = [];
    (Array.isArray(candidates) ? candidates : []).forEach(function(candidate) {
        if (suggestions.length >= maxItems) {
            return;
        }
        var display = stripWrappingQuotes(candidate || '');
        var normalized = normalizeDistinctiveArabicToken(display);
        if (!display || !normalized || !isArabic(display) || ARABIC_SUGGESTION_STOPWORDS.has(normalized)
                || existing[normalized] || seen[normalized]) {
            return;
        }
        seen[normalized] = true;
        suggestions.push(display);
    });
    return suggestions;
}

function deriveArabicSuggestionTermsFromNarrations(narrations, queryText, limit) {
    var items = Array.isArray(narrations) ? narrations : [];
    var existing = buildArabicSuggestionExistingMap(queryText);
    var docCounts = Object.create(null);
    var totalCounts = Object.create(null);
    var desired = Math.max(1, Number(limit) || ARABIC_SUGGESTION_LIMIT);

    items.forEach(function(item) {
        var raw = strip((item && (item.arabicContent || item.arabic || '')) || '').trim();
        if (!raw) {
            return;
        }
        var seenInDoc = Object.create(null);
        SIMILAR_ARABIC_TOKEN_PATTERN.lastIndex = 0;
        var match;
        while ((match = SIMILAR_ARABIC_TOKEN_PATTERN.exec(raw)) !== null) {
            var normalized = normalizeDistinctiveArabicToken(match[0]);
            if (!normalized || existing[normalized] || ARABIC_SUGGESTION_STOPWORDS.has(normalized)) {
                continue;
            }
            totalCounts[normalized] = (totalCounts[normalized] || 0) + 1;
            if (!seenInDoc[normalized]) {
                docCounts[normalized] = (docCounts[normalized] || 0) + 1;
                seenInDoc[normalized] = true;
            }
        }
    });

    var ranked = Object.keys(docCounts);
    if (!ranked.length) {
        return [];
    }
    var minDocCount = ranked.some(function(term) {
        return docCounts[term] >= 2;
    }) ? 2 : 1;
    ranked = ranked.filter(function(term) {
        return docCounts[term] >= minDocCount;
    });
    ranked.sort(function(a, b) {
        var docDelta = docCounts[b] - docCounts[a];
        if (docDelta !== 0) {
            return docDelta;
        }
        var totalDelta = totalCounts[b] - totalCounts[a];
        if (totalDelta !== 0) {
            return totalDelta;
        }
        return b.length - a.length;
    });
    return ranked.slice(0, desired);
}

function buildSimilarHighlightTermSet(terms) {
    var termSet = new Set();
    if (!Array.isArray(terms)) {
        return termSet;
    }
    terms.forEach(function(term) {
        var clean = term == null ? '' : String(term).trim();
        if (clean) {
            termSet.add(clean);
        }
    });
    return termSet;
}

function resolveSimilarHighlightToken(tokenText, highlightKey) {
    if (!tokenText) {
        return '';
    }
    if (highlightKey === 'syntactic') {
        var normalized = normalizeArabicForSimilarHighlight(tokenText);
        return normalized.indexOf(' ') >= 0 ? '' : normalized;
    }
    return normalizeDistinctiveArabicToken(tokenText);
}

function buildSimilarHighlightFragment(text, highlightSpec) {
    if (!text || !highlightSpec || !highlightSpec.key || !highlightSpec.termSet || !highlightSpec.termSet.size) {
        return null;
    }
    SIMILAR_ARABIC_TOKEN_PATTERN.lastIndex = 0;
    var match;
    var cursor = 0;
    var changed = false;
    var fragment = document.createDocumentFragment();
    while ((match = SIMILAR_ARABIC_TOKEN_PATTERN.exec(text)) !== null) {
        var start = match.index;
        var tokenText = match[0];
        if (start > cursor) {
            fragment.appendChild(document.createTextNode(text.slice(cursor, start)));
        }
        var compareToken = resolveSimilarHighlightToken(tokenText, highlightSpec.key);
        if (compareToken && highlightSpec.termSet.has(compareToken)) {
            var span = document.createElement('span');
            span.className = 'similar-term-highlight similar-term-highlight--' + highlightSpec.toneSuffix;
            span.textContent = tokenText;
            fragment.appendChild(span);
            changed = true;
        } else {
            fragment.appendChild(document.createTextNode(tokenText));
        }
        cursor = start + tokenText.length;
    }
    if (!changed) {
        return null;
    }
    if (cursor < text.length) {
        fragment.appendChild(document.createTextNode(text.slice(cursor)));
    }
    return fragment;
}

function applySimilarArabicHighlight(htmlText, highlightSpec) {
    var raw = htmlText == null ? '' : String(htmlText);
    if (!raw || !highlightSpec || !highlightSpec.termSet || !highlightSpec.termSet.size || !document || !document.createElement) {
        return raw;
    }
    var container = document.createElement('div');
    container.innerHTML = raw;
    var showText = window.NodeFilter ? window.NodeFilter.SHOW_TEXT : 4;
    var walker = document.createTreeWalker(container, showText, null, false);
    var textNodes = [];
    while (walker.nextNode()) {
        textNodes.push(walker.currentNode);
    }
    textNodes.forEach(function(node) {
        if (!node || !node.parentNode || !node.nodeValue || !node.nodeValue.trim()) {
            return;
        }
        var fragment = buildSimilarHighlightFragment(node.nodeValue, highlightSpec);
        if (fragment) {
            node.parentNode.replaceChild(fragment, node);
        }
    });
    return container.innerHTML;
}

var ALLAH_TAG_PLACEHOLDER_PATTERN = /\uE000(\d+)\uE001/g;
var HTML_TAG_CAPTURE_PATTERN = /<[^>]*>/g;
var ENGLISH_ALLAH_PATTERN = /\bAll[aā]h\b/gi;
var ARABIC_DIACRITIC_BLOCK = "\u0610-\u061A\u064B-\u065F\u0670\u06D6-\u06ED\u0640";
var ARABIC_ALLAH_PATTERN = new RegExp("(?:\\u0671|\\u0627)?\\u0644[" + ARABIC_DIACRITIC_BLOCK + "]*\\u0644[" +
    ARABIC_DIACRITIC_BLOCK + "]*\\u0647[" + ARABIC_DIACRITIC_BLOCK + "]*", "g");

function styleAllahMentions(htmlText) {
    var raw = htmlText == null ? '' : String(htmlText);
    if (!raw) {
        return '';
    }
    var placeholders = [];
    var tokenized = raw.replace(HTML_TAG_CAPTURE_PATTERN, function(tag) {
        var marker = '\uE000' + placeholders.length + '\uE001';
        placeholders.push(tag);
        return marker;
    });
    tokenized = tokenized
        .replace(ENGLISH_ALLAH_PATTERN, function(match) {
            return '<span class="allah-mention">' + match + '</span>';
        })
        .replace(ARABIC_ALLAH_PATTERN, function(match) {
            return '<span class="allah-mention">' + match + '</span>';
        });
    return tokenized.replace(ALLAH_TAG_PLACEHOLDER_PATTERN, function(_, idx) {
        var parsed = Number(idx);
        return Number.isFinite(parsed) && placeholders[parsed] ? placeholders[parsed] : '';
    });
}

function truncateWordsPreservingFormatting(text, maxWords) {
    var raw = (text == null ? '' : String(text)).trim();
    if (!raw) {
        return '';
    }
    var matcher = raw.match(/\S+/g);
    if (!matcher || matcher.length <= maxWords) {
        return raw;
    }
    var count = 0;
    var idx = 0;
    var rgx = /\S+/g;
    var m;
    while ((m = rgx.exec(raw)) !== null) {
        count += 1;
        idx = m.index + m[0].length;
        if (count >= maxWords) {
            break;
        }
    }
    return raw.substring(0, idx).trim() + '...';
}

function loadBrowseBooks() {
    var bookSelect = document.getElementById('browseBookSelect');
    var submitBtn = document.getElementById('browseSubmitBtn');
    if (!bookSelect) {
        return;
    }
    setBrowsePanelVisible(false);
    setBrowseBookFieldVisible(false);
    updateBrowseSubmitState({ book: '' });
    var meta = document.getElementById('browseMeta');
    if (meta) {
        meta.textContent = 'Select a book card, then choose optional filters and submit.';
    }
    if (!bookSelect.dataset.bound) {
        bookSelect.addEventListener('change', function() {
            indicateActionButtonPending('browseSubmitBtn');
            handleBrowseSelectionChange(true);
        });
        browseFacetConfig.forEach(function(config) {
            var select = document.getElementById(config.selectId);
            if (select) {
                select.addEventListener('change', function() {
                    indicateActionButtonPending('browseSubmitBtn');
                    handleBrowseSelectionChange(false);
                });
            }
        });
        if (submitBtn) {
            submitBtn.addEventListener('click', function() {
                clearActionButtonPending('browseSubmitBtn');
                startBrowseFlow();
            });
        }
        bookSelect.dataset.bound = 'true';
    }

    fetch('/v1/browse/books')
        .then(function(resp) { return resp.json(); })
        .then(function(data) {
            populateBrowseBooks(data || []);
        })
        .catch(function() {
            if (meta) {
                meta.textContent = 'Unable to load books right now.';
            }
        });
}

function setBrowseBookFieldVisible(visible) {
    var field = document.getElementById('browseBookField');
    if (!field) {
        return;
    }
    field.classList.toggle('is-hidden', !visible);
}

function setBrowsePanelVisible(visible) {
    var panel = document.getElementById('browseFacetPanel');
    if (!panel) {
        return;
    }
    panel.classList.toggle('is-hidden', !visible);
}

function populateBrowseBooks(books) {
    var bookSelect = document.getElementById('browseBookSelect');
    var bookList = document.getElementById('browseBookList');
    if (!bookSelect) {
        return;
    }
    bookSelect.innerHTML = '<option value=\"\">Select a book</option>';
    if (bookList) {
        bookList.innerHTML = '';
    }
    books.forEach(function(item) {
        var name = item.name || item.key || '';
        if (!name) {
            return;
        }
        var count = item.count || 0;
        var option = document.createElement('option');
        option.value = name;
        option.textContent = name + ' (' + formatHadithCount(count) + ')';
        bookSelect.appendChild(option);

        if (bookList) {
            var card = document.createElement('div');
            card.className = 'browse-book-card';
            card.setAttribute('data-book', name);
            card.innerHTML = '<div class=\"browse-book-head\">' +
                '<span class=\"browse-book-icon\" aria-hidden=\"true\"><i class=\"fa fa-book\"></i></span>' +
                '<div class=\"browse-book-copy\">' +
                '<div class=\"browse-book-title\">' + escapeHtml(name) + '</div>' +
                '<div class=\"browse-book-count\">' + count + ' narrations</div>' +
                '</div></div>';
            card.addEventListener('click', function() {
                setBrowsePanelVisible(true);
                setBrowseBookFieldVisible(true);
                bookSelect.value = name;
                indicateActionButtonPending('browseSubmitBtn');
                if (bookList) {
                    Array.prototype.slice.call(bookList.querySelectorAll('.browse-book-card')).forEach(function(node) {
                        node.classList.toggle('is-active', node === card);
                    });
                }
                handleBrowseSelectionChange(true);
                var panel = document.getElementById('browse');
                if (panel) {
                    panel.scrollIntoView({ behavior: 'smooth', block: 'start' });
                }
            });
            bookList.appendChild(card);
        }
    });
}

function resetBrowseFacetSelects() {
    browseFacetConfig.forEach(function(config) {
        updateFacetSelect(config, [], '');
    });
}

function handleBrowseSelectionChange(resetFacets) {
    var selections = getBrowseSelections();
    var meta = document.getElementById('browseMeta');
    if (!selections.book) {
        resetBrowseFacetSelects();
        updateBrowseSubmitState(selections);
        if (meta) {
            meta.textContent = 'Select a book card, then choose optional filters and submit.';
        }
        return;
    }
    setBrowsePanelVisible(true);
    setBrowseBookFieldVisible(true);
    if (resetFacets) {
        selections.volume = '';
        selections.part = '';
        selections.section = '';
        selections.chapter = '';
    }
    if (meta) {
        meta.textContent = 'Loading filters...';
    }
    updateBrowseSubmitState(selections);
    fetchBrowseFacets(selections);
}

function fetchBrowseFacets(filters) {
    var activeFilters = cloneFacetSelections(filters || getBrowseSelections());
    activeFilters.book = (filters && filters.book) ? filters.book : getSelectValue('browseBookSelect');
    var url = buildFacetsUrl(activeFilters);
    fetch(url)
        .then(function(resp) { return resp.json(); })
        .then(function(data) {
            applyBrowseFacets(data, activeFilters);
        })
        .catch(function() {
            var meta = document.getElementById('browseMeta');
            if (meta) {
                meta.textContent = 'Unable to load filters for this book.';
            }
            resetBrowseFacetSelects();
            updateBrowseSubmitState(activeFilters);
        });
}

function applyBrowseFacets(data, selections) {
    var facets = (data && data.facets) ? data.facets : {};
    var currentSelections = cloneFacetSelections(selections || getBrowseSelections());
    currentSelections.book = (selections && selections.book) ? selections.book : getSelectValue('browseBookSelect');
    var resolvedSelections = cloneFacetSelections(currentSelections);
    resolvedSelections.book = currentSelections.book;
    browseFacetConfig.forEach(function(config) {
        var items = facets[config.key] || [];
        var selectedValue = resolvedSelections[config.key];
        resolvedSelections[config.key] = updateFacetSelect(config, items, selectedValue);
    });
    var meta = document.getElementById('browseMeta');
    if (meta) {
        var summary = buildFacetSummary(facets);
        meta.textContent = summary ? ('Available: ' + summary + '.') : 'No facets found for this book.';
    }
    if (facetSelectionsChanged(currentSelections, resolvedSelections)) {
        fetchBrowseFacets(resolvedSelections);
        return;
    }
    updateBrowseSubmitState(resolvedSelections);
}

function getBrowseSelections() {
    return {
        book: getSelectValue('browseBookSelect'),
        volume: getSelectValue('browseVolumeSelect'),
        part: getSelectValue('browsePartSelect'),
        section: getSelectValue('browseSectionSelect'),
        chapter: getSelectValue('browseChapterSelect')
    };
}

function updateBrowseSubmitState(selections) {
    var btn = document.getElementById('browseSubmitBtn');
    if (!btn) {
        return;
    }
    var currentSelections = selections || getBrowseSelections();
    btn.disabled = !currentSelections.book;
    if (btn.disabled) {
        clearActionButtonPending('browseSubmitBtn');
    }
}

function startBrowseFlow(scopedSelections) {
    var selections = scopedSelections || getBrowseSelections();
    if (!selections.book) {
        return;
    }
    var query = buildQueryFromFilters(selections);
    var sortFields = buildSortFields(selections);
    redirectToSearchResult(query, 1, sortFields, 'read', '', searchMatchMode, 'browse');
}

function sanitizeQueryValue(value) {
    return value.replace(/\"/g, '').trim();
}

function escapeSearchTermQuotes(value) {
    return String(value || '').replace(/\\/g, '\\\\').replace(/"/g, '\\"');
}

function humanizeFacetLabel(key) {
    if (!key) {
        return 'Section';
    }
    return key.charAt(0).toUpperCase() + key.slice(1);
}

function changeCardWidth() {
    return;
}

function validQuery(query) {
    if (query.trim().length < 2) {
        return false;
    }
    return true;
}

/**
 * Main method responsible for displaying queries using Vue.js. Stores the
 * created Vue instance in the global vueApp variable.
 */
function setupVue(query, page, sortFields) {
    // clear welcome page content
    document.getElementById('welcome').innerHTML = '';
    // Setup hadith vue component
    vueApp = new Vue({
        el: '#hadithView',
        data: {
            narrations: [],
            allNarrations: [],
            narrationsLoading: true,
            queryStr: query,
            sortFields: sortFields,
            page: (isReadingMode(query) || isCollectionMode()) ? page : 1,
            totalHits: 0,
            pageSize: isReadingMode(query) ? READING_PAGE_SIZE : 20,
            book_blurbs: bookBlurbs,
            readingMode: isReadingMode(query),
            collectionMode: isCollectionMode(),
            collectionId: resolveCollectionIdParam(),
            collectionTitle: '',
            collectionMeta: null,
            isEditingCollectionTitle: false,
            editingCollectionTitle: '',
            entryContext: resolveEntryContextParam(),
            authStateProxy: authState,
            taxonomy: {},
            activeTopicTags: getInitialTopicTags(),
            tagFilterExpanded: false,
            topicTagFacets: {},
            baseNarrationTotal: 0,
            searchResultOrdinalMap: {},
            visibleNarrationCount: INITIAL_VISIBLE_NARRATIONS,
            narrationRevealObserver: null,
            arabicSuggestionObserver: null,
            arabicSuggestionLoading: false,
            arabicSuggestionTerms: [],
            arabicSuggestionInputTerms: [],
            arabicSuggestionToastShown: false,
            arabicSuggestionThresholdPassed: false,
            collections: userCollectionsCache,
            collectionSearchQuery: ''
        },
        // runs when the Vue instance has initialized.
        mounted: function() {
            this.loadTaxonomy();
            this.fetchNarrations();
            this.initCollectionSidebar();
            var self = this;
            document.addEventListener('click', function(e) {
                if (!e.target.closest('.chip-dropdown')) {
                    self.narrations.forEach(function(n) {
                        n.similarDropdownOpen = false;
                        n.quranicDropdownOpen = false;
                    });
                }
            });
        },
        beforeDestroy: function() {
        },
        computed: {
            queryState: function() {
                return extractQueryState(this.queryStr || '');
            },
            activeScopeFilters: function() {
                return this.queryState.scopeFilters;
            },
            hasActiveScope: function() {
                return this.queryState.hasScope;
            },
            hasKeywordQuery: function() {
                return !!this.queryState.keywordQuery;
            },
            hasMoreNarrationsToReveal: function() {
                if (this.collectionMode) {
                    return false;
                }
                // When tags are active, compare against matching narrations count
                var targetCount = this.activeTopicTags.length > 0
                    ? this.matchingNarrationsTotal
                    : this.filteredNarrationTotal;
                return this.narrations.length < targetCount;
            },
            scopeBreadcrumbText: function() {
                if (!this.hasActiveScope) {
                    return '';
                }
                var parts = [];
                if (this.activeScopeFilters.book) {
                    parts.push(this.activeScopeFilters.book);
                }
                if (this.activeScopeFilters.volume) {
                    parts.push(formatFacetDisplay('volume', this.activeScopeFilters.volume));
                }
                if (this.activeScopeFilters.part) {
                    parts.push(this.activeScopeFilters.part);
                }
                if (this.activeScopeFilters.section) {
                    parts.push(this.activeScopeFilters.section);
                }
                if (this.activeScopeFilters.chapter) {
                    parts.push(this.activeScopeFilters.chapter);
                }
                return parts.join(' / ');
            },
            totalPages: function() {
                var hits = Number(this.totalHits) || 0;
                return Math.max(1, Math.ceil(hits / this.pageSize));
            },
            pageLabel: function() {
                return 'Page ' + this.page + ' of ' + this.totalPages;
            },
            resultsHeadingText: function() {
                if (this.collectionMode) {
                    if (this.activeTopicTags.length > 0) {
                        return 'Showing ' + this.matchingNarrationsCount + '/' + this.filteredNarrationTotal + ' saved hadith';
                    }
                    return this.collectionTitle || 'Saved Hadith';
                }
                if (this.readingMode) {
                    // In reading mode, use server counts (totalHits is filtered, baseNarrationTotal is total scope)
                    if (this.activeTopicTags.length > 0) {
                        var filteredCount = Number(this.totalHits) || 0;
                        var baseCount = Number(this.baseNarrationTotal) || 0;
                        return 'Showing ' + filteredCount + '/' + baseCount + ' hadith';
                    }
                    return (Number(this.baseNarrationTotal) || 0) + ' hadith found.';
                }
                if (this.activeTopicTags.length > 0) {
                    return 'Showing ' + this.matchingNarrationsCount + '/' + this.filteredNarrationTotal + ' results';
                }
                return (Number(this.totalHits) || 0) + ' results found.';
            },
            matchingNarrationsCount: function() {
                var self = this;
                // In reading mode or collection mode, filtering is done server-side
                // so totalHits/baseNarrationTotal is the correct count
                if (this.readingMode || this.collectionMode) {
                    var base = Number(this.baseNarrationTotal) || 0;
                    if (base > 0) {
                        return base;
                    }
                    return Number(this.totalHits) || 0;
                }
                // In search mode, count matching items from loaded results
                if (!this.activeTopicTags.length) {
                    return Array.isArray(this.allNarrations) ? this.allNarrations.length : 0;
                }
                return this.allNarrations.filter(function(item) {
                    return self.matchesActiveTopicTags(item);
                }).length;
            },
            filteredNarrationsAll: function() {
                var self = this;
                // Filter by active topic tags
                if (!this.activeTopicTags.length) {
                    return this.allNarrations;
                }
                return this.allNarrations.filter(function(item) {
                    return self.matchesActiveTopicTags(item);
                });
            },
            filteredNarrationTotal: function() {
                // Total results from server (not just the loaded page)
                var base = Number(this.baseNarrationTotal) || 0;
                if (base > 0) {
                    return base;
                }
                return Number(this.totalHits) || 0;
            },
            unfilteredNarrationTotal: function() {
                var base = Number(this.baseNarrationTotal) || 0;
                if (base > 0) {
                    return base;
                }
                return Array.isArray(this.allNarrations) ? this.allNarrations.length : 0;
            },
            resultsStatusText: function() {
                var visibleCount = Array.isArray(this.narrations) ? this.narrations.length : 0;
                if (this.collectionMode) {
                    return 'Showing ' + visibleCount + ' / ' + this.filteredNarrationTotal + ' saved hadith';
                }
                if (this.readingMode) {
                    // When topic tags are active, show "X/Y hadith" where X=filtered, Y=base
                    if (this.activeTopicTags.length > 0) {
                        var filteredCount = this.totalHits || 0;
                        var baseCount = this.baseNarrationTotal || 0;
                        if (filteredCount !== baseCount) {
                            return 'Showing ' + filteredCount + ' / ' + baseCount + ' hadith';
                        }
                    }
                    return 'Showing ' + visibleCount + ' / ' + this.baseNarrationTotal + ' hadith';
                }
                return 'Showing ' + visibleCount + ' / ' + this.filteredNarrationTotal + ' results';
            },
            matchingNarrationsTotal: function() {
                // When tags are active, show count of matching narrations
                var self = this;
                if (!this.activeTopicTags.length) {
                    return this.filteredNarrationTotal;
                }
                return this.allNarrations.filter(function(item) {
                    return self.matchesActiveTopicTags(item);
                }).length;
            },
            tagFilterOptions: function() {
                var taxonomy = this.taxonomy || {};
                var activeTags = this.activeTopicTags || [];
                var facetCounts = this.topicTagFacets || {};
                var counts = {};
                // Use backend facet counts for accurate totals across full result set
                // (both in reading mode and search mode)
                Object.keys(facetCounts).forEach(function(tag) {
                    if (!tag) {
                        return;
                    }
                    var count = Number(facetCounts[tag]);
                    counts[tag] = isNaN(count) ? 0 : count;
                });
                return Object.keys(counts).concat(activeTags.filter(function(tag) {
                    return !(tag in counts);
                })).filter(function(tag, index, tags) {
                    return !!tag && tags.indexOf(tag) === index;
                }).filter(function(tag) {
                    // Filter out parent tags that are not taggable (e.g., "prayer" when its children are the selectable tags)
                    var tagInfo = taxonomy[tag];
                    return !tagInfo || tagInfo.taggable !== false;
                }).map(function(tag) {
                    return {
                        slug: tag,
                        label: (taxonomy[tag] && taxonomy[tag].en) || tag,
                        count: counts[tag] || 0
                    };
                }).sort(function(left, right) {
                    var leftActive = activeTags.indexOf(left.slug) >= 0 ? 1 : 0;
                    var rightActive = activeTags.indexOf(right.slug) >= 0 ? 1 : 0;
                    if (rightActive !== leftActive) {
                        return rightActive - leftActive;
                    }
                    if (right.count !== left.count) {
                        return right.count - left.count;
                    }
                    return left.label.localeCompare(right.label);
                });
            },
            visibleTagFilterOptions: function() {
                var options = Array.isArray(this.tagFilterOptions) ? this.tagFilterOptions : [];
                if (this.tagFilterExpanded) {
                    return options;
                }
                return options.slice(0, INITIAL_VISIBLE_TAG_FILTERS);
            },
            hasCollapsedTagFilters: function() {
                return Array.isArray(this.tagFilterOptions)
                    && this.tagFilterOptions.length > INITIAL_VISIBLE_TAG_FILTERS;
            },
            isAuthenticated: function() {
                return this.authStateProxy && this.authStateProxy.authenticated;
            },
            filteredCollections: function() {
                var self = this;
                var collections = this.collections || [];
                var query = (this.collectionSearchQuery || '').toLowerCase().trim();
                if (!query) {
                    return collections;
                }
                return collections.filter(function(col) {
                    return (col.name || '').toLowerCase().indexOf(query) >= 0;
                });
            }
        },
        methods: {
            loadTaxonomy: function() {
                var self = this;
                return fetchTaxonomyMap()
                    .then(function(map) {
                        self.taxonomy = map;
                        return map;
                    })
                    .catch(function() {
                        self.taxonomy = {};
                        return {};
                    });
            },
            ensureEditorTaxonomy: function() {
                if (this.taxonomy && Object.keys(this.taxonomy).length) {
                    return Promise.resolve(this.taxonomy);
                }
                return this.loadTaxonomy();
            },
            canEditNarrations: function() {
                return currentUserCanEditHadith();
            },
            toggleTagFilterExpansion: function() {
                this.tagFilterExpanded = !this.tagFilterExpanded;
            },
            taxonomyLabel: function(slug) {
                return (this.taxonomy[slug] && this.taxonomy[slug].en) || slug;
            },
            taxonomyCategory: function(slug) {
                return (this.taxonomy[slug] && this.taxonomy[slug].category) || 'other';
            },
            taxonomyParent: function(slug) {
                return (this.taxonomy[slug] && this.taxonomy[slug].parent) || '';
            },
            syncTopicFilterUrl: function() {
                if (!window.history || !window.history.replaceState) {
                    return;
                }
                try {
                    var url = new URL(window.location.href);
                    url.searchParams.delete('topic_tags');
                    this.activeTopicTags.forEach(function(tag) {
                        url.searchParams.append('topic_tags', tag);
                    });
                    if (!this.readingMode && !this.collectionMode) {
                        url.searchParams.delete('page');
                    }
                    window.history.replaceState({}, '', url.toString());
                } catch (e) {
                    // ignore
                }
            },
            matchesActiveTopicTags: function(narration) {
                if (!this.activeTopicTags.length) {
                    return true;
                }
                var tags = Array.isArray(narration && narration.topic_tags) ? narration.topic_tags : [];
                return this.activeTopicTags.every(function(tag) {
                    return tags.indexOf(tag) >= 0;
                });
            },
            applyTagFilter: function(slug) {
                if (!slug) {
                    return;
                }
                var idx = this.activeTopicTags.indexOf(slug);
                if (idx >= 0) {
                    this.activeTopicTags.splice(idx, 1);
                } else {
                    this.activeTopicTags.push(slug);
                }
                // Keep tag filter collapsed when selecting tags
                this.tagFilterExpanded = false;
                this.visibleNarrationCount = INITIAL_VISIBLE_NARRATIONS;
                this.syncTopicFilterUrl();
                this.fetchNarrations();
            },
            clearTagFilters: function() {
                if (!this.activeTopicTags.length) {
                    return;
                }
                this.activeTopicTags = [];
                this.tagFilterExpanded = false;
                this.visibleNarrationCount = INITIAL_VISIBLE_NARRATIONS;
                this.syncTopicFilterUrl();
                this.fetchNarrations();
            },
            clearScope: function() {
                var queryState = extractQueryState(this.queryStr || '');
                var nextQuery = queryState.keywordQuery || '*:*';
                redirectToSearchResult(nextQuery, 1, this.sortFields || '', '', '', searchMatchMode, 'search');
            },
            buildCollectionViewUrl: function(collectionId) {
                return '/collection/' + encodeURIComponent(collectionId);
            },
            isCollectionActive: function(collectionId) {
                return this.collectionMode && this.collectionId === collectionId;
            },
            initCollectionSidebar: function() {
                var self = this;
                var sidebar = document.getElementById('collectionSidebar');
                var collapseBtn = document.getElementById('sidebarCollapseBtn');
                var expandBtn = document.getElementById('sidebarExpandBtn');
                var searchInput = document.getElementById('collectionSearchInput');

                if (!sidebar) return;

                // Collapse button
                if (collapseBtn) {
                    collapseBtn.addEventListener('click', function() {
                        sidebar.classList.add('is-collapsed');
                        if (expandBtn) {
                            expandBtn.style.display = 'flex';
                        }
                    });
                }

                // Expand button
                if (expandBtn) {
                    expandBtn.addEventListener('click', function() {
                        sidebar.classList.remove('is-collapsed');
                        expandBtn.style.display = 'none';
                    });
                }

                // Search input
                if (searchInput) {
                    searchInput.addEventListener('input', function(e) {
                        self.collectionSearchQuery = e.target.value;
                    });
                }

                // Update collections when auth state changes
                this.$watch('authStateProxy', function(newAuthState) {
                    if (newAuthState && newAuthState.authenticated) {
                        // Reload collections when user logs in
                        apiJSON('/v1/collections', { method: 'GET' }).then(function(resp) {
                            if (resp.ok && resp.data && resp.data.collections) {
                                self.collections = resp.data.collections;
                            }
                        });
                    } else {
                        self.collections = [];
                    }
                });
            },
            searchResultOrdinalKey: function(narration) {
                return narration ? String(narration._id || narration.id || '').trim() : '';
            },
            assignSearchResultOrdinal: function(narration, fallbackOrdinal) {
                if (!narration || this.readingMode || this.collectionMode) {
                    return narration;
                }
                var key = this.searchResultOrdinalKey(narration);
                var resolvedOrdinal = 0;
                if (key && this.searchResultOrdinalMap[key]) {
                    resolvedOrdinal = Number(this.searchResultOrdinalMap[key]) || 0;
                }
                if (resolvedOrdinal < 1) {
                    resolvedOrdinal = Number(narration._resultOrdinal);
                }
                if (isNaN(resolvedOrdinal) || resolvedOrdinal < 1) {
                    resolvedOrdinal = Number(fallbackOrdinal) || 0;
                }
                if (resolvedOrdinal > 0) {
                    narration._resultOrdinal = resolvedOrdinal;
                    if (key && !this.activeTopicTags.length) {
                        this.searchResultOrdinalMap[key] = resolvedOrdinal;
                    }
                }
                return narration;
            },
            resultOrdinal: function(narration, index) {
                if (!this.readingMode && !this.collectionMode) {
                    var storedOrdinal = Number(narration && narration._resultOrdinal);
                    if (!isNaN(storedOrdinal) && storedOrdinal > 0) {
                        return storedOrdinal;
                    }
                    return Number(index) + 1;
                }
                var pageNum = Number(this.page);
                if (isNaN(pageNum) || pageNum < 1) {
                    pageNum = 1;
                }
                var size = Number(this.pageSize);
                if (isNaN(size) || size < 1) {
                    size = 20;
                }
                var idx = Number(index);
                if (isNaN(idx) || idx < 0) {
                    idx = 0;
                }
                return ((pageNum - 1) * size) + idx + 1;
            },
            narrationReferenceLine: function(narration) {
                if (!narration) {
                    return '';
                }
                var parts = [];
                if (narration.book) {
                    parts.push(strip(narration.book));
                }
                if (narration.volume) {
                    parts.push(strip(narration.volume));
                }
                if (narration.chapter) {
                    parts.push(strip(narration.chapter));
                } else if (narration.section) {
                    parts.push(strip(narration.section));
                } else if (narration.part) {
                    parts.push(strip(narration.part));
                }
                if (narration.number) {
                    parts.push('Hadith #' + strip(narration.number));
                }
                return parts.join(' · ');
            },
            plainExcerpt: function(rawText, maxWords) {
                var text = strip(rawText || '').replace(/\s+/g, ' ').trim();
                if (!text) {
                    return '';
                }
                var words = text.split(' ');
                if (words.length <= maxWords) {
                    return text;
                }
                return words.slice(0, maxWords).join(' ') + '...';
            },
            englishPreview: function(narration) {
                return this.plainExcerpt((narration && (narration.englishContent || narration.english)) || '', 34);
            },
            arabicPreview: function(narration) {
                return this.plainExcerpt((narration && (narration.arabicContent || narration.arabic)) || '', 28);
            },
            toggleNarrationExpanded: function(narration) {
                if (!narration) {
                    return;
                }
                narration.expanded = !narration.expanded;
                if (!narration.expanded) {
                    narration.detailsOpen = false;
                    narration.relatedOpen = false;
                }
            },
            toggleNarrationDetails: function(narration) {
                if (!narration) {
                    return;
                }
                narration.detailsOpen = !narration.detailsOpen;
            },
            toggleNarrationRelated: function(narration) {
                if (!narration || !Array.isArray(narration.related) || narration.related.length === 0) {
                    return;
                }
                narration.relatedOpen = !narration.relatedOpen;
            },
            activateNarrationTab: function(evt) {
                var btn = evt && evt.currentTarget ? evt.currentTarget : null;
                if (!btn) {
                    return;
                }
                if (window.bootstrap && window.bootstrap.Tab) {
                    window.bootstrap.Tab.getOrCreateInstance(btn).show();
                    return;
                }
                var nav = btn.closest('.nav-tabs');
                if (nav) {
                    Array.prototype.slice.call(nav.querySelectorAll('.nav-link')).forEach(function(node) {
                        node.classList.remove('active');
                        node.setAttribute('aria-selected', 'false');
                    });
                }
                btn.classList.add('active');
                btn.setAttribute('aria-selected', 'true');
                var targetSelector = btn.getAttribute('data-bs-target');
                if (!targetSelector) {
                    return;
                }
                var tabContent = btn.closest('section');
                if (!tabContent) {
                    return;
                }
                Array.prototype.slice.call(tabContent.querySelectorAll('.tab-pane')).forEach(function(pane) {
                    pane.classList.remove('show');
                    pane.classList.remove('active');
                });
                var targetPane = tabContent.querySelector(targetSelector);
                if (targetPane) {
                    targetPane.classList.add('show');
                    targetPane.classList.add('active');
                }
            },
            decorateNarration: function(value) {
                if (!value) {
                    return value;
                }
                if (value.notes) {
                    value.notes = marked(value.notes);
                }
                if (value.volume && String(value.volume).indexOf('Volume ') !== 0) {
                    value.volume = "Volume " + value.volume;
                }
                value = socialMediaDecoratedHadith(value);
                value = decorateNarrationForSimilarity(value);
                return value;
            },
            refreshVisibleNarrations: function() {
                if (this.collectionMode) {
                    this.narrations = this.filteredNarrationsAll.slice();
                } else {
                    this.narrations = this.filteredNarrationsAll.slice(0, this.visibleNarrationCount);
                }
                this.narrations.forEach(function(item) {
                    if (!item._similarPrefetched) {
                        item._similarPrefetched = true;
                        this.prefetchSimilarCount(item);
                    }
                    if (!item._quranicInsightsPrefetched) {
                        item._quranicInsightsPrefetched = true;
                        this.prefetchQuranicInsightsCount(item);
                    }
                }, this);
                var self = this;
                this.$nextTick(function() {
                    self.setupNarrationRevealObserver();
                    self.setupArabicSuggestionObserver();
                });
            },
            increaseVisibleNarrations: function() {
                if (this.collectionMode || !this.hasMoreNarrationsToReveal) {
                    return;
                }
                this.visibleNarrationCount = Math.min(
                    this.filteredNarrationTotal,
                    (Math.max(0, Number(this.visibleNarrationCount) || 0) + REVEAL_BATCH_SIZE)
                );
                this.refreshVisibleNarrations();
            },
            teardownNarrationRevealObserver: function() {
                if (this.narrationRevealObserver && typeof this.narrationRevealObserver.disconnect === 'function') {
                    this.narrationRevealObserver.disconnect();
                }
                this.narrationRevealObserver = null;
            },
            teardownArabicSuggestionObserver: function() {
                if (this.arabicSuggestionObserver && typeof this.arabicSuggestionObserver.disconnect === 'function') {
                    this.arabicSuggestionObserver.disconnect();
                }
                this.arabicSuggestionObserver = null;
            },
            setupNarrationRevealObserver: function() {
                this.teardownNarrationRevealObserver();
                if (this.collectionMode || !this.hasMoreNarrationsToReveal || typeof IntersectionObserver === 'undefined') {
                    return;
                }
                var sentinel = this.$refs && this.$refs.scrollSentinel;
                if (!sentinel) {
                    return;
                }
                var self = this;
                this.narrationRevealObserver = new IntersectionObserver(function(entries) {
                    entries.forEach(function(entry) {
                        if (entry && entry.isIntersecting) {
                            self.increaseVisibleNarrations();
                        }
                    });
                }, { rootMargin: '280px 0px' });
                this.narrationRevealObserver.observe(sentinel);
            },
            setupArabicSuggestionObserver: function() {
                this.teardownArabicSuggestionObserver();
                if (this.readingMode || this.collectionMode || this.arabicSuggestionThresholdPassed
                        || typeof IntersectionObserver === 'undefined') {
                    return;
                }
                var thresholdNode = this.$el
                    ? this.$el.querySelector('.hadith-entry[data-result-ordinal="21"]')
                    : null;
                if (!thresholdNode) {
                    return;
                }
                var self = this;
                this.arabicSuggestionObserver = new IntersectionObserver(function(entries) {
                    entries.forEach(function(entry) {
                        if (!entry || !entry.isIntersecting) {
                            return;
                        }
                        self.arabicSuggestionThresholdPassed = true;
                        self.maybeShowArabicSuggestionToast();
                        self.teardownArabicSuggestionObserver();
                    });
                }, { rootMargin: '0px 0px -12% 0px', threshold: 0.35 });
                this.arabicSuggestionObserver.observe(thresholdNode);
            },
            dismissArabicSuggestion: function() {
                this.arabicSuggestionLoading = false;
                this.arabicSuggestionTerms = [];
                this.arabicSuggestionInputTerms = [];
                this.arabicSuggestionToastShown = false;
                this.arabicSuggestionThresholdPassed = false;
                this.teardownArabicSuggestionObserver();
                closeArabicSuggestionToast();
            },
            maybeShowArabicSuggestionToast: function() {
                if (this.readingMode || this.collectionMode || this.arabicSuggestionToastShown) {
                    return;
                }
                if (!this.arabicSuggestionThresholdPassed) {
                    return;
                }
                if (!Array.isArray(this.arabicSuggestionTerms) || this.arabicSuggestionTerms.length < 1) {
                    return;
                }
                showArabicSuggestionToast(this.arabicSuggestionTerms.slice(0, 3));
                this.arabicSuggestionToastShown = true;
            },
            addArabicSuggestionToSearchBar: function(suggestionInput) {
                var suggestions = [];
                if (suggestionInput && String(suggestionInput).trim()) {
                    suggestions = [String(suggestionInput)];
                } else if (Array.isArray(this.arabicSuggestionTerms)) {
                    suggestions = this.arabicSuggestionTerms.slice();
                }
                suggestions = suggestions.map(function(term) {
                    return stripWrappingQuotes(term || '');
                }).filter(function(term) {
                    return !!term;
                });
                if (!suggestions.length) {
                    return;
                }
                if (!searchSelectControl) {
                    initSelect2('searchTerms');
                }
                if (!searchSelectControl) {
                    return;
                }

                var addedCount = 0;
                for (var i = 0; i < suggestions.length; i++) {
                    var suggestion = suggestions[i];
                    var token = suggestion.indexOf(' ') >= 0 ? ('"' + suggestion + '"') : suggestion;
                    var normalizedToken = normalizeTermForCompare(token);
                    var exists = (searchSelectControl.items || []).some(function(item) {
                        return normalizeTermForCompare(item) === normalizedToken;
                    });
                    if (exists) {
                        continue;
                    }
                    if (!searchSelectControl.options[token]) {
                        searchSelectControl.addOption({ value: token, text: token });
                    }
                    searchSelectControl.addItem(token, true);
                    addedCount++;
                }
                updateSearchPlaceholder(searchSelectControl);
                if (addedCount > 0) {
                    indicatePendingSearchTerms();
                    showSearchUpdateToast();
                }
                if (suggestionInput && this.arabicSuggestionTerms.length) {
                    var normalizedChosen = normalizeTermForCompare(suggestionInput);
                    this.arabicSuggestionTerms = this.arabicSuggestionTerms.filter(function(term) {
                        return normalizeTermForCompare(term) !== normalizedChosen;
                    });
                } else {
                    this.dismissArabicSuggestion();
                }
            },
            copyNarrationField: function(narration, field) {
                if (!narration) {
                    return;
                }
                var raw = '';
                if (field === 'arabic') {
                    raw = narration.arabicContent || narration.arabic || '';
                } else {
                    raw = narration.englishContent || narration.english || '';
                }
                var text = strip(raw || '').trim();
                if (!text) {
                    return;
                }
                if (navigator && navigator.clipboard && navigator.clipboard.writeText) {
                    navigator.clipboard.writeText(text)
                        .then(function() {
                            showToast('Copied ' + (field === 'arabic' ? 'Arabic' : 'English') + ' text.', 'information');
                        })
                        .catch(function() {
                            showToast('Unable to copy text.', 'warning');
                        });
                    return;
                }
                try {
                    var textArea = document.createElement('textarea');
                    textArea.value = text;
                    textArea.style.position = 'fixed';
                    textArea.style.opacity = '0';
                    document.body.appendChild(textArea);
                    textArea.select();
                    document.execCommand('copy');
                    document.body.removeChild(textArea);
                    showToast('Copied ' + (field === 'arabic' ? 'Arabic' : 'English') + ' text.', 'information');
                } catch (err) {
                    showToast('Unable to copy text.', 'warning');
                }
            },
            copyHadithStaticUrl: function(id) {
                var hadithId = (id || '').toString().trim();
                if (!hadithId) {
                    return;
                }
                this.resolveHadithShareUrl(hadithId).then(function(url) {
                    if (!url) {
                        showToast('Unable to build link.', 'warning');
                        return;
                    }
                    if (navigator && navigator.clipboard && navigator.clipboard.writeText) {
                        navigator.clipboard.writeText(url)
                            .then(function() {
                                showToast('Static URL copied.', 'information');
                            })
                            .catch(function() {
                                showToast('Unable to copy link.', 'warning');
                            });
                        return;
                    }
                    try {
                        var textArea = document.createElement('textarea');
                        textArea.value = url;
                        textArea.style.position = 'fixed';
                        textArea.style.opacity = '0';
                        document.body.appendChild(textArea);
                        textArea.select();
                        document.execCommand('copy');
                        document.body.removeChild(textArea);
                        showToast('Static URL copied.', 'information');
                    } catch (err) {
                        showToast('Unable to copy link.', 'warning');
                    }
                }).catch(function() {
                    showToast('Unable to build link.', 'warning');
                });
            },
            requestArabicSuggestion: function(resultNarrations) {
                this.dismissArabicSuggestion();
                if (this.readingMode || this.collectionMode) {
                    return;
                }
                var englishTerms = extractEnglishKeywordTerms(this.queryStr || '')
                    .map(function(term) { return stripWrappingQuotes(term); })
                    .filter(function(term) { return !!term; });
                if (!englishTerms.length) {
                    return;
                }
                var self = this;
                this.arabicSuggestionInputTerms = englishTerms.slice(0, 8);
                this.arabicSuggestionLoading = true;
                var fallbackSuggestions = deriveArabicSuggestionTermsFromNarrations(
                    resultNarrations || this.narrations,
                    this.queryStr || '',
                    ARABIC_SUGGESTION_LIMIT
                );
                var url = '/v1/terms/significant?size=' + ARABIC_SUGGESTION_FETCH_SIZE + '&inputTerms=' +
                    encodeURIComponent(this.arabicSuggestionInputTerms.join(','));
                fetch(url)
                    .then(function(resp) { return resp.json(); })
                    .then(function(data) {
                        var suggestions = filterArabicSuggestionCandidates(
                            data,
                            self.queryStr || '',
                            ARABIC_SUGGESTION_LIMIT
                        );
                        if (suggestions.length < ARABIC_SUGGESTION_LIMIT && fallbackSuggestions.length) {
                            fallbackSuggestions.forEach(function(term) {
                                if (suggestions.length >= ARABIC_SUGGESTION_LIMIT
                                        || suggestions.indexOf(term) !== -1) {
                                    return;
                                }
                                suggestions.push(term);
                            });
                        }
                        if (suggestions.length) {
                            self.arabicSuggestionTerms = suggestions;
                            self.maybeShowArabicSuggestionToast();
                        }
                    })
                    .catch(function() {
                        if (fallbackSuggestions.length) {
                            self.arabicSuggestionTerms = fallbackSuggestions;
                            self.maybeShowArabicSuggestionToast();
                            return;
                        }
                        self.dismissArabicSuggestion();
                    })
                    .finally(function() {
                        self.arabicSuggestionLoading = false;
                    });
            },
            // fetches more narrations to display using the API.
            fetchNarrations: function() {
                var self = this;
                this.narrationsLoading = true;
                this.dismissArabicSuggestion();
                this.teardownNarrationRevealObserver();
                this.narrations = [];
                this.allNarrations = [];
                this.topicTagFacets = {};
                this.baseNarrationTotal = 0;
                this.collectionTitle = '';
                this.collectionMeta = null;
                var applyIncomingNarrations = function(respJSON) {
                    if (respJSON.error) {
                        self.dismissArabicSuggestion();
                        swal("Oops...",
                            self.collectionMode
                                ? "Something went wrong while opening this collection."
                                : "Something went wrong while fetching your hadith, please try a different search.");
                        return;
                    }
                    var items = Array.isArray(respJSON.collection) ? respJSON.collection : [];
                    if (items.length < 1 && self.allNarrations.length === 0) {
                        self.dismissArabicSuggestion();
                        if (self.collectionMode) {
                            self.allNarrations = [];
                            self.narrations = [];
                            self.baseNarrationTotal = 0;
                            self.totalHits = Number(respJSON.totalResultSetSize) || 0;
                            self.topicTagFacets = respJSON.topicTagFacets || {};
                            return;
                        }
                        swal("Oops...",
                            "No results seem to match your query!",
                            "error");
                        return;
                    }
                    var incoming = items.map(function(value, idx) {
                        var narration = self.decorateNarration(value);
                        return self.assignSearchResultOrdinal(narration, idx + 1);
                    });
                    self.allNarrations = incoming;
                    if (!self.collectionMode) {
                        self.visibleNarrationCount = INITIAL_VISIBLE_NARRATIONS;
                    }
                    self.refreshVisibleNarrations();
                    // totalResultSetSize is the filtered count (e.g., 368 matching topic tag)
                    // baseResultSetSize is the unfiltered count (e.g., 1242 total in reading scope)
                    var filteredCount = Number(respJSON.totalResultSetSize) || incoming.length;
                    var baseCount = Number(respJSON.baseResultSetSize) || filteredCount;
                    self.baseNarrationTotal = baseCount;
                    self.totalHits = filteredCount;
                    if (!self.readingMode && !self.collectionMode && self.totalHits > 100) {
                        self.totalHits = 100;
                    }
                    self.topicTagFacets = respJSON.topicTagFacets || {};
                    self.requestArabicSuggestion(incoming);
                    self.$nextTick(function() {
                        self.focusNarrationFromQuery();
                    });
                };
                if (this.collectionMode) {
                    apiJSON('/v1/collections/' + encodeURIComponent(this.collectionId), { method: 'GET' })
                        .then(function(metaResp) {
                            if (metaResp.status === 401) {
                                showToast('Sign in to view your collection.', 'information');
                                openLoginModal();
                                return null;
                            }
                            if (!metaResp.ok || !metaResp.data || !metaResp.data.ok) {
                                swal('Collection unavailable', (metaResp.data && metaResp.data.message) || 'Unable to load this collection.', 'error');
                                return null;
                            }
                            self.collectionMeta = metaResp.data.collection || null;
                            self.collectionTitle = (self.collectionMeta && self.collectionMeta.name) || 'Saved Hadith';
                            var collectionUrl = '/v1/collections/' + encodeURIComponent(this.collectionId) +
                                '/hadith?page=' + self.page + '&per_page=' + self.pageSize;
                            self.activeTopicTags.forEach(function(tag) {
                                collectionUrl += '&topic_tags=' + encodeURIComponent(tag);
                            });
                            return apiJSON(collectionUrl, { method: 'GET' });
                        }.bind(this))
                        .then(function(resp) {
                            if (!resp) {
                                return;
                            }
                            applyIncomingNarrations(resp.data || {});
                        })
                        .catch(function() {
                            swal('Collection unavailable', 'Unable to load this collection right now.', 'error');
                        })
                        .finally(function() {
                            self.narrationsLoading = false;
                        });
                    return;
                }
                var xhr = new XMLHttpRequest();
                xhr.onload = function() {
                    if (xhr.readyState == XMLHttpRequest.DONE) {
                        var respJSON = {};
                        if (xhr.responseText) {
                            try {
                                respJSON = JSON.parse(xhr.responseText);
                            } catch (err) {
                                respJSON = { error: true };
                            }
                        }
                        applyIncomingNarrations(respJSON);
                    }
                }
                xhr.onerror = function() {
                    self.dismissArabicSuggestion();
                    swal("Oops...",
                        "Something went wrong while fetching your hadith, please try a different search.");
                };
                xhr.onloadend = function() {
                    self.narrationsLoading = false;
                };
                // Build query: in reading mode, include scope filters; in search mode, just use queryStr
                var queryToUse = this.queryStr;
                if (this.readingMode && this.hasActiveScope) {
                    // Use keywordQuery if present, otherwise use null (buildScopedQuery will use only scope)
                    var keywordPart = this.queryState.keywordQuery || null;
                    queryToUse = buildScopedQuery(keywordPart, this.activeScopeFilters);
                }
                var reqUrl = '/v1/narrations?q=' + encodeURIComponent(queryToUse) +
                '&page=' + (this.readingMode ? this.page : 1) +
                '&per_page=' + (this.readingMode ? this.pageSize : SEARCH_FETCH_LIMIT);
                if (this.sortFields) {
                    reqUrl += '&sort_fields=' + this.sortFields
                }
                this.activeTopicTags.forEach(function(tag) {
                    reqUrl += '&topic_tags=' + encodeURIComponent(tag);
                });
                if (this.readingMode) {
                    reqUrl += '&mode=read';
                }
                if (resolveSearchMatchModeParam() === 'strict') {
                    reqUrl += '&match_mode=strict';
                }
                xhr.open('GET', reqUrl);
                xhr.send();
            },
            narrationDomId: function(narration) {
                if (!narration) {
                    return '';
                }
                var id = narration._id || narration.id || '';
                return id ? ('hadith-' + domSafeHadithId(id)) : '';
            },
            focusNarrationFromQuery: function() {
                var focusId = getQueryStringValue('focus_id');
                if (!focusId) {
                    return;
                }
                var element = document.getElementById('hadith-' + domSafeHadithId(focusId));
                if (!element) {
                    return;
                }
                Array.prototype.slice.call(document.querySelectorAll('.hadith-card.hadith-focus')).forEach(function(node) {
                    if (node !== element) {
                        node.classList.remove('hadith-focus');
                    }
                });
                element.classList.add('hadith-focus');
                if (element.scrollIntoView) {
                    element.scrollIntoView({ behavior: 'smooth', block: 'start' });
                }
            },
            similarCountText: function(narration) {
                if (!narration || typeof narration.similarCount !== 'number' || narration.similarCount <= 0) {
                    return '';
                }
                return narration.similarCount + ' similar hadith found';
            },
            showSimilarTrigger: function(narration) {
                return !!(narration && typeof narration.similarCount === 'number' && narration.similarCount > 0);
            },
            prefetchSimilarCount: function(narration) {
                if (!narration) {
                    return;
                }
                if (narration.similarCountLoading || narration.similarCount !== null) {
                    return;
                }
                var sourceId = narration._id || narration.id;
                if (!sourceId) {
                    return;
                }
                var loadingStartedAt = Date.now();
                narration.similarCountLoading = true;
                fetch('/v1/narrations/similar?id=' + encodeURIComponent(sourceId) + '&per_page=0')
                    .then(function(resp) { return resp.json(); })
                    .then(function(data) {
                        var total = Number(data && data.totalResultSetSize);
                        if (isNaN(total)) {
                            narration.similarCount = (data && data.collection && data.collection.length) ? data.collection.length : 0;
                            return;
                        }
                        narration.similarCount = Math.max(0, total);
                    })
                    .catch(function() {
                        narration.similarCount = 0;
                    })
                    .finally(function() {
                        finishSimilarCountLoadingState(narration, loadingStartedAt);
                    });
            },
            toggleSimilarPanel: function(narration) {
                if (!narration) {
                    return;
                }
                if (narration.similarCountLoading || narration.similarCount === null) {
                    return;
                }
                if (narration.similarDropdownOpen) {
                    this.clearSimilarHighlightState(narration);
                    narration.similarDropdownOpen = false;
                    narration.similarOpen = false;
                    return;
                }
                this.narrations.forEach(function(item) {
                    if (item !== narration) {
                        item.similarOpen = false;
                        item.similarDropdownOpen = false;
                        item.similarHighlightKey = '';
                        item.similarHighlightTone = '';
                        item.quranicInsightsOpen = false;
                        item.quranicDropdownOpen = false;
                    }
                });
                narration.quranicInsightsOpen = false;
                narration.quranicDropdownOpen = false;
                narration.similarDropdownOpen = true;
                narration.similarOpen = false;
                if (!narration.similarItemsLoaded) {
                    this.fetchSimilarNarrations(narration, 10);
                }
            },
            fetchSimilarNarrations: function(narration, perPage) {
                var sourceId = narration ? (narration._id || narration.id) : '';
                if (!sourceId || narration.similarItemsLoading) {
                    return;
                }
                var loadingStartedAt = Date.now();
                narration.similarItemsLoading = true;
                if (narration.similarCount === null) {
                    narration.similarCountLoading = true;
                }
                narration.similarError = '';
                var size = perPage || 10;
                fetch('/v1/narrations/similar?id=' + encodeURIComponent(sourceId) + '&per_page=' + size)
                    .then(function(resp) { return resp.json(); })
                    .then(function(data) {
                        var incoming = (data && data.collection) ? data.collection : [];
                        var total = Number(data && data.totalResultSetSize);
                        narration.similarItems = incoming.map(function(item) {
                            if (item.volume && String(item.volume).indexOf('Volume') !== 0) {
                                item.volume = 'Volume ' + item.volume;
                            }
                            item.sharedSyntacticTokens = Array.isArray(item.sharedSyntacticTokens) ? item.sharedSyntacticTokens : [];
                            item.sharedDistinctiveTokens = Array.isArray(item.sharedDistinctiveTokens) ? item.sharedDistinctiveTokens : [];
                            item.sharedSignificantTerms = Array.isArray(item.sharedSignificantTerms) ? item.sharedSignificantTerms : [];
                            return item;
                        });
                        narration.similarItemsLoaded = true;
                        narration.similarCount = isNaN(total) ? narration.similarItems.length : Math.max(0, total);
                        if (!narration.similarItems.length || narration.similarCount <= 0) {
                            // If pre-count overestimates, reconcile state and hide the stale trigger/panel.
                            narration.similarItems = [];
                            narration.similarCount = 0;
                            narration.similarOpen = false;
                            showToast('No similar hadith found for this narration.', 'information');
                            return;
                        }
                        if (narration.similarActiveIndex >= narration.similarItems.length) {
                            narration.similarActiveIndex = 0;
                        }
                    })
                    .catch(function() {
                        narration.similarItems = [];
                        narration.similarItemsLoaded = true;
                        narration.similarCount = 0;
                        narration.similarError = 'Unable to load similar hadith right now.';
                    })
                    .finally(function() {
                        finishSimilarLoadingState(narration, loadingStartedAt);
                    });
            },
            selectSimilarTab: function(narration, index) {
                if (!narration || !narration._id) {
                    return;
                }
                if (index < 0 || index >= narration.similarItems.length) {
                    return;
                }
                this.clearSimilarHighlightState(narration);
                narration.similarActiveIndex = index;
                narration.similarDropdownOpen = false;
                narration.similarOpen = true;
            },
            quranicInsightsCountText: function(narration) {
                if (!narration || typeof narration.quranicInsightsCount !== 'number' || narration.quranicInsightsCount <= 0) {
                    return '';
                }
                return narration.quranicInsightsCount + ' Quranic verses matched';
            },
            showQuranicInsightsTrigger: function(narration) {
                return !!(narration && typeof narration.quranicInsightsCount === 'number' && narration.quranicInsightsCount > 0);
            },
            prefetchQuranicInsightsCount: function(narration) {
                if (!narration) {
                    return;
                }
                if (narration.quranicInsightsCountLoading || narration.quranicInsightsCount !== null) {
                    return;
                }
                var sourceId = narration._id || narration.id;
                if (!sourceId) {
                    narration.quranicInsightsCount = 0;
                    return;
                }
                var loadingStartedAt = Date.now();
                narration.quranicInsightsCountLoading = true;
                fetch('/v1/narrations/quranic_insights?id=' + encodeURIComponent(sourceId) + '&count_only=true')
                    .then(function(resp) { return resp.json(); })
                    .then(function(data) {
                        narration.quranicInsightsCount = Math.max(0, Number(data && data.count) || 0);
                    })
                    .catch(function() {
                        narration.quranicInsightsCount = 0;
                    })
                    .finally(function() {
                        finishQuranicInsightsCountLoadingState(narration, loadingStartedAt);
                    });
            },
            toggleQuranicInsightsPanel: function(narration) {
                if (!narration) {
                    return;
                }
                if (narration.quranicInsightsCountLoading || narration.quranicInsightsCount === null || narration.quranicInsightsCount <= 0) {
                    return;
                }
                if (narration.quranicDropdownOpen) {
                    narration.quranicDropdownOpen = false;
                    narration.quranicInsightsOpen = false;
                    return;
                }
                this.narrations.forEach(function(item) {
                    if (item !== narration) {
                        item.quranicInsightsOpen = false;
                        item.quranicDropdownOpen = false;
                        item.similarOpen = false;
                        item.similarDropdownOpen = false;
                        item.similarHighlightKey = '';
                        item.similarHighlightTone = '';
                    }
                });
                narration.similarOpen = false;
                narration.similarDropdownOpen = false;
                narration.quranicDropdownOpen = true;
                narration.quranicInsightsOpen = false;
                if (!narration.quranicInsightsItemsLoaded) {
                    this.fetchQuranicInsights(narration);
                }
            },
            fetchQuranicInsights: function(narration) {
                var sourceId = narration ? (narration._id || narration.id) : '';
                if (!sourceId || narration.quranicInsightsItemsLoading) {
                    return;
                }
                var self = this;
                var loadingStartedAt = Date.now();
                narration.quranicInsightsItemsLoading = true;
                if (narration.quranicInsightsCount === null) {
                    narration.quranicInsightsCountLoading = true;
                }
                narration.quranicInsightsError = '';
                fetch('/v1/narrations/quranic_insights?id=' + encodeURIComponent(sourceId))
                    .then(function(resp) { return resp.json(); })
                    .then(function(data) {
                        var incoming = Array.isArray(data && data.candidates) ? data.candidates : [];
                        narration.quranicInsightsItems = incoming.map(function(item) {
                            return {
                                verse_key: item.verse_key || '',
                                reference: item.reference || item.verse_key || '',
                                surah_name_english: item.surah_name_english || '',
                                surah_number: Number(item.surah_number) || 0,
                                ayah_number: Number(item.ayah_number) || 0,
                                text_english: item.text_english || '',
                                text_arabic: item.text_arabic || '',
                                combined_score: Number(item.combined_score) || 0,
                                shared_tags: Array.isArray(item.shared_tags) ? item.shared_tags : [],
                                tafsir_snippet_count: Number(item.tafsir_snippet_count) || 0,
                                sources: Array.isArray(item.sources) ? item.sources : [],
                                tafsir_snippets: Array.isArray(item.tafsir_snippets) ? item.tafsir_snippets.map(function(s) {
                                    return {
                                        tafsir_slug: s.tafsir_slug || '',
                                        tafsir_name: s.tafsir_name || '',
                                        commentary_text: s.commentary_text || '',
                                        source_url: s.source_url || '',
                                        section_title: s.section_title || '',
                                        commentary_score: Number(s.commentary_score) || 0
                                    };
                                }) : []
                            };
                        });
                        narration.quranicInsightsItemsLoaded = true;
                        narration.quranicInsightsCount = Math.max(0, Number(data && data.count) || narration.quranicInsightsItems.length);
                        if (!narration.quranicInsightsItems.length || narration.quranicInsightsCount <= 0) {
                            narration.quranicInsightsItems = [];
                            narration.quranicInsightsCount = 0;
                            narration.quranicInsightsOpen = false;
                            return;
                        }
                        if (narration.quranicInsightsActiveIndex >= narration.quranicInsightsItems.length) {
                            narration.quranicInsightsActiveIndex = 0;
                        }
                        self.ensureActiveQuranicInsightSummary(narration);
                    })
                    .catch(function() {
                        narration.quranicInsightsItems = [];
                        narration.quranicInsightsItemsLoaded = true;
                        narration.quranicInsightsCount = 0;
                        narration.quranicInsightsError = 'Unable to load Quranic insights right now.';
                    })
                    .finally(function() {
                        finishQuranicInsightsLoadingState(narration, loadingStartedAt);
                    });
            },
            selectQuranicInsightTab: function(narration, index) {
                if (!narration || !narration._id) {
                    return;
                }
                if (index < 0 || index >= narration.quranicInsightsItems.length) {
                    return;
                }
                narration.quranicInsightsActiveIndex = index;
                narration.quranicDropdownOpen = false;
                narration.quranicInsightsOpen = true;
            },
            quranicInsightTabTitle: function(item, index) {
                if (!item) {
                    return 'Verse ' + (index + 1);
                }
                return item.reference || item.verse_key || ('Verse ' + (index + 1));
            },
            activeQuranicInsight: function(narration) {
                if (!narration || !narration.quranicInsightsItems || !narration.quranicInsightsItems.length) {
                    return null;
                }
                var idx = narration.quranicInsightsActiveIndex || 0;
                if (idx < 0 || idx >= narration.quranicInsightsItems.length) {
                    idx = 0;
                }
                return narration.quranicInsightsItems[idx];
            },
            similarTabTitle: function(similar, index) {
                if (similar && similar.book && similar.number) {
                    return similar.book + ' #' + similar.number;
                }
                if (similar && similar.book) {
                    return similar.book;
                }
                if (similar && similar.number) {
                    return 'Hadith #' + similar.number;
                }
                return 'Similar hadith #' + (index + 1);
            },
            jumpLevelForSimilar: function(similar) {
                if (!similar) {
                    return 'book';
                }
                if (similar.chapter) {
                    return 'chapter';
                }
                if (similar.section) {
                    return 'section';
                }
                if (similar.part) {
                    return 'part';
                }
                if (similar.volume) {
                    return 'volume';
                }
                return 'book';
            },
            jumpToSimilarHadith: function(similar, level) {
                if (!similar || !similar.book) {
                    return;
                }
                var targetLevel = level || this.jumpLevelForSimilar(similar);
                var selections = {
                    book: strip(similar.book || '').trim(),
                    volume: '',
                    part: '',
                    section: '',
                    chapter: ''
                };
                if (targetLevel === 'volume' || targetLevel === 'part' || targetLevel === 'section' || targetLevel === 'chapter') {
                    selections.volume = strip(similar.volume || '').replace(/^Volume\s+/i, '').trim();
                }
                if (targetLevel === 'part' || targetLevel === 'section' || targetLevel === 'chapter') {
                    selections.part = strip(similar.part || '').trim();
                }
                if (targetLevel === 'section' || targetLevel === 'chapter') {
                    selections.section = strip(similar.section || '').trim();
                }
                if (targetLevel === 'chapter') {
                    selections.chapter = strip(similar.chapter || '').trim();
                }
                var nextQuery = buildQueryFromFilters(selections);
                var nextSort = buildSortFields(selections);
                // Redirect to filtered search results (page 1), not to specific hadith in reading mode
                redirectToSearchResult(nextQuery, 1, nextSort, null, null,
                    resolveSearchMatchModeParam(), 'browse');
            },
            similarityScorePercent: function(similar) {
                if (!similar) {
                    return 0;
                }
                var retrievalPercent = Number(similar.retrievalSimilarityPercent);
                if (!isNaN(retrievalPercent) && retrievalPercent > 0) {
                    return Math.max(0, Math.min(100, retrievalPercent));
                }
                var percent = Number(similar.similarityPercent);
                if (!isNaN(percent) && percent > 0) {
                    return Math.max(0, Math.min(100, percent));
                }
                var raw = Number(similar.similarityScore);
                if (isNaN(raw) || raw <= 0) {
                    return 0;
                }
                if (raw <= 1) {
                    return Math.max(0, Math.min(100, raw * 100));
                }
                return Math.max(0, Math.min(100, raw));
            },
            similarityScoreClass: function(similar) {
                var percent = this.similarityScorePercent(similar);
                if (percent >= 90) {
                    return 'is-high';
                }
                if (percent >= 82) {
                    return 'is-good';
                }
                if (percent >= 74) {
                    return 'is-medium';
                }
                return 'is-low';
            },
            similarWhyMatchedItems: function(similar) {
                if (!similar) {
                    return [];
                }
                var items = [];
                var similarityPercent = this.similarityScorePercent(similar);
                if (similarityPercent > 0) {
                    items.push({
                        text: 'Similarity ' + similarityPercent.toFixed(1) + '%',
                        tone: 'is-similarity'
                    });
                }
                var semanticPercent = this.safeSimilarPercent(similar.semanticSimilarityPercent);
                if (semanticPercent > 0) {
                    items.push({
                        text: 'Theme match ' + semanticPercent.toFixed(1) + '%',
                        tone: 'is-theme'
                    });
                }
                var syntacticPercent = this.safeSimilarPercent(similar.syntacticSimilarityPercent);
                if (syntacticPercent > 0) {
                    items.push({
                        text: 'Wording overlap ' + syntacticPercent.toFixed(1) + '%',
                        tone: 'is-syntactic',
                        highlightKey: Array.isArray(similar.sharedSyntacticTokens) && similar.sharedSyntacticTokens.length ? 'syntactic' : ''
                    });
                }
                var sharedSignificantTerms = Array.isArray(similar.sharedSignificantTerms) ? similar.sharedSignificantTerms : [];
                var sharedSignificantTermCount = Number(similar.sharedSignificantTermCount);
                var significantCount = sharedSignificantTerms.length > 0 ? sharedSignificantTerms.length : sharedSignificantTermCount;
                if (!isNaN(significantCount) && significantCount > 0) {
                    items.push({
                        text: significantCount + ' shared major '
                            + (significantCount === 1 ? 'term' : 'terms'),
                        tone: 'is-significant',
                        highlightKey: sharedSignificantTerms.length ? 'significant' : ''
                    });
                }
                return items;
            },
            toggleSimilarHighlightState: function(narration, item) {
                if (!narration || !item || !item.highlightKey) {
                    return;
                }
                if (narration.similarHighlightKey === item.highlightKey) {
                    narration.similarHighlightKey = '';
                    narration.similarHighlightTone = '';
                    return;
                }
                narration.similarHighlightKey = item.highlightKey;
                narration.similarHighlightTone = item.tone || '';
            },
            clearSimilarHighlightState: function(narration) {
                if (!narration) {
                    return;
                }
                narration.similarHighlightKey = '';
                narration.similarHighlightTone = '';
            },
            isSimilarHighlightActive: function(narration, item) {
                if (!narration || !item || !item.highlightKey) {
                    return false;
                }
                return narration.similarHighlightKey === item.highlightKey;
            },
            similarHighlightSpec: function(narration) {
                if (!narration || !narration.similarHighlightKey) {
                    return null;
                }
                var similar = this.activeSimilar(narration);
                if (!similar) {
                    return null;
                }
                var terms = [];
                if (narration.similarHighlightKey === 'syntactic') {
                    terms = Array.isArray(similar.sharedSyntacticTokens) ? similar.sharedSyntacticTokens : [];
                } else if (narration.similarHighlightKey === 'significant') {
                    terms = Array.isArray(similar.sharedSignificantTerms) ? similar.sharedSignificantTerms : [];
                }
                var termSet = buildSimilarHighlightTermSet(terms);
                if (!termSet.size) {
                    return null;
                }
                var toneSuffix = 'syntactic';
                if (narration.similarHighlightTone === 'is-significant') {
                    toneSuffix = 'significant';
                }
                return {
                    key: narration.similarHighlightKey,
                    toneSuffix: toneSuffix,
                    termSet: termSet
                };
            },
            renderNarrationArabicHtml: function(narration) {
                var html = narration ? (narration.arabicContent || narration.arabic || '') : '';
                return applySimilarArabicHighlight(html, this.similarHighlightSpec(narration));
            },
            renderSimilarArabicHtml: function(narration) {
                var similar = this.activeSimilar(narration);
                var html = similar ? (similar.arabicContent || similar.arabic || '') : '';
                return applySimilarArabicHighlight(html, this.similarHighlightSpec(narration));
            },
            safeSimilarPercent: function(value) {
                var percent = Number(value);
                if (isNaN(percent) || percent <= 0) {
                    return 0;
                }
                return Math.max(0, Math.min(100, percent));
            },
            activeSimilar: function(narration) {
                if (!narration || !narration.similarItems || !narration.similarItems.length) {
                    return null;
                }
                var idx = narration.similarActiveIndex || 0;
                if (idx < 0 || idx >= narration.similarItems.length) {
                    idx = 0;
                }
                return narration.similarItems[idx];
            },
            similarLocationLabel: function(narration) {
                if (!narration) {
                    return '';
                }
                var parts = [];
                if (narration.volume) {
                    parts.push(narration.volume);
                }
                if (narration.part) {
                    parts.push(narration.part);
                }
                if (narration.section) {
                    parts.push(narration.section);
                }
                if (narration.chapter) {
                    parts.push(narration.chapter);
                }
                return parts.join(' · ');
            },
            saveNarrationToCollection: function(narration) {
                if (!narration) {
                    return;
                }
                var hadithId = narration._id || narration.id;
                if (!hadithId) {
                    return;
                }
                openSaveHadithModal(hadithId);
            },
            openNarrationEditor: function(narration) {
                if (!narration || !this.canEditNarrations()) {
                    return;
                }
                var hadithId = narration._id || narration.id;
                if (!hadithId) {
                    return;
                }
                // Navigate to the dedicated edit page
                window.location.href = '/edit?id=' + encodeURIComponent(hadithId);
            },
            removeNarrationFromCurrentCollection: function(narration) {
                if (!this.collectionMode || !this.collectionId || !narration) {
                    return;
                }
                var hadithId = narration._id || narration.id;
                if (!hadithId) {
                    return;
                }
                var self = this;
                apiJSON('/v1/collections/' + encodeURIComponent(this.collectionId) + '/hadith/' + encodeURIComponent(hadithId), {
                    method: 'DELETE'
                }).then(function(resp) {
                    if (!resp.ok || !resp.data || !resp.data.ok) {
                        swal('Remove failed', (resp.data && resp.data.message) || 'Unable to remove hadith from this collection.', 'error');
                        return;
                    }
                    var collection = resp.data.collection || self.collectionMeta || null;
                    var totalRemaining = Array.isArray(collection && collection.hadith_ids) ? collection.hadith_ids.length : 0;
                    self.collectionMeta = collection;
                    self.collectionTitle = (collection && collection.name) || self.collectionTitle;
                    loadAndRenderCollections(false);
                    showToast('Removed from collection.', 'success');
                    var maxPage = Math.max(1, Math.ceil(Math.max(0, totalRemaining) / self.pageSize));
                    var targetPage = Math.min(self.page, maxPage);
                    if (targetPage !== self.page) {
                        openCollectionPage(self.collectionId, targetPage, self.activeTopicTags.slice());
                        return;
                    }
                    self.fetchNarrations();
                });
            },
            startEditCollectionTitle: function() {
                this.editingCollectionTitle = this.collectionTitle || '';
                this.isEditingCollectionTitle = true;
                var self = this;
                this.$nextTick(function() {
                    var input = self.$refs.collectionTitleInput;
                    if (input) {
                        input.focus();
                        input.select();
                    }
                });
            },
            saveCollectionTitle: function() {
                var newName = (this.editingCollectionTitle || '').trim();
                if (!newName) {
                    showToast('Collection name cannot be empty.', 'error');
                    return;
                }
                if (newName === this.collectionTitle) {
                    this.isEditingCollectionTitle = false;
                    return;
                }
                var self = this;
                apiJSON('/v1/collections/' + encodeURIComponent(this.collectionId), {
                    method: 'PATCH',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ name: newName })
                }).then(function(resp) {
                    if (!resp.ok || !resp.data || !resp.data.ok) {
                        swal('Update failed', (resp.data && resp.data.message) || 'Unable to update collection name.', 'error');
                        return;
                    }
                    self.collectionTitle = newName;
                    self.collectionMeta = resp.data.collection || self.collectionMeta;
                    self.isEditingCollectionTitle = false;
                    loadAndRenderCollections(false);
                    showToast('Collection name updated.', 'success');
                });
            },
            cancelEditCollectionTitle: function() {
                this.isEditingCollectionTitle = false;
                this.editingCollectionTitle = '';
            },
            exportNarrationsPdf: function() {
                var sourceNarrations = this.activeTopicTags.length > 0 ? this.filteredNarrationsAll : this.allNarrations;
                var narrations = Array.isArray(sourceNarrations) ? sourceNarrations.slice() : [];
                if (!narrations.length) {
                    return;
                }
                var self = this;
                var subtitle = '';
                if (this.collectionMode) {
                    subtitle = 'Collection: ' + (this.collectionTitle || 'Saved Hadith');
                } else if (this.readingMode) {
                    subtitle = this.scopeBreadcrumbText
                        ? ('Reading scope: ' + this.scopeBreadcrumbText)
                        : ('Reading mode · page ' + this.page);
                } else if (this.queryStr) {
                    subtitle = 'Search query: ' + strip(this.queryStr);
                }
                var tagSummary = this.activeTopicTags.map(function(tag) {
                    return this.taxonomyLabel(tag);
                }, this).join(', ');
                var metaLine = this.resultsStatusText;
                if (tagSummary) {
                    metaLine += ' · Tags: ' + tagSummary;
                }
                openPdfExportWindow({
                    title: this.collectionMode
                        ? (this.collectionTitle || 'Saved Hadith')
                        : (this.readingMode ? ('Reading Mode - Page ' + this.page) : 'Search Results'),
                    subtitle: subtitle,
                    metaLine: metaLine,
                    narrations: narrations,
                    resultOrdinal: function(narration, index) {
                        return self.resultOrdinal(narration, index);
                    },
                    referenceLine: function(narration) {
                        return self.narrationReferenceLine(narration);
                    },
                    tagLabel: function(tag) {
                        return self.taxonomyLabel(tag);
                    }
                });
            },
            isActiveClass: function(text) {
                if (text && text.includes('<span')) {
                    return "is-active"
                } else {
                    return '';
                }
            },
            buildHadithShareUrl: function(id, pageNum) {
                var hadithId = (id || '').toString().trim();
                if (!hadithId) {
                    return location.protocol + '//' + location.host + '/';
                }
                var baseQuery = 'id:"' + escapeSearchTermQuotes(hadithId) + '"';
                var params = new URLSearchParams();
                params.set('q', baseQuery);
                params.set('match_mode', 'strict');
                return location.protocol + '//' + location.host + '/?' + params.toString();
            },
            resolveHadithShareUrl: function(id) {
                var hadithId = (id || '').toString().trim();
                if (!hadithId) {
                    return Promise.resolve(location.protocol + '//' + location.host + '/');
                }
                return Promise.resolve(this.buildHadithShareUrl(hadithId, 1));
            },
            reportNarrationHref: function(narration) {
                var hadithId = narration ? (narration._id || narration.id || '') : '';
                var shareUrl = this.buildHadithShareUrl(hadithId, 1);
                var descriptor = [];
                if (narration && narration.book) {
                    descriptor.push(strip(narration.book));
                }
                if (narration && narration.number) {
                    descriptor.push('#' + strip(narration.number));
                }
                var subject = 'Hadith Report: ' + (descriptor.length ? descriptor.join(' ') : ('Hadith ' + hadithId));
                var bodyLines = [
                    'Please review the hadith linked below.',
                    '',
                    'Hadith link: ' + shareUrl,
                    'Hadith id: ' + (hadithId || 'Unknown'),
                    '',
                    'Issue summary:',
                    '- ',
                    '',
                    'What seems incorrect:',
                    '- ',
                    '',
                    'Suggested correction (optional):',
                    '- ',
                    '',
                    'Additional context:',
                    '- '
                ];
                return 'mailto:rewayaat.org@gmail.com?subject='
                    + encodeURIComponent(subject)
                    + '&body=' + encodeURIComponent(bodyLines.join('\n'));
            },
            showHadithURL: function(id) {
                var hadithId = (id || '').toString().trim();
                if (!hadithId) {
                    return;
                }
                var self = this;
                this.resolveHadithShareUrl(hadithId).then(function(url) {
                var wrapper = document.createElement("div");
                var title = document.createElement("h3");
                title.textContent = "Hadith URL";
                var pre = document.createElement("pre");
                pre.textContent = url;
                var actions = document.createElement("div");
                actions.className = "share-actions";
                var copyBtn = document.createElement("button");
                copyBtn.className = "icon-action icon-action--copy";
                copyBtn.type = "button";
                copyBtn.setAttribute("aria-label", "Copy URL");
                copyBtn.title = "Copy URL";
                copyBtn.innerHTML = '<i class="fa fa-copy" aria-hidden="true"></i>';
                copyBtn.addEventListener("click", function() {
                    if (navigator && navigator.clipboard && navigator.clipboard.writeText) {
                        navigator.clipboard.writeText(url).then(function() {
                            copyBtn.innerHTML = '<i class="fa fa-check" aria-hidden="true"></i>';
                            showToast('Link copied.', 'information');
                        }).catch(function() {});
                    } else {
                        var textArea = document.createElement("textarea");
                        textArea.value = url;
                        textArea.style.position = "fixed";
                        textArea.style.opacity = "0";
                        document.body.appendChild(textArea);
                        textArea.select();
                        try {
                            document.execCommand("copy");
                            copyBtn.innerHTML = '<i class="fa fa-check" aria-hidden="true"></i>';
                            showToast('Link copied.', 'information');
                        } catch (err) {}
                        document.body.removeChild(textArea);
                    }
                });
                actions.appendChild(copyBtn);
                wrapper.appendChild(title);
                wrapper.appendChild(pre);
                wrapper.appendChild(actions);
                swal({
                    content: wrapper
                });
                });
            }
        }
    });
}

function decorateNarrationForSimilarity(narration) {
    if (!narration) {
        return narration;
    }
    narration.similarCount = null;
    narration.similarCountLoading = false;
    narration.similarItems = [];
    narration.similarItemsLoaded = false;
    narration.similarItemsLoading = false;
    narration.similarError = '';
    narration.similarOpen = false;
    narration.similarDropdownOpen = false;
    narration.similarActiveIndex = 0;
    narration.similarHighlightKey = '';
    narration.similarHighlightTone = '';
    narration.quranicInsightsCount = null;
    narration.quranicInsightsCountLoading = false;
    narration.quranicInsightsItems = [];
    narration.quranicInsightsItemsLoaded = false;
    narration.quranicInsightsItemsLoading = false;
    narration.quranicInsightsError = '';
    narration.quranicInsightsOpen = false;
    narration.quranicDropdownOpen = false;
    narration.quranicInsightsActiveIndex = 0;
    narration.selectedForCollection = false;
    return narration;
}

function finishSimilarLoadingState(narration, loadingStartedAt) {
    var elapsed = Math.max(0, Date.now() - (loadingStartedAt || 0));
    var remaining = Math.max(0, similarLoadingMinDurationMs - elapsed);
    window.setTimeout(function() {
        narration.similarItemsLoading = false;
        narration.similarCountLoading = false;
    }, remaining);
}

function finishSimilarCountLoadingState(narration, loadingStartedAt) {
    var elapsed = Math.max(0, Date.now() - (loadingStartedAt || 0));
    var remaining = Math.max(0, similarLoadingMinDurationMs - elapsed);
    window.setTimeout(function() {
        if (!narration.similarItemsLoading) {
            narration.similarCountLoading = false;
        }
    }, remaining);
}

function finishQuranicInsightsLoadingState(narration, loadingStartedAt) {
    var elapsed = Math.max(0, Date.now() - (loadingStartedAt || 0));
    var remaining = Math.max(0, similarLoadingMinDurationMs - elapsed);
    window.setTimeout(function() {
        narration.quranicInsightsItemsLoading = false;
        narration.quranicInsightsCountLoading = false;
    }, remaining);
}

function finishQuranicInsightsCountLoadingState(narration, loadingStartedAt) {
    var elapsed = Math.max(0, Date.now() - (loadingStartedAt || 0));
    var remaining = Math.max(0, similarLoadingMinDurationMs - elapsed);
    window.setTimeout(function() {
        if (!narration.quranicInsightsItemsLoading) {
            narration.quranicInsightsCountLoading = false;
        }
    }, remaining);
}

/**
 * Adds relevant social media URLS as properties of the given hadith object.
 */
function socialMediaDecoratedHadith(hadithObj) {
    var hadithId = escapeSearchTermQuotes(hadithObj && (hadithObj._id || hadithObj.id || ''));
    var hadithURL = encodeURIComponent(location.protocol + "//" + location.host +
        "/?q=" + encodeURIComponent('id:"' + hadithId + '"') + "&match_mode=strict");
    var hadithDesc = "";
    if (hadithObj.book) {
        hadithDesc += hadithObj.book + " ";
    }
    if (hadithObj.edition) {
        hadithDesc += " (" + hadithObj.edition + "), ";
    } else {
        hadithDesc += ", ";
    }
    if (hadithObj.number) {
        hadithDesc += "#" + hadithObj.number + ", ";
    }
    if (hadithObj.chapter) {
        hadithDesc += hadithObj.chapter + ", ";
    }
    if (hadithObj.volume) {
        hadithDesc += "VOL. " + hadithObj.volume;
    }
    // keep the overall hadithDesc + hadithURL < 260 (to stay within twitter max
    // length)
    if ((hadithDesc.length + hadithURL.length) > 260) {
        hadithTextDesiredLen = 260 - hadithURL.length;
        hadithDesc = hadithDesc.substring(0, hadithTextDesiredLen);
    }
    hadithDesc = hadithDesc.replaceAll('<span class="highlight">', '');
    hadithDesc = hadithDesc.replaceAll('</span>', '');
    hadithDesc = encodeURIComponent(hadithDesc.replace(/(^,)|(,$)/g, "").trim());
    var hadithText = "Hadith " + hadithObj.number + " chapter " + hadithObj.chapter + " from " + hadithObj.book;
    if (hadithObj.english) {
        hadithText = encodeURIComponent(hadithObj.english.replaceAll(
            '<span class="highlight">', '').replaceAll('</span>', ''));
    }
    hadithObj["facebook"] = "https://www.facebook.com/sharer/sharer.php?u=" +
        hadithURL;
    hadithObj["twitter"] = "https://twitter.com/intent/tweet/?text=" +
        hadithDesc + "&url=" + hadithURL;
    return hadithObj;
}

// ── New Search Mode Dropdown ───────────────────────────────────────────────
function setupSearchModeDropdown() {
    var dropdown = document.getElementById('searchModeDropdown');
    var toggle = document.getElementById('searchModeToggle');
    var menu = document.getElementById('searchModeDropdownMenu');
    var searchBtn = document.getElementById('searchBtn1');
    var isInitializing = true;

    if (!dropdown || !toggle || !menu) {
        return;
    }

    // Initialize with current mode
    updateDropdownDisplay();

    // Toggle dropdown
    toggle.addEventListener('click', function(e) {
        e.stopPropagation();
        var isOpen = menu.classList.contains('show');
        if (isOpen) {
            closeDropdown();
        } else {
            openDropdown();
        }
    });

    // Close dropdown when clicking outside
    document.addEventListener('click', function() {
        closeDropdown();
    });

    // Prevent dropdown from closing when clicking inside
    menu.addEventListener('click', function(e) {
        e.stopPropagation();
    });

    // Handle mode selection
    var options = menu.querySelectorAll('.search-mode-option');
    options.forEach(function(option) {
        option.addEventListener('click', function() {
            var mode = this.dataset.mode;
            if (mode) {
                selectSearchMode(mode);
            }
        });
    });

    // Handle search button click
    if (searchBtn) {
        searchBtn.addEventListener('click', function() {
            submitSearchQuery();
        });
    }

    function openDropdown() {
        menu.classList.add('show');
        toggle.setAttribute('aria-expanded', 'true');

        // Set active option
        var currentMode = searchMatchMode || 'strict';
        options.forEach(function(option) {
            option.classList.toggle('active', option.dataset.mode === currentMode);
            var checkIcon = option.querySelector('i.fa-check');
            if (checkIcon) {
                checkIcon.style.color = option.dataset.mode === currentMode ? 'var(--primary)' : 'transparent';
            }
        });
    }

    function closeDropdown() {
        menu.classList.remove('show');
        toggle.setAttribute('aria-expanded', 'false');
    }

    function selectSearchMode(mode) {
        var normalizedMode = normalizeSearchMatchMode(mode);
        if (searchMatchMode === normalizedMode) {
            return;
        }

        searchMatchMode = normalizedMode;
        updateDropdownDisplay();
        closeDropdown();

        // Removed glow effect - search button does not glow

        // Show toast notification
        showSearchToast();
    }

    function updateDropdownDisplay() {
        var modeText = searchMatchMode === 'permissive' ? 'Flexible' : 'Exact';
        toggle.querySelector('.search-mode-text').textContent = modeText;
        toggle.classList.toggle('active', searchMatchMode === 'permissive');
    }

    // Mark initialization as complete
    isInitializing = false;
}

function showSearchToast() {
    // Remove existing toast if any
    var existingToast = document.querySelector('.search-toast');
    if (existingToast) {
        existingToast.remove();
    }

    // Create new toast
    var toast = document.createElement('div');
    toast.className = 'search-toast';

    // Get current search terms
    var selectedTerms = getSelectedSearchTerms();
    var pendingTerms = getPendingSearchTerms();
    var allTerms = mergeSearchTerms(selectedTerms, pendingTerms.length ? pendingTerms : getSelectedSearchTerms());

    // Suggested terms based on common search patterns
    var suggestedTerms = ['حديث', 'سنة', 'علم', 'فضل', 'أحكام', 'تفسير', 'الإمام علي', 'الرسول'];

    // Build toast content
    toast.innerHTML = `
        <div class="search-toast__title">Not finding what you're looking for?</div>
        <div class="search-toast__subtitle">Consider adding one or more of these search terms to broaden your results.</div>
        <div class="search-toast__suggestions">
            <div class="search-toast__suggestions-title">Suggested terms:</div>
            <div class="search-toast__suggestions-list">
                ${suggestedTerms.slice(0, 6).map(term =>
                    `<span class="search-toast__suggestion">${term}</span>`
                ).join('')}
            </div>
        </div>
    `;

    document.body.appendChild(toast);

    // Show toast
    setTimeout(function() {
        toast.classList.add('show');
    }, 10);

    // Hide toast after 5 seconds
    setTimeout(function() {
        toast.classList.remove('show');
        setTimeout(function() {
            toast.remove();
        }, 300);
    }, 5000);
}

// Initialize search mode dropdown when DOM is ready
$(document).ready(function() {
    setupSearchModeDropdown();
});
