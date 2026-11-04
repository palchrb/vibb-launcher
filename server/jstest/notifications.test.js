// Behaviour of static/notifications.js (design 35 §5.2, qa-35-design H2): the state order, Turn
// On calling pushManager.subscribe first in the tap with a Uint8Array key, the form filled and
// sent, the server-known check by the endpoint's hash, Turn Off. The state words are the form's data-w-* (here
// as an nb page renders them, design 70 §5.3).
// Run: node --test jstest/notifications.test.js
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const crypto = require("node:crypto");
const { install, decodeKey, endpointHash } = require("../static/notifications.js");

const KEY = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4";
const ENDPOINT = "https://web.push.apple.com/QK1-fake";
// The form's words, as notifications_settings.html renders them in Norwegian.
const WORDS = {
    "data-w-home-screen": "Legg den på Hjem-skjermen først",
    "data-w-not-available": "Ikke tilgjengelig i denne nettleseren",
    "data-w-blocked": "Blokkert",
    "data-w-off": "Av",
    "data-w-on": "På",
    "data-w-on-failed": "Fikk ikke slått på - prøv igjen",
    "data-w-off-failed": "Fikk ikke slått av - prøv igjen",
};
const hashOf = (endpoint) =>
    crypto.createHash("sha256").update(endpoint).digest("hex").slice(0, 16);

// The fake page's digest resolves as a microtask: WebCrypto's runs on the thread pool and missed
// the 20 ms `settle()` on a loaded machine (qa-srv-sweep-resume-code L8). The real WebCrypto is
// still checked by "the key decodes and the hash matches the server's".
const syncSubtle = {
    digest: (_algorithm, bytes) => {
        const d = crypto.createHash("sha256").update(bytes).digest();
        return Promise.resolve(d.buffer.slice(d.byteOffset, d.byteOffset + d.length));
    },
};

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
    words = WORDS,
    sync = [],
    off = null,
} = {}) {
    const calls = [];
    const submitted = [];
    const posted = [];
    const storage = { "vibb.push-off": off };
    const field = () => ({ value: "" });
    const form = element({
        elements: { endpoint: field(), p256dh: field(), auth: field(), replaces: field() },
        getAttribute: (name) => ({ "data-key": KEY, "data-subscribed": known.join(","), ...words })[name],
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
        btoa: (s) => Buffer.from(s, "binary").toString("base64"),
        TextEncoder,
        crypto: { subtle: syncSubtle },
        localStorage: {
            getItem: (k) => (k in storage ? storage[k] : null),
            setItem: (k, v) => { storage[k] = v; },
            removeItem: (k) => { storage[k] = null; },
        },
        // The sync route, answering from `sync` in order.
        fetch: (url, options) => {
            posted.push([url, options.method, options.redirect, new URLSearchParams(options.body)]);
            const next = sync.shift();
            if (!next) return Promise.reject(new Error("offline"));
            return Promise.resolve({ ok: true, json: () => Promise.resolve(next) });
        },
    };
    if (pushManager) win.PushManager = function () {};
    return { win, nodes, calls, submitted, form, offForm, posted, storage };
}

// A browser subscription: its endpoint, the key it was made for (`key`, base64url; null = the browser doesn't say)
// and whether it was ended.
function browserSubscription(key = KEY) {
    const sub = {
        endpoint: ENDPOINT,
        ended: false,
        toJSON: () => ({ endpoint: ENDPOINT, keys: { p256dh: "p-key", auth: "a-key" } }),
        unsubscribe: () => {
            sub.ended = true;
            return Promise.resolve(true);
        },
    };
    if (key) {
        const bytes = Buffer.from(key, "base64url");
        sub.options = { applicationServerKey: bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.length) };
    }
    return sub;
}

const settle = () => new Promise((resolve) => setTimeout(resolve, 20));

test("an iPhone's Safari tab says to add the app to the Home Screen first", () => {
    // iOS Safari outside the Home Screen has no PushManager: the Home Screen still comes first.
    const page = fakePage({ standalone: false, pushManager: false });
    install(page.win);
    assert.strictEqual(page.nodes["push-state"].textContent, "Legg den på Hjem-skjermen først");
    assert.strictEqual(page.nodes["push-home-screen"].hidden, false);
    assert.strictEqual(page.nodes["push-on"].hidden, true);
});

test("a word the page doesn't give shows its name", () => {
    const page = fakePage({ pushManager: false, words: {} });
    install(page.win);
    assert.strictEqual(page.nodes["push-state"].textContent, "not-available");
});

test("then not available, then blocked", () => {
    let page = fakePage({ pushManager: false });
    install(page.win);
    assert.strictEqual(page.nodes["push-state"].textContent, "Ikke tilgjengelig i denne nettleseren");
    page = fakePage({ secure: false });
    install(page.win);
    assert.strictEqual(page.nodes["push-state"].textContent, "Ikke tilgjengelig i denne nettleseren");
    page = fakePage({ standalone: true, permission: "denied" });
    install(page.win);
    assert.strictEqual(page.nodes["push-state"].textContent, "Blokkert");
    assert.strictEqual(page.nodes["push-blocked"].hidden, false);
    assert.strictEqual(page.nodes["push-on"].hidden, true);
});

test("Turn On waits for the worker, subscribes first in the tap and sends the form", async () => {
    const page = fakePage();
    install(page.win);
    const on = page.nodes["push-on"];
    assert.strictEqual(page.nodes["push-state"].textContent, "Av");
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
    assert.strictEqual(page.nodes["push-state"].textContent, "Blokkert");
    assert.deepStrictEqual(page.submitted, []);
});

test("a subscription the server knows is On, and Turn Off ends it there too and is remembered", async () => {
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
    assert.strictEqual(page.nodes["push-state"].textContent, "På");
    assert.ok(page.nodes["push-on"].hidden && !page.nodes["push-off"].hidden);
    page.nodes["push-off"].click();
    await settle();
    assert.ok(ended);
    assert.deepStrictEqual(page.submitted, ["off"]);
    assert.strictEqual(page.offForm.elements.endpoint.value, ENDPOINT);
    // The app never turns it on again by itself (register-sw.js reads it); Turn On forgets it.
    assert.strictEqual(page.storage["vibb.push-off"], "1");
    assert.deepStrictEqual(page.posted, [], "a known one is never sent");
});

// Push never lost silently (2026-11-04): an unknown subscription made for the server's key is the server's loss,
// not the browser's - sent again, never dropped.
test("a subscription the server lost is sent again and shows On", async () => {
    for (const key of [KEY, null]) {
        const subscription = browserSubscription(key);
        const page = fakePage({ subscription, known: ["0123456789abcdef"], sync: [{ state: "stored" }] });
        install(page.win);
        await settle();
        assert.strictEqual(subscription.ended, false, String(key));
        assert.strictEqual(page.nodes["push-state"].textContent, "På");
        assert.ok(!page.nodes["push-off"].hidden);
        assert.strictEqual(page.posted.length, 1);
        const [url, method, redirect, body] = page.posted[0];
        assert.deepStrictEqual([url, method, redirect], ["/notifications/subscriptions/sync", "POST", "manual"]);
        assert.strictEqual(body.get("endpoint"), ENDPOINT);
        assert.strictEqual(body.get("p256dh"), "p-key");
        assert.strictEqual(body.get("auth"), "a-key");
        assert.strictEqual(body.get("key"), key);
    }
});

test("one made for another key, or ended by its push service, is dropped; the next Turn On replaces it", async () => {
    const other = "BPmRsEk2bg2pTq7XvgQsSdpS1hNlDXT1txWOP4h0lZlqOYuKrHiIWZQVA1Ey7k3T9ajW6JrSoZJqxmn4mtHL0K0";
    let subscription = browserSubscription(other);
    let page = fakePage({ subscription, known: [] });
    install(page.win);
    await settle();
    assert.ok(subscription.ended);
    assert.deepStrictEqual(page.posted, [], "another key's is never sent");
    assert.strictEqual(page.nodes["push-state"].textContent, "Av");
    assert.strictEqual(page.form.elements.replaces.value, ENDPOINT);
    assert.ok(!page.nodes["push-on"].disabled);

    subscription = browserSubscription();
    page = fakePage({ subscription, sync: [{ state: "gone", key: KEY }] });
    install(page.win);
    await settle();
    assert.ok(subscription.ended);
    assert.strictEqual(page.form.elements.replaces.value, ENDPOINT);
    page.nodes["push-on"].click();
    await settle();
    assert.deepStrictEqual(page.submitted, ["on"]);
});

test("one a parent removed, one after Turn Off here, or an answer that fails stays Off and kept", async () => {
    for (const [options, posts] of [
        [{ sync: [{ state: "removed" }] }, 1],
        [{ sync: [{ state: "refused", notice: "push_many" }] }, 1],
        [{ sync: [] }, 1], // offline
        [{ off: "1" }, 0],
    ]) {
        const subscription = browserSubscription();
        const page = fakePage(Object.assign({ subscription }, options));
        install(page.win);
        await settle();
        const what = JSON.stringify(options);
        assert.strictEqual(subscription.ended, false, what);
        assert.strictEqual(page.posted.length, posts, what);
        assert.strictEqual(page.nodes["push-state"].textContent, "Av", what);
        assert.strictEqual(page.form.elements.replaces.value, "", what);
        // Turn On forgets a Turn Off.
        page.nodes["push-on"].click();
        assert.strictEqual(page.storage["vibb.push-off"], null, what);
    }
});

test("the key decodes and the hash matches the server's", async () => {
    const win = { atob: (s) => Buffer.from(s, "base64").toString("binary"), TextEncoder, crypto: { subtle: crypto.webcrypto.subtle } };
    assert.deepStrictEqual(Buffer.from(decodeKey(KEY, win)), Buffer.from(KEY, "base64url"));
    assert.strictEqual(await endpointHash(ENDPOINT, win), hashOf(ENDPOINT));
});

// Server round 5 §5: after Send a Test, the rows poll subscriptions.json and re-render in place.
const { testedRow, pollRows } = require("../static/notifications.js");

function fakeRows(answers) {
    const rows = {};
    const make = (id, sub, badge) => {
        const line = { textContent: sub };
        const pill = { textContent: badge, className: "badge badge-on" };
        rows[id] = {
            line,
            pill,
            querySelector: (s) => (s === "[data-sub]" ? line : s === "[data-badge]" ? pill : null),
        };
    };
    make(4, "Nothing shown yet", "On");
    make(5, "Last shown 2 h ago", "On");
    const timers = [];
    const fetched = [];
    const win = {
        document: {
            querySelector: (s) => {
                const m = /data-subscription="(\d+)"/.exec(s);
                return m ? rows[m[1]] || null : null;
            },
        },
        setTimeout: (fn, ms) => timers.push([fn, ms]),
        fetch: (url, options) => {
            fetched.push([url, options.credentials]);
            const next = answers.shift();
            if (next instanceof Error) return Promise.reject(next);
            return Promise.resolve({ ok: true, json: () => Promise.resolve(next) });
        },
    };
    const run = async () => {
        const [fn, ms] = timers.shift();
        assert.strictEqual(ms, 3000);
        fn();
        await settle();
    };
    return { win, rows, timers, fetched, run };
}

test("the tested row comes from the query", () => {
    assert.strictEqual(testedRow("?from=devices&notice=test_sent&sub=4"), "4");
    assert.strictEqual(testedRow("?notice=test_wait&sub=4"), null);
    assert.strictEqual(testedRow("?notice=test_sent"), null);
    assert.strictEqual(testedRow(""), null);
});

test("the rows re-render in place and the poll stops once the tested row changed", async () => {
    const same = [
        { id: 4, badge: "On", badge_class: "badge badge-on", sub: "Nothing shown yet" },
        { id: 5, badge: "May not be showing", badge_class: "badge badge-warn", sub: "Last shown 2 h ago" },
    ];
    const changed = [{ id: 4, badge: "On", badge_class: "badge badge-on", sub: "Last shown 1 min ago" }];
    const page = fakeRows([same, new Error("offline"), changed]);
    let stopped = false;
    pollRows(page.win, "4", () => { stopped = true; });
    assert.strictEqual(page.fetched.length, 0, "the first poll waits 3 s");
    await page.run();
    assert.deepStrictEqual(page.fetched[0], ["/notifications/subscriptions.json", "same-origin"]);
    assert.strictEqual(page.rows[5].pill.textContent, "May not be showing");
    assert.strictEqual(page.rows[5].pill.className, "badge badge-warn");
    assert.ok(!stopped);
    await page.run(); // a lost answer: polled again
    assert.ok(!stopped);
    await page.run();
    assert.strictEqual(page.rows[4].line.textContent, "Last shown 1 min ago");
    assert.ok(stopped);
    assert.strictEqual(page.timers.length, 0, "nothing more scheduled");
});

test("the poll stops after ten tries", async () => {
    const same = () => [{ id: 4, badge: "On", badge_class: "badge badge-on", sub: "Nothing shown yet" }];
    const page = fakeRows(Array.from({ length: 12 }, same));
    let stopped = false;
    pollRows(page.win, "4", () => { stopped = true; });
    for (let i = 0; i < 10; i++) await page.run();
    assert.ok(stopped);
    assert.strictEqual(page.fetched.length, 10);
    assert.strictEqual(page.timers.length, 0);
});

// The first opening's offer on Devices (2026-11-01, partials/push_offer.html): the card's rule as a
// table, then the card itself - shown only once the worker is ready and the browser holds no
// subscription the server knows, Turn On subscribing first in the tap and posting the form, Not
// Now kept in localStorage.
const { offerShown } = require("../static/notifications.js");

test("the offer's rule: the Home Screen (or an install), Push, the permission not asked, not dismissed", () => {
    const base = { standalone: true, installed: false, push: true, permission: "default", dismissed: false };
    const rows = [
        [{}, true],
        [{ standalone: false }, false], // a plain Safari or Chrome tab
        [{ standalone: false, installed: true }, true], // Chrome's appinstalled, in the tab it came from
        [{ push: false }, false],
        [{ permission: "granted" }, false],
        [{ permission: "denied" }, false],
        [{ permission: "" }, false], // no Notification API
        [{ dismissed: true }, false],
        [{ standalone: false, installed: true, dismissed: true }, false],
    ];
    for (const [change, shown] of rows) {
        assert.strictEqual(offerShown(Object.assign({}, base, change)), shown, JSON.stringify(change));
    }
});

function offerPage({
    standalone = true,
    mediaStandalone = false,
    permission = "default",
    known = [],
    subscription = null,
    subscribe,
    stored = null,
    storageThrows = false,
    sync = [],
} = {}) {
    const calls = [];
    const submitted = [];
    const listeners = {};
    const field = () => ({ value: "" });
    const parts = {
        "[data-offer-on]": element({ hidden: false, disabled: true }),
        "[data-offer-later]": element({ hidden: false }),
        "[data-offer-line]": element({ textContent: "Hear at once" }),
    };
    const card = element({
        elements: { endpoint: field(), p256dh: field(), auth: field(), replaces: field() },
        getAttribute: (name) => ({
            "data-key": KEY,
            "data-subscribed": known.join(","),
            "data-failed": "Couldn't turn notifications on",
            "data-blocked": "Notifications are blocked",
        })[name],
        querySelector: (s) => parts[s],
        submit: () => submitted.push("on"),
    });
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
    const storage = { value: stored };
    const nav = { serviceWorker: { ready: Promise.resolve({ pushManager: manager }) } };
    if (standalone !== undefined) nav.standalone = standalone;
    const win = {
        document: { getElementById: (id) => (id === "push-offer" ? card : null) },
        navigator: nav,
        matchMedia: (q) => ({ matches: mediaStandalone && q === "(display-mode: standalone)" }),
        addEventListener: (type, fn) => {
            listeners[type] = fn;
        },
        localStorage: {
            getItem: (k) => {
                if (storageThrows) throw new Error("SecurityError");
                return k === "vibb.push-offer" ? storage.value : null;
            },
            setItem: (k, v) => {
                if (storageThrows) throw new Error("SecurityError");
                if (k === "vibb.push-offer") storage.value = v;
            },
        },
        isSecureContext: true,
        Notification: { permission },
        PushManager: function () {},
        atob: (s) => Buffer.from(s, "base64").toString("binary"),
        btoa: (s) => Buffer.from(s, "binary").toString("base64"),
        TextEncoder,
        crypto: { subtle: syncSubtle },
        fetch: () => {
            const next = sync.shift();
            if (!next) return Promise.reject(new Error("offline"));
            return Promise.resolve({ ok: true, json: () => Promise.resolve(next) });
        },
    };
    return { win, card, parts, calls, submitted, listeners, storage };
}

test("the card shows from the Home Screen once the worker is ready, and Turn On subscribes in the tap", async () => {
    const page = offerPage();
    install(page.win);
    assert.strictEqual(page.card.hidden, true, "hidden until the worker and the subscription are known");
    await settle();
    assert.strictEqual(page.card.hidden, false);
    const on = page.parts["[data-offer-on]"];
    assert.ok(!on.disabled);
    on.click();
    assert.strictEqual(page.calls.length, 1, "subscribe first, in the tap");
    assert.ok(page.calls[0][1].applicationServerKey instanceof Uint8Array);
    assert.strictEqual(page.calls[0][1].userVisibleOnly, true);
    // Disabled at the tap: a second tap while the prompt runs asks nothing more.
    assert.strictEqual(on.disabled, true);
    on.click();
    assert.strictEqual(page.calls.length, 1);
    await settle();
    assert.deepStrictEqual(page.submitted, ["on"]);
    assert.strictEqual(page.card.elements.endpoint.value, ENDPOINT);
    assert.strictEqual(page.card.elements.p256dh.value, "p-key");
    assert.strictEqual(page.card.elements.auth.value, "a-key");
});

test("display-mode standalone counts as the Home Screen too (Android, desktop)", async () => {
    const page = offerPage({ standalone: undefined, mediaStandalone: true });
    install(page.win);
    await settle();
    assert.strictEqual(page.card.hidden, false);
});

test("a plain tab shows nothing, until Chrome says the app was installed", async () => {
    const page = offerPage({ standalone: false });
    install(page.win);
    await settle();
    assert.strictEqual(page.card.hidden, true);
    page.listeners.appinstalled();
    await settle();
    assert.strictEqual(page.card.hidden, false);
});

test("a subscription the server knows keeps it hidden; one it lost is sent again, another key's dropped", async () => {
    let page = offerPage({
        subscription: { endpoint: ENDPOINT, unsubscribe: () => Promise.resolve(true) },
        known: [hashOf(ENDPOINT)],
    });
    install(page.win);
    await settle();
    assert.strictEqual(page.card.hidden, true);
    let subscription = browserSubscription();
    page = offerPage({ subscription, known: ["0123456789abcdef"], sync: [{ state: "stored" }] });
    install(page.win);
    await settle();
    assert.strictEqual(subscription.ended, false);
    assert.strictEqual(page.card.hidden, true, "known now");
    subscription = browserSubscription(null);
    subscription.options = { applicationServerKey: new Uint8Array(65).fill(4).buffer };
    page = offerPage({ subscription, known: ["0123456789abcdef"] });
    install(page.win);
    await settle();
    assert.ok(subscription.ended);
    assert.strictEqual(page.card.hidden, false);
    assert.strictEqual(page.card.elements.replaces.value, ENDPOINT);
});

test("an asked permission, or Not Now tapped before, shows nothing", async () => {
    for (const options of [{ permission: "granted" }, { permission: "denied" }, { stored: "not-now" }]) {
        const page = offerPage(options);
        install(page.win);
        await settle();
        assert.strictEqual(page.card.hidden, true, JSON.stringify(options));
    }
});

test("Not Now hides the card and is kept in this browser; storage that throws still shows it", async () => {
    let page = offerPage();
    install(page.win);
    await settle();
    page.parts["[data-offer-later]"].click();
    assert.strictEqual(page.card.hidden, true);
    assert.strictEqual(page.storage.value, "not-now");
    assert.deepStrictEqual(page.calls, []);
    page = offerPage({ storageThrows: true });
    install(page.win);
    await settle();
    assert.strictEqual(page.card.hidden, false);
    page.parts["[data-offer-later]"].click();
    assert.strictEqual(page.card.hidden, true);
});

test("a refused prompt says blocked and drops Turn On; another failure says try again", async () => {
    let page = offerPage({
        subscribe: () => {
            page.win.Notification.permission = "denied";
            return Promise.reject(new Error("NotAllowedError"));
        },
    });
    install(page.win);
    await settle();
    page.parts["[data-offer-on]"].click();
    await settle();
    assert.strictEqual(page.parts["[data-offer-line]"].textContent, "Notifications are blocked");
    assert.strictEqual(page.parts["[data-offer-on]"].hidden, true);
    assert.deepStrictEqual(page.submitted, []);
    page = offerPage({ subscribe: () => Promise.reject(new Error("AbortError")) });
    install(page.win);
    await settle();
    page.parts["[data-offer-on]"].click();
    await settle();
    assert.strictEqual(page.parts["[data-offer-line]"].textContent, "Couldn't turn notifications on");
    assert.strictEqual(page.parts["[data-offer-on]"].hidden, false);
    // Enabled again after the failure, so a second try works.
    assert.strictEqual(page.parts["[data-offer-on]"].disabled, false);
});
