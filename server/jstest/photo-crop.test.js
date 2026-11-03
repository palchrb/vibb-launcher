// Behaviour of static/photo-crop.js: the crop maths (the square from the zoom and the centre, the clamp that keeps
// the circle on the photo, cover as the least zoom) and the dialog with a fake canvas - Cancel uploads nothing,
// Save puts one JPEG into the input and submits its form. Run: node --test jstest/photo-crop.test.js
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const crop = require("../static/photo-crop.js");

const { clampView, centred, sourceRect, pan, zoomAt, fitWithin, outputSide, install } = crop;

/** The square lies inside the w×h picture. */
function inside(rect, w, h) {
    return rect.x >= 0 && rect.y >= 0 && rect.x + rect.side <= w + 1e-9 && rect.y + rect.side <= h + 1e-9;
}

test("cover, centred: the shorter side across, as the server's own centre crop", () => {
    assert.deepStrictEqual(sourceRect(centred(4000, 3000), 4000, 3000), { x: 500, y: 0, side: 3000 });
    assert.deepStrictEqual(sourceRect(centred(300, 600), 300, 600), { x: 0, y: 150, side: 300 });
});

test("the zoom and the centre give the square", () => {
    assert.deepStrictEqual(sourceRect({ zoom: 2, cx: 1000, cy: 1000 }, 4000, 3000), { x: 250, y: 250, side: 1500 });
    assert.deepStrictEqual(sourceRect({ zoom: 4, cx: 2000, cy: 1500 }, 4000, 3000), { x: 1625, y: 1125, side: 750 });
});

test("the least zoom is cover: below 1 (or not a number) reads as 1, the square never leaves the photo", () => {
    for (const zoom of [0.5, 0, -3, NaN, "x"]) {
        const view = clampView({ zoom, cx: 2000, cy: 1500 }, 4000, 3000, 4);
        assert.strictEqual(view.zoom, 1);
        const rect = sourceRect(view, 4000, 3000);
        assert.strictEqual(rect.side, 3000);
        assert.ok(inside(rect, 4000, 3000));
    }
    assert.strictEqual(clampView({ zoom: 9, cx: 0, cy: 0 }, 4000, 3000, 4).zoom, 4);
});

test("the centre is clamped so the circle never shows empty area", () => {
    const w = 4000;
    const h = 3000;
    for (const [zoom, cx, cy] of [[1, -5000, 9000], [2, 0, 0], [2, 4000, 3000], [3.3, 10, 2990], [1, 2000, -1]]) {
        const rect = sourceRect(clampView({ zoom, cx, cy }, w, h, 4), w, h);
        assert.ok(inside(rect, w, h), JSON.stringify({ zoom, cx, cy, rect }));
    }
    // At cover the photo's short side can't move at all.
    assert.strictEqual(clampView({ zoom: 1, cx: 2000, cy: 0 }, w, h, 4).cy, 1500);
});

test("a drag moves the photo with the finger, and stops at its edge", () => {
    // A 300 px frame over a 3000 px square: 1 px on the screen is 10 px of the photo.
    const start = { zoom: 2, cx: 2000, cy: 1500 };
    const moved = pan(start, 30, -20, 300, 4000, 3000);
    assert.strictEqual(moved.cx, 2000 - 30 * 5);
    assert.strictEqual(moved.cy, 1500 + 20 * 5);
    const far = pan(start, 100000, 100000, 300, 4000, 3000);
    assert.deepStrictEqual(sourceRect(far, 4000, 3000), { x: 0, y: 0, side: 1500 });
});

test("a zoom keeps the point under the pinch where it is", () => {
    const start = { zoom: 1, cx: 2000, cy: 1500 };
    const frame = 300;
    // The photo's point 60 px right of and 30 px above the frame's centre.
    const point = (v) => ({ x: v.cx + 60 * (3000 / v.zoom / frame), y: v.cy - 30 * (3000 / v.zoom / frame) });
    const before = point(start);
    const after = zoomAt(start, 2, 60, -30, frame, 4000, 3000, 4);
    assert.strictEqual(after.zoom, 2);
    assert.deepStrictEqual(point(after), before);
    // Out to cover again: the clamp wins over the anchor, the square inside the photo.
    const out = zoomAt({ zoom: 3, cx: 300, cy: 300 }, 0.5, -100, -100, frame, 4000, 3000, 4);
    assert.strictEqual(out.zoom, 1);
    assert.ok(inside(sourceRect(out, 4000, 3000), 4000, 3000));
});

test("the working copy is shrunk, never enlarged; the upload is at most the server's side", () => {
    assert.deepStrictEqual(fitWithin(4000, 3000, 2048, 4096), { w: 2731, h: 2048 });
    assert.deepStrictEqual(fitWithin(800, 600, 2048, 4096), { w: 800, h: 600 });
    assert.deepStrictEqual(fitWithin(10000, 2500, 2048, 4096), { w: 4096, h: 1024 });
    assert.strictEqual(outputSide(1500, 512), 512);
    assert.strictEqual(outputSide(300.4, 512), 300);
    assert.strictEqual(outputSide(0.2, 512), 1);
});

// ---- The dialog, with a fake page ----

function element(tag, attrs = {}) {
    const el = {
        tagName: tag.toUpperCase(),
        attrs: { ...attrs },
        listeners: {},
        hidden: Object.prototype.hasOwnProperty.call(attrs, "hidden"),
        disabled: false,
        value: "",
        getAttribute(name) { return Object.prototype.hasOwnProperty.call(this.attrs, name) ? this.attrs[name] : null; },
        setAttribute(name, value) { this.attrs[name] = String(value); },
        hasAttribute(name) { return Object.prototype.hasOwnProperty.call(this.attrs, name); },
        removeAttribute(name) { delete this.attrs[name]; },
        addEventListener(type, fn) { (this.listeners[type] = this.listeners[type] || []).push(fn); },
        fire(type, event = {}) {
            const e = { target: this, preventDefault() { this.prevented = true; }, ...event };
            for (const fn of this.listeners[type] || []) fn(e);
            return e;
        },
        focus() { page.doc.activeElement = this; },
        getBoundingClientRect() { return { left: 0, top: 0, width: 300, height: 300 }; },
    };
    return el;
}

/** A canvas whose drawing is recorded, and whose toBlob hands back a fake JPEG (or null). */
function canvas() {
    const el = element("canvas");
    el.width = 300;
    el.height = 150;
    el.drawn = [];
    el.getContext = () => ({
        fillRect() {},
        drawImage: (...args) => el.drawn.push(args),
    });
    el.toBlob = (done, type, quality) => {
        page.blobs.push({ type, quality, side: el.width });
        setTimeout(() => done(page.noBlob ? null : { fake: "jpeg", type, side: el.width }), 0);
    };
    return el;
}

let page;

/** The calls page: one contact's photo form (its file input, "Adjust crop" when it has a photo) and the dialog. */
function fakePage({ photo = true, transfer = true, decodes = true, size = [4000, 3000] } = {}) {
    page = { blobs: [], loaded: [], revoked: [], submitted: [], noBlob: false };
    const dialog = element("div", { "data-crop-dialog": "", "data-side": "512", "data-max-bytes": "10485760", hidden: "" });
    const parts = {
        "[data-crop-frame]": element("div"),
        "[data-crop-canvas]": canvas(),
        "[data-crop-zoom]": element("input"),
        "[data-crop-cancel]": element("button"),
        "[data-crop-save]": element("button"),
    };
    dialog.querySelector = (selector) => parts[selector] || null;
    const form = { submit() { page.submitted.push(input.files); } };
    const input = element("input", { type: "file", name: "photo", "data-autosave": "", "data-photo-crop": "" });
    input.form = form;
    input.files = [];
    form.querySelector = () => input;
    const adjust = photo ? element("button", { "data-photo-adjust": "/contact-photos/abc", hidden: "" }) : null;
    if (adjust) adjust.form = form;
    const doc = {
        activeElement: null,
        querySelector: (selector) => (selector === "[data-crop-dialog]" ? dialog : null),
        querySelectorAll: (selector) => {
            if (selector === "input[type=file][data-photo-crop]") return [input];
            if (selector === "[data-photo-adjust]") return adjust ? [adjust] : [];
            return [];
        },
        createElement: (tag) => (tag === "canvas" ? canvas() : element(tag)),
    };
    class FakeTransfer {
        constructor() {
            if (!transfer) throw new TypeError("Illegal constructor");
            const files = [];
            this.files = files;
            this.items = { add: (file) => files.push(file) };
        }
    }
    class FakeFile {
        constructor(parts, name, options) {
            this.parts = parts;
            this.name = name;
            this.type = options.type;
        }
    }
    class FakeImage {
        set src(value) { this.url = value; page.loaded.push(value); }
        get src() { return this.url; }
        decode() {
            this.naturalWidth = size[0];
            this.naturalHeight = size[1];
            return decodes ? Promise.resolve() : Promise.reject(new Error("EncodingError"));
        }
    }
    const win = {
        Promise,
        DataTransfer: FakeTransfer,
        File: FakeFile,
        Image: FakeImage,
        devicePixelRatio: 2,
        URL: {
            createObjectURL: (file) => `blob:${file.name}`,
            revokeObjectURL: (url) => page.revoked.push(url),
        },
        addEventListener() {},
    };
    page.doc = doc;
    page.api = install(doc, win);
    return Object.assign(page, { dialog, input, adjust, parts, form });
}

const settle = () => new Promise((resolve) => setTimeout(resolve, 5));

function pick(p, file = { name: "IMG_1234.jpg", size: 3 * 1024 * 1024 }) {
    p.input.files = [file];
    p.input.value = "C:\\fakepath\\IMG_1234.jpg";
    p.input.fire("change");
}

test("a picked photo opens the dialog instead of uploading, and the input no longer autosaves", async () => {
    const p = fakePage();
    assert.ok(!p.input.hasAttribute("data-autosave"));
    assert.strictEqual(p.adjust.hidden, false);
    pick(p);
    assert.strictEqual(p.dialog.hidden, false);
    assert.strictEqual(p.doc.activeElement, p.parts["[data-crop-frame]"]);
    await settle();
    assert.deepStrictEqual(p.loaded, ["blob:IMG_1234.jpg"]);
    assert.deepStrictEqual(p.revoked, ["blob:IMG_1234.jpg"]);
    assert.strictEqual(p.parts["[data-crop-save]"].disabled, false);
    // The preview at the screen's density: the 300 px frame on a 2x screen.
    assert.strictEqual(p.parts["[data-crop-canvas]"].width, 600);
    assert.deepStrictEqual(p.submitted, []);
});

test("Cancel empties the input and uploads nothing; Escape does the same", async () => {
    const p = fakePage();
    pick(p);
    await settle();
    p.parts["[data-crop-cancel]"].fire("click");
    assert.strictEqual(p.dialog.hidden, true);
    assert.strictEqual(p.input.value, "");
    assert.strictEqual(p.doc.activeElement, p.input);
    pick(p);
    await settle();
    p.dialog.fire("keydown", { key: "Escape" });
    assert.strictEqual(p.dialog.hidden, true);
    assert.strictEqual(p.input.value, "");
    await settle();
    assert.deepStrictEqual(p.submitted, []);
    assert.deepStrictEqual(p.blobs, []);
});

test("Save uploads one 512 px JPEG through the form, with the square the frame shows", async () => {
    const p = fakePage();
    pick(p);
    await settle();
    // Zoomed in to 2 and dragged: the working copy is 2731x2048, the square 1024 of it.
    p.parts["[data-crop-zoom]"].value = "2";
    p.parts["[data-crop-zoom]"].fire("input");
    p.parts["[data-crop-save]"].fire("click");
    await settle();
    assert.strictEqual(p.blobs.length, 1);
    assert.deepStrictEqual(p.blobs[0], { type: "image/jpeg", quality: 0.92, side: 512 });
    assert.strictEqual(p.submitted.length, 1);
    const files = p.submitted[0];
    assert.strictEqual(files.length, 1);
    assert.strictEqual(files[0].name, "photo.jpg");
    assert.strictEqual(files[0].type, "image/jpeg");
    assert.strictEqual(files[0].parts[0].fake, "jpeg");
    assert.strictEqual(p.dialog.hidden, true);
    // A change the new file might fire starts nothing.
    p.input.fire("change");
    assert.strictEqual(p.dialog.hidden, true);
});

test("the focus stays in the dialog: Tab from Save goes back to the frame, Shift-Tab from the frame to Save", async () => {
    const p = fakePage();
    pick(p);
    await settle();
    p.parts["[data-crop-save]"].focus();
    p.dialog.fire("keydown", { key: "Tab" });
    assert.strictEqual(p.doc.activeElement, p.parts["[data-crop-frame]"]);
    p.dialog.fire("keydown", { key: "Tab", shiftKey: true });
    assert.strictEqual(p.doc.activeElement, p.parts["[data-crop-save]"]);
});

test("Adjust crop opens the stored photo and saves the same way", async () => {
    const p = fakePage({ size: [512, 512] });
    p.adjust.fire("click");
    await settle();
    assert.deepStrictEqual(p.loaded, ["/contact-photos/abc"]);
    p.parts["[data-crop-save]"].fire("click");
    await settle();
    assert.strictEqual(p.submitted.length, 1);
    assert.strictEqual(p.submitted[0][0].type, "image/jpeg");
});

test("a photo the browser can't read, or one over the limit, goes up as it is", async () => {
    const p = fakePage({ decodes: false });
    pick(p);
    await settle();
    assert.strictEqual(p.dialog.hidden, true);
    assert.strictEqual(p.submitted.length, 1);
    assert.strictEqual(p.submitted[0][0].name, "IMG_1234.jpg");

    const big = fakePage();
    pick(big, { name: "huge.jpg", size: 11 * 1024 * 1024 });
    assert.strictEqual(big.dialog.hidden, true);
    assert.strictEqual(big.submitted.length, 1);
    assert.strictEqual(big.submitted[0][0].name, "huge.jpg");
});

test("without a settable file input nothing changes: the input autosaves, Adjust crop stays hidden", () => {
    const p = fakePage({ transfer: false });
    assert.strictEqual(p.api, null);
    assert.ok(p.input.hasAttribute("data-autosave"));
    assert.strictEqual(p.adjust.hidden, true);
    assert.deepStrictEqual(p.input.listeners, {});
});
