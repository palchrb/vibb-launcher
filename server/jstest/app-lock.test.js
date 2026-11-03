// Behaviour of static/app-lock.js (design 74 step 3, the app lock's page side): idle, hidden, the back-forward cache,
// the sessionStorage input time across a reload, the beacon's throttle, the veil, handyLock.due().
// Run: node --test jstest/app-lock.test.js
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const al = require("../static/app-lock.js");
const reconnect = require("../static/reconnect.js");
const { install, BEACON_EVERY, USED_KEY, SENT_KEY } = al;

const MIN = 60 * 1000;

// A fake window: a clock, timers run by the clock, events fired by hand, the <html> element's attributes and
// classes, sessionStorage (or one that throws), fetches recorded and answered by the test.
function fakeWindow({ lockMs = 5 * MIN, idle = 0, stored = {}, storage = true, path = "/devices/1", search = "?tab=apps", hash = "", navigation = null } = {}) {
    let clock = 10000000;
    let timerId = 0;
    const timers = new Map();
    const listeners = {};
    const classes = new Set();
    const attrs = {};
    if (lockMs !== null) attrs["data-lock-ms"] = String(lockMs);
    if (idle !== null) attrs["data-lock-idle"] = String(idle);
    const win = {
        replaced: [],
        fetches: [],
        stored: Object.assign({}, stored),
        visibility: "visible",
        classes,
        location: {
            pathname: path,
            search,
            hash,
            replace: (to) => win.replaced.push(to),
        },
        setTimeout: (fn, ms) => {
            timerId += 1;
            timers.set(timerId, { fn, at: clock + ms });
            return timerId;
        },
        clearTimeout: (id) => timers.delete(id),
        fetch: (url, options) =>
            new Promise((resolve) => {
                win.fetches.push({ url, options, resolve });
            }),
        addEventListener: (type, fn) => {
            (listeners["win:" + type] = listeners["win:" + type] || []).push(fn);
        },
        performance: navigation ? { getEntriesByType: (t) => (t === "navigation" ? [navigation] : []) } : undefined,
        document: {
            title: "Anna's phone · Vibb Phone",
            documentElement: {
                getAttribute: (name) => (name in attrs ? attrs[name] : null),
                classList: { add: (c) => classes.add(c), remove: (c) => classes.delete(c) },
            },
            get visibilityState() {
                return win.visibility;
            },
            addEventListener: (type, fn) => {
                (listeners["doc:" + type] = listeners["doc:" + type] || []).push(fn);
            },
        },
    };
    win.sessionStorage = storage
        ? {
              getItem: (key) => (key in win.stored ? win.stored[key] : null),
              setItem: (key, value) => {
                  win.stored[key] = value;
              },
          }
        : {
              getItem: () => {
                  throw new Error("SecurityError");
              },
              setItem: () => {
                  throw new Error("SecurityError");
              },
          };
    win.now = () => clock;
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
    win.input = () => win.fire("doc", "pointerdown");
    win.hide = () => {
        win.visibility = "hidden";
        win.fire("doc", "visibilitychange");
    };
    win.show = () => {
        win.visibility = "visible";
        win.fire("doc", "visibilitychange");
    };
    // The wall clock can be stepped (`wallShift`) and the page's own clock paused (`monoPause`, a sleeping phone).
    win.wallShift = 0;
    win.monoPause = 0;
    win.install = () =>
        install(win, { now: () => win.now() + win.wallShift, mono: () => win.now() - win.monoPause });
    return win;
}

const LOCK = "/lock?next=" + encodeURIComponent("/devices/1?tab=apps");

test("without the server's mark nothing happens, but handyLock is there", () => {
    const win = fakeWindow({ lockMs: null });
    win.install();
    assert.strictEqual(win.handyLock.due(), false);
    win.advance(24 * 60 * MIN);
    win.input();
    assert.deepStrictEqual(win.replaced, []);
    assert.deepStrictEqual(win.fetches, [], "no beacon without the lock");
    // A 423 elsewhere (reconnect.js, passkey.js) uses its lock(): to /lock, back here afterwards.
    win.handyLock.lock();
    assert.deepStrictEqual(win.replaced, [LOCK]);
    assert.ok(win.classes.has("locked"));
});

test("a page that is due when it loads locks at once, before anything shows", () => {
    const win = fakeWindow({ idle: 5 * MIN });
    const images = [];
    win.Image = function () { images.push(this); };
    win.install();
    assert.deepStrictEqual(images.map((i) => i.src), ["/static/vibb-rest.svg"], "the locked page's night picture, fetched");
    assert.ok(win.classes.has("locked"));
    assert.deepStrictEqual(win.replaced, [LOCK]);
    assert.strictEqual(win.handyLock.due(), true);
    // Once: a second ask doesn't navigate again.
    win.handyLock.lock();
    win.input();
    assert.strictEqual(win.replaced.length, 1);
});

test("a page lying open with no input locks when its time runs out", () => {
    const win = fakeWindow({ idle: 1 * MIN });
    win.install();
    assert.deepStrictEqual(win.replaced, []);
    win.advance(4 * MIN - 1000);
    assert.deepStrictEqual(win.replaced, []);
    assert.strictEqual(win.handyLock.due(), false);
    win.advance(2000);
    assert.deepStrictEqual(win.replaced, [LOCK]);
    assert.ok(win.classes.has("locked"));
});

test("an input starts the time again; one that comes too late locks instead", () => {
    const win = fakeWindow();
    win.install();
    win.advance(4 * MIN);
    win.input();
    win.advance(4 * MIN);
    assert.deepStrictEqual(win.replaced, [], "the input counted");
    // The timers held (a phone's background): the late input locks, it isn't a use.
    const late = fakeWindow();
    late.setTimeout = () => 0;
    late.install();
    late.advance(5 * MIN);
    assert.deepStrictEqual(late.replaced, [], "no timer ran");
    late.input();
    assert.deepStrictEqual(late.replaced, [LOCK]);
    assert.strictEqual(late.stored[USED_KEY], undefined, "not counted as a use");
    assert.strictEqual(late.fetches.length, 0, "no beacon");
});

test("the input time in sessionStorage carries over a reload; the later of it and the server's counts", () => {
    // The page before the reload: an input 1 min ago.
    const first = fakeWindow({ idle: 0 });
    first.install();
    first.advance(3 * MIN);
    first.input();
    const kept = first.stored[USED_KEY];
    assert.ok(kept, "kept for the next page");
    // A scripted reload 1 min later: the server's last use is 4 min ago (a reload never moves it), the input 1 min.
    const reloaded = fakeWindow({ idle: 4 * MIN, stored: { [USED_KEY]: kept } });
    reloaded.advance(first.now() + MIN - reloaded.now());
    reloaded.install();
    assert.deepStrictEqual(reloaded.replaced, []);
    reloaded.advance(4 * MIN - 1000);
    assert.deepStrictEqual(reloaded.replaced, []);
    reloaded.advance(2000);
    assert.deepStrictEqual(reloaded.replaced, [LOCK]);
    // A newer server use (another tab's tap) wins over an older stored input.
    const other = fakeWindow({ idle: 0, stored: { [USED_KEY]: "1" } });
    other.install();
    assert.deepStrictEqual(other.replaced, []);
});

test("an unreadable sessionStorage reads as the server's last use", () => {
    const win = fakeWindow({ storage: false, idle: 2 * MIN });
    win.install();
    win.advance(3 * MIN - 1000);
    assert.deepStrictEqual(win.replaced, []);
    win.advance(2000);
    assert.deepStrictEqual(win.replaced, [LOCK]);
    // Inputs still count in memory.
    const kept = fakeWindow({ storage: false });
    kept.install();
    kept.advance(4 * MIN);
    kept.input();
    kept.advance(4 * MIN);
    assert.deepStrictEqual(kept.replaced, []);
});

test("hidden, the page is veiled; shown again in time it comes back, after its time it locks", () => {
    const win = fakeWindow();
    // A phone holds a hidden page's timers: the clock moves without them.
    win.setTimeout = () => 0;
    win.install();
    win.hide();
    assert.ok(win.classes.has("veiled"));
    win.show();
    assert.ok(!win.classes.has("veiled"));
    assert.deepStrictEqual(win.replaced, []);
    // Hidden past the time.
    win.hide();
    win.advance(6 * MIN);
    assert.deepStrictEqual(win.replaced, []);
    win.show();
    assert.ok(win.classes.has("veiled"), "still veiled while it goes");
    assert.ok(win.classes.has("locked"));
    assert.deepStrictEqual(win.replaced, [LOCK]);
});

test("back from the back-forward cache after its time it locks; before it, it shows", () => {
    const win = fakeWindow({ hash: "#apps" });
    win.setTimeout = () => 0;
    win.install();
    win.fire("win", "pagehide", { persisted: true });
    assert.ok(win.classes.has("veiled"), "the cached page is veiled");
    win.fire("win", "pageshow", { persisted: true });
    assert.ok(!win.classes.has("veiled"));
    win.fire("win", "pagehide", { persisted: true });
    win.advance(5 * MIN);
    assert.deepStrictEqual(win.replaced, []);
    win.fire("win", "pageshow", { persisted: true });
    assert.deepStrictEqual(win.replaced, ["/lock?next=" + encodeURIComponent("/devices/1?tab=apps#apps")]);
    // A first pageshow isn't a restore.
    const fresh = fakeWindow();
    fresh.install();
    fresh.fire("win", "pageshow", { persisted: false });
    assert.deepStrictEqual(fresh.replaced, []);
});

test("an unlock in this tab (the newer stored input) keeps a restored page open", () => {
    const win = fakeWindow();
    // A cached page's timers don't run.
    win.setTimeout = () => 0;
    win.install();
    win.fire("win", "pagehide", { persisted: true });
    win.advance(6 * MIN);
    win.stored[USED_KEY] = String(win.now());
    win.fire("win", "pageshow", { persisted: true });
    assert.deepStrictEqual(win.replaced, []);
});

test("the beacon: on an input while visible, at most once a minute, across pages; a 423 locks", async () => {
    const win = fakeWindow();
    win.install();
    assert.strictEqual(win.fetches.length, 0, "nothing without an input");
    win.input();
    assert.strictEqual(win.fetches.length, 1);
    assert.strictEqual(win.fetches[0].url, "/api/used");
    assert.strictEqual(win.fetches[0].options.method, "POST");
    assert.strictEqual(win.fetches[0].options.credentials, "same-origin");
    win.advance(BEACON_EVERY - 1000);
    win.input();
    assert.strictEqual(win.fetches.length, 1, "throttled");
    win.advance(1000);
    win.input();
    assert.strictEqual(win.fetches.length, 2);
    // The next page knows when the last one went (sessionStorage).
    const next = fakeWindow({ stored: { [SENT_KEY]: win.stored[SENT_KEY] } });
    next.advance(win.now() + 1000 - next.now());
    next.install();
    next.input();
    assert.strictEqual(next.fetches.length, 0);
    // Hidden: none (a key event in the background).
    const hidden = fakeWindow();
    hidden.install();
    hidden.visibility = "hidden";
    hidden.fire("doc", "keydown");
    assert.strictEqual(hidden.fetches.length, 0);
    // The server's lock answers: to the lock.
    win.advance(BEACON_EVERY);
    win.input();
    win.fetches[2].resolve({ status: 423 });
    await new Promise((resolve) => setImmediate(resolve));
    assert.deepStrictEqual(win.replaced, [LOCK]);
});

test("the lock screen itself never locks", () => {
    const win = fakeWindow({ idle: 60 * MIN, path: "/lock", search: "?next=%2Fdevices" });
    win.install();
    assert.deepStrictEqual(win.replaced, []);
    assert.strictEqual(win.handyLock.due(), false);
});

test("the script is ES5 (§5.7)", () => {
    const src = require("node:fs")
        .readFileSync(require.resolve("../static/app-lock.js"), "utf8")
        .replace(/\/\*[\s\S]*?\*\//g, "")
        .replace(/^\s*\/\/.*$/gm, "");
    assert.ok(!/=>|\blet\s|\bconst\s|`|\bclass\s/.test(src), "no ES2015 syntax");
});

test("a history load (Back past the bfcache) or a cached page counts only the stored input; none is due (QA M1)", () => {
    // Served long ago with a fresh-looking idle: the stored input says 6 min.
    const back = fakeWindow({ idle: 2000, navigation: { type: "back_forward", transferSize: 900, decodedBodySize: 900 } });
    back.stored[USED_KEY] = String(back.now() - 6 * MIN);
    back.install();
    assert.deepStrictEqual(back.replaced, [LOCK]);
    assert.ok(back.classes.has("locked"));
    // No stored input at all: due.
    const none = fakeWindow({ idle: 0, navigation: { type: "back_forward", transferSize: 900, decodedBodySize: 900 } });
    none.install();
    assert.deepStrictEqual(none.replaced, [LOCK]);
    // From the HTTP cache (nothing transferred) on a plain navigation: the same.
    const cached = fakeWindow({ idle: 0, navigation: { type: "navigate", transferSize: 0, decodedBodySize: 5000 } });
    cached.install();
    assert.deepStrictEqual(cached.replaced, [LOCK]);
    // A recent stored input keeps a history load open; a fresh navigation uses the server's numbers.
    const recent = fakeWindow({ idle: 0, navigation: { type: "back_forward", transferSize: 900, decodedBodySize: 900 } });
    recent.stored[USED_KEY] = String(recent.now() - MIN);
    recent.install();
    assert.deepStrictEqual(recent.replaced, []);
    const fresh = fakeWindow({ idle: 0, navigation: { type: "navigate", transferSize: 900, decodedBodySize: 900 } });
    fresh.install();
    assert.deepStrictEqual(fresh.replaced, []);
});

test("pagehide into the back-forward cache veils the page and its title says only Vibb; shown again, both come back", () => {
    const win = fakeWindow();
    win.install();
    win.fire("win", "pagehide", { persisted: false });
    assert.ok(!win.classes.has("veiled"), "a plain unload isn't veiled");
    win.fire("win", "pagehide", { persisted: true });
    assert.ok(win.classes.has("veiled"));
    assert.strictEqual(win.document.title, "Vibb");
    win.fire("win", "pageshow", { persisted: true });
    assert.ok(!win.classes.has("veiled"));
    assert.strictEqual(win.document.title, "Anna's phone · Vibb Phone");
    win.hide();
    assert.strictEqual(win.document.title, "Vibb");
    win.show();
    assert.strictEqual(win.document.title, "Anna's phone · Vibb Phone");
    // Locked: the title stays "Vibb".
    const due = fakeWindow({ idle: 5 * MIN });
    due.install();
    assert.strictEqual(due.document.title, "Vibb");
});

test("a wall clock set back while the page is open doesn't hold the lock; nor does a sleeping phone's paused clock (QA L1)", () => {
    const stepped = fakeWindow();
    stepped.install();
    stepped.advance(2 * MIN);
    stepped.wallShift = -10 * MIN;
    stepped.advance(3 * MIN + 1000);
    assert.deepStrictEqual(stepped.replaced, [LOCK], "on time by the page's own clock");
    const slept = fakeWindow();
    slept.setTimeout = () => 0;
    slept.install();
    slept.hide();
    slept.advance(6 * MIN);
    slept.monoPause = 6 * MIN;
    slept.show();
    assert.deepStrictEqual(slept.replaced, [LOCK], "the wall clock saw the sleep");
});

test("a script's synthetic event is no use (isTrusted)", () => {
    const win = fakeWindow();
    win.install();
    win.advance(4 * MIN);
    win.fire("doc", "pointerdown", { type: "pointerdown", isTrusted: false });
    assert.strictEqual(win.stored[USED_KEY], undefined);
    assert.strictEqual(win.fetches.length, 0);
    win.advance(MIN + 1000);
    assert.deepStrictEqual(win.replaced, [LOCK]);
});

// The real app-lock.js and reconnect.js on one page (app-lock.js first, as the head loads them): the lock's `next` after a
// long time away is app-lock.js's to pick, since it locks first and reconnect.js's later lock() is then a no-op.
test("a lock due on the return: next is Devices (`/`) after over 10 minutes away, the page they were on after less", () => {
    const { HOME_AFTER } = al;
    assert.strictEqual(HOME_AFTER, 10 * MIN);
    for (const [away, next] of [[11 * MIN, "/"], [9 * MIN, "/devices/1?tab=apps"]]) {
        for (const via of ["visibility", "pageshow"]) {
            const win = fakeWindow({ lockMs: 5 * MIN });
            win.install();
            assert.strictEqual(win.handyLock.homeAfter, HOME_AFTER);
            const view = { bar() {}, chip() {}, offer() {}, mark() {} };
            reconnect.install(win, { now: () => win.now() + win.wallShift, view });
            if (via === "visibility") {
                win.hide();
                win.wallShift += away; // a phone holds a hidden page's timers: the time passes, no timer runs
                win.show();
            } else {
                win.fire("win", "pagehide", { persisted: true });
                win.visibility = "hidden";
                win.fire("doc", "visibilitychange");
                win.wallShift += away;
                win.visibility = "visible";
                win.fire("win", "pageshow", { persisted: true });
            }
            assert.deepStrictEqual(win.replaced, ["/lock?next=" + encodeURIComponent(next)], `${away / MIN} min via ${via}`);
        }
    }
});
