var vueApp;
var loadingHadith = false;

/**
 * Main entry point to the website. If does not exist, display default welcome
 * content. If there is a valid query, setup a Vue.js instance to display it.s
 */
function loadQuery(query) {
	if (query) {
		// validate query
		if (validQuery(query)) {
			// load the query
			setupVue(query);
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

function validQuery(query) {
	if (query.trim().length < 4) {
		return false;
	}
	return true;
}

function displayWelcomeContent() {
	document.getElementById('hadithView').innerHTML = '';
	vueApp = new Vue({
		el : '#hadithView'
	});
	$("#welcome").load("/welcome.html");
}

// records queries on enter key-presses.
$(document).keypress(function(e) {
	if (e.which == 13 || event.keyCode == 13) {
		// Enter key was pressed.
		var el = document.activeElement;
		if (el && (el.id == 'query')) {
			// focused element is query search bar.
			submitSearchQuery();
		}
	}
});

// loads more hadith when near the bottom of the page
window.onscroll = function(ev) {
	if (((window.innerHeight + window.pageYOffset) >= (document.body.offsetHeight - 100))
			&& document.getElementById('hadithView').innerHTML !== '') {
		vueApp.fetchNarrations();
	}
};
// records queries on enter key-presses.
$(document).keyup(function(e) {
	if (isCharacterKeyPress(e)) {
		var el = document.activeElement;
		if (el && (el.id == 'query')) {
			updateQueryColor(getCaretCharacterOffsetWithin(el));
		}
	}
});

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

/**
 * Adds color to elastic search query string query symbols in front end user
 * queries. Some symbols that are coloured include: [,], [0-9], {, }, ~, ^,
 * etc...
 */
function updateQueryColor(currentCaretPosition) {
	var queryDiv = document.getElementById('query');
	if (queryDiv) {
		var queryValue = queryDiv.innerText;
		if (queryValue) {
			var queryDivInnerHTML = '';
			for (var i = 0, len = queryValue.length; i < len; i++) {
				if (queryValue[i] === '+') {
					queryDivInnerHTML += '<span class="querySymbol" style="color: #22df80">+</span>';
				} else if (queryValue[i] === '-') {
					queryDivInnerHTML += '<span class="querySymbol" style="color: #ea4f4f">-</span>';
				} else if (queryValue[i] === '[') {
					queryDivInnerHTML += '<span class="querySymbol" style="color: #cc98f1">[</span>';
				} else if (queryValue[i] === ']') {
					queryDivInnerHTML += '<span class="querySymbolRight" style="color: #cc98f1">]</span>';
				} else if (queryValue[i] === '~') {
					queryDivInnerHTML += '<span class="querySymbol" style="color: #f8bb86">~</span>';
				} else if (queryValue[i] === '^') {
					queryDivInnerHTML += '<span class="querySymbol" style="color: #32d2cd">^</span>';
				} else if (isNumeric(queryValue[i])) {
					queryDivInnerHTML += '<span style="color: #98c2f1">'
							+ queryValue[i] + '</span>';
				} else {
					queryDivInnerHTML += '<span  style="color: #000">'
							+ queryValue[i] + '</span>';
				}
			}
			queryDiv.innerHTML = queryDivInnerHTML;
			if (currentCaretPosition) {
				setCaretPostion(queryDiv, currentCaretPosition);
			}
		}
	}
}

function isArabic(text) {
	var pattern = /[\u0600-\u06FF\u0750-\u077F]/;
	result = pattern.test(text);
	return result;
}

function isNumeric(obj) {
	return !isNaN(obj - parseFloat(obj));
}

function getCaretCharacterOffsetWithin(element) {
	var caretOffset = 0;
	if (typeof window.getSelection != "undefined") {
		var range = window.getSelection().getRangeAt(0);
		var preCaretRange = range.cloneRange();
		preCaretRange.selectNodeContents(element);
		preCaretRange.setEnd(range.endContainer, range.endOffset);
		caretOffset = preCaretRange.toString().length;
	} else if (typeof document.selection != "undefined"
			&& document.selection.type != "Control") {
		var textRange = document.selection.createRange();
		var preCaretTextRange = document.body.createTextRange();
		preCaretTextRange.moveToElementText(element);
		preCaretTextRange.setEndPoint("EndToEnd", textRange);
		caretOffset = preCaretTextRange.text.length;
	}
	return caretOffset;
}

function setCaretPostion(el, pos) {
	var range = document.createRange();
	var sel = window.getSelection();
	range.setStart(el, pos);
	range.collapse(true);
	sel.removeAllRanges();
	sel.addRange(range);
	el.focus();
}

// processes the user's query by redirecting with query parameter.
function submitSearchQuery() {
	var query = encodeURIComponent(document.getElementById("query").innerText);
	window.location.href = window.location.protocol + "//"
			+ window.location.host + window.location.pathname + '?' + 'q='
			+ query;
}

/**
 * Main method responsible for displaying queries using Vue.js. Stores the
 * created Vue instance in the global vueApp variable.
 */
function setupVue(query) {

	// create hadith details component
	Vue
			.component(
					'hadith-details',
					{
						template : '<div><div title="Book" uk-tooltip="pos: right" class="uk-align-left">'
								+ '	<i style="color: rgb(83, 102, 125);" class="fa fa-book hadithDetailsIcon"'
								+ '		aria-hidden="true"></i>'
								+ '	<p class="hadithDetailsTitle" v-html="narration.book" />'
								+ '</div>'
								+ '<div title="Edition" uk-tooltip="pos: right" class="uk-align-left" v-if="narration.edition">'
								+ '<i style="color: rgb(83, 102, 125)"'
								+ '	class="fa fa-pencil-square-o hadithDetailsIcon"'
								+ '	aria-hidden="true"></i>'
								+ '<p class="hadithDetailsTitle">({{narration.edition}})</p>'
								+ '</div>'
								+ '<div title="Number" uk-tooltip="pos: right" class="uk-align-left" v-if="narration.number">'
								+ '<i style="color: rgb(83, 102, 125)"'
								+ '	class="fa fa-pencil-square-o hadithDetailsIcon"'
								+ '	aria-hidden="true"></i>'
								+ '<p class="hadithDetailsTitle">Hadith #{{narration.number}}</p>'
								+ '</div>'
								+ '<div title="Chapter" uk-tooltip="pos: right" class="uk-align-left" v-if="narration.chapter">'
								+ '<i style="color: rgb(83, 102, 125);"'
								+ '	class="fa fa-superpowers hadithDetailsIcon" aria-hidden="true"></i>'
								+ '<p class="hadithDetailsTitle" v-html="narration.chapter" />'
								+ '</div>'
								+ '<div title="Section" uk-tooltip="pos: right"  class="uk-align-left" v-if="narration.section">'
								+ '<i style="color:  rgb(83, 102, 125);"'
								+ '	class="fa fa-bookmark-o hadithDetailsIcon" aria-hidden="true"></i>'
								+ '<p class="hadithDetailsTitle" v-html="narration.section" />'
								+ '</div>'
								+ '<div title="Part" uk-tooltip="pos: right" class="uk-align-left" v-if="narration.part">'
								+ '<i style="color: rgb(83, 102, 125);"'
								+ '  class="fa fa-clone hadithDetailsIcon" aria-hidden="true"></i>'
								+ '	<p class="hadithDetailsTitle" v-html="narration.part" />'
								+ '</div>'
								+ '<div title="Volume" uk-tooltip="pos: right" class="uk-align-left" v-if="narration.volume">'
								+ '<i style="color:rgb(83, 102, 125)"'
								+ '	class="fa fa-calendar-o hadithDetailsIcon" aria-hidden="true"></i>'
								+ '<p class="hadithDetailsTitle" v-html="narration.volume" />'
								+ '</div>'
								+ '<span title="Grading" uk-tooltip="pos: right" class="uk-align-left"'
								+ 'v-for="gradingobj in narration.gradings"'
								+ 'v-bind:class="gradeLabelClass(gradingobj.grading)"> <i'
								+ 'v-bind:class="gradeLabelIcon(gradingobj.grading)"'
								+ 'aria-hidden="true"></i> {{gradingobj.grader}}'
								+ '</span></div>',
						props : [ 'narration' ]

					});

	// clear welcome page content
	document.getElementById('welcome').innerHTML = '';

	vueApp = new Vue({
		el : '#hadithView',
		data : {
			narrations : [],
			queryStr : query,
			page : 0,
			done : false
		},
		// runs when the Vue instance has initialized.
		mounted : function() {
			this.fetchNarrations();
		},
		methods : {
			// fetches more narrations to display using the Rewayaat
			// REST API.
			fetchNarrations : function() {
				loadingHadith = true;
				if (!this.done) {
					var self = this;
					var xhr = new XMLHttpRequest();
					xhr.onload = function() {
						if (xhr.readyState == XMLHttpRequest.DONE) {
							if (JSON.parse(xhr.responseText).length < 1
									&& self.narrations.length === 0) {
								swal("Oops...",
										"No results seem to match your query!",
										"error");
							}
							$.each(JSON.parse(xhr.responseText), function(
									index, value) {

								if (value.notes) {
									value.notes = marked(value.notes);
								}
								value.volume = "Volume " + value.volume;
								value = quranicVersesDecoratedHadith(value);
								value = socialMediaDecoratedHadith(value);
								self.narrations.push(value);
								console.log(value);

							});
							if (JSON.parse(xhr.responseText).length < 10) {
								self.done = true;
							}

						}
					}
					xhr.open('GET', '/v1/narrations?q=' + this.queryStr
							+ '&page=' + this.page);
					xhr.send();
					this.page++;
					loadingHadith = false;
				}
			},
			gradeLabelClass : function(grading) {
				if (grading === 'mutawatir') {
					return 'uk-label';
				} else if (grading === 'sahih') {
					return 'uk-label-success';
				} else if (grading === 'hasan') {
					return 'uk-label-warning';
				} else {
					return 'uk-label-danger';
				}
			},
			gradeLabelIcon : function(grading) {
				if (grading === 'mutawatir') {
					return 'fa fa-bullhorn';
				} else if (grading === 'sahih') {
					return 'fa fa-check-circle-o';
				} else if (grading === 'hasan') {
					return 'fa fa-thumbs-o-up';
				} else {
					return 'fa fa-thumbs-o-down';
				}
			},
			isActiveClass : function(text) {
				if (text && text.includes('<span')) {
					return "uk-active"
				} else {
					return '';
				}
			}
		}
	});
}

/**
 * Adds relevant social media URLS as properties of the given hadith object.
 */
function socialMediaDecoratedHadith(hadithObj) {

	var hadithURL = encodeURIComponent(location.protocol + "//" + location.host
			+ "/?q=_id:" + hadithObj._id);
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
	// keep the overall hadithDesc + hadithURL < 150 (to stay within twitter max
	// length)
	if ((hadithDesc.length + hadithURL.length) > 140) {
		hadithTextDesiredLen = 140 - hadithURL.length;
		hadithDesc = hadithDesc.substring(0, hadithTextDesiredLen);
	}

	hadithDesc = hadithDesc.replaceAll('<span class="highlight">', '');
	hadithDesc = hadithDesc.replaceAll('</span>', '');
	hadithDesc = encodeURIComponent(hadithDesc);
	var hadithText = encodeURIComponent(hadithObj.english.replaceAll(
			'<span class="highlight">', '').replaceAll('</span>', ''));

	hadithObj["facebook"] = "https://www.facebook.com/sharer/sharer.php?u="
			+ hadithURL;
	hadithObj["twitter"] = "https://twitter.com/intent/tweet/?text="
			+ hadithDesc + "&url=" + hadithURL;
	hadithObj["tumblr"] = "https://www.tumblr.com/widgets/share/tool/preview?posttype=link&title=Rewayaat.io&caption="
			+ hadithDesc
			+ "&content="
			+ hadithURL
			+ "&shareSource=tumblr_share_button&_format=html";
	hadithObj["googleplus"] = "https://plus.google.com/share?url=" + hadithURL;
	hadithObj["whatsapp"] = "whatsapp://send?text=" + hadithText + " - "
			+ hadithURL + "%20" + hadithURL;
	return hadithObj;
}

/**
 * References to Qur'anic verses in the hadith object are replaced with
 * hyper-links that allow users to view those verses.
 */
function quranicVersesDecoratedHadith(hadithObj) {

	var quranicVerses = hadithObj.english.match(/\s\[?[0-9]+:[0-9]+\]?\s/g);
	if (quranicVerses) {
		for (var i = 0, l = quranicVerses.length; i < l; i++) {
			// trim, remove square brackets, and split on ':' symbol
			var reference = quranicVerses[i].trim();
			reference = reference.replaceAll('\\[', '');
			reference = reference.replaceAll('\\]', '');
			reference = reference.split(":");
			var modalId = makeid();
			var buttonCode = '<a href="#' + modalId + '" uk-toggle>'
					+ quranicVerses[i].trim() + '</a>'
			createQuranicVerseModal(reference[0], reference[1], modalId);
			hadithObj.english = hadithObj.english.replace(quranicVerses[i]
					.trim(), buttonCode);
		}
	}
	return hadithObj;
}

/**
 * Appends a modal object containing information for the given Qur'anic verse to the dom.
 */
function createQuranicVerseModal(surah, ayat, divId) {

	var url = 'http://api.alquran.cloud/ayah/' + surah + ':' + ayat
			+ '/editions/quran-simple,en.asad';
	$
			.get(
					url,
					function(data, status) {
						// create UIKit modal
						var divCode = '<div id="' + divId + '" uk-modal>';
						divCode += ' <div class="uk-modal-dialog">';
						divCode += '<button class="uk-modal-close-default" type="button" uk-close></button>';
						divCode += '<div class="uk-modal-header"> <h2 class="uk-modal-title">Surah #'
								+ surah
								+ ' ('
								+ data.data[0].surah.englishName
								+ '), Verse #' + ayat + '</h2> </div>';
						divCode += ' <div class="uk-modal-body"><p style="font-family:Scheherazade; font-size:35px; direction: rtl;">'
								+ data.data[0].text + '</p><p>'
								+ data.data[1].text + '</p></div>';
						divCode += '</div></div>';
						document.getElementById("hadithView").innerHTML += divCode;
					});
}

String.prototype.replaceAll = function(search, replacement) {
	var target = this;
	return target.replace(new RegExp(search, 'g'), replacement);
};

function getParameterByName(name, url) {
	if (!url) {
		url = window.location.href;
	}
	name = name.replace(/[\[\]]/g, "\\$&");
	var regex = new RegExp("[?&]" + name + "(=([^&#]*)|&|#|$)"), results = regex
			.exec(url);
	if (!results)
		return null;
	if (!results[2])
		return '';
	return decodeURIComponent(results[2].replace(/\+/g, " "));
}

function getQueryStringValue(key) {
	return decodeURIComponent(window.location.search.replace(new RegExp(
			"^(?:.*[&\\?]"
					+ encodeURIComponent(key).replace(/[\.\+\*]/g, "\\$&")
					+ "(?:\\=([^&]*))?)?.*$", "i"), "$1"));
}

/**
 * Generates a random string, suitable for making random id's.
 */
function makeid() {
	var text = "";
	var possible = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

	for (var i = 0; i < 5; i++)
		text += possible.charAt(Math.floor(Math.random() * possible.length));

	return text;
}