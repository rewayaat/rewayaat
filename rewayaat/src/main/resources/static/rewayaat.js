var vueApp;
var loadingHadith = false;
var signedIn = false;
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

$(document).ready(function(){
  var changeCardWidth = function(){
    if ( $(window).width() < 768 ){
        $('.uk-card').css({"padding-left" : "0px"});
        $('.uk-container-large').css({"width" : "97%"});
        $('.uk-align-left').css({"margin-right" : "0px"});

  };
  if ( $(window).width() >= 768 ){
          $('.uk-card').css({"padding-left" : "30px"});
          $('.uk-container-large').css({"width" : "80%"});
          $('.uk-align-left').css({"margin-right" : "30px"});
    };


   };
  $(window).resize(changeCardWidth);
  changeCardWidth();
});

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
	$("#welcome").load("/welcome.html");
	var queryBar = document.getElementById("queryBar");
	if (queryBar) {
		queryBar.style.opacity = "0";
		document.getElementById("queryBarText").innerHTML = '';
	}
}

// records queries on enter key-presses.
$(document).keypress(function (e) {
	if (e.which == 13 || e.keyCode == 13) {
		// Enter key was pressed.
		var el = document.activeElement;
		if (el && (el.id == 'query')) {
			// focused element is query search bar.
			submitSearchQuery();
		}
		if (el && (el.id == 'queryBarText')) {
			// focused element is query search bar.
			submitSearchQuery();
		}
	}
});

// loads more hadith when near the bottom of the page
window.onscroll = function (ev) {
	if (((window.innerHeight + window.pageYOffset) >= (document.body.offsetHeight - (100 * (vueApp.page + 1))))
		&& document.getElementById('hadithView').innerHTML !== ''
		&& loadingHadith == false) {
		vueApp.fetchNarrations();
	}
};

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
	var queryBarOne = document.getElementById("query");
	if (queryBarOne) {
		var query = encodeURIComponent(queryBarOne.innerText);
	}
	if (!query) {
		query = encodeURIComponent(document.getElementById("queryBarText").innerText);
	}
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
			template: '<div><div title="Book" uk-tooltip="pos: right" style=" margin-right:30px;" class="uk-align-left" >'
				+ '	<i style="color: rgb(83, 102, 125);" class="fa fa-book hadithDetailsIcon"'
				+ '		aria-hidden="true"></i>'
				+ '	<p class="hadithDetailsTitle" v-html="narration.book" />'
				+ '</div>'
				+ '<div title="Edition" uk-tooltip="pos: right" style=" margin-right:30px;" class="uk-align-left"  v-if="narration.edition">'
				+ '<i style="color: rgb(83, 102, 125)"'
				+ '	class="fa fa-pencil-square-o hadithDetailsIcon"'
				+ '	aria-hidden="true"></i>'
				+ '<p class="hadithDetailsTitle">({{narration.edition}})</p>'
				+ '</div>'
				+ '<div title="Number" uk-tooltip="pos: right" style=" margin-right:30px;" class="uk-align-left"  v-if="narration.number">'
				+ '<i style="color: rgb(83, 102, 125)"'
				+ '	class="fa fa-pencil-square-o hadithDetailsIcon"'
				+ '	aria-hidden="true"></i>'
				+ '<p class="hadithDetailsTitle">Hadith #{{narration.number}}</p>'
				+ '</div>'
				+ '<div title="Chapter" uk-tooltip="pos: right" style=" margin-right:30px;" class="uk-align-left"  v-if="narration.chapter">'
				+ '<i style="color: rgb(83, 102, 125);"'
				+ '	class="fa fa-superpowers hadithDetailsIcon" aria-hidden="true"></i>'
				+ '<p class="hadithDetailsTitle" v-html="narration.chapter" />'
				+ '</div>'
				+ '<div title="Section" uk-tooltip="pos: right"  class="uk-align-left" v-if="narration.section">'
				+ '<i style="color:  rgb(83, 102, 125);"'
				+ '	class="fa fa-bookmark-o hadithDetailsIcon" aria-hidden="true"></i>'
				+ '<p class="hadithDetailsTitle" v-html="narration.section" />'
				+ '</div>'
				+ '<div title="Part" uk-tooltip="pos: right" style=" margin-right:30px;" class="uk-align-left"  v-if="narration.part">'
				+ '<i style="color: rgb(83, 102, 125);"'
				+ '  class="fa fa-clone hadithDetailsIcon" aria-hidden="true"></i>'
				+ '	<p class="hadithDetailsTitle" v-html="narration.part" />'
				+ '</div>'
				+ '<div title="Volume" uk-tooltip="pos: right" style=" margin-right:30px;" class="uk-align-left"  v-if="narration.volume">'
				+ '<i style="color:rgb(83, 102, 125)"'
				+ '	class="fa fa-calendar-o hadithDetailsIcon" aria-hidden="true"></i>'
				+ '<p class="hadithDetailsTitle" v-html="narration.volume" />'
				+ '</div>'
				+ '<div title="Source" uk-tooltip="pos: right" style=" margin-right:30px;" class="uk-align-left"  v-if="narration.source">'
				+ '<i style="color:rgb(83, 102, 125)"'
				+ '	class="fa fa-share-square-o hadithDetailsIcon" aria-hidden="true"></i>'
				+ '<p class="hadithDetailsTitle" v-html="narration.source" />'
				+ '</div>'
				+ '<div title="Publisher" uk-tooltip="pos: right" style=" margin-right:30px;" class="uk-align-left"  v-if="narration.publisher">'
				+ '<i style="color:rgb(83, 102, 125)"'
				+ '	class="fa fa-medium hadithDetailsIcon" aria-hidden="true"></i>'
				+ '<p class="hadithDetailsTitle" v-html="narration.publisher" />'
				+ '</div>'
				+ '<span v-on:click="showHadithInfo(gradingobj)" style="padding:10px;    max-width: 150px; text-align:center; width: 80%; margin-right:25px; cursor:pointer; margin-top:15px;" title="Click for more info" uk-tooltip="pos: right" class="uk-align-left" '
				+ 'v-for="gradingobj in narration.gradings"'
				+ 'v-bind:class="gradeLabelClass(gradingobj.grading)"> <i'
				+ ' v-bind:class="gradeLabelIcon(gradingobj.grading)" '
				+ 'aria-hidden="true"></i> {{gradingobj.grading}}'
				+ '</span></div>',
			props: ['narration'],
			methods: {
				showHadithInfo: function (gradingobj) {
					var rationaleStr = '';
					if (gradingobj.rationale) {
						rationaleStr = gradingobj.rationale;
					}
					UIkit.modal.alert('<h2>Hadith Grading</h2><p>This hadith was given a grading of <code>' + gradingobj.grading + '</code> by ' +
						gradingobj.grader + '. ' + rationaleStr + '</p>');
				},
				gradeLabelClass: function (grading) {
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
				gradeLabelIcon: function (grading) {
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
				isActiveClass: function (text) {
					if (text && text.includes('<span')) {
						return "uk-active"
					} else {
						return '';
					}
				}
			}

		});

	// clear welcome page content
	document.getElementById('welcome').innerHTML = '';

	vueApp = new Vue({
		el: '#hadithView',
		data: {
			narrations: [],
			queryStr: query,
			page: 0,
			totalHits: 0,
			done: false,
			signedIn: signedIn
		},
		// runs when the Vue instance has initialized.
		mounted: function () {
			this.fetchNarrations();
		},
		methods: {
			// fetches more narrations to display using the Rewayaat
			// REST API.
			fetchNarrations: function () {
				loadingHadith = true;
				if (!this.done) {
					var self = this;
					var xhr = new XMLHttpRequest();
					xhr.onload = function () {
						if (xhr.readyState == XMLHttpRequest.DONE) {
							var queryBar = document.getElementById("queryBar");
							queryBar.style.opacity = "1";
							document.getElementById("queryBarText").innerHTML = query;
							var respJSON = JSON.parse(xhr.responseText)
							if (respJSON.collection.length < 1
								&& self.narrations.length === 0) {
								swal("Oops...",
									"No results seem to match your query!",
									"error");
							}
							$.each(respJSON.collection, function (index, value) {

								if (value.notes) {
									value.notes = marked(value.notes);
								}
								if (value.volume) {
									value.volume = "Volume " + value.volume;
								}
								value = quranicVersesDecoratedHadith(value);
								value = socialMediaDecoratedHadith(value);
								self.narrations.push(value);
								console.log(value);

							});
							if (respJSON.collection.length < 10) {
								self.done = true;
							}
							// set total results size value
							self.totalHits = respJSON.totalResultSetSize;
							loadingHadith = false;
						}
					}
					xhr.open('GET', '/v1/narrations?q=' + this.queryStr
						+ '&page=' + this.page);
					xhr.send();
					this.page++;
				}
			},
			gradeLabelClass: function (grading) {
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
			gradeLabelIcon: function (grading) {
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
			isActiveClass: function (text) {
				if (text && text.includes('<span')) {
					return "uk-active"
				} else {
					return '';
				}
			},
			showHadithURL: function (id) {
				UIkit.modal.alert('<h2>Hadith URL</h2><pre>' + location.protocol + '//' + location.host + '/?q=_id:' + id + '</pre>');
			},
			editHadith: function (narration, index) {
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
					'">OK</span> to save your changes to the database.</p>').then(function () {
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
							var url = "/v1/narrations?id=" + narration._id;
							var data = JSON.stringify(changedAtributes);
							http.open("POST", url, true);

							//Send the proper header information along with the request
							http.setRequestHeader("Content-type", "application/json");

							http.onreadystatechange = function () {//Call a function when the state changes.
								if (http.readyState == 4 && http.status == 200) {
									swal(
										"Success!",
										"This hadith was successfully modifed.",
										"success");
									// modify existing hadith object with new values
									Object.keys(changedAtributes).forEach(function (key) {
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
					}, function () {
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
				var editor = new JSONEditor(container, options);
				editor.set(originalHadith);
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
 * Appends a modal object containing information for the given Qur'anic verse to
 * the dom.
 */
function createQuranicVerseModal(surah, ayat, divId) {

	var url = 'http://api.alquran.cloud/ayah/' + surah + ':' + ayat
		+ '/editions/quran-simple,en.asad';
	$
		.get(
		url,
		function (data, status) {
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
				+ data.data[0].text
				+ '</p><p>'
				+ data.data[1].text + '</p></div>';
			divCode += '</div></div>';
			document.getElementById("hadithView").innerHTML += divCode;
		});
}

String.prototype.replaceAll = function (search, replacement) {
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

function onSignIn(googleUser) {
	var profile = googleUser.getBasicProfile();
	console.log('ID: ' + profile.getId()); // Do not send to your backend! Use an ID token instead.
	console.log('Name: ' + profile.getName());
	console.log('Image URL: ' + profile.getImageUrl());
	console.log('Email: ' + profile.getEmail()); // This is null if the 'email' scope is not present.
	var id_token = googleUser.getAuthResponse().id_token;
	var xhr = new XMLHttpRequest();
	xhr.open('POST', '/google/signin');
	xhr.setRequestHeader('Content-Type', 'application/x-www-form-urlencoded');
	xhr.onload = function () {
		if (xhr.status == 200){
			if (vueApp) {
				vueApp.signedIn = true;
				vueApp.$forceUpdate();
			} else {
				signedIn = true;
			}
		} else {
			if (vueApp) {
				vueApp.signedIn = false;
				vueApp.$forceUpdate();
			} else {
				signedIn = false;
			}
		}
	};
	xhr.send('idtoken=' + id_token);
}