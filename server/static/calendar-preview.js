// The title rule editor's preview (design 69 §3.3): 400 ms after the last change in the rule form, its fields go to
// the preview route (data-preview-url) with fragment=1, and the answer - the preview fragment alone - replaces
// [data-preview-result]. Never the form itself, so the caret stays and nothing jumps; a newer change's answer wins
// over an older one. Nothing is saved. Without JS the form's Preview button answers with the whole page. Its own
// file: the admin CSP allows no inline script. ES5 (fetch). Tested with node (jstest/calendar-preview.test.js).
(function (root) {
    "use strict";

    var DELAY_MS = 400;

    function body(form) {
        var parts = [];
        var fields = form.elements || [];
        for (var i = 0; i < fields.length; i++) {
            var f = fields[i];
            if (!f.name || f.disabled) continue;
            if ((f.type === "checkbox" || f.type === "radio") && !f.checked) continue;
            parts.push(encodeURIComponent(f.name) + "=" + encodeURIComponent(f.value));
        }
        parts.push("fragment=1");
        return parts.join("&");
    }

    function install(doc, fetchFn, timers) {
        var form = doc.getElementById("rule-form");
        if (!form || !form.getAttribute) return;
        var url = form.getAttribute("data-preview-url");
        var target = doc.querySelector("[data-preview-result]");
        if (!url || !target) return;
        var pending = null;
        var asked = 0;

        function ask() {
            pending = null;
            var mine = ++asked;
            fetchFn(url, {
                method: "POST",
                credentials: "same-origin",
                headers: { "Content-Type": "application/x-www-form-urlencoded" },
                body: body(form),
            }).then(function (res) {
                return res.ok ? res.text() : null;
            }).then(function (html) {
                if (html !== null && mine === asked) target.innerHTML = html;
            }, function () {});
        }

        function changed(e) {
            if (!e.target || e.target.form !== form) return;
            if (pending !== null) timers.clear(pending);
            pending = timers.set(ask, DELAY_MS);
        }
        doc.addEventListener("input", changed);
        doc.addEventListener("change", changed);
    }

    var api = { install: install, body: body, DELAY_MS: DELAY_MS };
    if (typeof module !== "undefined" && module.exports) {
        module.exports = api;
    } else if (root.document && root.fetch) {
        var start = function () {
            install(root.document, root.fetch.bind(root), {
                set: function (fn, ms) { return root.setTimeout(fn, ms); },
                clear: function (id) { root.clearTimeout(id); },
            });
        };
        if (root.document.readyState === "loading") {
            root.document.addEventListener("DOMContentLoaded", start);
        } else {
            start();
        }
    }
})(this);
