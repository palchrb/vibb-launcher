// Behaviour of static/photo-crop.js: the crop maths (the square from the zoom and the centre, the clamp that keeps
// the circle on the photo, cover as the least zoom, the server's original size, the view as the form sends it) and
// the dialog with a fake canvas - Cancel uploads nothing, Save submits the picked photo with its view, an adjust of a
// kept original starts at its saved view and sends only the new one. Run: node --test jstest/photo-crop.test.js
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const crop = require("../static/photo-crop.js");

const {
    clampView, centred, sourceRect, pan, zoomAt, fitWithin, outputSide, adjustZoom, halvings, originalSize, overLimits,
    viewText, viewOf, install,
} = crop;

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

test("the working copy is shrunk by area, never enlarged; the upload is at most the server's side", () => {
    assert.deepStrictEqual(fitWithin(4000, 3000, 8000000, 8192), { w: 3266, h: 2449 });
    assert.deepStrictEqual(fitWithin(800, 600, 8000000, 8192), { w: 800, h: 600 });
    // A banner keeps its short side (QA photo-crop L6).
    assert.deepStrictEqual(fitWithin(8000, 600, 8000000, 8192), { w: 8000, h: 600 });
    assert.strictEqual(outputSide(1500, 512), 512);
    assert.strictEqual(outputSide(300.4, 512), 300);
    assert.strictEqual(outputSide(0.2, 512), 1);
    // By halves while more than twice the target, so no draw shrinks by more than 2x.
    assert.deepStrictEqual(halvings(2048, 512), [1024, 512]);
    assert.deepStrictEqual(halvings(3000, 512), [1500, 750, 512]);
    assert.deepStrictEqual(halvings(1024, 512), [512]);
    assert.deepStrictEqual(halvings(300, 300), [300]);
});

test("an adjust zooms only while the square stays at least 230 px", () => {
    assert.strictEqual(adjustZoom(512, 512), 512 / 230);
    assert.strictEqual(adjustZoom(2048, 2048), crop.MAX_ZOOM);
    assert.strictEqual(adjustZoom(200, 200), 1);
});

test("the server's original size (photos::original_size): 2048 on the long side, a panorama's short side kept", () => {
    assert.deepStrictEqual(originalSize(4000, 3000), { w: 2048, h: 1536 });
    assert.deepStrictEqual(originalSize(4000, 2250), { w: 2048, h: 1152 });
    assert.deepStrictEqual(originalSize(8000, 1000), { w: 5017, h: 627 });
    assert.deepStrictEqual(originalSize(600, 600), { w: 600, h: 600 });
    assert.ok(!overLimits(8000, 5000) && overLimits(8001, 10) && overLimits(7000, 7000));
});

test("the view goes as fractions of the picture and comes back as the same square", () => {
    const text = viewText({ zoom: 2, cx: 1024, cy: 768 }, 2048, 1536);
    assert.strictEqual(text, "0.312500,0.250000,0.375000,0.500000");
    // The same square in another copy's pixels (the server's original, the cropper's working copy).
    assert.deepStrictEqual(viewOf(text, 4096, 3072, 4), { zoom: 2, cx: 2048, cy: 1536 });
    for (const bad of [null, "", "1,2,3", "a,b,c,d", "0,0,0,0"]) {
        assert.deepStrictEqual(viewOf(bad, 2048, 1536, 4), centred(2048, 1536), String(bad));
    }
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
    if (page) page.canvases.push(el);
    el.getContext = () => ({
        fillRect() {},
        drawImage: (...args) => el.drawn.push(args),
        set imageSmoothingQuality(value) { el.smoothing = value; },
    });
    el.toBlob = (done, type, quality) => {
        page.blobs.push({ type, quality, side: el.width });
        setTimeout(() => done(page.noBlob ? null : { fake: "jpeg", type, side: el.width }), 0);
    };
    return el;
}

let page;

/**
 * The calls page: one contact's photo form (its file input, the hidden view and original, "Adjust crop" when it has
 * a photo - of its kept original when `kept` is {hash, view}) and the dialog.
 */
function fakePage({ photo = true, kept = null, transfer = true, decodes = true, size = [4000, 3000] } = {}) {
    page = { blobs: [], loaded: [], revoked: [], submitted: [], noBlob: false, canvases: [] };
    const dialog = element("div", { "data-crop-dialog": "", "data-side": "512", "data-max-bytes": "10485760", hidden: "" });
    const parts = {
        "[data-crop-frame]": element("div"),
        "[data-crop-canvas]": canvas(),
        "[data-crop-zoom]": element("input"),
        "[data-crop-cancel]": element("button"),
        "[data-crop-save]": element("button"),
    };
    dialog.querySelector = (selector) => parts[selector] || null;
    const form = {
        submit() { page.submitted.push({ files: input.files, value: input.value, view: view.value, original: original.value }); },
    };
    const input = element("input", { type: "file", name: "photo", "data-autosave": "", "data-photo-crop": "" });
    input.form = form;
    input.files = [];
    const view = element("input", { type: "hidden", name: "view" });
    const original = element("input", { type: "hidden", name: "original" });
    const fields = { "input[type=file][data-photo-crop]": input, "[data-photo-view]": view, "[data-photo-original]": original };
    form.querySelector = (selector) => fields[selector] || null;
    const attrs = kept
        ? { "data-photo-adjust": `/contact-photos/original/${kept.hash}`, "data-photo-original": kept.hash, "data-photo-view": kept.view }
        : { "data-photo-adjust": "/contact-photos/abc" };
    const adjust = photo ? element("button", { ...attrs, hidden: "" }) : null;
    if (adjust) adjust.form = form;
    const main = element("main");
    const doc = element("document");
    Object.assign(doc, {
        activeElement: null,
        querySelector: (selector) => ({ "[data-crop-dialog]": dialog, main }[selector] || null),
        querySelectorAll: (selector) => {
            if (selector === "input[type=file][data-photo-crop]") return [input];
            if (selector === "[data-photo-adjust]") return adjust ? [adjust] : [];
            return [];
        },
        createElement: (tag) => (tag === "canvas" ? canvas() : element(tag)),
    });
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
    return Object.assign(page, { dialog, input, view, original, adjust, parts, form, main });
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
    p.doc.fire("keydown", { key: "Escape" });
    assert.strictEqual(p.dialog.hidden, true);
    assert.strictEqual(p.input.value, "");
    await settle();
    assert.deepStrictEqual(p.submitted, []);
    assert.deepStrictEqual(p.blobs, []);
});

test("Save submits the picked photo itself with the view of the square the frame shows", async () => {
    const p = fakePage({ size: [2048, 1536] });
    pick(p);
    await settle();
    // Zoomed in to 2 about the centre: the square is 768 of the 2048x1536 picture, at (640, 384).
    p.parts["[data-crop-zoom]"].value = "2";
    p.parts["[data-crop-zoom]"].fire("input");
    const made = p.canvases.length;
    p.parts["[data-crop-save]"].fire("click");
    await settle();
    assert.strictEqual(p.canvases.length, made, "nothing drawn: the server cuts the square");
    assert.deepStrictEqual(p.blobs, []);
    assert.strictEqual(p.submitted.length, 1);
    const sent = p.submitted[0];
    assert.strictEqual(sent.files[0].name, "IMG_1234.jpg");
    assert.strictEqual(sent.view, "0.312500,0.250000,0.375000,0.500000");
    assert.strictEqual(sent.original, "");
    assert.strictEqual(p.dialog.hidden, true);
    // A change the input might fire starts nothing.
    p.input.fire("change");
    assert.strictEqual(p.dialog.hidden, true);
});

test("a photo past the server's decoder goes up as its copy at the original's size, with the view", async () => {
    // 54 MP: the working copy is 3464x2309, the original the server would keep 2048x1365.
    const p = fakePage({ size: [9000, 6000] });
    pick(p);
    await settle();
    const preview = p.parts["[data-crop-canvas]"];
    const work = preview.drawn[preview.drawn.length - 1][0];
    const made = p.canvases.length;
    p.parts["[data-crop-save]"].fire("click");
    await settle();
    const drawn = p.canvases.slice(made);
    assert.deepStrictEqual(drawn.map((c) => c.drawn[0]), [[work, 0, 0, 3464, 2309, 0, 0, 2048, 1365]]);
    assert.strictEqual(drawn[0].smoothing, "high");
    assert.deepStrictEqual(p.blobs, [{ type: "image/jpeg", quality: 0.95, side: 2048 }]);
    const sent = p.submitted[0];
    assert.strictEqual(sent.files.length, 1);
    assert.strictEqual(sent.files[0].name, "photo.jpg");
    assert.strictEqual(sent.files[0].parts[0].fake, "jpeg");
    assert.strictEqual(sent.view, viewText(centred(3464, 2309), 3464, 2309));
});

test("a picked photo zooms only as far as the square stays 230 px of the original the server keeps", async () => {
    const small = fakePage({ size: [1000, 750] });
    pick(small);
    await settle();
    assert.strictEqual(small.parts["[data-crop-zoom]"].max, "3.26");
    // The working copy is 3266x2449, the original 2048x1536: 6.7x, so the slider's own limit.
    const big = fakePage({ size: [4000, 3000] });
    pick(big);
    await settle();
    assert.strictEqual(big.parts["[data-crop-zoom]"].max, String(crop.MAX_ZOOM));
});

test("toBlob failing: a picked photo goes up as it is, an adjust stays open to try again", async () => {
    // Past the decoder's limits, so its copy would have gone instead.
    const p = fakePage({ size: [9000, 6000] });
    page.noBlob = true;
    pick(p);
    await settle();
    p.parts["[data-crop-save]"].fire("click");
    await settle();
    assert.strictEqual(p.submitted.length, 1);
    assert.strictEqual(p.submitted[0].files[0].name, "IMG_1234.jpg");
    assert.strictEqual(p.blobs.length, 1);

    const a = fakePage({ size: [512, 512] });
    page.noBlob = true;
    a.adjust.fire("click");
    await settle();
    a.parts["[data-crop-save]"].fire("click");
    await settle();
    assert.deepStrictEqual(a.submitted, []);
    assert.strictEqual(a.dialog.hidden, false);
    assert.strictEqual(a.parts["[data-crop-save]"].disabled, false);
});

test("the page behind is inert while the dialog is open", async () => {
    const p = fakePage();
    pick(p);
    assert.ok(p.main.hasAttribute("inert"));
    await settle();
    p.parts["[data-crop-cancel]"].fire("click");
    assert.ok(!p.main.hasAttribute("inert"));
});

test("the focus stays in the dialog: Tab from Save goes back to the frame, Shift-Tab from the frame to Save", async () => {
    const p = fakePage();
    pick(p);
    await settle();
    p.parts["[data-crop-save]"].focus();
    p.doc.fire("keydown", { key: "Tab" });
    assert.strictEqual(p.doc.activeElement, p.parts["[data-crop-frame]"]);
    p.doc.fire("keydown", { key: "Tab", shiftKey: true });
    assert.strictEqual(p.doc.activeElement, p.parts["[data-crop-save]"]);
});

test("Adjust crop of a photo from before 0123 opens the stored square, zooms only to 230 px, uploads the square", async () => {
    const p = fakePage({ size: [512, 512] });
    p.view.value = "stale";
    p.adjust.fire("click");
    await settle();
    assert.deepStrictEqual(p.loaded, ["/contact-photos/abc"]);
    assert.strictEqual(p.parts["[data-crop-zoom]"].max, "2.22");
    p.parts["[data-crop-zoom]"].value = "4";
    p.parts["[data-crop-zoom]"].fire("input");
    p.parts["[data-crop-save]"].fire("click");
    await settle();
    assert.strictEqual(p.blobs[0].side, 230);
    assert.strictEqual(p.submitted.length, 1);
    assert.strictEqual(p.submitted[0].files[0].type, "image/jpeg");
    // The square is the file: no view (the server keeps it whole), no original.
    assert.strictEqual(p.submitted[0].view, "");
    assert.strictEqual(p.submitted[0].original, "");
});

test("Adjust crop of a kept original starts at its saved view, can zoom out to cover, and sends only the view", async () => {
    const p = fakePage({ size: [2048, 1536], kept: { hash: "def", view: "0.312500,0.250000,0.375000,0.500000" } });
    p.input.value = "C:\\fakepath\\old.jpg";
    p.adjust.fire("click");
    await settle();
    assert.deepStrictEqual(p.loaded, ["/contact-photos/original/def"]);
    // The saved square: zoom 2 about (1024, 768), the slider up to the 230 px limit (6.7x, so 4).
    assert.strictEqual(p.parts["[data-crop-zoom]"].value, "2");
    assert.strictEqual(p.parts["[data-crop-zoom]"].max, "4");
    const preview = p.parts["[data-crop-canvas]"];
    assert.deepStrictEqual(preview.drawn[preview.drawn.length - 1].slice(1, 5), [640, 384, 768, 768]);
    // Out to cover: wider than the saved square, the whole short side.
    p.parts["[data-crop-zoom]"].value = "1";
    p.parts["[data-crop-zoom]"].fire("input");
    const made = p.canvases.length;
    p.parts["[data-crop-save]"].fire("click");
    await settle();
    assert.strictEqual(p.canvases.length, made, "nothing drawn: the server cuts again");
    assert.deepStrictEqual(p.blobs, []);
    assert.deepStrictEqual(p.submitted, [
        { files: [], value: "", view: "0.125000,0.000000,0.750000,1.000000", original: "def" },
    ]);
});

test("a photo the browser can't read, or one over the limit, goes up as it is", async () => {
    const p = fakePage({ decodes: false });
    pick(p);
    await settle();
    assert.strictEqual(p.dialog.hidden, true);
    assert.strictEqual(p.submitted.length, 1);
    assert.strictEqual(p.submitted[0].files[0].name, "IMG_1234.jpg");
    assert.strictEqual(p.submitted[0].view, "", "no view: the server cuts cover, centred");

    const big = fakePage();
    pick(big, { name: "huge.jpg", size: 11 * 1024 * 1024 });
    assert.strictEqual(big.dialog.hidden, true);
    assert.strictEqual(big.submitted.length, 1);
    assert.strictEqual(big.submitted[0].files[0].name, "huge.jpg");
});

test("without a settable file input nothing changes: the input autosaves, Adjust crop stays hidden", () => {
    const p = fakePage({ transfer: false });
    assert.strictEqual(p.api, null);
    assert.ok(p.input.hasAttribute("data-autosave"));
    assert.strictEqual(p.adjust.hidden, true);
    assert.deepStrictEqual(p.input.listeners, {});
});
