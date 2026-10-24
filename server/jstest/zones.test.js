// Behaviour of static/zones.js (design 64 §3.5): a zone's page - the tap that places the centre,
// the radius slider and its clamp, Use Phone's Location. Run: node --test jstest/zones.test.js
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const { install, clampRadius, fixed6, wrapLng, readCoord, place } = require("../static/zones.js");

/** A fake Leaflet: the map's handlers, the circle and the marker, what was drawn. */
function fakeLeaflet() {
    const calls = [];
    const handlers = {};
    const map = {
        on: (type, fn) => { handlers[type] = fn; return map; },
        setView: (at, zoom) => { calls.push(["setView", at, zoom]); return map; },
        fitBounds: () => { calls.push(["fitBounds"]); return map; },
        removeLayer: (layer) => { calls.push(["remove", layer.kind]); return map; },
    };
    const L = {
        calls,
        handlers,
        drawn: {},
        map: (el, options) => { calls.push(["map", options]); return map; },
        tileLayer: () => ({ addTo() { return this; } }),
        Icon: { Default: {} },
        circle: (at, options) => {
            const c = { kind: "circle", at, radius: options.radius };
            c.addTo = () => c;
            c.setLatLng = (p) => { c.at = p; return c; };
            c.setRadius = (r) => { c.radius = r; return c; };
            c.getBounds = () => "bounds";
            L.drawn.circle = c;
            calls.push(["circle", at, options.radius]);
            return c;
        },
        marker: (at, options) => {
            const handlers = {};
            const m = { kind: "marker", at, options, handlers };
            m.addTo = () => m;
            m.on = (type, fn) => { handlers[type] = fn; return m; };
            m.setLatLng = (p) => { m.at = p; return m; };
            m.getLatLng = () => ({ lat: m.at[0], lng: m.at[1] });
            L.drawn.marker = m;
            return m;
        },
    };
    return L;
}

function element(attrs = {}, value = "") {
    return {
        attrs: { ...attrs },
        value,
        listeners: {},
        getAttribute(name) { return Object.prototype.hasOwnProperty.call(this.attrs, name) ? this.attrs[name] : null; },
        hasAttribute(name) { return Object.prototype.hasOwnProperty.call(this.attrs, name); },
        removeAttribute(name) { delete this.attrs[name]; },
        addEventListener(type, fn) { this.listeners[type] = fn; },
    };
}

/** A zone's page: `box` = the map's attributes, the fields' values, a phone fix or none. */
function fakePage({ box = {}, lat = "", lng = "", radius = "150", phone = null } = {}) {
    const mapBox = element({ "data-zone-map": "", "data-min": "100", "data-max": "2000", ...box });
    mapBox.querySelector = () => ({ id: "map" });
    const fields = {
        lat: element({ "data-zone-lat": "" }, lat),
        lng: element({ "data-zone-lng": "" }, lng),
        radius: element({ "data-zone-radius": "" }, radius),
    };
    const slider = element({ "data-zone-slider": "" }, radius);
    const sliderRow = element({ "data-zone-slider-row": "", hidden: "" });
    const usePhone = phone ? element({ "data-use-phone": "", "data-lat": phone[0], "data-lng": phone[1], hidden: "" }) : null;
    const doc = {
        documentElement: {},
        querySelector: (selector) => ({
            "[data-zone-map]": mapBox,
            "[data-zone-lat]": fields.lat,
            "[data-zone-lng]": fields.lng,
            "[data-zone-radius]": fields.radius,
            "[data-zone-slider]": slider,
            "[data-zone-slider-row]": sliderRow,
            "[data-use-phone]": usePhone,
        })[selector] || null,
    };
    const win = { document: doc, getComputedStyle: () => ({ getPropertyValue: () => "" }) };
    return { win, fields, slider, sliderRow, usePhone };
}

test("the radius is a whole number within the range; nothing typed is no number", () => {
    assert.strictEqual(clampRadius("150", 100, 2000), 150);
    assert.strictEqual(clampRadius("152.4", 100, 2000), 152);
    assert.strictEqual(clampRadius("50", 100, 2000), 100);
    assert.strictEqual(clampRadius("99999", 100, 2000), 2000);
    assert.strictEqual(clampRadius("", 100, 2000), null);
    assert.strictEqual(clampRadius("abc", 100, 2000), null);
    assert.strictEqual(clampRadius(null, 100, 2000), null);
});

test("coordinates are written with 6 decimals, a longitude wrapped back on Earth", () => {
    assert.strictEqual(fixed6(59.91390049), "59.913900");
    assert.strictEqual(wrapLng(190), -170);
    assert.strictEqual(wrapLng(-181), 179);
    assert.strictEqual(wrapLng(10.5), 10.5);
    assert.strictEqual(readCoord("59,9139", 90), 59.9139);
    assert.strictEqual(readCoord(" 10.75 ", 180), 10.75);
    assert.strictEqual(readCoord("91", 90), null);
    assert.strictEqual(readCoord("", 90), null);
    const fields = { lat: { value: "" }, lng: { value: "" } };
    place(fields, 59.9139123, 370.5);
    assert.deepStrictEqual([fields.lat.value, fields.lng.value], ["59.913912", "10.500000"]);
});

test("a tap places the centre and writes the fields; a double tap doesn't zoom", () => {
    const L = fakeLeaflet();
    const page = fakePage();
    install(page.win, L);
    assert.deepStrictEqual(L.calls[0], ["map", { doubleClickZoom: false }]);
    assert.deepStrictEqual(L.calls[1], ["setView", [20, 0], 2], "no centre and no fix: the world");
    assert.strictEqual(L.drawn.circle, undefined);
    L.handlers.click({ latlng: { lat: 59.91417, lng: 10.7522 } });
    assert.deepStrictEqual([page.fields.lat.value, page.fields.lng.value], ["59.914170", "10.752200"]);
    assert.deepStrictEqual(L.drawn.circle.at, [59.91417, 10.7522]);
    assert.strictEqual(L.drawn.circle.radius, 150);
    assert.strictEqual(L.drawn.marker.options.draggable, true);
    // Dragging the marker moves the centre too.
    L.drawn.marker.setLatLng([59.92, 10.76]);
    L.drawn.marker.handlers.dragend();
    assert.deepStrictEqual([page.fields.lat.value, page.fields.lng.value], ["59.920000", "10.760000"]);
});

test("the slider and the number field redraw the circle; leaving the field clamps it", () => {
    const L = fakeLeaflet();
    const page = fakePage({ lat: "59.9139", lng: "10.7522" });
    install(page.win, L);
    assert.ok(!page.sliderRow.hasAttribute("hidden"), "the slider shows with JS");
    assert.deepStrictEqual(L.calls.filter((c) => c[0] === "circle"), [["circle", [59.9139, 10.7522], 150]]);
    assert.ok(L.calls.some((c) => c[0] === "fitBounds"));
    page.slider.value = "420";
    page.slider.listeners.input();
    assert.strictEqual(page.fields.radius.value, "420");
    assert.strictEqual(L.drawn.circle.radius, 420);
    // Typing "1" on the way to "1500" draws the smallest circle, the field left as typed.
    page.fields.radius.value = "1";
    page.fields.radius.listeners.input();
    assert.strictEqual(L.drawn.circle.radius, 100);
    assert.strictEqual(page.fields.radius.value, "1");
    page.fields.radius.value = "5000";
    page.fields.radius.listeners.change();
    assert.strictEqual(page.fields.radius.value, "2000");
    assert.strictEqual(page.slider.value, "2000");
    assert.strictEqual(L.drawn.circle.radius, 2000);
});

test("Use Phone's Location moves the centre to the newest fix", () => {
    const L = fakeLeaflet();
    const page = fakePage({ box: { "data-phone-lat": "59.950000", "data-phone-lng": "10.700000" }, phone: ["59.950000", "10.700000"] });
    install(page.win, L);
    assert.deepStrictEqual(L.calls[1], ["setView", [59.95, 10.7], 15], "no centre yet: the phone's fix");
    assert.ok(!page.usePhone.hasAttribute("hidden"));
    page.usePhone.listeners.click();
    assert.deepStrictEqual([page.fields.lat.value, page.fields.lng.value], ["59.950000", "10.700000"]);
    assert.deepStrictEqual(L.drawn.circle.at, [59.95, 10.7]);
    // Coordinates typed off the Earth take the circle off.
    page.fields.lat.value = "95";
    page.fields.lat.listeners.change();
    assert.ok(L.calls.some((c) => c[0] === "remove" && c[1] === "circle"));
});
