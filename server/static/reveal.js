// Lock Screen & PINs' answer to "Show Child PIN…" (design 59 §2.2, qa-59-step2-code S2P-5/6), loaded
// only on that answer. The PIN is never in an address, so the answer can't be a redirect; instead:
// - its history entry is turned into a plain GET of the same address at once, so a reload (or Back
//   and Forward) loads the page without the PIN and never posts the password again. Without JS the
//   browser asks before posting it again, as for any form's answer;
// - the digits are removed when the page is left (pagehide; also when it is hidden), so the
//   back-forward cache keeps no PIN. Without JS the CSS hides them after a minute anyway
//   (style.css, .pin-digits).
// ES5, no framework. Tested with node (jstest/reveal.test.js).
(function (root) {
    "use strict";

    function forget(doc) {
        var shown = doc.querySelectorAll(".pin-digits, .pin-inline");
        for (var i = 0; i < shown.length; i++) shown[i].textContent = "";
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
    }

    var api = { install: install, forget: forget };
    if (typeof module !== "undefined" && module.exports) {
        module.exports = api;
    } else if (root.document) {
        install(root);
    }
})(this);
