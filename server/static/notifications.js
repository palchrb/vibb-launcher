// This Device on /notifications (design 35 §5.2): Web Push on this browser. The state, checked in
// this order (qa-35-design H2): an iPhone/iPad Safari tab (navigator.standalone false) needs the
// Home Screen first; no Push API or not a secure context is "Not available"; a denied permission
// is "Blocked"; subscribed here and known to the server (its endpoint's SHA-256 prefix is in the
// form's data-subscribed) is "On" with Turn Off; else "Off" with Turn On (a local subscription the
// server doesn't know is dropped first). Turn On waits for the
// service worker's registration (fetched at load) and its click handler's first call is
// pushManager.subscribe - Apple wants it at once in the tap; subscribe asks for the permission
// itself. The subscription goes to the server in the form (a 303 back to #this-device). ES5 syntax.
// After Send a Test (the page is back with ?notice=test_sent&sub=<id>), the device rows poll
// subscriptions.json every 3 s for 30 s and re-render their badge and sub-line in place - no
// reload, no scroll - stopping once the tested row's sub-line changed (server round 5 §5).
// Lives on /notifications/settings (design 45 §1.7), and on Devices for the first opening's offer
// (`offer` below).
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

    // The tested subscription's id from the page's query, or null.
    function testedRow(search) {
        if (!/(^\?|&)notice=test_sent(&|$)/.test(search || "")) return null;
        var match = /(^\?|&)sub=(\d+)(&|$)/.exec(search);
        return match ? match[2] : null;
    }

    // Re-renders the rows from subscriptions.json until the tested row's sub-line changes, or
    // ten polls. `done` (tests) is called when it stops.
    function pollRows(win, tested, done) {
        var doc = win.document;
        var row = function (id) {
            return doc.querySelector('[data-subscription="' + id + '"]');
        };
        var first = row(tested);
        var sub = first && first.querySelector("[data-sub]");
        if (!sub || !win.fetch) {
            if (done) done();
            return;
        }
        var before = sub.textContent;
        var left = 10;
        function stop() {
            if (done) done();
        }
        function render(rows) {
            var changed = false;
            for (var i = 0; i < rows.length; i++) {
                var el = row(rows[i].id);
                if (!el) continue;
                var line = el.querySelector("[data-sub]");
                var badge = el.querySelector("[data-badge]");
                if (line) line.textContent = rows[i].sub;
                if (badge) {
                    badge.textContent = rows[i].badge;
                    badge.className = rows[i].badge_class;
                }
                if (String(rows[i].id) === String(tested) && rows[i].sub !== before) changed = true;
            }
            return changed;
        }
        function tick() {
            left--;
            win.fetch("/notifications/subscriptions.json", {
                credentials: "same-origin",
                headers: { Accept: "application/json" }
            }).then(function (res) {
                if (!res.ok) throw new Error("status " + res.status);
                return res.json();
            }).then(function (rows) {
                if (render(rows) || left <= 0) return stop();
                win.setTimeout(tick, 3000);
            }, function () {
                // A lost answer (a bad network): try again until the time is up.
                if (left <= 0) return stop();
                win.setTimeout(tick, 3000);
            });
        }
        win.setTimeout(tick, 3000);
    }

    // The first opening's offer on Devices (2026-11-01, partials/push_offer.html). Browsers grant
    // the permission prompt only inside a tap, so the app can't ask at launch; this shows a card
    // whose Turn On is This Device's subscribe and form post (back to Devices on success). Its
    // rule (pure, the jstest's table): the app opened from the Home Screen (display-mode
    // standalone, or iOS's navigator.standalone) or just installed (Chrome's appinstalled, in the
    // tab it was installed from); Push here (PushManager, a secure context, a service worker); the
    // permission not asked yet ("default"); Not Now not tapped in this browser (localStorage).
    // Then, once the worker is ready, no subscription the server knows: a known one keeps the card
    // hidden, an unknown one is dropped first, as on This Device. A plain Safari tab never shows it.
    var OFFER_KEY = "vibb.push-offer";

    function offerShown(s) {
        if (!s.standalone && !s.installed) return false;
        if (!s.push) return false;
        if (s.permission !== "default") return false;
        return !s.dismissed;
    }

    function standaloneNow(win) {
        if (win.navigator.standalone === true) return true;
        try {
            return !!(win.matchMedia && win.matchMedia("(display-mode: standalone)").matches);
        } catch (e) {
            return false;
        }
    }

    // Not Now is kept in this browser only; storage that throws (a private window) shows the card.
    function offerDismissed(win) {
        try {
            return win.localStorage.getItem(OFFER_KEY) === "not-now";
        } catch (e) {
            return false;
        }
    }

    function dismissOffer(win) {
        try {
            win.localStorage.setItem(OFFER_KEY, "not-now");
        } catch (e) {
            // Nothing kept: the card comes back at the next opening.
        }
    }

    function offer(win) {
        var doc = win.document;
        var card = doc.getElementById("push-offer");
        if (!card) return;
        var nav = win.navigator;
        var onButton = card.querySelector("[data-offer-on]");
        var later = card.querySelector("[data-offer-later]");
        var line = card.querySelector("[data-offer-line]");
        var installed = false;
        var checking = false;
        var registration = null;

        function facts() {
            return {
                standalone: standaloneNow(win),
                installed: installed,
                push: "PushManager" in win && !!win.isSecureContext && !!nav.serviceWorker,
                permission: win.Notification ? win.Notification.permission : "",
                dismissed: offerDismissed(win)
            };
        }

        // The worker's registration and the browser's subscription, then the card.
        function check() {
            if (checking || !offerShown(facts())) return;
            checking = true;
            nav.serviceWorker.ready.then(function (reg) {
                registration = reg;
                return reg.pushManager.getSubscription();
            }).then(function (subscription) {
                if (!subscription) return false;
                return endpointHash(subscription.endpoint, win).then(function (hash) {
                    var known = (card.getAttribute("data-subscribed") || "").split(",");
                    if (known.indexOf(hash) >= 0) return true;
                    return subscription.unsubscribe().then(function () {
                        return false;
                    }, function () {
                        return false;
                    });
                });
            }).then(function (known) {
                if (known || !offerShown(facts())) return;
                card.hidden = false;
                onButton.disabled = false;
            }, function () {
                // No worker or no answer: no card, This Device is still the way.
            });
        }

        onButton.addEventListener("click", function () {
            if (!registration || onButton.disabled) return;
            // One subscribe and one post per tap: a second tap while the prompt or the post runs does nothing.
            var asked = registration.pushManager.subscribe({
                userVisibleOnly: true,
                applicationServerKey: decodeKey(card.getAttribute("data-key"), win)
            });
            onButton.disabled = true;
            asked.then(function (subscription) {
                var json = subscription.toJSON();
                card.elements.endpoint.value = json.endpoint;
                card.elements.p256dh.value = json.keys.p256dh;
                card.elements.auth.value = json.keys.auth;
                card.submit();
            }, function () {
                onButton.disabled = false;
                if (win.Notification && win.Notification.permission === "denied") {
                    onButton.hidden = true;
                    line.textContent = card.getAttribute("data-blocked");
                } else {
                    line.textContent = card.getAttribute("data-failed");
                }
            });
        });

        later.addEventListener("click", function () {
            dismissOffer(win);
            card.hidden = true;
        });

        if (win.addEventListener) {
            win.addEventListener("appinstalled", function () {
                installed = true;
                check();
            });
        }
        check();
    }

    function install(win) {
        var doc = win.document;
        offer(win);
        var tested = testedRow(win.location && win.location.search);
        if (tested && doc.querySelector) pollRows(win, tested);
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

    var api = {
        install: install,
        offerShown: offerShown,
        decodeKey: decodeKey,
        endpointHash: endpointHash,
        testedRow: testedRow,
        pollRows: pollRows
    };
    if (typeof module !== "undefined" && module.exports) {
        module.exports = api;
    } else if (root.document) {
        install(root);
    }
})(this);
