// A zone's page (design 64 §3.5, templates/device_zone.html): the map over the form. Without this
// script the form is the whole feature; with it a tap on the map places the centre (Leaflet's
// `click`, which a drag never fires; `doubleClickZoom` off so a second tap doesn't zoom), the
// centre's marker drags, the radius slider and its number field redraw the circle as they move,
// and "Use Phone's Location" moves the centre to the phone's newest fix. Every change writes the
// latitude and longitude fields (6 decimals), so Save posts what the map shows. Long-press isn't
// used: a tap is enough, and iOS's long-press opens its own menu.
//
// What the page knows comes in data- attributes (`data-zone-map`: the centre, the radius, the
// slider's range; `data-use-phone`: the newest fix); nothing is read as HTML. ES5. Run its tests:
// node --test jstest/zones.test.js
(function (root) {
    "use strict";

    // The circle's colour when the page names none (`--map-zone`, style.css).
    var ZONE = "#2F6F62";

    /** The radius typed or slid, a whole number within [min, max]; `null` for no number. */
    function clampRadius(value, min, max) {
        var text = String(value === undefined || value === null ? "" : value).trim();
        if (text === "") return null;
        var n = Math.round(Number(text));
        if (!isFinite(n)) return null;
        if (n < min) return min;
        if (n > max) return max;
        return n;
    }

    /** A coordinate as the fields hold it: 6 decimals (about 0.1 m), as the server stores it. */
    function fixed6(n) {
        return (Math.round(n * 1e6) / 1e6).toFixed(6);
    }

    /** A longitude back on Earth's -180..180 (Leaflet's click gives 190 after a pan east). */
    function wrapLng(lng) {
        return ((lng + 180) % 360 + 360) % 360 - 180;
    }

    /** A typed coordinate (a decimal comma reads as a point, as on the server), or `null`. */
    function readCoord(text, limit) {
        var t = String(text === undefined || text === null ? "" : text).trim();
        if (t === "") return null;
        if (t.indexOf(".") < 0) t = t.replace(",", ".");
        var n = Number(t);
        return isFinite(n) && Math.abs(n) <= limit ? n : null;
    }

    /** Writes a centre into the two fields. */
    function place(fields, lat, lng) {
        fields.lat.value = fixed6(lat);
        fields.lng.value = fixed6(wrapLng(lng));
    }

    function zoneColour(win) {
        try {
            var value = win.getComputedStyle(win.document.documentElement).getPropertyValue("--map-zone");
            if (value && value.trim()) return value.trim();
        } catch (e) {}
        return ZONE;
    }

    function install(win, L) {
        var doc = win.document;
        var box = doc.querySelector("[data-zone-map]");
        if (!box || !L) return null;
        var fields = {
            lat: doc.querySelector("[data-zone-lat]"),
            lng: doc.querySelector("[data-zone-lng]"),
            radius: doc.querySelector("[data-zone-radius]")
        };
        if (!fields.lat || !fields.lng || !fields.radius) return null;
        var slider = doc.querySelector("[data-zone-slider]");
        var sliderRow = doc.querySelector("[data-zone-slider-row]");
        var usePhone = doc.querySelector("[data-use-phone]");
        var min = parseInt(box.getAttribute("data-min"), 10) || 100;
        var max = parseInt(box.getAttribute("data-max"), 10) || 2000;
        if (sliderRow) sliderRow.removeAttribute("hidden");
        if (usePhone) usePhone.removeAttribute("hidden");

        var map = L.map(box.querySelector("#map"), { doubleClickZoom: false });
        // OSM's tiles with our origin as the Referer (design 33), as on the Locate page.
        L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
            maxZoom: 19,
            referrerPolicy: 'strict-origin-when-cross-origin',
            attribution: '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
        }).addTo(map);
        L.Icon.Default.imagePath = '/static/leaflet/images/';
        var colour = zoneColour(win);
        var circle = null;
        var marker = null;

        function centre() {
            var lat = readCoord(fields.lat.value, 90);
            var lng = readCoord(fields.lng.value, 180);
            return lat === null || lng === null ? null : [lat, lng];
        }

        function radius() {
            var r = clampRadius(fields.radius.value, min, max);
            return r === null ? min : r;
        }

        // The circle and its marker where the fields say; `fit` brings the whole circle into view.
        function redraw(fit) {
            var at = centre();
            if (!at) {
                if (circle) { map.removeLayer(circle); map.removeLayer(marker); }
                circle = null;
                marker = null;
                return;
            }
            if (!circle) {
                circle = L.circle(at, { radius: radius(), color: colour, weight: 2, fillColor: colour, fillOpacity: 0.12 }).addTo(map);
                marker = L.marker(at, { draggable: true }).addTo(map);
                marker.on("drag", function () { circle.setLatLng(marker.getLatLng()); });
                marker.on("dragend", function () {
                    var p = marker.getLatLng();
                    place(fields, p.lat, p.lng);
                    redraw(false);
                });
            } else {
                circle.setLatLng(at);
                circle.setRadius(radius());
                marker.setLatLng(at);
            }
            if (fit) map.fitBounds(circle.getBounds(), { maxZoom: 17, padding: [24, 24] });
        }

        map.on("click", function (e) {
            place(fields, e.latlng.lat, e.latlng.lng);
            redraw(false);
        });
        if (slider) {
            slider.addEventListener("input", function () {
                fields.radius.value = slider.value;
                redraw(false);
            });
        }
        // While typing the circle follows what can be drawn; leaving the field puts the number in
        // range (the server refuses one outside it).
        fields.radius.addEventListener("input", function () {
            var r = clampRadius(fields.radius.value, min, max);
            if (r !== null && slider) slider.value = String(r);
            redraw(false);
        });
        fields.radius.addEventListener("change", function () {
            var r = clampRadius(fields.radius.value, min, max);
            if (r !== null) {
                fields.radius.value = String(r);
                if (slider) slider.value = String(r);
            }
            redraw(false);
        });
        fields.lat.addEventListener("change", function () { redraw(true); });
        fields.lng.addEventListener("change", function () { redraw(true); });
        if (usePhone) {
            usePhone.addEventListener("click", function () {
                var lat = readCoord(usePhone.getAttribute("data-lat"), 90);
                var lng = readCoord(usePhone.getAttribute("data-lng"), 180);
                if (lat === null || lng === null) return;
                place(fields, lat, lng);
                redraw(true);
            });
        }

        if (centre()) {
            redraw(true);
        } else {
            var phoneLat = readCoord(box.getAttribute("data-phone-lat"), 90);
            var phoneLng = readCoord(box.getAttribute("data-phone-lng"), 180);
            if (phoneLat !== null && phoneLng !== null) {
                map.setView([phoneLat, phoneLng], 15);
            } else {
                map.setView([20, 0], 2);
            }
        }
        return { map: map, redraw: redraw };
    }

    var api = { install: install, clampRadius: clampRadius, fixed6: fixed6, wrapLng: wrapLng, readCoord: readCoord, place: place, zoneColour: zoneColour };
    if (typeof module !== "undefined" && module.exports) {
        module.exports = api;
    } else if (root.document) {
        install(root, root.L);
    }
})(this);
