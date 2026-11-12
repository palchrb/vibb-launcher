// Behaviour of static/map-full.js (design 85 §4.8): ⤢ opens the map at full screen, ✕ and Escape
// close it, the page behind locked, Leaflet told to re-measure, the focus on the button shown.
// Run: node --test jstest/map-full.test.js
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const { install } = require("../static/map-full.js");

function el(attrs = {}) {
    const classes = new Set();
    const e = {
        attrs: { ...attrs },
        listeners: {},
        classList: { add: (c) => classes.add(c), remove: (c) => classes.delete(c), contains: (c) => classes.has(c) },
        setAttribute(n, v) { e.attrs[n] = String(v); },
        removeAttribute(n) { delete e.attrs[n]; },
        hasAttribute(n) { return n in e.attrs; },
        getAttribute(n) { return n in e.attrs ? e.attrs[n] : null; },
        addEventListener(t, fn) { e.listeners[t] = fn; },
        focus() { page.focused = e; page.doc.activeElement = e; },
    };
    return e;
}
let page;

function fakePage() {
    const open = el({ "data-full-open": "", hidden: "" });
    const close = el({ "data-full-close": "", hidden: "" });
    const box = el({ "data-map-full": "" });
    const chip = el({ href: "/devices/locate" });
    const marker = el({ tabindex: "0" });
    box.stops = [chip, marker, open, close];
    box.popupClose = null;
    box.querySelector = (sel) => ({ "[data-full-open]": open, "[data-full-close]": close, ".leaflet-popup-close-button": box.popupClose })[sel] || null;
    box.querySelectorAll = () => box.stops;
    box.contains = (other) => box.stops.includes(other);
    for (const stop of box.stops) stop.closest = (sel) => (sel === "[hidden]" && stop.hasAttribute("hidden") ? stop : null);
    const body = el();
    const doc = el();
    doc.body = body;
    doc.querySelector = (sel) => (sel === "[data-map-full]" ? box : null);
    const events = [];
    const win = { document: doc, Event: function (type) { this.type = type; }, dispatchEvent: (e) => events.push(e.type) };
    doc.activeElement = null;
    page = { win, doc, box, body, open, close, chip, marker, events, focused: null };
    return page;
}

test("⤢ shows with JS; it opens the map at full screen and ✕ closes it", () => {
    const p = fakePage();
    install(p.win);
    assert.ok(!p.open.hasAttribute("hidden") && p.close.hasAttribute("hidden"));
    p.open.listeners.click();
    assert.ok(p.box.classList.contains("map-full") && p.body.classList.contains("map-fullscreen"));
    assert.ok(p.open.hasAttribute("hidden") && !p.close.hasAttribute("hidden"));
    assert.deepStrictEqual(p.events, ["resize"]);
    assert.strictEqual(p.focused, p.close);
    p.close.listeners.click();
    assert.ok(!p.box.classList.contains("map-full") && !p.body.classList.contains("map-fullscreen"));
    assert.deepStrictEqual(p.events, ["resize", "resize"]);
    assert.strictEqual(p.focused, p.open);
});

test("Escape closes it, and only while it is open", () => {
    const p = fakePage();
    install(p.win);
    let prevented = 0;
    const escape = { key: "Escape", preventDefault: () => { prevented += 1; } };
    p.doc.listeners.keydown(escape);
    assert.strictEqual(prevented, 0);
    p.open.listeners.click();
    p.doc.listeners.keydown(escape);
    assert.strictEqual(prevented, 1);
    assert.ok(!p.box.classList.contains("map-full"));
});

test("Escape closes an open bubble first and stays; a key already handled is left alone", () => {
    const p = fakePage();
    install(p.win);
    p.open.listeners.click();
    let closed = 0;
    p.box.popupClose = { click: () => { closed += 1; p.box.popupClose = null; } };
    const escape = () => ({ key: "Escape", preventDefault() {} });
    p.doc.listeners.keydown(escape());
    assert.strictEqual(closed, 1);
    assert.ok(p.box.classList.contains("map-full"));
    p.doc.listeners.keydown({ ...escape(), defaultPrevented: true });
    assert.ok(p.box.classList.contains("map-full"));
    p.doc.listeners.keydown(escape());
    assert.ok(!p.box.classList.contains("map-full"));
});

test("Tab goes round inside the box while it is open, past the hidden ⤢", () => {
    const p = fakePage();
    install(p.win);
    p.open.listeners.click();
    let prevented = 0;
    const tab = (shiftKey) => ({ key: "Tab", shiftKey, preventDefault: () => { prevented += 1; } });
    // From ✕ (the last stop) Tab goes to the first; Shift+Tab from the first to ✕.
    assert.strictEqual(p.doc.activeElement, p.close);
    p.doc.listeners.keydown(tab(false));
    assert.strictEqual(p.doc.activeElement, p.chip);
    p.doc.listeners.keydown(tab(true));
    assert.strictEqual(p.doc.activeElement, p.close);
    // In the middle the browser moves it.
    p.marker.focus();
    p.doc.listeners.keydown(tab(false));
    assert.strictEqual(prevented, 2);
    // Closed, Tab is the page's again.
    p.close.listeners.click();
    p.doc.listeners.keydown(tab(false));
    assert.strictEqual(prevented, 2);
});
