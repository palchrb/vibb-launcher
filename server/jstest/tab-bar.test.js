// Behaviour of static/tab-bar.js: the bar is laid out again when the page comes back, once per
// frame, and nothing else is touched (no scroll).
// Run: node --test jstest/tab-bar.test.js
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const { install } = require("../static/tab-bar.js");

function fakeWindow({ bar = true, standalone = true } = {}) {
    const listeners = {};
    const frames = [];
    const tabBar = {
        displays: [],
        style: {
            set display(v) {
                tabBar.displays.push(v);
            },
        },
        get offsetHeight() {
            tabBar.displays.push("read");
            return 57;
        },
    };
    const win = {
        frames,
        tabBar,
        scrolled: 0,
        scrollTo: () => {
            win.scrolled += 1;
        },
        document: {
            visibilityState: "visible",
            addEventListener: (type, fn) => {
                (listeners["doc:" + type] = listeners["doc:" + type] || []).push(fn);
            },
            querySelector: (selector) => (selector === ".tab-bar" && bar ? tabBar : null),
        },
        addEventListener: (type, fn) => {
            (listeners["win:" + type] = listeners["win:" + type] || []).push(fn);
        },
        matchMedia: (query) => ({
            matches: query === "(display-mode: standalone)" && standalone,
        }),
        requestAnimationFrame: (fn) => {
            frames.push(fn);
        },
        fire: (key) => (listeners[key] || []).forEach((fn) => fn({})),
        runFrames: () => frames.splice(0).forEach((fn) => fn()),
    };
    return win;
}

test("a resume lays the bar out again, hidden and back within one frame", () => {
    const win = fakeWindow();
    install(win);
    win.fire("win:pageshow");
    win.runFrames();
    assert.deepStrictEqual(win.tabBar.displays, ["none", "read", ""]);
    assert.strictEqual(win.scrolled, 0);
});

test("several events in one frame lay it out once", () => {
    const win = fakeWindow();
    install(win);
    win.fire("win:resize");
    win.fire("win:resize");
    win.fire("doc:visibilitychange");
    assert.strictEqual(win.frames.length, 1);
    win.runFrames();
    assert.deepStrictEqual(win.tabBar.displays, ["none", "read", ""]);
    win.fire("win:resize");
    assert.strictEqual(win.frames.length, 1);
});

test("going to the background does nothing; a page without the bar is fine", () => {
    const win = fakeWindow();
    install(win);
    win.document.visibilityState = "hidden";
    win.fire("doc:visibilitychange");
    assert.strictEqual(win.frames.length, 0);

    const bare = fakeWindow({ bar: false });
    install(bare);
    bare.fire("win:pageshow");
    assert.doesNotThrow(() => bare.runFrames());
});

test("in Safari (not installed) a resize does nothing; a pageshow still lays the bar out", () => {
    const win = fakeWindow({ standalone: false });
    install(win);
    win.fire("win:resize");
    assert.strictEqual(win.frames.length, 0);
    win.fire("win:pageshow");
    win.runFrames();
    assert.deepStrictEqual(win.tabBar.displays, ["none", "read", ""]);
});
