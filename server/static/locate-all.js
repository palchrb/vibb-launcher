// Locate's "Alle" view (design 85 §4, mockup B): every phone with a position on one map, as its
// round avatar with a time tag, its accuracy circle at its true radius, a bubble on a tap; markers
// closer than one avatar are one group marker, regrouped after every zoom. Leaflet against OSM's
// tiles with the same options as static/locate.js (design 33: the tiles alone send our origin).
//
// Everything comes from `GET /devices/locate/phones.json` (no-store), every word in it the
// server's - the script places text (textContent; security review H2), it never words anything
// itself. The page's own words are on the map box and the wait line (`data-w-*`, design 70 §5.3)
// and on the fit-all button the page renders hidden. Asked once at start, every 60 s while the page
// is visible and when it becomes visible again; after Update All (`data-waiting`, `data-at`) every
// 5 s for a minute, counting the rows' `answered`, then the address loses `requested`/`at`.
//
// A redraw never closes an open bubble or drops the keyboard focus (§4.4a): markers are kept by
// phone id (a group by its members' sorted ids) and updated in place; only a group whose members
// change is a new marker, and its bubble and focus move to the marker now holding that phone. The
// view is set once at load and once at the end of a wait, never while the parent has moved the map.
// The marker itself (avatar, tag, circle) is static/map-marker.js's, the one-phone view's too.
// ES5. Run its tests: node --test jstest/locate-all.test.js
(function (root) {
    "use strict";

    // Markers whose centres are closer than this (one avatar) are one group.
    var JOIN_PX = 40;
    // A fit that needs a zoom below this (wider than a region) shows the fresh phones only.
    var FAR_ZOOM = 11;
    var PAD_TOP_LEFT = [40, 64];
    var PAD_BOTTOM_RIGHT = [56, 40];
    var REFRESH_MS = 60000;
    var WAIT_EVERY_MS = 5000;
    var WAIT_TRIES = 12;
    // The accuracy circle when the page names no `--map-trail`.
    var TRAIL = "#A64B25";
    // The marker's look, shared with the one-phone view (static/map-marker.js, loaded first).
    var Mk = root.HandyMapMarker || (typeof require === "function" ? require("./map-marker.js") : null);
    var fillMarker = Mk.fillMarker;

    function w(el, name) {
        return (el && el.getAttribute && el.getAttribute("data-w-" + name)) || name;
    }

    // `text` with each `{name}` replaced by its value.
    function fill(text, values) {
        for (var name in values) {
            if (Object.prototype.hasOwnProperty.call(values, name)) {
                text = text.split("{" + name + "}").join(String(values[name]));
            }
        }
        return text;
    }

    function placed(row) {
        return typeof row.lat === "number" && typeof row.lng === "number";
    }

    // Fresh before old, then the newest seen first (the instants are one RFC 3339 shape), then id.
    function freshestFirst(a, b) {
        if (!!a.fresh !== !!b.fresh) return a.fresh ? -1 : 1;
        if (a.at !== b.at) return a.at > b.at ? -1 : 1;
        return a.id - b.id;
    }

    /**
     * The markers for `rows` with a position: greedily from the freshest, each joins every row not
     * yet placed whose projected point (`project(row)` -> {x, y}) is closer than `px` to its own. A
     * group is `{key, members (freshest first), lat, lng}` at the mean of its points.
     */
    function groups(rows, project, px) {
        var points = rows.filter(placed).sort(freshestFirst);
        var at = points.map(project);
        var taken = [];
        var out = [];
        for (var i = 0; i < points.length; i++) {
            if (taken[i]) continue;
            var members = [points[i]];
            taken[i] = true;
            for (var j = i + 1; j < points.length; j++) {
                if (taken[j]) continue;
                var dx = at[i].x - at[j].x;
                var dy = at[i].y - at[j].y;
                if (Math.sqrt(dx * dx + dy * dy) < px) {
                    members.push(points[j]);
                    taken[j] = true;
                }
            }
            var lat = 0;
            var lng = 0;
            for (var k = 0; k < members.length; k++) {
                lat += members[k].lat;
                lng += members[k].lng;
            }
            out.push({ key: keyOf(members), members: members, lat: lat / members.length, lng: lng / members.length });
        }
        return out;
    }

    function keyOf(members) {
        return members.map(function (m) { return m.id; }).sort(function (a, b) { return a - b; }).join(",");
    }

    function planFor(points) {
        if (points.length === 1) return { center: [points[0].lat, points[0].lng], zoom: 15 };
        return { bounds: points.map(function (p) { return [p.lat, p.lng]; }) };
    }

    /**
     * The view for `rows` (§4.5): the world at zoom 2 with no position; one at zoom 15; else every
     * position fitted - unless that needs a zoom below FAR_ZOOM (`boundsZoom(latLngs)`, with the
     * fit's padding) and some are fresh: then the fresh ones by the same rule. `everything` (the
     * fit-all button) never takes the exception.
     */
    function fitPlan(rows, boundsZoom, everything) {
        var points = rows.filter(placed);
        if (!points.length) return { center: [20, 0], zoom: 2 };
        var plan = planFor(points);
        if (everything || points.length < 2) return plan;
        var fresh = points.filter(function (p) { return p.fresh; });
        if (fresh.length && fresh.length < points.length && boundsZoom(plan.bounds) < FAR_ZOOM) return planFor(fresh);
        return plan;
    }

    function tokenColour(win, name, fallback) {
        try {
            var value = win.getComputedStyle(win.document.documentElement).getPropertyValue(name);
            if (value && value.trim()) return value.trim();
        } catch (e) {}
        return fallback;
    }

    /** A bubble: a part per phone (freshest first) - its name, its lines, a hairline and "Show …". */
    function bubble(doc, members) {
        var box = doc.createElement("div");
        box.className = "all-bubble";
        for (var i = 0; i < members.length; i++) {
            var row = members[i];
            var part = doc.createElement("div");
            part.className = "all-part";
            var name = doc.createElement("strong");
            name.className = "all-name";
            name.textContent = row.name || "";
            part.appendChild(name);
            var lines = row.lines || [];
            for (var j = 0; j < lines.length; j++) {
                var line = doc.createElement("div");
                line.textContent = String(lines[j]);
                part.appendChild(line);
            }
            var show = doc.createElement("a");
            show.className = "all-show";
            show.setAttribute("href", row.href);
            show.textContent = row.show || "";
            part.appendChild(show);
            box.appendChild(part);
        }
        return box;
    }

    function label(members) {
        return members.map(function (m) { return m.label; }).join("; ");
    }

    function install(win, L) {
        var doc = win.document;
        var box = doc.querySelector("[data-locate-all]");
        if (!box || !L) return null;
        var lang = box.getAttribute("data-lang") || "";
        var waitAt = box.hasAttribute("data-waiting") ? box.getAttribute("data-at") || "" : "";

        var map = L.map(box.querySelector("#map"), { zoomControl: false });
        L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
            maxZoom: 19,
            referrerPolicy: 'strict-origin-when-cross-origin',
            attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
        }).addTo(map);
        // The chips have the top: the zoom at the bottom right, fit-all above it (a bottom corner
        // stacks the control added last on top).
        L.control.zoom({ position: "bottomright" }).addTo(map);
        var fitButton = box.querySelector("[data-fit]");
        if (fitButton) {
            var Fit = L.Control.extend({
                options: { position: "bottomright" },
                onAdd: function () {
                    fitButton.removeAttribute("hidden");
                    L.DomEvent.disableClickPropagation(fitButton);
                    return fitButton;
                }
            });
            new Fit().addTo(map);
            fitButton.addEventListener("click", function () { fit(true); });
        }

        var trail = tokenColour(win, "--map-trail", TRAIL);
        var rows = [];
        var markers = {};
        var circles = {};
        var moved = false;
        var fitting = false;
        var viewSet = false;

        map.on("movestart", function () {
            if (!fitting) moved = true;
        });
        map.on("zoomend", function () { regroup(); });

        function boundsZoom(latLngs) {
            var pad = L.point(PAD_TOP_LEFT[0] + PAD_BOTTOM_RIGHT[0], PAD_TOP_LEFT[1] + PAD_BOTTOM_RIGHT[1]);
            return map.getBoundsZoom(L.latLngBounds(latLngs), false, pad);
        }

        // The script's own view: not animated, so its movestart is seen as its own.
        function fit(everything) {
            var plan = fitPlan(rows, boundsZoom, everything);
            fitting = true;
            try {
                if (plan.bounds) {
                    map.fitBounds(plan.bounds, { maxZoom: 16, paddingTopLeft: PAD_TOP_LEFT, paddingBottomRight: PAD_BOTTOM_RIGHT, animate: false });
                } else {
                    map.setView(plan.center, plan.zoom, { animate: false });
                }
            } finally {
                fitting = false;
            }
            viewSet = true;
            if (everything) moved = false;
            regroup();
        }

        function popupOptions() {
            var size = map.getSize ? map.getSize() : null;
            var width = size && size.x ? Math.max(160, Math.min(300, size.x - 64)) : 300;
            return { maxWidth: width, minWidth: Math.min(200, width), autoPanPaddingTopLeft: [10, 64], className: "all-popup" };
        }

        function circleStyle(row) {
            return Mk.circleStyle(row, trail);
        }

        // Each phone's accuracy circle at its own true point; none when the accuracy is unknown.
        function drawCircles() {
            var keep = {};
            for (var i = 0; i < rows.length; i++) {
                var row = rows[i];
                if (!placed(row) || typeof row.accuracy_m !== "number") continue;
                keep[row.id] = true;
                var circle = circles[row.id];
                if (!circle) {
                    var options = circleStyle(row);
                    options.radius = row.accuracy_m;
                    circles[row.id] = L.circle([row.lat, row.lng], options).addTo(map);
                } else {
                    circle.setLatLng([row.lat, row.lng]);
                    circle.setRadius(row.accuracy_m);
                    circle.setStyle(circleStyle(row));
                }
            }
            for (var id in circles) {
                if (Object.prototype.hasOwnProperty.call(circles, id) && !keep[id]) {
                    circles[id].remove();
                    delete circles[id];
                }
            }
        }

        function elementOf(entry) {
            return entry.marker.getElement ? entry.marker.getElement() : null;
        }

        function speak(entry) {
            var el = elementOf(entry);
            if (!el) return;
            el.setAttribute("role", "button");
            el.setAttribute("aria-label", label(entry.members));
        }

        function update(entry, group) {
            entry.members = group.members;
            entry.marker.setLatLng([group.lat, group.lng]);
            entry.marker.setZIndexOffset(group.members[0].fresh ? 1000 : 0);
            fillMarker(doc, entry.node, group.members);
            entry.marker.setPopupContent(bubble(doc, group.members));
            speak(entry);
        }

        function create(group) {
            var made = Mk.icon(L, doc, group.members);
            var node = made.node;
            var marker = L.marker([group.lat, group.lng], {
                icon: made.icon,
                keyboard: true,
                zIndexOffset: group.members[0].fresh ? 1000 : 0
            }).addTo(map);
            marker.bindPopup(bubble(doc, group.members), popupOptions());
            var entry = { marker: marker, node: node, members: group.members };
            speak(entry);
            return entry;
        }

        // The markers for the rows at the map's zoom, kept in place by key (§4.4a).
        function regroup() {
            if (!viewSet) return;
            var openPhone = null;
            var focusedPhone = null;
            var active = doc.activeElement;
            for (var key in markers) {
                if (!Object.prototype.hasOwnProperty.call(markers, key)) continue;
                var entry = markers[key];
                if (entry.marker.isPopupOpen && entry.marker.isPopupOpen()) openPhone = entry.members[0].id;
                var el = elementOf(entry);
                if (el && active && (el === active || (el.contains && el.contains(active)))) focusedPhone = entry.members[0].id;
            }
            var next = {};
            var holder = {};
            var list = groups(rows, function (row) { return map.latLngToLayerPoint([row.lat, row.lng]); }, JOIN_PX);
            for (var i = 0; i < list.length; i++) {
                var group = list[i];
                var kept = markers[group.key];
                if (kept) update(kept, group);
                next[group.key] = kept || create(group);
                for (var m = 0; m < group.members.length; m++) holder[group.members[m].id] = next[group.key];
            }
            for (var old in markers) {
                if (Object.prototype.hasOwnProperty.call(markers, old) && !next[old]) markers[old].marker.remove();
            }
            markers = next;
            if (openPhone !== null && holder[openPhone] && !holder[openPhone].marker.isPopupOpen()) holder[openPhone].marker.openPopup();
            if (focusedPhone !== null && holder[focusedPhone]) {
                var target = elementOf(holder[focusedPhone]);
                if (target && target !== doc.activeElement && target.focus) target.focus({ preventScroll: true });
            }
        }

        function draw(answer) {
            rows = answer;
            drawCircles();
            if (!viewSet) fit(false);
            else regroup();
        }

        function load() {
            var query = [];
            if (lang) query.push("lang=" + encodeURIComponent(lang));
            if (waitAt) query.push("at=" + encodeURIComponent(waitAt));
            var url = "/devices/locate/phones.json" + (query.length ? "?" + query.join("&") : "");
            return win.fetch(url, { credentials: "same-origin" })
                .then(function (r) { return r.ok ? r.json() : null; })
                .then(function (answer) {
                    if (!answer || typeof answer.length !== "number") throw new Error("phones");
                    draw(answer);
                    return answer;
                })
                .catch(function () {
                    if (!viewSet) {
                        map.setView([20, 0], 2);
                        viewSet = true;
                    }
                    return null;
                });
        }

        function visible() {
            return doc.visibilityState !== "hidden";
        }

        win.setInterval(function () {
            if (visible()) load();
        }, REFRESH_MS);
        if (doc.addEventListener) {
            doc.addEventListener("visibilitychange", function () {
                if (visible()) load();
            });
        }

        // After Update All: every 5 s for a minute, "2 of 3 have answered", then the end's words,
        // the address without the wait, and one fit unless the parent moved the map.
        // A hidden page neither asks nor counts a try (code QA L5); the end's words come from the
        // last answer read, even when the last ask failed.
        function waitForAnswers() {
            var tries = 0;
            var counted = null;
            var line = doc.getElementById("all_wait");
            var timer = win.setInterval(function () {
                if (!visible()) return;
                tries += 1;
                load().then(function (answer) {
                    if (!timer) return;
                    if (answer) {
                        counted = { done: 0, count: 0 };
                        for (var i = 0; i < answer.length; i++) {
                            if (typeof answer[i].answered !== "boolean") continue;
                            counted.count += 1;
                            if (answer[i].answered) counted.done += 1;
                        }
                    }
                    var over = tries >= WAIT_TRIES || (!!answer && counted.count > 0 && counted.done === counted.count);
                    if (line && counted && (answer || over)) line.textContent = fill(w(line, over ? "answered-end" : "answered"), counted);
                    if (!over) return;
                    win.clearInterval(timer);
                    timer = null;
                    waitAt = "";
                    try {
                        win.history.replaceState(null, "", "/devices/locate#all");
                    } catch (e) {}
                    if (!moved) fit(false);
                });
            }, WAIT_EVERY_MS);
        }

        var first = load();
        if (waitAt) waitForAnswers();
        return { map: map, loaded: first, markers: function () { return markers; }, regroup: regroup };
    }

    var api = { install: install, groups: groups, fitPlan: fitPlan, bubble: bubble, fillMarker: fillMarker, JOIN_PX: JOIN_PX };
    if (typeof module !== "undefined" && module.exports) {
        module.exports = api;
    } else if (root.document) {
        install(root, root.L);
    }
})(this);
