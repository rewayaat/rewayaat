var vueApp;
var google_id_token;
var currentQueryText = '';
var bookBlurbs;
/**
 * Main entry point to the website. If does not exist, display default welcome
 * content. If there is a valid query, setup a Vue.js instance to display it.s
 */
function loadQuery(query, page = 1, sortFields) {
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
            });
            // Update latest new bar
            setLatestNewsBarHTML('<b>NEW</b>: Hadith metadata such as the chapter, section, part ' +
            'and volume can now be selected <i class="fa fa-mouse-pointer"></i> for further ' +
            'reading!')
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
});

function setupSelect2EnterKeyListener(select2_id) {
    $(document).on('keyup', '.select2-search__field', function(e) {
        if (e.which === 13) {
            e.preventDefault();
            var curr_text = $('.select2-search__field')[0].value;
            var selectSearchTerms = document.getElementById(select2_id);
            if (curr_text) {
                var option = document.createElement("option");
                option.text = curr_text;
                option.selected = true;
                selectSearchTerms.add(option);
                $('.select2-search__field')[0].value = "";
                currentQueryText = "";
                $('#' + select2_id).select2('close');
            } else if (selectSearchTerms.length > 0) {
                // User pressed enter while not in the middle of a term, submit the query developed thus far.
                submitSearchQuery();
            }
        }
    });
}

function removeArabicText(text) {
    return text.replace(/[^\x00-\x7F]/g, "").trim();
}
function initSelect2(select2_id) {
    $('#' + select2_id).select2({
        tags: "true",
        dropdownAutoWidth: true,
        width: '60%',
        minimumInputLength: 2,
        placeholder: '"Household of the prophet"  Ahlulbayt  "اهل البيت"',
        matcher: function(params, data) {
            return matchStart(params, data);
        },
        ajax: {
            url: '/v1/terms/top',
            dataType: "json",
            type: "GET",
            delay: 250,
            data: function(params) {
                if (params['term'].indexOf(' ') >= 0) {
                    return;
                }
                var queryParameters = {
                    term: params.term.replace(/["']/g, "")
                }
                return queryParameters;
            },
            processResults: function(data) {
                return {
                    results: $.map(data, function(item) {
                        return {
                            text: item,
                            id: item
                        }
                    })
                };
            }
        }
    });

    $('#' + select2_id).on('select2:closing', function() {
        currentQueryText = $('.select2-search input').prop('value').trim();
        if (currentQueryText) {
            var selectSearchTerms = document.getElementById(select2_id);
            var option = document.createElement("option");
            option.text = currentQueryText;
            option.selected = true;
            selectSearchTerms.add(option);
            currentQueryText = '';
            $('.select2-search input').val('');
        }
    });

    $('#' + select2_id).on('select2:selecting', function() {
        currentQueryText = '';
        $('.select2-search input').val('');
        indicatePendingSearchTerms();
    });
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

function arraysEqual(a, b) {
    if (a === b) return true;
    if (a == null || b == null) return false;
    if (a.length != b.length) return false;

    // We don't  care about order...
    a.sort();
    b.sort();

    for (var i = 0; i < a.length; ++i) {
        if (a[i] !== b[i]) return false;
    }
    return true;
}

String.prototype.replaceAll = function(search, replacement) {
    var target = this;
    return target.replace(new RegExp(search, 'g'), replacement);
};

function getQueryStringValue(key) {
    return decodeURIComponent(window.location.search.replace(new RegExp(
        "^(?:.*[&\\?]" +
        encodeURIComponent(key).replace(/[\.\+\*]/g, "\\$&") +
        "(?:\\=([^&]*))?)?.*$", "i"), "$1"));
}

function splitQuery(query) {
    var myRegexp = /((".*?"|[^"\s]+)+(?=\s*|\s*$))/gi;
    var termsArr = [];

    do {
        //Each call to exec returns the next regex match as an array
        var match = myRegexp.exec(query);
        if (match != null) {
            //Index 1 in the array is the captured group if it exists
            //Index 0 is the matched text, which we use if no captured group exists
            termsArr.push(match[1] ? match[1] : match[0]);
        }
    } while (match != null);
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

function isIterable (value) {
  return Symbol.iterator in Object(value);
}

function isNumeric(obj) {
    return !isNaN(obj - parseFloat(obj));
}

function arraysEqual(a, b) {
    if (a === b) return true;
    if (a == null || b == null) return false;
    if (a.length != b.length) return false;

    // We don't  care about order...
    a.sort();
    b.sort();

    for (var i = 0; i < a.length; ++i) {
        if (a[i] !== b[i]) return false;
    }
    return true;
}

String.prototype.replaceAll = function(search, replacement) {
    var target = this;
    return target.replace(new RegExp(search, 'g'), replacement);
};

function getQueryStringValue(key) {
    return decodeURIComponent(window.location.search.replace(new RegExp(
        "^(?:.*[&\\?]" +
        encodeURIComponent(key).replace(/[\.\+\*]/g, "\\$&") +
        "(?:\\=([^&]*))?)?.*$", "i"), "$1"));
}


function onSignIn(googleUser) {
    var profile = googleUser.getBasicProfile();
    // console.log('Image URL: ' + profile.getImageUrl());
    // Identify user with log rocket.
    google_id_token = googleUser.getAuthResponse().id_token;
    LogRocket.identify(profile.getId(), {
        name: profile.getName(),
        email: profile.getEmail()
    });
    // display signout button
    displaySignOutBtn();
    // establish session with rewayaat webapp
    vueApp.signedIn = true;
    vueApp.$forceUpdate();
}

function displaySignOutBtn() {
    var gSignInBtn = document.getElementsByClassName('g-signin2')[0];
    gSignInBtn.outerHTML = '';
    signInBtnLi = document.getElementById('signInBtnLi');
    signInBtnLi.innerHTML = '<div onclick="signOutOfRewayaat()" class="uk-navbar-item" style="display:inline-block;  margin-right: 20px;">' +
        '<a style="margin-top: 20px; border-radius: 3px;" class="uk-button uk-button-default tm-button-default uk-icon"><img style="display: inline-block;   margin-bottom: 4px;width:18px;" src="img/google.png"/><span style="margin-left: 10px;">Sign Out</span>' +
        '</a>' +
        '</div>';
}

// processes the user's query by redirecting with query parameter.
function submitSearchQuery() {
    //searchTerms corresponds to to the main search bar
    var queryBar = document.getElementById("searchTerms");
    if (queryBar.options.length < 1) {
        // searchTerms2 corresponds to the home page search bar
        queryBar = document.getElementById("searchTerms2");
    }
    query = ''
    for (var i = 0, len = queryBar.options.length; i < len; i++) {
        opt = queryBar.options[i]
        if (opt.selected === true) {
            query += opt.value + " ";
        }
    }
    if (query) {
        redirectToSearchResult(query)
    }
}

function redirectToSearchResult(query, page, sortFields) {
    var queryParamString = '?q=' + encodeURIComponent(query.trim())
    if (sortFields) {
        queryParamString += '&sort_fields=' + encodeURIComponent(sortFields.trim())
    }
    if (page) {
        queryParamString += '&page=' + page;
    }
    window.location.href = window.location.protocol + "//" +
        window.location.host + window.location.pathname + queryParamString;
}

function showBookBlurb(bookName) {
    for (blurb in bookBlurbs) {
        if (strip(bookName).toUpperCase().includes(bookBlurbs[blurb].book.toUpperCase())) {
            UIkit.modal.alert(bookBlurbs[blurb].blurb);
            var modelDialog = document.getElementsByClassName("uk-modal-dialog")[0];
            modelDialog.style.width = '95%';
            modelDialog.style.maxWidth = '1000px';

        }
    }
}

function displayWelcomeContent() {
    document.getElementById('hadithView').innerHTML = '';
    vueApp = new Vue({
        el: '#hadithView'
    });
    $("#welcome").load("/welcome.html?v=4", function(responseData) {
        // initialize select2
        initSelect2('searchTerms2');
        // setup select2 select handler
        select2SelectHandler('searchTerms2');
        // setup enter key listener
        setupSelect2EnterKeyListener('searchTerms2');
        // Scroll to tips section if required
        if (window.location.hash && window.location.hash.substring(1) === 'tips') {
              document.getElementById('tips').scrollIntoView();
        }
    });
}

function indicatePendingSearchTerms() {
    // make search button glow
    $("[id^=searchBtn]").addClass("button-glow");
    $("[id^=searchBtn]").css('background', '#383737');
    $("[id^=searchBtn]").css('color', '#fafafa');
}

function signOutOfRewayaat() {
    var auth2 = gapi.auth2.getAuthInstance();
    auth2.disconnect();
    location.reload();
}

function onSignIn(googleUser) {
    var profile = googleUser.getBasicProfile();
    // console.log('Image URL: ' + profile.getImageUrl());
    // Identify user with log rocket.
    google_id_token = googleUser.getAuthResponse().id_token;
    LogRocket.identify(profile.getId(), {
        name: profile.getName(),
        email: profile.getEmail()
    });
    // display signout button
    displaySignOutBtn();
    // establish session with rewayaat webapp
    vueApp.signedIn = true;
    vueApp.$forceUpdate();
}

function displaySignOutBtn() {
    var gSignInBtn = document.getElementsByClassName('g-signin2')[0];
    gSignInBtn.outerHTML = '';
    signInBtnLi = document.getElementById('signInBtnLi');
    signInBtnLi.innerHTML = '<div onclick="signOutOfRewayaat()" class="uk-navbar-item" style="display:inline-block;  margin-right: 20px;">' +
        '<a style="margin-top: 20px; border-radius: 3px;" class="uk-button uk-button-default tm-button-default uk-icon"><img style="display: inline-block;   margin-bottom: 4px;width:18px;" src="img/google.png"/><span style="margin-left: 10px;">Sign Out</span>' +
        '</a>' +
        '</div>';
}

// processes the user's query by redirecting with query parameter.
function submitSearchQuery() {
    //searchTerms corresponds to to the main search bar
    var queryBar = document.getElementById("searchTerms");
    if (queryBar.options.length < 1) {
        // searchTerms2 corresponds to the home page search bar
        queryBar = document.getElementById("searchTerms2");
    }
    query = ''
    for (var i = 0, len = queryBar.options.length; i < len; i++) {
        opt = queryBar.options[i]
        if (opt.selected === true) {
            query += opt.value + " ";
        }
    }

    if (query) {
        window.location.href = window.location.protocol + "//" +
            window.location.host + window.location.pathname + '?' + 'q=' +
            encodeURIComponent(query.trim());
    }
}

function showBookBlurb(bookName) {
    for (blurb in bookBlurbs) {
        if (strip(bookName).toUpperCase().includes(bookBlurbs[blurb].book.toUpperCase())) {
            UIkit.modal.alert(bookBlurbs[blurb].blurb);
            var modelDialog = document.getElementsByClassName("uk-modal-dialog")[0];
            modelDialog.style.width = '95%';
            modelDialog.style.maxWidth = '1000px';

        }
    }
}

function indicatePendingSearchTerms() {
    // make search button glow
    $("[id^=searchBtn]").addClass("button-glow");
    $("[id^=searchBtn]").css('background', '#383737');
    $("[id^=searchBtn]").css('color', '#fafafa');
}

function signOutOfRewayaat() {
    var auth2 = gapi.auth2.getAuthInstance();
    auth2.disconnect();
    location.reload();
}

function select2SelectHandler(select2_id) {
    $('#' + select2_id).on('select2:close', function(e) {
        var select2SearchField = $(this).parent().find('.select2-search__field'),
            setfocus = setTimeout(function() {
                select2SearchField.focus();
            }, 100);
    });
}

async function displayQuery(query) {
    // 1 --> split query on spaces and quotes
    searchTermsArray = splitQuery(query)
    // 2 --> populate search bar with options
    var selectSearchTerms = document.getElementById("searchTerms");
    for (term in searchTermsArray) {
        var option = document.createElement("option");
        option.text = searchTermsArray[term];
        option.selected = true;
        selectSearchTerms.add(option);
    }
    // initialize select2
    initSelect2('searchTerms');
    // setup select2 select handler
    select2SelectHandler('searchTerms');
    // display search bar
    var queryBar = document.getElementById("queryBar");
    $(queryBar).css('display', 'block');
    // setup enter key listener
    setupSelect2EnterKeyListener('searchTerms');
}

function changeCardWidth() {
    if ($(window).width() < 768) {
        $('.uk-card').css({
            "padding-left": "0px"
        });
        $('.uk-container-large').css({
            "width": "97%"
        });
        $('.uk-align-left').css({
            "margin-right": "0px",
            "padding-left": "5px"
        });
    };
    if ($(window).width() >= 768) {
        $('.uk-container-large').css({
            "width": "80%"
        });
        $('.uk-align-left').css({
            "margin-right": "30px"
        });
    };
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
            queryStr: query,
            significantTerms: [],
            sortFields: sortFields,
            page: page,
            totalHits: 0,
            pageSize: 20,
            signedIn: false,
            book_blurbs: bookBlurbs
        },
        // runs when the Vue instance has initialized.
        mounted: function() {
            this.fetchNarrations();
        },
        methods: {
            // fetches significant query terms using the Rewayaat REST API
            fetchSignificantTerms: function() {
                var self = this;
                if (this.queryStr && !this.sortFields) {
                    var xhr = new XMLHttpRequest();
                    xhr.onload = function() {
                        if (xhr.readyState == XMLHttpRequest.DONE && xhr.status == 200) {
                            var respJSON = '{}'
                            if (xhr.responseText) {
                                self.significantTerms = JSON.parse(xhr.responseText)
                            }
                        }
                    }
                    xhr.open('GET', '/v1/terms/significant?inputTerms=' + splitQuery(this.queryStr).join(","));
                    xhr.send();
                }
            },
            // fetches more narrations to display using the API.
            fetchNarrations: function() {
                var self = this;
                var xhr = new XMLHttpRequest();
                xhr.onload = function() {
                    if (xhr.readyState == XMLHttpRequest.DONE) {
                        var respJSON = '{}'
                        if (xhr.responseText) {
                            var respJSON = JSON.parse(xhr.responseText)
                        }
                        if (respJSON.error) {
                            swal("Oops...",
                                "Something went wrong while fetching your hadith, please try a different search.");
                        } else if (respJSON.collection.length < 1 &&
                            self.narrations.length === 0) {
                            swal("Oops...",
                                "No results seem to match your query!",
                                "error");
                        } else {
                            $.each(respJSON.collection, function(index, value) {
                                if (value.notes) {
                                    value.notes = marked(value.notes);
                                }
                                if (value.volume) {
                                    value.volume = "Volume " + value.volume;
                                }
                                value = socialMediaDecoratedHadith(value);
                                self.narrations.push(value);
                                console.log(value);
                            });
                            // set total results size value
                            self.totalHits = respJSON.totalResultSetSize;
                        }
                        // fetch significant terms
                        self.fetchSignificantTerms();
                    }
                }
                var reqUrl = '/v1/narrations?q=' + encodeURIComponent(this.queryStr) +
                '&page=' + this.page + '&per_page=' + this.pageSize;
                if (this.sortFields) {
                    reqUrl += '&sort_fields=' + this.sortFields
                }
                xhr.open('GET', reqUrl);
                xhr.send();
            },
            significant_btn_class: function(btn_text) {
                class_str = "uk-button tm-button-default uk-icon";
                if (isArabic(btn_text)) {
                    class_str += " uk-button-significant-arabic";
                } else {
                    class_str += " uk-button-significant-english";
                }
                return class_str
            },
            addTermToSearchBar: function(term, termDivId) {
                var selectSearchTerms = document.getElementById("searchTerms");
                var option = document.createElement("option");
                option.text = term;
                option.selected = true;
                selectSearchTerms.add(option);
                $('#' + termDivId).remove();
                indicatePendingSearchTerms();
                new Noty({
                    type: 'success',
                    text: '<b>Click here</b> to update your search results!',
                    theme: 'semanticui',
                    layout: 'topRight',
                    killer: true,
                    callbacks: {
                        onClick: function() {
                            submitSearchQuery();
                        },
                    }
                }).show();
            },
            isActiveClass: function(text) {
                if (text && text.includes('<span')) {
                    return "uk-active"
                } else {
                    return '';
                }
            },
            showHadithURL: function(id) {
                UIkit.modal.alert('<h2>Hadith URL</h2><pre>' + location.protocol + '//' + location.host + '/?q=_id:' + id + '</pre>');
            },
            editHadith: function(index) {
                var narration = this.narrations[index];
                // hadith that the user will see...
                var originalHadith = new Object();
                // remove any HTML from the JSON object...
                narration = JSON.parse(JSON.stringify(narration).replace(/<(?:.|\n)*?>/gm, ''));
                var editableAttrs = ["book", "number", "source", "part", "edition", "chapter",
                    "publisher", "section", "volume", "tags", "notes", "english", "arabic"
                ];
                // loop through narration properties
                for (var attribute in narration) {
                    if (Object.prototype.hasOwnProperty.call(narration, attribute) &&
                        editableAttrs.includes(attribute) && narration[attribute]) {
                        originalHadith[attribute] = narration[attribute];
                    }
                }
                // stores all the hadith properties that were modified.
                var changedAttributes = new Object();
                UIkit.modal.confirm('<h2>Editing Mode</h2><p>Modify this hadith in the editor below, make sure to read the <a target="_blank" href="https://github.com/rewayaat/rewayaat/wiki/Hadith-Entry-Guidelines">Hadith Entry Guidelines</a>. When finished, select <span style="display: inline-block;box-sizing: border-box;' +
                    'padding: 0 15px;vertical-align: middle;font-size: 14px;line-height: 28px;text-align: center;color: #fff;background-color:#1e87f0;' +
                    '">OK</span> to save your changes to the database.</p>').then(function() {
                    var modifiedHadith = editor.get();
                    var changeDetected = false;
                    // loop through original hadith
                    for (var attribute in originalHadith) {
                        if (Object.prototype.hasOwnProperty.call(originalHadith, attribute) &&
                            editableAttrs.includes(attribute) && modifiedHadith[attribute]) {
                            if ((isIterable(originalHadith[attribute]) &&
                            arraysEqual(modifiedHadith[attribute], originalHadith[attribute]) ===
                            false) ||
                            (isIterable(originalHadith[attribute]) === false &&
                             modifiedHadith[attribute] !== originalHadith[attribute])) {
                                changeDetected = true;
                                changedAttributes[attribute] = modifiedHadith[attribute];
                                originalHadith[attribute] = narration[attribute];
                            }
                        }
                    }
                    if (changeDetected) {
                        // save hadith
                        var http = new XMLHttpRequest();
                        var url = "/v1/narrations?hadith_id=" + narration._id + '&id_token=' + google_id_token;
                        var data = JSON.stringify(changedAttributes);
                        http.open("POST", url, true);
                        //Send the proper header information along with the request
                        http.setRequestHeader("Content-type", "application/json");
                        http.onreadystatechange = function() { //Call a function when the state changes.
                            if (http.readyState == 4 && http.status == 200) {
                                swal(
                                    "Success!",
                                    "This hadith was successfully modifed.",
                                    "success");
                                // modify existing hadith object with new values
                                Object.keys(changedAttributes).forEach(function(key) {
                                    vueApp.narrations[index][key] = changedAttributes[key];
                                });
                            } else {
                                swal(
                                    "Oops!",
                                    "An error was encountered while saving this hadith, please contact us " +
                                    "if this issue persists.",
                                    "error");
                            }
                        }
                        http.send(data);
                    } else {
                        // no changes were found
                        swal(
                            "No Changes Were Found",
                            "If you meant to make changes to the hadith, please try again.",
                            "error");
                    }
                });
                var modelDialog = document.getElementsByClassName("uk-modal-dialog")[0];
                modelDialog.style.width = '80%';
                modelDialog.style.height = '80%';
                var container = document.getElementsByClassName("uk-modal-body")[0];
                container.style.height = '85%';
                var options = {
                    "mode": "code",
                    "sortObjectKeys": true,
                    "search": true,
                    "indentation": 2
                };
                var editor = new JSONEditor(container, options, originalHadith);
            }
        }
    });
}

/**
 * Adds relevant social media URLS as properties of the given hadith object.
 */
function socialMediaDecoratedHadith(hadithObj) {
    var hadithURL = encodeURIComponent(location.protocol + "//" + location.host +
        "/?q=_id:" + hadithObj._id);
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
