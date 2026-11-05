/*
 * The giving form on the About page. Shown on rewayaat.info only (and on a developer's
 * machine); the Academy's mirror takes no money, and neither does any other host. The
 * HTML is the same everywhere so crawlers see one page, and /about/checkout refuses
 * other hosts too. A gift is posted
 * to /about/checkout, which answers with the Stripe Checkout page to go to.
 */
(function () {
  var band = document.querySelector('[data-support]');
  var GIVING_HOSTS = ['rewayaat.info', 'www.rewayaat.info', 'localhost', '127.0.0.1'];
  if (!band || GIVING_HOSTS.indexOf(location.hostname) < 0) { return; }
  band.hidden = false;

  var form = band.querySelector('[data-give]'), box = form.querySelector('[data-amounts]');
  var other = form.querySelector('[data-other]'), otherInput = form.querySelector('[data-other-input]');
  var go = form.querySelector('[data-go]'), msg = form.querySelector('[data-msg]');
  var seg = [].slice.call(form.querySelectorAll('[data-interval]'));
  var SETS = { month: [5, 10, 25, 50, 'other'], once: [25, 50, 100, 250, 'other'] };
  var DEFAULT = { month: 10, once: 50 };
  var mode = 'month', amount = DEFAULT.month;

  function text(name) { return form.getAttribute('data-label-' + name) || ''; }
  function money(n) { return '$' + Number(n).toLocaleString('en-US', { maximumFractionDigits: 2 }); }
  function chosen() { return amount === 'other' ? parseFloat(otherInput.value) : amount; }
  function label() {
    var n = chosen();
    var shown = isFinite(n) && n > 0 ? money(n) : '';
    return text(mode === 'month' ? 'month' : 'once').replace('{0}', shown).replace(/\s+/g, ' ').trim();
  }
  function draw() {
    seg.forEach(function (b) { b.setAttribute('aria-pressed', String(b.getAttribute('data-interval') === mode)); });
    box.textContent = '';
    SETS[mode].forEach(function (a) {
      var b = document.createElement('button');
      b.type = 'button';
      b.textContent = a === 'other' ? text('other') : money(a);
      if (a === 'other') { b.className = 'is-other'; }
      b.setAttribute('aria-pressed', String(a === amount));
      b.addEventListener('click', function () {
        amount = a; draw();
        if (a === 'other') { otherInput.focus(); }
      });
      box.appendChild(b);
    });
    other.hidden = amount !== 'other';
    go.textContent = label();
  }
  seg.forEach(function (b) {
    b.addEventListener('click', function () { mode = b.getAttribute('data-interval'); amount = DEFAULT[mode]; draw(); });
  });
  otherInput.addEventListener('input', function () { go.textContent = label(); msg.textContent = ''; });

  form.addEventListener('submit', function (e) {
    e.preventDefault();
    var n = chosen();
    if (!(n >= 5 && n <= 10000)) { msg.textContent = text('amount'); otherInput.focus(); return; }
    go.disabled = true; msg.textContent = text('busy');
    fetch(form.getAttribute('data-checkout'), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        interval: mode, amount: n,
        locale: document.documentElement.lang
      })
    }).then(function (r) {
      return r.json().catch(function () { return {}; }).then(function (body) { return { status: r.status, body: body }; });
    }).then(function (res) {
      if (res.body.url) { location.href = res.body.url; return; }
      go.disabled = false;
      msg.textContent = res.body.error === 'closed' ? text('closed')
        : res.body.error === 'amount' ? text('amount') : text('error');
    }).catch(function () { go.disabled = false; msg.textContent = text('error'); });
  });
  draw();
}());
