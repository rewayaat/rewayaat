var vueApp;

/**
 * Main entry point to the website. If does not exist, display default welcome content.
 * If there is a valid query, setup a Vue.js instance to display it.s
 */
function loadQuery(query) {
	if (query) {
		// load the query
		setupVue(query);
	} else {
		// show default mark-down welcome page
		displayWelcomeContent();
	}
}

function displayWelcomeContent() {
	document.getElementById('hadithView').innerHTML = '';
	vueApp = new Vue({el : '#hadithView'});
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
    if (((window.innerHeight + window.pageYOffset) >= document.body.offsetHeight) && document.getElementById('hadithView').innerHTML !== '') {
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
					queryDivInnerHTML += '<span  style="color: #cec9c9">'
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
 * created vue instance in the global vueApp var.
 */
function setupVue(query) {
	
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
			// fetches more narrations to display using the Rewayaat REST API.
			fetchNarrations : function() {
				if (!this.done) {
				var self = this;
				var xhr = new XMLHttpRequest();
				xhr.onload = function() {
					if (xhr.readyState == XMLHttpRequest.DONE) {
						if (JSON.parse(xhr.responseText).length < 1 && self.narrations.length === 0) {
							swal("Oops...", "No results seem to match your query!", "error");
						}
						$.each(JSON.parse(xhr.responseText), function(index, value) {
							setTimeout(function() {
								value.notes = marked(value.notes);
								value = socialMediaDecoratedHadith(value);
								self.narrations.push(value);
								console.log(value);
							}, 200 * index);

						});
						if (JSON.parse(xhr.responseText).length < 10) {
							self.done = true;
						}
						
					}
				}
				xhr.open('GET', '/api/narrations?q=' + this.queryStr + '&page='
						+ this.page);
				xhr.send();
				this.page++;
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
				if (text.includes('<span')) {
					return "uk-active"
				} else {
					return '';
				}
			}
		}
	});
}

/**
 * Adds relevant social media urls as properties of the given hadith object.
 */
function socialMediaDecoratedHadith(hadithObj) {
	
	var hadithURL = encodeURIComponent(location.protocol + "//" + location.host + "/?q=_id:" + hadithObj._id);
	var hadithText = "";
	if (hadithObj.book) {
		hadithText += hadithObj.book;
	}
	if (hadithObj.edition) {
		hadithText += " (" +  hadithObj.edition + "), ";
	} else {
		hadithText += ", ";
	}
	if (hadithObj.number) {
		hadithText += "#" +  hadithObj.number + ", ";
	}
	if (hadithObj.chapter) {
		hadithText += "CHAP. " +  hadithObj.chapter + ", ";
	}
	if (hadithObj.volume) {
		hadithText += "VOL. " +  hadithObj.volume;
	}
	// keep the overall text < 150 (to stay within twitter max length)
	var remainingLen = 150 - (hadithText.length + hadithURL.length);
	var hadithText = hadithText + " \"" + hadithObj.english.slice(remainingLen * -1) + "\""; 
	hadithText = encodeURIComponent(hadithText);
	hadithObj["facebook"] = "https://www.facebook.com/sharer/sharer.php?u=" + hadithURL;
	hadithObj["twitter"] = "https://twitter.com/intent/tweet/?text=" + hadithText + "&url=" + hadithURL;
	hadithObj["tumblr"] = "https://www.tumblr.com/widgets/share/tool/preview?posttype=link&title=Rewayaat.io&caption=" + hadithText + "&content=" + hadithURL + "&shareSource=tumblr_share_button&_format=html";
	hadithObj["googleplus"] = "https://plus.google.com/share?url=" + hadithURL;
	hadithObj["whatsapp"] = "whatsapp://send?text=" + hadithText + "%20" + hadithURL;
	return hadithObj;
}

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