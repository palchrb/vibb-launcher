// Behaviour of static/pull-refresh.js (2026-10-25: pull down to refresh on Devices and a phone's
// summary), as touch timelines. Run: node --test jstest/pull-refresh.test.js
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const pr = require("../static/pull-refresh.js");
const { install, shiftOf, THRESHOLD, START_AFTER, MAX_SHIFT } = pr;

// A fake window: <main> as the scroller, listeners fired by hand (with their options kept), a
// view that records what is shown, and reconnect.js's handyReconnect answering as told.
function fakeWindow({ scrollTop = 0, shared = true } = {}) {
    const listeners = {};
    const win = {
        main: { scrollTop },
        openForm: false,
        reloads: 0,
        shown: [],
        mayReload: true,
        asked: 0,
        getComputedStyle: () => ({ overflowY: "auto" }),
        addEventListener: (type, fn) => {
            (listeners["win:" + type] = listeners["win:" + type] || []).push({ fn });
        },
        location: {
            reload: () => {
                win.reloads += 1;
            },
        },
        document: {
            addEventListener: (type, fn, options) => {
                (listeners["doc:" + type] = listeners["doc:" + type] || []).push({ fn, options });
            },
            querySelector: (selector) => {
                if (selector === "main") return win.main;
                if (selector === "details[open]") return win.openForm ? {} : null;
                return null;
            },
        },
    };
    if (shared) {
        win.handyReconnect = {
            mayReload: () => win.mayReload,
            reload: () => {
                win.asked += 1;
                return win.mayReload;
            },
        };
    }
    win.options = (type) => listeners["doc:" + type][0].options;
    win.fire = (where, type, event) => {
        (listeners[where + ":" + type] || []).forEach((l) => l.fn(event || { type }));
    };
    // One touch event; returns whether it was preventDefault()ed.
    win.touch = (type, points) => {
        let prevented = false;
        win.fire("doc", type, {
            type,
            cancelable: true,
            touches: points.map(([x, y]) => ({ clientX: x, clientY: y })),
            preventDefault: () => {
                prevented = true;
            },
        });
        return prevented;
    };
    // A finger down at (x, y0), moved through every y in `ys`, then lifted.
    win.drag = (x, y0, ys, { lift = true } = {}) => {
        const prevented = [];
        win.touch("touchstart", [[x, y0]]);
        for (const y of ys) prevented.push(win.touch("touchmove", [[x, y]]));
        if (lift) win.touch("touchend", []);
        return prevented;
    };
    win.install = () =>
        install(win, {
            view: {
                pull: (shift, armed) => win.shown.push(armed ? "armed " + shift : "pull " + shift),
                refreshing: () => win.shown.push("spinner"),
                hide: () => win.shown.push("hide"),
            },
        });
    return win;
}

test("at the top, a pull past 70 px shows the arrow, turns it over, and the release reloads", () => {
    const win = fakeWindow();
    win.install();
    const prevented = win.drag(180, 100, [104, 110, 130, 165, 175, 190]);
    // 4 and 10 px: still a tap or a scroll's start, nothing shown, the browser's.
    assert.deepStrictEqual(prevented, [false, false, true, true, true, true]);
    assert.deepStrictEqual(win.shown, [
        "pull " + shiftOf(30),
        "pull " + shiftOf(65),
        "armed " + shiftOf(75),
        "armed " + shiftOf(90),
        "spinner",
    ]);
    assert.strictEqual(win.asked, 1, "through reconnect.js's reload in place");
});

test("a release short of 70 px puts the arrow away and reloads nothing", () => {
    const win = fakeWindow();
    win.install();
    win.drag(180, 100, [130, 160, 169]);
    assert.deepStrictEqual(win.shown.slice(-1), ["hide"]);
    assert.strictEqual(win.asked, 0);
    // Pulled past it, then back above it before the release: nothing either.
    win.shown = [];
    win.drag(180, 100, [130, 180, 150]);
    assert.deepStrictEqual(win.shown, ["pull " + shiftOf(30), "armed " + shiftOf(80), "pull " + shiftOf(50), "hide"]);
    assert.strictEqual(win.asked, 0);
});

test("not at the very top: the browser scrolls, nothing is shown", () => {
    const win = fakeWindow({ scrollTop: 1 });
    win.install();
    const prevented = win.drag(180, 100, [130, 200, 260]);
    assert.deepStrictEqual(prevented, [false, false, false]);
    assert.deepStrictEqual(win.shown, []);
    assert.strictEqual(win.asked, 0);
});

test("a scroll up, a sideways swipe, a second finger and a cancel are never a pull", () => {
    const up = fakeWindow();
    up.install();
    up.drag(180, 300, [290, 280, 300, 400]);
    assert.deepStrictEqual(up.shown, [], "up first: the browser's scroll to the end of the gesture");

    const sideways = fakeWindow();
    sideways.install();
    sideways.touch("touchstart", [[100, 100]]);
    sideways.touch("touchmove", [[130, 108]]);
    sideways.touch("touchmove", [[160, 190]]);
    sideways.touch("touchend", []);
    assert.deepStrictEqual(sideways.shown, []);
    assert.strictEqual(sideways.asked, 0);

    const two = fakeWindow();
    two.install();
    two.drag(180, 100, [140, 190], { lift: false });
    two.touch("touchmove", [[180, 200], [40, 210]]);
    two.touch("touchend", []);
    assert.deepStrictEqual(two.shown.slice(-1), ["hide"]);
    assert.strictEqual(two.asked, 0);
    two.shown = [];
    two.touch("touchstart", [[180, 100], [40, 100]]);
    two.touch("touchmove", [[180, 200], [40, 200]]);
    assert.deepStrictEqual(two.shown, [], "a two-finger start is a pinch");

    const cancelled = fakeWindow();
    cancelled.install();
    cancelled.drag(180, 100, [140, 190], { lift: false });
    cancelled.touch("touchcancel", []);
    cancelled.touch("touchend", []);
    assert.deepStrictEqual(cancelled.shown.slice(-1), ["hide"]);
    assert.strictEqual(cancelled.asked, 0);
});

test("refused while the page holds what a reload would lose: at the start, and again at the release", () => {
    const win = fakeWindow();
    win.install();
    win.mayReload = false;
    win.drag(180, 100, [140, 190]);
    assert.deepStrictEqual(win.shown, [], "a typed field or an open <details>: nothing shown");
    // Typed into during the pull (another finger can't, but a keyboard can): nothing at the release.
    win.mayReload = true;
    win.drag(180, 100, [140, 190], { lift: false });
    win.mayReload = false;
    win.touch("touchend", []);
    assert.deepStrictEqual(win.shown.slice(-1), ["hide"]);
    assert.strictEqual(win.asked, 0);
});

test("a save on its way refuses it, with or without reconnect.js", () => {
    for (const shared of [true, false]) {
        const win = fakeWindow({ shared });
        win.install();
        win.fire("doc", "change", { target: { hasAttribute: (n) => n === "data-autosave" } });
        win.drag(180, 100, [140, 190]);
        assert.deepStrictEqual(win.shown, [], "shared " + shared);
        assert.strictEqual(win.asked + win.reloads, 0);
    }
    const alone = fakeWindow({ shared: false });
    alone.install();
    alone.openForm = true;
    alone.drag(180, 100, [140, 190]);
    assert.deepStrictEqual(alone.shown, [], "an open <details> without reconnect.js");
    alone.openForm = false;
    alone.drag(180, 100, [140, 190]);
    assert.strictEqual(alone.reloads, 1, "without reconnect.js: a plain reload");
});

test("a reload reconnect.js refuses at the last moment puts the spinner away", () => {
    const win = fakeWindow();
    win.install();
    win.handyReconnect.reload = () => {
        win.asked += 1;
        return false;
    };
    win.drag(180, 100, [140, 190]);
    assert.deepStrictEqual(win.shown.slice(-2), ["spinner", "hide"]);
});

test("back from the back-forward cache, never left spinning", () => {
    const win = fakeWindow();
    win.install();
    win.drag(180, 100, [140, 190]);
    assert.strictEqual(win.shown[win.shown.length - 1], "spinner");
    win.fire("win", "pageshow", { persisted: true });
    assert.strictEqual(win.shown[win.shown.length - 1], "hide");
});

test("the moves' listener can cancel (passive: false), the start's can't hold up a scroll", () => {
    const win = fakeWindow();
    win.install();
    assert.deepStrictEqual(win.options("touchmove"), { passive: false });
    assert.deepStrictEqual(win.options("touchstart"), { passive: true });
});

test("the arrow's shift follows half the finger, from 10 px, at most 40 px", () => {
    assert.strictEqual(THRESHOLD, 70);
    assert.strictEqual(START_AFTER, 10);
    assert.strictEqual(shiftOf(0), 0);
    assert.strictEqual(shiftOf(START_AFTER), 0);
    assert.strictEqual(shiftOf(70), 30);
    assert.strictEqual(shiftOf(500), MAX_SHIFT);
});

test("the script is ES5 (§5.7)", () => {
    const src = require("node:fs")
        .readFileSync(require.resolve("../static/pull-refresh.js"), "utf8")
        .replace(/\/\*[\s\S]*?\*\//g, "")
        .replace(/^\s*\/\/.*$/gm, "");
    assert.ok(!/=>|\blet\s|\bconst\s|`|\bclass\s/.test(src), "no ES2015 syntax");
});
