/*
 * The rijāl tab: what the books say of each man in the isnād.
 *
 * The chain itself is drawn by the server and is complete without this file: every link in
 * order, the words as the isnād writes them, and a link to the man's page. All this adds is
 * what a reader opens the tab to learn — the grade word each book gives him, whose word it
 * is, when and where he lived — and the one flourish when the chain assembles.
 *
 * The briefs for a whole chain come in one request, and go into the same map the preview
 * card keeps (window.narratorBriefs), so a man looked up here costs nothing when the reader
 * then taps his name in the text, and the other way round. Nothing is fetched until a panel
 * is opened: a chapter page carries twenty of these and most are never looked at.
 */
(function () {
    "use strict";

    // person_id -> brief. narrator-preview.js owns this map; whichever script runs first
    // creates it, so the order the two load in does not matter.
    var held = window.narratorBriefs = window.narratorBriefs || {};

    // How long the assembly takes to finish, so the class that drives it can come off. A
    // CSS animation replays whenever its element is shown again, and the chain must
    // assemble once per card, not every time the reader returns to the tab.
    var STAGGER_MS = 60;
    var LINK_MS = 260;
    var GLOW_MS = 900;

    function arabic() {
        return (window.I18N_LOCALE || "en") === "ar";
    }

    function t(key, fallback) {
        var value = (window.I18N || {})[key];
        return (typeof value === "string" && value.length) ? value : fallback;
    }

    function digits(value) {
        if (!arabic() || value == null) {
            return String(value == null ? "" : value);
        }
        return String(value).replace(/[0-9]/g, function (d) {
            return String.fromCharCode(0x0660 + Number(d));
        });
    }

    /* «Kufa (Shayban/Mawla)» carries his tribe, which a link has no room for. */
    function town(name) {
        return name ? String(name).replace(/\s*\(.*\)\s*$/, "") : "";
    }

    function reducedMotion() {
        return !!(window.matchMedia
            && window.matchMedia("(prefers-reduced-motion: reduce)").matches);
    }

    function show(node, text) {
        if (!node) {
            return;
        }
        node.textContent = text;
        node.hidden = !text;
    }

    /* The Arabic term first, as the preview card does: a rijāl reader looks for «ثقة» and
       the English half of «thiqa — trustworthy» is the gloss, not the term. On the Arabic
       site only the Arabic is used, and a row with no Arabic word is skipped rather than
       shown in Latin. */
    function gradeRows(brief) {
        var rows = (brief.said_by || []).slice(0, 2);
        return rows.map(function (row) {
            var books = (arabic() ? row.books_ar : row.books) || [];
            var shown = books.slice(0, 2).join(" · ");
            var rest = books.length - 2;
            if (rest > 0) {
                shown += " " + t("narrators.andMore", "+{0}").replace("{0}", digits(rest));
            }
            var word = row.said_ar || "";
            if (!arabic() && row.said) {
                word = (word ? word + " — " : "")
                    + String(row.said).split(" — ").pop();
            }
            return {leaning: row.leaning || "", word: word, books: shown};
        }).filter(function (row) { return row.word; });
    }

    function lifeLine(life) {
        if (!life) {
            return "";
        }
        var parts = [];
        var eras = (arabic() ? life.imams_ar : life.imams) || [];
        if (eras.length) {
            parts.push(eras.slice(0, 3).join(" · "));
        }
        var died = arabic() ? (life.died_ar || life.died_reported_ar) : (life.died || life.died_reported);
        if (died) {
            parts.push(t("narrators.died", "d. {0} AH").replace("{0}", digits(died)));
        }
        var where = arabic()
            ? town(life.city_ar || life.city_reported_ar)
            : town(life.city || life.city_reported);
        if (where) {
            parts.push(where);
        }
        return parts.join(" · ");
    }

    function name(brief) {
        return arabic() ? brief.display_ar : (brief.display_en || brief.display_ar);
    }

    /** Fills one link from the brief for its man. */
    function fill(link, brief) {
        var imam = link.classList.contains("rijal-link--imam");
        var who = link.querySelector("[data-rijal-who]");
        var said = link.querySelector("[data-rijal-said]");
        var life = link.querySelector("[data-rijal-life]");
        if (imam) {
            show(who, t("rijal.imam", "Imam") + " " + name(brief)
                + " (" + t("rijal.salutation", "") + ")");
            return;
        }
        // On the English site the display name is the Latin reading beneath the Arabic
        // words; on the Arabic site it is the Arabic display name, and nothing Latin.
        show(who, name(brief));
        var rows = gradeRows(brief);
        // No grade is not a finding worth a line in a box this size: the absence of
        // any word under his name says it, and a sentence saying it only costs room.
        if (said && rows.length) {
            said.textContent = "";
            said.className = "rijal-link__said";
            rows.forEach(function (row) {
                var line = document.createElement("span");
                line.className = "rijal-link__grade rijal-link__grade--" + row.leaning;
                var word = document.createElement("span");
                word.className = "rijal-link__word";
                word.textContent = row.word;
                line.appendChild(word);
                if (row.books) {
                    var books = document.createElement("span");
                    books.className = "rijal-link__books";
                    books.textContent = row.books;
                    line.appendChild(books);
                }
                said.appendChild(line);
            });
            said.hidden = false;
        }
        show(life, lifeLine(brief.life));
    }

    function endsAt(body, brief) {
        var ends = body.querySelector("[data-rijal-ends]");
        var last = body.querySelector(".rijal-link:last-child.rijal-link--imam");
        if (!ends || !last || !brief) {
            return;
        }
        var text = ends.querySelector("[data-rijal-ends-text]");
        text.textContent = t("rijal.summary.endsAt", "ends at {0}").replace("{0}", name(brief));
        ends.hidden = false;
    }

    function fillAll(body) {
        body.querySelectorAll(".rijal-link[data-rijal-id]").forEach(function (link) {
            var brief = held[link.getAttribute("data-rijal-id")];
            if (brief) {
                fill(link, brief);
            }
        });
        var last = body.querySelector(".rijal-link:last-child[data-rijal-id]");
        if (last) {
            endsAt(body, held[last.getAttribute("data-rijal-id")]);
        }
    }

    /* The one request. Ids already in the cache — the reader tapped one of these names in
       the text before opening the tab — are left out, and when none is missing there is no
       request at all. */
    function fetchBriefs(body) {
        var status = body.querySelector("[data-rijal-status]");
        var wanted = (body.getAttribute("data-rijal-ids") || "").split(",")
            .filter(function (id) { return id && !held[id]; });
        if (!wanted.length) {
            fillAll(body);
            return;
        }
        show(status, t("rijal.loading", "Fetching what the books say"));
        fetch("/v1/narrators/briefs?ids=" + encodeURIComponent(wanted.join(",")))
            .then(function (response) { return response.ok ? response.json() : null; })
            .then(function (payload) {
                if (!payload || !payload.narrators) {
                    throw new Error("no briefs");
                }
                Object.keys(payload.narrators).forEach(function (id) {
                    held[id] = payload.narrators[id];
                });
                show(status, "");
                fillAll(body);
            })
            .catch(function () {
                // The chain is already readable; say that the extra is missing, and let the
                // next opening try again.
                show(status, t("rijal.failed", "Could not load what the books say of these men."));
                body.removeAttribute("data-rijal-opened");
            });
    }

    /* Links fade and rise in turn, the connectors draw as each lands, and one glow runs the
       length of the chain at the end. Applied over markup that is already there, and not at
       all under reduced motion. */
    function assemble(body) {
        if (reducedMotion()) {
            return;
        }
        var count = body.querySelectorAll(".rijal-link").length;
        // The glow waits for the last link to land, so the stylesheet needs the count.
        body.style.setProperty("--n", count);
        body.classList.add("is-assembling");
        var total = (count - 1) * STAGGER_MS + LINK_MS + GLOW_MS + 120;
        window.setTimeout(function () {
            body.classList.remove("is-assembling");
        }, total);
    }

    document.addEventListener("click", function (event) {
        var button = event.target.closest && event.target.closest('[data-hub-panel="rijal"]');
        if (!button) {
            return;
        }
        var card = button.closest(".hadith-card");
        // The server renders the panel with the page; the search results draw it in the
        // browser a tick after the click, from the same chain_people. So the panel is
        // looked for by its ids attribute as well as by its hub name, and if it is not
        // there yet it is waited for rather than given up on.
        var body = card && (card.querySelector('[data-hub-panel-body="rijal"]')
                || card.querySelector('[data-rijal-ids]'));
        if (!body) {
            // Vue replaces the card's subtree when it draws the panel, so the card element
            // caught at click time is detached a moment later and anything found through it
            // is found in a document fragment nobody is looking at. The button and the panel
            // carry the narration's own id for that reason, and the panel is looked up by it.
            var mine = button.getAttribute("data-rijal-for");
            var waited = 0;
            var waiting = window.setInterval(function () {
                var late = mine
                    ? document.querySelector('[data-rijal-for="' + mine + '"][data-rijal-ids]')
                    : (card && card.querySelector('[data-rijal-ids]'));
                if (late && !late.getAttribute("data-rijal-opened")) {
                    window.clearInterval(waiting);
                    late.setAttribute("data-rijal-opened", "1");
                    assemble(late);
                    fetchBriefs(late);
                } else if (++waited > 40) {
                    window.clearInterval(waiting);
                }
            }, 25);
            return;
        }
        if (body.getAttribute("data-rijal-opened")) {
            return;
        }
        body.setAttribute("data-rijal-opened", "1");
        // hub-pages.js unhides the panel in its own click handler, which runs after this
        // one on the document; waiting a frame lets the animation start on a visible chain.
        window.requestAnimationFrame(function () {
            assemble(body);
        });
        fetchBriefs(body);
    });
}());
