// Behaviour of static/reconnect.js (2026-10-21: a tap after being away never "does nothing").
// Run: node --test jstest/reconnect.test.js
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const rc = require("../static/reconnect.js");
const { install, navigationTarget, CHIP_AFTER, PING_EVERY, PING_TIMEOUT, OFFER_AFTER, AWAY_MS } = rc;

const HERE = "https://pi.example.ts.net/devices/1?tab=apps#apps";

// A link-like element.
function link(href, attrs = {}) {
    const all = Object.assign({ href }, attrs);
    const classes = new Set();
    return {
        tagName: "A",
        parentNode: null,
        classes,
        busy: null,
        classList: { add: (c) => classes.add(c), remove: (c) => classes.delete(c) },
        hasAttribute: (name) => all[name] !== undefined,
        getAttribute: (name) => (all[name] === undefined ? null : all[name]),
        setAttribute(name, value) {
            if (name === "aria-busy") this.busy = value;
        },
        removeAttribute(name) {
            if (name === "aria-busy") this.busy = null;
        },
    };
}

const click = (target, extra = {}) =>
    Object.assign({ target, button: 0, defaultPrevented: false }, extra);

test("which clicks are navigations", () => {
    const go = (href, attrs, ev) => navigationTarget(link(href, attrs), click(null, ev), HERE);
    assert.strictEqual(go("/apps"), "https://pi.example.ts.net/apps");
    assert.strictEqual(go("music"), "https://pi.example.ts.net/devices/music");
    assert.strictEqual(go("/devices/1?tab=apps"), "https://pi.example.ts.net/devices/1?tab=apps");
    assert.strictEqual(go("/devices/2#apps"), "https://pi.example.ts.net/devices/2#apps");
    assert.strictEqual(go("https://pi.example.ts.net/settings"), "https://pi.example.ts.net/settings");
    // Hash-only, on this page.
    assert.strictEqual(go("#music"), null);
    assert.strictEqual(go("/devices/1?tab=apps#music"), null);
    // Downloads, targets, opt-outs.
    assert.strictEqual(go("/backups/a.db/download", { download: "" }), null);
    assert.strictEqual(go("/x", { target: "_blank" }), null);
    assert.strictEqual(go("/x", { target: "_self" }), "https://pi.example.ts.net/x");
    assert.strictEqual(go("/x", { "data-no-wait": "" }), null);
    // Another origin or scheme.
    assert.strictEqual(go("https://open.spotify.com/playlist/x"), null);
    assert.strictEqual(go("http://pi.example.ts.net/apps"), null);
    assert.strictEqual(go("tel:+4712345678"), null);
    assert.strictEqual(go("mailto:a@example.com"), null);
    assert.strictEqual(go("javascript:void(0)"), null);
    // Modified or handled clicks.
    for (const ev of [{ metaKey: true }, { ctrlKey: true }, { shiftKey: true }, { altKey: true }, { button: 1 }, { defaultPrevented: true }]) {
        assert.strictEqual(go("/apps", {}, ev), null, JSON.stringify(ev));
    }
    assert.strictEqual(navigationTarget(null, click(null), HERE), null);
});

test("the link is found from a tap on its icon", () => {
    const a = link("/apps");
    const svg = { tagName: "svg", parentNode: { tagName: "SPAN", parentNode: a } };
    assert.strictEqual(rc.linkOf(svg), a);
    assert.strictEqual(rc.linkOf({ tagName: "DIV", parentNode: null }), null);
});

// A fake window: a clock, timers, a fetch the test answers, events fired by hand, a view that
// records what is shown.
function fakeWindow({ openForm = false } = {}) {
    let clock = 1000000;
    let timerId = 0;
    const timers = new Map();
    const listeners = {};
    const pings = [];
    const view = { bar: false, chip: false, retry: null, marked: new Set() };
    const win = {
        assigned: [],
        reloads: 0,
        openForm,
        stored: {},
        name: "",
        visibility: "visible",
        scrollY: 0,
        main: { scrollTop: 420 },
        pings,
        view,
        location: {
            href: "https://pi.example.ts.net/devices/1",
            pathname: "/devices/1",
            hash: "",
            assign: (href) => win.assigned.push(href),
            reload: () => {
                win.reloads += 1;
            },
        },
        history: { scrollRestoration: "manual" },
        sessionStorage: {
            setItem: (key, value) => {
                win.stored[key] = value;
            },
        },
        getComputedStyle: () => ({ overflowY: "auto" }),
        setTimeout: (fn, ms) => {
            timerId += 1;
            timers.set(timerId, { fn, at: clock + ms });
            return timerId;
        },
        clearTimeout: (id) => timers.delete(id),
        fetch: (url, options) =>
            new Promise((resolve, reject) => {
                pings.push({ url, options, resolve, reject });
            }),
        addEventListener: (type, fn) => {
            (listeners["win:" + type] = listeners["win:" + type] || []).push(fn);
        },
        document: {
            get visibilityState() {
                return win.visibility;
            },
            addEventListener: (type, fn) => {
                (listeners["doc:" + type] = listeners["doc:" + type] || []).push(fn);
            },
            querySelector: (selector) => {
                if (selector === "details[open]") return win.openForm ? {} : null;
                if (selector === "main") return win.main;
                return null;
            },
            getElementById: (id) => (id === "apps" ? { getBoundingClientRect: () => ({ top: 88 }) } : null),
        },
    };
    win.now = () => clock;
    // Moves the clock by `ms`, running each timer when its time comes.
    win.advance = (ms) => {
        const end = clock + ms;
        for (;;) {
            let next = null;
            for (const [id, t] of timers) {
                if (t.at <= end && (next === null || t.at < timers.get(next).at)) next = id;
            }
            if (next === null) break;
            const t = timers.get(next);
            timers.delete(next);
            clock = Math.max(clock, t.at);
            t.fn();
        }
        clock = end;
    };
    win.fire = (where, type, event) => {
        (listeners[where + ":" + type] || []).forEach((fn) => fn(event || { type }));
    };
    win.tap = (a, extra) => win.fire("win", "click", click(a, extra));
    win.hide = () => {
        win.visibility = "hidden";
        win.fire("doc", "visibilitychange");
    };
    win.show = () => {
        win.visibility = "visible";
        win.fire("doc", "visibilitychange");
    };
    win.install = () =>
        install(win, {
            now: win.now,
            view: {
                bar: (on) => {
                    view.bar = on;
                },
                chip: (on) => {
                    view.chip = on;
                    if (!on) view.retry = null;
                },
                offer: (fn) => {
                    view.retry = fn || null;
                },
                mark: (a, on) => {
                    if (!a) return;
                    if (on) view.marked.add(a);
                    else view.marked.delete(a);
                },
            },
        });
    return win;
}

// Lets the promise callbacks run.
const settle = () => new Promise((resolve) => setImmediate(resolve));

async function answer(win, status = 204) {
    const p = win.pings[win.pings.length - 1];
    p.resolve({ status });
    await settle();
}

test("a tap marks the link and shows the bar at once; nothing else while the page comes", async () => {
    const win = fakeWindow();
    win.install();
    const tab = link("/apps");
    win.tap(tab);
    assert.ok(win.view.marked.has(tab));
    assert.strictEqual(win.view.bar, true);
    assert.strictEqual(win.view.chip, false);
    win.advance(CHIP_AFTER - 1);
    assert.strictEqual(win.view.chip, false, "no chip before 3 s");
    assert.strictEqual(win.pings.length, 0, "no ping before 3 s");
    assert.deepStrictEqual(win.assigned, [], "the browser's own navigation goes on");
});

test("after 3 s a calm chip, a ping, and the same navigation again once the server answers", async () => {
    const win = fakeWindow();
    win.install();
    win.tap(link("/apps"));
    win.advance(CHIP_AFTER);
    assert.strictEqual(win.view.chip, true);
    assert.strictEqual(win.pings.length, 1);
    assert.match(win.pings[0].url, /^\/ping\?t=\d+$/);
    assert.strictEqual(win.pings[0].options.credentials, "same-origin");
    // No answer in time: the network isn't up yet.
    win.advance(PING_TIMEOUT);
    await settle();
    assert.deepStrictEqual(win.assigned, []);
    // The next ping is answered: the navigation starts again by itself, no tap needed.
    win.advance(PING_EVERY - 1000); // the next ping (at 8 s), before its own timeout;
    assert.strictEqual(win.pings.length, 2);
    await answer(win);
    assert.deepStrictEqual(win.assigned, ["https://pi.example.ts.net/apps"]);
    assert.strictEqual(win.view.chip, true, "still connecting until the page is left");
});

test("a server that answers at once doesn't restart a navigation that is only slow", async () => {
    const win = fakeWindow();
    win.install();
    win.tap(link("/apps"));
    win.advance(CHIP_AFTER);
    await answer(win);
    win.advance(PING_EVERY);
    await answer(win);
    assert.deepStrictEqual(win.assigned, [], "8 s: still waiting for the browser");
    win.advance(PING_EVERY);
    await answer(win);
    assert.deepStrictEqual(win.assigned, ["https://pi.example.ts.net/apps"], "13 s: started again");
});

test("online and coming back to the page ping at once", async () => {
    const win = fakeWindow();
    win.install();
    win.tap(link("/music"));
    win.advance(CHIP_AFTER);
    win.pings[0].reject(new TypeError("Failed to fetch"));
    await settle();
    win.fire("win", "online");
    assert.strictEqual(win.pings.length, 2);
    await answer(win);
    assert.deepStrictEqual(win.assigned, ["https://pi.example.ts.net/music"]);
    win.hide();
    win.show();
    assert.strictEqual(win.pings.length, 3, "visible again: one more ping");
});

test("a 5xx from a proxy is no answer", async () => {
    const win = fakeWindow();
    win.install();
    win.tap(link("/music"));
    win.advance(CHIP_AFTER);
    await answer(win, 502);
    win.advance(20000);
    assert.ok(win.pings.length >= 4);
    win.pings.slice(1).forEach((p) => p.resolve({ status: 504 }));
    await settle();
    assert.deepStrictEqual(win.assigned, []);
});

test("Try again only after 20 s; it starts the same navigation", async () => {
    const win = fakeWindow();
    win.install();
    win.tap(link("/settings"));
    win.advance(OFFER_AFTER - 1);
    assert.strictEqual(win.view.retry, null);
    win.advance(1);
    assert.strictEqual(typeof win.view.retry, "function");
    win.view.retry();
    assert.deepStrictEqual(win.assigned, ["https://pi.example.ts.net/settings"]);
});

test("a page back from the back-forward cache is never left loading", () => {
    const win = fakeWindow();
    win.install();
    const tab = link("/apps");
    win.tap(tab);
    win.advance(OFFER_AFTER);
    win.fire("win", "pageshow", { persisted: true });
    assert.strictEqual(win.view.bar, false);
    assert.strictEqual(win.view.chip, false);
    assert.strictEqual(win.view.retry, null);
    assert.ok(!win.view.marked.has(tab));
    const before = win.pings.length;
    win.advance(60000);
    assert.strictEqual(win.pings.length, before, "no timers left");
});

test("a first pageshow (not from the cache) doesn't clear a tap made while the page loads", () => {
    const win = fakeWindow();
    win.install();
    win.tap(link("/apps"));
    win.fire("win", "pageshow", { persisted: false });
    assert.strictEqual(win.view.bar, true);
});

test("ignored clicks show nothing", () => {
    const win = fakeWindow();
    win.install();
    win.tap(link("#top"));
    win.tap(link("/backups/a/download", { download: "" }));
    win.tap(link("/apps"), { metaKey: true });
    win.tap(link("https://open.spotify.com/x", { target: "_blank" }));
    assert.strictEqual(win.view.bar, false);
    win.advance(60000);
    assert.strictEqual(win.pings.length, 0);
});

test("back after more than 10 minutes: a ping, and answered, a reload in place that keeps the place", async () => {
    const win = fakeWindow();
    win.install();
    win.hide();
    win.advance(AWAY_MS + 1);
    win.show();
    assert.strictEqual(win.pings.length, 1, "pinged at once");
    assert.strictEqual(win.view.chip, false, "no chip unless it takes 3 s");
    await answer(win);
    assert.strictEqual(win.reloads, 1);
    assert.strictEqual(win.history.scrollRestoration, "auto");
    const record = JSON.parse(win.stored["handy-scroll"]);
    assert.strictEqual(record.path, "/devices/1");
    assert.strictEqual(record.y, 420, "<main>'s position in the shell");
    assert.strictEqual(record.t, win.now());
    assert.strictEqual(record.tops, undefined, "no #fragment");
    assert.strictEqual(win.name, "handy-scroll:" + win.stored["handy-scroll"], "iPhone's second carrier");
});

test("the reload over a #fragment records where its element is on the screen", () => {
    const win = fakeWindow();
    win.location.hash = "#apps";
    const record = rc.placeRecord(win, 5);
    assert.deepStrictEqual(record, { path: "/devices/1", y: 420, t: 5, tops: { apps: 88 } });
});

test("back after a short time: nothing", () => {
    const win = fakeWindow();
    win.install();
    win.hide();
    win.advance(AWAY_MS - 1000);
    win.show();
    win.advance(60000);
    assert.strictEqual(win.pings.length, 0);
    assert.strictEqual(win.reloads, 0);
});

test("back after long, the server not there yet: the chip after 3 s, the reload once it answers", async () => {
    const win = fakeWindow();
    win.install();
    win.hide();
    win.advance(AWAY_MS * 3);
    win.show();
    win.advance(CHIP_AFTER);
    assert.strictEqual(win.view.chip, true);
    win.advance(PING_TIMEOUT);
    await settle();
    assert.strictEqual(win.reloads, 0);
    win.advance(PING_EVERY - 1000); // the next ping, before its own timeout;
    await answer(win);
    assert.strictEqual(win.reloads, 1);
    assert.strictEqual(win.view.bar, true, "the reload is a navigation on its way");
});

test("no reload in place while a <details> is open or after a save started", async () => {
    const open = fakeWindow({ openForm: true });
    open.install();
    open.hide();
    open.advance(AWAY_MS + 1);
    open.show();
    await answer(open);
    assert.strictEqual(open.reloads, 0);
    assert.strictEqual(open.view.chip, false);

    const saved = fakeWindow();
    saved.install();
    saved.fire("doc", "change", { target: { hasAttribute: (n) => n === "data-autosave" } });
    saved.hide();
    saved.advance(AWAY_MS + 1);
    saved.show();
    assert.strictEqual(saved.pings.length, 0);
    assert.strictEqual(saved.reloads, 0);

    const submitted = fakeWindow();
    submitted.install();
    submitted.hide();
    submitted.advance(AWAY_MS + 1);
    submitted.show();
    submitted.fire("doc", "submit", { target: {} });
    await answer(submitted);
    assert.strictEqual(submitted.reloads, 0, "a save that started during the ping");
});

test("back from the back-forward cache after long: the same ping", async () => {
    const win = fakeWindow();
    win.install();
    win.fire("win", "pagehide");
    win.advance(AWAY_MS + 1);
    win.fire("win", "pageshow", { persisted: true });
    assert.strictEqual(win.pings.length, 1);
    await answer(win);
    assert.strictEqual(win.reloads, 1);
});

test("the strings and the script are ES5 (§5.7)", () => {
    assert.deepStrictEqual(rc.TEXT, { connecting: "Connecting…", retry: "Try again" });
    const src = require("node:fs")
        .readFileSync(require.resolve("../static/reconnect.js"), "utf8")
        .replace(/\/\*[\s\S]*?\*\//g, "")
        .replace(/^\s*\/\/.*$/gm, "");
    assert.ok(!/=>|\blet\s|\bconst\s|`|\bclass\s/.test(src), "no ES2015 syntax");
});
