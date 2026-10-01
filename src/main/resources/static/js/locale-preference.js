/**
 * Records a signed-in reader's language on their account when they use the toggle.
 *
 * The toggle itself is navigation — the language is the URL — so this changes nothing
 * about where the click goes. What it adds is the part navigation cannot carry: which
 * language to write to the reader in. Mail is sent from a background job or from a
 * signed-out reset form, neither of which knows what page anyone was last on, so the
 * preference has to live on the account.
 *
 * The request is fired and not waited on, with keepalive so the navigation that follows
 * does not cancel it. A reader who is not signed in gets a 400 that nobody reads, which
 * is cheaper than asking the server who they are before every switch.
 */
(function () {
    function record(tag) {
        try {
            fetch('/v1/auth/locale', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                credentials: 'same-origin',
                keepalive: true,
                body: JSON.stringify({ locale: tag })
            }).catch(function () { /* the toggle still navigates */ });
        } catch (e) {
            /* the toggle still navigates */
        }
    }

    document.addEventListener('click', function (event) {
        var toggle = event.target.closest('[data-set-locale]');
        if (!toggle) {
            return;
        }
        var tag = toggle.getAttribute('data-set-locale');
        if (tag) {
            record(tag);
        }
    });
})();
