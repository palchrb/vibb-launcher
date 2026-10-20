// The Locate tab's map (design 31 step 13, design 44): the Locate page and a phone's Locations
// page. Leaflet against OSM's tiles (design 33: the tiles alone send our origin as Referer), the
// trail of the span picked with a small dot per fix, the newest fix as the marker whatever the
// span. What the page knows comes in data- attributes on the map box (`data-locate` = the phone,
// `data-shown` / `data-shown-text` = the newest fix shown and the popup's words, `data-newest-age`,
// `data-span`, `data-list` on the Locations page, `data-waiting` after Update Location); a phone's
// values reach the page only as text (textContent; security review H2).
//
// The span pick ("12 hours · 24 hours · 7 days · All kept") is a view choice, remembered per
// browser in localStorage (`handy-locate-span`): on the Locate page it redraws the trail and the
// Locations row's count in place; on the Locations page its link opens the page with that span
// (the server words and pages the list). Without JS the links do the same with the server's
// default. A tap on a Locations row brings the map into view and shows that fix on it. After
// Update Location the page asks every 5 s, for a minute, for a fix newer than the one shown and
// for the phone's answer. ES5. Run its tests: node --test jstest/locate.test.js
(function (root) {
    "use strict";

    var SPAN_KEY = "handy-locate-span";
    // Hours (0 = everything kept) and their words, as the server's locate::SPANS.
    var SPANS = { "12": "12 hours", "24": "24 hours", "168": "7 days", "0": "All kept" };
    var TRAIL = "#0B66D6";
    var WAIT_EVERY_MS = 5000;
    var WAIT_TRIES = 12;

    function readSpan(storage, fallback) {
        try {
            var span = storage && storage.getItem(SPAN_KEY);
            if (span && Object.prototype.hasOwnProperty.call(SPANS, span)) return span;
        } catch (e) {}
        return fallback;
    }

    function storeSpan(storage, span) {
        try {
            if (storage) storage.setItem(SPAN_KEY, span);
        } catch (e) {}
    }

    /**
     * The Locations row's value for `points` (locations.json's answer for `span`), as the server
     * words it: "14 in the last 12 hours", "3 kept", "None yet", "0 in the last 12 hours · newest
     * 3 days ago". The newest point flagged `newest` lies outside the span and isn't counted.
     */
    function countText(points, span, newestAge) {
        if (!points.length) return "None yet";
        var count = points.length - (points[points.length - 1].newest ? 1 : 0);
        if (span === "0") return count + " kept";
        if (count === 0) return "0 in the last " + SPANS[span] + " · newest " + newestAge;
        return count + " in the last " + SPANS[span];
    }

    function install(win, L) {
        var doc = win.document;
        var box = doc.querySelector("[data-locate]");
        if (!box || !L) return null;
        var id = box.getAttribute("data-locate");
        var shown = box.getAttribute("data-shown") || "";
        var shownText = box.getAttribute("data-shown-text") || "";
        var newestAge = box.getAttribute("data-newest-age") || "";
        var listPage = box.hasAttribute("data-list");
        var rendered = box.getAttribute("data-span") || "12";
        var storage = null;
        try {
            storage = win.localStorage;
        } catch (e) {}
        var pick = doc.querySelector("[data-span-pick]");
        var links = pick ? pick.querySelectorAll("a[data-span]") : [];

        function linkFor(span) {
            for (var i = 0; i < links.length; i++) {
                if (links[i].getAttribute("data-span") === span) return links[i];
            }
            return null;
        }

        // The address's span (a link followed) wins and is remembered; else the remembered one.
        var span = rendered;
        if (/[?&]span=/.test(win.location.search || "")) {
            storeSpan(storage, rendered);
        } else {
            span = readSpan(storage, rendered);
        }
        if (listPage && span !== rendered && linkFor(span)) {
            // The server words and pages the list: open it with the remembered span.
            win.location.replace(linkFor(span).getAttribute("href"));
            return null;
        }

        var map = L.map(box.querySelector("#map"), { preferCanvas: true });
        // OSM refuses tile requests without a Referer, and the page's own Referrer-Policy is
        // same-origin: the tiles alone send our origin (no path) - design 33.
        L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
            maxZoom: 19,
            referrerPolicy: 'strict-origin-when-cross-origin',
            attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
        }).addTo(map);
        // Leaflet's default marker icon from our own vendored images: Icon.Default prepends
        // imagePath to its file names (an icon URL of our own would be prefixed twice - a 404).
        L.Icon.Default.imagePath = '/static/leaflet/images/';
        var layer = L.layerGroup().addTo(map);
        var drawn = false;
        var asked = 0;

        function draw(points) {
            layer.clearLayers();
            if (!points.length) {
                map.setView([20, 0], 2);
                return;
            }
            var all = [];
            var trail = [];
            for (var i = 0; i < points.length; i++) {
                var at = [points[i].latitude, points[i].longitude];
                all.push(at);
                if (!points[i].newest) trail.push(at);
            }
            L.polyline(trail, { color: TRAIL }).addTo(layer);
            // A dot per fix, so fixes close together show at all; the newest has the marker.
            for (var j = 0; j < trail.length && j < points.length - 1; j++) {
                L.circleMarker(trail[j], { radius: 3, color: TRAIL, weight: 1, fillColor: TRAIL, fillOpacity: 1 }).addTo(layer);
            }
            var last = points[points.length - 1];
            // A DOM node with textContent: the phone's values are never HTML (review H2). The
            // server's words for the fix shown on the page, else (a newer one came since) none.
            var popup = doc.createElement("span");
            popup.textContent = last.captured_at === shown ? shownText : "Newest location";
            var marker = L.marker([last.latitude, last.longitude]).addTo(layer).bindPopup(popup);
            map.fitBounds(all, { maxZoom: 16, padding: [30, 30] });
            // After the view is set: a map without one opens no popup.
            marker.openPopup();
        }

        function load(span) {
            var mine = ++asked;
            return win.fetch("/devices/" + id + "/locations.json?since=" + span, { credentials: "same-origin" })
                .then(function (r) { return r.ok ? r.json() : null; })
                .then(function (points) {
                    if (mine !== asked) return;
                    if (!points) throw new Error("no points");
                    drawn = true;
                    draw(points);
                    var count = doc.querySelector("[data-locations-count]");
                    if (count) count.textContent = countText(points, span, newestAge);
                })
                .catch(function () {
                    if (mine === asked && !drawn) map.setView([20, 0], 2);
                });
        }

        function choose(chosen) {
            span = chosen;
            for (var i = 0; i < links.length; i++) {
                links[i].setAttribute("aria-checked", links[i].getAttribute("data-span") === chosen ? "true" : "false");
            }
            var row = doc.querySelector('[data-row="locations"]');
            if (row) row.setAttribute("href", "/devices/" + id + "/locations?span=" + chosen);
            return load(chosen);
        }

        if (pick) {
            pick.addEventListener("click", function (e) {
                var link = e.target && e.target.closest ? e.target.closest("a[data-span]") : null;
                if (!link) return;
                storeSpan(storage, link.getAttribute("data-span"));
                // The Locations page opens again with the span (the link); Locate redraws here.
                if (listPage) return;
                e.preventDefault();
                choose(link.getAttribute("data-span"));
            });
        }

        // A Locations row: the map into view (inside the page's scroller), that fix on it.
        function showFix(row) {
            if (box.scrollIntoView) box.scrollIntoView({ block: "start" });
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
            var line = function () { return doc.getElementById("fix_wait"); };
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
                win.fetch("/devices/" + id + "/locate-result.json", { credentials: "same-origin" })
                    .then(function (r) { return r.json(); })
                    .then(function (cmd) {
                        if (cmd && cmd.text) {
                            var el = line();
                            if (el) el.textContent = "The phone answered: " + cmd.text;
                            if (String(cmd.result).indexOf("fix") !== 0) win.clearInterval(timer);
                        }
                    })
                    .catch(function () {});
                if (tries >= WAIT_TRIES) {
                    win.clearInterval(timer);
                    var el = line();
                    if (el && el.textContent.indexOf("The phone answered") !== 0) {
                        el.textContent = "No answer yet. The phone may be offline; it gets the request if it is back within an hour.";
                    }
                }
            }, WAIT_EVERY_MS);
        }

        choose(span);
        return { choose: choose, map: map };
    }

    var api = { install: install, countText: countText, readSpan: readSpan, SPAN_KEY: SPAN_KEY, SPANS: SPANS };
    if (typeof module !== "undefined" && module.exports) {
        module.exports = api;
    } else if (root.document) {
        install(root, root.L);
    }
})(this);
