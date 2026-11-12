// Locate's map at full screen (design 85 §4.8, the user's decision of 2026-11-12), on the Alle view
// and the one-phone view: ⤢ on the map box (`data-map-full`) makes the box fill the screen by CSS
// (`.map-full`, fixed under the status bar's strip, over the band and the tab bar; not the
// Fullscreen API, which iPhone Safari lacks for elements), ✕ or Escape closes it. The page behind
// doesn't scroll meanwhile (`body.map-fullscreen`). Leaflet re-measures the map on the window's
// `resize` (its `trackResize`: `invalidateSize`, the centre kept), so a resize is sent after each
// change and the map keeps its view. Without JS both buttons stay hidden. ES5.
// Run its tests: node --test jstest/map-full.test.js
(function (root) {
    "use strict";

    function install(win) {
        var doc = win.document;
        var box = doc.querySelector("[data-map-full]");
        if (!box) return null;
        var open = box.querySelector("[data-full-open]");
        var close = box.querySelector("[data-full-close]");
        if (!open || !close) return null;
        open.removeAttribute("hidden");

        function isOpen() {
            return box.classList.contains("map-full");
        }

        function set(on) {
            if (isOpen() === on) return;
            if (on) {
                box.classList.add("map-full");
                doc.body.classList.add("map-fullscreen");
                open.setAttribute("hidden", "");
                close.removeAttribute("hidden");
            } else {
                box.classList.remove("map-full");
                doc.body.classList.remove("map-fullscreen");
                close.setAttribute("hidden", "");
                open.removeAttribute("hidden");
            }
            try {
                win.dispatchEvent(new win.Event("resize"));
            } catch (e) {}
            (on ? close : open).focus({ preventScroll: true });
        }

        open.addEventListener("click", function () { set(true); });
        close.addEventListener("click", function () { set(false); });
        doc.addEventListener("keydown", function (e) {
            if ((e.key === "Escape" || e.key === "Esc") && isOpen()) {
                e.preventDefault();
                set(false);
            }
        });
        return { set: set, isOpen: isOpen };
    }

    var api = { install: install };
    if (typeof module !== "undefined" && module.exports) {
        module.exports = api;
    } else if (root.document) {
        install(root);
    }
})(this);
