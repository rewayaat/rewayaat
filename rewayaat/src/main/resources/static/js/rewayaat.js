var vueApp;
var google_id_token;
var currentQueryText = '';

/**
 * Main entry point to the website. If does not exist, display default welcome
 * content. If there is a valid query, setup a Vue.js instance to display it.s
 */
function loadQuery(query, page = 1) {
    if (query) {
        // validate query
        if (validQuery(query)) {
            // display query in search bar
            displayQuery(query);
            // load the query
            setupVue(query, page);
        } else {
            swal(
                "Invalid Query",
                "Please ensure the entered query is greater than three characters long!",
                "error");
            displayWelcomeContent();
        }
    } else {
        // show default mark-down welcome page
        displayWelcomeContent();
    }
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
    $(document).on('keyup', '.select2-search__field', function (e) {
        if (e.which === 13) {
            e.preventDefault();
            var curr_text = $('.select2-search__field')[0].value;
            if (curr_text) {
                var selectSearchTerms = document.getElementById(select2_id);
                var option = document.createElement("option");
                option.text = curr_text;
                option.selected = true;
                selectSearchTerms.add(option);
                $('.select2-search__field')[0].value = "";
                currentQueryText = "";
                $('#' + select2_id).select2('close');
            }
        }
    });
}


function initSelect2(select2_id) {
    $('#' + select2_id).select2({
        tags: "true",
        dropdownAutoWidth: true,
        width: '100%',
        minimumInputLength: 2,
        placeholder: 'Enter search terms here...',
        matcher: function(params, data) {
            return matchStart(params, data);
        },
        ajax: {
            url: '/v1/terms/top',
            dataType: "json",
            type: "GET",
            delay: 250,
            data: function(params) {
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


function select2SelectHandler(select2_id) {
    $('#' + select2_id).on('select2:close', function(e) {
        var select2SearchField = $(this).parent().find('.select2-search__field'),
            setfocus = setTimeout(function() {
                select2SearchField.focus();
            }, 100);
    });
}

function splitQuery(query) {
    var myRegexp = /[^\s"]+|"([^"]*)"/gi;
    var termsArr = [];

    do {
        //Each call to exec returns the next regex match as an array
        var match = myRegexp.exec(query);
        if (match != null) {
            //Index 1 in the array is the captured group if it exists
            //Index 0 is the matched text, which we use if no captured group exists
            termsArr.push(match[1] ? '"' + match[1] + '"' : match[0]);
        }
    } while (match != null);
    return termsArr;
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

function displayWelcomeContent() {
    document.getElementById('hadithView').innerHTML = '';
    vueApp = new Vue({
        el: '#hadithView'
    });
    $("#welcome").load("/welcome.html", function(responseData) {
        // initialize select2
        initSelect2('searchTerms2');
        // setup select2 select handler
        select2SelectHandler('searchTerms2');

        // setup enter key listener
        setupSelect2EnterKeyListener('searchTerms2');
    });
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

function isNumeric(obj) {
    return !isNaN(obj - parseFloat(obj));
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

function indicatePendingSearchTerms() {
    //$("html, body").animate({ scrollTop: 0 }, "slow");
    $("[id^=searchBtn]").addClass("button-glow");
    $("[id^=searchBtn]").css('background', '#383737');
    $("[id^=searchBtn]").css('color', '#fafafa');
}
/**
 * Main method responsible for displaying queries using Vue.js. Stores the
 * created Vue instance in the global vueApp variable.
 */
function setupVue(query, page) {

    // create hadith details component
    Vue
        .component(
            'hadith-details', {
                template: '<div><div v-on:click="showBookBlurb(narration.book)" title="Book" uk-tooltip="pos: right"  class="uk-align-left" >' +
                    '	<i style="color: rgb(83, 102, 125);" class="fa fa-book hadithDetailsIcon"' +
                    '		aria-hidden="true"></i>' +
                    '	<p style="text-decoration:underline; cursor: pointer;" class="hadithDetailsTitle" v-html="narration.book" />' +
                    '</div>' +
                    '<div title="Edition" uk-tooltip="pos: right"  class="uk-align-left"  v-if="narration.edition">' +
                    '<i style="color: rgb(83, 102, 125)"' +
                    '	class="fa fa-pencil-square-o hadithDetailsIcon"' +
                    '	aria-hidden="true"></i>' +
                    '<p class="hadithDetailsTitle">({{narration.edition}})</p>' +
                    '</div>' +
                    '<div title="Number" uk-tooltip="pos: right"  class="uk-align-left"  v-if="narration.number">' +
                    '<i style="color: rgb(83, 102, 125)"' +
                    '	class="fa fa-pencil-square-o hadithDetailsIcon"' +
                    '	aria-hidden="true"></i>' +
                    '<p class="hadithDetailsTitle">Hadith #{{narration.number}}</p>' +
                    '</div>' +
                    '<div title="Chapter" uk-tooltip="pos: right"  class="uk-align-left"  v-if="narration.chapter">' +
                    '<i style="color: rgb(83, 102, 125);"' +
                    '	class="fa fa-superpowers hadithDetailsIcon" aria-hidden="true"></i>' +
                    '<p class="hadithDetailsTitle" v-html="narration.chapter" />' +
                    '</div>' +
                    '<div title="Section" uk-tooltip="pos: right"  class="uk-align-left" v-if="narration.section">' +
                    '<i style="color:  rgb(83, 102, 125);"' +
                    '	class="fa fa-bookmark-o hadithDetailsIcon" aria-hidden="true"></i>' +
                    '<p class="hadithDetailsTitle" v-html="narration.section" />' +
                    '</div>' +
                    '<div title="Part" uk-tooltip="pos: right"   class="uk-align-left"  v-if="narration.part">' +
                    '<i style="color: rgb(83, 102, 125);"' +
                    '  class="fa fa-clone hadithDetailsIcon" aria-hidden="true"></i>' +
                    '	<p class="hadithDetailsTitle" v-html="narration.part" />' +
                    '</div>' +
                    '<div title="Volume" uk-tooltip="pos: right"   class="uk-align-left"  v-if="narration.volume">' +
                    '<i style="color:rgb(83, 102, 125)"' +
                    '	class="fa fa-calendar-o hadithDetailsIcon" aria-hidden="true"></i>' +
                    '<p class="hadithDetailsTitle" v-html="narration.volume" />' +
                    '</div>' +
                    '<div title="Source" uk-tooltip="pos: right"  class="uk-align-left"  v-if="narration.source">' +
                    '<i style="color:rgb(83, 102, 125)"' +
                    '	class="fa fa-share-square-o hadithDetailsIcon" aria-hidden="true"></i>' +
                    '<p class="hadithDetailsTitle" v-html="narration.source" />' +
                    '</div>' +
                    '<div title="Publisher" uk-tooltip="pos: right" class="uk-align-left"  v-if="narration.publisher">' +
                    '<i style="color:rgb(83, 102, 125)"' +
                    '	class="fa fa-medium hadithDetailsIcon" aria-hidden="true"></i>' +
                    '<p class="hadithDetailsTitle" v-html="narration.publisher" />' +
                    '</div>'
                    //+ '<span v-on:click="showHadithInfo(gradingobj)" style="padding:10px;    max-width: 150px; text-align:center; width: 80%; margin-right:25px; cursor:pointer; margin-top:15px;" title="Click for more info" uk-tooltip="pos: right" class="uk-align-left" '
                    //+ 'v-for="gradingobj in narration.gradings"'
                    //+ 'v-bind:class="gradeLabelClass(gradingobj.grading)"> <i'
                    //+ ' v-bind:class="gradeLabelIcon(gradingobj.grading)" '
                    //+ 'aria-hidden="true"></i> {{gradingobj.grading}}'
                    //+ '</span>
                    +
                    '</div>',
                props: ['narration'],
                methods: {
                    showHadithInfo: function(gradingobj) {
                        var rationaleStr = '';
                        if (gradingobj.rationale) {
                            rationaleStr = gradingobj.rationale;
                        }
                        UIkit.modal.alert('<h2>Hadith Grading</h2><p>This hadith was given a grading of <code>' + gradingobj.grading + '</code> by ' +
                            gradingobj.grader + '. ' + rationaleStr + '</p>');
                    },
                    showBookBlurb: function(bookName) {
                        for (blurb in this.$root.book_blurbs) {
                            if (strip(bookName).toUpperCase().includes(this.$root.book_blurbs[blurb].book.toUpperCase())) {
                                UIkit.modal.alert(this.$root.book_blurbs[blurb].blurb);
                                var modelDialog = document.getElementsByClassName("uk-modal-dialog")[0];
                                modelDialog.style.width = '95%';
                                modelDialog.style.maxWidth = '1000px';

                            }
                        }
                    },
                    gradeLabelClass: function(grading) {
                        if (grading === 'mutawatir') {
                            return 'uk-label';
                        } else if (grading === 'sahih') {
                            return 'uk-label-success';
                        } else if (grading === 'hassan') {
                            return 'uk-label-warning';
                        } else {
                            return 'uk-label-danger';
                        }
                    },
                    gradeLabelIcon: function(grading) {
                        if (grading === 'mutawatir') {
                            return 'fa fa-bullhorn';
                        } else if (grading === 'sahih') {
                            return 'fa fa-check-circle-o';
                        } else if (grading === 'hassan') {
                            return 'fa fa-thumbs-o-up';
                        } else {
                            return 'fa fa-thumbs-o-down';
                        }
                    },
                    isActiveClass: function(text) {
                        if (text && text.includes('<span')) {
                            return "uk-active"
                        } else {
                            return '';
                        }
                    }
                }
            });

    // create pagination component
    Vue.component('pagination', {
        template: '<ul v-if="showList()" style="margin-top: 25px;margin-bottom: -35px;margin-left: -40px; font-size: 15px;" class="uk-pagination uk-flex-left">' +
            '<li v-if="showPrevious()" v-on:click="goToPrevious()" style="margin-right:15px;"><a><span>&lArr; Previous</span></a></li>' +
            '<li v-bind:class="isActivePage(n)" v-if="showPage(n)" v-for="n in 20"><a v-on:click="goToPage(n)">{{n}}</a></li>' +
            '<li v-if="showNext()" v-on:click="goToNext()" style="margin-left:15px;"><a><span>Next &rArr;</span></a></li>' +
            '</ul>',
        methods: {
            isActivePage: function(n) {
                if (n == (this.$root.page)) {
                    return 'uk-active';
                }
            },
            showList: function() {
                if (this.$root.totalHits > this.$root.pageSize) {
                    return true;
                } else {
                    return false;
                }
            },
            showPage: function(n) {
                if (Math.ceil(this.$root.totalHits / this.$root.pageSize) >= n) {
                    return true;
                } else {
                    return false;
                }
            },
            showPrevious: function() {
                if (this.$root.page > 1) {
                    return true;
                } else {
                    return false;
                }
            },
            showNext: function() {
                if (this.$root.page < 21 && ((this.$root.totalHits / this.$root.pageSize) > (this.$root.page))) {
                    return true;
                } else {
                    return false;
                }
            },
            goToPrevious: function() {
                this.goToPage(this.$root.page - 1);
            },
            goToNext: function() {
                this.goToPage(this.$root.page + 1);
            },
            goToPage: function(n) {
                if (n !== this.$root.page) {
                    window.location.href = window.location.protocol + "//" +
                        window.location.host + window.location.pathname + '?' + 'q=' +
                        getQueryStringValue('q') + '&page=' + n;
                }
            }
        }
    });

    // clear welcome page content
    document.getElementById('welcome').innerHTML = '';

    // get book blurbs info
    $.getJSON("book_blurbs.json", function(book_blurbs) {

        vueApp = new Vue({
            el: '#hadithView',
            data: {
                narrations: [],
                queryStr: query,
                significantTerms: [],
                page: page,
                totalHits: 0,
                pageSize: 20,
                signedIn: false,
                book_blurbs: book_blurbs
            },
            // runs when the Vue instance has initialized.
            mounted: function() {
                this.fetchNarrations();
            },
            methods: {
                // fetches significant query terms using the Rewayaat REST API
                fetchSignificantTerms: function() {
                    var self = this;
                    if (this.queryStr) {
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
                // fetches more narrations to display using the Rewayaat
                // REST API.
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
                    xhr.open('GET', '/v1/narrations?q=' + this.queryStr +
                        '&page=' + this.page + '&per_page=' + this.pageSize);
                    xhr.send();
                },
                gradeLabelClass: function(grading) {
                    if (grading === 'mutawatir') {
                        return 'uk-label';
                    } else if (grading === 'sahih') {
                        return 'uk-label-success';
                    } else if (grading === 'hassan') {
                        return 'uk-label-warning';
                    } else {
                        return 'uk-label-danger';
                    }
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
                },
                gradeLabelIcon: function(grading) {
                    if (grading === 'mutawatir') {
                        return 'fa fa-bullhorn';
                    } else if (grading === 'sahih') {
                        return 'fa fa-check-circle-o';
                    } else if (grading === 'hasdan') {
                        return 'fa fa-thumbs-o-up';
                    } else {
                        return 'fa fa-thumbs-o-down';
                    }
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

                    originalHadith.book = '';
                    if (narration.book) {
                        originalHadith.book = narration.book;
                    }
                    originalHadith.number = '';
                    if (narration.number) {
                        originalHadith.number = narration.number;
                    }
                    originalHadith.source = '';
                    if (narration.source) {
                        originalHadith.source = narration.source;
                    }
                    originalHadith.part = '';
                    if (narration.part) {
                        originalHadith.part = narration.part;
                    }
                    originalHadith.edition = '';
                    if (narration.edition) {
                        originalHadith.edition = narration.edition;
                    }
                    originalHadith.chapter = '';
                    if (narration.chapter) {
                        originalHadith.chapter = narration.chapter;
                    }
                    originalHadith.publisher = '';
                    if (narration.publisher) {
                        originalHadith.publisher = narration.publisher;
                    }
                    originalHadith.section = '';
                    if (narration.section) {
                        originalHadith.section = narration.section;
                    }
                    originalHadith.volume = '';
                    if (narration.volume) {
                        originalHadith.volume = narration.volume;
                    }
                    originalHadith.tags = [];
                    if (narration.tags) {
                        originalHadith.tags = narration.tags;
                    }
                    originalHadith.notes = '';
                    if (narration.notes) {
                        originalHadith.notes = narration.notes;
                    }
                    originalHadith.arabic = '';
                    if (narration.arabic) {
                        originalHadith.arabic = narration.arabic;
                    }
                    originalHadith.english = '';
                    if (narration.english) {
                        originalHadith.english = narration.english;
                    }
                    originalHadith.gradings = [];
                    if (narration.gradings) {
                        originalHadith.gradings = narration.gradings;
                    }

                    // stores all the hadith properties that were modified.
                    var changedAtributes = new Object();

                    UIkit.modal.confirm('<h2>Editing Mode</h2><p>Modify this hadith in the editor below, make sure to read the <a target="_blank" href="https://github.com/rewayaat/rewayaat/wiki/Hadith-Entry-Guidelines">Hadith Entry Guidelines</a>. When finished, select <span style="display: inline-block;box-sizing: border-box;' +
                        'padding: 0 15px;vertical-align: middle;font-size: 14px;line-height: 28px;text-align: center;color: #fff;background-color:#1e87f0;' +
                        '">OK</span> to save your changes to the database.</p>').then(function() {
                        var modifiedHadith = editor.get();
                        var changeDetected = false;

                        if (modifiedHadith.book && (modifiedHadith.book !== narration.book)) {
                            changeDetected = true;
                            changedAtributes.book = modifiedHadith.book;
                        }
                        if (modifiedHadith.number && (modifiedHadith.number !== narration.number)) {
                            changeDetected = true;
                            changedAtributes.number = modifiedHadith.number;
                        }
                        if (modifiedHadith.source && (modifiedHadith.source !== narration.source)) {
                            changeDetected = true;
                            changedAtributes.source = modifiedHadith.source;
                        }
                        if (modifiedHadith.part && (modifiedHadith.part !== narration.part)) {
                            changeDetected = true;
                            changedAtributes.part = modifiedHadith.part;
                        }
                        if (modifiedHadith.edition && (modifiedHadith.edition !== narration.edition)) {
                            changeDetected = true;
                            changedAtributes.edition = modifiedHadith.edition;
                        }
                        if (modifiedHadith.chapter && (modifiedHadith.chapter !== narration.chapter)) {
                            changeDetected = true;
                            changedAtributes.chapter = modifiedHadith.chapter;
                        }
                        if (modifiedHadith.publisher && (modifiedHadith.publisher !== narration.publisher)) {
                            changeDetected = true;
                            changedAtributes.publisher = modifiedHadith.publisher;
                        }
                        if (modifiedHadith.volume && (modifiedHadith.volume !== narration.volume)) {
                            changeDetected = true;
                            changedAtributes.volume = modifiedHadith.volume;
                        }
                        if (modifiedHadith.section && (modifiedHadith.section !== narration.section)) {
                            changeDetected = true;
                            changedAtributes.section = modifiedHadith.section;
                        }
                        if (modifiedHadith.tags && (_.isEqual(modifiedHadith.tags, narration.tags) === false)) {
                            changeDetected = true;
                            changedAtributes.tags = modifiedHadith.tags;
                        }
                        if (modifiedHadith.notes && (modifiedHadith.notes !== narration.notes)) {
                            changeDetected = true;
                            changedAtributes.notes = modifiedHadith.notes;
                        }
                        if (modifiedHadith.gradings && (_.isEqual(modifiedHadith.gradings, narration.gradings) === false)) {
                            changeDetected = true;
                            changedAtributes.gradings = modifiedHadith.gradings;
                        }
                        if (modifiedHadith.arabic && (modifiedHadith.arabic !== narration.arabic)) {
                            changeDetected = true;
                            changedAtributes.arabic = modifiedHadith.arabic;
                        }
                        if (modifiedHadith.english && (modifiedHadith.english !== narration.english)) {
                            changeDetected = true;
                            changedAtributes.english = modifiedHadith.english;
                        }

                        if (changeDetected) {
                            // save hadith
                            var http = new XMLHttpRequest();
                            var url = "/v1/narrations?hadith_id=" + narration._id + '&id_token=' + google_id_token;
                            var data = JSON.stringify(changedAtributes);
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
                                    Object.keys(changedAtributes).forEach(function(key) {
                                        vueApp.narrations[index][key] = changedAtributes[key];
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
                    }, function() {
                        console.log('rejected');
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
    });
}

function strip(html) {
    var tmp = document.createElement("DIV");
    tmp.innerHTML = html;
    return tmp.textContent || tmp.innerText || "";
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
    var hadithText = encodeURIComponent(hadithObj.english.replaceAll(
        '<span class="highlight">', '').replaceAll('</span>', ''));

    hadithObj["facebook"] = "https://www.facebook.com/sharer/sharer.php?u=" +
        hadithURL;
    hadithObj["twitter"] = "https://twitter.com/intent/tweet/?text=" +
        hadithDesc + "&url=" + hadithURL;
    hadithObj["tumblr"] = "https://www.tumblr.com/widgets/share/tool?canonicalUrl=" + hadithURL + '&title=' + hadithDesc + '&caption=' + hadithText;
    hadithObj["googleplus"] = "https://plus.google.com/share?url=" + hadithURL;
    hadithObj["whatsapp"] = "whatsapp://send?text=" + hadithText + "\n\n[" +
        hadithURL + "]";
    return hadithObj;
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

function signOutOfRewayaat() {
    var auth2 = gapi.auth2.getAuthInstance();
    auth2.disconnect();
    location.reload();
}
