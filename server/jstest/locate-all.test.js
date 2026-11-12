// Behaviour of static/locate-all.js (design 85 §4, §8): the groups, the view, the markers and
// bubbles as text, a redraw that keeps the open bubble and the focus, the refresh and the wait after
// Update All. Run: node --test jstest/locate-all.test.js
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const path = require("node:path");
const { install, groups, fitPlan, bubble, fillMarker, JOIN_PX } = require("../static/locate-all.js");

/** A small DOM element: classes, attributes, children and text (never HTML). */
function element(tag) {
    const el = {
        tag,
        className: "",
        attrs: {},
        children: [],
        listeners: {},
        own: "",
        get firstChild() { return el.children[0] || null; },
        get textContent() { return el.own + el.children.map((c) => c.textContent).join(""); },
        set textContent(text) { el.children = []; el.own = String(text); },
        appendChild(child) { el.children.push(child); child.parent = el; return child; },
        removeChild(child) { el.children = el.children.filter((c) => c !== child); return child; },
        setAttribute(name, value) { el.attrs[name] = String(value); },
        getAttribute(name) { return Object.prototype.hasOwnProperty.call(el.attrs, name) ? el.attrs[name] : null; },
        hasAttribute(name) { return Object.prototype.hasOwnProperty.call(el.attrs, name); },
        removeAttribute(name) { delete el.attrs[name]; },
        addEventListener(type, fn) { el.listeners[type] = fn; },
        contains(other) { for (let n = other; n; n = n.parent) if (n === el) return true; return false; },
        focus() { el.doc.activeElement = el; },
        all() { return [el].concat(...el.children.map((c) => c.all())); },
    };
    return el;
}

/** A fake Leaflet: a map projecting `lng` to x and `-lat` to y at 100 px per degree at zoom 10, doubling per zoom. */
function fakeLeaflet(doc) {
    const calls = [];
    const listeners = {};
    const map = {
        zoom: 10,
        on(type, fn) { (listeners[type] = listeners[type] || []).push(fn); return map; },
        fire(type) { (listeners[type] || []).forEach((fn) => fn()); },
        setView(at, zoom) { calls.push(["setView", at, zoom]); map.fire("movestart"); map.zoom = zoom; map.fire("zoomend"); return map; },
        fitBounds(bounds, options) { calls.push(["fitBounds", bounds.length, options]); map.fire("movestart"); map.fire("zoomend"); return map; },
        getBoundsZoom(bounds, inside, pad) { calls.push(["boundsZoom", bounds.points.length, pad]); return map.boundsZoom; },
        boundsZoom: 14,
        latLngToLayerPoint([lat, lng]) { const s = 100 * Math.pow(2, map.zoom - 10); return { x: lng * s, y: -lat * s }; },
        getSize: () => ({ x: 390, y: 500 }),
    };
    const markers = [];
    const L = {
        calls,
        markers,
        map: (el, options) => { calls.push(["map", options]); return map; },
        tileLayer: () => ({ addTo() { return this; } }),
        control: { zoom: (options) => ({ addTo() { calls.push(["zoom", options.position]); return this; } }) },
        Control: {
            extend(proto) {
                function Ctor() {}
                Ctor.prototype.addTo = function (m) { calls.push(["control", proto.options.position, proto.onAdd(m)]); return this; };
                return Ctor;
            },
        },
        DomEvent: { disableClickPropagation: () => {} },
        point: (x, y) => ({ x, y }),
        latLngBounds: (points) => ({ points }),
        divIcon: (options) => options,
        circle: (at, options) => {
            const c = { at, options, removed: false };
            c.addTo = () => c;
            c.setLatLng = (p) => { c.at = p; return c; };
            c.setRadius = (r) => { c.options.radius = r; return c; };
            c.setStyle = (s) => { Object.assign(c.options, s); return c; };
            c.remove = () => { c.removed = true; return c; };
            calls.push(["circle", options.radius, options.dashArray || null]);
            return c;
        },
        marker: (at, options) => {
            const icon = element("div");
            icon.doc = doc;
            icon.appendChild(options.icon.html);
            const m = { at, options, icon, removed: false, open: false, content: null };
            m.addTo = () => m;
            m.getElement = () => icon;
            m.setLatLng = (p) => { m.at = p; return m; };
            m.setZIndexOffset = (z) => { m.options.zIndexOffset = z; return m; };
            m.bindPopup = (node, popupOptions) => { m.content = node; m.popupOptions = popupOptions; return m; };
            m.setPopupContent = (node) => { m.content = node; return m; };
            m.isPopupOpen = () => m.open;
            m.openPopup = () => { markers.forEach((o) => { o.open = false; }); m.open = true; return m; };
            m.remove = () => { m.removed = true; m.open = false; return m; };
            markers.push(m);
            return m;
        },
    };
    return { L, map };
}

const row = (id, lat, extra = {}) => ({
    id, name: `Phone ${id}`, initial: "P", tone: "tone-sky", href: `/devices/locate?device=${id}`,
    label: `Phone ${id}, 2 min ago, within 12 m`, show: `Show Phone ${id}`,
    lat, lng: 10, accuracy_m: 20, fresh: true, at: `2026-11-12T10:0${id}:00.000Z`, tag: "2 min",
    lines: ["Last seen 2 min ago", "Within 12 m"], ...extra,
});

function fakePage({ box = {}, answers = [], visibility = "visible" } = {}) {
    const doc = { activeElement: null, visibilityState: visibility, listeners: {} };
    doc.createElement = (tag) => { const el = element(tag); el.doc = doc; return el; };
    doc.addEventListener = (type, fn) => { doc.listeners[type] = fn; };
    const mapBox = doc.createElement("div");
    Object.assign(mapBox.attrs, { "data-locate-all": "", "data-lang": "nb", ...box });
    const fitButton = doc.createElement("button");
    fitButton.attrs = { "data-fit": "", hidden: "" };
    mapBox.querySelector = (sel) => (sel === "[data-fit]" ? fitButton : { id: "map" });
    const line = doc.createElement("p");
    Object.assign(line.attrs, { "data-w-answered": "{done} av {count} har svart", "data-w-answered-end": "{done} av {count} svarte" });
    line.textContent = "Spør 2 telefoner …";
    doc.querySelector = (sel) => (sel === "[data-locate-all]" ? mapBox : null);
    doc.getElementById = (id) => (id === "all_wait" ? line : null);
    const fetched = [];
    const timers = [];
    const replaced = [];
    let answer = 0;
    const win = {
        document: doc,
        getComputedStyle: () => ({ getPropertyValue: () => "#A64B25" }),
        fetch: (url) => {
            fetched.push(url);
            const rows = answers.length ? answers[Math.min(answer++, answers.length - 1)] : [];
            return Promise.resolve({ ok: true, json: () => Promise.resolve(JSON.parse(JSON.stringify(rows))) });
        },
        setInterval: (fn, ms) => { timers.push({ fn, ms, cleared: false }); return timers.length; },
        clearInterval: (id) => { timers[id - 1].cleared = true; },
        history: { replaceState: (state, title, url) => replaced.push(url) },
    };
    const { L, map } = fakeLeaflet(doc);
    return { win, doc, L, map, mapBox, fitButton, line, fetched, timers, replaced };
}

const settle = () => new Promise((resolve) => setImmediate(resolve));
const tick = async (timer) => { timer.fn(); await settle(); await settle(); };
const live = (L) => L.markers.filter((m) => !m.removed);

test("markers closer than one avatar are one group; zooming in splits them; the freshest leads", () => {
    const project = (zoom) => (r) => ({ x: r.lng * 100 * Math.pow(2, zoom - 10), y: -r.lat * 100 * Math.pow(2, zoom - 10) });
    const near = [row(1, 59.0, { fresh: false, at: "2026-11-12T07:00:00.000Z" }), row(2, 59.0 + 1 / 10, { at: "2026-11-12T10:00:00.000Z" })];
    // 10 px apart at zoom 10: one group, the fresh one first, at the mean of the two.
    const one = groups(near, project(10), JOIN_PX);
    assert.strictEqual(one.length, 1);
    assert.deepStrictEqual(one[0].members.map((m) => m.id), [2, 1]);
    assert.strictEqual(one[0].key, "1,2");
    assert.ok(Math.abs(one[0].lat - 59.05) < 1e-9);
    // 50 px apart: two.
    const far = [row(1, 59.0), row(2, 59.0 + 5 / 10)];
    assert.strictEqual(groups(far, project(10), JOIN_PX).length, 2);
    // Zoom in on the near pair (80 px at zoom 13): split.
    assert.deepStrictEqual(groups(near, project(13), JOIN_PX).map((g) => g.key), ["2", "1"]);
    // A row without a position has no marker.
    assert.strictEqual(groups([row(3, undefined)], project(10), JOIN_PX).length, 0);
});

test("the view: the world, one at 15, all fitted, the far old one left out, fit-all ignoring that", () => {
    assert.deepStrictEqual(fitPlan([{ id: 1, name: "x" }], () => 14, false), { center: [20, 0], zoom: 2 });
    assert.deepStrictEqual(fitPlan([row(1, 59.9)], () => 14, false), { center: [59.9, 10], zoom: 15 });
    const two = [row(1, 59.9), row(2, 59.8)];
    assert.deepStrictEqual(fitPlan(two, () => 14, false), { bounds: [[59.9, 10], [59.8, 10]] });
    // An old position far away: the fresh ones alone when the fit would need a zoom below 11.
    const farOld = [row(1, 59.9), row(2, 59.8), row(3, 40.0, { fresh: false })];
    let asked = null;
    const plan = fitPlan(farOld, (bounds) => { asked = bounds; return 6; }, false);
    assert.strictEqual(asked.length, 3);
    assert.deepStrictEqual(plan, { bounds: [[59.9, 10], [59.8, 10]] });
    assert.deepStrictEqual(fitPlan([row(1, 59.9), row(3, 40.0, { fresh: false })], () => 6, false), { center: [59.9, 10], zoom: 15 });
    // Not when none is fresh, nor when the fit is close enough, nor for fit-all.
    assert.strictEqual(fitPlan(farOld.map((r) => ({ ...r, fresh: false })), () => 6, false).bounds.length, 3);
    assert.strictEqual(fitPlan(farOld, () => 11, false).bounds.length, 3);
    assert.strictEqual(fitPlan(farOld, () => 6, true).bounds.length, 3);
});

test("a marker and a bubble hold the row's words as text, never as markup", () => {
    const doc = { createElement: (tag) => element(tag) };
    const jack = row(2, 59.9, { name: "<b>Jack</b>", show: "Vis <b>Jack</b>", lines: ["Sist sett for 3 t siden", "Innenfor 60 m · I sonen Skolen"], tag: "3 t", fresh: false });
    const node = fillMarker(doc, element("span"), [jack]);
    const all = node.all();
    assert.ok(!all.some((el) => el.tag === "b"));
    assert.strictEqual(node.children[0].className, "avatar tone-sky all-av-old");
    assert.strictEqual(node.children[1].className, "all-tag all-tag-old");
    assert.strictEqual(node.children[1].textContent, "3 t");
    const part = bubble(doc, [jack]).children[0];
    assert.ok(!part.all().some((el) => el.tag === "b"));
    // Exactly the row's words: the name, its lines, "Vis … ›" (the › is the stylesheet's).
    assert.deepStrictEqual(part.children.map((c) => c.textContent), ["<b>Jack</b>", "Sist sett for 3 t siden", "Innenfor 60 m · I sonen Skolen", "Vis <b>Jack</b>"]);
    const show = part.children[3];
    assert.strictEqual(show.tag, "a");
    assert.strictEqual(show.className, "all-show");
    assert.strictEqual(show.getAttribute("href"), "/devices/locate?device=2");
    // The script never builds markup from a string.
    const source = fs.readFileSync(path.join(__dirname, "../static/locate-all.js"), "utf8");
    assert.ok(!/innerHTML|insertAdjacentHTML|outerHTML/.test(source));
});

test("a group shows up to three avatars and +N, the freshest's tag, and a bubble part per phone", () => {
    const doc = { createElement: (tag) => element(tag) };
    const members = [1, 2, 3, 4, 5].map((id) => row(id, 59.9, { tag: `${id} min` }));
    const node = fillMarker(doc, element("span"), members);
    assert.deepStrictEqual(node.children.map((c) => c.className), ["avatar tone-sky", "avatar tone-sky", "avatar tone-sky", "avatar all-more", "all-tag"]);
    assert.strictEqual(node.children[3].textContent, "+2");
    assert.strictEqual(node.children[4].textContent, "1 min");
    const parts = bubble(doc, members.slice(0, 2)).children;
    assert.strictEqual(parts.length, 2);
    assert.deepStrictEqual(parts.map((p) => p.children[0].textContent), ["Phone 1", "Phone 2"]);
    assert.deepStrictEqual(parts.map((p) => p.children[p.children.length - 1].getAttribute("href")), ["/devices/locate?device=1", "/devices/locate?device=2"]);
});

test("the map: the zoom and fit-all at the bottom right, a marker per phone spoken as its chip, fitted once", async () => {
    const page = fakePage({ answers: [[row(1, 59.9), row(2, 59.0, { fresh: false }), { id: 3, name: "Lily", none: "no_fix" }]] });
    const api = install(page.win, page.L);
    await api.loaded;
    assert.deepStrictEqual(page.L.calls.find((c) => c[0] === "map")[1], { zoomControl: false });
    assert.deepStrictEqual(page.L.calls.filter((c) => c[0] === "zoom" || c[0] === "control").map((c) => c.slice(0, 2)), [["zoom", "bottomright"], ["control", "bottomright"]]);
    assert.ok(!page.fitButton.hasAttribute("hidden"));
    assert.strictEqual(page.fetched[0], "/devices/locate/phones.json?lang=nb");
    const markers = live(page.L);
    assert.strictEqual(markers.length, 2);
    assert.strictEqual(markers[0].getElement().getAttribute("role"), "button");
    assert.strictEqual(markers[0].getElement().getAttribute("aria-label"), "Phone 1, 2 min ago, within 12 m");
    assert.strictEqual(markers[0].options.keyboard, true);
    // Fresh over old.
    assert.ok(markers[0].options.zIndexOffset > markers[1].options.zIndexOffset);
    // The accuracy circles: solid when fresh, dashed when old.
    assert.deepStrictEqual(page.L.calls.filter((c) => c[0] === "circle"), [["circle", 20, null], ["circle", 20, "4 4"]]);
    const fits = page.L.calls.filter((c) => c[0] === "fitBounds");
    assert.strictEqual(fits.length, 1);
    assert.deepStrictEqual(fits[0][2].paddingTopLeft, [40, 64]);
    assert.deepStrictEqual(fits[0][2].paddingBottomRight, [56, 40]);
    // getBoundsZoom takes the fit's padding as one point, the corners' sums.
    assert.deepStrictEqual(page.L.calls.find((c) => c[0] === "boundsZoom")[2], { x: 96, y: 104 });
    // Fit-all fits everything again.
    page.fitButton.listeners.click();
    assert.strictEqual(page.L.calls.filter((c) => c[0] === "fitBounds").length, 2);
});

test("a refresh keeps the open bubble open with new words, no marker removed, the focus kept", async () => {
    const first = [row(1, 59.9), row(2, 59.0)];
    const later = [row(1, 59.9, { tag: "3 min", lines: ["Last seen 3 min ago"] }), row(2, 59.0)];
    const page = fakePage({ answers: [first, later] });
    const api = install(page.win, page.L);
    await api.loaded;
    const one = live(page.L).find((m) => m.content.children[0].children[0].textContent === "Phone 1");
    one.openPopup();
    one.getElement().focus();
    const refresh = page.timers.find((t) => t.ms === 60000);
    await tick(refresh);
    assert.strictEqual(page.L.markers.length, 2, "no marker made again");
    assert.ok(!one.removed && one.isPopupOpen());
    assert.strictEqual(one.content.children[0].children[1].textContent, "Last seen 3 min ago");
    assert.strictEqual(one.getElement().all().find((el) => el.className === "all-tag").textContent, "3 min");
    assert.strictEqual(page.doc.activeElement, one.getElement());
});

test("a regroup on zoom keeps the bubble open and the focus on the marker now holding that phone", async () => {
    // 10 px apart at zoom 10: one group; at zoom 13 two markers.
    const page = fakePage({ answers: [[row(1, 59.9), row(2, 59.9 + 1 / 10, { fresh: false })]] });
    page.map.boundsZoom = 10;
    const api = install(page.win, page.L);
    await api.loaded;
    page.map.zoom = 10;
    api.regroup();
    const group = live(page.L).find((m) => m.content.children.length === 2);
    assert.ok(group, "one group marker with a part per phone");
    group.openPopup();
    group.getElement().focus();
    page.map.zoom = 13;
    page.map.fire("zoomend");
    assert.ok(group.removed);
    const holder = live(page.L).find((m) => m.content.children[0].children[0].textContent === "Phone 1");
    assert.ok(holder.isPopupOpen(), "the bubble moved to the marker holding its first phone");
    assert.strictEqual(page.doc.activeElement, holder.getElement());
    // No new fit: the parent's view stays.
    assert.strictEqual(page.L.calls.filter((c) => c[0] === "fitBounds" || c[0] === "setView").length, 1);
});

test("the 60 s refresh asks only while the page is visible, and again when it shows", async () => {
    const page = fakePage({ answers: [[row(1, 59.9)]], visibility: "hidden" });
    const api = install(page.win, page.L);
    await api.loaded;
    const refresh = page.timers.find((t) => t.ms === 60000);
    await tick(refresh);
    assert.strictEqual(page.fetched.length, 1);
    page.doc.visibilityState = "visible";
    page.doc.listeners.visibilitychange();
    await settle();
    assert.strictEqual(page.fetched.length, 2);
    await tick(refresh);
    assert.strictEqual(page.fetched.length, 3);
});

test("after Update All: every 5 s, the answers counted, stopped after 12, the address cleaned, one fit", async () => {
    const asking = [row(1, 59.9, { answered: false }), row(2, 59.0, { answered: false }), { id: 3, name: "Lily", none: "not_set_up" }];
    const one = [row(1, 59.9, { answered: true }), row(2, 59.0, { answered: false }), { id: 3, name: "Lily", none: "not_set_up" }];
    const page = fakePage({ box: { "data-waiting": "", "data-at": "2026-11-12T10:00:00.000Z" }, answers: [asking, one] });
    const api = install(page.win, page.L);
    await api.loaded;
    assert.strictEqual(page.fetched[0], "/devices/locate/phones.json?lang=nb&at=2026-11-12T10%3A00%3A00.000Z");
    const wait = page.timers.find((t) => t.ms === 5000);
    await tick(wait);
    assert.strictEqual(page.line.textContent, "1 av 2 har svart");
    for (let i = 2; i <= 11; i++) await tick(wait);
    assert.ok(!wait.cleared);
    assert.deepStrictEqual(page.replaced, []);
    await tick(wait);
    assert.ok(wait.cleared);
    assert.strictEqual(page.line.textContent, "1 av 2 svarte");
    assert.deepStrictEqual(page.replaced, ["/devices/locate#all"]);
    assert.strictEqual(page.fetched.length, 13);
    // Fitted at load and once at the end; later asks drop `at`.
    assert.strictEqual(page.L.calls.filter((c) => c[0] === "fitBounds").length, 2);
    await tick(page.timers.find((t) => t.ms === 60000));
    assert.strictEqual(page.fetched[13], "/devices/locate/phones.json?lang=nb");
});

test("a wait ends early when every phone answered, and the parent's moved map isn't fitted", async () => {
    const done = [row(1, 59.9, { answered: true }), row(2, 59.0, { answered: true })];
    const page = fakePage({ box: { "data-waiting": "", "data-at": "2026-11-12T10:00:00.000Z" }, answers: [done] });
    const api = install(page.win, page.L);
    await api.loaded;
    page.map.fire("movestart");
    const wait = page.timers.find((t) => t.ms === 5000);
    await tick(wait);
    assert.ok(wait.cleared);
    assert.strictEqual(page.line.textContent, "2 av 2 svarte");
    assert.deepStrictEqual(page.replaced, ["/devices/locate#all"]);
    assert.strictEqual(page.L.calls.filter((c) => c[0] === "fitBounds").length, 1);
});

test("a hidden page neither asks nor counts a try; a failed last ask still ends with the last counts", async () => {
    const asking = [row(1, 59.9, { answered: false }), row(2, 59.0, { answered: false })];
    const one = [row(1, 59.9, { answered: true }), row(2, 59.0, { answered: false })];
    // The load, the first try, then every later ask fails (an answer that isn't a list).
    const page = fakePage({ box: { "data-waiting": "", "data-at": "2026-11-12T10:00:00.000Z" }, answers: [asking, one, null] });
    const api = install(page.win, page.L);
    await api.loaded;
    const wait = page.timers.find((t) => t.ms === 5000);
    page.doc.visibilityState = "hidden";
    for (let i = 0; i < 20; i++) await tick(wait);
    assert.strictEqual(page.fetched.length, 1);
    assert.ok(!wait.cleared);
    page.doc.visibilityState = "visible";
    await tick(wait);
    assert.strictEqual(page.line.textContent, "1 av 2 har svart");
    for (let i = 2; i <= 11; i++) await tick(wait);
    assert.strictEqual(page.line.textContent, "1 av 2 har svart", "a failed ask leaves the line");
    await tick(wait);
    assert.ok(wait.cleared);
    assert.strictEqual(page.line.textContent, "1 av 2 svarte");
    assert.deepStrictEqual(page.replaced, ["/devices/locate#all"]);
});
