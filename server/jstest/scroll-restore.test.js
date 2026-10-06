// Behaviour of static/scroll-restore.js (qa-fixround-2026-10-06 #5). Run: node --test jstest/
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const { install, restoreTarget, KEY, MAX_AGE_MS } = require("../static/scroll-restore.js");

function fakeWindow({ path = "/devices/1", hash = "", scrollY = 0, stored = null, readyState = "complete" } = {}) {
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
        },
    };
    return { win, store, listeners, winListeners, scrolls, nativeSubmits };
}

test("a submit remembers path, position and time", () => {
    const w = fakeWindow({ scrollY: 840 });
    install(w.win, () => 1000);
    w.listeners.submit();
    assert.deepStrictEqual(JSON.parse(w.store.get(KEY)), { path: "/devices/1", y: 840, t: 1000 });
});

test("form.submit() (the auto-saving controls) remembers too and still submits", () => {
    const w = fakeWindow({ scrollY: 300 });
    install(w.win, () => 5);
    const form = new w.win.HTMLFormElement();
    form.submit();
    assert.strictEqual(w.nativeSubmits.length, 1);
    assert.strictEqual(w.nativeSubmits[0], form);
    assert.strictEqual(JSON.parse(w.store.get(KEY)).y, 300);
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

test("another path, a #fragment or an old save don't restore", () => {
    for (const opts of [
        { path: "/devices/2", stored: { path: "/devices/1", y: 5, t: 0 } },
        { hash: "#screen-lock", stored: { path: "/devices/1", y: 5, t: 0 } },
    ]) {
        const w = fakeWindow(opts);
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
});

test("no sessionStorage (private mode): nothing breaks", () => {
    const w = fakeWindow();
    Object.defineProperty(w.win, "sessionStorage", { get() { throw new Error("denied"); } });
    install(w.win, () => 0);
    w.listeners.submit();
    assert.deepStrictEqual(w.scrolls, []);
});
