/*
 * A hand over the bookcase, in place of the pointer: a tasbih round the wrist, an ʿaqīq on
 * the ring finger. Resting, it follows the pointer, fingertip first; on a book it reaches
 * for the top of the cover and closes on it, and rides the book up as the shelf lifts it.
 *
 * Only for a mouse or trackpad (a fine pointer that hovers). A touch has no pointer to
 * replace, and the keyboard's focus ring is left as it is. The native pointer is hidden
 * only while the hand is drawn, so if this script never runs nothing changes. A reader who
 * asks for less motion gets the same two poses without the easing between them.
 *
 * The hand is one small fixed SVG moved with a transform, so following the pointer costs a
 * composite and nothing else.
 */
(function () {
  "use strict";
  var fine = false;
  try { fine = window.matchMedia("(hover: hover) and (pointer: fine)").matches; } catch (e) { fine = false; }
  if (!fine) { return; }
  var still = false;
  try { still = window.matchMedia("(prefers-reduced-motion: reduce)").matches; } catch (e) { still = false; }

  var SIZE = 118;                // rendered width, px; the drawing is 100 units wide
  var K = SIZE / 100;
  var TIP = { x: 51.5 * K, y: 126 * K };  // the middle fingertip, at rest: the hotspot
  // How far the middle fingertip draws back up when the fingers fold, less how far the
  // whole hand lowers onto the book: where the curled fingers end up.
  var GRIP_RISE = 24 * K;

  /* A tapering segment from y0 to y1 centred on x, w0 wide at the top and w1 at the end,
     rounded at the end. */
  function segment(x, y0, y1, w0, w1) {
    var r = w1 / 2;
    return "M" + (x - w0 / 2) + " " + y0 + "L" + (x - w1 / 2) + " " + (y1 - r)
      + "A" + r + " " + r + " 0 0 0 " + (x + w1 / 2) + " " + (y1 - r)
      + "L" + (x + w0 / 2) + " " + y0 + "Z";
  }

  /* One finger: hinged at the knuckle, and again at the middle joint, so a grip can fold
     each part by its own amount. */
  function finger(cls, x, top, w, len) {
    var p = Math.round(len * 0.52), joint = top + p, tipW = w * 0.8, midW = w * 0.9;
    return '<g class="hand__finger ' + cls + '" style="transform-origin:' + x + "px " + top + 'px">'
      + '<path class="hand__skin" d="' + segment(x, top - 3, joint + 3, w, midW) + '"/>'
      + '<g class="hand__tip" style="transform-origin:' + x + "px " + joint + 'px">'
      + '<path class="hand__skin" d="' + segment(x, joint - 1, top + len, midW, tipW) + '"/>'
      + '<path class="hand__nail" d="' + segment(x, top + len - 9, top + len - 1.6, tipW - 3.4, tipW - 3.6) + '"/>'
      + '<path class="hand__crease" d="M' + (x - midW / 2 + 1.8) + " " + (joint + (len - p) * 0.45) + "q" + (midW / 2 - 1.8) + " 1.6 " + (midW - 3.6) + ' 0"/>'
      + "</g>"
      + '<path class="hand__crease" d="M' + (x - midW / 2 + 1.6) + " " + joint + "q" + (midW / 2 - 1.6) + " 2 " + (midW - 3.2) + ' 0"/>'
      + "</g>";
  }

  /* The tasbih: beads across the back of the wrist, the strand behind it darker, and a
     tassel hanging from a gold bead on the near side. */
  function tasbih() {
    var beads = "", back = "";
    for (var i = 0; i < 10; i++) {
      var t = i / 9, x = 27 + t * 46, y = 31 + Math.sin(t * Math.PI) * 4.5;
      beads += '<circle class="hand__bead" cx="' + x.toFixed(1) + '" cy="' + y.toFixed(1) + '" r="3.1"/>'
        + '<circle class="hand__bead-shine" cx="' + (x - 1).toFixed(1) + '" cy="' + (y - 1.1).toFixed(1) + '" r=".95"/>';
    }
    for (var j = 0; j < 8; j++) {
      var u = j / 7, bx = 29 + u * 42, by = 25.5 - Math.sin(u * Math.PI) * 1.4;
      back += '<circle class="hand__bead hand__bead--back" cx="' + bx.toFixed(1) + '" cy="' + by.toFixed(1) + '" r="2.7"/>';
    }
    return '<g class="hand__tasbih-back">' + back + "</g>"
      + '<g class="hand__tasbih">' + beads
      + '<g class="hand__tassel" style="transform-origin:26px 33px">'
      + '<path class="hand__cord" d="M26 33 C23.5 41 22.5 47 22.5 53"/>'
      + '<circle class="hand__bead hand__bead--imam" cx="22.5" cy="55.5" r="3.4"/>'
      + '<path class="hand__fringe" d="M20 59 l-1.6 12 M21.6 59.5 l-.6 12.5 M23.2 59.5 l.4 12.5 M24.8 59 l1.4 12"/>'
      + "</g></g>";
  }

  var svg = ""
    + '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 150" width="' + SIZE + '" height="' + (150 * K) + '" aria-hidden="true">'
    + "<defs>"
    + '<radialGradient id="hand-aqiq" cx="40%" cy="35%" r="70%"><stop offset="0" stop-color="#f08a5d"/><stop offset=".45" stop-color="#c63b1f"/><stop offset="1" stop-color="#6e1408"/></radialGradient>'
    + '<linearGradient id="hand-skin" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#f2cba6"/><stop offset=".55" stop-color="#e3b087"/><stop offset="1" stop-color="#c98f62"/></linearGradient>'
    + '<radialGradient id="hand-bead" cx="35%" cy="30%" r="75%"><stop offset="0" stop-color="#5cc4b3"/><stop offset=".6" stop-color="#1d6e64"/><stop offset="1" stop-color="#0d3a35"/></radialGradient>'
    + '<radialGradient id="hand-gold" cx="35%" cy="30%" r="75%"><stop offset="0" stop-color="#ffe8a3"/><stop offset=".6" stop-color="#d4a83a"/><stop offset="1" stop-color="#7d5a1f"/></radialGradient>'
    + '<filter id="hand-soft" x="-40%" y="-40%" width="180%" height="180%"><feGaussianBlur stdDeviation="3.2"/></filter>'
    + "</defs>"
    + '<ellipse class="hand__shadow" cx="58" cy="96" rx="27" ry="36" filter="url(#hand-soft)"/>'
    + '<g class="hand__all">'
    // the thumb: the fleshy base along the side of the hand, then the thumb itself, angled
    // out and down; at rest it hangs clear, gripping it swings in under the fingers
    + '<g class="hand__thumb" style="transform-origin:68px 46px">'
    + '<path class="hand__skin" d="M62 40 C70 42 76 50 78 60 C79 66 76 70 72 69 C68 66 64 58 60 52 Z"/>'
    + '<g transform="rotate(-24 75 62)">'
    + '<path class="hand__skin" d="' + segment(75, 58, 80, 11, 10) + '"/>'
    + '<path class="hand__skin" d="' + segment(75, 78, 93, 10, 8.6) + '"/>'
    + '<path class="hand__nail" d="' + segment(75, 84.5, 91.6, 5.4, 5.2) + '"/>'
    + '<path class="hand__crease" d="M71 79 q4 2 8 0"/>'
    + "</g></g>"
    // the fingers, under the back of the hand so its knuckle line covers where they join
    + finger("hand__finger--pinky", 30, 73, 8.6, 35)
    + finger("hand__finger--ring", 40.5, 76, 9.6, 44)
    + finger("hand__finger--middle", 51.5, 77, 10, 49)
    + finger("hand__finger--index", 62, 75.5, 9.6, 44)
    // the sleeve and its gold cuff, then the back of the hand
    + '<path class="hand__sleeve" d="M22 0 H74 V20 C67 24.5 29 24.5 24 20 Z"/>'
    + '<path class="hand__cuff" d="M24 18.5 C30 23 67 23 74 18.5 V22.5 C67 27 30 27 24 22.5 Z"/>'
    + '<path class="hand__skin hand__back" d="M28 22 C28 34 25 50 24 64 C23.5 72 25 77 31 78 Q35.5 80.5 40.5 79.6 Q46 81.6 51.5 80.6 Q57 81.4 62 79.6 Q66.5 79 69 78 C74 77 75 73 74 66 C73 54 70 36 70 22 Z"/>'
    + '<path class="hand__tendon" d="M36 42 C35 56 33 66 32 74 M45 42 C44.5 56 43 66 42.5 76 M54 42 C54 56 53.5 66 53 77 M62 42 C62.5 54 63 64 63 75"/>'
    + tasbih()
    // the ʿaqīq on the ring finger: a silver band and the carnelian set on it
    + '<g class="hand__ring">'
    + '<rect class="hand__band" x="35.4" y="86" width="10.2" height="3.4" rx="1.5"/>'
    + '<ellipse cx="40.5" cy="87.6" rx="4.5" ry="3.5" class="hand__bezel"/>'
    + '<ellipse cx="40.5" cy="87.6" rx="3.5" ry="2.6" fill="url(#hand-aqiq)"/>'
    + '<ellipse cx="39.4" cy="86.7" rx="1.2" ry=".7" class="hand__glint"/>'
    + "</g>"
    // the knuckles, which only show once the fingers fold
    + '<g class="hand__knuckles"><path d="M26 76 q4 -3.5 8 0 M36 78.5 q4.5 -4 9 0 M47 79.5 q4.5 -4 9 0 M57.5 78 q4.5 -4 9 0"/></g>'
    + "</g></svg>";

  var hand = document.createElement("div");
  hand.className = "shelf-hand";
  hand.innerHTML = svg;
  document.body.appendChild(hand);

  var x = -200, y = -200, tx = x, ty = y, mx = 0, my = 0;
  var over = null, book = null, running = false, shown = false;

  function target() {
    if (book) {
      // the top edge of the cover, a little in from its centre: the fingers come over it
      var slot = book.querySelector(".shelf-book__slot") || book;
      var r = slot.getBoundingClientRect();
      tx = r.left + r.width * 0.5 - TIP.x;
      ty = r.top + 10 - (TIP.y - GRIP_RISE);
    } else {
      tx = mx - TIP.x;
      ty = my - TIP.y;
    }
  }

  function frame() {
    target();
    var ease = still ? 1 : (book ? 0.22 : 0.45);
    x += (tx - x) * ease;
    y += (ty - y) * ease;
    hand.style.transform = "translate3d(" + x.toFixed(1) + "px," + y.toFixed(1) + "px,0)";
    if (shown && (Math.abs(tx - x) > 0.3 || Math.abs(ty - y) > 0.3 || book)) {
      requestAnimationFrame(frame);
    } else {
      running = false;
    }
  }
  function kick() {
    if (!running) { running = true; requestAnimationFrame(frame); }
  }

  function show(on, shelf) {
    if (on === shown) { return; }
    shown = on;
    hand.classList.toggle("is-shown", on);
    if (shelf) { shelf.classList.toggle("has-hand", on); }
    if (on) {
      // arrive at the pointer, not slide in from the last place it was hidden
      target(); x = tx; y = ty;
      kick();
    }
  }

  document.addEventListener("pointermove", function (event) {
    if (event.pointerType && event.pointerType !== "mouse" && event.pointerType !== "pen") { return; }
    mx = event.clientX; my = event.clientY;
    var shelf = event.target.closest ? event.target.closest(".bookcase") : null;
    if (shelf !== over) {
      if (over) { over.classList.remove("has-hand"); }
      over = shelf;
    }
    show(!!shelf, shelf);
    var nextBook = shelf ? event.target.closest(".shelf-book") : null;
    if (nextBook !== book) {
      book = nextBook;
      hand.classList.toggle("is-gripping", !!book);
    }
    if (shown) { kick(); }
  }, { passive: true });

  document.addEventListener("pointerleave", function () { show(false, over); }, { passive: true });
  window.addEventListener("blur", function () { show(false, over); });
  // a scroll moves the books under a still pointer
  window.addEventListener("scroll", function () { if (shown) { kick(); } }, { passive: true });
})();
