/*
 * The three quiet reminders that the library takes support, all leading to /about#support:
 *
 *   - a bold "Support the library" under Contact in the footer of every page;
 *   - a note at the end of a chapter, at most once every 14 days;
 *   - a note after the reader copies a narration or its link, at most once every 30 days.
 *
 * rewayaat.info only (and a developer's machine). The Academy's mirror takes no money, and
 * the HTML has to be the same on every host so crawlers see one page, so nothing here is
 * in the markup: it is added by this script, on this host, after the page has loaded.
 *
 * A reader who has given (reached /about/thanks) sees none of them for 180 days. The About
 * page carries the ask itself, so it gets none either. Nothing here runs inside search
 * results, share cards, mail or the connector; those never load this file.
 *
 * Every remembered date lives in localStorage, which a private window or a strict browser
 * can refuse. Each read and write is guarded, and a refusal means "never shown", so the
 * worst case is a reminder shown slightly too often, never a broken page.
 */
(function () {
  var HOSTS = ['rewayaat.info', 'www.rewayaat.info', 'localhost', '127.0.0.1'];
  if (HOSTS.indexOf(location.hostname) < 0) { return; }

  var DAY = 864e5, now = Date.now();
  var GIVEN = 'rw.support.given', CHAPTER = 'rw.support.chapter', COPY = 'rw.support.copy';
  var prefix = window.I18N_LOCALE === 'ar' ? '/ar' : '';
  var path = location.pathname.replace(/^\/ar(?=\/|$)/, '') || '/';
  var ASK = prefix + '/about#support';

  function t(key, fallback) {
    var found = window.I18N && window.I18N[key];
    return found || fallback;
  }
  function remember(key) {
    try { localStorage.setItem(key, String(Date.now())); } catch (e) { /* not kept; see above */ }
  }
  function daysSince(key) {
    var at = 0;
    try { at = Number(localStorage.getItem(key)) || 0; } catch (e) { at = 0; }
    return at ? (now - at) / DAY : Infinity;
  }
  function el(tag, cls, text) {
    var node = document.createElement(tag);
    if (cls) { node.className = cls; }
    if (text) { node.textContent = text; }
    return node;
  }
  function askLink(cls, text) {
    var a = el('a', cls, text);
    a.href = ASK;
    return a;
  }

  if (path === '/about/thanks') { remember(GIVEN); return; }
  if (path === '/about' || daysSince(GIVEN) < 180) { return; }

  /* The last item under Contact in the footer, in bold. */
  function footerLine() {
    var columns = document.querySelectorAll('.site-footer .site-footer__grid .site-footer__links');
    var contact = columns[columns.length - 1];
    if (!contact || contact.querySelector('.support-footer__link')) { return; }
    contact.appendChild(askLink('support-footer__link', t('support.footer.link', 'Support the library')));
  }

  /* Counted as shown only once it is actually on screen, so a reader who never scrolls to
     the end of a chapter does not use up the fortnight. */
  function chapterNote() {
    var slot = document.querySelector('[data-support-slot="chapter"]');
    if (!slot || daysSince(CHAPTER) < 14) { return; }
    var note = el('aside', 'support-note');
    note.setAttribute('aria-label', t('support.footer.link', 'Support the library'));
    note.appendChild(el('span', 'support-note__mark', '۞'));
    note.appendChild(el('p', 'support-note__text', t('support.chapter.text', 'If this chapter was of benefit to you, help carry the light further.')));
    note.appendChild(askLink('support-note__link', t('support.footer.link', 'Support the library')));
    slot.appendChild(note);
    if (!('IntersectionObserver' in window)) { remember(CHAPTER); return; }
    var seen = new IntersectionObserver(function (entries) {
      if (entries.some(function (e) { return e.isIntersecting; })) { remember(CHAPTER); seen.disconnect(); }
    }, { threshold: 0.6 });
    seen.observe(note);
  }

  /*
   * After a copy. The site copies from a dozen buttons across three scripts, all through
   * the async clipboard, so the clipboard is wrapped once here rather than each button
   * being taught about this. Only on this host: elsewhere the clipboard is left alone.
   * A copy the reader makes by selecting text is theirs, and is not watched.
   */
  var toastOpen = false;
  function afterCopy() {
    if (toastOpen || daysSince(COPY) < 30) { return; }
    toastOpen = true;
    remember(COPY);
    // After the site's own "Copied" note, not on top of it.
    setTimeout(showCopyNote, 1600);
  }
  function showCopyNote() {
    var box = el('aside', 'support-toast');
    box.setAttribute('role', 'status');
    var close = el('button', 'support-toast__close', '×');
    close.type = 'button';
    close.setAttribute('aria-label', t('js.close', 'Close'));
    var quote = el('a', 'support-toast__quote', 'زَكاةُ العِلْمِ نَشْرُهُ');
    quote.href = prefix + '/hadith/Ghurar-al-Hikam-Amidi:6946';
    quote.lang = 'ar';
    quote.dir = 'rtl';
    box.appendChild(close);
    box.appendChild(quote);
    box.appendChild(el('p', 'support-toast__text', t('support.copy.text', 'Imam Ali (a.s.): the zakat of knowledge is spreading it. Thank you for sharing.')));
    box.appendChild(askLink('support-toast__link', t('support.copy.link', 'How the library is kept running')));
    document.body.appendChild(box);
    requestAnimationFrame(function () { box.classList.add('is-in'); });
    var timer = setTimeout(dismiss, 12000);
    function dismiss() {
      clearTimeout(timer);
      box.classList.remove('is-in');
      setTimeout(function () { box.remove(); toastOpen = false; }, 400);
    }
    close.addEventListener('click', dismiss);
    // Reading it should not race the timer.
    box.addEventListener('mouseenter', function () { clearTimeout(timer); });
    box.addEventListener('mouseleave', function () { timer = setTimeout(dismiss, 5000); });
  }
  var clip = navigator.clipboard;
  if (clip) {
    ['writeText', 'write'].forEach(function (name) {
      var original = clip[name];
      if (typeof original !== 'function') { return; }
      try {
        clip[name] = function () {
          var done = original.apply(clip, arguments);
          if (done && done.then) { done.then(afterCopy, function () { /* nothing was copied */ }); }
          return done;
        };
      } catch (e) { /* a browser that will not let it be wrapped simply shows no note */ }
    });
  }

  function start() { footerLine(); chapterNote(); }
  if (document.readyState === 'loading') { document.addEventListener('DOMContentLoaded', start); } else { start(); }
})();
