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
function fakeWindow({ openForm = false, postAnswer = false, href = "https://pi.example.ts.net/devices/1", referrer = "", stored = {}, name = "" } = {}) {
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
        stored: Object.assign({}, stored),
        name,
        replaced: [],
        visibility: "visible",
        scrollY: 0,
        main: { scrollTop: 420 },
        pings,
        view,
        // What happened, in order: `replace <url>`, `reload`.
        steps: [],
        controls: [],
        location: {
            href,
            pathname: new URL(href).pathname,
            search: new URL(href).search,
            hash: "",
            assign: (to) => win.assigned.push(to),
            replace: (to) => win.replaced.push(to),
            reload: () => {
                win.reloads += 1;
                win.steps.push("reload");
            },
        },
        history: {
            scrollRestoration: "manual",
            state: { from: "the page" },
            replaceState: (state, _title, url) => {
                win.steps.push("replace " + url);
                assert.deepStrictEqual(state, { from: "the page" }, "the entry's state kept");
            },
        },
        sessionStorage: {
            setItem: (key, value) => {
                win.stored[key] = value;
            },
            getItem: (key) => (key in win.stored ? win.stored[key] : null),
            removeItem: (key) => {
                delete win.stored[key];
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
            referrer,
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
            querySelectorAll: () => win.controls,
            body: { hasAttribute: (name) => name === "data-post-answer" && postAnswer },
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

test("back after more than a minute (AWAY_MS): a ping, and answered, a reload in place that keeps the place", async () => {
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
    assert.deepStrictEqual(record, { path: "/devices/1", href: "/devices/1", y: 420, t: 5, tops: { apps: 88 } });
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

test("handyReconnect: a reload asked for (pull-refresh.js) is the reload in place, under the same rules", async () => {
    const win = fakeWindow();
    win.install();
    assert.strictEqual(win.handyReconnect.mayReload(), true);
    assert.strictEqual(win.handyReconnect.reload(), true);
    assert.deepStrictEqual(win.steps, ["replace https://pi.example.ts.net/devices/1", "reload"]);
    assert.strictEqual(win.view.bar, true, "the bar while it comes");
    assert.strictEqual(JSON.parse(win.stored["handy-scroll"]).y, 420, "the place kept");
    // Slow: the chip after 3 s, the ping, the same reload again once the server answers after a miss.
    win.advance(CHIP_AFTER);
    assert.strictEqual(win.view.chip, true);
    assert.strictEqual(win.pings.length, 1);

    const typed = fakeWindow();
    typed.controls = [{ tagName: "INPUT", type: "text", value: "Ella", defaultValue: "" }];
    typed.install();
    assert.strictEqual(typed.handyReconnect.reload(), false, "a typed field");
    assert.strictEqual(typed.reloads, 0);

    const open = fakeWindow({ openForm: true });
    open.install();
    assert.strictEqual(open.handyReconnect.mayReload(), false, "an open <details>");

    const saved = fakeWindow();
    saved.install();
    saved.fire("doc", "submit", { target: {} });
    assert.strictEqual(saved.handyReconnect.reload(), false, "a save on its way");
    assert.strictEqual(saved.reloads, 0);

    // A POST's answer may be reloaded when asked: the entry is a GET first.
    const answered = fakeWindow({ postAnswer: true });
    answered.install();
    assert.strictEqual(answered.handyReconnect.reload(), true);
    assert.strictEqual(answered.steps[0], "replace https://pi.example.ts.net/devices/1");
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

// ---- qa-srv-sweep-resume-code ------------------------------------------------------------------

async function awayAndBack(win) {
    win.hide();
    win.advance(AWAY_MS + 1);
    win.show();
}

test("M1: a POST's answer (data-post-answer) is never reloaded after being away", async () => {
    const win = fakeWindow({ postAnswer: true });
    win.install();
    await awayAndBack(win);
    win.advance(60000);
    await settle();
    assert.strictEqual(win.reloads, 0);
    assert.deepStrictEqual(win.steps, []);
    win.fire("win", "pagehide");
    win.advance(AWAY_MS + 1);
    win.fire("win", "pageshow", { persisted: true });
    assert.strictEqual(win.reloads, 0, "not from the back-forward cache either");
});

test("M1: every reload in place is made a GET of the address first, the shown notice dropped", async () => {
    const win = fakeWindow();
    win.location.href = "https://pi.example.ts.net/devices/1?notice=sent&tab=apps#apps";
    win.install();
    await awayAndBack(win);
    await answer(win);
    assert.deepStrictEqual(win.steps, [
        "replace https://pi.example.ts.net/devices/1?tab=apps#apps",
        "reload",
    ]);
    assert.strictEqual(rc.reloadUrl("https://h/x?notice=a"), "https://h/x");
    assert.strictEqual(rc.reloadUrl("https://h/x?a=1&notice=b&c=2#f"), "https://h/x?a=1&c=2#f");
    assert.strictEqual(rc.reloadUrl("https://h/x#f"), "https://h/x#f");
    assert.strictEqual(rc.reloadUrl("https://h/x?notices=1"), "https://h/x?notices=1");
});

test("L2: a slow page with the server answering is started again once, then left to come", async () => {
    const win = fakeWindow();
    win.install();
    win.tap(link("/updates"));
    win.advance(CHIP_AFTER);
    await answer(win);
    for (let i = 0; i < 12; i++) {
        win.advance(PING_EVERY);
        await answer(win);
    }
    assert.deepStrictEqual(win.assigned, ["https://pi.example.ts.net/updates"], "once, at 13 s");
    // The network dropping and coming back still starts it again.
    win.advance(PING_EVERY);
    const dropped = win.pings.length;
    win.pings[dropped - 1].reject(new TypeError("Failed to fetch"));
    await settle();
    win.advance(PING_EVERY);
    assert.strictEqual(win.pings.length, dropped + 1);
    await answer(win);
    assert.strictEqual(win.assigned.length, 2);
});

test("L3: a save started during a wait ends it - the restart can't cancel the save", async () => {
    const win = fakeWindow();
    win.install();
    const tab = link("/apps");
    win.tap(tab);
    win.advance(CHIP_AFTER);
    win.pings[0].reject(new TypeError("Failed to fetch"));
    await settle();
    win.fire("doc", "change", { target: { hasAttribute: (n) => n === "data-autosave" } });
    assert.strictEqual(win.view.bar, false);
    assert.strictEqual(win.view.chip, false);
    assert.ok(!win.view.marked.has(tab));
    const before = win.pings.length;
    win.fire("win", "online");
    win.advance(60000);
    assert.strictEqual(win.pings.length, before, "nothing pinged any more");
    assert.deepStrictEqual(win.assigned, []);

    const submitted = fakeWindow();
    submitted.install();
    submitted.tap(link("/apps"));
    submitted.fire("doc", "submit", { target: {} });
    assert.strictEqual(submitted.view.bar, false);
});

test("L4: no reload in place over a field that was typed in or changed", async () => {
    const typed = { tagName: "INPUT", type: "text", value: "Ella", defaultValue: "" };
    const win = fakeWindow();
    win.controls = [{ tagName: "INPUT", type: "hidden", value: "x", defaultValue: "y" }, typed];
    win.install();
    await awayAndBack(win);
    await answer(win);
    assert.strictEqual(win.reloads, 0);
    assert.strictEqual(win.view.chip, false);

    const doc = (controls) => ({ querySelectorAll: () => controls });
    assert.ok(!rc.edited(doc([])));
    assert.ok(!rc.edited(doc([{ tagName: "INPUT", type: "text", value: "a", defaultValue: "a" }])));
    assert.ok(rc.edited(doc([{ tagName: "TEXTAREA", value: "hei", defaultValue: "" }])));
    assert.ok(rc.edited(doc([{ tagName: "INPUT", type: "checkbox", checked: true, defaultChecked: false }])));
    assert.ok(!rc.edited(doc([{ tagName: "INPUT", type: "submit", value: "Save", defaultValue: "" }])));
    const option = (selected, defaultSelected) => ({ selected, defaultSelected });
    assert.ok(rc.edited(doc([{ tagName: "SELECT", options: [option(false, true), option(true, false)] }])));
    assert.ok(!rc.edited(doc([{ tagName: "SELECT", options: [option(true, true), option(false, false)] }])));
    assert.ok(rc.edited(doc([{ tagName: "INPUT", type: "file", value: "C:\\fakepath\\a.mp3" }])));
});

test("L5: a pagehide that doesn't unload, then back: the wait's pings and Try again come again", async () => {
    const win = fakeWindow();
    win.install();
    win.tap(link("/music"));
    win.advance(CHIP_AFTER);
    assert.strictEqual(win.pings.length, 1);
    win.fire("win", "pagehide");
    win.advance(PING_TIMEOUT);
    await settle();
    win.advance(30000);
    assert.strictEqual(win.pings.length, 1, "stopped while hidden");
    win.show();
    assert.strictEqual(win.pings.length, 2, "a ping at once");
    win.advance(PING_TIMEOUT);
    await settle();
    win.advance(PING_EVERY);
    assert.ok(win.pings.length >= 3, "then every 5 s");
    assert.strictEqual(typeof win.view.retry, "function", "Try again (over 20 s since the tap)");
});

test("L9: the chip's words are set when it shows, so a screen reader announces them", () => {
    const made = [];
    const element = (tag) => {
        const el = {
            tag,
            hidden: false,
            textContent: "",
            children: [],
            attrs: {},
            setAttribute(name, value) {
                this.attrs[name] = value;
            },
            appendChild(child) {
                this.children.push(child);
            },
        };
        made.push(el);
        return el;
    };
    const body = element("body");
    const view = rc.domView({ document: { body, createElement: element } });
    view.chip(false);
    const chip = made.find((el) => el.className === "net-chip");
    const words = chip.children[0];
    assert.strictEqual(chip.attrs.role, "status");
    assert.strictEqual(chip.hidden, true);
    assert.strictEqual(words.textContent, "", "empty while hidden");
    view.chip(true);
    assert.strictEqual(chip.hidden, false);
    assert.strictEqual(words.textContent, "Connecting\u2026");
    view.chip(false);
    assert.strictEqual(words.textContent, "");
});

test("the strings and the script are ES5 (§5.7)", () => {
    assert.deepStrictEqual(rc.TEXT, { connecting: "Connecting…", retry: "Try again" });
    const src = require("node:fs")
        .readFileSync(require.resolve("../static/reconnect.js"), "utf8")
        .replace(/\/\*[\s\S]*?\*\//g, "")
        .replace(/^\s*\/\/.*$/gm, "");
    assert.ok(!/=>|\blet\s|\bconst\s|`|\bclass\s/.test(src), "no ES2015 syntax");
});

// 2026-10-25 (PLAN "PWA shows the login page after a Tailscale outage, though the session is
// valid"): the sign-in page asks GET /api/session before it stays, and goes back to the wanted page.
const LOGIN = "https://pi.example.ts.net/login";

test("loginReturn: where the sign-in page goes back to, and when it stays", () => {
    const { loginReturn, WANTED_MS, BOUNCE_MS } = rc;
    const t = 5000000;
    const fresh = (href) => ({ href, t: t - 1000 });
    // Not the sign-in page: nothing to do.
    assert.strictEqual(loginReturn("https://pi.example.ts.net/devices", "", null, null, t), null);
    assert.strictEqual(loginReturn("https://pi.example.ts.net/auth/verify-2fa", "", null, null, t), null);
    // The wanted page, then the referrer, then /.
    assert.strictEqual(
        loginReturn(LOGIN, "https://pi.example.ts.net/settings", fresh("https://pi.example.ts.net/devices/locate?device=2#location"), null, t),
        "/devices/locate?device=2#location"
    );
    assert.strictEqual(loginReturn(LOGIN, "https://pi.example.ts.net/settings", null, null, t), "/settings");
    assert.strictEqual(loginReturn(LOGIN, "", null, null, t), "/");
    // A wanted page from longer ago than 2 minutes doesn't count; the referrer then does.
    assert.strictEqual(
        loginReturn(LOGIN, "https://pi.example.ts.net/apps", { href: "/music", t: t - WANTED_MS }, null, t),
        "/apps"
    );
    // Q-1: nothing in the sign-in page's own address is read - the server never sets a next.
    assert.strictEqual(loginReturn(LOGIN + "?next=%2Fdevices%2F3%3Ftab%3Dapps", "", null, null, t), "/");
    assert.strictEqual(loginReturn(LOGIN + "?from=/music", "", fresh("/apps"), null, t), "/apps");
    assert.strictEqual(loginReturn(LOGIN + "?next=/.//evil.com/x", "", null, null, t), "/");
    assert.strictEqual(loginReturn(LOGIN + "?next=https://pi.example.ts.net//evil.com", "", null, null, t), "/");
    // Never another origin - not through the wanted record, not through the referrer.
    for (const evil of ["/.//evil.com", "//evil.com", "\\\\evil.com", "/\\evil.com", "https://evil.com",
        "/%2e//evil.com", "/a/..//evil.com", "https://pi.example.ts.net//evil.com", "javascript:alert(1)"]) {
        assert.strictEqual(loginReturn(LOGIN, "", fresh(evil), null, t), "/", "wanted " + evil);
        assert.strictEqual(loginReturn(LOGIN, evil, null, null, t), "/", "referrer " + evil);
    }
    // Never the sign-in pages themselves.
    assert.strictEqual(loginReturn(LOGIN, "https://pi.example.ts.net/login", fresh("/logout"), null, t), "/");
    assert.strictEqual(loginReturn(LOGIN, "", fresh("/api/session"), null, t), "/");
    // Went back less than 30 s ago: stay (never a loop).
    assert.strictEqual(loginReturn(LOGIN, "", fresh("/apps"), t - (BOUNCE_MS - 1), t), null);
    assert.strictEqual(loginReturn(LOGIN, "", fresh("/apps"), t - BOUNCE_MS, t), "/apps");
});

test("appPath: a page of this app, never one that leaves the origin (Q-1)", () => {
    const here = LOGIN;
    for (const evil of ["/.//evil.com", "//evil.com", "\\\\evil.com", "\\evil.com", "/\\evil.com", "https://evil.com",
        "http://pi.example.ts.net/devices", "/%2e//evil.com", "/a/..//evil.com", "https://pi.example.ts.net//evil.com",
        "javascript:alert(1)", "data:text/html,x", "", null, undefined, 42]) {
        assert.strictEqual(rc.appPath(evil, here), null, String(evil));
    }
    assert.strictEqual(rc.appPath("/devices/locate?device=1", here), "/devices/locate?device=1");
    assert.strictEqual(rc.appPath("https://pi.example.ts.net/devices/locate?device=1#location", here),
        "/devices/locate?device=1#location");
    assert.strictEqual(rc.appPath("/devices/1", here), "/devices/1");
    // Whatever it returns starts with one slash: location.replace stays on this origin.
    for (const ok of ["/", "/devices", "/x/./y", "/a/../b?c=//d"]) {
        const path = rc.appPath(ok, here);
        assert.ok(path && path.charAt(0) === "/" && path.charAt(1) !== "/" && path.charAt(1) !== "\\", ok + " -> " + path);
    }
});

test("the sign-in page with a live session goes back to the page the return reload wanted", async () => {
    // The reload in place on the second phone's Locate page landed on /login: scroll-restore.js took
    // its place record from window.name (handyPlaceRecord); sessionStorage lost, as an installed
    // iPhone app can. The query comes back too (Q-6).
    const now = 1000000;
    const win = fakeWindow({ href: LOGIN });
    win.handyPlaceRecord = { path: "/devices/locate", href: "/devices/locate?device=2", y: 300, t: now - 5000 };
    win.install();
    assert.strictEqual(win.pings.length, 1, "asked at once");
    assert.strictEqual(win.pings[0].url, "/api/session");
    assert.strictEqual(win.pings[0].options.credentials, "same-origin");
    assert.strictEqual(win.pings[0].options.cache, "no-store");
    await answer(win, 204);
    assert.deepStrictEqual(win.replaced, ["/devices/locate?device=2"]);
    assert.strictEqual(win.stored["handy-login-bounce"], String(now), "the way back is remembered");
    assert.strictEqual(win.name, "handy-login-bounce:" + now, "in window.name too (Q-5)");

    // A place record still in window.name counts as well; an older one without `href` its path.
    const named = fakeWindow({ href: LOGIN, name: "handy-scroll:" + JSON.stringify({ path: "/apps", y: 0, t: now - 1000 }) });
    named.install();
    await answer(named, 204);
    assert.deepStrictEqual(named.replaced, ["/apps"]);
});

test("a fresh place record wins over a stale sessionStorage record (Q-6)", async () => {
    const now = 1000000;
    const stale = JSON.stringify({ href: "/music", t: now - 60000 });
    const win = fakeWindow({ href: LOGIN, stored: { "handy-wanted": stale } });
    win.handyPlaceRecord = { path: "/devices/locate", href: "/devices/locate?device=2", y: 0, t: now - 2000 };
    win.install();
    await answer(win, 204);
    assert.deepStrictEqual(win.replaced, ["/devices/locate?device=2"]);
    // ... and the other way round: the newer sessionStorage record wins.
    const newer = fakeWindow({ href: LOGIN, stored: { "handy-wanted": JSON.stringify({ href: "/music", t: now - 1000 }) } });
    newer.handyPlaceRecord = { path: "/apps", y: 0, t: now - 50000 };
    newer.install();
    await answer(newer, 204);
    assert.deepStrictEqual(newer.replaced, ["/music"]);
});

test("the loop guard holds in window.name when sessionStorage is lost (Q-5)", () => {
    const now = 1000000;
    const win = fakeWindow({ href: LOGIN, name: "handy-login-bounce:" + (now - 1000) });
    win.sessionStorage.getItem = () => {
        throw new Error("lost");
    };
    win.install();
    assert.strictEqual(win.pings.length, 0, "went back 1 s ago (window.name): stay");
    const later = fakeWindow({ href: LOGIN, name: "handy-login-bounce:" + (now - 31000) });
    later.install();
    assert.strictEqual(later.pings.length, 1, "31 s ago: asks again");
});

test("a resumed sign-in page asks again: shown after being hidden, back from the cache (Q-7)", async () => {
    const win = fakeWindow({ href: LOGIN });
    win.install();
    await answer(win, 401);
    assert.strictEqual(win.pings.length, 1);
    // Hidden briefly (no reload in place), shown again: asks once more.
    win.hide();
    win.advance(5000);
    win.show();
    assert.strictEqual(win.pings.length, 2);
    assert.strictEqual(win.pings[1].url, "/api/session");
    // A second show while that ask is out asks nothing more.
    win.hide();
    win.show();
    assert.strictEqual(win.pings.length, 2, "one ask at a time");
    await answer(win, 401);
    // Back from the back-forward cache: asks again, and a 204 goes back.
    win.fire("win", "pageshow", { persisted: true });
    assert.strictEqual(win.pings.length, 3);
    await answer(win, 204);
    assert.deepStrictEqual(win.replaced, ["/"]);
    // Another page never asks on being shown.
    const elsewhere = fakeWindow();
    elsewhere.install();
    elsewhere.hide();
    elsewhere.show();
    assert.ok(elsewhere.pings.every((p) => p.url !== "/api/session"));
});

test("a navigation remembers where it goes; the sign-in page uses it", async () => {
    const win = fakeWindow();
    win.install();
    win.tap(link("/apps?x=1"));
    const wanted = JSON.parse(win.stored["handy-wanted"]);
    assert.strictEqual(wanted.href, "https://pi.example.ts.net/apps?x=1");
    // The tap's page came back as the sign-in page.
    const login = fakeWindow({ href: LOGIN, stored: win.stored });
    login.install();
    await answer(login, 204);
    assert.deepStrictEqual(login.replaced, ["/apps?x=1"]);
    assert.strictEqual(login.stored["handy-wanted"], undefined, "used once");
});

test("the sign-in page stays for a 401, no answer, a refused password and a sign-in on its way", async () => {
    const signedOut = fakeWindow({ href: LOGIN });
    signedOut.install();
    await answer(signedOut, 401);
    assert.deepStrictEqual(signedOut.replaced, []);

    const silent = fakeWindow({ href: LOGIN });
    silent.install();
    silent.advance(PING_TIMEOUT);
    await settle();
    await answer(silent, 204); // too late: already given up
    assert.deepStrictEqual(silent.replaced, []);

    const refused = fakeWindow({ href: LOGIN, postAnswer: true });
    refused.install();
    assert.strictEqual(refused.pings.length, 0, "a POST's answer asks nothing");

    const typing = fakeWindow({ href: LOGIN });
    typing.install();
    typing.fire("doc", "submit", { type: "submit" });
    await answer(typing, 204);
    assert.deepStrictEqual(typing.replaced, [], "the sign-in's POST goes on");

    const looping = fakeWindow({ href: LOGIN, stored: { "handy-login-bounce": String(1000000 - 1000) } });
    looping.install();
    assert.strictEqual(looping.pings.length, 0, "went back 1 s ago: stay");

    const elsewhere = fakeWindow();
    elsewhere.install();
    assert.strictEqual(elsewhere.pings.length, 0, "only the sign-in page asks");
});

test("a ping redirected to the sign-in page asks /api/session before it counts", async () => {
    const win = fakeWindow();
    win.install();
    win.tap(link("/apps"));
    win.advance(CHIP_AFTER);
    // The first ping (no answer) fails: the network isn't back.
    win.advance(PING_TIMEOUT);
    await settle();
    win.advance(PING_EVERY - 1000);
    assert.strictEqual(win.pings.length, 2);
    win.pings[1].resolve({ status: 200, redirected: true, url: LOGIN });
    await settle();
    assert.strictEqual(win.pings.length, 3);
    assert.strictEqual(win.pings[2].url, "/api/session");
    assert.deepStrictEqual(win.assigned, [], "not yet");
    await answer(win, 204);
    assert.deepStrictEqual(win.assigned, ["https://pi.example.ts.net/apps"], "the wanted page again");

    // The session check unanswered: no answer, the wait goes on.
    const flaky = fakeWindow();
    flaky.install();
    flaky.tap(link("/apps"));
    flaky.advance(CHIP_AFTER);
    flaky.advance(PING_TIMEOUT);
    await settle();
    flaky.advance(PING_EVERY - 1000);
    flaky.pings[1].resolve({ status: 200, redirected: true, url: LOGIN });
    await settle();
    flaky.pings[2].reject(new Error("offline"));
    await settle();
    assert.deepStrictEqual(flaky.assigned, []);
});
