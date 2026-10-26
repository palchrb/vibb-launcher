// The rule editor's Type pre-sets "Calls with contacts" (school: off, every other kind: on); the
// switch can still be changed after. Without JS the parent sets the switch. Its own file, because
// the admin CSP allows no inline script (security review L5, design 22 S7). Listens on the
// document, so it works however late it loads. ES5. Tested with node (jstest/rule-kind.test.js).
(function (root) {
    "use strict";

    function install(doc) {
        doc.addEventListener("change", function (e) {
            var el = e.target;
            if (!el || !el.form || !el.hasAttribute || !el.hasAttribute("data-presets-calls")) return;
            var calls = el.form.querySelector("[name=calls_allowed]");
            if (calls) calls.checked = el.value !== "school";
        });
    }

    var api = { install: install };
    if (typeof module !== "undefined" && module.exports) {
        module.exports = api;
    } else if (root.document) {
        install(root.document);
    }
})(this);
