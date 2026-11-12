// Locate's map at full screen (design 85 §4.8, the user's decision of 2026-11-12), on the Alle view
// and the one-phone view: ⤢ on the map box (`data-map-full`) makes the box fill the screen by CSS
// (`.map-full`, absolute in the body under the status bar's strip, over the band and the tab bar; not the
// Fullscreen API, which iPhone Safari lacks for elements), ✕ or Escape closes it. The page behind
// doesn't scroll meanwhile (`body.map-fullscreen`). Leaflet re-measures the map on the window's
// `resize` (its `trackResize`: `invalidateSize`, the centre kept), so a resize is sent after each
// change and the map keeps its view. Escape closes an open bubble first, then full screen; Tab goes
// round inside the box while it covers the page. Without JS both buttons stay hidden. ES5.
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
        // What Tab moves between inside the box: links, buttons, Leaflet's markers; none hidden.
        function stops() {
            var all = box.querySelectorAll("a[href], button, [tabindex]");
            var out = [];
            for (var i = 0; i < all.length; i++) {
                var el = all[i];
                if (el.getAttribute("tabindex") === "-1" || (el.closest && el.closest("[hidden]"))) continue;
                out.push(el);
            }
            return out;
        }

        doc.addEventListener("keydown", function (e) {
            if (!isOpen() || e.defaultPrevented) return;
            if (e.key === "Escape" || e.key === "Esc") {
                e.preventDefault();
                // An open bubble closes first (Leaflet closes it itself only with the focus in the
                // map); the next Escape leaves full screen.
                var popupClose = box.querySelector(".leaflet-popup-close-button");
                if (popupClose) {
                    popupClose.click();
                    return;
                }
                set(false);
            } else if (e.key === "Tab") {
                // The page behind is covered: Tab goes round inside the box.
                var list = stops();
                if (!list.length) return;
                var first = list[0];
                var last = list[list.length - 1];
                var active = doc.activeElement;
                var inside = active && box.contains(active);
                if (!inside || (e.shiftKey && active === first) || (!e.shiftKey && active === last)) {
                    e.preventDefault();
                    (e.shiftKey ? last : first).focus({ preventScroll: true });
                }
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
