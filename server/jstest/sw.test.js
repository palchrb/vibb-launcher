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
    assert.deepStrictEqual(w.log.map((e) => e[0]), ["show", "shown", "badge", "fetch"]);
    const [, title, options] = w.log[0];
    assert.strictEqual(title, "Anna called an emergency number");
    assert.strictEqual(options.body, "At 14:02");
    assert.strictEqual(options.tag, "e812");
    assert.deepStrictEqual(JSON.parse(JSON.stringify(options.data)), {
        navigate: ORIGIN + "/devices/3/calls",
        d: 4411,
        t: "AAAAAAAAAAAAAAAAAAAAAA",
    });
    // The link as an option too (absolute, this origin), as the Notifications draft has it.
    assert.strictEqual(options.navigate, ORIGIN + "/devices/3/calls");
    assert.deepStrictEqual(w.log[2], ["badge", 3]);
    const [, url, init] = w.log[3];
    assert.strictEqual(url, "/notifications/receipt");
    assert.strictEqual(init.method, "POST");
    assert.strictEqual(init.credentials, "omit");
    assert.deepStrictEqual(JSON.parse(init.body), { d: 4411, t: "AAAAAAAAAAAAAAAAAAAAAA", a: "shown", e: "push", v: "v-test" });
    // A legacy show that fails throws: nothing shown, no receipt.
    const failing = fakeWorker({ badge: false, showFails: true });
    await assert.rejects(failing.fire("push", pushOf(MESSAGE)));
    assert.deepStrictEqual(failing.log.map((e) => e[0]), ["show"]);
});

// Safari reads a mutable declarative push itself and fires `push` with `data` null and its
// proposed Notification on the event (WWDC25 session 235; WebKit's PushEvent.idl). The worker
// posts the receipt and sets the badge from it and shows NOTHING: Safari shows its proposed
// notification, the one iOS displays (the user's iPhone, iOS 26, 2026-10-19: a replacement shown
// from the worker was accepted and never appeared).
test("a declarative push on Safari is confirmed and badged, and left to Safari to show", async () => {
    const w = fakeWorker();
    const proposed = Object.assign({ silent: false, dir: "auto" }, MESSAGE.notification);
    await w.fire("push", { data: null, notification: proposed });
    assert.deepStrictEqual(w.log.map((e) => e[0]), ["fetch", "badge"]);
    const [, url, init] = w.log[0];
    assert.strictEqual(url, "/notifications/receipt");
    assert.deepStrictEqual(JSON.parse(init.body), { d: 4411, t: "AAAAAAAAAAAAAAAAAAAAAA", a: "shown", e: "push-declarative", v: "v-test" });
    assert.deepStrictEqual(w.log[1], ["badge", 3]);
    // The payload wins over a proposed notification when both are there (a browser that gives both).
    const both = fakeWorker({ badge: false });
    await both.fire("push", Object.assign(pushOf(MESSAGE), { notification: { title: "proposed" } }));
    assert.strictEqual(both.log[0][0], "show");
    assert.strictEqual(both.log[0][1], MESSAGE.notification.title);
    assert.strictEqual(JSON.parse(both.log[2][2].body).e, "push");
    // A proposed notification without our data (an old payload, or none): nothing posted, nothing shown, nothing thrown.
    const bare = fakeWorker({ badge: false });
    await bare.fire("push", { data: null, notification: { title: "x" } });
    assert.deepStrictEqual(bare.log, []);
});

// The explainer's `pushnotification` event (no shipping Safari fires it yet): the same receipt
// and badge, nothing shown.
test("a pushnotification event sets the badge and posts the receipt without showing", async () => {
    const w = fakeWorker();
    const notification = { title: MESSAGE.notification.title, data: MESSAGE.notification.data };
    await w.fire("pushnotification", { notification });
    assert.deepStrictEqual(w.log.map((e) => e[0]), ["fetch", "badge"]);
    assert.deepStrictEqual(JSON.parse(w.log[0][2].body), { d: 4411, t: "AAAAAAAAAAAAAAAAAAAAAA", a: "shown", e: "pushnotification", v: "v-test" });
    assert.deepStrictEqual(w.log[1], ["badge", 3]);
    const bare = fakeWorker({ badge: false });
    await bare.fire("pushnotification", { notification: { title: "x" } });
    await bare.fire("pushnotification", {});
    assert.deepStrictEqual(bare.log, []);
});

test("a push that can't be read still shows a notification, and posts nothing", async () => {
    for (const event of [pushOf("not json"), { data: null }, pushOf({ web_push: 8030 })]) {
        const w = fakeWorker({ badge: false });
        await w.fire("push", event);
        assert.strictEqual(w.log[0][0], "show");
        assert.strictEqual(w.log[0][1], "Vibb Phone");
        assert.strictEqual(w.log[0][2].body, "Something new - open Notifications");
        assert.strictEqual(w.log[0][2].navigate, ORIGIN + "/notifications");
        assert.strictEqual(w.log[0][2].data.navigate, ORIGIN + "/notifications");
        assert.ok(!w.log.some((e) => e[0] === "fetch"));
    }
    // A navigate off this origin never becomes the option (the inbox instead).
    const away = fakeWorker({ badge: false });
    await away.fire("push", pushOf({ notification: { title: "x", navigate: "https://evil.example/x" } }));
    assert.strictEqual(away.log[0][2].navigate, ORIGIN + "/notifications");
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

// A declarative notification Safari showed carries its link on the notification itself.
test("a tap on Safari's own notification opens its navigate link", async () => {
    const w = fakeWorker({ badge: false });
    const event = clickOf({ d: 7, t: "tok" });
    event.notification.navigate = ORIGIN + "/devices/3/calls";
    await w.fire("notificationclick", event);
    assert.deepStrictEqual(w.log.find((e) => e[0] === "open"), ["open", "/devices/3/calls"]);
    assert.strictEqual(JSON.parse(w.log.find((e) => e[0] === "fetch")[2].body).a, "clicked");
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
