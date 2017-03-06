<!doctype html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="description"
	content="The most comprehensive hadith searching tool">
<meta name="keywords"
	content="hadith, rewayaat, search, islam, narration, quran">
<meta name="author" content="Rewayaat">
<meta name="viewport"
	content="width=device-width, initial-scale=1, maximum-scale=1">
<link rel="stylesheet" href="/theme.css" />
<link rel="stylesheet" href="/sweetalert.css" />
<link href="https://fonts.googleapis.com/css?family=Source+Sans+Pro"
	rel="stylesheet">
<link href="https://fonts.googleapis.com/css?family=Roboto"
	rel="stylesheet">
<link href="https://fonts.googleapis.com/css?family=Open+Sans"
	rel="stylesheet">
<link href="/pace.css" rel="stylesheet">
<script
	src="https://cdnjs.cloudflare.com/ajax/libs/clipboard.js/1.5.16/clipboard.min.js"></script>
<script
	src="https://cdnjs.cloudflare.com/ajax/libs/jquery/3.1.1/jquery.min.js"></script>
<script
	src="https://cdnjs.cloudflare.com/ajax/libs/marked/0.3.6/marked.min.js"></script>
<script
	src="https://cdnjs.cloudflare.com/ajax/libs/vue/2.1.8/vue.min.js"></script>
<script
	src="https://cdnjs.cloudflare.com/ajax/libs/vue-router/2.1.1/vue-router.min.js"></script>
<script src="/rewayaat.js"></script>
<script src="/pace.min.js"></script>
<script src="/uikit.min.js"></script>
<script src="/sweetalert-dev.js"></script>
</head>
<body>
	<!--  LOAD THE MENU BAR AT THE TOP -->
	<div id="header"></div>
	<!---->
	<!--  VUE APP  -->
	<div id="hadithView"></div>	
	<!---->
	<script type="text/javascript">
		$(function() {
			$("#header").load("/header.html");
		});
		var query = '${query}';
		loadQuery(query);
	</script>
</body>
</html>
