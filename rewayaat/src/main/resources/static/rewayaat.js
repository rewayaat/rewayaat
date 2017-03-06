function loadQuery(query) {
	if (query) {
		// load the query
		var currentQuery = new HadithQuery(query);
		// display query results
		currentQuery.showNextPage();
	} else {
		// show default mark-down welcome page
		
	}
}

function submitSearchQuery() {
	var query = document.getElementById("query").value;
	window.location = "/?query=" + encodeURIComponent(query);
}

class HadithQuery {
	constructor(query) {
		// setup a new Vue Instance
		this.hadith = new Array();
		this.query = query;
		this.page = 0;
		this.showNextPage();	
	}	
	
	/**
	 * Loads the next page of hadith using the rewayaat REST API.
	 */
	getMoreHadith(callback) {
		$.ajax({
	        url: '/api/hadith/query?query=' + this.query + '&page=' + this.page,
	        type: "GET",
	        success: function(data, status, xhr) {
	        	callback(data);
	        },
	        timeout: 30000,
	        error: function(e, b, a) {
	        	swal("Oops!", "Something went wrong!", "error");
	        }
	    });
		this.page++;
	}
	
	/**
	 * Shows the next page of query results.
	 */
	showNextPage() {
		this.getMoreHadith(function(hadithCollectionStr) {
			
		})
	}
}