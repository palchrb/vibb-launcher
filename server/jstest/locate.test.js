// Behaviour of static/locate.js (design 44): the span pick, the trail and its count, the
// Locations rows. Run: node --test jstest/locate.test.js
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const { install, countText, readSpan, trailColour, SPAN_KEY } = require("../static/locate.js");

/** A fake Leaflet that records what was drawn. */
function fakeLeaflet() {
    const calls = [];
    const layer = () => ({
        addTo(target) { calls.push(["addTo", this.kind]); this.target = target; return this; },
    });
    const map = {
        zoom: 0,
        setView: (at, zoom) => { calls.push(["setView", at, zoom]); map.zoom = zoom; return map; },
        fitBounds: (all) => calls.push(["fitBounds", all.length]),
        getZoom: () => map.zoom,
    };
    const L = {
        calls,
        map: (el, options) => { calls.push(["map", options]); return map; },
        tileLayer: (url, options) => Object.assign(layer(), { kind: "tiles", url, options }),
        Icon: { Default: {} },
        layerGroup: () => Object.assign(layer(), { kind: "group", clearLayers: () => calls.push(["clear"]) }),
        polyline: (points) => { calls.push(["polyline", points.length]); return Object.assign(layer(), { kind: "line" }); },
        circleMarker: (at, options) => { calls.push(["dot", options.radius]); return Object.assign(layer(), { kind: "dot" }); },
        marker: (at) => {
            calls.push(["marker", at]);
            const m = Object.assign(layer(), { kind: "marker" });
            m.bindPopup = (node) => { calls.push(["popup", node.textContent]); return m; };
            m.openPopup = () => m;
            return m;
        },
        popup: () => {
            const p = {};
            p.setLatLng = (at) => { calls.push(["popupAt", at]); return p; };
            p.setContent = (node) => { calls.push(["popupText", node.textContent]); return p; };
            p.openOn = () => p;
            return p;
        },
    };
    return L;
}

function fakeAttrs(attrs) {
    return {
        attrs,
        listeners: {},
        getAttribute(name) { return Object.prototype.hasOwnProperty.call(this.attrs, name) ? this.attrs[name] : null; },
        setAttribute(name, value) { this.attrs[name] = String(value); },
        hasAttribute(name) { return Object.prototype.hasOwnProperty.call(this.attrs, name); },
        addEventListener(type, fn) { this.listeners[type] = fn; },
    };
}

/**
 * A fake page: the map box with `box` attributes, the span pick, the Locations row (Locate) or
 * the fix rows (Locations), `answers` maps a fetched URL to its JSON.
 */
function fakePage({ box = {}, search = "", stored = null, answers = {}, fixes = [] } = {}) {
    const fetched = [];
    const store = new Map();
    if (stored !== null) store.set(SPAN_KEY, stored);
    const mapBox = fakeAttrs({ "data-locate": "3", "data-shown": "2026-10-20T10:00:00.000Z", "data-shown-text": "10 min ago · within 40 m", "data-newest-age": "3 days ago", "data-span": "12", ...box });
    const scrolled = [];
    mapBox.scrollIntoView = (options) => scrolled.push(options);
    mapBox.querySelector = () => ({ id: "map" });
    const links = ["12", "24", "168", "0"].map((s) => {
        const a = fakeAttrs({ "data-span": s, role: "radio", "aria-checked": s === mapBox.attrs["data-span"] ? "true" : "false", href: `/x?span=${s}` });
        a.closest = () => a;
        return a;
    });
    const pick = fakeAttrs({ "data-span-pick": "" });
    pick.querySelectorAll = () => links;
    const count = { textContent: "server's" };
    const row = fakeAttrs({ "data-row": "locations", href: "/devices/3/locations?span=12" });
    const fixRows = fixes.map((f) => fakeAttrs({ "data-fix": "", "data-lat": f[0], "data-lng": f[1], "data-text": f[2] }));
    const replaced = [];
    const doc = {
        querySelector: (selector) => ({
            "[data-locate]": mapBox,
            "[data-span-pick]": pick,
            "[data-locations-count]": mapBox.hasAttribute("data-list") ? null : count,
            '[data-row="locations"]': mapBox.hasAttribute("data-list") ? null : row,
        })[selector] || null,
        querySelectorAll: (selector) => (selector === "[data-fix]" ? fixRows : []),
        getElementById: () => null,
        createElement: () => ({ textContent: "" }),
    };
    const win = {
        document: doc,
        location: { search, replace: (url) => replaced.push(url) },
        localStorage: {
            getItem: (k) => (store.has(k) ? store.get(k) : null),
            setItem: (k, v) => store.set(k, v),
        },
        fetch: (url) => {
            fetched.push(url);
            const answer = Object.prototype.hasOwnProperty.call(answers, url) ? answers[url] : [];
            return Promise.resolve({ ok: true, json: () => Promise.resolve(answer) });
        },
        setInterval: () => 1,
        clearInterval: () => {},
    };
    return { win, mapBox, links, pick, count, row, fixRows, fetched, store, replaced, scrolled };
}

const point = (lat, at, newest) => ({ latitude: lat, longitude: 10, captured_at: at, ...(newest ? { newest: true } : {}) });
const settle = () => new Promise((resolve) => setImmediate(resolve));

test("the count is worded as the server words it; the newest outside the span isn't counted", () => {
    const two = [point(1, "a"), point(2, "b")];
    assert.strictEqual(countText(two, "12", "x"), "2 in the last 12 hours");
    assert.strictEqual(countText(two, "168", "x"), "2 in the last 7 days");
    assert.strictEqual(countText(two, "0", "x"), "2 kept");
    assert.strictEqual(countText([point(1, "a", true)], "24", "3 days ago"), "0 in the last 24 hours · newest 3 days ago");
    assert.strictEqual(countText([], "12", ""), "None yet");
});

test("the remembered span reads back only when it is one of the four", () => {
    const store = (value) => ({ getItem: () => value });
    assert.strictEqual(readSpan(store("168"), "12"), "168");
    assert.strictEqual(readSpan(store("0"), "12"), "0");
    assert.strictEqual(readSpan(store("5"), "12"), "12");
    assert.strictEqual(readSpan(store(null), "12"), "12");
    assert.strictEqual(readSpan({ getItem: () => { throw new Error("blocked"); } }, "24"), "24");
});

test("the map loads the span's points, draws the trail with a dot per fix and the newest as the marker", async () => {
    const L = fakeLeaflet();
    const page = fakePage({
        answers: {
            "/devices/3/locations.json?since=12": [point(1, "a"), point(2, "b"), point(3, "2026-10-20T10:00:00.000Z")],
        },
    });
    install(page.win, L);
    await settle();
    assert.deepStrictEqual(page.fetched, ["/devices/3/locations.json?since=12"]);
    assert.deepStrictEqual(L.calls[0], ["map", { preferCanvas: true }]);
    assert.strictEqual(L.Icon.Default.imagePath, "/static/leaflet/images/");
    const drawn = L.calls.filter((c) => ["polyline", "dot", "marker", "popup", "fitBounds"].includes(c[0]));
    assert.deepStrictEqual(drawn, [
        ["polyline", 3],
        ["dot", 3],
        ["dot", 3],
        ["marker", [3, 10]],
        ["popup", "10 min ago · within 40 m"],
        ["fitBounds", 3],
    ]);
    assert.strictEqual(page.count.textContent, "3 in the last 12 hours");
});

test("the newest fix outside the span keeps the marker, joins no trail and isn't counted", async () => {
    const L = fakeLeaflet();
    const page = fakePage({ answers: { "/devices/3/locations.json?since=12": [point(9, "old", true)] } });
    install(page.win, L);
    await settle();
    const drawn = L.calls.filter((c) => ["polyline", "dot", "marker", "popup"].includes(c[0]));
    assert.deepStrictEqual(drawn, [["polyline", 0], ["marker", [9, 10]], ["popup", "Newest location"]]);
    assert.strictEqual(page.count.textContent, "0 in the last 12 hours · newest 3 days ago");
});

test("the pick stores the span, fetches with since and redraws in place", async () => {
    const L = fakeLeaflet();
    const page = fakePage({
        answers: { "/devices/3/locations.json?since=168": [point(1, "a"), point(2, "b")] },
    });
    install(page.win, L);
    await settle();
    let prevented = false;
    page.pick.listeners.click({ target: page.links[2], preventDefault: () => { prevented = true; } });
    await settle();
    assert.ok(prevented, "no navigation, nothing that can jump");
    assert.strictEqual(page.store.get(SPAN_KEY), "168");
    assert.deepStrictEqual(page.fetched, ["/devices/3/locations.json?since=12", "/devices/3/locations.json?since=168"]);
    assert.deepStrictEqual(page.links.map((a) => a.getAttribute("aria-checked")), ["false", "false", "true", "false"]);
    assert.deepStrictEqual(page.links.map((a) => a.getAttribute("role")), ["radio", "radio", "radio", "radio"]);
    assert.strictEqual(page.count.textContent, "2 in the last 7 days");
    assert.strictEqual(page.row.getAttribute("href"), "/devices/3/locations?span=168");
    assert.ok(L.calls.filter((c) => c[0] === "clear").length >= 2, "the old trail went");
});

test("a remembered span is shown on the next visit; the address's span wins and is remembered", async () => {
    const L = fakeLeaflet();
    const page = fakePage({ stored: "0" });
    install(page.win, L);
    await settle();
    assert.deepStrictEqual(page.fetched, ["/devices/3/locations.json?since=0"]);
    assert.strictEqual(page.links[3].getAttribute("aria-checked"), "true");
    const linked = fakePage({ stored: "0", search: "?device=3&span=12" });
    install(linked.win, fakeLeaflet());
    await settle();
    assert.deepStrictEqual(linked.fetched, ["/devices/3/locations.json?since=12"]);
    assert.strictEqual(linked.store.get(SPAN_KEY), "12");
});

test("on the Locations page the pick's link opens the page; a remembered span reopens it", async () => {
    const page = fakePage({ box: { "data-list": "" } });
    install(page.win, fakeLeaflet());
    await settle();
    let prevented = false;
    page.pick.listeners.click({ target: page.links[1], preventDefault: () => { prevented = true; } });
    // The page opens with its span in place of this entry: Back goes to Locate (qa-r5-a-code N1).
    assert.ok(prevented, "no new history entry");
    assert.deepStrictEqual(page.replaced, ["/x?span=24"]);
    assert.strictEqual(page.store.get(SPAN_KEY), "24");
    const remembered = fakePage({ box: { "data-list": "" }, stored: "24" });
    assert.strictEqual(install(remembered.win, fakeLeaflet()), null);
    assert.deepStrictEqual(remembered.replaced, ["/x?span=24"]);
    assert.deepStrictEqual(remembered.fetched, []);
});

test("a Locations row scrolls the map into view, then pans to its fix with its words", async () => {
    const L = fakeLeaflet();
    const page = fakePage({ box: { "data-list": "" }, fixes: [["59.91", "10.75", "10:58 · ± 25 m"]] });
    install(page.win, L);
    await settle();
    const row = page.fixRows[0];
    assert.strictEqual(row.getAttribute("role"), "button");
    assert.strictEqual(row.getAttribute("tabindex"), "0");
    row.listeners.click();
    // No <main> scroller (a page whose window scrolls): scrollIntoView.
    assert.deepStrictEqual(page.scrolled, [{ block: "start" }]);
    const after = L.calls.slice(L.calls.findIndex((c) => c[0] === "popupAt") - 1);
    assert.deepStrictEqual(after, [
        ["setView", [59.91, 10.75], 16],
        ["popupAt", [59.91, 10.75]],
        ["popupText", "10:58 · ± 25 m"],
    ]);
    // The keyboard too.
    let prevented = false;
    row.listeners.keydown({ key: "Enter", preventDefault: () => { prevented = true; } });
    assert.ok(prevented);
    assert.strictEqual(page.scrolled.length, 2);
});

// In the shell the map's top goes to the top of <main>'s padding (under the status bar) by an
// offset in the scroll, not scroll-margin-top (Safari 14.1; the floor is 14.0).
test("in the shell a Locations row scrolls <main> to put the map under its top padding", async () => {
    const page = fakePage({ box: { "data-list": "" }, fixes: [["1", "2", "t"]] });
    const main = { scrollTop: 900, getBoundingClientRect: () => ({ top: 0 }) };
    page.mapBox.getBoundingClientRect = () => ({ top: 60 - (main.scrollTop - 900) - 900 });
    const query = page.win.document.querySelector;
    page.win.document.querySelector = (selector) => (selector === "main" ? main : query(selector));
    page.win.getComputedStyle = () => ({ overflowY: "auto", paddingTop: "55px" });
    install(page.win, fakeLeaflet());
    await settle();
    page.fixRows[0].listeners.click();
    // The box was 840 px above the screen's top: it now sits 55 px down, at main's padding.
    assert.strictEqual(main.scrollTop, 900 - 840 - 55);
    assert.deepStrictEqual(page.scrolled, [], "no scrollIntoView");
});

test("the trail takes the page's --map-trail, else vibb's dark peach", () => {
    const win = (value) => ({
        document: { documentElement: {} },
        getComputedStyle: () => ({ getPropertyValue: (name) => (name === "--map-trail" ? value : "") }),
    });
    assert.strictEqual(trailColour(win(" #123456")), "#123456");
    assert.strictEqual(trailColour(win("")), "#A64B25");
    assert.strictEqual(trailColour({ document: { documentElement: {} } }), "#A64B25");
});

test("no map box or no Leaflet: nothing happens", () => {
    const page = fakePage();
    page.win.document.querySelector = () => null;
    assert.strictEqual(install(page.win, fakeLeaflet()), null);
    assert.strictEqual(install(fakePage().win, undefined), null);
});
