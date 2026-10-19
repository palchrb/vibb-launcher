// Behaviour of templates/sw.js (design 35 §5.3): a push always shows a notification, the receipt
// goes only after it showed, the badge; a tap opens a page on this server and posts "clicked".
// Run: node --test jstest/sw.test.js
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

const SOURCE = fs
    .readFileSync(path.join(__dirname, "..", "templates", "sw.js"), "utf8")
    .replace(/\{\{ app_version \}\}/g, "v-test");

const ORIGIN = "https://kidphone.example.ts.net";

// A fake worker global: listeners, notifications, fetches, windows and the badge, all recorded.
function fakeWorker({ windows = [], badge = true, showFails = false } = {}) {
    const listeners = {};
    const log = [];
    const self = {
        location: { origin: ORIGIN },
        addEventListener: (type, fn) => {
            listeners[type] = fn;
        },
        skipWaiting: () => {},
        registration: {
            showNotification: (title, options) => {
                log.push(["show", title, options]);
                if (showFails) {
                    const e = new Error("quota");
                    e.name = "QuotaExceededError";
                    return Promise.reject(e);
                }
                return new Promise((resolve) => setTimeout(() => {
                    log.push(["shown"]);
                    resolve();
                }, 5));
            },
        },
        clients: {
            claim: () => Promise.resolve(),
            matchAll: () => Promise.resolve(windows),
            openWindow: (url) => {
                log.push(["open", url]);
                return Promise.resolve();
            },
        },
        navigator: badge
            ? {
                setAppBadge: (n) => {
                    log.push(["badge", n]);
                    return Promise.resolve();
                },
                clearAppBadge: () => {
                    log.push(["badge", 0]);
                    return Promise.resolve();
                },
            }
            : {},
    };
    const context = {
        self,
        URL,
        JSON,
        Promise,
        setTimeout,
        fetch: (url, init) => {
            log.push(["fetch", url, init]);
            return Promise.resolve({ status: 204 });
        },
    };
    vm.runInNewContext(SOURCE, context);
    // Fires an event and waits for what it handed to waitUntil.
    const fire = async (type, event) => {
        let work = Promise.resolve();
        event.waitUntil = (p) => {
            work = p;
        };
        listeners[type](event);
        await work;
    };
    return { listeners, log, fire };
}

function pushOf(message) {
    return {
        data: {
            json: () => (typeof message === "string" ? JSON.parse(message) : message),
        },
    };
}

const MESSAGE = {
    web_push: 8030,
    mutable: true,
    notification: {
        title: "Anna called an emergency number",
        body: "At 14:02",
        lang: "en",
        navigate: ORIGIN + "/devices/3/calls",
        tag: "e812",
        data: { d: 4411, t: "AAAAAAAAAAAAAAAAAAAAAA", b: 3 },
    },
};

test("a push shows its notification, then the badge, then posts the receipt", async () => {
    const w = fakeWorker();
    await w.fire("push", pushOf(MESSAGE));
    assert.deepStrictEqual(w.log.map((e) => e[0]), ["fetch", "show", "shown", "fetch", "badge", "fetch"]);
    // The probe first: the event's shape, nothing of ours and no text; its second step after the show.
    assert.deepStrictEqual(JSON.parse(w.log[0][2].body), { p: 1, e: "push", v: "v-test", keys: ["data"], data: "set", n: null });
    assert.deepStrictEqual(JSON.parse(w.log[3][2].body), { p: 2, e: "push", shown: "ok", v: "v-test" });
    w.log.shift();
    w.log.splice(2, 1);
    const [, title, options] = w.log[0];
    assert.strictEqual(title, "Anna called an emergency number");
    assert.strictEqual(options.body, "At 14:02");
    assert.strictEqual(options.tag, "e812");
    assert.deepStrictEqual(JSON.parse(JSON.stringify(options.data)), {
        navigate: ORIGIN + "/devices/3/calls",
        d: 4411,
        t: "AAAAAAAAAAAAAAAAAAAAAA",
    });
    assert.deepStrictEqual(w.log[2], ["badge", 3]);
    const [, url, init] = w.log[3];
    assert.strictEqual(url, "/notifications/receipt");
    assert.strictEqual(init.method, "POST");
    assert.strictEqual(init.credentials, "omit");
    assert.deepStrictEqual(JSON.parse(init.body), { d: 4411, t: "AAAAAAAAAAAAAAAAAAAAAA", a: "shown", e: "push", v: "v-test" });
});

// Safari 18.4+ reads a mutable declarative push itself and fires `push` with `data` null and
// its proposed Notification on the event (WWDC25 session 235; the user's iPhone, 2026-10-19,
// where `push` found no data and every push stayed unconfirmed): the worker shows the
// replacement from it - the same words, our icon, the payload's `data` - then the badge and
// the receipt, which names the source.
test("a declarative push on Safari is shown from the proposed notification and confirmed", async () => {
    const w = fakeWorker();
    const proposed = Object.assign({ silent: false, dir: "auto" }, MESSAGE.notification);
    await w.fire("push", { data: null, notification: proposed });
    // The receipt BEFORE the show (Safari shows the notification either way; the worker didn't live
    // past showNotification on the user's iPhone), then the probe's second step, then the badge.
    assert.deepStrictEqual(w.log.map((e) => e[0]), ["fetch", "fetch", "show", "shown", "fetch", "badge"]);
    assert.deepStrictEqual(JSON.parse(w.log[0][2].body), {
        p: 1, e: "push", v: "v-test", keys: ["data", "notification"], data: "null",
        n: { title: "string", data: "object", dataKeys: ["d", "t", "b"], navigate: "string", tag: "string" },
    });
    assert.deepStrictEqual(JSON.parse(w.log[4][2].body), { p: 2, e: "push-declarative", shown: "ok", v: "v-test" });
    w.log.shift();
    const [, url, init] = w.log[0];
    assert.strictEqual(url, "/notifications/receipt");
    assert.deepStrictEqual(JSON.parse(init.body), { d: 4411, t: "AAAAAAAAAAAAAAAAAAAAAA", a: "shown", e: "push-declarative", v: "v-test" });
    w.log.shift();
    w.log.splice(2, 1);
    assert.strictEqual(w.log[0][1], MESSAGE.notification.title);
    // (JSON round trip: the worker's objects come from the vm context's own prototypes.)
    assert.deepStrictEqual(JSON.parse(JSON.stringify(w.log[0][2])), {
        body: "At 14:02",
        lang: "en",
        icon: "/static/icons/icon-192.png",
        tag: "e812",
        data: { navigate: ORIGIN + "/devices/3/calls", d: 4411, t: "AAAAAAAAAAAAAAAAAAAAAA" },
    });
    assert.deepStrictEqual(w.log[2], ["badge", 3]);
    // The payload wins over a proposed notification when both are there (a browser that gives both).
    const both = fakeWorker({ badge: false });
    await both.fire("push", Object.assign(pushOf(MESSAGE), { notification: { title: "proposed" } }));
    assert.strictEqual(both.log[1][1], MESSAGE.notification.title);
    assert.strictEqual(JSON.parse(both.log[4][2].body).e, "push");
    // A proposed notification without our data (an old payload, or none): shown, no receipt posted.
    const bare = fakeWorker({ badge: false });
    await bare.fire("push", { data: null, notification: { title: "x" } });
    assert.deepStrictEqual(bare.log.map((e) => e[0]), ["fetch", "show", "shown", "fetch"]);
    assert.strictEqual(JSON.parse(bare.log[0][2].body).p, 1);
    assert.strictEqual(JSON.parse(bare.log[3][2].body).p, 2);
    assert.strictEqual(bare.log[1][1], "x");
    assert.strictEqual(bare.log[1][2].data.navigate, "/notifications");
    // A declarative show that fails still leaves the receipt sent, and the probe says so.
    const failing = fakeWorker({ badge: false, showFails: true });
    await failing.fire("push", { data: null, notification: proposed });
    assert.deepStrictEqual(failing.log.map((e) => e[0]), ["fetch", "fetch", "show", "fetch"]);
    assert.strictEqual(JSON.parse(failing.log[1][2].body).a, "shown");
    assert.deepStrictEqual(JSON.parse(failing.log[3][2].body), { p: 2, e: "push-declarative", shown: "failed:QuotaExceededError", v: "v-test" });
    // A legacy show that fails still throws (nothing shown, no receipt), after the probe's step.
    const failingLegacy = fakeWorker({ badge: false, showFails: true });
    await assert.rejects(failingLegacy.fire("push", pushOf(MESSAGE)));
    assert.deepStrictEqual(failingLegacy.log.map((e) => e[0]), ["fetch", "show"]);
});

// The explainer's `pushnotification` event (no shipping Safari fires it yet): the worker shows
// nothing more, sets the badge and posts the receipt from the notification Safari built.
test("a pushnotification event sets the badge and posts the receipt without showing twice", async () => {
    const w = fakeWorker();
    const notification = { title: MESSAGE.notification.title, data: MESSAGE.notification.data };
    await w.fire("pushnotification", { notification });
    assert.deepStrictEqual(w.log.map((e) => e[0]), ["fetch", "badge", "fetch"]);
    assert.strictEqual(JSON.parse(w.log[0][2].body).e, "pushnotification");
    w.log.shift();
    assert.deepStrictEqual(w.log[0], ["badge", 3]);
    const [, url, init] = w.log[1];
    assert.strictEqual(url, "/notifications/receipt");
    assert.deepStrictEqual(JSON.parse(init.body), { d: 4411, t: "AAAAAAAAAAAAAAAAAAAAAA", a: "shown", e: "pushnotification", v: "v-test" });
    // A declarative notification without our data (an old payload, or none): nothing posted, nothing thrown.
    const bare = fakeWorker({ badge: false });
    await bare.fire("pushnotification", { notification: { title: "x" } });
    assert.deepStrictEqual(bare.log.map((e) => e[0]), ["fetch"]);
    await bare.fire("pushnotification", {});
    assert.deepStrictEqual(bare.log.map((e) => e[0]), ["fetch", "fetch"]);
});

test("a push that can't be read still shows a notification, and posts nothing", async () => {
    for (const event of [pushOf("not json"), { data: null }, pushOf({ web_push: 8030 })]) {
        const w = fakeWorker({ badge: false });
        await w.fire("push", event);
        assert.strictEqual(w.log[0][0], "fetch"); // the probe
        assert.strictEqual(w.log[1][0], "show");
        assert.strictEqual(w.log[1][1], "Vibb Phone");
        assert.strictEqual(w.log[1][2].body, "Something new - open Notifications");
        assert.strictEqual(w.log[1][2].data.navigate, "/notifications");
        // No receipt: nothing but the probe's two steps is posted.
        assert.ok(!w.log.some((e) => e[0] === "fetch" && JSON.parse(e[2].body).p === undefined));
    }
});

function clickOf(data) {
    const notification = { data, closed: false, close() { this.closed = true; } };
    return { notification };
}

test("a tap posts clicked and opens the event's page", async () => {
    const w = fakeWorker();
    const event = clickOf({ navigate: ORIGIN + "/devices/3/calls", d: 7, t: "tok" });
    await w.fire("notificationclick", event);
    assert.ok(event.notification.closed);
    const fetches = w.log.filter((e) => e[0] === "fetch");
    assert.deepStrictEqual(JSON.parse(fetches[0][2].body), { d: 7, t: "tok", a: "clicked", e: "click", v: "v-test" });
    assert.deepStrictEqual(w.log.find((e) => e[0] === "open"), ["open", "/devices/3/calls"]);
});

test("a tap steers an open window instead of opening another", async () => {
    const steered = [];
    const client = {
        focus: () => Promise.resolve(client),
        navigate: (url) => {
            steered.push(url);
            return Promise.resolve();
        },
    };
    const w = fakeWorker({ windows: [client] });
    await w.fire("notificationclick", clickOf({ navigate: ORIGIN + "/devices/3#foreign-access" }));
    assert.deepStrictEqual(steered, ["/devices/3#foreign-access"]);
    assert.ok(!w.log.some((e) => e[0] === "open"));
});

test("a tap never leaves this server", async () => {
    for (const navigate of [
        "https://evil.example/x",
        "//evil.example/x",
        "/\\evil.example/x",
        "javascript:alert(1)",
        undefined,
    ]) {
        const w = fakeWorker();
        await w.fire("notificationclick", clickOf({ navigate }));
        assert.deepStrictEqual(w.log.find((e) => e[0] === "open"), ["open", "/notifications"], String(navigate));
    }
});

test("no fetch handler: requests pass straight through", () => {
    const w = fakeWorker();
    assert.deepStrictEqual(Object.keys(w.listeners).sort(), ["activate", "install", "notificationclick", "push", "pushnotification"]);
});
