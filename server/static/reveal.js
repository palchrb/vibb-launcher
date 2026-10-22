// A secret shown once in a POST's answer, loaded only on that answer: Lock Screen & PINs' "Show
// Child PIN…" (design 59 §2.2, qa-59-step2-code S2P-5/6) and Account's Add Parent one-time password
// (design 62 §3). The secret is never in an address, so the answer can't be a redirect; instead:
// - its history entry is turned into a plain GET of the same address at once, so a reload (or Back
//   and Forward) loads the page without the secret and never posts the password again. Without JS
//   the browser asks before posting it again, as for any form's answer;
// - the secret is removed when the page is left (pagehide; also when it is hidden), so the
//   back-forward cache keeps none. Without JS the CSS hides it anyway (style.css: .pin-digits
//   after a minute, .secret-once after 5 minutes);
// - a Copy button ([data-copy-secret], hidden without JS) copies the one-time password.
// ES5, no framework. Tested with node (jstest/reveal.test.js).
(function (root) {
    "use strict";

    var SHOWN = ".pin-digits, .pin-inline, .secret-once";

    function forget(doc) {
        var shown = doc.querySelectorAll(SHOWN);
        for (var i = 0; i < shown.length; i++) shown[i].textContent = "";
        var copy = doc.querySelectorAll("[data-copy-secret]");
        for (var j = 0; j < copy.length; j++) copy[j].hidden = true;
    }

    // The text of the page's .secret-once (its blocks are spans: no spaces) to the clipboard;
    // calls done(true) when it was copied.
    function copySecret(win, done) {
        var doc = win.document;
        var secret = doc.querySelector(".secret-once");
        var text = secret ? secret.textContent : "";
        if (!text) return done(false);
        var clip = win.navigator && win.navigator.clipboard;
        if (clip && clip.writeText) {
            clip.writeText(text).then(function () { done(true); }, function () { done(selectAndCopy(win, secret)); });
            return;
        }
        done(selectAndCopy(win, secret));
    }

    // Without the clipboard API (plain http, an older browser): select it and copy; at worst it
    // stays selected for a long-press.
    function selectAndCopy(win, node) {
        try {
            var range = win.document.createRange();
            range.selectNodeContents(node);
            var selection = win.getSelection();
            selection.removeAllRanges();
            selection.addRange(range);
            return !!win.document.execCommand("copy");
        } catch (e) {
            return false;
        }
    }

    function install(win) {
        var doc = win.document;
        try {
            if (win.history && win.history.replaceState) {
                win.history.replaceState(null, "", win.location.href);
            }
        } catch (e) {}
        win.addEventListener("pagehide", function () { forget(doc); });
        doc.addEventListener("visibilitychange", function () {
            if (doc.visibilityState === "hidden") forget(doc);
        });
        var buttons = doc.querySelectorAll("[data-copy-secret]");
        for (var i = 0; i < buttons.length; i++) {
            (function (button) {
                button.hidden = false;
                button.addEventListener("click", function () {
                    copySecret(win, function (copied) {
                        button.textContent = copied ? "Copied" : "Select and copy it";
                    });
                });
            })(buttons[i]);
        }
    }

    var api = { install: install, forget: forget, copySecret: copySecret };
    if (typeof module !== "undefined" && module.exports) {
        module.exports = api;
    } else if (root.document) {
        install(root);
    }
})(this);
