// Backups (code QA L1 of security round 2, 2026-11-18): a backup's Download answers with the zip and the page stays
// where it is, so its form is cleared right after it is sent - the password and a used passkey assertion never sit in
// it (a second Download would send a spent assertion again, counted as a wrong try), and its <details> closes. A
// submit another script stopped (passkey.js asks for the passkey first) is left alone.
(function (root) {
    "use strict";
    var doc = root.document;
    if (!doc || !doc.addEventListener) return;
    doc.addEventListener("submit", function (e) {
        var form = e && e.target;
        if (!form || !form.hasAttribute || !form.hasAttribute("data-download")) return;
        root.setTimeout(function () {
            if (e.defaultPrevented) return;
            var assertion = form.querySelector("input[name=\"assertion\"]");
            if (assertion) assertion.value = "";
            var password = form.querySelector("input[name=\"password\"]");
            if (password) {
                password.value = "";
                if (!password.hasAttribute("hidden")) password.required = true;
            }
            var details = form.closest ? form.closest("details") : null;
            if (details) details.open = false;
        }, 0);
    });
})(window);
