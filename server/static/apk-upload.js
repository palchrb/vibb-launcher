// A manual APK upload (an app's page, Upload a new version; upstream review 2026-11-20): a file over the server's
// limit (`data-max-bytes`, tracked_apps::MAX_UPLOAD_BYTES) is stopped before it leaves the page, with the server's
// own words (`data-w-too-big`) as the field's validity message - the browser shows it at the field and never posts
// the form, so nothing moves on the page. The server refuses such a file anyway; this spares sending gigabytes only to
// have the connection cut. Without JS the server's answer lands on the Upload group. ES5, no framework. Tested with
// node (jstest/apk-upload.test.js).
(function (root) {
    "use strict";

    var FIELD = "input[type=file][data-max-bytes]";

    /** Sets `input`'s validity from its chosen file's size; true when the file is over the limit. */
    function check(input) {
        var max = Number(input.getAttribute("data-max-bytes")) || 0;
        var file = input.files && input.files[0];
        var over = !!(max > 0 && file && file.size > max);
        input.setCustomValidity(over ? input.getAttribute("data-w-too-big") || "too_big" : "");
        return over;
    }

    function install(win) {
        // On the document: a refresh in place (static/swap.js) may bring a new field.
        win.document.addEventListener("change", function (e) {
            var input = e && e.target;
            if (!input || typeof input.matches !== "function" || !input.matches(FIELD)) return;
            if (check(input) && typeof input.reportValidity === "function") input.reportValidity();
        });
    }

    if (typeof module === "object" && module.exports) {
        module.exports = { install: install, check: check };
    } else {
        install(root);
    }
})(this);
