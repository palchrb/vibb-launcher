// Behaviour of static/scroll-restore.js (qa-fixround-2026-10-06 #5, the PWA browser pass of
// 2026-10-14, design 43's app shell). Run: node --test jstest/
//
// Every restore test runs twice (`both`): with the window as the scroller (the auth pages, the
// pages before design 43) and with a scrolling <main> already scrolled to 300 (the shell) - there
// the window must never be scrolled, and the #fragment cases must land on the group all the same.
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const { install, restoreTarget, scrollerOf, navigationType, KEY, NAME_PREFIX, MAX_AGE_MS, PLACE_KEY, MAX_PLACES, FROM_KEY } =
    require("../static/scroll-restore.js");

test("a record counts for 20 s (server/CLAUDE.md, design 31 §5.1); 50 places are kept", () => {
    assert.strictEqual(MAX_AGE_MS, 20000);
    assert.strictEqual(MAX_PLACES, 50);
});

/** An element with an id (or "") at `top` on the screen, inside `parent`. */
function fakeElement(id, top, parent = null) {
    return { id, parentNode: parent, getBoundingClientRect: () => ({ top }) };
}

/**
 * A fake window. `mode` "window" scrolls the window, "main" a <main> whose overflow scrolls
 * (scrolled to `scrollY`, 300 unless given). `elements` maps an id to its top in the scrolled
 * content, or to [top, the id of the element it is in]; its screen top follows the scroller.
 * `scrolls` records every restore as [0, y] in both modes; `windowScrolls` the window's own.
 */
function fakeWindow({
    mode = "window",
    path = "/devices/1",
    search = "",
    hash = "",
    scrollY,
    stored = null,
    places = null,
    readyState = "complete",
    elements = {},
    navType = null,
    legacyNav = null,
    origin = 0,
} = {}) {
    const store = new Map();
    if (stored) store.set(KEY, JSON.stringify(stored));
    if (places) store.set(PLACE_KEY, JSON.stringify(places));
    const listeners = {};
    const winListeners = {};
    const scrolls = [];
    const windowScrolls = [];
    const nativeSubmits = [];
    const inMain = mode === "main";
    let mainTop = inMain ? (scrollY === undefined ? 300 : scrollY) : 0;
    const main = {
        overflowY: inMain ? "auto" : "visible",
        get scrollTop() { return mainTop; },
        set scrollTop(y) { mainTop = y; scrolls.push([0, y]); },
    };
    function HTMLFormElement() {}
    HTMLFormElement.prototype.submit = function () { nativeSubmits.push(this); };
    const scrolled = () => (inMain ? mainTop : win.scrollY);
    function byId(id) {
        if (!(id in elements)) return null;
        const [top, parent] = Array.isArray(elements[id]) ? elements[id] : [elements[id], null];
        return {
            id,
            parentNode: parent ? byId(parent) : null,
            getBoundingClientRect: () => ({ top: origin + top - scrolled() }),
        };
    }
    const win = {
        name: "",
        location: { pathname: path, search, hash },
        scrollY: inMain ? 0 : scrollY || 0,
        history: { scrollRestoration: "auto" },
        sessionStorage: {
            getItem: (k) => (store.has(k) ? store.get(k) : null),
            setItem: (k, v) => store.set(k, v),
            removeItem: (k) => store.delete(k),
        },
        HTMLFormElement,
        hasMain: true,
        getComputedStyle: (el) => ({ overflowY: el.overflowY }),
        scrollTo: (x, y) => {
            windowScrolls.push([x, y]);
            if (!inMain) {
                win.scrollY = y;
                scrolls.push([x, y]);
            }
        },
        addEventListener: (type, fn) => { winListeners[type] = fn; },
        document: {
            readyState,
            visibilityState: "visible",
            addEventListener: (type, fn) => { listeners[type] = fn; },
            getElementById: byId,
            querySelector: (selector) => (selector === "main" && win.hasMain ? main : null),
        },
    };
    if (navType) win.performance = { getEntriesByType: (t) => (t === "navigation" ? [{ type: navType }] : []) };
    if (legacyNav !== null) win.performance = { getEntriesByType: () => [], navigation: { type: legacyNav } };
    const w = { win, main, store, listeners, winListeners, scrolls, windowScrolls, nativeSubmits };
    made.push({ mode, w });
    return w;
}

let made = [];

/** The test once per scroller; in the shell the window is never scrolled. */
function both(name, fn) {
    for (const mode of ["window", "main"]) {
        test(`${name} (${mode})`, () => {
            made = [];
            fn(mode);
            for (const { mode: m, w } of made) {
                if (m === "main") assert.deepStrictEqual(w.windowScrolls, [], "the window stays put in the shell");
            }
        });
    }
}

// A form in a row in a group in <main>: only the group has an id.
function formInGroup() {
    const main = fakeElement("", -900);
    const group = fakeElement("music-settings", 120, main);
    const row = fakeElement("", 180, group);
    return fakeElement("", 180, row);
}

both("a submit remembers path, position, time and where the form's groups are on the screen", (mode) => {
    const w = fakeWindow({ mode, scrollY: 840 });
    install(w.win, () => 1000);
    w.listeners.submit({ target: formInGroup() });
    assert.deepStrictEqual(JSON.parse(w.store.get(KEY)), {
        path: "/devices/1", y: 840, t: 1000, tops: { "music-settings": 120 },
    });
});

both("form.submit() (the auto-saving controls) remembers too and still submits", (mode) => {
    const w = fakeWindow({ mode, scrollY: 300 });
    install(w.win, () => 5);
    const form = new w.win.HTMLFormElement();
    form.id = "lock-screen-app-x";
    form.parentNode = fakeElement("notifications", 40);
    form.getBoundingClientRect = () => ({ top: 90 });
    form.submit();
    assert.strictEqual(w.nativeSubmits.length, 1);
    assert.strictEqual(w.nativeSubmits[0], form);
    const saved = JSON.parse(w.store.get(KEY));
    assert.strictEqual(saved.y, 300);
    assert.deepStrictEqual(saved.tops, { "lock-screen-app-x": 90, notifications: 40 });
});

both("the page that comes back scrolls to the saved position, once", (mode) => {
    const w = fakeWindow({ mode, stored: { path: "/devices/1", y: 840, t: 1000 } });
    install(w.win, () => 3000);
    assert.deepStrictEqual(w.scrolls, [[0, 840]]);
    assert.strictEqual(w.win.history.scrollRestoration, "manual");
    assert.ok(!w.store.has(KEY), "consumed");
    w.winListeners.load();
    assert.deepStrictEqual(w.scrolls, [[0, 840], [0, 840]]);
    // Then the entry is the browser's again (qa-browser-fixes-code M1).
    assert.strictEqual(w.win.history.scrollRestoration, "auto");
});

// qa-browser-fixes-code M1: after the save's page has loaded, the user opens a link and comes
// Back. The record is used up, so only the browser (or the place record) can bring the place back:
// the entry must not stay "manual", and the page loaded again restores nothing from the record.
both("Back to a saved page later is not the form record's: the entry is \"auto\" again after load", (mode) => {
    const w = fakeWindow({ mode, hash: "#music-settings", stored: { path: "/devices/1", y: 840, t: 0, tops: { "music-settings": 120 } }, elements: { "music-settings": 960 } });
    install(w.win, () => 100);
    assert.strictEqual(w.win.history.scrollRestoration, "manual", "before load: the script restores");
    w.winListeners.load();
    assert.strictEqual(w.win.history.scrollRestoration, "auto", "after load: the browser keeps the place");
    const back = fakeWindow({ mode, hash: "#music-settings", elements: { "music-settings": 960 } });
    back.win.history = w.win.history;
    install(back.win, () => 5000);
    assert.deepStrictEqual(back.scrolls, []);
    assert.strictEqual(back.win.history.scrollRestoration, "auto");
});

both("still loading: restores at DOMContentLoaded", (mode) => {
    const w = fakeWindow({ mode, stored: { path: "/devices/1", y: 10, t: 0 }, readyState: "loading" });
    install(w.win, () => 1);
    assert.deepStrictEqual(w.scrolls, []);
    w.listeners.DOMContentLoaded();
    assert.deepStrictEqual(w.scrolls, [[0, 10]]);
});

// Every form redirects to its own group's #fragment, the no-JS fallback (§5.1.4); with JS the place
// is kept over it: a save never moves the page.
both("a #fragment naming the form's own group keeps that group where it was on the screen", (mode) => {
    const stored = { path: "/devices/1", y: 840, t: 0, tops: { "music-settings": 120 } };
    // Nothing above it changed (the group is at 960 in the document): the same position.
    const same = fakeWindow({ mode, hash: "#music-settings", stored, elements: { "music-settings": 960 } });
    install(same.win, () => 100);
    assert.deepStrictEqual(same.scrolls, [[0, 840]]);
    assert.strictEqual(same.win.history.scrollRestoration, "manual");
    // A warning came in above it (still in its place in the page): it is kept where it was on
    // the screen, the page a little lower.
    const lower = fakeWindow({ mode, hash: "#music-settings", stored, elements: { "music-settings": 1020 } });
    install(lower.win, () => 100);
    assert.deepStrictEqual(lower.scrolls, [[0, 900]]);
    lower.winListeners.load();
    assert.deepStrictEqual(lower.scrolls, [[0, 900], [0, 900]]);
    assert.strictEqual(lower.win.history.scrollRestoration, "auto");
    // Not on the page that came back: the saved position.
    const gone = fakeWindow({ mode, hash: "#music-settings", stored });
    install(gone.win, () => 100);
    assert.deepStrictEqual(gone.scrolls, [[0, 840]]);
    // A percent-encoded id.
    const encoded = fakeWindow({
        mode, hash: "#app-%C3%A5", stored: { ...stored, tops: { "app-å": 10 } }, elements: { "app-å": 410 },
    });
    install(encoded.win, () => 100);
    assert.deepStrictEqual(encoded.scrolls, [[0, 400]]);
});

// The saved tops are screen tops: where the scroller itself sits on the screen cancels out.
test("a scroller that doesn't start at the screen's top keeps the group in place too", () => {
    // The group was at 120 on the screen with <main> (its top at 44) scrolled to 840: it is at
    // 916 in <main>'s content. Nothing changed: the same 840.
    const w = fakeWindow({
        mode: "main", origin: 44, hash: "#g", stored: { path: "/devices/1", y: 840, t: 0, tops: { g: 120 } },
        elements: { g: 916 },
    });
    install(w.win, () => 100);
    assert.deepStrictEqual(w.scrolls, [[0, 840]]);
});

// Device › Apps: a switch moves its row to the other group. Following the row would scroll the
// page to that group - the jump the user saw (2026-10-19) - so the exact position is kept and
// the row is simply gone from where it was.
both("a row that moved to another group is not followed: the same position", (mode) => {
    const stored = { path: "/devices/1/apps", y: 840, t: 0, tops: { "app-x": 180, allowed: 120 } };
    const w = fakeWindow({
        mode,
        path: "/devices/1/apps",
        hash: "#app-x",
        stored,
        elements: { allowed: 960, "not-allowed": 1900, "app-x": [2300, "not-allowed"] },
    });
    install(w.win, () => 100);
    assert.deepStrictEqual(w.scrolls, [[0, 840]]);
    assert.strictEqual(w.win.history.scrollRestoration, "manual");
    // Still in its group: followed as before.
    const inPlace = fakeWindow({
        mode,
        path: "/devices/1/apps",
        hash: "#app-x",
        stored,
        elements: { allowed: 960, "app-x": [1060, "allowed"] },
    });
    install(inPlace.win, () => 100);
    assert.deepStrictEqual(inPlace.scrolls, [[0, 880]]);
});

both("a #fragment inside the form's group (a new row, a deleted row's neighbour) keeps the group", (mode) => {
    // Add Contact (in #add-contact, in #contacts) lands on the new row, #contact-9: #contacts,
    // the nearest element around both, stays where it was on the screen.
    const stored = { path: "/devices/1/calls", y: 900, t: 0, tops: { "add-contact": 500, contacts: 100 } };
    const w = fakeWindow({
        mode,
        path: "/devices/1/calls",
        hash: "#contact-9",
        stored,
        elements: { contacts: 1000, "contact-9": [1400, "contacts"], "add-contact": [1480, "contacts"] },
    });
    install(w.win, () => 100);
    assert.deepStrictEqual(w.scrolls, [[0, 900]]);
    assert.strictEqual(w.win.history.scrollRestoration, "manual");
});

both("a #fragment naming anything else leaves the browser at that anchor", (mode) => {
    // A row of another group (a photo uploaded in #add lands on its row in #all), an id the page
    // doesn't have, an inherited property name, a record without groups (auto-reload.js's: it
    // hands the place to the browser) or a bad one.
    for (const opts of [
        { hash: "#wallpaper-9", stored: { path: "/devices/1", y: 5, t: 0, tops: { add: 50 } } },
        { hash: "#nowhere", stored: { path: "/devices/1", y: 5, t: 0, tops: { add: 50 } } },
        { hash: "#toString", stored: { path: "/devices/1", y: 5, t: 0, tops: {} } },
        { hash: "#rules", stored: { path: "/devices/1", y: 5, t: 0 } },
        { hash: "#rules", stored: { path: "/devices/1", y: 5, t: 0, tops: { rules: "50" } } },
    ]) {
        const w = fakeWindow({ mode, ...opts, elements: { all: 600, "wallpaper-9": [700, "all"], add: 300, rules: 300, toString: 1 } });
        install(w.win, () => 100);
        assert.deepStrictEqual(w.scrolls, [], JSON.stringify(opts));
        assert.strictEqual(w.win.history.scrollRestoration, "auto");
        assert.ok(!w.store.has(KEY), "consumed even when not used");
    }
});

both("another path or an old save don't restore", (mode) => {
    for (const opts of [
        { path: "/devices/2", stored: { path: "/devices/1", y: 5, t: 0 } },
        { path: "/devices/2", hash: "#a", stored: { path: "/devices/1", y: 5, t: 0, tops: { a: 0 } } },
    ]) {
        const w = fakeWindow({ mode, ...opts, elements: { a: 0 } });
        install(w.win, () => 100);
        assert.deepStrictEqual(w.scrolls, [], JSON.stringify(opts));
        assert.ok(!w.store.has(KEY), "consumed even when not used");
    }
    const old = fakeWindow({ mode, stored: { path: "/devices/1", y: 5, t: 0 } });
    install(old.win, () => MAX_AGE_MS);
    assert.deepStrictEqual(old.scrolls, []);
    // An old record over the form's own group's fragment restores nothing either (qa L2).
    const oldGroup = fakeWindow({ mode, hash: "#a", stored: { path: "/devices/1", y: 5, t: 0, tops: { a: 40 } }, elements: { a: 300 } });
    install(oldGroup.win, () => MAX_AGE_MS);
    assert.deepStrictEqual(oldGroup.scrolls, []);
    oldGroup.winListeners.load();
    assert.deepStrictEqual(oldGroup.scrolls, []);
});

test("restoreTarget rejects garbage and clock jumps", () => {
    assert.strictEqual(restoreTarget(null, "/", "", 0), null);
    assert.strictEqual(restoreTarget({ path: "/", y: "1", t: 0 }, "/", "", 0), null);
    assert.strictEqual(restoreTarget({ path: "/", y: 1, t: 10 }, "/", "", 5), null);
    assert.strictEqual(restoreTarget({ path: "/", y: 1, t: 0 }, "/", "", MAX_AGE_MS - 1), 1);
    assert.strictEqual(restoreTarget({ path: "/", y: 7, t: 0 }, "/", "#", 0), 7, "an empty # is none");
});

both("no sessionStorage (private mode): nothing breaks, and window.name still carries the record", (mode) => {
    const w = fakeWindow({ mode, scrollY: 0, navType: "reload" });
    Object.defineProperty(w.win, "sessionStorage", { get() { throw new Error("denied"); } });
    install(w.win, () => 0);
    w.listeners.submit({ target: formInGroup() });
    w.winListeners.pagehide();
    assert.deepStrictEqual(w.scrolls, []);
    assert.deepStrictEqual(JSON.parse(w.win.name.slice(NAME_PREFIX.length)), {
        path: "/devices/1", y: 0, t: 0, tops: { "music-settings": 120 },
    });
});

// An installed iPhone app lost sessionStorage across the save's navigation (the user,
// 2026-10-19): window.name is the second carrier, read when the storage has nothing, and
// cleared either way so a later page never reads a stale record from it.
both("a record in window.name restores when sessionStorage has none, and is cleared", (mode) => {
    const w = fakeWindow({ mode, hash: "#music-settings", elements: { "music-settings": 960 } });
    w.win.name = NAME_PREFIX + JSON.stringify({ path: "/devices/1", y: 840, t: 0, tops: { "music-settings": 120 } });
    install(w.win, () => 100);
    assert.deepStrictEqual(w.scrolls, [[0, 840]]);
    assert.strictEqual(w.win.name, "");
    // sessionStorage wins when both exist; the name is cleared too.
    const both = fakeWindow({ mode, stored: { path: "/devices/1", y: 300, t: 0 } });
    both.win.name = NAME_PREFIX + JSON.stringify({ path: "/devices/1", y: 840, t: 0 });
    install(both.win, () => 100);
    assert.deepStrictEqual(both.scrolls, [[0, 300]]);
    assert.strictEqual(both.win.name, "");
    // Someone else's window.name is left alone.
    const other = fakeWindow({ mode });
    other.win.name = "their-frame";
    install(other.win, () => 100);
    assert.strictEqual(other.win.name, "their-frame");
    assert.deepStrictEqual(other.scrolls, []);
    assert.strictEqual(other.win.handyPlaceRecord, undefined);
});

// qa-pwa-fixes-d Q-6: the record taken is handed to reconnect.js's sign-in check (deferred, it
// runs after both carriers were cleared here), the query in its `href` kept.
both("the record taken is left for reconnect.js as handyPlaceRecord", (mode) => {
    const w = fakeWindow({ mode });
    const record = { path: "/devices/1", href: "/devices/1?tab=apps", y: 840, t: 0 };
    w.win.name = NAME_PREFIX + JSON.stringify(record);
    install(w.win, () => 100);
    assert.strictEqual(w.win.name, "");
    assert.deepStrictEqual(w.win.handyPlaceRecord, record);
    // A sign-in page's bounce mark has another prefix: left alone.
    const bounce = fakeWindow({ mode });
    bounce.win.name = "handy-login-bounce:12345";
    install(bounce.win, () => 100);
    assert.strictEqual(bounce.win.name, "handy-login-bounce:12345");
});

// A browser that scrolls to the #fragment after load (iOS) must not win: the place is put back
// on the two frames after load, and only then is the entry the browser's again.
both("after load the place is put back on two more frames before the entry goes back to the browser", (mode) => {
    const w = fakeWindow({ mode, stored: { path: "/devices/1", y: 840, t: 1000 } });
    const frames = [];
    w.win.requestAnimationFrame = (fn) => frames.push(fn);
    install(w.win, () => 3000);
    w.winListeners.load();
    assert.deepStrictEqual(w.scrolls, [[0, 840], [0, 840]]);
    assert.strictEqual(w.win.history.scrollRestoration, "manual", "still ours during the frames");
    frames.shift()();
    assert.deepStrictEqual(w.scrolls, [[0, 840], [0, 840], [0, 840]]);
    assert.strictEqual(w.win.history.scrollRestoration, "manual");
    frames.shift()();
    assert.strictEqual(w.scrolls.length, 4);
    assert.strictEqual(w.win.history.scrollRestoration, "auto");
    assert.strictEqual(frames.length, 0, "no more frames");
});

// ---- Design 43: the scroller and the place record ----

test("scrollerOf picks <main> when it scrolls, else the window", () => {
    for (const overflow of ["auto", "scroll"]) {
        const w = fakeWindow({ mode: "main", scrollY: 70 });
        w.main.overflowY = overflow;
        const scroller = scrollerOf(w.win);
        assert.ok(scroller.inner, overflow);
        assert.strictEqual(scroller.top(), 70);
        scroller.scrollTo(90);
        assert.strictEqual(w.main.scrollTop, 90);
        assert.deepStrictEqual(w.windowScrolls, []);
    }
    // An auth page: its <main class="auth-card"> doesn't scroll, the document does.
    const auth = fakeWindow({ scrollY: 40 });
    assert.ok(!scrollerOf(auth.win).inner);
    assert.strictEqual(scrollerOf(auth.win).top(), 40);
    scrollerOf(auth.win).scrollTo(12);
    assert.deepStrictEqual(auth.windowScrolls, [[0, 12]]);
    // No <main> at all.
    const bare = fakeWindow({ mode: "main" });
    bare.win.hasMain = false;
    assert.ok(!scrollerOf(bare.win).inner);
});

// The script runs from the head, before <main> exists: the scroller is looked up at each use.
test("installed before <main> exists, the restore still scrolls <main>", () => {
    const w = fakeWindow({ mode: "main", readyState: "loading", stored: { path: "/devices/1", y: 640, t: 0 } });
    w.win.hasMain = false;
    install(w.win, () => 1);
    w.win.hasMain = true;
    w.listeners.DOMContentLoaded();
    assert.deepStrictEqual(w.scrolls, [[0, 640]]);
    assert.strictEqual(w.main.scrollTop, 640);
    assert.deepStrictEqual(w.windowScrolls, []);
});

both("leaving the page stores its place under path and search", (mode) => {
    const w = fakeWindow({ mode, search: "?device=3", scrollY: 1200 });
    install(w.win, () => 77);
    w.winListeners.pagehide();
    assert.deepStrictEqual(JSON.parse(w.store.get(PLACE_KEY)), { "/devices/1?device=3": { y: 1200, t: 77 } });
    // Hidden (the app switched away, maybe never to come back): stored too.
    const hidden = fakeWindow({ mode, scrollY: 50 });
    install(hidden.win, () => 9);
    hidden.win.document.visibilityState = "hidden";
    hidden.listeners.visibilitychange();
    assert.deepStrictEqual(JSON.parse(hidden.store.get(PLACE_KEY)), { "/devices/1": { y: 50, t: 9 } });
});

test("the place map keeps the newest 50", () => {
    const places = {};
    for (let i = 1; i <= MAX_PLACES; i++) places["/p" + i] = { y: i, t: i };
    const w = fakeWindow({ mode: "main", path: "/new", scrollY: 5, places });
    install(w.win, () => 1000);
    w.winListeners.pagehide();
    const kept = JSON.parse(w.store.get(PLACE_KEY));
    assert.strictEqual(Object.keys(kept).length, MAX_PLACES);
    assert.ok(!("/p1" in kept), "the oldest dropped");
    assert.deepStrictEqual(kept["/new"], { y: 5, t: 1000 });
    assert.deepStrictEqual(kept["/p2"], { y: 2, t: 2 });
    // The same page again replaces its entry, nothing else goes.
    const again = fakeWindow({ mode: "main", path: "/p2", scrollY: 9, places: kept });
    install(again.win, () => 2000);
    again.winListeners.pagehide();
    const after = JSON.parse(again.store.get(PLACE_KEY));
    assert.strictEqual(Object.keys(after).length, MAX_PLACES);
    assert.deepStrictEqual(after["/p2"], { y: 9, t: 2000 });
    // A map that doesn't read is started again.
    const bad = fakeWindow({ mode: "main", path: "/x", scrollY: 3 });
    bad.store.set(PLACE_KEY, "{oops");
    install(bad.win, () => 1);
    bad.winListeners.pagehide();
    assert.deepStrictEqual(JSON.parse(bad.store.get(PLACE_KEY)), { "/x": { y: 3, t: 1 } });
});

// After any save the address carries its group's #fragment; Back must land where the parent was.
both("Back restores the place record, also over a #fragment", (mode) => {
    const places = { "/devices/1": { y: 1234, t: 1 }, "/devices/2": { y: 9, t: 2 } };
    const w = fakeWindow({ mode, hash: "#music-settings", places, navType: "back_forward", elements: { "music-settings": 300 } });
    install(w.win, () => 100);
    assert.deepStrictEqual(w.scrolls, [[0, 1234]]);
    w.winListeners.load();
    assert.deepStrictEqual(w.scrolls, [[0, 1234], [0, 1234]]);
    assert.strictEqual(w.win.history.scrollRestoration, "auto");
    // Another search is another place.
    const other = fakeWindow({ mode, search: "?span=24", places, navType: "back_forward" });
    install(other.win, () => 100);
    assert.deepStrictEqual(other.scrolls, []);
});

both("a reload restores the place record; a link (navigate) doesn't", (mode) => {
    const places = { "/devices/1": { y: 640, t: 1 } };
    const reload = fakeWindow({ mode, places, navType: "reload" });
    install(reload.win, () => 100);
    assert.deepStrictEqual(reload.scrolls, [[0, 640]]);
    const link = fakeWindow({ mode, hash: "#g", places, navType: "navigate", elements: { g: 400 } });
    install(link.win, () => 100);
    assert.deepStrictEqual(link.scrolls, [], "the fragment (or the top) is the browser's");
    assert.strictEqual(link.winListeners.load, undefined, "nothing to put back later either");
});

// iOS 14 has no PerformanceNavigationTiming.
both("performance.navigation.type is the fallback: 1 reload, 2 back/forward, 0 neither", (mode) => {
    const places = { "/devices/1": { y: 640, t: 1 } };
    for (const [type, restored] of [[1, true], [2, true], [0, false]]) {
        const w = fakeWindow({ mode, places, legacyNav: type });
        install(w.win, () => 100);
        assert.deepStrictEqual(w.scrolls, restored ? [[0, 640]] : [], String(type));
    }
});

test("navigationType reads the entry, then the legacy number, else navigate", () => {
    assert.strictEqual(navigationType(fakeWindow({ navType: "back_forward" }).win), "back_forward");
    assert.strictEqual(navigationType(fakeWindow({ legacyNav: 2 }).win), "back_forward");
    assert.strictEqual(navigationType(fakeWindow({ legacyNav: 1 }).win), "reload");
    assert.strictEqual(navigationType(fakeWindow().win), "navigate");
});

both("the form record wins over the place record", (mode) => {
    const w = fakeWindow({
        mode, stored: { path: "/devices/1", y: 840, t: 0 }, places: { "/devices/1": { y: 5, t: 1 } }, navType: "reload",
    });
    install(w.win, () => 100);
    assert.deepStrictEqual(w.scrolls, [[0, 840]]);
    // A form record that restores nothing (another group's fragment) leaves it to the place.
    const elsewhere = fakeWindow({
        mode, hash: "#other", stored: { path: "/devices/1", y: 840, t: 0, tops: { mine: 10 } },
        places: { "/devices/1": { y: 5, t: 1 } }, navType: "reload", elements: { other: 200 },
    });
    install(elsewhere.win, () => 100);
    assert.deepStrictEqual(elsewhere.scrolls, [[0, 5]]);
});

// The back-forward cache brings the DOM back as it was: nothing to restore (the script doesn't
// run again; the `pageshow` it gets restores nothing).
both("a page from the back-forward cache restores nothing", (mode) => {
    const w = fakeWindow({ mode, places: { "/devices/1": { y: 640, t: 1 } } });
    install(w.win, () => 100);
    w.winListeners.pageshow({ persisted: true });
    assert.deepStrictEqual(w.scrolls, []);
});

// With overflow: hidden on the body, iOS can leave the window panned by the keyboard.
test("in the shell a window left offset is put back at 0; not on an auth page", () => {
    const w = fakeWindow({ mode: "main" });
    let viewportResize = null;
    w.win.visualViewport = { addEventListener: (type, fn) => { if (type === "resize") viewportResize = fn; } };
    install(w.win, () => 1);
    w.listeners.focusout();
    assert.deepStrictEqual(w.windowScrolls, [], "not offset: nothing");
    w.win.scrollY = 12;
    w.listeners.focusout();
    assert.deepStrictEqual(w.windowScrolls, [[0, 0]]);
    w.win.scrollY = 30;
    viewportResize();
    w.win.scrollY = 8;
    w.winListeners.pageshow({ persisted: false });
    assert.deepStrictEqual(w.windowScrolls, [[0, 0], [0, 0], [0, 0]]);
    assert.strictEqual(w.main.scrollTop, 300, "<main> untouched");
    // The login page scrolls the window itself.
    const auth = fakeWindow({ scrollY: 12 });
    install(auth.win, () => 1);
    auth.listeners.focusout();
    auth.winListeners.pageshow({ persisted: false });
    assert.deepStrictEqual(auth.windowScrolls, []);
});

// The band's back link (design 84; the user's iPhone, 2026-11-11): Apps' "On phones" row opens a phone's
// Apps page, whose chevron goes to `/apps#phone-3` - and landed with that row at the screen's top.
const ORIGIN = "https://vibb.example";

/** A page of this origin opened from `referrer` (a path, or a full address). */
function opened(options, referrer) {
    const w = fakeWindow(options);
    w.win.location.origin = ORIGIN;
    w.win.document.referrer = referrer === "" || referrer.startsWith("http") ? referrer : ORIGIN + referrer;
    return w;
}

/** The band's chevron to `pathname` + `search`, and the tap's target: the drawn chevron inside it. */
function chevron(pathname, search = "", className = "nav-back") {
    const link = { tagName: "A", className, pathname, search, parentNode: null };
    return { link, tap: { target: { tagName: "path", className: {}, parentNode: { tagName: "svg", className: {}, parentNode: link } } } };
}

const fromOf = (w) => JSON.parse(w.store.get(FROM_KEY) || "{}");

test("a page opened by a link remembers the page it came from; its saves, a return to it, Back and reload keep that", () => {
    const sub = opened({ path: "/devices/3/apps", search: "?from=list", navType: "navigate" }, "/apps");
    install(sub.win, () => 10);
    assert.deepStrictEqual(fromOf(sub), { "/devices/3/apps": { key: "/apps", t: 10 } });
    const came = { "/devices/3/apps": { key: "/apps", t: 10 } };
    // A save's answer comes from the page itself, with or without its query.
    for (const referrer of ["/devices/3/apps?from=list", "/devices/3/apps"]) {
        const saved = opened({ path: "/devices/3/apps", search: "?from=list", navType: "navigate" }, referrer);
        saved.store.set(FROM_KEY, JSON.stringify(came));
        install(saved.win, () => 20);
        assert.deepStrictEqual(fromOf(saved), came, referrer);
    }
    // Back, Forward and a reload keep it.
    for (const navType of ["back_forward", "reload"]) {
        const w = opened({ path: "/devices/3/apps", navType }, "/x");
        w.store.set(FROM_KEY, JSON.stringify(came));
        install(w.win, () => 20);
        assert.deepStrictEqual(fromOf(w), came, navType);
    }
    // A link load with no referrer of this origin (a notification, a typed address) forgets it (code QA L2).
    for (const referrer of ["", "https://elsewhere.example/apps", "not an address"]) {
        const w = opened({ path: "/devices/3/apps", navType: "navigate" }, referrer);
        w.store.set(FROM_KEY, JSON.stringify(Object.assign({ "/apps": { key: "/devices", t: 1 } }, came)));
        install(w.win, () => 20);
        assert.deepStrictEqual(fromOf(w), { "/apps": { key: "/devices", t: 1 } }, referrer);
    }
    // Back on it by its own child's back link: it still came from Apps.
    const returned = opened({
        path: "/devices/3/apps", navType: "navigate",
        stored: { path: "/devices/3/apps", href: "/devices/3/apps", y: 0, t: 15, back: true },
    }, "/devices/3/apps/foo");
    returned.store.set(FROM_KEY, JSON.stringify(came));
    install(returned.win, () => 20);
    assert.deepStrictEqual(fromOf(returned), came);
});

// Code QA M2: a save's answer on another path, and the one-shot words a save adds to an address.
test("a save's answer on another path takes the form page's entry, never one naming itself", () => {
    const froms = {
        "/schedules/rules/new": { key: "/schedules", t: 1 },
        "/devices/3/calendar/rules/7": { key: "/devices/3/calendar", t: 2 },
        "/devices/3/calendar": { key: "/devices/3", t: 3 },
    };
    // An Add: `/new` posts and the answer is the rule's own page - it came from Time Rules too.
    const added = opened({
        path: "/schedules/rules/5", search: "?saved=1", navType: "navigate",
        stored: { path: "/schedules/rules/new", y: 0, t: 15, tops: {} },
    }, "/schedules/rules/new");
    added.store.set(FROM_KEY, JSON.stringify(froms));
    install(added.win, () => 20);
    assert.deepStrictEqual(fromOf(added)["/schedules/rules/5"], { key: "/schedules", t: 20 });
    // A child's Delete back to its parent: the parent keeps where it came from.
    const deleted = opened({
        path: "/devices/3/calendar", search: "?notice=deleted", hash: "#rules", navType: "navigate",
        stored: { path: "/devices/3/calendar/rules/7", y: 0, t: 15, tops: {} },
    }, "/devices/3/calendar/rules/7");
    deleted.store.set(FROM_KEY, JSON.stringify(froms));
    install(deleted.win, () => 20);
    assert.deepStrictEqual(fromOf(deleted), froms);
    // An old form record is no answer: the referrer counts.
    const old = opened({
        path: "/devices/3/calendar", navType: "navigate", stored: { path: "/devices/3/calendar/rules/7", y: 0, t: 0, tops: {} },
    }, "/devices/3/calendar/rules/7");
    install(old.win, () => MAX_AGE_MS);
    assert.deepStrictEqual(fromOf(old)["/devices/3/calendar"], { key: "/devices/3/calendar/rules/7", t: MAX_AGE_MS });
});

test("pages are compared without a save's one-shot words (notice, app, saved)", () => {
    // The parent was opened with a notice, left with it, and the child's back link names it bare.
    const child = opened({ path: "/devices/3/calendar/link/abc", navType: "navigate" }, "/devices/3/calendar?notice=added&app=x&saved=1");
    child.store.set(PLACE_KEY, JSON.stringify({ "/devices/3/calendar": { y: 260, t: 5 } }));
    install(child.win, () => 10);
    assert.deepStrictEqual(fromOf(child)["/devices/3/calendar/link/abc"], { key: "/devices/3/calendar", t: 10 });
    child.listeners.click(chevron("/devices/3/calendar", "?saved=1").tap);
    assert.deepStrictEqual(JSON.parse(child.store.get(KEY)),
        { path: "/devices/3/calendar", href: "/devices/3/calendar", y: 260, t: 10, back: true });
    // The parent's own place goes under the bare key, whatever words its address carried.
    const parent = opened({ mode: "main", path: "/devices/3/calendar", search: "?notice=added&span=24", navType: "navigate", scrollY: 70 }, "");
    install(parent.win, () => 30);
    parent.winListeners.pagehide();
    assert.deepStrictEqual(JSON.parse(parent.store.get(PLACE_KEY)), { "/devices/3/calendar?span=24": { y: 70, t: 30 } });
});

both("the back link to the page it came from carries that page's place, which wins over its #fragment", (mode) => {
    const sub = opened({ mode, path: "/devices/3/apps", search: "?from=list", navType: "navigate" }, "/apps");
    sub.store.set(PLACE_KEY, JSON.stringify({ "/apps": { y: 0, t: 5 }, "/music": { y: 900, t: 6 } }));
    install(sub.win, () => 10);
    const { tap } = chevron("/apps");
    sub.listeners.click(tap);
    const record = { path: "/apps", href: "/apps", y: 0, t: 10, back: true };
    assert.deepStrictEqual(JSON.parse(sub.store.get(KEY)), record);
    assert.deepStrictEqual(JSON.parse(sub.win.name.slice(NAME_PREFIX.length)), record, "the second carrier");
    // Apps opens where it was left, the top, not at the row (900 px down) - and still came from wherever it did.
    const apps = opened({ mode, path: "/apps", hash: "#phone-3", stored: record, navType: "navigate", elements: { "phone-3": 900 } },
        "/devices/3/apps?from=list");
    apps.store.set(FROM_KEY, JSON.stringify({ "/apps": { key: "/devices", t: 1 } }));
    install(apps.win, () => 12);
    assert.deepStrictEqual(apps.scrolls, [[0, 0]]);
    apps.winListeners.load();
    assert.deepStrictEqual(apps.scrolls, [[0, 0], [0, 0]]);
    assert.deepStrictEqual(fromOf(apps), { "/apps": { key: "/devices", t: 1 } });
    // A query on the parent is part of its place.
    const zone = opened({ mode, path: "/devices/locate/zones/new", navType: "navigate" }, "/devices/locate?device=3");
    zone.store.set(PLACE_KEY, JSON.stringify({ "/devices/locate?device=3": { y: 420, t: 5 } }));
    install(zone.win, () => 10);
    zone.listeners.click(chevron("/devices/locate", "?device=3").tap);
    assert.strictEqual(JSON.parse(zone.store.get(KEY)).y, 420);
});

test("no place goes with a back link to another page, a parent never left, another link or a modified tap", () => {
    const setUp = (places) => {
        const w = opened({ mode: "main", path: "/devices/3/apps", navType: "navigate" }, "/apps");
        w.store.set(PLACE_KEY, JSON.stringify(places));
        install(w.win, () => 10);
        return w;
    };
    const places = { "/apps": { y: 300, t: 5 }, "/devices/3": { y: 200, t: 6 } };
    const cases = [
        ["a back link to another page than it came from", places, chevron("/devices/3").tap],
        ["the parent's query differs", places, chevron("/apps", "?x=1").tap],
        ["a parent never left", { "/devices/3": { y: 200, t: 6 } }, chevron("/apps").tap],
        ["another link", places, chevron("/apps", "", "row").tap],
        ["a link inside nothing", places, { target: { tagName: "SPAN", className: "nav-back", parentNode: null } }],
        ["a new tab", places, Object.assign({ metaKey: true }, chevron("/apps").tap)],
        ["the middle button", places, Object.assign({ button: 1 }, chevron("/apps").tap)],
        ["a tap another script handled", places, Object.assign({ defaultPrevented: true }, chevron("/apps").tap)],
    ];
    for (const [what, p, tap] of cases) {
        const w = setUp(p);
        w.listeners.click(tap);
        assert.ok(!w.store.has(KEY), what);
        assert.strictEqual(w.win.name, "", what);
    }
    // Opened directly (no referrer): the plain link.
    const direct = opened({ mode: "main", path: "/devices/3/apps", navType: "navigate" }, "");
    direct.store.set(PLACE_KEY, JSON.stringify(places));
    install(direct.win, () => 10);
    direct.listeners.click(chevron("/apps").tap);
    assert.ok(!direct.store.has(KEY));
});

test("restoreTarget: a back record is the place over any #fragment, for 20 s", () => {
    const back = { path: "/apps", y: 40, t: 0, back: true };
    assert.strictEqual(restoreTarget(back, "/apps", "#phone-3", 5, () => null), 40);
    assert.strictEqual(restoreTarget(back, "/apps", "", 5), 40);
    assert.strictEqual(restoreTarget(back, "/apps", "#phone-3", MAX_AGE_MS, () => null), null);
    assert.strictEqual(restoreTarget(back, "/music", "#phone-3", 5, () => null), null);
});

// A save leaves the history as it was (the user, 2026-11-14: Check now, then Back, showed the same app page again).
const { stayPost, stayLanding, STAY_HEADER, STAY_LOCATION } = require("../static/scroll-restore.js");

const HERE = "https://vibb.example/apps/tracked/2";

/** A fake form: `attrs` its attributes, `fields` its controls as [name, value, type]. */
function fakeForm(attrs = {}, fields = []) {
    const elements = fields.map(([name, value, type = "hidden"]) => ({ name, value, type }));
    return {
        attrs,
        elements,
        hasAttribute: (n) => n in attrs,
        getAttribute: (n) => (n in attrs ? attrs[n] : null),
        setAttribute: (n, v) => { attrs[n] = String(v); },
        removeAttribute: (n) => { delete attrs[n]; },
    };
}

/** A fake submit button. */
function fakeButton(attrs = {}, name = "", value = "") {
    return {
        name,
        value,
        disabled: false,
        attrs,
        hasAttribute: (n) => n in attrs,
        getAttribute: (n) => (n in attrs ? attrs[n] : null),
        setAttribute: (n, v) => { attrs[n] = String(v); },
        removeAttribute: (n) => { delete attrs[n]; },
    };
}

const checkNow = () => fakeForm({ method: "post", action: "/apps/tracked/2/check#check" }, [["v", "1"]]);

test("stayPost: a POST back to its page's #fragment is posted by the script, anything else by the browser", () => {
    assert.deepStrictEqual(stayPost(checkNow(), null, HERE), {
        url: "https://vibb.example/apps/tracked/2/check#check",
        multipart: false,
    });
    assert.strictEqual(stayPost(checkNow(), null, HERE + "?from=apps#check").url, "https://vibb.example/apps/tracked/2/check#check");
    const leaves = [
        ["a GET", fakeForm({ method: "get", action: "/notifications#inbox" })],
        ["no method", fakeForm({ action: "/x#y" })],
        ["no fragment (a form that leaves its page)", fakeForm({ method: "post", action: "/apps/tracked/2/delete" })],
        ["an empty fragment", fakeForm({ method: "post", action: "/x#" })],
        ["another origin", fakeForm({ method: "post", action: "https://other.example/x#y" })],
        ["a target", fakeForm({ method: "post", action: "/x#y", target: "_blank" })],
        ["answered with a page", fakeForm({ method: "post", action: "/devices/3/lock#show-pin", "data-native-post": "" })],
        ["a password", fakeForm({ method: "post", action: "/x#y" }, [["password", "", "password"]])],
        ["a file", fakeForm({ method: "post", action: "/x#y", enctype: "multipart/form-data" }, [["cover", "", "file"]])],
        ["a passkey's assertion", fakeForm({ method: "post", action: "/x#y" }, [["assertion", ""]])],
        ["a new passkey", fakeForm({ method: "post", action: "/x#y" }, [["credential", ""]])],
    ];
    for (const [what, form] of leaves) assert.strictEqual(stayPost(form, null, HERE), null, what);
    assert.strictEqual(stayPost(checkNow(), fakeButton({ formtarget: "_blank" }), HERE), null, "the button's target");
    assert.strictEqual(stayPost(fakeForm({ action: "/x#y" }), fakeButton({ formmethod: "post" }), HERE).url, "https://vibb.example/x#y");
    // The button's formaction wins: a fragment of its own (Device › Calendar's Remove), or none (it leaves the page).
    const calendar = fakeForm({ method: "post", action: "/devices/3/calendar/x#sources" });
    assert.strictEqual(
        stayPost(calendar, fakeButton({ formaction: "/devices/3/calendar/calendars/a/remove#calendars" }), HERE).url,
        "https://vibb.example/devices/3/calendar/calendars/a/remove#calendars"
    );
    assert.strictEqual(stayPost(calendar, fakeButton({ formaction: "/devices/3/calendar/links/a/remove" }), HERE), null);
    assert.strictEqual(stayPost(fakeForm({ method: "POST", action: "/x#y", enctype: "multipart/form-data" }), null, HERE).multipart, true);
});

test("stayLanding: the same page is reloaded in its own entry, another page opened as a link", () => {
    assert.deepStrictEqual(stayLanding("/apps/tracked/2#check", HERE), { reload: true, url: HERE + "#check" });
    assert.deepStrictEqual(stayLanding("/apps/tracked/2?notice=x#check", HERE + "?from=apps#top"), {
        reload: true,
        url: HERE + "?notice=x#check",
    });
    assert.deepStrictEqual(stayLanding("/schedules/rules/5#rule", "https://vibb.example/schedules/rules/new"), {
        reload: false,
        url: "https://vibb.example/schedules/rules/5#rule",
    });
    // Only an http(s) address of this origin (code QA L3).
    for (const to of ["https://other.example/apps/tracked/2", "javascript:alert(1)", "data:text/html,x", "http://vibb.example/apps"]) {
        assert.strictEqual(stayLanding(to, HERE), null, to);
    }
});

/** A window with fetch: `answer(url, init)` gives the fetch's answer (a promise's value, or throws to reject). */
function stayWindow(answer) {
    const w = fakeWindow({ mode: "main", path: "/apps/tracked/2" });
    const { win } = w;
    w.fetches = [];
    w.went = [];
    win.location.href = HERE;
    win.location.assign = (url) => w.went.push(["assign", url]);
    win.location.replace = (url) => w.went.push(["replace", url]);
    win.location.reload = () => w.went.push(["reload", win.location.href]);
    win.history.state = { s: 1 };
    win.history.replaceState = (state, title, url) => {
        w.went.push(["replaceState", url, state]);
        win.location.href = url;
    };
    win.URLSearchParams = URLSearchParams;
    win.FormData = function (form) {
        this.pairs = form.elements.filter((f) => f.type !== "submit").map((f) => [f.name, f.value]);
    };
    win.FormData.prototype.append = function (k, v) { this.pairs.push([k, v]); };
    win.FormData.prototype.forEach = function (fn) { this.pairs.forEach(([k, v]) => fn(v, k)); };
    win.fetch = (url, init) => {
        w.fetches.push([url, init]);
        return new Promise((resolve) => resolve(answer(url, init)));
    };
    w.requested = [];
    w.form = (attrs, fields) => {
        const form = fakeForm(attrs, fields);
        Object.setPrototypeOf(form, win.HTMLFormElement.prototype);
        form.requestSubmit = (submitter) => {
            w.requested.push(submitter);
            // The browser fires the submit again; the script lets this one pass.
            const again = { target: form, submitter: submitter || null, defaultPrevented: false, preventDefault() { this.defaultPrevented = true; } };
            w.winListeners.submit(again);
            assert.strictEqual(again.defaultPrevented, false, "the browser's own POST passes");
        };
        return form;
    };
    w.submit = (form, submitter = null, extra = {}) => {
        const e = Object.assign({ target: form, submitter, defaultPrevented: false, preventDefault() { this.defaultPrevented = true; } }, extra);
        w.winListeners.submit(e);
        return e;
    };
    install(win, () => 1000);
    return w;
}

const settle = () => new Promise((r) => setImmediate(r));
const stayAnswer = (to) => ({ status: 204, type: "basic", headers: { get: (n) => (n === STAY_LOCATION ? to : null) } });
const page = (status) => ({ status, type: "basic", headers: { get: () => null } });

test("a save back to its page: posted by the script, its entry replaced with the answer's address and reloaded", async () => {
    assert.strictEqual(STAY_HEADER, "X-Handy-Stay");
    assert.strictEqual(STAY_LOCATION, "X-Handy-Location");
    const w = stayWindow(() => stayAnswer("/apps/tracked/2#check"));
    const form = w.form({ method: "post", action: "/apps/tracked/2/check#check" }, [["v", "1"]]);
    const e = w.submit(form, fakeButton({}, "go", "now"));
    assert.strictEqual(e.defaultPrevented, true, "not the browser's POST");
    assert.strictEqual(w.fetches.length, 1);
    const [url, init] = w.fetches[0];
    assert.strictEqual(url, "https://vibb.example/apps/tracked/2/check#check");
    assert.strictEqual(init.method, "POST");
    assert.strictEqual(init.redirect, "manual");
    assert.strictEqual(init.credentials, "same-origin");
    assert.strictEqual(init.headers[STAY_HEADER], "1");
    assert.ok(init.body instanceof URLSearchParams, "urlencoded, as the browser sends the form");
    assert.strictEqual(init.body.toString(), "v=1&go=now", "the button's name and value too");
    await settle();
    assert.deepStrictEqual(w.went, [
        ["replaceState", HERE + "#check", { s: 1 }],
        ["reload", HERE + "#check"],
    ]);
    assert.deepStrictEqual(w.nativeSubmits, []);
    assert.deepStrictEqual(w.requested, []);
});

test("an auto-saving switch (form.submit()) stays too; a save that lands on another page opens it as a link", async () => {
    const w = stayWindow(() => stayAnswer("/schedules/rules/5#rule"));
    const form = w.form({ method: "post", action: "/devices/1/x#x" }, [["on", "on"]]);
    form.submit();
    assert.strictEqual(w.fetches.length, 1);
    assert.ok(w.store.has(KEY), "the place goes with it as before");
    await settle();
    assert.deepStrictEqual(w.went, [["assign", "https://vibb.example/schedules/rules/5#rule"]]);
    assert.deepStrictEqual(w.nativeSubmits, []);
});

test("a refusal is sent again the browser's way, which shows it; no answer or a 5xx never posts again (code QA H1)", async () => {
    for (const status of [400, 403, 404, 409, 422, 423, 200]) {
        const w = stayWindow(() => page(status));
        const form = w.form({ method: "post", action: "/apps/tracked/2/check#check" });
        const button = fakeButton({}, "action", "x");
        assert.strictEqual(w.submit(form, button).defaultPrevented, true);
        await settle();
        assert.deepStrictEqual(w.requested, [button], status + ": requestSubmit with its button");
        assert.strictEqual(button.disabled, false, status + ": the button is live again for it");
        assert.deepStrictEqual(w.went, [], String(status));
        const hooked = w.form({ method: "post", action: "/x#x" });
        hooked.submit();
        await settle();
        assert.deepStrictEqual(w.nativeSubmits, [hooked], status + ": form.submit()'s own");
    }
    // The server may have acted: the page with a GET, nothing posted again. A redirect past the stay
    // answer (a sign-in portal) too.
    const lost = [
        () => page(500),
        () => page(502),
        () => page(504),
        () => page(413),
        () => { throw new Error("Load failed"); },
        () => ({ status: 0, type: "opaqueredirect", headers: { get: () => null } }),
        () => stayAnswer("javascript:alert(1)"),
        () => stayAnswer("https://other.example/apps/tracked/2#check"),
    ];
    for (const answer of lost) {
        const w = stayWindow(answer);
        w.submit(w.form({ method: "post", action: "/apps/tracked/2/check#check" }), fakeButton({}, "go", "1"));
        await settle();
        assert.deepStrictEqual(w.requested, []);
        assert.deepStrictEqual(w.nativeSubmits, []);
        assert.deepStrictEqual(w.went, [["replaceState", HERE, { s: 1 }], ["reload", HERE]]);
    }
});

test("a requestSubmit that throws (its button left the form): the page with a GET, nothing escapes", async () => {
    const w = stayWindow(() => page(400));
    const form = w.form({ method: "post", action: "/apps/tracked/2/check#check" });
    form.requestSubmit = () => { throw new TypeError("not a submit button of this form"); };
    w.submit(form, fakeButton({}, "go", "1"));
    await settle();
    assert.deepStrictEqual(w.went, [["replaceState", HERE, { s: 1 }], ["reload", HERE]]);
});

test("while a save is out its form is busy: a second tap does nothing, the button is disabled (code QA M1)", async () => {
    let answer;
    const w = stayWindow(() => new Promise((r) => { answer = r; }));
    const form = w.form({ method: "post", action: "/devices/1/lifts#quick" });
    const button = fakeButton({}, "minutes", "30");
    assert.strictEqual(w.submit(form, button).defaultPrevented, true);
    assert.strictEqual(form.attrs["aria-busy"], "true");
    assert.strictEqual(button.disabled, true);
    assert.strictEqual(button.attrs["aria-busy"], "true");
    const again = w.submit(form, button);
    assert.strictEqual(again.defaultPrevented, true, "not the browser's POST either");
    assert.strictEqual(w.fetches.length, 1, "one lift, not two");
    answer(stayAnswer("/apps/tracked/2#quick"));
    await settle();
    assert.deepStrictEqual(w.went.map((g) => g[0]), ["replaceState", "reload"]);
});

test("several saves out: the page lands once, after the last, at the last's address; a form's saves go in order (code QA L1)", async () => {
    const answers = [];
    const w = stayWindow(() => new Promise((r) => answers.push(r)));
    const one = w.form({ method: "post", action: "/devices/1/apps/toggle#a" }, [["on", "on"]]);
    const two = w.form({ method: "post", action: "/devices/1/apps/toggle#b" }, [["on", "on"]]);
    one.submit();
    two.submit();
    one.elements[0].value = "off";
    one.submit();
    assert.strictEqual(w.fetches.length, 2, "one's second save waits for its first");
    answers[0](stayAnswer("/apps/tracked/2#a"));
    await settle();
    assert.strictEqual(w.fetches.length, 3, "then it goes");
    assert.strictEqual(w.fetches[2][1].body.toString(), "on=off", "with the form as it is now");
    assert.deepStrictEqual(w.went, [], "nothing lands while saves are out");
    answers[1](stayAnswer("/apps/tracked/2#b"));
    await settle();
    assert.deepStrictEqual(w.went, []);
    answers[2](stayAnswer("/apps/tracked/2#a2"));
    await settle();
    assert.deepStrictEqual(w.went, [
        ["replaceState", HERE + "#a2", { s: 1 }],
        ["reload", HERE + "#a2"],
    ]);
    // A refusal among them still shows, once the rest are done.
    const later = [];
    const m = stayWindow((url) => new Promise((r) => later.push([url, r])));
    const ok = m.form({ method: "post", action: "/devices/1/x#x" });
    const bad = m.form({ method: "post", action: "/devices/1/y#y" });
    ok.submit();
    bad.submit();
    later[1][1](page(400));
    await settle();
    assert.deepStrictEqual(m.nativeSubmits, []);
    later[0][1](stayAnswer("/apps/tracked/2#x"));
    await settle();
    assert.deepStrictEqual(m.nativeSubmits, [bad]);
    assert.deepStrictEqual(m.went, []);
});

test("a stay save's reload: a #fragment outside the form's groups is the anchor, not the old place (code QA L2)", () => {
    // Music import's Confirm (#import-preview) lands on #import, a sibling the record doesn't hold.
    const record = { path: "/music/import", y: 900, t: 990, tops: { "import-preview": 120 }, stay: true };
    const w = fakeWindow({
        mode: "main",
        path: "/music/import",
        hash: "#import",
        stored: record,
        places: { "/music/import": { y: 900, t: 980 } },
        navType: "reload",
        elements: { import: 40 },
    });
    const anchored = [];
    const byId = w.win.document.getElementById;
    w.win.document.getElementById = (id) => {
        const el = byId(id);
        if (el) el.scrollIntoView = (top) => anchored.push([id, top]);
        return el;
    };
    install(w.win, () => 1000);
    assert.deepStrictEqual(w.scrolls, [], "not the place record's 900");
    assert.deepStrictEqual(anchored, [["import", true]]);
    // Without the stay mark (a reload by hand) the place record as before.
    const plain = fakeWindow({
        mode: "main",
        path: "/music/import",
        hash: "#import",
        stored: Object.assign({}, record, { stay: undefined }),
        places: { "/music/import": { y: 900, t: 980 } },
        navType: "reload",
        elements: { import: 40 },
    });
    install(plain.win, () => 1000);
    assert.deepStrictEqual(plain.scrolls, [[0, 900]]);
});

test("the reload carries the form record marked as a stay", async () => {
    const w = stayWindow(() => stayAnswer("/apps/tracked/2#check"));
    w.submit(w.form({ method: "post", action: "/apps/tracked/2/check#check" }), null);
    // The submit's capture listener records the place (the fake keeps one listener per type).
    w.listeners.submit({ target: { id: "check", parentNode: null, getBoundingClientRect: () => ({ top: 50 }) } });
    await settle();
    assert.strictEqual(JSON.parse(w.store.get(KEY)).stay, true);
});

test("the browser posts as before: a form that leaves its page, a submit taken over, no submitter, no fetch", () => {
    const w = stayWindow(() => assert.fail("no fetch"));
    const leaves = w.form({ method: "post", action: "/apps/tracked/2/delete" });
    assert.strictEqual(w.submit(leaves).defaultPrevented, false);
    leaves.submit();
    assert.deepStrictEqual(w.nativeSubmits, [leaves]);
    const stays = w.form({ method: "post", action: "/apps/tracked/2/check#check" });
    w.submit(stays, null, { defaultPrevented: true });
    assert.strictEqual(w.fetches.length, 0, "a submit another script took over");
    const old = { target: stays, defaultPrevented: false, preventDefault() { this.defaultPrevented = true; } };
    w.winListeners.submit(old);
    assert.strictEqual(old.defaultPrevented, false, "a browser without `submitter`");
    assert.strictEqual(w.fetches.length, 0);

    const bare = fakeWindow({ path: "/apps/tracked/2" });
    install(bare.win, () => 1000);
    const form = fakeForm({ method: "post", action: "/apps/tracked/2/check#check" });
    Object.setPrototypeOf(form, bare.win.HTMLFormElement.prototype);
    const e = { target: form, submitter: null, defaultPrevented: false, preventDefault() { this.defaultPrevented = true; } };
    bare.winListeners.submit(e);
    assert.strictEqual(e.defaultPrevented, false);
    form.submit();
    assert.deepStrictEqual(bare.nativeSubmits, [form]);
});

// Design 103 §2.2: a landing on this page goes to swap.js (handySwap.refresh), the landed forms busy until its morph.
function swapWindow(answer) {
    const w = stayWindow(answer);
    w.refreshes = [];
    w.taken = true;
    w.win.handySwap = {
        refresh: (url, opts) => {
            w.refreshes.push([url, opts]);
            return w.taken;
        },
    };
    return w;
}

test("a landing on this page is refreshed in place with its forms and buttons; a refresh that says no is the reload", async () => {
    const w = swapWindow(() => stayAnswer("/apps/tracked/2?notice=checked#check"));
    const form = w.form({ method: "post", action: "/apps/tracked/2/check#check" });
    const button = fakeButton({}, "go", "1");
    w.submit(form, button);
    await settle();
    assert.strictEqual(w.refreshes.length, 1);
    const [url, opts] = w.refreshes[0];
    assert.strictEqual(url, "https://vibb.example/apps/tracked/2?notice=checked#check");
    assert.deepStrictEqual(opts.forms, [form]);
    assert.deepStrictEqual(opts.byButton, [button]);
    assert.strictEqual(opts.lost, false);
    assert.deepStrictEqual(w.went, [], "no reload");
    assert.strictEqual(form.attrs["aria-busy"], "true", "still busy until the morph");
    assert.strictEqual(button.disabled, true);
    // The morph is done: swap.js releases the landed forms.
    w.win.handyStay.landed([form]);
    assert.strictEqual(form.attrs["aria-busy"], undefined);
    assert.strictEqual(button.disabled, false);
    // A refresh that says no: the reload as before, with its record.
    const no = swapWindow(() => stayAnswer("/apps/tracked/2#check"));
    no.taken = false;
    no.submit(no.form({ method: "post", action: "/apps/tracked/2/check#check" }), null);
    await settle();
    assert.deepStrictEqual(no.went.map((g) => g[0]), ["replaceState", "reload"]);
    // And its fallback is that same reload.
    const fb = swapWindow(() => stayAnswer("/apps/tracked/2#check"));
    fb.submit(fb.form({ method: "post", action: "/apps/tracked/2/check#check" }), null);
    await settle();
    fb.refreshes[0][1].fallback();
    assert.deepStrictEqual(fb.went.map((g) => g[0]), ["replaceState", "reload"]);
});

test("103-3: a flip in the gap is queued and sent after the morph; only the landed entries are released", async () => {
    const answers = [];
    const w = swapWindow(() => new Promise((r) => answers.push(r)));
    const sw = w.form({ method: "post", action: "/devices/1/hardening#restrictions" }, [["a", "on"]]);
    sw.submit();
    answers[0](stayAnswer("/apps/tracked/2#restrictions"));
    await settle();
    assert.strictEqual(w.refreshes.length, 1, "landed: the GET is out");
    // The gap: the same switch flipped again, and another form saved.
    sw.elements[0].value = "off";
    sw.submit();
    assert.strictEqual(w.fetches.length, 1, "queued, not sent");
    assert.strictEqual(w.win.handyStay.entry(sw).again, true);
    const other = w.form({ method: "post", action: "/devices/1/other#other" });
    const button = fakeButton({}, "x", "1");
    w.submit(other, button);
    assert.strictEqual(w.win.handyStay.inflight(), 1);
    assert.strictEqual(w.win.handyStay.isSubmitter(button), true);
    // The morph lands for the first form only.
    w.win.handyStay.landed([sw]);
    assert.strictEqual(w.fetches.length, 3, "the queued flip is sent now");
    assert.strictEqual(w.fetches[2][1].body.toString(), "a=off");
    assert.strictEqual(other.attrs["aria-busy"], "true", "the other save keeps its marks");
    assert.strictEqual(button.disabled, true);
    assert.strictEqual(sw.attrs["aria-busy"], "true", "busy again for its new save");
    assert.strictEqual(w.win.handyStay.inflight(), 2);
});

test("an answer not had refreshes in place with the red line; the 400 ms bar is a counter of its own", async () => {
    const w = swapWindow(() => page(502));
    const timers = [];
    w.win.setTimeout = (fn, ms) => { timers.push([fn, ms]); return timers.length; };
    w.win.clearTimeout = (id) => { timers[id - 1] = null; };
    const bars = [];
    w.win.handyReconnect = { busy: (n) => bars.push(n) };
    const form = w.form({ method: "post", action: "/apps/tracked/2/check#check" });
    w.submit(form, fakeButton({}, "go", "1"));
    assert.strictEqual(timers[0][1], 400);
    timers[0][0]();
    assert.deepStrictEqual(bars, [1], "slow: the bar");
    await settle();
    assert.strictEqual(w.refreshes[0][0], HERE);
    assert.strictEqual(w.refreshes[0][1].lost, true);
    assert.deepStrictEqual(w.went, [], "no reload, no POST again");
    w.win.handyStay.landed([form]);
    assert.deepStrictEqual(bars, [1, -1], "its share goes with its marks");
    // A fast save never shows it.
    const fast = swapWindow(() => stayAnswer("/apps/tracked/2#check"));
    const t2 = [];
    fast.win.setTimeout = (fn, ms) => { t2.push(fn); return t2.length; };
    fast.win.clearTimeout = (id) => { t2[id - 1] = null; };
    const b2 = [];
    fast.win.handyReconnect = { busy: (n) => b2.push(n) };
    const f2 = fast.form({ method: "post", action: "/apps/tracked/2/check#check" });
    f2.submit();
    await settle();
    fast.win.handyStay.landed([f2]);
    assert.strictEqual(t2[0], null, "the timer cleared");
    assert.deepStrictEqual(b2, []);
});
