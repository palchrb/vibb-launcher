// A contact photo's crop (Device › Calls, templates/device_calls.html): on Home a contact is a 76 dp circle, and
// in a phone photo the face is small, so the parent picks the part the circle shows (the user, 2026-11-03).
// Choosing a file for a contact opens a dialog instead of uploading at once: the photo in a square frame with the
// phone's circle over it (the phone's circle is the square's inscribed one, `centerCrop`); a drag moves it, a pinch
// or the slider (or the wheel, + and -) zooms, the arrow keys move it. Zoom 1 is "cover": the square spans the
// photo's shorter side, so the circle never shows anything outside the photo, and it can't go below that. Save
// draws the chosen square (at most the server's `photos::MAX_SIDE`, `data-side`) on a white canvas, encodes it as
// JPEG, puts that file into the form's own file input (DataTransfer) and submits the form - the same route and
// the same answer as without this script: the redirect to the contact's row, kept on the screen where it was by
// static/scroll-restore.js's hooked form.submit() (a save never jumps to the top), or the 400 page with the
// reason under the photo. Cancel empties the input and uploads nothing. "Adjust crop" (`data-photo-adjust`, shown
// only here) opens the stored 512 px square the same way, to zoom in and move.
//
// The picture: decoded by an <img> (with decode(), off the main thread), which applies the EXIF orientation in
// Chrome 81+, Firefox 77+ and Safari 13.1+ (iOS 13.4 is when drawImage began to draw photos upright), then drawn
// once into a working canvas of at most 2048 px on the shorter side, so a 10 MB photo doesn't stall every move.
// createImageBitmap's `imageOrientation` isn't used: for a Blob in WebKit it is not documented to follow EXIF.
// A file over the server's limit (`data-max-bytes`) or one the browser can't decode goes up as it is, and the
// server says why. Without JS, or without canvas.toBlob or a settable file input, nothing here runs and the
// input autosaves as before (its `data-autosave`, which this removes only where it takes over).
//
// The dialog is the page's `[data-crop-dialog]`, its words the template's. A focus trap, Escape cancels, the
// slider is labelled. ES5. Run its tests: node --test jstest/photo-crop.test.js
(function (root) {
    "use strict";

    // How far in the slider and a pinch go: a quarter of the shorter side across.
    var MAX_ZOOM = 4;
    // The working copy's bounds (shorter side, longer side), and an arrow key's move in CSS px.
    var WORK_SHORT = 2048;
    var WORK_LONG = 4096;
    var STEP = 10;
    var QUALITY = 0.92;
    var JPEG = "image/jpeg";

    function clamp(n, lo, hi) {
        return n < lo ? lo : (n > hi ? hi : n);
    }

    /**
     * `view` ({zoom, cx, cy}: the zoom over cover and the square's centre in the picture's pixels) made valid
     * for a `w`×`h` picture: the zoom within [1, maxZoom], the centre where the square stays inside it.
     */
    function clampView(view, w, h, maxZoom) {
        var zoom = Number(view.zoom);
        if (!isFinite(zoom)) zoom = 1;
        zoom = clamp(zoom, 1, maxZoom || MAX_ZOOM);
        var half = Math.min(w, h) / zoom / 2;
        var cx = isFinite(view.cx) ? view.cx : w / 2;
        var cy = isFinite(view.cy) ? view.cy : h / 2;
        return { zoom: zoom, cx: clamp(cx, half, w - half), cy: clamp(cy, half, h - half) };
    }

    /** The first view: cover, centred - what the server's own centre crop gives. */
    function centred(w, h) {
        return { zoom: 1, cx: w / 2, cy: h / 2 };
    }

    /** The square the circle's frame shows: {x, y, side} in the picture's pixels, always inside it. */
    function sourceRect(view, w, h) {
        var v = clampView(view, w, h, Infinity);
        var side = Math.min(w, h) / v.zoom;
        return { x: v.cx - side / 2, y: v.cy - side / 2, side: side };
    }

    /** The view after a drag of (dx, dy) CSS px on a frame `frame` px wide: the picture follows the finger. */
    function pan(view, dx, dy, frame, w, h, maxZoom) {
        var v = clampView(view, w, h, maxZoom);
        var k = Math.min(w, h) / v.zoom / frame;
        return clampView({ zoom: v.zoom, cx: v.cx - dx * k, cy: v.cy - dy * k }, w, h, maxZoom);
    }

    /**
     * The view at `zoom`, the picture's point under (px, py) - CSS px from the frame's centre - staying there
     * (a pinch's midpoint; the slider's is the centre), as far as the clamp allows.
     */
    function zoomAt(view, zoom, px, py, frame, w, h, maxZoom) {
        var v = clampView(view, w, h, maxZoom);
        var short = Math.min(w, h);
        var before = short / v.zoom / frame;
        var ix = v.cx + px * before;
        var iy = v.cy + py * before;
        var z = clampView({ zoom: zoom, cx: v.cx, cy: v.cy }, w, h, maxZoom).zoom;
        var after = short / z / frame;
        return clampView({ zoom: z, cx: ix - px * after, cy: iy - py * after }, w, h, maxZoom);
    }

    /** A `w`×`h` picture's size shrunk (never enlarged) to at most `short` on its shorter, `long` on its longer side. */
    function fitWithin(w, h, short, long) {
        var s = Math.min(1, short / Math.min(w, h), long / Math.max(w, h));
        return { w: Math.max(1, Math.round(w * s)), h: Math.max(1, Math.round(h * s)) };
    }

    /** The uploaded square's side: the chosen square's own pixels, at most `max` (never enlarged). */
    function outputSide(side, max) {
        return Math.max(1, Math.min(max, Math.round(side)));
    }

    /** Can this browser crop and hand the result to the form: a 2D canvas with toBlob, and a settable file input. */
    function supported(win, doc) {
        try {
            var canvas = doc.createElement("canvas");
            if (!canvas.getContext || !canvas.getContext("2d") || typeof canvas.toBlob !== "function") return false;
            if (!win.URL || typeof win.URL.createObjectURL !== "function" || typeof win.Promise !== "function") return false;
            var transfer = new win.DataTransfer();
            transfer.items.add(new win.File([""], "probe.jpg", { type: JPEG }));
            var probe = doc.createElement("input");
            probe.type = "file";
            probe.files = transfer.files;
            return !!(probe.files && probe.files.length === 1);
        } catch (e) {
            return false;
        }
    }

    /** The picture at `src`, decoded (its EXIF orientation applied by the browser). */
    function loadImage(win, src) {
        return new win.Promise(function (resolve, reject) {
            var img = new win.Image();
            if (typeof img.decode === "function") {
                img.src = src;
                img.decode().then(function () { resolve(img); }, reject);
                return;
            }
            img.onload = function () { resolve(img); };
            img.onerror = reject;
            img.src = src;
        });
    }

    function install(doc, win) {
        win = win || root;
        var dialog = doc.querySelector("[data-crop-dialog]");
        if (!dialog || !supported(win, doc)) return null;
        var frame = dialog.querySelector("[data-crop-frame]");
        var preview = dialog.querySelector("[data-crop-canvas]");
        var slider = dialog.querySelector("[data-crop-zoom]");
        var cancelButton = dialog.querySelector("[data-crop-cancel]");
        var saveButton = dialog.querySelector("[data-crop-save]");
        var maxSide = Number(dialog.getAttribute("data-side")) || 512;
        var maxBytes = Number(dialog.getAttribute("data-max-bytes")) || 10 * 1024 * 1024;
        slider.min = "1";
        slider.max = String(MAX_ZOOM);

        // The open crop: the input it fills, what had focus, whether it came from a picked file, the working
        // canvas and its size, the view and the frame's width. `null` while closed.
        var session = null;
        var pointers = {};
        var queued = false;
        // Set from Save until the page goes: a change the file input fires for the cropped file starts nothing.
        var submitting = false;

        function setBusy(busy) {
            frame.setAttribute("aria-busy", busy ? "true" : "false");
            slider.disabled = busy;
            saveButton.disabled = busy;
        }

        function draw() {
            queued = false;
            if (!session || !session.work) return;
            var r = sourceRect(session.view, session.w, session.h);
            var ctx = preview.getContext("2d");
            ctx.fillStyle = "#FFFFFF";
            ctx.fillRect(0, 0, preview.width, preview.height);
            ctx.drawImage(session.work, r.x, r.y, r.side, r.side, 0, 0, preview.width, preview.height);
        }

        function redraw() {
            if (queued) return;
            queued = true;
            if (typeof win.requestAnimationFrame === "function") {
                win.requestAnimationFrame(draw);
            } else {
                draw();
            }
        }

        function setView(view) {
            if (!session || !session.work) return;
            session.view = view;
            slider.value = String(Math.round(view.zoom * 100) / 100);
            redraw();
        }

        function close() {
            dialog.hidden = true;
            pointers = {};
            var s = session;
            session = null;
            if (s && s.work) {
                s.work.width = 0;
                s.work.height = 0;
            }
            return s;
        }

        function cancel() {
            var s = close();
            if (!s) return;
            if (s.picked) s.input.value = "";
            if (s.opener && s.opener.focus) s.opener.focus();
        }

        /** The upload as the page did it before this script: the input's own file, through the hooked submit. */
        function plainUpload(input) {
            submitting = true;
            input.form.submit();
        }

        function open(input, opener, src, picked, revoke) {
            session = { input: input, opener: opener, picked: picked, work: null };
            var mine = session;
            setBusy(true);
            slider.value = "1";
            dialog.hidden = false;
            frame.focus();
            loadImage(win, src).then(function (img) {
                if (revoke) revoke();
                if (session !== mine) return;
                var size = fitWithin(img.naturalWidth || img.width, img.naturalHeight || img.height, WORK_SHORT, WORK_LONG);
                var work = doc.createElement("canvas");
                work.width = size.w;
                work.height = size.h;
                work.getContext("2d").drawImage(img, 0, 0, size.w, size.h);
                mine.work = work;
                mine.w = size.w;
                mine.h = size.h;
                var box = frame.getBoundingClientRect();
                mine.frame = box.width || 280;
                var ratio = win.devicePixelRatio || 1;
                preview.width = Math.round(mine.frame * ratio);
                preview.height = preview.width;
                setBusy(false);
                setView(centred(size.w, size.h));
            }, function () {
                if (revoke) revoke();
                if (session !== mine) return;
                close();
                // A file the browser can't read goes up as it is: the server's answer says what is wrong.
                if (picked) {
                    plainUpload(input);
                } else if (opener && opener.focus) {
                    opener.focus();
                }
            });
        }

        function save() {
            var s = session;
            if (!s || !s.work || submitting) return;
            var r = sourceRect(s.view, s.w, s.h);
            var side = outputSide(r.side, maxSide);
            var out = doc.createElement("canvas");
            out.width = side;
            out.height = side;
            var ctx = out.getContext("2d");
            // JPEG has no alpha: a transparent PNG goes on white, as the server flattens it.
            ctx.fillStyle = "#FFFFFF";
            ctx.fillRect(0, 0, side, side);
            ctx.drawImage(s.work, r.x, r.y, r.side, r.side, 0, 0, side, side);
            submitting = true;
            setBusy(true);
            out.toBlob(function (blob) {
                close();
                if (!blob) {
                    submitting = false;
                    if (s.picked) plainUpload(s.input);
                    return;
                }
                var transfer = new win.DataTransfer();
                transfer.items.add(new win.File([blob], "photo.jpg", { type: JPEG }));
                s.input.files = transfer.files;
                s.input.form.submit();
            }, JPEG, QUALITY);
        }

        function framePoint(x, y) {
            var box = frame.getBoundingClientRect();
            return { x: x - (box.left + box.width / 2), y: y - (box.top + box.height / 2) };
        }

        function ids() {
            var out = [];
            for (var id in pointers) {
                if (Object.prototype.hasOwnProperty.call(pointers, id)) out.push(id);
            }
            return out;
        }

        frame.addEventListener("pointerdown", function (e) {
            if (!session || !session.work) return;
            if (e.preventDefault) e.preventDefault();
            try {
                if (frame.setPointerCapture) frame.setPointerCapture(e.pointerId);
            } catch (err) {}
            pointers[e.pointerId] = { x: e.clientX, y: e.clientY };
        });

        frame.addEventListener("pointermove", function (e) {
            var last = pointers[e.pointerId];
            if (!last || !session || !session.work) return;
            var s = session;
            var held = ids();
            if (held.length === 1) {
                pointers[e.pointerId] = { x: e.clientX, y: e.clientY };
                setView(pan(s.view, e.clientX - last.x, e.clientY - last.y, s.frame, s.w, s.h));
                return;
            }
            // Two fingers: the midpoint pans, the distance zooms about it.
            var other = pointers[held[0] === String(e.pointerId) ? held[1] : held[0]];
            var midBefore = { x: (last.x + other.x) / 2, y: (last.y + other.y) / 2 };
            var distBefore = Math.sqrt(Math.pow(last.x - other.x, 2) + Math.pow(last.y - other.y, 2));
            pointers[e.pointerId] = { x: e.clientX, y: e.clientY };
            var midAfter = { x: (e.clientX + other.x) / 2, y: (e.clientY + other.y) / 2 };
            var distAfter = Math.sqrt(Math.pow(e.clientX - other.x, 2) + Math.pow(e.clientY - other.y, 2));
            var view = pan(s.view, midAfter.x - midBefore.x, midAfter.y - midBefore.y, s.frame, s.w, s.h);
            if (distBefore > 0) {
                var at = framePoint(midAfter.x, midAfter.y);
                view = zoomAt(view, view.zoom * distAfter / distBefore, at.x, at.y, s.frame, s.w, s.h);
            }
            setView(view);
        });

        function lift(e) {
            delete pointers[e.pointerId];
        }
        frame.addEventListener("pointerup", lift);
        frame.addEventListener("pointercancel", lift);

        frame.addEventListener("wheel", function (e) {
            if (!session || !session.work) return;
            if (e.preventDefault) e.preventDefault();
            var s = session;
            var at = framePoint(e.clientX, e.clientY);
            setView(zoomAt(s.view, s.view.zoom * Math.exp(-e.deltaY * 0.002), at.x, at.y, s.frame, s.w, s.h));
        });

        frame.addEventListener("keydown", function (e) {
            if (!session || !session.work) return;
            var s = session;
            var step = e.shiftKey ? STEP * 4 : STEP;
            // An arrow moves the photo as a drag that way would.
            var moves = { ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, -step], ArrowDown: [0, step] };
            var view = null;
            if (Object.prototype.hasOwnProperty.call(moves, e.key)) {
                view = pan(s.view, moves[e.key][0], moves[e.key][1], s.frame, s.w, s.h);
            } else if (e.key === "+" || e.key === "=") {
                view = zoomAt(s.view, s.view.zoom * 1.1, 0, 0, s.frame, s.w, s.h);
            } else if (e.key === "-") {
                view = zoomAt(s.view, s.view.zoom / 1.1, 0, 0, s.frame, s.w, s.h);
            }
            if (!view) return;
            if (e.preventDefault) e.preventDefault();
            setView(view);
        });

        slider.addEventListener("input", function () {
            if (!session || !session.work) return;
            var s = session;
            setView(zoomAt(s.view, Number(slider.value), 0, 0, s.frame, s.w, s.h));
        });

        cancelButton.addEventListener("click", cancel);
        saveButton.addEventListener("click", save);

        // The dialog keeps the focus: Tab and Shift-Tab go round its controls, Escape cancels.
        dialog.addEventListener("keydown", function (e) {
            if (!session) return;
            if (e.key === "Escape" || e.key === "Esc") {
                if (e.preventDefault) e.preventDefault();
                if (!submitting) cancel();
                return;
            }
            if (e.key !== "Tab") return;
            var stops = [frame, slider, cancelButton, saveButton].filter(function (el) { return !el.disabled; });
            var at = stops.indexOf(doc.activeElement);
            var next = e.shiftKey ? (at <= 0 ? stops.length - 1 : at - 1) : (at < 0 || at === stops.length - 1 ? 0 : at + 1);
            if (e.preventDefault) e.preventDefault();
            stops[next].focus();
        });

        var inputs = doc.querySelectorAll("input[type=file][data-photo-crop]");
        Array.prototype.forEach.call(inputs, function (input) {
            // autosave.js would upload the file as picked; from here the dialog's Save does.
            input.removeAttribute("data-autosave");
            input.addEventListener("change", function () {
                if (session || submitting) return;
                var file = input.files && input.files[0];
                if (!file) return;
                if (file.size > maxBytes) {
                    plainUpload(input);
                    return;
                }
                var url = win.URL.createObjectURL(file);
                open(input, input, url, true, function () { win.URL.revokeObjectURL(url); });
            });
        });

        var adjusters = doc.querySelectorAll("[data-photo-adjust]");
        Array.prototype.forEach.call(adjusters, function (button) {
            var input = button.form && button.form.querySelector("input[type=file][data-photo-crop]");
            if (!input) return;
            button.hidden = false;
            button.addEventListener("click", function () {
                if (session || submitting) return;
                open(input, button, button.getAttribute("data-photo-adjust"), false, null);
            });
        });

        // Back to this page from the back-forward cache after a save: it can crop again.
        if (win.addEventListener) {
            win.addEventListener("pageshow", function () {
                submitting = false;
            });
        }
        return { open: open, cancel: cancel, save: save };
    }

    var api = {
        install: install,
        clampView: clampView,
        centred: centred,
        sourceRect: sourceRect,
        pan: pan,
        zoomAt: zoomAt,
        fitWithin: fitWithin,
        outputSide: outputSide,
        supported: supported,
        MAX_ZOOM: MAX_ZOOM
    };
    if (typeof module !== "undefined" && module.exports) {
        module.exports = api;
    } else if (root.document) {
        install(root.document, root);
    }
})(this);
