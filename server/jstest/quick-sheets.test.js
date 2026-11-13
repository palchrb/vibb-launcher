// Behaviour of static/quick-sheets.js (design 93 §6.1, §6.2): a tile opens its sheet in place and checks its mode,
// Lukk closes it, the buttons say their sentences, a length fills Til, and "Alle regler" leaves in Slå på nå.
// Run: node --test jstest/quick-sheets.test.js
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const { install, clock } = require("../static/quick-sheets.js");

// A small DOM: enough elements, attributes and selectors (`tag`, `.class`, `[attr]`, `[attr=value]`) for the script.
class El {
    constructor(tag, attrs = {}, children = []) {
        this.tagName = tag.toUpperCase();
        this.attrs = {};
        for (const [k, v] of Object.entries(attrs)) if (k !== "text") this.attrs[k] = v;
        this.own = attrs.text || "";
        this.hidden = "hidden" in attrs;
        this.checked = "checked" in attrs;
        this.disabled = "disabled" in attrs;
        this.selected = "selected" in attrs;
        this.value = attrs.value || "";
        this.children = [];
        this.parentNode = null;
        this.focused = [];
        const el = this;
        this.classList = {
            list: () => (el.attrs.class || "").split(/\s+/).filter(Boolean),
            contains: (c) => el.classList.list().includes(c),
            add: (c) => { if (!el.classList.contains(c)) el.attrs.class = el.classList.list().concat(c).join(" "); },
            remove: (c) => { el.attrs.class = el.classList.list().filter((x) => x !== c).join(" "); },
        };
        children.forEach((c) => this.appendChild(c));
    }
    get id() { return this.attrs.id; }
    get name() { return this.attrs.name; }
    set className(v) { this.attrs.class = v; }
    getAttribute(n) { return n in this.attrs ? String(this.attrs[n]) : null; }
    hasAttribute(n) { return n in this.attrs; }
    setAttribute(n, v) { this.attrs[n] = v; }
    appendChild(c) { c.parentNode = this; this.children.push(c); }
    insertBefore(c, ref) {
        c.parentNode = this;
        const at = ref ? this.children.indexOf(ref) : -1;
        if (at < 0) this.children.push(c); else this.children.splice(at, 0, c);
    }
    removeChild(c) { this.children.splice(this.children.indexOf(c), 1); c.parentNode = null; }
    get nextSibling() { const p = this.parentNode; return p ? p.children[p.children.indexOf(this) + 1] || null : null; }
    get textContent() { return this.own + this.children.map((c) => c.textContent).join(""); }
    set textContent(v) { this.own = v; this.children = []; }
    get form() { let e = this.parentNode; while (e && e.tagName !== "FORM") e = e.parentNode; return e; }
    all() { return this.children.flatMap((c) => [c, ...c.all()]); }
    matches(sel) {
        const m = /^([a-z]+)?(?:\.([\w-]+))?(?:\[([\w-]+)(?:=([\w-]+))?\])?$/.exec(sel);
        if (!m) throw new Error("selector " + sel);
        return (!m[1] || this.tagName === m[1].toUpperCase()) && (!m[2] || this.classList.contains(m[2])) &&
            (!m[3] || (this.hasAttribute(m[3]) && (m[4] === undefined || this.getAttribute(m[3]) === m[4])));
    }
    querySelectorAll(sel) { return this.all().filter((e) => e.matches(sel)); }
    querySelector(sel) { return this.querySelectorAll(sel)[0] || null; }
    focus(options) { this.focused.push(options); }
}

const h = (tag, attrs, children) => new El(tag, attrs, children);
const segment = (name, value, text, extra = {}) =>
    h("label", { class: "segment" }, [h("input", { type: "radio", name, value, ...extra }), h("span", { text })]);

function page() {
    const tiles = {
        time: h("a", { class: "qa", "data-sheet": "time-sheet" }, [h("span", { text: "Skjermtid" })]),
        pause: h("a", { class: "qa", "data-sheet": "rules-sheet", "data-mode": "pause" }, [h("span", { text: "Pause regler" })]),
        on: h("a", { class: "qa", "data-sheet": "rules-sheet", "data-mode": "on" }, [h("span", { text: "Slå på regel" })]),
    };
    const timeForm = h("form", {}, [
        segment("minutes", "15", "15 min"),
        segment("minutes", "30", "30 min", { checked: "" }),
        h("button", { "data-qa-button": "extra", text: "Gi ekstra tid" }),
    ]);
    const timeSheet = h("div", { class: "group qa-sheet", id: "time-sheet", hidden: "" }, [
        h("span", { id: "time-sheet-title" }),
        h("a", { "data-qa-close": "" }, [h("span", { text: "Lukk" })]),
        timeForm,
    ]);
    const options = [
        h("option", { value: "all", "data-all": "", selected: "", text: "Alle regler" }),
        h("option", { value: "4", "data-school": "", "data-line": "Som i Skole", "data-warn": "W1\nW2", text: "Skole" }),
        h("option", { value: "9", "data-line": "Som i Leggetid", "data-warn": "", text: "Leggetid" }),
    ];
    const rulesForm = h("form", { class: "qa-form" }, [
        h("input", { id: "m-on", name: "mode", value: "on" }),
        h("input", { id: "m-pause", name: "mode", value: "pause", checked: "" }),
        h("select", { "data-qa-rule": "" }, options),
        segment("on_len", "60", "1 t"),
        segment("on_len", "120", "2 t", { checked: "" }),
        segment("on_len", "day", "Ut dagen"),
        h("input", { name: "til", "data-qa-til": "" }),
        segment("pause_minutes", "60", "1 t", { checked: "" }),
        h("p", { "data-qa-line": "", text: "Som i Skole" }),
        h("button", { "data-qa-button": "on", text: "Slå på" }),
        h("button", { "data-qa-button": "pause", text: "Sett på pause" }),
    ]);
    const rulesSheet = h("div", { class: "qa-sheet", id: "rules-sheet", hidden: "" }, [
        h("span", { id: "rules-sheet-title" }),
        h("a", { "data-qa-close": "" }),
        h("div", { class: "group" }, [rulesForm]),
    ]);
    const section = h("section", {
        id: "quick", "data-now-minute": "990", "data-w-midnight": "midnatt",
        "data-w-on": "Slå på {rule} til {at}", "data-w-pause": "Sett {rule} på pause i {duration}",
        "data-w-pause-all": "Sett alle regler på pause i {duration}", "data-w-extra": "Gi {duration} ekstra",
    }, [tiles.time, tiles.pause, tiles.on, timeSheet, rulesSheet]);
    const listeners = {};
    const doc = {
        getElementById: (id) => (section.id === id ? section : section.all().find((e) => e.id === id) || null),
        createElement: (tag) => h(tag),
        addEventListener: (type, fn) => { listeners[type] = fn; },
    };
    const fire = (type, target) => {
        let prevented = false;
        listeners[type]({ target, preventDefault: () => { prevented = true; } });
        return prevented;
    };
    const get = (id) => doc.getElementById(id);
    const button = (which) => rulesForm.querySelector(`button[data-qa-button=${which}]`);
    const warnings = () => rulesForm.querySelectorAll("[data-qa-warn]").map((p) => p.textContent);
    install(doc);
    return { doc, tiles, timeSheet, rulesSheet, rulesForm, timeForm, options, fire, get, button, warnings };
}

test("a minute is a clock time, midnight a word", () => {
    assert.strictEqual(clock(1110, "midnatt"), "18:30");
    assert.strictEqual(clock(5, "midnatt"), "00:05");
    assert.strictEqual(clock(1440, "midnatt"), "midnatt");
});

test("the buttons say their sentences from the start", () => {
    const p = page();
    assert.strictEqual(p.button("pause").textContent, "Sett alle regler på pause i 1 t");
    assert.strictEqual(p.timeForm.querySelector("button").textContent, "Gi 30 min ekstra");
});

test("Slå på regel opens the Regler sheet in on mode, without Alle regler, and says the end", () => {
    const p = page();
    assert.ok(p.fire("click", p.tiles.on.children[0]));
    assert.strictEqual(p.rulesSheet.hidden, false);
    assert.strictEqual(p.get("m-on").checked, true);
    assert.strictEqual(p.options[0].hidden, true);
    assert.strictEqual(p.options[1].selected, true, "the school rule");
    assert.strictEqual(p.rulesForm.querySelector("[data-qa-line]").textContent, "Som i Skole");
    assert.deepStrictEqual(p.warnings(), ["W1", "W2"]);
    assert.strictEqual(p.button("on").textContent, "Slå på Skole til 18:30");
    assert.ok(p.tiles.on.classList.contains("qa-chosen"));
    assert.strictEqual(p.tiles.on.getAttribute("aria-expanded"), "true");
    assert.ok(!p.tiles.pause.classList.contains("qa-chosen"));
    assert.deepStrictEqual(p.get("rules-sheet-title").focused, [{ preventScroll: true }]);

    // The other rule tile only switches the mode; the open tile closes its sheet.
    p.fire("click", p.tiles.pause);
    assert.strictEqual(p.rulesSheet.hidden, false);
    assert.strictEqual(p.get("m-pause").checked, true);
    assert.strictEqual(p.options[0].hidden, false);
    assert.ok(p.tiles.pause.classList.contains("qa-chosen"));
    assert.strictEqual(p.button("pause").textContent, "Sett Skole på pause i 1 t");
    p.fire("click", p.tiles.pause);
    assert.strictEqual(p.rulesSheet.hidden, true);
    assert.strictEqual(p.tiles.pause.getAttribute("aria-expanded"), "false");
});

test("a length fills Til, typing in Til clears the lengths, and a rule brings its own line", () => {
    const p = page();
    p.fire("click", p.tiles.on);
    const lengths = p.rulesForm.querySelectorAll("input[name=on_len]");
    lengths[1].checked = false;
    lengths[0].checked = true;
    p.fire("change", lengths[0]);
    const til = p.rulesForm.querySelector("[data-qa-til]");
    assert.strictEqual(til.value, "17:30");
    assert.strictEqual(p.button("on").textContent, "Slå på Skole til 17:30");
    til.value = "19:00";
    p.fire("input", til);
    assert.ok(lengths.every((l) => !l.checked));
    assert.strictEqual(p.button("on").textContent, "Slå på Skole til 19:00");
    p.options[1].selected = false;
    p.options[2].selected = true;
    p.fire("change", p.rulesForm.querySelector("select"));
    assert.strictEqual(p.rulesForm.querySelector("[data-qa-line]").textContent, "Som i Leggetid");
    assert.deepStrictEqual(p.warnings(), []);
    assert.strictEqual(p.button("on").textContent, "Slå på Leggetid til 19:00");
    // Til emptied again: the page's length is back, so the form never posts neither.
    til.value = "";
    p.fire("input", til);
    assert.ok(lengths[1].checked);
    assert.strictEqual(p.button("on").textContent, "Slå på Leggetid til 18:30");
});

test("Slå på regel closes the sheet it opened while Slå på nå can't be chosen", () => {
    const p = page();
    p.get("m-on").disabled = true;
    p.fire("click", p.tiles.on);
    assert.strictEqual(p.rulesSheet.hidden, false);
    assert.strictEqual(p.get("m-pause").checked, true);
    assert.ok(p.tiles.on.classList.contains("qa-chosen"));
    assert.ok(!p.tiles.pause.classList.contains("qa-chosen"));
    p.fire("click", p.tiles.on);
    assert.strictEqual(p.rulesSheet.hidden, true);
});

test("Lukk closes the sheet and gives the focus back to its tile; a tile hides the other sheet", () => {
    const p = page();
    p.fire("click", p.tiles.time);
    assert.strictEqual(p.timeSheet.hidden, false);
    p.fire("click", p.tiles.on);
    assert.strictEqual(p.timeSheet.hidden, true);
    assert.strictEqual(p.rulesSheet.hidden, false);
    assert.ok(p.fire("click", p.rulesSheet.querySelector("[data-qa-close]")));
    assert.strictEqual(p.rulesSheet.hidden, true);
    assert.deepStrictEqual(p.tiles.on.focused, [{ preventScroll: true }], "the tile that opened it");
    assert.ok(!p.fire("click", p.rulesForm), "a click elsewhere does nothing");
});
