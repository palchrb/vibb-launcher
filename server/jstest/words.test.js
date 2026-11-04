// The moved scripts have no words of their own (design 70 §5.3): a script's words come from the page
// (`data-w-*`, `partials/js_words.html`), set with textContent. Outside comments, a string (quoted or a template
// literal) that starts upper- then lower-case, or has a letter and a space, fails unless it is on that file's list of
// non-words (diagnostics, class names). Each slice-3 PR adds its scripts. Run: node --test jstest/words.test.js
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const fs = require("node:fs");
const path = require("node:path");

const MOVED = {
    // Selectors.
    "reconnect.js": ["input, textarea, select"],
    // The page-timing line is a diagnostic, shown only after ?timing=1 (design 70 N4).
    "nav-timing.js": [
        "assets ",
        "tap ",
        "wait ",
        "dns ",
        "connect ",
        "ttfb ",
        "download ",
        "dom ",
        "load ",
        "back-forward cache: no request",
        "[timing] ",
    ],
    "reveal.js": [".pin-digits, .pin-inline, .secret-once"],
    // 3b: Apps (install-now.js's sub-lines come worded in its JSON).
    "app-display.js": [],
    "install-now.js": [],
    // 3c: Locate (locate-result.json's text comes worded): a key's name, and OpenStreetMap's attribution - its
    // licence's credit line, HTML Leaflet renders as given.
    "locate.js": [
        "Enter",
        '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
    ],
    // 3d: Music (the status blocks come worded from /music/cards): a selector.
    "music-cards.js": [".music-card, .card"],
    // 3e: Settings (subscriptions.json's rows come worded): an API's name, a media query, an error's message.
    "notifications.js": ["PushManager", "(display-mode: standalone)", "status ", "Content-Type"],
    // Push never lost silently (2026-11-04): the banner's words come worded in the sync's answer; an API's name, a
    // header, an error's message, class names.
    "register-sw.js": ["PushManager", "Content-Type", "status ", "attention push-banner"],
    // Design 74: DOM exception names, a header and selectors.
    "passkey.js": [
        "NotAllowedError",
        "AbortError",
        "InvalidStateError",
        "Content-Type",
        "input[name=\"current_password\"], input[name=\"password\"]",
        "input[required], select[required], textarea[required]",
    ],
    // 3e-b: the app lock (the locked page's tab title is the name, design 74 §6.5) and the title rule preview (a
    // header).
    "app-lock.js": ["Vibb"],
    "calendar-preview.js": ["Content-Type"],
    // The contact photo's crop: key names.
    "photo-crop.js": ["Escape", "Esc", "Tab"],
};

/** Every string literal outside comments, in order. */
function literals(src) {
    const out = [];
    let i = 0;
    while (i < src.length) {
        const c = src[i];
        if (c === "/" && src[i + 1] === "/") {
            i = src.indexOf("\n", i);
            if (i < 0) break;
        } else if (c === "/" && src[i + 1] === "*") {
            i = src.indexOf("*/", i + 2) + 2;
        } else if (c === '"' || c === "'" || c === "`") {
            let j = i + 1;
            let text = "";
            while (j < src.length && src[j] !== c) {
                if (src[j] === "\\") {
                    text += src[j + 1];
                    j += 2;
                } else {
                    text += src[j++];
                }
            }
            out.push(text);
            i = j + 1;
        } else if (c === "/" && /[(,=:[!&|?{};]\s*$/.test(src.slice(Math.max(0, i - 20), i))) {
            // A regular expression literal: skip it whole.
            let j = i + 1;
            while (j < src.length && src[j] !== "/" && src[j] !== "\n") j += src[j] === "\\" ? 2 : 1;
            i = j + 1;
        } else {
            i++;
        }
    }
    return out;
}

function looksLikeWords(text) {
    return /^[A-Z][a-z]/.test(text) || (/\p{L}/u.test(text) && / /.test(text));
}

for (const [file, nonWords] of Object.entries(MOVED)) {
    test(`${file} has no words of its own`, () => {
        const src = fs.readFileSync(path.join(__dirname, "..", "static", file), "utf8");
        const found = literals(src).filter(
            (text) => looksLikeWords(text) && text !== "use strict" && !nonWords.includes(text),
        );
        assert.deepStrictEqual(found, []);
    });
}

test("the scan finds a word and skips comments", () => {
    const found = literals('// "Not this"\nvar a = "Copied"; /* "nor this" */ var b = \'two words\'; var c = "data-w-on"; var d = `Try ${n} again`;');
    assert.deepStrictEqual(found.filter(looksLikeWords), ["Copied", "two words", "Try ${n} again"]);
});
