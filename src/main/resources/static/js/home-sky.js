/*
 * The night sky behind the home page, the same sky as the About page's: a fixed layer of
 * stars the page scrolls over, a few of them shimmering, a rare falling star.
 *
 * Only while the home content is on screen. index.html also shows search results, and
 * those keep the light reading background, so the canvas hides itself (CSS, from the same
 * :has() test the colours use) and stops drawing until the home content comes back.
 *
 * Cheap by construction: the still stars and the haze are painted once into an offscreen
 * layer, and each frame copies it and redraws only the one star in ten that shimmers, at
 * about 30 frames a second. A reader who asks for less motion gets the still sky.
 */
(function () {
  var canvas = document.createElement('canvas');
  canvas.className = 'home-stars';
  canvas.setAttribute('aria-hidden', 'true');
  document.body.insertBefore(canvas, document.body.firstChild);
  var g = canvas.getContext('2d');
  if (!g) { return; }

  var still = false;
  try { still = window.matchMedia('(prefers-reduced-motion: reduce)').matches; } catch (e) { still = false; }

  var w = 0, h = 0, dpr = 1, base = null, shimmer = [], halo = null, seed = 11, last = 0, fall = null, nextFall = 0;
  function rnd() { seed = (seed * 16807) % 2147483647; return (seed - 1) / 2147483646; }
  function onHome() { return !!document.querySelector('#welcome .home-hero'); }

  function sprite(rgb) {
    var s = document.createElement('canvas'), q = s.getContext('2d'), r = 32;
    s.width = s.height = r * 2;
    var grad = q.createRadialGradient(r, r, 0, r, r, r);
    grad.addColorStop(0, 'rgba(' + rgb + ',.9)');
    grad.addColorStop(0.18, 'rgba(' + rgb + ',.35)');
    grad.addColorStop(1, 'rgba(' + rgb + ',0)');
    q.fillStyle = grad; q.fillRect(0, 0, r * 2, r * 2);
    return s;
  }

  var TINTS = ['255,255,255', '214,228,255', '255,236,196'];

  function build() {
    w = window.innerWidth; h = window.innerHeight;
    dpr = Math.min(window.devicePixelRatio || 1, 2);
    canvas.width = Math.round(w * dpr); canvas.height = Math.round(h * dpr);
    base = document.createElement('canvas'); base.width = canvas.width; base.height = canvas.height;
    var b = base.getContext('2d');
    b.scale(dpr, dpr);
    halo = halo || sprite('255,226,160');

    // Haze: a warm lamp high in the middle, cool drifts at the sides. Faint on purpose.
    [[0.5, 0.18, 0.55, '233,196,106', 0.10], [0.12, 0.55, 0.45, '90,130,210', 0.07],
     [0.9, 0.42, 0.4, '120,110,200', 0.05], [0.55, 0.95, 0.6, '40,80,160', 0.08]].forEach(function (n) {
      var r = Math.max(w, h) * n[2], grad = b.createRadialGradient(w * n[0], h * n[1], 0, w * n[0], h * n[1], r);
      grad.addColorStop(0, 'rgba(' + n[3] + ',' + n[4] + ')');
      grad.addColorStop(1, 'rgba(' + n[3] + ',0)');
      b.fillStyle = grad; b.fillRect(0, 0, w, h);
    });

    seed = 11; shimmer = [];
    var count = Math.min(1100, Math.round(w * h / 1300));
    for (var i = 0; i < count; i++) {
      var star = {
        x: rnd() * w, y: rnd() * h,
        r: 0.35 + Math.pow(rnd(), 3) * 1.25,
        a: 0.18 + rnd() * 0.6,
        tint: TINTS[i % 3],
        ph: rnd() * 6.283, sp: 0.6 + rnd() * 1.6, sp2: 2.3 + rnd() * 2.1,
        big: rnd() < 0.05
      };
      if (star.big) { star.r += 0.5; star.a = Math.min(1, star.a + 0.25); }
      if (rnd() < 0.1 || star.big) { shimmer.push(star); continue; }
      b.globalAlpha = star.a;
      b.fillStyle = 'rgb(' + star.tint + ')';
      b.beginPath(); b.arc(star.x, star.y, star.r, 0, 6.283); b.fill();
    }
    b.globalAlpha = 1;
    paint(performance.now());
  }

  function paint(t) {
    g.setTransform(1, 0, 0, 1, 0, 0);
    g.clearRect(0, 0, canvas.width, canvas.height);
    g.drawImage(base, 0, 0);
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    var s = t / 1000;
    for (var i = 0; i < shimmer.length; i++) {
      var st = shimmer[i];
      // Two waves at unrelated speeds read as the air moving, not as a pulse.
      var k = still ? 0.7 : 0.5 + 0.32 * Math.sin(s * st.sp + st.ph) + 0.18 * Math.sin(s * st.sp2 + st.ph * 1.7);
      var a = st.a * (0.25 + 0.75 * k * k);
      g.globalAlpha = a;
      g.fillStyle = 'rgb(' + st.tint + ')';
      g.beginPath(); g.arc(st.x, st.y, st.r, 0, 6.283); g.fill();
      if (st.big) {
        var size = (6 + st.r * 7) * (0.6 + 0.5 * k);
        g.globalAlpha = a * 0.55;
        g.drawImage(halo, st.x - size / 2, st.y - size / 2, size, size);
      }
    }
    if (!still) { falling(t); }
    g.globalAlpha = 1;
  }

  /* Now and then a falling star, thin and quick, in the upper half. */
  function falling(t) {
    if (!fall) {
      if (!nextFall) { nextFall = t + 6000 + Math.random() * 8000; }
      if (t < nextFall) { return; }
      var fromLeft = Math.random() < 0.5;
      fall = { t0: t, x: w * (0.15 + Math.random() * 0.7), y: h * (0.05 + Math.random() * 0.3),
               dx: (fromLeft ? 1 : -1) * (0.55 + Math.random() * 0.25), dy: 0.32 + Math.random() * 0.12, len: 120 + Math.random() * 90 };
    }
    var p = (t - fall.t0) / 950;
    if (p >= 1) { fall = null; nextFall = t + 12000 + Math.random() * 14000; return; }
    var travel = 260 * p, hx = fall.x + fall.dx * travel, hy = fall.y + fall.dy * travel;
    var tail = fall.len * Math.min(1, p * 3), tx = hx - fall.dx * tail, ty = hy - fall.dy * tail;
    var fade = p < 0.2 ? p / 0.2 : 1 - (p - 0.2) / 0.8;
    var grad = g.createLinearGradient(tx, ty, hx, hy);
    grad.addColorStop(0, 'rgba(255,236,196,0)');
    grad.addColorStop(1, 'rgba(255,246,226,' + (0.85 * fade) + ')');
    g.globalAlpha = 1;
    g.strokeStyle = grad; g.lineWidth = 1.3; g.lineCap = 'round';
    g.beginPath(); g.moveTo(tx, ty); g.lineTo(hx, hy); g.stroke();
    g.globalAlpha = 0.7 * fade;
    g.drawImage(halo, hx - 7, hy - 7, 14, 14);
  }

  function loop(t) {
    if (!onHome() || document.hidden) {
      setTimeout(function () { requestAnimationFrame(loop); }, 800);
      return;
    }
    if (t - last > 32) { last = t; paint(t); }
    requestAnimationFrame(loop);
  }

  var resizeTimer = 0, builtW = 0, builtH = 0;
  window.addEventListener('resize', function () {
    clearTimeout(resizeTimer);
    resizeTimer = setTimeout(function () {
      // A phone's address bar showing and hiding changes the height a little on every
      // scroll; rebuilding for that would reshuffle the sky under the reader.
      if (window.innerWidth === builtW && Math.abs(window.innerHeight - builtH) < 140) { return; }
      builtW = window.innerWidth; builtH = window.innerHeight; build();
    }, 150);
  });

  builtW = window.innerWidth; builtH = window.innerHeight;
  build();
  if (!still) { requestAnimationFrame(loop); }
})();
