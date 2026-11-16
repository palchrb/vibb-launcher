// Behaviour of static/top-strip.js (design 104, 109): in the installed iPhone app (navigator.standalone) a return
// (pageshow, visible, focus, a colour-scheme change) replaces the status bar's strip with a clone carrying the nudge
// and moves theme-color one step, holds that for two frames and HOLD_MS, then puts both back - and again at each of
// RETRY_MS; a hold always ends (cap, hidden, pagehide, the next return); the keyboard and window resize nudge once,
// KEYBOARD_MS after the last; elsewhere nothing is installed.
// Run: node --test jstest/top-strip.test.js
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const { install, NUDGE, RETRY_MS, KEYBOARD_MS, HOLD_MS, HOLD_CAP_MS } = require("../static/top-strip.js");

const NIGHT = "#0C0C14";

function fakePage({ withStrip = true, withMeta = true, raf = true, viewport = true, standalone = true, noStandalone = false, mm = "add" } = {}) {
    const listeners = {};
    const frames = [];
    let now = 0;
    let nextId = 1;
    const timers = new Map();
    const stats = { replaced: 0, metaSets: 0 };
    function makeStrip() {
        const classes = new Set(["top-strip"]);
        const node = {
            attrs: { class: "top-strip", "aria-hidden": "true" },
            classes,
            parentNode: null,
            classList: {
                add: (c) => classes.add(c),
                remove: (c) => classes.delete(c),
                contains: (c) => classes.has(c),
            },
            cloneNode: () => {
                const c = makeStrip();
                c.attrs = Object.assign({}, node.attrs);
                c.classes.clear();
                node.classes.forEach((k) => c.classes.add(k));
                return c;
            },
        };
        return node;
    }
    const holder = {
        strip: null,
        replaceChild: (fresh, old) => {
            assert.strictEqual(old, holder.strip);
            holder.strip = fresh;
            fresh.parentNode = holder;
            old.parentNode = null;
            stats.replaced += 1;
        },
    };
    if (withStrip) { holder.strip = makeStrip(); holder.strip.parentNode = holder; }
    const meta = {
        content: NIGHT,
        getAttribute: (n) => (n === "content" ? meta.content : null),
        setAttribute: (n, v) => { if (n === "content") { meta.content = v; stats.metaSets += 1; } },
    };
    const doc = {
        visibilityState: "visible",
        querySelector: (s) => {
            if (s === ".top-strip") return holder.strip;
            if (s === 'meta[name="theme-color"]') return withMeta ? meta : null;
            return null;
        },
        addEventListener: (type, fn) => { listeners["doc:" + type] = fn; },
    };
    const win = {
        document: doc,
        navigator: { standalone },
        addEventListener: (type, fn) => { listeners["win:" + type] = fn; },
        setTimeout: (fn, ms) => { const id = nextId++; timers.set(id, { fn, at: now + ms }); return id; },
        clearTimeout: (id) => { timers.delete(id); },
    };
    if (noStandalone) delete win.navigator.standalone;
    if (raf) win.requestAnimationFrame = (fn) => frames.push(fn);
    if (viewport) win.visualViewport = { addEventListener: (type, fn) => { listeners["vv:" + type] = fn; } };
    if (mm) {
        win.matchMedia = () => (mm === "add"
            ? { addEventListener: (t, fn) => { listeners["mq:" + t] = fn; } }
            : { addListener: (fn) => { listeners["mq:listener"] = fn; } });
    }
    const runFrame = () => { frames.splice(0).forEach((fn) => fn()); };
    const paint = () => { runFrame(); runFrame(); };
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
    // One full hold: both frames painted, then HOLD_MS.
    const hold = () => { paint(); advance(HOLD_MS); };
    return {
        win, doc, meta, holder, listeners, frames, timers, runFrame, paint, advance, hold, stats,
        strip: () => holder.strip,
        nudged: () => !!holder.strip && holder.strip.classes.has(NUDGE),
    };
}

test("a return puts a clone with the nudge in the strip's place and moves theme-color; the hold ends clean", () => {
    const page = fakePage();
    install(page.win);
    const before = page.strip();
    page.listeners["win:pageshow"]({ persisted: true });
    assert.notStrictEqual(page.strip(), before, "a fresh strip");
    assert.ok(page.nudged());
    assert.deepStrictEqual(page.strip().attrs, before.attrs, "same attributes");
    assert.strictEqual(page.meta.content, "#0C0C15");
    assert.strictEqual(page.stats.metaSets, 1, "one setAttribute");
    page.runFrame();
    page.runFrame();
    assert.ok(page.nudged(), "still held after two frames");
    page.advance(HOLD_MS - 1);
    assert.ok(page.nudged());
    page.advance(1);
    assert.ok(!page.nudged(), "the clone is plain");
    assert.strictEqual(page.meta.content, NIGHT, "the old content back");
    assert.strictEqual(page.stats.metaSets, 2);
});

test("a nudge while holding does nothing", () => {
    const page = fakePage();
    install(page.win);
    page.listeners["doc:visibilitychange"]();
    const held = page.strip();
    // A retry timer firing mid-hold (the flag, not the class, guards: the node was replaced).
    page.advance(RETRY_MS[0]);
    page.listeners["doc:focusout"]({});
    page.advance(KEYBOARD_MS);
    assert.strictEqual(page.stats.replaced, 1);
    assert.strictEqual(page.strip(), held);
});

test("a return nudges again at each retry; hidden cancels the series", () => {
    assert.deepStrictEqual(RETRY_MS, [300, 1000, 3000]);
    const page = fakePage();
    install(page.win);
    page.listeners["doc:visibilitychange"]();
    page.hold();
    assert.strictEqual(page.stats.replaced, 1);
    let at = HOLD_MS;
    RETRY_MS.forEach((ms, i) => {
        page.advance(ms - at - 1);
        assert.strictEqual(page.stats.replaced, i + 1, "not before " + ms + " ms");
        page.advance(1);
        assert.strictEqual(page.stats.replaced, i + 2, "nudged at " + ms + " ms");
        assert.ok(page.nudged());
        page.hold();
        assert.ok(!page.nudged());
        at = ms + HOLD_MS;
    });
    page.advance(60000);
    assert.strictEqual(page.stats.replaced, 1 + RETRY_MS.length);
    assert.strictEqual(page.timers.size, 0);

    const other = fakePage();
    install(other.win);
    other.listeners["win:pageshow"]({});
    other.hold();
    other.doc.visibilityState = "hidden";
    other.listeners["doc:visibilitychange"]();
    assert.strictEqual(other.timers.size, 0);
    other.advance(60000);
    assert.strictEqual(other.stats.replaced, 1);
});

test("hidden mid-hold ends the hold at once, and the next visible return nudges again", () => {
    const page = fakePage();
    install(page.win);
    page.listeners["win:pageshow"]({});
    assert.ok(page.nudged());
    page.doc.visibilityState = "hidden";
    page.listeners["doc:visibilitychange"]();
    assert.ok(!page.nudged());
    assert.strictEqual(page.meta.content, NIGHT);
    assert.strictEqual(page.timers.size, 0, "cap and retries cleared");
    page.doc.visibilityState = "visible";
    page.listeners["doc:visibilitychange"]();
    assert.ok(page.nudged());
    assert.strictEqual(page.stats.replaced, 2);
});

test("pagehide mid-hold ends it the same way", () => {
    const page = fakePage();
    install(page.win);
    page.listeners["win:pageshow"]({});
    page.listeners["win:pagehide"]({});
    assert.ok(!page.nudged());
    assert.strictEqual(page.meta.content, NIGHT);
    assert.strictEqual(page.timers.size, 0);
});

test("with no frames at all the cap ends the hold", () => {
    const page = fakePage();
    install(page.win);
    page.listeners["doc:focusout"]({});
    page.advance(KEYBOARD_MS); // one nudge, no series
    assert.ok(page.nudged());
    page.advance(HOLD_CAP_MS - 1);
    assert.ok(page.nudged());
    page.advance(1);
    assert.ok(!page.nudged());
    assert.strictEqual(page.meta.content, NIGHT);
});

test("a stale hold never blocks a return", () => {
    const page = fakePage();
    install(page.win);
    page.listeners["win:pageshow"]({});
    page.listeners["win:pageshow"]({});
    assert.strictEqual(page.stats.replaced, 2, "the return ended the old hold and nudged again");
    assert.ok(page.nudged());
    assert.strictEqual(page.meta.content, "#0C0C15");
    page.advance(60000, true);
    assert.strictEqual(page.meta.content, NIGHT);
});

test("focus and a colour-scheme change each start a series, restarting it, never stacking", () => {
    for (const mm of ["add", "listener"]) {
        const page = fakePage({ mm });
        install(page.win);
        page.listeners["win:focus"]({});
        assert.ok(page.nudged());
        page.hold();
        page.advance(500, true);
        const colour = page.listeners["mq:change"] || page.listeners["mq:listener"];
        assert.ok(colour, "colour-scheme listener (" + mm + ")");
        colour({});
        assert.strictEqual(page.timers.size, RETRY_MS.length + 1, "one series pending, plus the cap");
        page.hold();
        page.advance(60000, true);
        // focus: 1 + the 300 ms retry; the change: 1 + three retries.
        assert.strictEqual(page.stats.replaced, 2 + 1 + RETRY_MS.length);
    }
});

test("the keyboard and a window resize nudge once, after the last event", () => {
    const page = fakePage();
    install(page.win);
    page.listeners["doc:focusout"]({});
    page.advance(KEYBOARD_MS - 100);
    page.listeners["vv:resize"]({});
    page.advance(KEYBOARD_MS - 100);
    page.listeners["win:resize"]({});
    assert.strictEqual(page.timers.size, 1, "one timer, re-armed");
    page.advance(KEYBOARD_MS - 1);
    assert.strictEqual(page.stats.replaced, 0);
    page.advance(1);
    assert.strictEqual(page.stats.replaced, 1);
    page.hold();
    page.advance(60000);
    assert.strictEqual(page.stats.replaced, 1, "not a series");
});

test("without navigator.standalone nothing is installed", () => {
    for (const opts of [{ standalone: false }, { noStandalone: true }]) {
        const page = fakePage(opts);
        const nudge = install(page.win);
        assert.deepStrictEqual(Object.keys(page.listeners), []);
        nudge();
        assert.strictEqual(page.stats.replaced, 0);
    }
});

test("without rAF a timer stands in; a page without the strip or the meta nudges what it has", () => {
    const page = fakePage({ raf: false, viewport: false, mm: null });
    install(page.win);
    page.listeners["win:pageshow"]({});
    assert.ok(page.nudged());
    page.advance(32 + HOLD_MS);
    assert.ok(!page.nudged(), "two 16 ms frames and the hold");

    const noMeta = fakePage({ withMeta: false });
    install(noMeta.win);
    noMeta.listeners["win:pageshow"]({});
    assert.ok(noMeta.nudged());
    assert.strictEqual(noMeta.stats.metaSets, 0);
    noMeta.hold();
    assert.ok(!noMeta.nudged());

    const noStrip = fakePage({ withStrip: false });
    install(noStrip.win);
    noStrip.listeners["win:pageshow"]({});
    assert.strictEqual(noStrip.meta.content, "#0C0C15");
    noStrip.hold();
    assert.strictEqual(noStrip.meta.content, NIGHT);

    const bare = fakePage({ withStrip: false, withMeta: false });
    const nudge = install(bare.win);
    assert.doesNotThrow(() => nudge());
    assert.doesNotThrow(() => bare.listeners["win:pageshow"]({}));
    bare.advance(60000);
    assert.strictEqual(bare.frames.length, 0);
});
