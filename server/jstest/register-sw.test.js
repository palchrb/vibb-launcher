// Behaviour of static/register-sw.js: the worker is registered at load; the app badge follows the
// bell's unread count where there is a bell (design 35 §5.3); Sign Out takes the subscription; the push sync keeps
// this browser's notifications on (Push never lost silently, 2026-11-04).
// Run: node --test jstest/register-sw.test.js
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const { install, SIGN_OUT_WAIT } = require("../static/register-sw.js");

function fakeWindow({ unread, badge = true } = {}) {
    const log = [];
    const listeners = {};
    const navigator = {
        serviceWorker: {
            register: (url) => {
                log.push(["register", url]);
                return Promise.resolve({ update: () => { log.push(["update"]); return Promise.resolve(); } });
            },
        },
    };
    if (badge) {
        navigator.setAppBadge = (n) => {
            log.push(["badge", n]);
            return Promise.resolve();
        };
        navigator.clearAppBadge = () => {
            log.push(["badge", 0]);
            return Promise.resolve();
        };
    }
    const bell = unread === undefined ? null : { getAttribute: () => String(unread) };
    const win = {
        navigator,
        document: { querySelector: (selector) => (selector === "[data-unread]" ? bell : null) },
        addEventListener: (type, fn) => {
            listeners[type] = fn;
        },
    };
    return { win, log, load: () => listeners.load() };
}

test("registers the worker at load and asks for an update check at once", async () => {
    const w = fakeWindow();
    install(w.win);
    assert.deepStrictEqual(w.log, []);
    w.load();
    assert.deepStrictEqual(w.log, [["register", "/sw.js"]]);
    await new Promise((resolve) => setTimeout(resolve, 0));
    assert.deepStrictEqual(w.log, [["register", "/sw.js"], ["update"]]);
    // A browser whose register() returns nothing (an old WebView stub): no throw.
    const bare = fakeWindow();
    bare.win.navigator.serviceWorker.register = (url) => { bare.log.push(["register", url]); };
    install(bare.win);
    bare.load();
    assert.deepStrictEqual(bare.log, [["register", "/sw.js"]]);
});

test("the badge follows the bell, and stays untouched without one", () => {
    let w = fakeWindow({ unread: 3 });
    install(w.win);
    assert.deepStrictEqual(w.log, [["badge", 3]]);
    w = fakeWindow({ unread: 0 });
    install(w.win);
    assert.deepStrictEqual(w.log, [["badge", 0]]);
    w = fakeWindow();
    install(w.win);
    assert.deepStrictEqual(w.log, []);
    w = fakeWindow({ unread: 3, badge: false });
    install(w.win);
    assert.deepStrictEqual(w.log, []);
});

// Sign Out (the user, 2026-10-25): the form gets this browser's subscription endpoint, and the
// browser unsubscribes before the form goes - at most SIGN_OUT_WAIT ms later.
function signOutWindow({ subscription, worker = true, unsubscribe } = {}) {
    const log = [];
    const timers = [];
    const listeners = {};
    const field = { value: "" };
    const form = {
        querySelector: (selector) => (selector === "input[name=endpoint]" ? field : null),
        addEventListener: (type, fn) => {
            listeners[type] = fn;
        },
        submit: () => log.push(["submit", field.value]),
    };
    const sub = subscription && {
        endpoint: subscription,
        unsubscribe: unsubscribe || (() => {
            log.push(["unsubscribe"]);
            return Promise.resolve(true);
        }),
    };
    const navigator = {};
    if (worker) {
        navigator.serviceWorker = {
            register: () => Promise.resolve({}),
            ready: Promise.resolve({ pushManager: { getSubscription: () => Promise.resolve(sub || null) } }),
        };
    }
    const win = {
        navigator,
        document: { querySelector: (selector) => (selector === "form[data-sign-out]" ? form : null) },
        addEventListener: () => {},
        setTimeout: (fn, ms) => timers.push({ fn, ms }),
    };
    const submit = () => {
        const event = { prevented: false, preventDefault() { this.prevented = true; } };
        if (listeners.submit) listeners.submit(event);
        return event;
    };
    return { win, log, field, timers, submit, listening: () => !!listeners.submit };
}

const ticks = () => new Promise((resolve) => setTimeout(resolve, 0));
const FAKE_ENDPOINT = "https://web.push.apple.com/QK1-fake-endpoint";

test("sign out sends the endpoint and unsubscribes first", async () => {
    const w = signOutWindow({ subscription: FAKE_ENDPOINT });
    install(w.win);
    await ticks();
    assert.strictEqual(w.field.value, FAKE_ENDPOINT);
    const event = w.submit();
    assert.strictEqual(event.prevented, true);
    await ticks();
    assert.deepStrictEqual(w.log, [["unsubscribe"], ["submit", FAKE_ENDPOINT]]);
    // The cap fires later: the form went once.
    assert.strictEqual(w.timers[0].ms, SIGN_OUT_WAIT);
    assert.strictEqual(SIGN_OUT_WAIT, 300);
    w.timers[0].fn();
    assert.strictEqual(w.log.filter((l) => l[0] === "submit").length, 1);
});

test("a slow unsubscribe never holds the sign-out past the cap", async () => {
    const w = signOutWindow({ subscription: FAKE_ENDPOINT, unsubscribe: () => new Promise(() => {}) });
    install(w.win);
    await ticks();
    w.submit();
    await ticks();
    assert.deepStrictEqual(w.log, []);
    w.timers[0].fn();
    assert.deepStrictEqual(w.log, [["submit", FAKE_ENDPOINT]]);
});

test("without a subscription the form goes with an empty field; without a worker it is left alone", async () => {
    const w = signOutWindow();
    install(w.win);
    await ticks();
    w.submit();
    await ticks();
    assert.deepStrictEqual(w.log, [["submit", ""]]);
    const bare = signOutWindow({ worker: false, subscription: FAKE_ENDPOINT });
    install(bare.win);
    await ticks();
    assert.strictEqual(bare.listening(), false, "the browser's own submit");
    assert.strictEqual(bare.field.value, "");
});

// Push never lost silently (2026-11-04): at a signed-in page's load the browser's subscription goes to the sync route;
// none (or a dead one) is made again with the server's key; a subscribe refused without a tap shows the quiet banner.
const { pushSync, SYNC_SPACING } = require("../static/register-sw.js");

const SERVER_KEY = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
const NEW_ENDPOINT = "https://web.push.apple.com/QK1-fake-new";
const BANNER_WORDS = { off: "Varsler er av på denne telefonen", on: "Slå på", hide: "Skjul", failed: "Fikk ikke slått dem på" };

function node(tag) {
    const el = {
        tagName: tag,
        children: [],
        parentNode: null,
        listeners: {},
        className: "",
        id: "",
        textContent: "",
        attributes: {},
        setAttribute(name, value) { this.attributes[name] = value; },
        appendChild(child) { child.parentNode = this; this.children.push(child); return child; },
        insertBefore(child, before) {
            child.parentNode = this;
            const at = before ? this.children.indexOf(before) : -1;
            if (at < 0) this.children.push(child); else this.children.splice(at, 0, child);
            this.scrollHeight += 60;
            return child;
        },
        removeChild(child) { this.children.splice(this.children.indexOf(child), 1); child.parentNode = null; },
        addEventListener(type, fn) { this.listeners[type] = fn; },
        click() { this.listeners.click(); },
        scrollTop: 0,
        scrollHeight: 1000,
    };
    return el;
}

function syncWindow({
    signedIn = true,
    thisDevice = false,
    permission = "granted",
    subscription = null,
    subscribe,
    answers = [],
    off = null,
    storageThrows = false,
    session = {},
    scrolled = 0,
} = {}) {
    const log = [];
    const local = { "vibb.push-off": off };
    const main = node("main");
    main.scrollTop = scrolled;
    const title = node("h1");
    title.className = "page-title";
    main.appendChild(title);
    main.querySelector = (s) => (s === ".page-title" ? title : null);
    const byId = () => main.children.find((c) => c.id === "push-banner") || null;
    const manager = {
        getSubscription: () => Promise.resolve(subscription),
        subscribe: (options) => {
            log.push(["subscribe", options.applicationServerKey instanceof Uint8Array, options.userVisibleOnly]);
            if (subscribe) return subscribe();
            return Promise.resolve({
                endpoint: NEW_ENDPOINT,
                toJSON: () => ({ endpoint: NEW_ENDPOINT, keys: { p256dh: "p-new", auth: "a-new" } }),
            });
        },
    };
    const storage = (map) => ({
        getItem: (k) => { if (storageThrows) throw new Error("SecurityError"); return k in map ? map[k] : null; },
        setItem: (k, v) => { if (storageThrows) throw new Error("SecurityError"); map[k] = v; },
        removeItem: (k) => { if (storageThrows) throw new Error("SecurityError"); map[k] = null; },
    });
    const win = {
        document: {
            querySelector: (s) => (s === "[data-signed-in]" ? (signedIn ? {} : null) : s === "main" ? main : null),
            getElementById: (id) => (id === "push-on-form" ? (thisDevice ? {} : null) : id === "push-banner" ? byId() : null),
            createElement: (tag) => node(tag),
        },
        navigator: { serviceWorker: { ready: Promise.resolve({ pushManager: manager }) } },
        PushManager: function () {},
        isSecureContext: true,
        Notification: { permission },
        localStorage: storage(local),
        sessionStorage: storage(session),
        atob: (s) => Buffer.from(s, "base64").toString("binary"),
        btoa: (s) => Buffer.from(s, "binary").toString("base64"),
        fetch: (url, options) => {
            const body = Object.fromEntries(new URLSearchParams(options.body));
            log.push(["post", url, options.redirect, body]);
            const next = answers.shift();
            if (!next) return Promise.reject(new Error("offline"));
            return Promise.resolve({ ok: true, json: () => Promise.resolve(next) });
        },
    };
    return { win, log, local, session, main, banner: byId };
}

const settled = () => new Promise((resolve) => setTimeout(resolve, 20));

function held(endpoint = FAKE_ENDPOINT) {
    const sub = {
        endpoint,
        ended: false,
        toJSON: () => ({ endpoint, keys: { p256dh: "p-key", auth: "a-key" } }),
        unsubscribe: () => { sub.ended = true; return Promise.resolve(true); },
    };
    return sub;
}

test("a signed-in page sends this browser's subscription once per spacing; a sign-in page clears it", async () => {
    const session = {};
    let w = syncWindow({ subscription: held(), answers: [{ state: "stored" }], session });
    await pushSync(w.win);
    assert.deepStrictEqual(w.log, [[
        "post",
        "/notifications/subscriptions/sync",
        "manual",
        { endpoint: FAKE_ENDPOINT, p256dh: "p-key", auth: "a-key" },
    ]]);
    assert.strictEqual(w.banner(), null);
    w = syncWindow({ subscription: held(), answers: [{ state: "known" }], session });
    assert.strictEqual(pushSync(w.win), null, "within the spacing");
    assert.ok(SYNC_SPACING >= 60 * 1000);
    // The sign-in page (or the lock screen) has no tab bar: the next signed-in page checks at once.
    w = syncWindow({ signedIn: false, session });
    assert.strictEqual(pushSync(w.win), null);
    assert.deepStrictEqual(w.log, []);
    w = syncWindow({ subscription: held(), answers: [{ state: "known" }], session });
    await pushSync(w.win);
    assert.strictEqual(w.log.length, 1);
});

test("no subscription: a new one with the server's key, sent; none for another key or a dead one either", async () => {
    let w = syncWindow({ answers: [{ state: "none", key: SERVER_KEY, words: BANNER_WORDS }, { state: "stored" }] });
    await pushSync(w.win);
    assert.deepStrictEqual(w.log[0], ["post", "/notifications/subscriptions/sync", "manual", {}]);
    assert.deepStrictEqual(w.log[1], ["subscribe", true, true]);
    assert.deepStrictEqual(w.log[2][3], { endpoint: NEW_ENDPOINT, p256dh: "p-new", auth: "a-new" });
    assert.strictEqual(w.banner(), null);
    for (const state of ["gone", "other_key"]) {
        const old = held();
        w = syncWindow({ subscription: old, answers: [{ state, key: SERVER_KEY, words: BANNER_WORDS }, { state: "stored" }] });
        await pushSync(w.win);
        assert.ok(old.ended, state);
        assert.deepStrictEqual(w.log[2][3], { endpoint: NEW_ENDPOINT, p256dh: "p-new", auth: "a-new", replaces: FAKE_ENDPOINT }, state);
    }
});

test("a subscribe refused without a tap shows the banner; its Turn On subscribes in the tap", async () => {
    let refuse = true;
    const w = syncWindow({
        subscribe: () => (refuse ? Promise.reject(new Error("NotAllowedError")) : Promise.resolve({
            endpoint: NEW_ENDPOINT,
            toJSON: () => ({ endpoint: NEW_ENDPOINT, keys: { p256dh: "p-new", auth: "a-new" } }),
        })),
        answers: [{ state: "none", key: SERVER_KEY, words: BANNER_WORDS }],
    });
    await pushSync(w.win);
    const banner = w.banner();
    assert.ok(banner, "the banner");
    assert.strictEqual(w.main.children.indexOf(banner), 1, "under the title");
    assert.strictEqual(banner.className, "attention push-banner");
    assert.strictEqual(banner.attributes.role, "status");
    const [text, on, hide] = banner.children;
    assert.deepStrictEqual([text.textContent, on.textContent, hide.textContent], ["Varsler er av på denne telefonen", "Slå på", "Skjul"]);
    refuse = false;
    w.log.length = 0;
    w.win.fetch = (url, options) => {
        w.log.push(["asked", new URLSearchParams(options.body).get("asked")]);
        return Promise.resolve({ ok: true, json: () => Promise.resolve({ state: "stored" }) });
    };
    on.click();
    assert.deepStrictEqual(w.log[0], ["subscribe", true, true], "first, in the tap");
    on.click();
    assert.strictEqual(w.log.filter((l) => l[0] === "subscribe").length, 1, "one try per tap");
    await settled();
    assert.deepStrictEqual(w.log[1], ["asked", "1"]);
    assert.strictEqual(w.banner(), null, "gone once it is on");
});

test("the banner's Hide is a Turn Off in this browser; a failed Turn On says so", async () => {
    let w = syncWindow({
        subscribe: () => Promise.reject(new Error("NotAllowedError")),
        answers: [{ state: "none", key: SERVER_KEY, words: BANNER_WORDS }],
    });
    await pushSync(w.win);
    w.banner().children[2].click();
    assert.strictEqual(w.banner(), null);
    assert.strictEqual(w.local["vibb.push-off"], "1");
    // The server marks this browser off too (review M-1).
    assert.deepStrictEqual(w.log[w.log.length - 1], ["post", "/notifications/subscriptions/sync", "manual", { off: "1" }]);
    w = syncWindow({
        subscribe: () => Promise.reject(new Error("NotAllowedError")),
        answers: [{ state: "none", key: SERVER_KEY, words: BANNER_WORDS }],
    });
    await pushSync(w.win);
    const [text, on] = w.banner().children;
    on.click();
    await settled();
    assert.strictEqual(text.textContent, "Fikk ikke slått dem på");
    assert.strictEqual(on.disabled, false);
});

test("a scrolled page keeps its place when the banner comes", async () => {
    const w = syncWindow({
        scrolled: 300,
        subscribe: () => Promise.reject(new Error("NotAllowedError")),
        answers: [{ state: "none", key: SERVER_KEY, words: BANNER_WORDS }],
    });
    await pushSync(w.win);
    assert.ok(w.banner());
    assert.strictEqual(w.main.scrollTop, 360);
});

test("nothing is sent without the permission, after Turn Off here, with unreadable storage or on This Device", async () => {
    for (const options of [
        { permission: "default" },
        { permission: "denied" },
        { off: "1" },
        { storageThrows: true },
        { thisDevice: true },
    ]) {
        const w = syncWindow(Object.assign({ subscription: held(), answers: [{ state: "stored" }] }, options));
        await pushSync(w.win);
        assert.deepStrictEqual(w.log, [], JSON.stringify(options));
        assert.strictEqual(w.banner(), null);
    }
    // This Device doesn't spend the tab's check.
    const session = {};
    let w = syncWindow({ thisDevice: true, session });
    pushSync(w.win);
    w = syncWindow({ subscription: held(), answers: [{ state: "known" }], session });
    await pushSync(w.win);
    assert.strictEqual(w.log.length, 1);
});

test("a parent's Remove or Turn Off answered: this browser stops asking", async () => {
    const w = syncWindow({ answers: [{ state: "removed" }] });
    await pushSync(w.win);
    assert.strictEqual(w.log.length, 1);
    assert.strictEqual(w.local["vibb.push-off"], "1");
    assert.strictEqual(w.banner(), null);
});

test("removed, refused, known or a failed answer: nothing more", async () => {
    for (const answers of [[{ state: "removed" }], [{ state: "refused", notice: "push_many" }], [{ state: "known" }], [], [{ state: "no_key" }]]) {
        const sub = held();
        const w = syncWindow({ subscription: sub, answers });
        await pushSync(w.win);
        assert.strictEqual(w.log.length, 1, JSON.stringify(answers));
        assert.strictEqual(sub.ended, false);
        assert.strictEqual(w.banner(), null);
    }
});
