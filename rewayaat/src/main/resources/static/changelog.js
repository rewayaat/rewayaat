var vueApp;

function loadChangelog() {
	setupChangelogVue();
}

// loads more hadith when near the bottom of the page
window.onscroll = function(ev) {
	if (((window.innerHeight + window.pageYOffset) >= document.body.offsetHeight)) {
		vueApp.fetchChangeLog();
	}
};

/**
 * Main method responsible for displaying queries using Vue.js. Stores the
 * created vue instance in the global vueApp var.
 */
function setupVue(query) {

	vueApp = new Vue({
		el : '#changelogView',
		data : {
			changelogitems : [],
			page : 0,
			done : false
		},
		// runs when the Vue instance has initialized.
		mounted : function() {
			this.fetchChangeLog();
		},
		methods : {
			fetchChangeLog : function() {
				if (!this.done) {
					var self = this;
					var xhr = new XMLHttpRequest();
					xhr.onload = function() {
						if (xhr.readyState == XMLHttpRequest.DONE) {
							$.each(JSON.parse(xhr.responseText), function(
									index, value) {
								setTimeout(function() {
									self.changelogitems.push(value);
									console.log(value);
								}, 200 * index);
							});
							if (JSON.parse(xhr.responseText).length < 100) {
								self.done = true;
							}

						}
					}
					xhr.open('GET', '/v1/changelog?page=' + this.page);
					xhr.send();
					this.page++;
				}
			}
		}
	});
}