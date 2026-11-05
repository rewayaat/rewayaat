/* The sky: night, a dark Earth, and books that open in turn, each lighting the globe as its words rise to the countries they reach. */
(function () {
  /* Gold vowel marks: a copy of the text in a font where only the marks have ink, laid exactly over it. */
  [].forEach.call(document.querySelectorAll('.q-ar, .m-quote .ar'), function (el) {
    el.insertAdjacentHTML('beforeend', '<span class="mk" aria-hidden="true">' + el.innerHTML + '</span>');
  });
  var sky = document.getElementById('sky'), c = document.getElementById('sky-c');
  if (!c || !c.getContext) { return; }
  var g = c.getContext('2d'), lib = sky.querySelector('.lib'), text = sky.querySelector('.lib-text');
  var books = [].slice.call(sky.querySelectorAll('.book'));
  var still = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  var TURN = 0.0035, TINTS = ['#fff6e0', '#dfe9ff', '#ffe2a6'], RAD = Math.PI / 180;
  var TILT = -5 * RAD, SPIN = 0.055, FACE = -46 * RAD, EVERY = 2.4, LIFE = 3.9, REACH = 1.8, PERIOD = 15, EMBER = .3;
  var w = 0, h = 0, dpr = 1, t = 0, last = 0, on = true, seed = 7;
  var stars = [], neb = null, glow = null, warm = null, mx = 0, my = 0;
  var cx = 0, cy = 0, R = 0, beams = [], wait = .4, order = [], next = 0, turn = 0, glyphs = {}, nameFont = document.documentElement.lang === 'ar' ? '600 40px "Noto Naskh Arabic", "Scheherazade New", serif' : '600 40px Raleway, sans-serif', sparks = [], turned = 0, boost = 1, low = 120, lights = new Float32Array(16), ring = null;
  /* Mock data, not readers: for launch, the real reader countries from GA4. */
  var ARABIC = document.documentElement.lang === 'ar';
  var PLACES = window.PLACES.map(function (p) { return { name: ARABIC ? (window.PLACES_AR || {})[p[0]] || p[0] : p[0].toUpperCase(), lat: p[1], lon: p[2], v: vec([p[1], p[2]]), size: p[3] }; });
  var since = PLACES.map(function () { return 99; }), gl = null, glc = null, maps = null, flat = null, flatImg = null, flatTick = 0, U = {}, ready = 0, pack = new Float32Array(PLACES.length * 4);
  /* On the Arabic page names are drawn whole, so their letters join; Latin names go letter by letter round the sphere. */
  var FONT = getComputedStyle(document.documentElement).getPropertyValue('--display') || 'Georgia, serif';
  function rnd() { seed = (seed * 16807) % 2147483647; return seed / 2147483647; }
  function clamp(v) { return Math.max(0, Math.min(1, v)); }
  function ease(u) { u = clamp(u); return u * u * (3 - 2 * u); }
  /* How bright a country is: it swells when the light lands, then settles back to an ember. */
  function heat(age) {
    if (age < 0) { return EMBER; }
    if (age < .5) { return EMBER + (1 - EMBER) * ease(age / .5); }
    if (age < 1.1) { return 1; }
    return EMBER + (1 - EMBER) * (1 - ease((age - 1.1) / 3));
  }
  function sprite() {
    var s = document.createElement('canvas'); s.width = s.height = 32;
    var q = s.getContext('2d'), r = q.createRadialGradient(16, 16, 0, 16, 16, 16);
    r.addColorStop(0, 'rgba(255,250,235,1)'); r.addColorStop(.18, 'rgba(255,232,180,.75)');
    r.addColorStop(.5, 'rgba(255,215,150,.14)'); r.addColorStop(1, 'rgba(255,215,150,0)');
    q.fillStyle = r; q.fillRect(0, 0, 32, 32);
    return s;
  }
  function lampSprite() {
    var s = document.createElement('canvas'); s.width = s.height = 160;
    var q = s.getContext('2d'), r = q.createRadialGradient(80, 80, 2, 80, 80, 80);
    r.addColorStop(0, 'rgba(255,190,100,.26)'); r.addColorStop(.42, 'rgba(255,160,70,.06)'); r.addColorStop(1, 'rgba(255,150,60,0)');
    q.fillStyle = r; q.fillRect(0, 0, 160, 160);
    return s;
  }
  function nebula() {
    var s = document.createElement('canvas'), f = 4;
    s.width = Math.ceil(w / f); s.height = Math.ceil(h / f);
    var q = s.getContext('2d');
    [[.2, .16, .5, 'rgba(70,120,190,.2)'], [.82, .32, .46, 'rgba(100,80,170,.15)'], [.14, .82, .42, 'rgba(50,110,170,.16)'], [.86, .86, .4, 'rgba(120,90,170,.12)']].forEach(function (b) {
      var x = b[0] * s.width, y = b[1] * s.height, r = b[2] * s.width;
      var d = q.createRadialGradient(x, y, 0, x, y, r);
      d.addColorStop(0, b[3]); d.addColorStop(1, 'rgba(0,0,0,0)');
      q.fillStyle = d; q.fillRect(0, 0, s.width, s.height);
    });
    return s;
  }
  /* The globe is drawn by the graphics chip: a sphere lit from above, with the baked map wrapped around it. */
  var VERT = '#version 300 es\nout vec2 vP;void main(){vec2 p=vec2((gl_VertexID<<1)&2,gl_VertexID&2);vP=p*2.-1.;gl_Position=vec4(vP,0,1);}';
  var FRAG = ['#version 300 es', 'precision highp float;', 'in vec2 vP;out vec4 o;',
    'uniform sampler2D uTex,uId;uniform mat3 uRot;uniform float uPx;uniform vec4 uLb[4];uniform vec4 uC[' + PLACES.length + '];uniform float uS[' + PLACES.length + '];',
    'float ease(float u){u=clamp(u,0.,1.);return u*u*(3.-2.*u);}',
    'float flare(float a){if(a<0.)return 0.;if(a<.5)return ease(a/.5);if(a<1.1)return 1.;return 1.-ease((a-1.1)/3.);}',
    'void main(){float r=length(vP);if(r>1.)discard;float edge=1.-smoothstep(1.-uPx,1.,r);',
    'vec3 N=vec3(vP,sqrt(max(0.,1.-r*r)));vec3 M=uRot*N;float lat=asin(clamp(M.y,-1.,1.)),lon=atan(M.x,M.z);',
    'vec2 uv=vec2(lon/6.2831853+.5,.5-lat/3.1415927),dx=dFdx(uv),dy=dFdy(uv);',
    'if(abs(dx.x)>.5)dx.x-=sign(dx.x);if(abs(dy.x)>.5)dy.x-=sign(dy.x);',
    'vec3 T=textureGrad(uTex,uv,dx,dy).rgb;int id=int(texture(uId,uv).r*255.+.5)-1;',
    'vec3 L=normalize(vec3(-.5,.62,.6));float d=max(dot(N,L),0.),li=.42+.78*d;',
    'float lit=0.;for(int i=0;i<4;i++)lit+=pow(max(dot(N,uLb[i].xyz)*.8+.2,0.),1.7)*uLb[i].w;lit=min(lit,1.1);',
    'vec3 sea=mix(vec3(.005,.013,.032),vec3(.012,.032,.08),d)+vec3(.15,.095,.035)*lit,ground=mix(vec3(.034,.062,.112),vec3(.072,.125,.205),d)+vec3(.3,.21,.09)*lit,col=mix(sea,ground,T.r);',
    'float w=max(length(vec2(dx.x,dy.x)),length(vec2(dx.y,dy.y)))*24.,la=(.5-uv.y)*12.,lo=uv.x*24.;',
    'float gr=1.-clamp(min(abs(fract(la+.5)-.5),abs(fract(lo+.5)-.5))/max(w,1e-4),0.,1.);',
    'col+=vec3(.78,.62,.32)*gr*.035*(li+lit)*(1.-T.r);',
    'col+=vec3(.3,.46,.72)*T.b*.09*(1.-T.r);float f=0.,gild=0.;',
    'if(id>=0&&id<' + PLACES.length + '){float ang=acos(clamp(dot(M,uC[id].xyz),-1.,1.)),u=ang/uS[id]*.55,a=uC[id].w;',
    'gild=flare(a*2.6-u*1.4);f=flare(a-.3-u-(1.-T.b)*.5);',
    'col=mix(col,vec3(1.,.8,.42)*(.74+.5*lit+.3*d),pow(f,1.7)*.94*T.r);}',
    'col+=vec3(.98,.82,.52)*T.g*(.58+.34*li+1.05*lit+1.4*max(gild,f));',
    'vec3 H=normalize(L+vec3(0,0,1));col+=vec3(.5,.62,.85)*pow(max(dot(N,H),0.),46.)*.05*(1.-T.r);',
    'col*=mix(.45,1.,smoothstep(0.,.55,N.z));col+=vec3(.3,.5,.95)*pow(1.-N.z,3.4)*.2;',
    'o=vec4(col*edge,edge);}'].join('\n');
  function texture(unit, src, smooth) {
    var img = new Image();
    img.onload = function () {
      gl.activeTexture(gl.TEXTURE0 + unit);
      gl.bindTexture(gl.TEXTURE_2D, gl.createTexture());
      gl.pixelStorei(gl.UNPACK_COLORSPACE_CONVERSION_WEBGL, gl.NONE);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, img);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.REPEAT);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, smooth ? gl.LINEAR : gl.NEAREST);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, smooth ? gl.LINEAR_MIPMAP_LINEAR : gl.NEAREST);
      if (smooth) {
        gl.generateMipmap(gl.TEXTURE_2D);
        var af = gl.getExtension('EXT_texture_filter_anisotropic');
        if (af) { gl.texParameterf(gl.TEXTURE_2D, af.TEXTURE_MAX_ANISOTROPY_EXT, 8); }
      }
      ready++;
      if (still) { paint(0); }
    };
    img.src = src;
  }
  /* An open book burns like a lamp: never quite steady. */
  function flame(n) { return .92 + .08 * Math.sin(t * 2.1 + n * 1.3) * Math.sin(t * 3.7 + n); }
  function setup() {
    ready = 0;
    var pr = gl.createProgram();
    [[gl.VERTEX_SHADER, VERT], [gl.FRAGMENT_SHADER, FRAG]].forEach(function (s) {
      var sh = gl.createShader(s[0]); gl.shaderSource(sh, s[1]); gl.compileShader(sh);
      if (!gl.getShaderParameter(sh, gl.COMPILE_STATUS)) { throw new Error(gl.getShaderInfoLog(sh)); }
      gl.attachShader(pr, sh);
    });
    gl.linkProgram(pr); gl.useProgram(pr);
    gl.bindVertexArray(gl.createVertexArray());
    ['uRot', 'uPx', 'uLb', 'uC', 'uS'].forEach(function (n) { U[n] = gl.getUniformLocation(pr, n); });
    gl.uniform1i(gl.getUniformLocation(pr, 'uTex'), 0); gl.uniform1i(gl.getUniformLocation(pr, 'uId'), 1);
    gl.uniform1fv(U.uS, new Float32Array(PLACES.map(function (o) { return o.size * RAD * 1.5; })));
    PLACES.forEach(function (o, k) { pack.set(o.v, k * 4); });
    texture(0, sky.getAttribute("data-earth"), true); texture(1, sky.getAttribute("data-countries"), false);
  }
  /* The graphics chip can take the globe away at any time, so recover, and fall back to drawing it by hand. */
  function globe() {
    flatMaps();
    glc = document.createElement('canvas');
    glc.addEventListener('webglcontextlost', function (e) { e.preventDefault(); ready = 0; });
    glc.addEventListener('webglcontextrestored', setup);
    gl = glc.getContext('webgl2', { alpha: true, premultipliedAlpha: true, antialias: false });
    if (!gl) { return; }
    try { setup(); } catch (e) { gl = null; }
  }
  function flatMaps() {
    var out = {}, W = 720, H = 360;
    [['e', sky.getAttribute("data-earth"), true], ['i', sky.getAttribute("data-countries"), false]].forEach(function (m) {
      var img = new Image();
      img.onload = function () {
        var s = document.createElement('canvas'); s.width = W; s.height = H;
        var q = s.getContext('2d', { willReadFrequently: true });
        q.imageSmoothingEnabled = m[2]; q.drawImage(img, 0, 0, W, H);
        out[m[0]] = q.getImageData(0, 0, W, H).data;
        if (out.e && out.i) { maps = out; }
      };
      img.src = m[1];
    });
  }
  /* The backup globe, drawn pixel by pixel on the main processor. */
  function paintFlat(rot) {
    var N = Math.min(Math.round(R * 2), 340);
    if (!flat || flat.width !== N) { flat = document.createElement('canvas'); flat.width = flat.height = N; flatImg = null; }
    if (!flatImg) { flatImg = flat.getContext('2d').createImageData(N, N); }
    if (flatTick++ % 2 === 0) {
      var d = flatImg.data, cr = Math.cos(rot), sr = Math.sin(rot), ct = Math.cos(TILT), st = Math.sin(TILT), last = Math.min(N, Math.ceil((h - (cy - R)) / (R * 2) * N)), e = maps.e, ids = maps.i;
      for (var j = 0; j < last; j++) {
        var y = 1 - (j + .5) / N * 2;
        for (var i = 0; i < N; i++) {
          var x = (i + .5) / N * 2 - 1, r2 = x * x + y * y, o = (j * N + i) * 4;
          if (r2 >= 1) { d[o + 3] = 0; continue; }
          var z = Math.sqrt(1 - r2), z0 = -st * y + ct * z, mx = x * cr - z0 * sr, my = ct * y + st * z, mz = x * sr + z0 * cr;
          var u = Math.atan2(mx, mz) / 6.2832 + .5, v = .5 - Math.asin(Math.max(-1, Math.min(1, my))) / 3.1416, k = (Math.min(359, v * 360 | 0) * 720 + Math.min(719, u * 720 | 0)) * 4;
          var land = e[k] / 255, line = Math.min(1, (e[k + 1] * 1.6 + e[k + 2]) / 255), id = ids[k] - 1, lit = id >= 0 ? (heat(since[id]) - EMBER) / (1 - EMBER) : 0, shade = .55 + .45 * z;
          var rr = (3 + land * 22) + lit * land * 225, gg = (9 + land * 36) + lit * land * 175, bb = (22 + land * 58) + lit * land * 45;
          d[o] = (rr + line * 170) * shade; d[o + 1] = (gg + line * 140) * shade; d[o + 2] = (bb + line * 90) * shade; d[o + 3] = 255;
        }
      }
      flat.getContext('2d').putImageData(flatImg, 0, 0);
    }
    g.save(); g.beginPath(); g.arc(cx, cy, R, 0, 6.284); g.clip();
    g.imageSmoothingEnabled = true; g.drawImage(flat, cx - R, cy - R, R * 2, R * 2);
    g.restore();
  }
  function paintGlobe(rot) {
    var scale = Math.min(dpr, 1.5), S = Math.round(R * 2 * scale);
    if (!S) { return; }
    if (glc.width !== S) { glc.width = glc.height = S; }
    var cr = Math.cos(rot), sr = Math.sin(rot), ct = Math.cos(TILT), st = Math.sin(TILT);
    function back(x, y1, z1) { var y = ct * y1 + st * z1, z0 = -st * y1 + ct * z1; return [x * cr - z0 * sr, y, x * sr + z0 * cr]; }
    var vis = Math.min(S, Math.ceil((h - (cy - R)) * scale));
    gl.viewport(0, 0, S, S); gl.enable(gl.SCISSOR_TEST); gl.scissor(0, S - vis, S, vis);
    gl.clearColor(0, 0, 0, 0); gl.clear(gl.COLOR_BUFFER_BIT);
    gl.uniformMatrix3fv(U.uRot, false, new Float32Array(back(1, 0, 0).concat(back(0, 1, 0), back(0, 0, 1))));
    gl.uniform1f(U.uPx, 3 / S);
    lights.fill(0);
    books.filter(function (b) { return b.dir && b.o > .02; }).sort(function (a, b) { return b.o - a.o; }).slice(0, 4).forEach(function (b, n) {
      lights.set([b.dir[0], b.dir[1], b.dir[2], b.o * flame(n) * .16], n * 4);
    });
    gl.uniform4fv(U.uLb, lights);
    since.forEach(function (a, k) { pack[k * 4 + 3] = a; });
    gl.uniform4fv(U.uC, pack);
    gl.drawArrays(gl.TRIANGLES, 0, 3);
    g.drawImage(glc, cx - R, cy - R, R * 2, R * 2);
  }
  function place() {
    /* The Earth rises below the numbers, cut off by the end of the sky. */
    R = w < 900 ? Math.min(w * .44, 270) : Math.min(w * .265, 300);
    var line = sky.querySelector('.reach');
    low = line.offsetHeight + (w < 760 ? 88 : 40);
    lib.style.paddingBottom = Math.round(R * .95 + low) + 'px';
    cx = w / 2;
    cy = lib.offsetTop + text.offsetTop + text.offsetHeight + R + (w < 900 ? 56 : 44);
    rim();
  }
  /* The library's numbers run around the rim of the globe, like the lettering on a coin. Phones take two arcs. */
  function rim() {
    /*
     * Each figure is set as one straight piece of text, turned to sit on the arc, rather than
     * as text along a path: a path lays glyphs out one by one, which pulled Latin letters apart
     * and broke Arabic joining. Figures run right to left on the Arabic page.
     */
    var svg = document.getElementById('rim'), small = w < 900, NS = 'http://www.w3.org/2000/svg';
    var items = [].map.call(sky.querySelectorAll('.nums div'), function (d) { return [d.querySelector('b').textContent, d.querySelector('span').textContent]; });
    var rows = small ? [[items.slice(3), R + 34], [items.slice(0, 3), R + 13]] : [[items, R + 18]];
    svg.setAttribute('class', small ? 'small' : '');
    svg.textContent = '';
    rows.forEach(function (row) {
      var r = row[1], gap = small ? 16 : 26, units = row[0].map(function (it) {
        var el = document.createElementNS(NS, 'text');
        el.setAttribute('text-anchor', 'middle');
        if (ARABIC) { el.setAttribute('direction', 'rtl'); }
        var n = document.createElementNS(NS, 'tspan'); n.setAttribute('class', 'n'); n.textContent = it[0];
        var l = document.createElementNS(NS, 'tspan'); l.setAttribute('class', 'l'); l.textContent = ' ' + it[1];
        el.appendChild(n); el.appendChild(l); svg.appendChild(el);
        return { el: el, wd: el.getComputedTextLength() };
      });
      if (ARABIC) { units.reverse(); }
      var total = units.reduce(function (s, u) { return s + u.wd; }, 0) + gap * (units.length - 1), at = -total / 2;
      units.forEach(function (u, k) {
        var mid = (at + u.wd / 2) / r, deg = mid / RAD;
        u.el.setAttribute('transform', 'translate(' + (cx + Math.sin(mid) * r) + ' ' + (cy - Math.cos(mid) * r) + ') rotate(' + deg + ')');
        at += u.wd;
        if (k < units.length - 1) {
          var dot = document.createElementNS(NS, 'circle'), dm = (at + gap / 2) / r, dr = r - (small ? 4 : 6);
          dot.setAttribute('cx', cx + Math.sin(dm) * dr); dot.setAttribute('cy', cy - Math.cos(dm) * dr); dot.setAttribute('r', small ? 1.4 : 2);
          dot.setAttribute('class', 's'); svg.appendChild(dot);
          at += gap;
        }
      });
    });
  }
  /* One letter, drawn large once with a dark halo, so names stay sharp and cheap as they turn. */
  function glyph(ch, hot) {
    var key = ch + (hot ? 1 : 0);
    if (glyphs[key]) { return glyphs[key]; }
    var s = document.createElement('canvas'), q = s.getContext('2d'), font = nameFont;
    q.font = font;
    s.width = Math.max(72, Math.ceil(q.measureText(ch).width) + 28); s.height = 72;
    q.font = font; q.textAlign = 'center'; q.textBaseline = 'middle'; q.direction = ARABIC ? 'rtl' : 'ltr';
    q.shadowColor = hot ? 'rgba(60,34,4,.55)' : 'rgba(3,8,20,.95)'; q.shadowBlur = hot ? 5 : 10;
    q.fillStyle = hot ? '#fff3cf' : '#ecd9a8';
    q.fillText(ch, s.width / 2, 36); q.fillText(ch, s.width / 2, 36);
    glyphs[key] = { c: s, adv: q.measureText(ch).width + 8 };
    return glyphs[key];
  }
  /* Names sit on the surface. The largest countries keep theirs always; a name that would crowd one shows only in flare. */
  function labels() {
    var taken = [], base = R < 220 ? 7.6 : 8.8;
    PLACES.slice().sort(function (a, b) { return b.size - a.size; }).forEach(function (p) {
      var fs = base + Math.min(R < 220 ? 2 : 3, p.size / 7), k = fs / 40, x = 0, c = Math.max(.3, Math.cos(p.lat * RAD));
      p.fs = k;
      p.letters = (ARABIC ? [p.name] : p.name.split('')).map(function (ch) {
        var a = ch === ' ' ? 16 : glyph(ch).adv, m = { ch: ch, x: x + a / 2 };
        x += a; return m;
      });
      p.letters.forEach(function (m) { m.x = (m.x - x / 2) * k / (R * c) / RAD; });
      var wide = x / 2 * k / (R * c) / RAD + 1.2, tall = fs * .95 / R / RAD;
      var box = [p.lon - wide, p.lat - tall, p.lon + wide, p.lat + tall];
      var under = taken.filter(function (o) { return box[0] < o.box[2] && box[2] > o.box[0] && box[1] < o.box[3] && box[3] > o.box[1]; });
      p.always = !under.length; p.box = box; p.quiet = [];
      if (p.always) { taken.push(p); }
      else { under.forEach(function (o) { o.quiet.push(p); }); }
    });
  }
  function build() {
    w = c.clientWidth; place();
    if (c.clientHeight === h && c.width === Math.round(w * dpr)) { return; }
    h = c.clientHeight;
    if (!w || !h) { return; }
    dpr = Math.min(window.devicePixelRatio || 1, 2);
    c.width = w * dpr; c.height = h * dpr;
    seed = 7; stars = [];
    var S = Math.hypot(w, h) * .72, n = Math.min(1900, Math.round(w * h / 620));
    for (var i = 0; i < n; i++) {
      var big = rnd() < .045;
      stars.push({ r: Math.sqrt(rnd()) * S, a: rnd() * 6.283, d: [.7, .85, 1][i % 3], big: big,
        z: big ? 3 + rnd() * 4 : .7 + rnd() * .9, b: big ? .5 + rnd() * .35 : .16 + rnd() * .6,
        tw: big ? .3 + rnd() * .6 : .4 + rnd() * 1.3, ph: rnd() * 6.283, glint: rnd() < .09, col: TINTS[i % 3] });
    }
    neb = nebula(); glow = glow || sprite(); warm = warm || lampSprite();
    place(); labels();
    books.forEach(function (b) { b.dir = null; });
    ring = null;
    beams = [];
    order = PLACES.map(function (p, k) { return k; }).sort(function () { return rnd() - .5; });
    /* A few countries already alight, so the idea reads before the first beam lands. */
    order.filter(function (k) { return project(PLACES[k].v, FACE, 1).z > .55; }).slice(0, still ? 9 : 3).forEach(function (k, n) { since[k] = still ? 1 : .6 + n * .5; });
  }
  function paintStars() {
    var px = w * .5, py = h * .55;
    stars.forEach(function (s) {
      var a = s.a + t * TURN * s.d, x = px + Math.cos(a) * s.r, y = py + Math.sin(a) * s.r;
      if (x < -8 || y < -8 || x > w + 8 || y > h + 8) { return; }
      if (s.big) {
        /* Gold stars swell and fade slowly, never in step. */
        var p = .5 + .5 * Math.sin(t * s.tw + s.ph), r = s.z * (.8 + .4 * p);
        g.globalAlpha = s.b * (.14 + .86 * p * p);
        g.drawImage(glow, x - r, y - r, r * 2, r * 2);
        return;
      }
      /* Upper sky: some stars scintillate like real ones, a fast irregular flicker over a slow drift. */
      var upper = clamp(1.5 - y / (h * .6));
      if (s.glint && upper > .02) {
        var drift = .5 + .5 * Math.sin(t * s.tw * .5 + s.ph);
        var flick = .5 * Math.sin(t * (5 + s.tw * 3) + s.ph * 2) + .3 * Math.sin(t * (9 + s.tw * 4) + s.ph * 3) + .2 * Math.sin(t * (13 + s.tw * 5) + s.ph * 5);
        var lum = clamp(.2 + .5 * drift + .42 * flick * (.45 + .55 * drift)), halo = 1.3 + 3.8 * lum;
        g.globalAlpha = upper * lum * .75;
        g.drawImage(glow, x - halo, y - halo, halo * 2, halo * 2);
        var core = 1 + lum * .7;
        g.globalAlpha = upper * Math.min(1, .2 + lum * 1.1);
        g.fillStyle = s.col; g.fillRect(x - core / 2, y - core / 2, core, core);
        /* At their brightest, a thin cross of light, as the eye sees it. */
        if (lum > .72) {
          var arm = (lum - .72) * 16;
          g.globalAlpha = upper * (lum - .72) * 1.5;
          g.fillRect(x - arm, y - .35, arm * 2, .7); g.fillRect(x - .35, y - arm, .7, arm * 2);
        }
        return;
      }
      g.globalAlpha = s.b * (.62 + .38 * Math.sin(t * s.tw + s.ph));
      g.fillStyle = s.col; g.fillRect(x, y, s.z, s.z);
    });
    g.globalAlpha = 1;
  }
  /* A point on or above the sphere, turned and tilted, on the screen. */
  function project(v, rot, alt) {
    var x = v[0] * Math.cos(rot) + v[2] * Math.sin(rot), z0 = -v[0] * Math.sin(rot) + v[2] * Math.cos(rot), y0 = v[1];
    var y = y0 * Math.cos(TILT) - z0 * Math.sin(TILT), z = y0 * Math.sin(TILT) + z0 * Math.cos(TILT);
    return { x: cx + R * alt * x, y: cy - R * alt * y, z: z };
  }
  function vec(ll) {
    var a = ll[0] * RAD, b = ll[1] * RAD;
    return [Math.cos(a) * Math.sin(b), Math.sin(a), Math.cos(a) * Math.cos(b)];
  }
  /* Two neighbours lit at once would print their names over each other. */
  function crowded(k) {
    var a = PLACES[k].box;
    return PLACES.some(function (o, n) {
      var b = o.box;
      return n !== k && since[n] < 3 && a[0] < b[2] && a[2] > b[0] && a[1] < b[3] && a[3] > b[1];
    });
  }
  /* On one endless clock, each cover's gold lights up briefly in turn, phases scattered so they twinkle. */
  function aim(b) {
    var bx = b.getBoundingClientRect(), sx = c.getBoundingClientRect(), right = b.getAttribute('data-side') === 'r';
    var x = ((right ? bx.left - sx.left : bx.right - sx.left) - cx) / R, y = (cy - (bx.top - sx.top + bx.height * .45)) / R, len = Math.hypot(x, y, .8);
    b.dir = [x / len, y / len, .8 / len];
    b.pool = [bx.left - sx.left + bx.width * .5, bx.top - sx.top + bx.height * .45];
  }
  function stepBooks() {
    ring = ring || books.filter(function (b) { return b.offsetParent; });
    ring.forEach(function (b, i) {
      var ph = (t / PERIOD + i * .618) % 1, o = still ? (i % 3 ? 0 : 1) : ph < .07 ? ease(ph / .07) : ph < .1 ? 1 : 1 - ease((ph - .1) / .12), was = b.o || 0;
      if (Math.abs(o - was) > .001 || b.o === undefined) { b.style.setProperty('--o', o.toFixed(3)); }
      if ((o > .5) !== (was > .5)) { b.classList.toggle('open', o > .5); }
      if (!b.dir) { aim(b); }
      b.o = o; b.lamp = o;
    });
  }
  /* A stream starts from the open page toward the next country in view. */
  function launch(b, rot) {
    for (var tries = 0; tries < order.length; tries++) {
      var k = order[next]; next = (next + 1) % order.length;
      var q = project(PLACES[k].v, rot, 1);
      if (q.z < .34 || q.y > h - low || since[k] < 3 || crowded(k) || beams.some(function (s) { return s.k === k; })) { continue; }
      beams.push({ k: k, age: 0, from: { x: b.pool[0], y: b.pool[1] } });
      return true;
    }
  }
  /* Light dims to almost nothing inside the band of rim numbers, so it passes behind them. */
  function behind(x, y) {
    if (cy - y < R * .18) { return 1; }
    var band = w < 900 ? 50 : 38, d = Math.hypot(x - cx, y - cy) - R;
    return 1 - .94 * clamp(Math.min(d + 2, band - d) / 9);
  }
  /* A streak of gold light runs from the cover to the country. */
  function paintBeams(dt, rot) {
    g.globalCompositeOperation = 'lighter'; g.lineCap = 'round';
    beams = beams.filter(function (b) {
      b.age += dt;
      var q = project(PLACES[b.k].v, rot, 1), mxx = b.from.x * .62 + q.x * .38, myy = Math.max(q.y, b.from.y) - 6;
      function at(u) { var a = 1 - u; return [a * a * b.from.x + 2 * a * u * mxx + u * u * q.x, a * a * b.from.y + 2 * a * u * myy + u * u * q.y]; }
      var fade = clamp(b.age / .4) * clamp((LIFE - b.age) / .9), head = clamp(b.age / REACH), i, n = 14, START = .06;
      if (head > START) {
        for (i = 0; i < n; i++) {
          var u0 = START + (head - START) * i / n, u1 = START + (head - START) * (i + 1) / n, A = at(u0), B = at(u1), grow = clamp((u1 - START) * 5), hide = behind(B[0], B[1]);
          g.strokeStyle = '#ffb648'; g.globalAlpha = hide * fade * grow * .13; g.lineWidth = 4.5;
          g.beginPath(); g.moveTo(A[0], A[1]); g.lineTo(B[0], B[1]); g.stroke();
          g.strokeStyle = '#ffeec8'; g.globalAlpha = hide * fade * grow * (.55 + .45 * Math.pow(Math.max(Math.sin(u1 * 15 - t * 6), 0), 3)); g.lineWidth = 1 + grow * .7;
          g.beginPath(); g.moveTo(A[0], A[1]); g.lineTo(B[0], B[1]); g.stroke();
        }
      }
      if (!b.done && b.age > REACH) { b.done = true; since[b.k] = 0; }
      return b.age < LIFE;
    });
    g.globalCompositeOperation = 'source-over'; g.globalAlpha = 1;
  }
  function paintEarth(dt) {
    /* The globe hurries past open ocean, so the light never stops for long. */
    var rot = FACE + turned, near = PLACES.filter(function (p) { var q = project(p.v, rot, 1); return q.z > .34 && q.y < h - low; }).length;
    boost += ((near < 7 ? 3.4 : 1) - boost) * Math.min(1, dt * 1.5);
    turned += dt * SPIN * boost;
    since = since.map(function (a) { return a + dt; });
    stepBooks();
    var air = g.createRadialGradient(cx, cy, R * .97, cx, cy, R * 1.1);
    air.addColorStop(0, 'rgba(110,150,225,.2)'); air.addColorStop(.3, 'rgba(84,120,200,.06)'); air.addColorStop(1, 'rgba(60,100,190,0)');
    g.fillStyle = air; g.beginPath(); g.arc(cx, cy, R * 1.1, 0, 6.284); g.fill();
    if (gl && ready > 1 && !gl.isContextLost()) { paintGlobe(rot); }
    else if (maps) { paintFlat(rot); }
    else {
      var body = g.createRadialGradient(cx - R * .25, cy - R * .4, R * .1, cx, cy, R);
      body.addColorStop(0, '#102d55'); body.addColorStop(.6, '#091c38'); body.addColorStop(1, '#050e1f');
      g.fillStyle = body; g.beginPath(); g.arc(cx, cy, R, 0, 6.284); g.fill();
    }
    /* A soft swell of light over a country as it warms. */
    g.globalCompositeOperation = 'lighter';
    PLACES.forEach(function (p, k) {
      var f = (heat(since[k]) - EMBER) / (1 - EMBER);
      if (f < .04) { return; }
      var q = project(p.v, rot, 1);
      if (q.z <= .05) { return; }
      var s = Math.max(26, Math.min(R * .42, p.size * RAD * R * 2));
      g.globalAlpha = .14 * f * clamp(q.z * 4); g.drawImage(glow, q.x - s, q.y - s, s * 2, s * 2);
    });
    g.globalCompositeOperation = 'source-over'; g.globalAlpha = 1;
    paintNames(rot);
    if (!still) {
      books.forEach(function (b) {
        if (b.o > .85 && !b.sent && b.pool) { b.sent = launch(b, rot); }
        if (b.o < .1) { b.sent = false; }
      });
    }
    paintBeams(dt, rot);
  }
  /* Each letter is laid flat on the sphere, so the names curve and turn with it. */
  function paintNames(rot) {
    var e = .6, ce = e * RAD;
    PLACES.forEach(function (p, k) {
      var f = (heat(since[k]) - EMBER) / (1 - EMBER), show = p.always ? 1 : f;
      /* A neighbour's name steps back while a smaller country has the light. */
      if (p.always) { p.quiet.forEach(function (o) { show = Math.min(show, 1 - (heat(since[PLACES.indexOf(o)]) - EMBER) / (1 - EMBER)); }); }
      if (show < .02 || project(p.v, rot, 1).z < .06) { return; }
      var c0 = R * Math.max(.3, Math.cos(p.lat * RAD)) * ce;
      p.letters.forEach(function (m) {
        if (m.ch === ' ') { return; }
        var lon = p.lon + m.x, a = project(vec([p.lat, lon]), rot, 1);
        if (a.z < .06) { return; }
        var b = project(vec([p.lat, lon + e]), rot, 1), d = project(vec([p.lat - e, lon]), rot, 1), k2 = p.fs * dpr;
        /* Names follow the sphere, but never flatten past the point of reading. */
        var ax = (b.x - a.x) / c0, ay = (b.y - a.y) / c0, dx = (d.x - a.x) / (R * ce), dy = (d.y - a.y) / (R * ce);
        var la = Math.hypot(ax, ay), ld = Math.hypot(dx, dy), ka = Math.max(1, .72 / la), kd = Math.max(1, .78 / ld);
        g.setTransform(ax * ka * k2, ay * ka * k2, dx * kd * k2, dy * kd * k2, a.x * dpr, a.y * dpr);
        var edge = clamp((a.z - .06) * 7);
        var cold = glyph(m.ch).c;
        g.globalAlpha = show * edge * .24; g.drawImage(cold, -cold.width / 2, -36);
        if (f > .02) { var hot = glyph(m.ch, 1).c; g.globalAlpha = f * edge; g.drawImage(hot, -hot.width / 2, -36); }
      });
    });
    g.setTransform(dpr, 0, 0, dpr, 0, 0); g.globalAlpha = 1;
  }
  function paint(dt) {
    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.clearRect(0, 0, w, h);
    if (neb) { g.drawImage(neb, 0, 0, w, h); }
    paintStars();
    paintEarth(dt);
  }
  /* Nearer books move more as the page scrolls and the pointer shifts. */
  function parallax() {
    var r = sky.getBoundingClientRect(), mid = r.top + r.height / 2 - window.innerHeight / 2;
    books.forEach(function (b) {
      var d = +b.getAttribute('data-d');
      b.style.transform = 'translate3d(' + (mx * d * 16).toFixed(1) + 'px,' + (my * d * 12 - mid * d * .05).toFixed(1) + 'px,0)';
    });
  }
  function frame(now) {
    if (on) {
      var dt = Math.min((now - last) / 1000, .06);
      t += dt; paint(dt); parallax();
    }
    last = now;
    window.requestAnimationFrame(frame);
  }
  globe();
  build();
  window.addEventListener('resize', function () { build(); if (still) { paint(0); } });
  window.addEventListener('load', function () { build(); if (still) { paint(0); } });
  if (document.fonts && document.fonts.ready) { document.fonts.load(nameFont).then(function () { glyphs = {}; labels(); if (still) { paint(0); } }); }
  if (still) { t = 12; paint(0); return; }
  if (window.matchMedia('(hover: hover)').matches) {
    sky.addEventListener('pointermove', function (e) {
      var r = sky.getBoundingClientRect();
      mx = (e.clientX - r.left) / r.width - .5; my = (e.clientY - r.top) / r.height - .5;
    });
  }
  if ('IntersectionObserver' in window) {
    new IntersectionObserver(function (e) { on = e[0].isIntersecting; }).observe(sky);
  }
  window.requestAnimationFrame(frame);
}());
