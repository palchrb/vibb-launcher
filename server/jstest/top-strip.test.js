// Behaviour of static/top-strip.js (2026-11-12): on pageshow and on a return to visible, the status bar's strip is a
// shade off the night for one painted frame, then the night again; hidden does nothing, nor does a page without it.
// Run: node --test jstest/top-strip.test.js
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const { install, NUDGE } = require("../static/top-strip.js");

function fakePage(withStrip = true) {
    const classes = new Set();
    const strip = {
        classList: {
            add: (c) => classes.add(c),
            remove: (c) => classes.delete(c),
            contains: (c) => classes.has(c),
        },
    };
    const listeners = {};
    const frames = [];
    const doc = {
        visibilityState: "visible",
        querySelector: (s) => (withStrip && s === ".top-strip" ? strip : null),
        addEventListener: (type, fn) => { listeners["doc:" + type] = fn; },
    };
    const win = {
        document: doc,
        requestAnimationFrame: (fn) => frames.push(fn),
        addEventListener: (type, fn) => { listeners["win:" + type] = fn; },
    };
    const runFrame = () => {
        const due = frames.splice(0);
        due.forEach((fn) => fn());
    };
    return { win, doc, classes, listeners, frames, runFrame };
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

test("a return to visible nudges; hidden does nothing", () => {
    const page = fakePage();
    install(page.win);
    page.doc.visibilityState = "hidden";
    page.listeners["doc:visibilitychange"]();
    assert.ok(!page.classes.has(NUDGE));
    assert.strictEqual(page.frames.length, 0);
    page.doc.visibilityState = "visible";
    page.listeners["doc:visibilitychange"]();
    assert.ok(page.classes.has(NUDGE));
    page.runFrame();
    page.runFrame();
    assert.ok(!page.classes.has(NUDGE));
});

test("a second event while nudged starts no second nudge", () => {
    const page = fakePage();
    install(page.win);
    page.listeners["win:pageshow"]({});
    page.listeners["doc:visibilitychange"]();
    assert.strictEqual(page.frames.length, 1);
});

test("without rAF a timer stands in, and a page without the strip is left alone", () => {
    const page = fakePage();
    const timers = [];
    delete page.win.requestAnimationFrame;
    page.win.setTimeout = (fn, ms) => timers.push([fn, ms]);
    install(page.win);
    page.listeners["win:pageshow"]({});
    assert.strictEqual(timers.length, 1);
    timers.shift()[0]();
    timers.shift()[0]();
    assert.ok(!page.classes.has(NUDGE));

    const bare = fakePage(false);
    assert.doesNotThrow(() => install(bare.win)({}));
});
