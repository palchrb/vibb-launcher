// Design 103-7: static/swap.js in real engines - what jstest/swap.test.js's fake DOM can only assume. Opens
// jstest/real/swap-fixture.html from file:// in Chromium and WebKit (mobile, 390 px, 3x) through the local Playwright
// and checks each pair:
// - a parent's change sets the real dirty flags: a typed value, a picked <option> and radio are kept over a morph,
//   the saved form takes the server's, the defaults follow the server;
// - DOMParser parses with scripting off (the new page's <noscript> holds a real button), and an imported form's
//   <noscript> is emptied, so `form.elements` holds only its fields;
// - a form in a <details> that closes is not drawn (swap.js's `drawn`: both engines still give such a form client
//   rects, so a closed <details> ancestor counts too), the sheet's row anchors, and a warning that comes in before a
//   form moves it by exactly the delta swap.js scrolls by;
// - style.css's row hairlines (the one rule, its 2dppx block last - design 103 §4) draw all 11 lines of a 12-row group
//   at each of 24 fractional offsets, none thicker than 2 device px (no doubled line).
// Run by hand: node server/jstest/real/swap-real.js (not in CI: two browsers to download on every run). The
// Playwright module: `playwright` on NODE_PATH, else ~/.local/opt/pw; the browsers: HANDY_PW_CHROMIUM and
// HANDY_PW_WEBKIT (an executable), else Playwright's own. Without Playwright it prints "skipped" and exits 0.
"use strict";
const path = require("path");
const fs = require("fs");
const os = require("os");

const LOCAL = path.join(os.homedir(), ".local/opt/pw");
if (!process.env.PLAYWRIGHT_BROWSERS_PATH && fs.existsSync(path.join(LOCAL, "browsers"))) {
    process.env.PLAYWRIGHT_BROWSERS_PATH = path.join(LOCAL, "browsers");
}
let pw = null;
for (const name of ["playwright", path.join(LOCAL, "node_modules/playwright")]) {
    try {
        pw = require(name);
        break;
    } catch (e) {
        // the next one
    }
}
if (!pw) {
    console.log("skipped: no Playwright on this machine");
    process.exit(0);
}

const FIXTURE = "file://" + path.join(__dirname, "swap-fixture.html");
// A WebKit build newer than the local Playwright's own revision works too (the §1.3 measure used it).
const WEBKIT_GUESS = path.join(os.homedir(), ".cache/ms-playwright/webkit-2370/pw_run.sh");
const ENGINES = [
    ["chromium", process.env.HANDY_PW_CHROMIUM || null],
    ["webkit", process.env.HANDY_PW_WEBKIT || (fs.existsSync(WEBKIT_GUESS) ? WEBKIT_GUESS : null)],
];

let failures = 0;
function check(engine, name, ok, detail) {
    console.log(`${ok ? "ok" : "FAIL"} ${engine}: ${name}${ok ? "" : " - " + JSON.stringify(detail)}`);
    if (!ok) failures++;
}

/** In the page: helpers to load a pair's current main and parse its next page. */
function helpers() {
    window.fx = {
        load(pair) {
            const cur = document.querySelector(`template[data-pair="${pair}"][data-part="cur"]`).innerHTML;
            document.getElementById("stage").innerHTML = '<main id="m">' + cur + "</main>";
        },
        next(pair) {
            const text = document.querySelector(`script[data-pair="${pair}"][data-part="next"]`).textContent;
            return new DOMParser().parseFromString(text, "text/html");
        },
        ctx(over) {
            return Object.assign({
                doc: document,
                focused: document.activeElement,
                flashes: [],
                formOf: (el) => el.closest("form"),
                saved: () => false,
                again: () => false,
                busy: () => false,
                busySubmitter: () => false,
                serverOpen: () => false,
                unchangedSince: () => true,
            }, over || {});
        },
    };
}

async function controls(engine, page) {
    await page.evaluate(() => fx.load("controls"));
    // A parent's real changes: the dirty flags are the engine's own.
    await page.check("#saved input[name=sw]");
    await page.fill("#saved input[name=t]", "typed");
    await page.selectOption("#saved select", "b");
    await page.fill("#other input[name=t]", "typed");
    await page.selectOption("#other select", "b");
    await page.check("#other input[value='2']");
    await page.evaluate(() => document.activeElement && document.activeElement.blur());
    const got = await page.evaluate(() => {
        const saved = document.getElementById("saved");
        const other = document.getElementById("other");
        const next = fx.next("controls");
        handySwap.api.morph(document.getElementById("m"), next.querySelector("main"), fx.ctx({ saved: (f) => f === saved }));
        return {
            same: document.getElementById("saved") === saved && document.getElementById("other") === other,
            savedSw: saved.querySelector("[name=sw]").checked,
            savedText: saved.querySelector("[name=t]").value,
            savedSel: saved.querySelector("select").value,
            otherSw: other.querySelector("[name=sw]").checked,
            otherSwDefault: other.querySelector("[name=sw]").defaultChecked,
            otherText: other.querySelector("[name=t]").value,
            otherTextDefault: other.querySelector("[name=t]").defaultValue,
            otherSel: other.querySelector("select").value,
            otherOptions: other.querySelector("select").options.length,
            otherRadio: other.querySelector("input[name=r]:checked").value,
        };
    });
    check(engine, "the forms keep their nodes", got.same, got);
    check(engine, "the saved form takes the server's (a refused switch flips back)",
        got.savedSw === false && got.savedText === "server" && got.savedSel === "a", got);
    check(engine, "an untouched switch elsewhere follows the server", got.otherSw === true && got.otherSwDefault === true, got);
    check(engine, "a typed field is kept, its default is the server's", got.otherText === "typed" && got.otherTextDefault === "server", got);
    check(engine, "a picked option is kept over a new selected option", got.otherSel === "b" && got.otherOptions === 3, got);
    check(engine, "a picked radio is kept", got.otherRadio === "2", got);
}

async function noscript(engine, page) {
    const got = await page.evaluate(() => {
        fx.load("noscript");
        const one = document.getElementById("one");
        const keptNoscript = one.querySelector("noscript");
        const next = fx.next("noscript");
        const parsedButton = !!next.querySelector("#two noscript button");
        handySwap.api.morph(document.getElementById("m"), next.querySelector("main"), fx.ctx());
        const two = document.getElementById("two");
        return {
            parsedButton,
            oneSame: document.getElementById("one") === one && one.querySelector("noscript") === keptNoscript,
            oneNoscriptEmpty: keptNoscript.childNodes.length === 0,
            twoNoscriptEmpty: two.querySelector("noscript").childNodes.length === 0,
            twoElements: Array.prototype.map.call(two.elements, (e) => e.name || e.tagName),
            oneElements: one.elements.length,
        };
    });
    check(engine, "DOMParser parses with scripting off (<noscript> holds a real button)", got.parsedButton, got);
    check(engine, "the current <noscript> is kept as it is", got.oneSame && got.oneNoscriptEmpty, got);
    check(engine, "an imported form's <noscript> is emptied: form.elements holds only its fields",
        got.twoNoscriptEmpty && JSON.stringify(got.twoElements) === '["b","c"]' && got.oneElements === 1, got);
}

async function sheet(engine, page) {
    const got = await page.evaluate(() => {
        fx.load("sheet");
        const main = document.getElementById("m");
        const api = handySwap.api;
        const openNow = document.getElementById("open-now");
        const restrictions = document.querySelector("#restrictions form");
        const sheetChain = api.anchorChain(document, main, openNow);
        const formChain = api.anchorChain(document, main, restrictions);
        const before = restrictions.getBoundingClientRect().top;
        const sheetEl = document.getElementById("setup-sheet");
        const ctx = fx.ctx({ serverOpen: (d) => d === sheetEl });
        api.morph(main, fx.next("sheet").querySelector("main"), ctx);
        const lockAgain = document.querySelector("#setup-sheet form");
        return {
            closed: !sheetEl.open,
            notDrawn: lockAgain.getClientRects().length === 0,
            notDrawnBySwap: !handySwap.api.drawn(lockAgain),
            summaryDrawn: sheetEl.querySelector("summary").getClientRects().length > 0,
            sheetDelta: api.anchorDelta(document, sheetChain),
            formSame: document.querySelector("#restrictions form") === restrictions,
            formDelta: api.anchorDelta(document, formChain),
            moved: restrictions.getBoundingClientRect().top - before,
            warning: document.querySelector("[data-warning]").getBoundingClientRect().height,
            flash: ctx.flashes.length === 1 && ctx.flashes[0].textContent === "Sent.",
        };
    });
    // The engine's own boxes for a closed <details>' content (kept for the record: both engines give some) and
    // swap.js's verdict, which counts the closed <details> itself.
    console.log(`   ${engine}: a closed <details>' form has client rects: ${!got.notDrawn}`);
    check(engine, "the sheet the button sat in closes; swap.js counts the form in it as not drawn, its row as drawn",
        got.closed && got.notDrawnBySwap && got.summaryDrawn, got);
    check(engine, "the sheet's row anchors: nothing above it changed, so no scroll", got.sheetDelta === 0, got);
    check(engine, "a warning before a form keeps the form's node, and swap.js scrolls by exactly its move",
        got.formSame && got.formDelta === got.moved && got.moved > 0 && got.warning > 0, got);
    check(engine, "the flash is imported fresh", got.flash, got);
}

async function hairlines(engine, page) {
    await page.evaluate(() => {
        document.getElementById("stage").innerHTML = "";
        window.scrollTo(0, 0);
        const g = document.getElementById("g");
        let rows = "";
        for (let i = 0; i < 12; i++) {
            rows += '<div class="row"><span class="row-label">Row ' + i +
                (i % 2 ? '<span class="row-sub">Sub</span>' : "") + "</span></div>";
        }
        g.innerHTML = rows;
    });
    const box = await page.evaluate(() => {
        const r = document.getElementById("g").getBoundingClientRect();
        return { top: r.top + window.scrollY, height: r.height };
    });
    const results = [];
    for (let k = 0; k < 24; k++) {
        await page.evaluate((h) => { document.getElementById("sp").style.height = h + "px"; }, k / 24);
        // Inside the group only (its ground is white, the page's #F2F2F7 would read as a line).
        const png = await page.screenshot({ clip: { x: 300, y: box.top + 1, width: 2, height: Math.floor(box.height) - 2 } });
        const runs = await page.evaluate(async (b64) => {
            const img = new Image();
            img.src = "data:image/png;base64," + b64;
            await img.decode();
            const c = document.createElement("canvas");
            c.width = img.width;
            c.height = img.height;
            const g = c.getContext("2d");
            g.drawImage(img, 0, 0);
            const data = g.getImageData(0, 0, 1, img.height).data;
            const out = [];
            let run = 0;
            for (let y = 0; y < img.height; y++) {
                const dark = data[y * 4] < 248;
                if (dark) run++;
                else if (run) {
                    out.push(run);
                    run = 0;
                }
            }
            if (run) out.push(run);
            return out;
        }, png.toString("base64"));
        results.push(runs);
    }
    const counts = results.map((r) => r.length);
    const thickest = Math.max(...results.flat());
    check(engine, "every hairline drawn at each of 24 fractional offsets (11 of 11)",
        counts.every((n) => n === 11), counts);
    check(engine, "no line thicker than 2 device px (no border and scaled line both)", thickest <= 2, thickest);
}

(async () => {
    let ran = 0;
    for (const [engine, executablePath] of ENGINES) {
        let browser;
        try {
            browser = await pw[engine].launch(executablePath ? { executablePath } : {});
        } catch (e) {
            console.log(`skipped ${engine}: ${String(e.message || e).split("\n")[0]}`);
            continue;
        }
        ran++;
        const context = await browser.newContext({
            viewport: { width: 390, height: 844 },
            deviceScaleFactor: 3,
            isMobile: true,
            hasTouch: true,
            colorScheme: "light",
        });
        const page = await context.newPage();
        page.on("pageerror", (e) => check(engine, "no page error", false, String(e)));
        await page.goto(FIXTURE);
        await page.evaluate(helpers);
        const ready = await page.evaluate(() => !!(window.handySwap && window.handySwap.api));
        check(engine, "swap.js installed with its api", ready, null);
        if (ready) {
            await controls(engine, page);
            await noscript(engine, page);
            await sheet(engine, page);
            await hairlines(engine, page);
        }
        await browser.close();
    }
    if (!ran) console.log("skipped: no browser could be launched");
    console.log(failures ? `${failures} failed` : "all passed");
    process.exit(failures ? 1 : 0);
})().catch((e) => {
    console.error(e);
    process.exit(1);
});
