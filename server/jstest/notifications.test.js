// Behaviour of static/notifications.js (design 35 §5.2, qa-35-design H2): the state order, Turn
// On calling pushManager.subscribe first in the tap with a Uint8Array key, the form filled and
// sent, the server-known check by the endpoint's hash, Turn Off.
// Run: node --test jstest/notifications.test.js
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const crypto = require("node:crypto");
const { install, decodeKey, endpointHash } = require("../static/notifications.js");

const KEY = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
const ENDPOINT = "https://web.push.apple.com/QK1-fake";
const hashOf = (endpoint) =>
    crypto.createHash("sha256").update(endpoint).digest("hex").slice(0, 16);

function element(extra = {}) {
    const listeners = {};
    return Object.assign({
        hidden: true,
        disabled: false,
        textContent: "",
        addEventListener: (type, fn) => {
            listeners[type] = fn;
        },
        click: () => listeners.click(),
    }, extra);
}

// A fake page and browser. `subscription` is what the browser already has; `subscribe` what a
// subscribe call does (default: grants and returns a new subscription).
function fakePage({
    standalone,
    pushManager = true,
    secure = true,
    permission = "default",
    known = [],
    subscription = null,
    subscribe,
} = {}) {
    const calls = [];
    const submitted = [];
    const field = () => ({ value: "" });
    const form = element({
        elements: { endpoint: field(), p256dh: field(), auth: field() },
        getAttribute: (name) => ({ "data-key": KEY, "data-subscribed": known.join(",") })[name],
        submit: () => submitted.push("on"),
    });
    const offForm = element({ elements: { endpoint: field() }, submit: () => submitted.push("off") });
    const nodes = {
        "push-on-form": form,
        "push-off-form": offForm,
        "push-state": element(),
        "push-on": element(),
        "push-off": element(),
        "push-home-screen": element(),
        "push-blocked": element(),
    };
    const fresh = {
        endpoint: ENDPOINT,
        toJSON: () => ({ endpoint: ENDPOINT, keys: { p256dh: "p-key", auth: "a-key" } }),
    };
    const manager = {
        getSubscription: () => Promise.resolve(subscription),
        subscribe: (options) => {
            calls.push(["subscribe", options]);
            return subscribe ? subscribe() : Promise.resolve(fresh);
        },
    };
    const nav = {
        serviceWorker: { ready: Promise.resolve({ pushManager: manager }) },
    };
    if (standalone !== undefined) nav.standalone = standalone;
    const win = {
        document: { getElementById: (id) => nodes[id] },
        navigator: nav,
        isSecureContext: secure,
        Notification: { permission },
        atob: (s) => Buffer.from(s, "base64").toString("binary"),
        TextEncoder,
        crypto: { subtle: crypto.webcrypto.subtle },
    };
    if (pushManager) win.PushManager = function () {};
    return { win, nodes, calls, submitted, form, offForm };
}

const settle = () => new Promise((resolve) => setTimeout(resolve, 20));

test("an iPhone's Safari tab says to add the app to the Home Screen first", () => {
    // iOS Safari outside the Home Screen has no PushManager: the Home Screen still comes first.
    const page = fakePage({ standalone: false, pushManager: false });
    install(page.win);
    assert.strictEqual(page.nodes["push-state"].textContent, "Add to the Home Screen first");
    assert.strictEqual(page.nodes["push-home-screen"].hidden, false);
    assert.strictEqual(page.nodes["push-on"].hidden, true);
});

test("then not available, then blocked", () => {
    let page = fakePage({ pushManager: false });
    install(page.win);
    assert.strictEqual(page.nodes["push-state"].textContent, "Not available in this browser");
    page = fakePage({ secure: false });
    install(page.win);
    assert.strictEqual(page.nodes["push-state"].textContent, "Not available in this browser");
    page = fakePage({ standalone: true, permission: "denied" });
    install(page.win);
    assert.strictEqual(page.nodes["push-state"].textContent, "Blocked");
    assert.strictEqual(page.nodes["push-blocked"].hidden, false);
    assert.strictEqual(page.nodes["push-on"].hidden, true);
});

test("Turn On waits for the worker, subscribes first in the tap and sends the form", async () => {
    const page = fakePage();
    install(page.win);
    const on = page.nodes["push-on"];
    assert.strictEqual(page.nodes["push-state"].textContent, "Off");
    assert.ok(!on.hidden && on.disabled, "disabled until the registration is in");
    await settle();
    assert.ok(!on.disabled);
    on.click();
    // Synchronously in the tap: nothing awaited before subscribe.
    assert.strictEqual(page.calls.length, 1);
    const [, options] = page.calls[0];
    assert.strictEqual(options.userVisibleOnly, true);
    assert.ok(options.applicationServerKey instanceof Uint8Array);
    assert.strictEqual(options.applicationServerKey.length, 65);
    assert.strictEqual(options.applicationServerKey[0], 4);
    await settle();
    assert.deepStrictEqual(page.submitted, ["on"]);
    assert.strictEqual(page.form.elements.endpoint.value, ENDPOINT);
    assert.strictEqual(page.form.elements.p256dh.value, "p-key");
    assert.strictEqual(page.form.elements.auth.value, "a-key");
});

test("a refused prompt shows Blocked and sends nothing", async () => {
    const page = fakePage({
        subscribe: () => {
            page.win.Notification.permission = "denied";
            return Promise.reject(new Error("NotAllowedError"));
        },
    });
    install(page.win);
    await settle();
    page.nodes["push-on"].click();
    await settle();
    assert.strictEqual(page.nodes["push-state"].textContent, "Blocked");
    assert.deepStrictEqual(page.submitted, []);
});

test("a subscription the server knows is On, and Turn Off ends it there too", async () => {
    let ended = false;
    const subscription = {
        endpoint: ENDPOINT,
        unsubscribe: () => {
            ended = true;
            return Promise.resolve(true);
        },
    };
    const page = fakePage({ subscription, known: ["0123456789abcdef", hashOf(ENDPOINT)] });
    install(page.win);
    await settle();
    assert.strictEqual(page.nodes["push-state"].textContent, "On");
    assert.ok(page.nodes["push-on"].hidden && !page.nodes["push-off"].hidden);
    page.nodes["push-off"].click();
    await settle();
    assert.ok(ended);
    assert.deepStrictEqual(page.submitted, ["off"]);
    assert.strictEqual(page.offForm.elements.endpoint.value, ENDPOINT);
});

test("a subscription the server doesn't know is dropped and shows Off", async () => {
    let ended = false;
    const subscription = {
        endpoint: ENDPOINT,
        unsubscribe: () => {
            ended = true;
            return Promise.resolve(true);
        },
    };
    const page = fakePage({ subscription, known: ["0123456789abcdef"] });
    install(page.win);
    await settle();
    assert.ok(ended);
    assert.strictEqual(page.nodes["push-state"].textContent, "Off");
    assert.ok(!page.nodes["push-on"].disabled);
});

test("the key decodes and the hash matches the server's", async () => {
    const win = { atob: (s) => Buffer.from(s, "base64").toString("binary"), TextEncoder, crypto: { subtle: crypto.webcrypto.subtle } };
    assert.deepStrictEqual(Buffer.from(decodeKey(KEY, win)), Buffer.from(KEY, "base64url"));
    assert.strictEqual(await endpointHash(ENDPOINT, win), hashOf(ENDPOINT));
});
