// Behaviour of static/top-strip.js (2026-11-12, design 104): on pageshow and on a return to visible, the status bar's
// strip is a shade off the night for one painted frame, then the night again, and once more at each of RETRY_MS after
// the return - a new return restarts the series, hidden cancels it; a field's focusout and the visual viewport's resize
// nudge once, KEYBOARD_MS after the last of them; a page without the strip is left alone.
// Run: node --test jstest/top-strip.test.js
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const { install, NUDGE, RETRY_MS, KEYBOARD_MS } = require("../static/top-strip.js");

function fakePage({ withStrip = true, raf = true, viewport = true } = {}) {
    const classes = new Set();
    let added = 0;
    const strip = {
        classList: {
            add: (c) => { classes.add(c); added += 1; },
            remove: (c) => classes.delete(c),
            contains: (c) => classes.has(c),
        },
    };
    const listeners = {};
    const frames = [];
    // A fake clock: timers by id, run in time order by advance().
    let now = 0;
    let nextId = 1;
    const timers = new Map();
    const doc = {
        visibilityState: "visible",
        querySelector: (s) => (withStrip && s === ".top-strip" ? strip : null),
        addEventListener: (type, fn) => { listeners["doc:" + type] = fn; },
    };
    const win = {
        document: doc,
        addEventListener: (type, fn) => { listeners["win:" + type] = fn; },
        setTimeout: (fn, ms) => { const id = nextId++; timers.set(id, { fn, at: now + ms }); return id; },
        clearTimeout: (id) => { timers.delete(id); },
    };
    if (raf) win.requestAnimationFrame = (fn) => frames.push(fn);
    if (viewport) {
        win.visualViewport = { addEventListener: (type, fn) => { listeners["vv:" + type] = fn; } };
    }
    const runFrame = () => {
        const due = frames.splice(0);
        due.forEach((fn) => fn());
    };
    // Two frames: a nudge's paint and its end.
    const paint = () => { runFrame(); runFrame(); };
    // Run the timers due in `ms`; with `painting`, both frames are painted after each.
    const advance = (ms, painting = false) => {
        const until = now + ms;
        for (;;) {
            let next = null;
            for (const [id, t] of timers) {
                if (t.at <= until && (next === null || t.at < timers.get(next).at)) next = id;
            }
            if (next === null) break;
            const t = timers.get(next);
            timers.delete(next);
            now = t.at;
            t.fn();
            if (painting) paint();
        }
        now = until;
    };
    return {
        win, doc, classes, listeners, frames, timers, runFrame, paint, advance,
        nudges: () => added,
    };
}

test("pageshow nudges the strip for exactly one painted frame", () => {
    const page = fakePage();
    install(page.win);
    page.listeners["win:pageshow"]({ persisted: true });
    assert.ok(page.classes.has(NUDGE));
    page.runFrame();
    assert.ok(page.classes.has(NUDGE), "still on in the first frame");
    page.runFrame();
    assert.ok(!page.classes.has(NUDGE), "the night again");
    assert.strictEqual(page.frames.length, 0);
});

test("a return nudges again at each retry, one frame each", () => {
    assert.deepStrictEqual(RETRY_MS, [300, 1000, 3000]);
    const page = fakePage();
    install(page.win);
    page.listeners["doc:visibilitychange"]();
    page.paint();
    assert.strictEqual(page.nudges(), 1);
    let at = 0;
    RETRY_MS.forEach((ms, i) => {
        page.advance(ms - 1 - at);
        assert.strictEqual(page.nudges(), i + 1, "not before " + ms + " ms");
        page.advance(1);
        at = ms;
        assert.ok(page.classes.has(NUDGE), "nudged at " + ms + " ms");
        page.paint();
        assert.ok(!page.classes.has(NUDGE), "the night again after " + ms + " ms");
    });
    page.advance(60000);
    assert.strictEqual(page.nudges(), 1 + RETRY_MS.length, "nothing after the last retry");
    assert.strictEqual(page.timers.size, 0);
});

test("a second return restarts the series; the retries never stack", () => {
    const page = fakePage();
    install(page.win);
    page.listeners["win:pageshow"]({});
    page.paint();
    page.advance(500, true); // the 300 ms retry has run
    page.listeners["doc:visibilitychange"]();
    page.paint();
    assert.strictEqual(page.timers.size, RETRY_MS.length, "one series pending, the old one dropped");
    page.advance(60000, true);
    // 1 + one retry of the first series, then 1 + all three of the second.
    assert.strictEqual(page.nudges(), 2 + 1 + RETRY_MS.length);
});

test("going hidden cancels the pending retries, and hidden does nothing", () => {
    const page = fakePage();
    install(page.win);
    page.listeners["win:pageshow"]({});
    page.paint();
    page.doc.visibilityState = "hidden";
    page.listeners["doc:visibilitychange"]();
    assert.strictEqual(page.timers.size, 0);
    assert.strictEqual(page.frames.length, 0);
    page.advance(60000);
    assert.strictEqual(page.nudges(), 1);
});

test("an event while nudged starts no second nudge", () => {
    const page = fakePage();
    install(page.win);
    page.listeners["win:pageshow"]({});
    page.listeners["doc:visibilitychange"]();
    assert.strictEqual(page.frames.length, 1);
    assert.strictEqual(page.nudges(), 1);
});

test("the keyboard nudges once, after the last focusout or resize", () => {
    const page = fakePage();
    install(page.win);
    page.listeners["doc:focusout"]({});
    page.advance(KEYBOARD_MS - 100);
    page.listeners["vv:resize"]({});
    page.advance(KEYBOARD_MS - 100);
    page.listeners["vv:resize"]({});
    assert.strictEqual(page.timers.size, 1, "one timer, re-armed");
    page.advance(KEYBOARD_MS - 1);
    assert.strictEqual(page.nudges(), 0);
    page.advance(1);
    assert.strictEqual(page.nudges(), 1);
    page.paint();
    assert.ok(!page.classes.has(NUDGE));
    page.advance(60000);
    assert.strictEqual(page.nudges(), 1);
});

test("without rAF a timer stands in, without visualViewport focusout still works, and no strip is left alone", () => {
    const page = fakePage({ raf: false, viewport: false });
    install(page.win);
    page.listeners["win:pageshow"]({});
    assert.ok(page.classes.has(NUDGE));
    page.advance(32);
    assert.ok(!page.classes.has(NUDGE), "two 16 ms frames");
    page.advance(60000);
    assert.strictEqual(page.nudges(), 1 + RETRY_MS.length);
    page.listeners["doc:focusout"]({});
    page.advance(60000);
    assert.strictEqual(page.nudges(), 1 + RETRY_MS.length + 1);

    const bare = fakePage({ withStrip: false });
    const nudge = install(bare.win);
    assert.doesNotThrow(() => nudge());
    assert.doesNotThrow(() => bare.listeners["win:pageshow"]({}));
    bare.advance(60000);
    assert.strictEqual(bare.frames.length, 0);
});
