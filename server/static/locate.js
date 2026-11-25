// The Locate tab's map (design 31 step 13, design 44): the Locate page and a phone's Locations
// page. Leaflet against OSM's tiles (design 33: the tiles alone send our origin as Referer), the
// trail of the span picked with a small dot per fix, the newest fix as the marker whatever the
// span. What the page knows comes in data- attributes on the map box (`data-locate` = the phone,
// `data-shown` / `data-shown-text` = the newest fix shown and the popup's words, `data-newest-age`,
// `data-trail` on the Locate page, `data-span` and `data-list` on the Locations page,
// `data-waiting` after Update Location); a phone's values reach the page only as text
// (textContent; security review H2).
//
// The Locate map's trail (the owner, 2026-11-24) is none by default - the newest fix alone
// (`locations.json?newest=1`) - or the last 6, 12, 24 or 48 hours, picked in the map's chip (a
// <details> at its bottom left, `data-span-pick`): a choice redraws in place, the chip's word
// follows it, the menu closes (also on Escape or a tap outside), and nothing is remembered - every
// load starts without a trail. On the Locations page the span pick (12 hours · 24 hours · 7 days ·
// all kept) opens the page with that span (the server words and pages the list). Without JS the
// links do the same. A tap on a Locations row brings the map into view and shows that fix on it. After
// Update Location the page asks every 5 s, for a minute, for a fix newer than the one shown and
// for the phone's answer. The phone's zones (design 64, `zones.json`, no-store; `data-zones` on
// the box while it has any) are circles in
// `--map-zone` with their names as tooltips (text nodes). Its words come from the page (design 70
// §5.3): the Locations count's `data-w-*`, the map box's `data-w-newest`, the wait line's
// `data-w-answered`/`data-w-no-answer`, set with textContent; `locate-result.json` is asked in the
// page's language (`data-lang`). The chip's words are its links' (`data-face`) and its summary's
// `data-label`.
//
// On the Locate page the newest fix is the Alle view's marker (design 85 revision 3,
// static/map-marker.js, loaded first): the phone's avatar in its tone, the time tag, fresh or old
// as the server judged the fix shown, the accuracy circle; the box's `data-marker-*` carry the
// phone's row (`tone`, `initial`, `photo`, `tag`, `fresh`, `label`), `data-chips` while the
// phones' chips lie over the map's top. A newer fix than the one shown is drawn fresh, without a
// tag (the server hasn't worded it). The Locations page keeps Leaflet's pin. Since design 88
// revision 2 the bubble alone tells the fix (the box under the map went): `data-shown-text`, the
// legend's word for screen readers (`data-fix-seen`), then a line each for `data-fix-still`,
// `-aged`, `-before-off` and `-title` (the exact time, the provider, "test location"). ES5. Run its
// tests: node --test jstest/locate.test.js
(function (root) {
    "use strict";

    // The trail's colour when the page names none: vibb's dark peach.
    var TRAIL = "#A64B25";
    // A zone's circle when the page names none (`--map-zone`).
    var ZONE = "#2F6F62";
    var WAIT_EVERY_MS = 5000;
    var WAIT_TRIES = 12;
    // The marker's look, shared with the Alle view.
    var Mk = root.HandyMapMarker || (typeof require === "function" ? require("./map-marker.js") : null);

    // A word the page gives an element (`data-w-<name>`); a missing one shows its name.
    function w(el, name) {
        return (el && el.getAttribute && el.getAttribute("data-w-" + name)) || name;
    }

    // `text` with each `{name}` replaced once by its value.
    function fill(text, values) {
        for (var name in values) {
            if (Object.prototype.hasOwnProperty.call(values, name)) {
                text = text.split("{" + name + "}").join(String(values[name]));
            }
        }
        return text;
    }

    // The trail in the page's accent, `--map-trail` (style.css): the tiles are light in both
    // schemes, so it is the light scheme's peach in dark mode too.
    function trailColour(win) {
        return tokenColour(win, "--map-trail", TRAIL);
    }

    function tokenColour(win, name, fallback) {
        try {
            var value = win.getComputedStyle(win.document.documentElement).getPropertyValue(name);
            if (value && value.trim()) return value.trim();
        } catch (e) {}
        return fallback;
    }

    // The bubble's lines after the first, in this order, from the map box's `data-fix-<name>`.
    var BUBBLE_LINES = ["still", "aged", "before-off", "title"];

    /**
     * The one-phone marker's bubble (design 88 revision 2): `text` first, with the legend's word
     * hidden for screen readers, then the box's further lines - all the server's words, set as
     * text. A fix newer than the one rendered (`shownFix` false) has only `text`.
     */
    function bubble(doc, box, text, shownFix) {
        var el = doc.createElement("div");
        el.className = "one-bubble";
        var first = doc.createElement("div");
        first.textContent = text;
        el.appendChild(first);
        if (!shownFix) return el;
        var seen = box.getAttribute("data-fix-seen");
        if (seen) {
            var hidden = doc.createElement("span");
            hidden.className = "visually-hidden";
            hidden.textContent = " " + seen;
            first.appendChild(hidden);
        }
        for (var i = 0; i < BUBBLE_LINES.length; i++) {
            var words = box.getAttribute("data-fix-" + BUBBLE_LINES[i]);
            if (!words) continue;
            var line = doc.createElement("div");
            line.className = "one-" + BUBBLE_LINES[i];
            line.textContent = words;
            el.appendChild(line);
        }
        return el;
    }

    /**
     * The phone's zones on `map` (design 64): a calm outline per zone in `colour`, its name a
     * tooltip built as text. Returns the centres drawn.
     */
    function drawZones(L, doc, layer, zones, colour) {
        var drawn = [];
        for (var i = 0; i < zones.length; i++) {
            var z = zones[i];
            if (typeof z.latitude !== "number" || typeof z.longitude !== "number" || typeof z.radius_m !== "number") continue;
            var name = doc.createElement("span");
            name.textContent = String(z.name || "");
            L.circle([z.latitude, z.longitude], { radius: z.radius_m, color: colour, weight: 2, fillColor: colour, fillOpacity: 0.08 })
                .addTo(layer)
                .bindTooltip(name);
            drawn.push([z.latitude, z.longitude]);
        }
        return drawn;
    }

    function install(win, L) {
        var doc = win.document;
        var box = doc.querySelector("[data-locate]");
        if (!box || !L) return null;
        var id = box.getAttribute("data-locate");
        var shown = box.getAttribute("data-shown") || "";
        var shownText = box.getAttribute("data-shown-text") || "";
        var listPage = box.hasAttribute("data-list");
        // The Locate map's trail in hours, "0" none; the Locations page's span, "0" all kept.
        var trailPage = box.hasAttribute("data-trail");
        var span = trailPage ? box.getAttribute("data-trail") || "0" : box.getAttribute("data-span") || "12";
        var lang = box.getAttribute("data-lang") || "";
        var pick = doc.querySelector("[data-span-pick]");
        var links = pick ? pick.querySelectorAll("a[data-span]") : [];
        var face = trailPage && pick ? pick.querySelector("summary") : null;

        // The phone's own marker (the Locate page), else Leaflet's pin (the Locations page).
        var mark = Mk && box.hasAttribute("data-marker-tone") ? {
            tone: box.getAttribute("data-marker-tone"),
            initial: box.getAttribute("data-marker-initial") || "",
            photo: box.getAttribute("data-marker-photo") || "",
            tag: box.getAttribute("data-marker-tag") || "",
            fresh: box.hasAttribute("data-marker-fresh"),
            label: box.getAttribute("data-marker-label") || ""
        } : null;
        // The chips have the map's top: the zoom goes to the bottom right, the fit keeps clear.
        var chips = box.hasAttribute("data-chips");
        var map = L.map(box.querySelector("#map"), mark ? { preferCanvas: true, zoomControl: false } : { preferCanvas: true });
        if (mark) L.control.zoom({ position: "bottomright" }).addTo(map);
        // OpenStreetMap's tiles through the server's cache (`map_tiles`): OSM sees the server, never this browser.
        L.tileLayer('/map-tiles/{z}/{x}/{y}.png', {
            maxZoom: 19,
            attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
        }).addTo(map);
        // Leaflet's default marker icon from our own vendored images: Icon.Default prepends
        // imagePath to its file names (an icon URL of our own would be prefixed twice - a 404).
        L.Icon.Default.imagePath = '/static/leaflet/images/';
        var layer = L.layerGroup().addTo(map);
        var zoneLayer = L.layerGroup().addTo(map);
        var drawn = false;
        var zoneCentres = [];
        var lastPoints = null;
        var asked = 0;

        // A span without points shows the zones when there are any (code QA Q-7), else the world.
        function emptyView() {
            if (zoneCentres.length === 1) {
                map.setView(zoneCentres[0], 15);
            } else if (zoneCentres.length) {
                map.fitBounds(zoneCentres, { maxZoom: 15, padding: [30, 30] });
            } else {
                map.setView([20, 0], 2);
            }
        }

        function draw(points) {
            layer.clearLayers();
            lastPoints = points;
            if (!points.length) {
                emptyView();
                return;
            }
            var all = [];
            var trail = [];
            for (var i = 0; i < points.length; i++) {
                var at = [points[i].latitude, points[i].longitude];
                all.push(at);
                if (!points[i].newest) trail.push(at);
            }
            var colour = trailColour(win);
            L.polyline(trail, { color: colour }).addTo(layer);
            // A dot per fix, so fixes close together show at all; the newest has the marker.
            for (var j = 0; j < trail.length && j < points.length - 1; j++) {
                L.circleMarker(trail[j], { radius: 3, color: colour, weight: 1, fillColor: colour, fillOpacity: 1 }).addTo(layer);
            }
            var last = points[points.length - 1];
            // A DOM node with textContent: the phone's values are never HTML (review H2). The
            // server's words for the fix shown on the page, else (a newer one came since) none.
            var popup = doc.createElement("span");
            popup.textContent = last.captured_at === shown ? shownText : w(box, "newest");
            if (!mark) {
                var marker = L.marker([last.latitude, last.longitude]).addTo(layer).bindPopup(popup);
                map.fitBounds(all, { maxZoom: 16, padding: [30, 30] });
                // After the view is set: a map without one opens no popup.
                marker.openPopup();
                return;
            }
            drawMark(last, popup, colour);
            map.fitBounds(all, chips
                ? { maxZoom: 16, paddingTopLeft: [30, 64], paddingBottomRight: [30, 30] }
                : { maxZoom: 16, padding: [30, 30] });
        }

        // The newest fix as the Alle view draws a phone: its accuracy circle, its avatar and tag;
        // its words in the white bubble (bubble()), opened by a tap (the tag says the age at a glance).
        function drawMark(last, popup, colour) {
            var shownFix = last.captured_at === shown;
            var row = {
                tone: mark.tone,
                initial: mark.initial,
                photo: mark.photo,
                fresh: shownFix ? mark.fresh : true,
                tag: shownFix ? mark.tag : ""
            };
            if (typeof last.accuracy_meters === "number") {
                var options = Mk.circleStyle(row, colour);
                options.radius = last.accuracy_meters;
                L.circle([last.latitude, last.longitude], options).addTo(layer);
            }
            var made = Mk.icon(L, doc, [row]);
            var marker = L.marker([last.latitude, last.longitude], { icon: made.icon, keyboard: true, zIndexOffset: 1000 });
            // Its element exists only once the map has a view (Leaflet adds a layer when the map is
            // ready): spoken at its "add", and at once when it is there already.
            var speak = function () {
                var el = marker.getElement ? marker.getElement() : null;
                if (!el) return;
                el.setAttribute("role", "button");
                el.setAttribute("aria-label", shownFix && mark.label ? mark.label : popup.textContent);
            };
            marker.on("add", speak);
            marker.addTo(layer).bindPopup(bubble(doc, box, popup.textContent, shownFix), { className: "all-popup", autoPanPaddingTopLeft: [10, chips ? 64 : 10] });
            speak();
        }

        function load(span) {
            var mine = ++asked;
            var query = trailPage && span === "0" ? "newest=1" : "since=" + span;
            return win.fetch("/devices/" + id + "/locations.json?" + query, { credentials: "same-origin" })
                .then(function (r) { return r.ok ? r.json() : null; })
                .then(function (points) {
                    if (mine !== asked) return;
                    if (!points) throw new Error("points");
                    drawn = true;
                    draw(points);
                })
                .catch(function () {
                    if (mine === asked && !drawn) map.setView([20, 0], 2);
                });
        }

        function choose(chosen) {
            span = chosen;
            for (var i = 0; i < links.length; i++) {
                var on = links[i].getAttribute("data-span") === chosen;
                links[i].setAttribute("aria-checked", on ? "true" : "false");
                if (on) chipSays(links[i]);
            }
            return load(chosen);
        }

        // The chip's word ("Spor", "24 t") and its spoken name ("Spor, 24 t") from the link chosen.
        function chipSays(link) {
            if (!face) return;
            var word = face.querySelector ? face.querySelector("[data-trail-face]") : null;
            if (word) word.textContent = link.getAttribute("data-face") || "";
            face.setAttribute("aria-label", (face.getAttribute("data-label") || "") + ", " + (link.textContent || ""));
        }

        // The chip's menu closes; the focus back on the chip when it was inside the menu.
        function closeMenu(refocus) {
            if (!trailPage || !pick || !pick.hasAttribute("open")) return false;
            pick.removeAttribute("open");
            if (refocus && face && face.focus) face.focus({ preventScroll: true });
            return true;
        }

        if (pick) {
            pick.addEventListener("click", function (e) {
                var link = e.target && e.target.closest ? e.target.closest("a[data-span]") : null;
                if (!link) return;
                e.preventDefault();
                // The Locations page opens again with the span, in place of this history entry
                // (Back goes to Locate, not through the spans); Locate redraws here.
                if (listPage) {
                    win.location.replace(link.getAttribute("href"));
                    return;
                }
                var inside = doc.activeElement && pick.contains && pick.contains(doc.activeElement);
                closeMenu(inside);
                choose(link.getAttribute("data-span"));
            });
        }
        if (trailPage && pick) {
            // Escape closes the menu before anything else hears it (map-full.js's full screen
            // waits for the next one: it skips a handled key).
            doc.addEventListener("keydown", function (e) {
                if ((e.key === "Escape" || e.key === "Esc") && closeMenu(true)) e.preventDefault();
            });
            // A tap outside closes it, before the map (or anything) acts on that tap.
            var outside = function (e) {
                if (pick.contains && !pick.contains(e.target)) closeMenu(false);
            };
            doc.addEventListener(win.PointerEvent ? "pointerdown" : "mousedown", outside, true);
            if (!win.PointerEvent) doc.addEventListener("touchstart", outside, true);
        }

        // A Locations row: the map into view (inside the page's scroller), that fix on it.
        // The map's top goes 8 px under the status bar's strip (.top-strip: <main> has no top padding
        // since design 84, its band scrolls away with the page), by an offset in the scroll itself:
        // scroll-margin-top needs Safari 14.1, the floor is 14.0.
        function mapIntoView() {
            var main = doc.querySelector("main");
            var style = main && win.getComputedStyle ? win.getComputedStyle(main) : null;
            if (style && (style.overflowY === "auto" || style.overflowY === "scroll")) {
                var strip = doc.querySelector(".top-strip");
                var under = (strip ? strip.getBoundingClientRect().height || 0 : 0) + 8;
                main.scrollTop += box.getBoundingClientRect().top - main.getBoundingClientRect().top - under;
            } else if (box.scrollIntoView) {
                box.scrollIntoView({ block: "start" });
            }
        }

        function showFix(row) {
            mapIntoView();
            var at = [parseFloat(row.getAttribute("data-lat")), parseFloat(row.getAttribute("data-lng"))];
            if (isNaN(at[0]) || isNaN(at[1])) return;
            map.setView(at, Math.max(map.getZoom() || 0, 16));
            var text = doc.createElement("span");
            text.textContent = row.getAttribute("data-text") || "";
            L.popup().setLatLng(at).setContent(text).openOn(map);
        }
        var rows = doc.querySelectorAll("[data-fix]");
        for (var i = 0; i < rows.length; i++) {
            (function (row) {
                row.setAttribute("role", "button");
                row.setAttribute("tabindex", "0");
                row.addEventListener("click", function () { showFix(row); });
                row.addEventListener("keydown", function (e) {
                    if (e.key === "Enter" || e.key === " ") {
                        e.preventDefault();
                        showFix(row);
                    }
                });
            })(rows[i]);
        }

        if (box.hasAttribute("data-waiting")) waitForFix();

        // After Update Location: a fix newer than the one shown reloads the page at the map; the
        // phone's own answer, in the server's words ("Location is off on the phone"), shows.
        function waitForFix() {
            var tries = 0;
            var answered = false;
            var line = function () { return doc.getElementById("fix_wait"); };
            var result = "/devices/" + id + "/locate-result.json" + (lang ? "?lang=" + encodeURIComponent(lang) : "");
            var timer = win.setInterval(function () {
                tries += 1;
                win.fetch("/devices/" + id + "/locations.json?since=1", { credentials: "same-origin" })
                    .then(function (r) { return r.json(); })
                    .then(function (points) {
                        var newest = points.length ? points[points.length - 1].captured_at : "";
                        if (newest && newest !== shown) {
                            win.clearInterval(timer);
                            win.location.replace("/devices/locate?device=" + id + "#location");
                        }
                    })
                    .catch(function () {});
                win.fetch(result, { credentials: "same-origin" })
                    .then(function (r) { return r.json(); })
                    .then(function (cmd) {
                        if (cmd && cmd.text) {
                            var el = line();
                            answered = true;
                            if (el) el.textContent = fill(w(el, "answered"), { answer: cmd.text });
                            if (String(cmd.result).indexOf("fix") !== 0) win.clearInterval(timer);
                        }
                    })
                    .catch(function () {});
                if (tries >= WAIT_TRIES) {
                    win.clearInterval(timer);
                    var el = line();
                    if (el && !answered) el.textContent = w(el, "no-answer");
                }
            }, WAIT_EVERY_MS);
        }

        // The zones (`data-zones` while the phone has any), once: they don't change with the trail.
        // Asked after the first locations settled (code QA Q-7: in either order the view is the
        // trail's, or the zones' when the span has no points).
        var first = choose(span);
        var zonesLoaded = !box.hasAttribute("data-zones") ? first : first
            .then(function () {
                return win.fetch("/devices/" + id + "/zones.json", { credentials: "same-origin" });
            })
            .then(function (r) { return r.ok ? r.json() : null; })
            .then(function (zones) {
                if (!zones || !zones.length) return;
                zoneCentres = drawZones(L, doc, zoneLayer, zones, tokenColour(win, "--map-zone", ZONE));
                if (!lastPoints || !lastPoints.length) emptyView();
            })
            .catch(function () {});
        return { choose: choose, map: map, zonesLoaded: zonesLoaded };
    }

    var api = { install: install, bubble: bubble, trailColour: trailColour, drawZones: drawZones };
    if (typeof module !== "undefined" && module.exports) {
        module.exports = api;
    } else if (root.document) {
        install(root, root.L);
    }
})(this);
