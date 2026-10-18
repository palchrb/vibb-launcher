// This Device on /notifications (design 35 §5.2): Web Push on this browser. The state, checked in
// this order (qa-35-design H2): an iPhone/iPad Safari tab (navigator.standalone false) needs the
// Home Screen first; no Push API or not a secure context is "Not available"; a denied permission
// is "Blocked"; subscribed here and known to the server (its endpoint's SHA-256 prefix is in the
// form's data-subscribed) is "On" with Turn Off; else "Off" with Turn On (a local subscription the
// server doesn't know is dropped first). Turn On waits for the
// service worker's registration (fetched at load) and its click handler's first call is
// pushManager.subscribe - Apple wants it at once in the tap; subscribe asks for the permission
// itself. The subscription goes to the server in the form (a 303 back to #this-device). ES5 syntax.
// Tests: jstest/notifications.test.js.
(function (root) {
    "use strict";

    // base64url -> Uint8Array (older Safari takes no string as applicationServerKey).
    function decodeKey(text, win) {
        var b64 = text.replace(/-/g, "+").replace(/_/g, "/");
        while (b64.length % 4) b64 += "=";
        var raw = win.atob(b64);
        var out = new Uint8Array(raw.length);
        for (var i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
        return out;
    }

    // The 16-hex SHA-256 prefix of an endpoint, as the server renders data-subscribed.
    function endpointHash(endpoint, win) {
        var bytes = new win.TextEncoder().encode(endpoint);
        return win.crypto.subtle.digest("SHA-256", bytes).then(function (digest) {
            var view = new Uint8Array(digest);
            var hex = "";
            for (var i = 0; i < 8; i++) hex += (view[i] < 16 ? "0" : "") + view[i].toString(16);
            return hex;
        });
    }

    function install(win) {
        var doc = win.document;
        var form = doc.getElementById("push-on-form");
        if (!form) return;
        var offForm = doc.getElementById("push-off-form");
        var stateText = doc.getElementById("push-state");
        var onButton = doc.getElementById("push-on");
        var offButton = doc.getElementById("push-off");
        var nav = win.navigator;
        var registration = null;
        var current = null;

        function show(text, note) {
            stateText.textContent = text;
            if (note) doc.getElementById(note).hidden = false;
        }

        if (nav.standalone === false) {
            show("Add to the Home Screen first", "push-home-screen");
            return;
        }
        if (!("PushManager" in win) || !win.isSecureContext || !nav.serviceWorker) {
            show("Not available in this browser");
            return;
        }
        if (win.Notification && win.Notification.permission === "denied") {
            show("Blocked", "push-blocked");
            return;
        }
        show("Off");
        onButton.hidden = false;
        onButton.disabled = true;

        nav.serviceWorker.ready.then(function (reg) {
            registration = reg;
            return reg.pushManager.getSubscription();
        }).then(function (subscription) {
            if (!subscription) return false;
            return endpointHash(subscription.endpoint, win).then(function (hash) {
                var known = (form.getAttribute("data-subscribed") || "").split(",");
                // One the server doesn't know (removed there, or made for an older server key)
                // is dropped here, so Turn On makes a fresh one: a subscribe with another key
                // over an old subscription fails.
                if (known.indexOf(hash) < 0) {
                    return subscription.unsubscribe().then(function () {
                        return false;
                    }, function () {
                        return false;
                    });
                }
                current = subscription;
                return true;
            });
        }).then(function (on) {
            if (on) {
                show("On");
                onButton.hidden = true;
                offButton.hidden = false;
            } else {
                onButton.disabled = false;
            }
        }, function () {
            onButton.disabled = false;
        });

        onButton.addEventListener("click", function () {
            if (!registration) return;
            registration.pushManager.subscribe({
                userVisibleOnly: true,
                applicationServerKey: decodeKey(form.getAttribute("data-key"), win)
            }).then(function (subscription) {
                var json = subscription.toJSON();
                form.elements.endpoint.value = json.endpoint;
                form.elements.p256dh.value = json.keys.p256dh;
                form.elements.auth.value = json.keys.auth;
                form.submit();
            }, function () {
                if (win.Notification && win.Notification.permission === "denied") {
                    onButton.hidden = true;
                    show("Blocked", "push-blocked");
                } else {
                    show("Couldn't turn on - try again");
                }
            });
        });

        offButton.addEventListener("click", function () {
            if (!current) return;
            var endpoint = current.endpoint;
            current.unsubscribe().then(function () {
                offForm.elements.endpoint.value = endpoint;
                offForm.submit();
            }, function () {
                show("Couldn't turn off - try again");
            });
        });
    }

    var api = { install: install, decodeKey: decodeKey, endpointHash: endpointHash };
    if (typeof module !== "undefined" && module.exports) {
        module.exports = api;
    } else if (root.document) {
        install(root);
    }
})(this);
