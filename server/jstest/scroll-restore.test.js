// Behaviour of static/scroll-restore.js (qa-fixround-2026-10-06 #5, the PWA browser pass of
// 2026-10-14). Run: node --test jstest/
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const { install, restoreTarget, KEY, MAX_AGE_MS } = require("../static/scroll-restore.js");

/** An element with an id (or "") at `top` on the screen, inside `parent`. */
function fakeElement(id, top, parent = null) {
    return { id, parentNode: parent, getBoundingClientRect: () => ({ top }) };
}

// The page that came back: `elements` maps an id to its top in the document, or to [top, the id
// of the element it is in].
function byId(elements, id) {
    if (!(id in elements)) return null;
    const [top, parent] = Array.isArray(elements[id]) ? elements[id] : [elements[id], null];
    return fakeElement(id, top, parent ? byId(elements, parent) : null);
}

function fakeWindow({ path = "/devices/1", hash = "", scrollY = 0, stored = null, readyState = "complete", elements = {} } = {}) {
    const store = new Map();
    if (stored) store.set(KEY, JSON.stringify(stored));
    const listeners = {};
    const winListeners = {};
    const scrolls = [];
    const nativeSubmits = [];
    function HTMLFormElement() {}
    HTMLFormElement.prototype.submit = function () { nativeSubmits.push(this); };
    const win = {
        location: { pathname: path, hash },
        scrollY,
        history: { scrollRestoration: "auto" },
        sessionStorage: {
            getItem: (k) => (store.has(k) ? store.get(k) : null),
            setItem: (k, v) => store.set(k, v),
            removeItem: (k) => store.delete(k),
        },
        HTMLFormElement,
        scrollTo: (x, y) => scrolls.push([x, y]),
        addEventListener: (type, fn) => { winListeners[type] = fn; },
        document: {
            readyState,
            addEventListener: (type, fn) => { listeners[type] = fn; },
            getElementById: (id) => byId(elements, id),
        },
    };
    return { win, store, listeners, winListeners, scrolls, nativeSubmits };
}

// A form in a row in a group in <main>: only the group has an id.
function formInGroup() {
    const main = fakeElement("", -900);
    const group = fakeElement("music-settings", 120, main);
    const row = fakeElement("", 180, group);
    return fakeElement("", 180, row);
}

test("a submit remembers path, position, time and where the form's groups are on the screen", () => {
    const w = fakeWindow({ scrollY: 840 });
    install(w.win, () => 1000);
    w.listeners.submit({ target: formInGroup() });
    assert.deepStrictEqual(JSON.parse(w.store.get(KEY)), {
        path: "/devices/1", y: 840, t: 1000, tops: { "music-settings": 120 },
    });
});

test("form.submit() (the auto-saving controls) remembers too and still submits", () => {
    const w = fakeWindow({ scrollY: 300 });
    install(w.win, () => 5);
    const form = new w.win.HTMLFormElement();
    form.id = "lock-screen-app-x";
    form.parentNode = fakeElement("notifications", 40);
    form.getBoundingClientRect = () => ({ top: 90 });
    form.submit();
    assert.strictEqual(w.nativeSubmits.length, 1);
    assert.strictEqual(w.nativeSubmits[0], form);
    const saved = JSON.parse(w.store.get(KEY));
    assert.strictEqual(saved.y, 300);
    assert.deepStrictEqual(saved.tops, { "lock-screen-app-x": 90, notifications: 40 });
});

test("the page that comes back scrolls to the saved position, once", () => {
    const w = fakeWindow({ stored: { path: "/devices/1", y: 840, t: 1000 } });
    install(w.win, () => 3000);
    assert.deepStrictEqual(w.scrolls, [[0, 840]]);
    assert.strictEqual(w.win.history.scrollRestoration, "manual");
    assert.ok(!w.store.has(KEY), "consumed");
    w.winListeners.load();
    assert.deepStrictEqual(w.scrolls, [[0, 840], [0, 840]]);
});

test("still loading: restores at DOMContentLoaded", () => {
    const w = fakeWindow({ stored: { path: "/devices/1", y: 10, t: 0 }, readyState: "loading" });
    install(w.win, () => 1);
    assert.deepStrictEqual(w.scrolls, []);
    w.listeners.DOMContentLoaded();
    assert.deepStrictEqual(w.scrolls, [[0, 10]]);
});

// Every form redirects to its own group's #fragment, the no-JS fallback (§5.1.4); with JS the place
// is kept over it: a save never moves the page.
test("a #fragment naming the form's own group keeps that group where it was on the screen", () => {
    const stored = { path: "/devices/1", y: 840, t: 0, tops: { "music-settings": 120 } };
    // Nothing above it changed (the group is at 960 in the document): the same position.
    const same = fakeWindow({ hash: "#music-settings", stored, elements: { "music-settings": 960 } });
    install(same.win, () => 100);
    assert.deepStrictEqual(same.scrolls, [[0, 840]]);
    assert.strictEqual(same.win.history.scrollRestoration, "manual");
    // The save moved it (an app switched to the other group): it stays under the finger.
    const moved = fakeWindow({ hash: "#music-settings", stored, elements: { "music-settings": 1500 } });
    install(moved.win, () => 100);
    assert.deepStrictEqual(moved.scrolls, [[0, 1380]]);
    moved.winListeners.load();
    assert.deepStrictEqual(moved.scrolls, [[0, 1380], [0, 1380]]);
    // Not on the page that came back: the saved position.
    const gone = fakeWindow({ hash: "#music-settings", stored });
    install(gone.win, () => 100);
    assert.deepStrictEqual(gone.scrolls, [[0, 840]]);
    // A percent-encoded id.
    const encoded = fakeWindow({
        hash: "#app-%C3%A5", stored: { ...stored, tops: { "app-å": 10 } }, elements: { "app-å": 410 },
    });
    install(encoded.win, () => 100);
    assert.deepStrictEqual(encoded.scrolls, [[0, 400]]);
});

test("a #fragment inside the form's group (a new row, a deleted row's neighbour) keeps the group", () => {
    // Add Contact (in #add-contact, in #contacts) lands on the new row, #contact-9: #contacts,
    // the nearest element around both, stays where it was on the screen.
    const stored = { path: "/devices/1/calls", y: 900, t: 0, tops: { "add-contact": 500, contacts: 100 } };
    const w = fakeWindow({
        path: "/devices/1/calls",
        hash: "#contact-9",
        stored,
        elements: { contacts: 1000, "contact-9": [1400, "contacts"], "add-contact": [1480, "contacts"] },
    });
    install(w.win, () => 100);
    assert.deepStrictEqual(w.scrolls, [[0, 900]]);
    assert.strictEqual(w.win.history.scrollRestoration, "manual");
});

test("a #fragment naming anything else leaves the browser at that anchor", () => {
    // A row of another group (a photo uploaded in #add lands on its row in #all), an id the page
    // doesn't have, an inherited property name, a record without groups (auto-reload.js's: it
    // hands the place to the browser) or a bad one.
    for (const opts of [
        { hash: "#wallpaper-9", stored: { path: "/devices/1", y: 5, t: 0, tops: { add: 50 } } },
        { hash: "#nowhere", stored: { path: "/devices/1", y: 5, t: 0, tops: { add: 50 } } },
        { hash: "#toString", stored: { path: "/devices/1", y: 5, t: 0, tops: {} } },
        { hash: "#rules", stored: { path: "/devices/1", y: 5, t: 0 } },
        { hash: "#rules", stored: { path: "/devices/1", y: 5, t: 0, tops: { rules: "50" } } },
    ]) {
        const w = fakeWindow({ ...opts, elements: { all: 600, "wallpaper-9": [700, "all"], add: 300, rules: 300, toString: 1 } });
        install(w.win, () => 100);
        assert.deepStrictEqual(w.scrolls, [], JSON.stringify(opts));
        assert.strictEqual(w.win.history.scrollRestoration, "auto");
        assert.ok(!w.store.has(KEY), "consumed even when not used");
    }
});

test("another path or an old save don't restore", () => {
    for (const opts of [
        { path: "/devices/2", stored: { path: "/devices/1", y: 5, t: 0 } },
        { path: "/devices/2", hash: "#a", stored: { path: "/devices/1", y: 5, t: 0, tops: { a: 0 } } },
    ]) {
        const w = fakeWindow({ ...opts, elements: { a: 0 } });
        install(w.win, () => 100);
        assert.deepStrictEqual(w.scrolls, [], JSON.stringify(opts));
        assert.ok(!w.store.has(KEY), "consumed even when not used");
    }
    const old = fakeWindow({ stored: { path: "/devices/1", y: 5, t: 0 } });
    install(old.win, () => MAX_AGE_MS);
    assert.deepStrictEqual(old.scrolls, []);
});

test("restoreTarget rejects garbage and clock jumps", () => {
    assert.strictEqual(restoreTarget(null, "/", "", 0), null);
    assert.strictEqual(restoreTarget({ path: "/", y: "1", t: 0 }, "/", "", 0), null);
    assert.strictEqual(restoreTarget({ path: "/", y: 1, t: 10 }, "/", "", 5), null);
    assert.strictEqual(restoreTarget({ path: "/", y: 1, t: 0 }, "/", "", MAX_AGE_MS - 1), 1);
    assert.strictEqual(restoreTarget({ path: "/", y: 7, t: 0 }, "/", "#", 0), 7, "an empty # is none");
});

test("no sessionStorage (private mode): nothing breaks", () => {
    const w = fakeWindow();
    Object.defineProperty(w.win, "sessionStorage", { get() { throw new Error("denied"); } });
    install(w.win, () => 0);
    w.listeners.submit({ target: formInGroup() });
    assert.deepStrictEqual(w.scrolls, []);
});
