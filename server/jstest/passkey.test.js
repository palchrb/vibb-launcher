// Behaviour of static/passkey.js (design 74 step 1): base64url both ways, the server's JSON options as the browser's
// options, a credential as the server reads it, the controls hidden without PublicKeyCredential (D4.4), the sign-in
// button's assertion posted in its form, and a re-auth that fills `assertion` and sends the form.
// Run: node --test jstest/passkey.test.js
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const passkey = require("../static/passkey.js");

function bytes(...values) {
    return new Uint8Array(values).buffer;
}

test("base64url without padding, both ways", () => {
    assert.strictEqual(passkey.b64url(bytes(0xfb, 0xff, 0xbf)), "-_-_");
    assert.strictEqual(passkey.b64url(bytes(1)), "AQ");
    assert.strictEqual(passkey.b64url(new Uint8Array([1, 2, 3]).subarray(1)), "AgM");
    assert.deepStrictEqual(new Uint8Array(passkey.unb64url("-_-_")), new Uint8Array([0xfb, 0xff, 0xbf]));
    assert.deepStrictEqual(new Uint8Array(passkey.unb64url("AQ")), new Uint8Array([1]));
});

test("the request options carry the challenge and the credentials as bytes", () => {
    const options = passkey.requestOptions({
        challenge: "AQID",
        rpId: "handy.example.com",
        timeout: 300000,
        userVerification: "required",
        allowCredentials: [{ type: "public-key", id: "BAU", transports: ["internal"] }],
    });
    assert.deepStrictEqual(new Uint8Array(options.challenge), new Uint8Array([1, 2, 3]));
    assert.strictEqual(options.rpId, "handy.example.com");
    assert.strictEqual(options.userVerification, "required");
    assert.deepStrictEqual(new Uint8Array(options.allowCredentials[0].id), new Uint8Array([4, 5]));
    assert.deepStrictEqual(options.allowCredentials[0].transports, ["internal"]);
    assert.deepStrictEqual(passkey.requestOptions({ challenge: "AQ", allowCredentials: [] }).allowCredentials, []);
});

test("the creation options carry the user id as bytes and keep the rest", () => {
    const options = passkey.creationOptions({
        challenge: "AQID",
        rp: { id: "handy.example.com", name: "Vibb" },
        user: { id: "CQk", name: "mamma", displayName: "Mamma" },
        pubKeyCredParams: [{ type: "public-key", alg: -7 }],
        authenticatorSelection: { residentKey: "required", userVerification: "required" },
        attestation: "none",
        excludeCredentials: [{ type: "public-key", id: "BAU" }],
        timeout: 300000,
    });
    assert.deepStrictEqual(new Uint8Array(options.user.id), new Uint8Array([9, 9]));
    assert.strictEqual(options.user.displayName, "Mamma");
    assert.strictEqual(options.authenticatorSelection.userVerification, "required");
    assert.strictEqual(options.attestation, "none");
    assert.deepStrictEqual(new Uint8Array(options.excludeCredentials[0].id), new Uint8Array([4, 5]));
});

test("a registration and an assertion as the server reads them", () => {
    const registration = passkey.credentialJSON({
        id: "AQI",
        rawId: bytes(1, 2),
        type: "public-key",
        response: { clientDataJSON: bytes(7), attestationObject: bytes(8), getTransports: () => ["internal", "hybrid"] },
    });
    assert.deepStrictEqual(registration, {
        id: "AQI",
        rawId: "AQI",
        type: "public-key",
        response: { clientDataJSON: "Bw", attestationObject: "CA", transports: ["internal", "hybrid"] },
    });
    const assertion = passkey.credentialJSON({
        id: "AQI",
        rawId: bytes(1, 2),
        type: "public-key",
        response: { clientDataJSON: bytes(7), authenticatorData: bytes(5), signature: bytes(6), userHandle: null },
    });
    assert.deepStrictEqual(assertion.response, {
        clientDataJSON: "Bw",
        authenticatorData: "BQ",
        signature: "Bg",
        userHandle: null,
    });
    const withHandle = passkey.credentialJSON({
        id: "AQI",
        rawId: bytes(1, 2),
        type: "public-key",
        response: { clientDataJSON: bytes(7), authenticatorData: bytes(5), signature: bytes(6), userHandle: bytes(3) },
    });
    assert.strictEqual(withHandle.response.userHandle, "Aw");
});

test("a form's route is its action's path; fields are urlencoded", () => {
    const win = { URL, location: { href: "https://handy.example.com/account" } };
    const form = { getAttribute: (n) => (n === "action" ? "/account/parents/3/reset#parents" : null) };
    assert.strictEqual(passkey.routeOf(win, form), "/account/parents/3/reset");
    assert.strictEqual(passkey.encode({ route: "/a b", x: null }), "route=%2Fa%20b&x=");
});

function element(attrs) {
    const el = {
        hidden: true,
        textContent: "",
        attrs: attrs || {},
        listeners: {},
        getAttribute: (name) => (name in el.attrs ? el.attrs[name] : null),
        setAttribute: (name, value) => { el.attrs[name] = value; },
        hasAttribute: (name) => name in el.attrs,
        addEventListener: (type, fn) => { el.listeners[type] = fn; },
        querySelector: () => null,
        querySelectorAll: () => [],
    };
    return el;
}

function fakePage(win, nodes) {
    win.document = {
        querySelectorAll: (s) => nodes[s] || [],
        querySelector: (s) => (nodes[s] || [])[0] || null,
        getElementById: (id) => (nodes["#" + id] || [])[0] || null,
    };
    return win;
}

test("without PublicKeyCredential every control stays hidden and the reason shows", () => {
    for (const [secure, text] of [[false, "needs https"], [true, "no passkeys here"]]) {
        const ui = element();
        const row = element({ "data-w-http": "needs https", "data-w-browser": "no passkeys here" });
        const win = fakePage({ isSecureContext: secure, navigator: {} }, {
            "[data-passkey-ui]": [ui],
            "[data-passkey-unsupported]": [row],
        });
        assert.strictEqual(passkey.supported(win), false);
        assert.strictEqual(passkey.install(win), false);
        assert.strictEqual(ui.hidden, true);
        assert.strictEqual(row.hidden, false);
        assert.strictEqual(row.textContent, text);
    }
});

/** A browser with passkeys: `credentials.get` answers `cred`, `fetch` answers `answers[url]`. */
function browser(nodes, cred, answers) {
    const fetched = [];
    const win = fakePage({
        isSecureContext: true,
        PublicKeyCredential: function () {},
        Promise,
        URL,
        location: { href: "https://handy.example.com/account" },
        navigator: { credentials: { get: (o) => { fetched.push(["get", o]); return Promise.resolve(cred); } } },
        fetch: (url, init) => {
            fetched.push([url, init]);
            const answer = answers[url];
            return Promise.resolve({ ok: answer.ok !== false, status: 200, json: () => Promise.resolve(answer) });
        },
    }, nodes);
    return { win, fetched };
}

const ASSERTION = {
    id: "AQI",
    rawId: bytes(1, 2),
    type: "public-key",
    response: { clientDataJSON: bytes(7), authenticatorData: bytes(5), signature: bytes(6), userHandle: bytes(3) },
};

const flush = () => new Promise((resolve) => setTimeout(resolve, 0));

test("the sign-in button posts the assertion in its form", async () => {
    const field = { value: "" };
    const button = element();
    let submitted = 0;
    const form = element({ "data-w-failed": "failed" });
    form.querySelector = (s) => (s === "[data-passkey-login]" ? button : s === "input[name=\"assertion\"]" ? field : null);
    form.submit = () => { submitted++; };
    const { win, fetched } = browser(
        { "#passkey-sign-in": [form], "[data-passkey-ui]": [form] },
        ASSERTION,
        { "/auth/passkey/options": { challenge: "AQID", rpId: "handy.example.com", allowCredentials: [] } },
    );
    assert.strictEqual(passkey.install(win), true);
    assert.strictEqual(form.hidden, false);
    button.listeners.click();
    await flush();
    await flush();
    assert.strictEqual(submitted, 1);
    assert.strictEqual(JSON.parse(field.value).response.userHandle, "Aw");
    assert.strictEqual(fetched[0][0], "/auth/passkey/options");
});

test("a cancelled prompt sends nothing and says nothing", async () => {
    const field = { value: "" };
    const button = element();
    const error = element();
    let submitted = 0;
    const form = element({ "data-w-failed": "failed" });
    form.querySelector = (s) => (s === "[data-passkey-login]" ? button : s === "input[name=\"assertion\"]" ? field : null);
    form.submit = () => { submitted++; };
    const { win } = browser(
        { "#passkey-sign-in": [form], "[data-passkey-error]": [error] },
        null,
        { "/auth/passkey/options": { challenge: "AQID", allowCredentials: [] } },
    );
    win.navigator.credentials.get = () => Promise.reject({ name: "NotAllowedError" });
    passkey.install(win);
    button.listeners.click();
    await flush();
    await flush();
    assert.strictEqual(submitted, 0);
    assert.strictEqual(error.hidden, true);
});

test("a re-auth fills assertion, empties the password and sends the form", async () => {
    const assertion = { value: "" };
    const password = { value: "typed", required: true, checkValidity: () => false };
    let sent = 0;
    const form = element({ action: "/account/reset-2fa#two-factor" });
    form.querySelector = (s) =>
        s === "input[name=\"assertion\"]" ? assertion : s.indexOf("current_password") >= 0 ? password : null;
    form.querySelectorAll = () => [password];
    form.requestSubmit = () => { sent++; };
    const error = element();
    const button = element({ "data-w-failed": "failed" });
    button.closest = () => form;
    button.parentNode = { querySelector: () => error };
    const { win, fetched } = browser(
        { "[data-passkey-reauth]": [button] },
        ASSERTION,
        { "/account/passkeys/reauth-options": { challenge: "AQID", allowCredentials: [{ id: "AQI" }] } },
    );
    passkey.install(win);
    button.listeners.click();
    await flush();
    await flush();
    assert.strictEqual(sent, 1);
    assert.strictEqual(password.value, "");
    assert.strictEqual(password.required, false);
    assert.strictEqual(JSON.parse(assertion.value).id, "AQI");
    assert.strictEqual(fetched[0][0], "/account/passkeys/reauth-options");
    assert.strictEqual(fetched[0][1].body, "route=%2Faccount%2Freset-2fa");
});

test("Add Passkey's re-auth is bound to its options call (qa-passkeys-1 B-M1)", () => {
    const win = { URL, location: { href: "https://handy.example.com/account" } };
    const attrs = { action: "/account/passkeys#add-passkey", "data-reauth-route": "/account/passkeys/options" };
    const form = { getAttribute: (n) => (n in attrs ? attrs[n] : null) };
    assert.strictEqual(passkey.routeOf(win, form), "/account/passkeys/options");
});

/** The add form with its fields; `answers` is what fetch answers per URL, `created` what create() gives. */
function addPage(answers, create) {
    const fields = {
        name: { value: "MacBook" },
        current_password: { value: "pw", required: true },
        assertion: { value: "" },
        credential: { value: "" },
    };
    const errors = { name: element(), password: element() };
    let submitted = 0;
    const form = element({ action: "/account/passkeys#add-passkey", "data-passkey-add": "", "data-w-failed": "failed", "data-w-exists": "exists" });
    form.querySelector = (s) => {
        const m = /name="(\w+)"/.exec(s);
        if (m) return fields[m[1]] || (s.indexOf("current_password") >= 0 ? fields.current_password : null);
        const e = /data-passkey-field-error="(\w+)"/.exec(s);
        return e ? errors[e[1]] : null;
    };
    form.submit = () => { submitted++; };
    const fetched = [];
    const win = fakePage({
        isSecureContext: true,
        PublicKeyCredential: function () {},
        Promise,
        URL,
        location: { href: "https://handy.example.com/account", assign: (to) => fetched.push(["assign", to]) },
        navigator: { credentials: { create, get: () => Promise.resolve(null) } },
        fetch: (url, init) => {
            fetched.push([url, init]);
            const a = answers[url];
            return Promise.resolve({ ok: a.ok !== false, status: a.status || 200, redirected: !!a.redirected, url: a.url, headers: { get: () => (a.html ? "text/html" : "application/json") }, json: () => (a.html ? Promise.reject(new Error("html")) : Promise.resolve(a)) });
        },
    }, { "[data-passkey-add]": [form] });
    return { win, form, fields, errors, fetched, submitted: () => submitted };
}

const CREATION = {
    challenge: "AQID",
    rp: { id: "handy.example.com", name: "Vibb" },
    user: { id: "CQk", name: "mamma", displayName: "Mamma" },
    pubKeyCredParams: [{ type: "public-key", alg: -7 }],
    excludeCredentials: [],
};
const REGISTRATION = {
    id: "AQI",
    rawId: bytes(1, 2),
    type: "public-key",
    response: { clientDataJSON: bytes(7), attestationObject: bytes(8), getTransports: () => ["internal"] },
};

test("the add flow: options behind the password, create, the credential posted", async () => {
    let created = null;
    const page = addPage({ "/account/passkeys/options": CREATION }, (o) => { created = o; return Promise.resolve(REGISTRATION); });
    passkey.install(page.win);
    let prevented = false;
    page.form.listeners.submit({ preventDefault: () => { prevented = true; } });
    await flush();
    await flush();
    assert.ok(prevented);
    assert.strictEqual(page.fetched[0][1].body, "name=MacBook&current_password=pw&assertion=");
    assert.strictEqual(page.fields.current_password.value, "", "the password is sent once");
    assert.deepStrictEqual(new Uint8Array(created.publicKey.user.id), new Uint8Array([9, 9]));
    assert.strictEqual(page.submitted(), 1);
    assert.strictEqual(JSON.parse(page.fields.credential.value).response.attestationObject, "CA");
});

test("a device that has a passkey already says so; a signed-out session goes to sign in", async () => {
    const page = addPage({ "/account/passkeys/options": CREATION }, () => Promise.reject({ name: "InvalidStateError" }));
    passkey.install(page.win);
    page.form.listeners.submit({ preventDefault: () => {} });
    await flush();
    await flush();
    await flush();
    assert.strictEqual(page.errors.password.textContent, "exists");
    assert.strictEqual(page.submitted(), 0);
    const out = addPage({ "/account/passkeys/options": { html: true, redirected: true, url: "https://handy.example.com/login" } }, () => Promise.resolve(REGISTRATION));
    passkey.install(out.win);
    out.form.listeners.submit({ preventDefault: () => {} });
    await flush();
    await flush();
    assert.deepStrictEqual(out.fetched[out.fetched.length - 1], ["assign", "/login"]);
});

test("the button aborts the autofill's get and asks with the same challenge", async () => {
    const field = { value: "" };
    const button = element();
    const form = element({ "data-w-failed": "failed" });
    form.querySelector = (s) => (s === "[data-passkey-login]" ? button : s === "input[name=\"assertion\"]" ? field : null);
    let submitted = 0;
    form.submit = () => { submitted++; };
    const calls = [];
    const aborted = [];
    const { win, fetched } = browser({ "#passkey-sign-in": [form] }, ASSERTION, {
        "/auth/passkey/options": { challenge: "AQID", allowCredentials: [] },
    });
    win.PublicKeyCredential.isConditionalMediationAvailable = () => Promise.resolve(true);
    win.AbortController = function () {
        const signal = { aborted: false };
        this.signal = signal;
        this.abort = () => { signal.aborted = true; aborted.push(true); };
    };
    win.navigator.credentials.get = (o) => {
        calls.push(o);
        if (o.mediation === "conditional") return new Promise(() => {});
        return Promise.resolve(ASSERTION);
    };
    passkey.install(win);
    await flush();
    await flush();
    assert.strictEqual(calls[0].mediation, "conditional");
    button.listeners.click();
    await flush();
    await flush();
    assert.deepStrictEqual(aborted, [true]);
    assert.strictEqual(calls[1].mediation, undefined);
    assert.deepStrictEqual(new Uint8Array(calls[1].publicKey.challenge), new Uint8Array([1, 2, 3]));
    assert.strictEqual(fetched.filter((f) => f[0] === "/auth/passkey/options").length, 1);
    assert.strictEqual(fetched[0][1].method, "POST");
    assert.strictEqual(submitted, 1);
});

test("only a 401 or a redirect to /login is signed out - csrf_guard's 403 page is an error (qa-passkeys-1 B-RN3)", async () => {
    // A 403 HTML page (a proxy that rewrites Host): the error under the field, no trip to the sign-in page.
    const forbidden = addPage({ "/account/passkeys/options": { html: true, ok: false, status: 403 } }, () => Promise.resolve(REGISTRATION));
    passkey.install(forbidden.win);
    forbidden.form.listeners.submit({ preventDefault: () => {} });
    await flush();
    await flush();
    await flush();
    assert.ok(!forbidden.fetched.some((f) => f[0] === "assign"), "not sent to /login");
    assert.strictEqual(forbidden.errors.password.textContent, "failed");
    // A redirect that ends elsewhere (an HTML page, not /login) isn't signed out either.
    const elsewhere = addPage({ "/account/passkeys/options": { html: true, ok: false, redirected: true, url: "https://handy.example.com/devices" } }, () => Promise.resolve(REGISTRATION));
    passkey.install(elsewhere.win);
    elsewhere.form.listeners.submit({ preventDefault: () => {} });
    await flush();
    await flush();
    assert.ok(!elsewhere.fetched.some((f) => f[0] === "assign"));
    // The server's 401 `{signed_out: true}` is.
    const out = addPage({ "/account/passkeys/options": { ok: false, status: 401, signed_out: true } }, () => Promise.resolve(REGISTRATION));
    passkey.install(out.win);
    out.form.listeners.submit({ preventDefault: () => {} });
    await flush();
    await flush();
    assert.deepStrictEqual(out.fetched[out.fetched.length - 1], ["assign", "/login"]);
});

/** The sign-in page with a conditional get, a clock and timers the test moves. */
function conditionalPage(attrs) {
    const field = { value: "" };
    const button = element();
    const form = element(Object.assign({ "data-w-failed": "failed" }, attrs || {}));
    form.querySelector = (s) => (s === "[data-passkey-login]" ? button : s === "input[name=\"assertion\"]" ? field : null);
    form.submit = () => {};
    let challenge = 0;
    const answers = {};
    const { win, fetched } = browser({ "#passkey-sign-in": [form] }, ASSERTION, answers);
    // Each options call is a fresh challenge (1, 2, ...).
    win.fetch = (url, init) => {
        fetched.push([url, init]);
        challenge++;
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ challenge: passkey.b64url(bytes(challenge)), allowCredentials: [] }) });
    };
    let clock = 1000000;
    const timers = [];
    win.Date = { now: () => clock };
    win.setTimeout = (fn, ms) => { timers.push({ fn, at: clock + ms }); return timers.length; };
    win.clearTimeout = () => {};
    const docListeners = {};
    win.document.addEventListener = (type, fn) => { docListeners[type] = fn; };
    win.document.visibilityState = "visible";
    win.addEventListener = () => {};
    win.PublicKeyCredential.isConditionalMediationAvailable = () => Promise.resolve(true);
    const aborted = [];
    win.AbortController = function () {
        const signal = { aborted: false };
        this.signal = signal;
        this.abort = () => { signal.aborted = true; aborted.push(true); };
    };
    const gets = [];
    win.navigator.credentials.get = (o) => {
        gets.push(o);
        return o.mediation === "conditional" ? new Promise(() => {}) : Promise.resolve(ASSERTION);
    };
    return {
        win, button, fetched, gets, aborted, docListeners,
        advance: (ms) => { clock += ms; },
        fire: () => {
            const due = timers.filter((t) => t.at <= clock);
            for (const t of due) timers.splice(timers.indexOf(t), 1);
            for (const t of due) t.fn();
        },
    };
}

test("the autofill's get starts again with fresh options once they are 4.5 minutes old (qa-passkeys-1 B-RN2)", async () => {
    const page = conditionalPage();
    passkey.install(page.win);
    await flush();
    await flush();
    assert.strictEqual(page.gets.length, 1);
    assert.deepStrictEqual(new Uint8Array(page.gets[0].publicKey.challenge), new Uint8Array([1]));
    // Back after four minutes: still fresh enough, nothing happens.
    page.advance(240000);
    page.docListeners.visibilitychange();
    await flush();
    assert.strictEqual(page.gets.length, 1);
    // Back after 4.5: the old get is aborted and a new one asks with a new challenge.
    page.advance(30000);
    page.docListeners.visibilitychange();
    await flush();
    await flush();
    await flush();
    assert.deepStrictEqual(page.aborted, [true]);
    assert.strictEqual(page.gets.length, 2);
    assert.deepStrictEqual(new Uint8Array(page.gets[1].publicKey.challenge), new Uint8Array([2]));
    // The timer does the same on a page that stays on screen.
    page.advance(270000);
    page.fire();
    await flush();
    await flush();
    await flush();
    assert.strictEqual(page.gets.length, 3);
    assert.deepStrictEqual(new Uint8Array(page.gets[2].publicKey.challenge), new Uint8Array([3]));
});

test("the passkey-only sign-in page starts nothing on its own; its button signs in (§6.4)", async () => {
    const page = conditionalPage({ "data-passkey-only": "" });
    passkey.install(page.win);
    await flush();
    await flush();
    assert.strictEqual(page.gets.length, 0, "no autofill get");
    assert.strictEqual(page.fetched.length, 0, "no options before the tap");
    page.button.listeners.click();
    await flush();
    await flush();
    assert.strictEqual(page.gets.length, 1);
    assert.strictEqual(page.gets[0].mediation, undefined);
});

test("a passkey-only form's submit (Enter included) starts the passkey, never sends the empty password (qa-passkeys-2 M1)", async () => {
    const assertion = { value: "" };
    let sent = 0;
    const formListeners = {};
    const button = element({ "data-w-failed": "failed", "data-passkey-only": "" });
    const form = element({ action: "/account/parents#parents" });
    form.addEventListener = (type, fn) => { formListeners[type] = fn; };
    form.querySelector = (s) =>
        s === "input[name=\"assertion\"]" ? assertion : s === "[data-passkey-reauth][data-passkey-only]" ? button : null;
    form.querySelectorAll = () => [];
    form.requestSubmit = () => { sent++; };
    const error = element();
    button.closest = () => form;
    button.parentNode = { querySelector: () => error };
    const { win, fetched } = browser(
        { "[data-passkey-reauth]": [button] },
        ASSERTION,
        { "/account/passkeys/reauth-options": { challenge: "AQID", allowCredentials: [{ id: "AQI" }] } },
    );
    passkey.install(win);
    let prevented = false;
    formListeners.submit({ preventDefault: () => { prevented = true; } });
    await flush();
    await flush();
    assert.ok(prevented, "the empty submit is held");
    assert.strictEqual(fetched[0][0], "/account/passkeys/reauth-options");
    assert.strictEqual(sent, 1, "sent once the assertion is in");
    // With the assertion in, the submit goes through.
    let again = false;
    formListeners.submit({ preventDefault: () => { again = true; } });
    assert.strictEqual(again, false);
});

test("reused options with a minute left refresh within 30 seconds (qa-passkeys-2 limiter L1)", async () => {
    const page = conditionalPage();
    page.win.fetch = (url, init) => {
        page.fetched.push([url, init]);
        const n = page.fetched.length;
        return Promise.resolve({ ok: true, status: 200, json: () => Promise.resolve({ challenge: passkey.b64url(bytes(n)), timeout: n === 1 ? 60000 : 300000, allowCredentials: [] }) });
    };
    passkey.install(page.win);
    await flush();
    await flush();
    assert.strictEqual(page.gets.length, 1);
    page.advance(29000);
    page.fire();
    await flush();
    assert.strictEqual(page.gets.length, 1, "not yet");
    page.advance(1000);
    page.fire();
    await flush();
    await flush();
    await flush();
    assert.strictEqual(page.gets.length, 2);
    assert.deepStrictEqual(new Uint8Array(page.gets[1].publicKey.challenge), new Uint8Array([2]));
});

// ---- Step 3: the app lock ------------------------------------------------------------------------------------------

/** A browser whose fetch answers `answers[url]` = {status, json} (default 200). */
function lockBrowser(nodes, answers, get) {
    const fetched = [];
    const win = fakePage({
        isSecureContext: true,
        PublicKeyCredential: function () {},
        Promise,
        URL,
        Date,
        replaced: [],
        stored: {},
        sessionStorage: { setItem(key, value) { win.stored[key] = value; } },
        addEventListener: () => {},
        location: {
            href: "https://handy.example.com/lock?next=%2Fdevices%2F3",
            pathname: "/lock",
            search: "?next=%2Fdevices%2F3",
            hash: "",
            replace: (to) => win.replaced.push(to),
        },
        navigator: { credentials: { get: (o) => { fetched.push(["get", o]); return get(o); } } },
        fetch: (url, init) => {
            fetched.push([url, init]);
            const answer = answers[url] || { status: 404, json: {} };
            const status = answer.status || 200;
            return Promise.resolve({ ok: status < 300, status, json: () => Promise.resolve(answer.json || {}) });
        },
    }, nodes);
    win.document.addEventListener = () => {};
    return { win, fetched };
}

function unlockPage(answers, get) {
    const next = { value: "/devices/3" };
    const button = element();
    const error = element({ "data-w-failed": "Couldn't unlock" });
    const form = element();
    form.querySelector = (s) =>
        s === "[data-passkey-unlock-button]" ? button : s === "[data-passkey-error]" ? error : s === "input[name=\"next\"]" ? next : null;
    const page = lockBrowser({ "[data-passkey-unlock]": [form], "[data-passkey-ui]": [button] }, answers, get);
    return Object.assign(page, { button, error });
}

const UNLOCK_OPTIONS = { json: { challenge: "AQID", rpId: "handy.example.com", allowCredentials: [{ id: "AQI" }] } };

test("the lock screen: its challenge is fetched at load, a tap prompts at once, the unlock replaces the page", async () => {
    const page = unlockPage(
        { "/lock/options": UNLOCK_OPTIONS, "/lock": { json: { next: "/devices/3" } } },
        () => Promise.resolve(ASSERTION),
    );
    passkey.install(page.win);
    assert.strictEqual(page.button.hidden, false);
    assert.strictEqual(page.fetched[0][0], "/lock/options");
    await flush();
    page.button.listeners.click();
    // Within the tap: the prompt before any fetch (iOS needs the gesture).
    assert.strictEqual(page.fetched[1][0], "get");
    await flush();
    await flush();
    const post = page.fetched.find((f) => f[0] === "/lock");
    assert.ok(post, "posted");
    const body = new URLSearchParams(post[1].body);
    assert.strictEqual(JSON.parse(body.get("assertion")).id, "AQI");
    assert.strictEqual(body.get("next"), "/devices/3");
    assert.deepStrictEqual(page.win.replaced, ["/devices/3"]);
    assert.ok(page.win.stored["handy-used"], "the use starts now in this tab");
});

test("the lock screen: a cancelled or refused try shows the red line and fetches a fresh challenge", async () => {
    const cancelled = unlockPage({ "/lock/options": UNLOCK_OPTIONS }, () => Promise.reject({ name: "NotAllowedError" }));
    passkey.install(cancelled.win);
    await flush();
    cancelled.button.listeners.click();
    await flush();
    await flush();
    assert.strictEqual(cancelled.error.hidden, false);
    assert.strictEqual(cancelled.error.textContent, "Couldn't unlock");
    assert.strictEqual(cancelled.fetched.filter((f) => f[0] === "/lock/options").length, 2);
    assert.deepStrictEqual(cancelled.win.replaced, []);
    const refused = unlockPage(
        { "/lock/options": UNLOCK_OPTIONS, "/lock": { status: 400, json: { error: "Couldn't unlock. Try again" } } },
        () => Promise.resolve(ASSERTION),
    );
    passkey.install(refused.win);
    await flush();
    refused.button.listeners.click();
    await flush();
    await flush();
    await flush();
    assert.strictEqual(refused.error.textContent, "Couldn't unlock. Try again");
    assert.deepStrictEqual(refused.win.replaced, []);
});

test("Account's lock: a change asks the passkey, then sends the form; a cancel puts the control back", async () => {
    function lockForm(get, control) {
        const assertion = { value: "" };
        const error = element({ "data-w-failed": "failed" });
        let sent = 0;
        const form = element({ action: "/account/lock#app-lock", "data-saved": "0" });
        form.querySelector = (s) =>
            s === "[data-app-lock-pick]" ? control : s === "[data-passkey-error]" ? error : s === "input[name=\"assertion\"]" ? assertion : null;
        form.requestSubmit = () => { sent++; };
        const page = lockBrowser(
            { "[data-app-lock]": [form] },
            { "/account/passkeys/reauth-options": UNLOCK_OPTIONS },
            get,
        );
        page.win.location.href = "https://handy.example.com/account";
        passkey.install(page.win);
        return Object.assign(page, { assertion, sent: () => sent });
    }
    const sw = Object.assign(element(), { type: "checkbox", value: "5", checked: false });
    const on = lockForm(() => Promise.resolve(ASSERTION), sw);
    sw.listeners.pointerdown();
    await flush();
    assert.strictEqual(on.fetched[0][1].body, "route=%2Faccount%2Flock");
    sw.checked = true;
    sw.listeners.change();
    assert.strictEqual(on.fetched[1][0], "get", "the prompt within the tap");
    await flush();
    assert.strictEqual(on.sent(), 1);
    assert.strictEqual(JSON.parse(on.assertion.value).id, "AQI");
    // Cancelled: back off, nothing sent.
    const sw2 = Object.assign(element(), { type: "checkbox", value: "5", checked: false });
    const off = lockForm(() => Promise.reject({ name: "NotAllowedError" }), sw2);
    sw2.checked = true;
    sw2.listeners.change();
    await flush();
    await flush();
    await flush();
    assert.strictEqual(off.sent(), 0);
    assert.strictEqual(sw2.checked, false);
});

test("a 423 (the server's lock) goes to the lock screen and ends the flow there", async () => {
    const assertion = { value: "" };
    const form = element({ action: "/account/reset-2fa#two-factor" });
    form.querySelector = (s) => (s === "input[name=\"assertion\"]" ? assertion : null);
    form.querySelectorAll = () => [];
    form.requestSubmit = () => assert.fail("sent");
    const error = element();
    const button = element({ "data-w-failed": "failed" });
    button.closest = () => form;
    button.parentNode = { querySelector: () => error };
    const page = lockBrowser(
        { "[data-passkey-reauth]": [button] },
        { "/account/passkeys/reauth-options": { status: 423 } },
        () => assert.fail("no prompt"),
    );
    page.win.location.pathname = "/account";
    page.win.location.search = "";
    const locks = [];
    page.win.handyLock = { lock: (next) => locks.push(next) };
    passkey.install(page.win);
    button.listeners.click();
    await flush();
    await flush();
    assert.deepStrictEqual(locks, ["/account"]);
    assert.strictEqual(error.hidden, true, "no error shown");
});

test("the lock screen fetches a fresh challenge every 3.5 minutes on screen, so a late tap still prompts at once (QA L2)", async () => {
    const page = unlockPage({ "/lock/options": UNLOCK_OPTIONS }, () => Promise.resolve(null));
    const timers = [];
    page.win.setTimeout = (fn, ms) => timers.push({ fn, ms });
    page.win.document.visibilityState = "visible";
    passkey.install(page.win);
    await flush();
    assert.strictEqual(timers.length, 1);
    assert.strictEqual(timers[0].ms, passkey.UNLOCK_REFRESH_MS);
    assert.ok(passkey.UNLOCK_REFRESH_MS < passkey.AHEAD_MS);
    timers.shift().fn();
    await flush();
    assert.strictEqual(page.fetched.filter((f) => f[0] === "/lock/options").length, 2, "fetched again");
    assert.strictEqual(timers.length, 1, "and again later");
    // Hidden: no fetch (the page refetches when shown).
    page.win.document.visibilityState = "hidden";
    timers.shift().fn();
    await flush();
    assert.strictEqual(page.fetched.filter((f) => f[0] === "/lock/options").length, 2);
    // The tap after the refresh prompts within the gesture.
    page.win.document.visibilityState = "visible";
    page.button.listeners.click();
    assert.strictEqual(page.fetched[page.fetched.length - 1][0], "get");
});
