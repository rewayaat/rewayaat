/*
 * When was that? — the preview behind each of the thirteen marks on a narrator's page.
 *
 * The marks say which Imams the books place a man with, and nothing about when: a reader who
 * does not already know that al-Ṣādiq's imamate ran 114–148 AH learns from the strip only
 * that the man's marks are the sixth and seventh of thirteen. Pointing at a mark (or tapping
 * it, or arrowing along the strip from the keyboard) opens a card for that generation: whose
 * it is, its years, and one axis with that imamate on one lane and the man's own span on the
 * other, so whether and how far the two overlap is something seen rather than worked out.
 *
 * His span is drawn from the record and says so: from the first Imam the books place him with
 * to the last, cut short at his death where that falls inside it, and carried on as a dotted
 * line to his death where he outlived the last. Nothing is inferred beyond that.
 *
 * The spans come from data/chronology.json through the page (window.RIJAL_GENS, see Eras).
 * The chronology band this replaces drew the same axis for every reader whether he asked or
 * not, never fitted a phone and drew nothing in Arabic; this draws one era at a time, at the
 * width of a card, and runs right to left on an Arabic page as the strip itself does.
 */
(function () {
    "use strict";

    var data = window.RIJAL_GENS;
    var strip = document.querySelector(".hub-hero--rijal .gens");
    if (!data || !strip || !(data.eras || []).length) {
        return;
    }
    var marks = Array.prototype.slice.call(strip.querySelectorAll(".gens__mark"));
    var eras = {};
    data.eras.forEach(function (era) { eras[era.order] = era; });
    var lit = (data.lit || []).filter(function (n) { return typeof n === "number" && eras[n]; });
    if (!lit.length || marks.length < data.eras.length) {
        return;
    }

    var rtl = (window.I18N_DIR || "ltr") === "rtl";
    var arabic = (window.I18N_LOCALE || "en") === "ar";

    function t(key, fallback) {
        return (window.I18N || {})[key] || fallback;
    }

    function fill(pattern, first, second) {
        return pattern.replace("{0}", first).replace("{1}", second == null ? "" : second);
    }

    function digits(value) {
        return window.narratorDigits ? window.narratorDigits(value) : String(value);
    }

    function years(howMany) {
        return window.narratorCountPhrase
            ? window.narratorCountPhrase(howMany, "gens.card.years")
            : howMany + " years";
    }

    function pigment(order, deep) {
        return window.narratorPigment ? window.narratorPigment(order, deep) : "hsl(213 52% 34%)";
    }

    function escaped(text) {
        var out = document.createElement("span");
        out.textContent = text == null ? "" : String(text);
        return out.innerHTML;
    }

    /* A year AH as a year CE, near enough for "when was that" and labelled as approximate:
       a lunar year is 354.37 days. */
    function ce(ah) {
        return Math.round(ah * 0.970229 + 621.5643);
    }

    // His span, from the record.
    var first = Math.min.apply(null, lit);
    var last = Math.max.apply(null, lit);
    var died = typeof data.died === "number" ? data.died : null;
    var start = eras[first].from;
    var end = eras[last].to;
    if (died != null && died > start && died < end) {
        end = died;
    }
    var lifeEnd = died != null ? Math.max(end, died) : end;
    var name = data.name || "";

    /* ── The card ─────────────────────────────────────────────────────────── */

    var card = document.createElement("div");
    card.className = "gens-card";
    card.id = "gensCard";
    card.setAttribute("role", "tooltip");
    document.body.appendChild(card);

    var current = null;
    var closing = null;
    var pointer = "mouse";

    function kicker(order) {
        if (order === 0) {
            return t("gens.card.kicker.prophet", "The Prophet");
        }
        var ordinals = t("gens.card.ordinals",
            "First,Second,Third,Fourth,Fifth,Sixth,Seventh,Eighth,Ninth,Tenth,Eleventh,Twelfth").split(",");
        return fill(t("gens.card.kicker.imam", "The {0} Imam"), ordinals[order - 1] || String(order));
    }

    function spanLabel(order) {
        if (order === 0) {
            return t("gens.card.span.prophet", "From the Hijra");
        }
        if (order === 12) {
            return t("gens.card.span.mahdi", "The Minor Occultation");
        }
        return t("gens.card.span.imam", "Imamate");
    }

    /* What the record says about him and this generation, in one line. */
    function verdict(era) {
        if (lit.indexOf(era.order) >= 0) {
            return { kind: "with", text: fill(t("gens.card.with", "The rijal books place {0} with him"), name) };
        }
        var meet = Math.min(era.to, lifeEnd) - Math.max(era.from, start);
        if (meet > 0) {
            return { kind: "meet", text: fill(t("gens.card.overlap",
                "Their years meet, but the books do not place {0} with him"), name) };
        }
        if (era.to <= start) {
            var before = start - era.to;
            return { kind: "apart", text: before === 0
                ? fill(t("gens.card.justBefore", "Ends as the time of {0} begins"), name)
                : fill(t("gens.card.before", "{0} before the time of {1}"), years(before), name) };
        }
        var after = era.from - lifeEnd;
        if (after === 0) {
            return { kind: "apart", text: fill(t("gens.card.justAfter", "Begins as the time of {0} ends"), name) };
        }
        return { kind: "apart", text: died != null && lifeEnd === died
            ? fill(t("gens.card.afterDeath", "{0} after the death of {1}"), years(after), name)
            : fill(t("gens.card.after", "{0} after the time of {1}"), years(after), name) };
    }

    /* A step that puts four to six labels on the axis. */
    function step(width) {
        var steps = [5, 10, 20, 25, 50, 100];
        for (var i = 0; i < steps.length; i++) {
            if (width / steps[i] <= 6) {
                return steps[i];
            }
        }
        return 100;
    }

    /* One axis, zoomed to this era and his span with room either side: the eras on the upper
       lane, his span on the lower, and their overlap ruled across both. */
    function chart(era) {
        var W = 300, left = 12, right = W - 12;
        var lo = Math.min(era.from, start);
        var hi = Math.max(era.to, lifeEnd);
        var pad = Math.max(6, (hi - lo) * 0.12);
        lo -= pad;
        hi += pad;
        if (hi - lo < 50) {
            var grow = (50 - (hi - lo)) / 2;
            lo -= grow;
            hi += grow;
        }
        if (lo < 0) {
            hi -= lo;
            lo = 0;
        }
        function x(ah) {
            var at = left + (ah - lo) / (hi - lo) * (right - left);
            return rtl ? W - at : at;
        }
        function span(a, b) {
            var x1 = x(Math.max(a, lo)), x2 = x(Math.min(b, hi));
            return { x: Math.min(x1, x2), w: Math.abs(x2 - x1) };
        }
        var startSide = rtl ? right : left;
        // centred labels keep Arabic reading order, so «٢٠٠ هـ» reads as it should
        var bidi = arabic ? ' direction="rtl"' : "";
        var anchor = rtl ? "end" : "start";
        var out = [];

        // the overlap, ruled across both lanes
        var oFrom = Math.max(era.from, start), oTo = Math.min(era.to, lifeEnd);
        if (oTo > oFrom) {
            var o = span(oFrom, oTo);
            out.push('<rect class="gc-overlap" x="' + o.x.toFixed(1) + '" y="12" width="' + o.w.toFixed(1)
                + '" height="62" fill="' + pigment(era.order, false) + '"/>');
        }
        [era.from, era.to].forEach(function (ah) {
            if (ah > lo && ah < hi) {
                out.push('<line class="gc-guide" x1="' + x(ah).toFixed(1) + '" x2="' + x(ah).toFixed(1)
                    + '" y1="12" y2="84" stroke="' + pigment(era.order, false) + '"/>');
            }
        });

        // the upper lane: every era the window reaches, this one raised and ringed
        out.push('<text class="gc-lane" x="' + startSide + '" y="9" text-anchor="' + anchor + '">'
            + escaped(t("gens.card.lane.eras", "Imamates")) + "</text>");
        data.eras.forEach(function (other) {
            if (other.to <= lo || other.from >= hi) {
                return;
            }
            var s = span(other.from, other.to);
            var on = other.order === era.order;
            var his = lit.indexOf(other.order) >= 0;
            out.push('<rect class="gc-era' + (on ? " is-on" : his ? " is-his" : "") + '" x="'
                + (s.x + 0.5).toFixed(1) + '" y="' + (on ? 13 : 16) + '" width="' + Math.max(1, s.w - 1).toFixed(1)
                + '" height="' + (on ? 22 : 16) + '" rx="2.5" fill="' + pigment(other.order, false) + '"/>');
            var label = other.short || "";
            if (label && s.w >= label.length * (arabic ? 4.6 : 4.9) + 8) {
                out.push('<text class="gc-era-name' + (on ? " is-on" : "") + '" x="' + (s.x + s.w / 2).toFixed(1)
                    + '" y="' + (on ? 27.5 : 27) + '" text-anchor="middle"' + bidi + '>' + escaped(label) + "</text>");
            } else if (label && on) {
                // the era the card is about is always named: above its segment when too narrow
                out.push('<text class="gc-era-name is-over" x="' + (s.x + s.w / 2).toFixed(1)
                    + '" y="9" text-anchor="middle"' + bidi + '>' + escaped(label) + "</text>");
            }
        });

        // the lower lane: his span, solid where the books place him, dotted on to his death
        var short = name.length > 40 ? name.slice(0, 38).trim() + "…" : name;
        out.push('<text class="gc-lane gc-lane--him" x="' + startSide + '" y="49" text-anchor="' + anchor + '">'
            + escaped(short) + "</text>");
        var him = span(start, end);
        out.push('<rect class="gc-him" x="' + him.x.toFixed(1) + '" y="54" width="' + Math.max(2, him.w).toFixed(1)
            + '" height="11" rx="5.5"/>');
        if (died != null && died > end) {
            var tail = span(end, died);
            out.push('<line class="gc-him-tail" x1="' + (rtl ? tail.x + tail.w : tail.x).toFixed(1) + '" x2="'
                + (rtl ? tail.x : tail.x + tail.w).toFixed(1) + '" y1="59.5" y2="59.5"/>');
        }
        if (died != null && died >= lo && died <= hi) {
            var dx = x(died);
            out.push('<line class="gc-death" x1="' + dx.toFixed(1) + '" x2="' + dx.toFixed(1) + '" y1="51" y2="68"/>');
            // the label reads onward from the tick in the axis's direction, unless that runs it
            // off the card, when it turns back
            var nearEdge = rtl ? dx < 40 : dx > W - 40;
            var toward = nearEdge !== rtl ? "end" : "start";
            out.push('<text class="gc-death-label" x="' + (dx + (toward === "end" ? -3 : 3)).toFixed(1)
                + '" y="76" text-anchor="' + toward + '">'
                + escaped(fill(t("gens.card.died", "d. {0}"), digits(died))) + "</text>");
        }

        // the axis, in years AH
        out.push('<line class="gc-axis" x1="' + left + '" x2="' + right + '" y1="86" y2="86"/>');
        var every = step(hi - lo);
        var ticks = [];
        for (var year = Math.ceil(lo / every) * every; year <= hi; year += every) {
            ticks.push(year);
        }
        ticks.forEach(function (year, i) {
            var tx = x(year);
            out.push('<line class="gc-tick" x1="' + tx.toFixed(1) + '" x2="' + tx.toFixed(1) + '" y1="86" y2="89"/>');
            var text = digits(year) + (i === ticks.length - 1 ? (arabic ? " هـ" : " AH") : "");
            out.push('<text class="gc-tick-label" x="' + tx.toFixed(1) + '" y="98" text-anchor="middle"' + bidi + '>'
                + escaped(text) + "</text>");
        });

        // Laid out in physical coordinates: x() already runs the axis right to left on an Arabic
        // page, and an SVG anchor read in the text's own direction would flip "end" to the left.
        return '<svg class="gens-card__chart" viewBox="0 0 ' + W + ' 102" direction="ltr" aria-hidden="true" focusable="false">'
            + out.join("") + "</svg>";
    }

    function render(order) {
        var era = eras[order];
        var said = verdict(era);
        var length = era.to - era.from;
        card.style.setProperty("--era", pigment(order, false));
        card.style.setProperty("--era-deep", pigment(order, true));
        card.innerHTML =
            '<span class="gens-card__arrow" aria-hidden="true"></span>'
            + '<div class="gens-card__head">'
            + '<span class="gens-card__chip" aria-hidden="true">' + (order === 0 ? "✦" : escaped(digits(order))) + "</span>"
            + '<div class="gens-card__who">'
            + '<p class="gens-card__kicker">' + escaped(kicker(order)) + "</p>"
            + '<p class="gens-card__name"' + (arabic ? ' lang="ar"' : "") + ">" + escaped(era.name) + "</p>"
            + "</div>"
            + (arabic ? "" : '<span class="gens-card__ar" lang="ar" dir="rtl">' + escaped(era.nameAr) + "</span>")
            + "</div>"
            + '<div class="gens-card__span">'
            + '<span class="gens-card__span-label">' + escaped(spanLabel(order)) + "</span>"
            + '<span class="gens-card__years">'
            + escaped(fill(t("gens.card.ah", "{0}–{1} AH"), digits(era.from), digits(era.to))) + "</span>"
            + '<span class="gens-card__meta">'
            + escaped(fill(t("gens.card.ce", "c. {0}–{1} CE"), digits(ce(era.from)), digits(ce(era.to))))
            + " · " + escaped(years(length)) + "</span>"
            + "</div>"
            + chart(era)
            + '<p class="gens-card__verdict is-' + said.kind + '"><i aria-hidden="true"></i><span>'
            + escaped(said.text) + "</span></p>"
            + '<p class="gens-card__note">' + escaped(t("gens.card.note",
                "His span runs from the first Imam the books place him with to the last, or to his death.")) + "</p>";
    }

    /* Under the strip, centred on the mark and kept on the screen; above it only when there
       is no room below. The arrow follows the mark. */
    function place(order) {
        var mark = marks[order].getBoundingClientRect();
        var row = strip.getBoundingClientRect();
        // below the key as well as the strip, so the card does not sit on the line that
        // explains the marks
        var block = (strip.closest(".rijal__gens") || strip).getBoundingClientRect();
        var width = card.offsetWidth;
        var height = card.offsetHeight;
        var centre = mark.left + mark.width / 2;
        var left = Math.max(12, Math.min(centre - width / 2, window.innerWidth - width - 12));
        var below = block.bottom + 12 + height <= window.innerHeight || row.top < height + 24;
        var top = below ? block.bottom + 12 : row.top - 12 - height;
        card.classList.toggle("is-above", !below);
        card.style.left = (left + window.scrollX) + "px";
        card.style.top = (top + window.scrollY) + "px";
        card.style.setProperty("--arrow-x", Math.max(16, Math.min(centre - left, width - 16)) + "px");
    }

    function open(order) {
        if (closing) {
            clearTimeout(closing);
            closing = null;
        }
        if (order === current && card.classList.contains("is-open")) {
            return;
        }
        if (current != null) {
            marks[current].classList.remove("is-active");
        }
        current = order;
        marks[order].classList.add("is-active");
        render(order);
        place(order);
        card.classList.add("is-open");
        strip.setAttribute("aria-describedby", card.id);
    }

    function shut() {
        if (closing) {
            clearTimeout(closing);
            closing = null;
        }
        card.classList.remove("is-open");
        strip.removeAttribute("aria-describedby");
        if (current != null) {
            marks[current].classList.remove("is-active");
        }
    }

    /* The mark nearest the pointer along the strip, so the gaps between marks and the band
       above and below them count too: a 7px mark is a hard thing to hit. */
    function markAt(clientX) {
        var best = null, distance = Infinity;
        marks.forEach(function (mark, i) {
            var box = mark.getBoundingClientRect();
            var d = Math.abs(clientX - (box.left + box.width / 2));
            if (d < distance) {
                distance = d;
                best = i;
            }
        });
        return best;
    }

    /* ── The strip, made something to point at ─────────────────────────────── */

    strip.classList.add("is-live");
    strip.tabIndex = 0;

    strip.addEventListener("pointerdown", function (event) {
        pointer = event.pointerType || "mouse";
    });
    strip.addEventListener("pointermove", function (event) {
        if (event.pointerType === "mouse" || event.pointerType === "pen") {
            open(markAt(event.clientX));
        }
    });
    strip.addEventListener("pointerleave", function (event) {
        if (event.pointerType === "mouse" || event.pointerType === "pen") {
            closing = setTimeout(shut, 140);
        }
    });
    strip.addEventListener("click", function (event) {
        if (pointer !== "touch") {
            return;
        }
        var order = markAt(event.clientX);
        if (order === current && card.classList.contains("is-open")) {
            shut();
        } else {
            open(order);
        }
    });
    document.addEventListener("click", function (event) {
        if (pointer === "touch" && !strip.contains(event.target)) {
            shut();
        }
    }, true);

    strip.addEventListener("focus", function () {
        if (!card.classList.contains("is-open") && pointer !== "touch") {
            open(current != null ? current : first);
        }
    });
    strip.addEventListener("blur", shut);
    strip.addEventListener("keydown", function (event) {
        var order = current != null ? current : first;
        // the arrows move the way the strip is laid out, so on an Arabic page right is earlier
        var forward = rtl ? "ArrowLeft" : "ArrowRight";
        var back = rtl ? "ArrowRight" : "ArrowLeft";
        if (event.key === forward) {
            order = Math.min(marks.length - 1, order + 1);
        } else if (event.key === back) {
            order = Math.max(0, order - 1);
        } else if (event.key === "Home") {
            order = 0;
        } else if (event.key === "End") {
            order = marks.length - 1;
        } else if (event.key === "Escape") {
            shut();
            return;
        } else {
            return;
        }
        event.preventDefault();
        open(order);
    });

    window.addEventListener("resize", shut);

    /* Say that the marks open, in the key under them, in the words for the reader's device. */
    var key = document.querySelector(".hub-hero--rijal .hub-hero__gens-key");
    if (key) {
        var touch = window.matchMedia && window.matchMedia("(hover: none)").matches;
        var dot = document.createElement("span");
        dot.className = "rijal__gens-dot";
        dot.setAttribute("aria-hidden", "true");
        dot.textContent = "·";
        var hint = document.createElement("span");
        hint.className = "rijal__gens-hint";
        hint.textContent = touch
            ? t("gens.card.hint.tap", "tap a mark for its years")
            : t("gens.card.hint.hover", "point at a mark for its years");
        key.appendChild(dot);
        key.appendChild(hint);
    }
}());
