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
	var hadithDiv = document.getElementById('hadithView');
	hadithDiv.innerHTML = "Hello";
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
					queryDivInnerHTML += '<span style="color: #98c2f1">' + queryValue[i] + '</span>';
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

function setupVue(query) {
	new Vue({
		el : '#hadithView',
		data : {
			narrations : [],
			queryStr : query,
			page : 0
		},
		// runs when the Vue instance has initialized.
		mounted : function() {
			this.fetchNarrations();
		},
		methods : {
			// fetches more narrations to display using the Rewayaat REST API.
			fetchNarrations : function() {
				var xhr = new XMLHttpRequest();
				var self = this;
				xhr.onload = function() {
					if (xhr.readyState == XMLHttpRequest.DONE) {
						var body = JSON.parse(xhr.responseText);
						$.each(body, function(index, value) {
							self.narrations.push(value);
							console.log(value);
						});
					}
				}
				xhr.open('GET', '/api/narrations?q=' + this.queryStr + '&page='
						+ this.page);
				xhr.send();
				this.page++;
			}
		}
	});
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