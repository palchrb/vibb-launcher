// This Device on /notifications (design 35 §5.2): Web Push on this browser. The state, checked in
// this order (qa-35-design H2): an iPhone/iPad Safari tab (navigator.standalone false) needs the
// Home Screen first; no Push API or not a secure context is "Not available"; a denied permission
// is "Blocked"; subscribed here and known to the server (its endpoint's SHA-256 prefix is in the
// form's data-subscribed) is "On" with Turn Off; else "Off" with Turn On. A local subscription the
// server doesn't know (Push never lost silently, 2026-11-04: `reconcile`) is sent to it again when it was made for
// the server's key - it is the parent's browser and session, so the server makes it active again - and dropped only
// when it was made for another key or its push service ended it (the next Turn On then replaces it). Turn Off is
// remembered in this browser (localStorage `vibb.push-off`), so the app never turns it on again by itself
// (static/register-sw.js); Turn On forgets it. Turn On waits for the
// service worker's registration (fetched at load) and its click handler's first call is
// pushManager.subscribe - Apple wants it at once in the tap; subscribe asks for the permission
// itself. The subscription goes to the server in the form (a 303 back to #this-device). ES5 syntax.
// After Send a Test (the page is back with ?notice=test_sent&sub=<id>), the device rows poll
// subscriptions.json every 3 s for 30 s and re-render their badge and sub-line in place - no
// reload, no scroll - stopping once the tested row's sub-line changed (server round 5 §5).
// Lives on /notifications/settings (design 45 §1.7), and on Devices for the first opening's offer
// (`offer` below). The state words come from the form's own data-w-* attributes (design 70 §5.3), set with
// textContent.
// Tests: jstest/notifications.test.js.
(function (root) {
    "use strict";

    // A word from the page; a missing one shows its name.
    function w(el, name) {
        return (el && el.getAttribute && el.getAttribute("data-w-" + name)) || name;
    }

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

    // Turn Off in this browser (Push never lost silently, 2026-11-04), read by static/register-sw.js too: the app
    // never sends this browser's subscription again by itself after it. Every access in try/catch.
    var OFF_KEY = "vibb.push-off";

    function turnedOff(win) {
        try {
            return win.localStorage.getItem(OFF_KEY) === "1";
        } catch (e) {
            return false;
        }
    }

    function markOff(win, off) {
        try {
            if (off) win.localStorage.setItem(OFF_KEY, "1");
            else win.localStorage.removeItem(OFF_KEY);
        } catch (e) {
            // Nothing kept: This Device's state is the server's.
        }
    }

    // The base64url of the key a browser subscription was made for, or "" when the browser doesn't say.
    function keyOf(subscription, win) {
        try {
            var key = subscription.options && subscription.options.applicationServerKey;
            if (!key) return "";
            var bytes = new Uint8Array(key);
            var raw = "";
            for (var i = 0; i < bytes.length; i++) raw += String.fromCharCode(bytes[i]);
            return win.btoa(raw).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
        } catch (e) {
            return "";
        }
    }

    // POST /notifications/subscriptions/sync with this browser's subscription; resolves with the answer.
    function syncPost(win, subscription, own) {
        // A subscription that can't be read rejects, never throws into the caller.
        return Promise.resolve().then(function () {
            return syncSend(win, subscription, own);
        });
    }

    function syncSend(win, subscription, own) {
        var json = subscription.toJSON();
        var fields = {
            endpoint: json.endpoint,
            p256dh: json.keys.p256dh,
            auth: json.keys.auth,
            key: own
        };
        var body = [];
        for (var name in fields) {
            if (fields[name]) body.push(encodeURIComponent(name) + "=" + encodeURIComponent(fields[name]));
        }
        return win.fetch("/notifications/subscriptions/sync", {
            method: "POST",
            credentials: "same-origin",
            redirect: "manual",
            headers: {
                "Content-Type": "application/x-www-form-urlencoded",
                Accept: "application/json"
            },
            body: body.join("&")
        }).then(function (res) {
            if (!res.ok) throw new Error("status " + res.status);
            return res.json();
        });
    }

    // A browser subscription the page's list doesn't name: made for another key - dropped, as before (a subscribe
    // with another key over it fails); else, unless Turn Off was tapped here, sent to the server again (the server
    // lost it: an account change on another device, a re-auth sign-out). Resolves with {on, replaces}: on = the
    // server has it now; replaces = a dropped one's endpoint, for the next Turn On to replace its dead row.
    function reconcile(win, subscription, key) {
        function drop() {
            var old = subscription.endpoint;
            return subscription.unsubscribe().then(function () {
                return { on: false, replaces: old };
            }, function () {
                return { on: false, replaces: old };
            });
        }
        var own = keyOf(subscription, win);
        if (own && own !== String(key || "").replace(/=+$/, "")) return drop();
        if (turnedOff(win) || !win.fetch) return Promise.resolve({ on: false, replaces: null });
        return syncPost(win, subscription, own).then(function (answer) {
            if (answer.state === "known" || answer.state === "stored") return { on: true, replaces: null };
            if (answer.state === "gone" || answer.state === "other_key") return drop();
            return { on: false, replaces: null };
        }, function () {
            return { on: false, replaces: null };
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
    // hidden, an unknown one is reconciled first, as on This Device. A plain Safari tab never shows it.
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
                    return reconcile(win, subscription, card.getAttribute("data-key")).then(function (r) {
                        if (r.replaces && card.elements.replaces) card.elements.replaces.value = r.replaces;
                        return r.on;
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
            markOff(win, false);
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

        function show(word, note) {
            stateText.textContent = w(form, word);
            if (note) doc.getElementById(note).hidden = false;
        }

        if (nav.standalone === false) {
            show("home-screen", "push-home-screen");
            return;
        }
        if (!("PushManager" in win) || !win.isSecureContext || !nav.serviceWorker) {
            show("not-available");
            return;
        }
        if (win.Notification && win.Notification.permission === "denied") {
            show("blocked", "push-blocked");
            return;
        }
        show("off");
        onButton.hidden = false;
        onButton.disabled = true;

        nav.serviceWorker.ready.then(function (reg) {
            registration = reg;
            return reg.pushManager.getSubscription();
        }).then(function (subscription) {
            if (!subscription) return false;
            return endpointHash(subscription.endpoint, win).then(function (hash) {
                var known = (form.getAttribute("data-subscribed") || "").split(",");
                if (known.indexOf(hash) >= 0) {
                    current = subscription;
                    // The server notes it as this session's browser (review L-4: an account change made here then
                    // keeps it); the answer changes nothing on the page.
                    if (win.fetch) syncPost(win, subscription, keyOf(subscription, win)).catch(function () {});
                    return true;
                }
                // One the server doesn't know: sent again when it is for the server's key, dropped when it was made
                // for another key or its push service ended it (reconcile above).
                return reconcile(win, subscription, form.getAttribute("data-key")).then(function (r) {
                    if (r.on) {
                        current = subscription;
                        return true;
                    }
                    if (r.replaces && form.elements.replaces) form.elements.replaces.value = r.replaces;
                    return false;
                });
            });
        }).then(function (on) {
            if (on) {
                show("on");
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
            markOff(win, false);
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
                    show("blocked", "push-blocked");
                } else {
                    show("on-failed");
                }
            });
        });

        offButton.addEventListener("click", function () {
            if (!current) return;
            var endpoint = current.endpoint;
            // Before the unsubscribe: the app must not send it again by itself (static/register-sw.js).
            markOff(win, true);
            current.unsubscribe().then(function () {
                offForm.elements.endpoint.value = endpoint;
                offForm.submit();
            }, function () {
                // Still on (review L-1): the app must keep sending it again if the server loses it.
                markOff(win, false);
                show("off-failed");
            });
        });
    }

    var api = {
        install: install,
        offerShown: offerShown,
        decodeKey: decodeKey,
        endpointHash: endpointHash,
        testedRow: testedRow,
        pollRows: pollRows,
        keyOf: keyOf,
        reconcile: reconcile,
        OFF_KEY: OFF_KEY
    };
    if (typeof module !== "undefined" && module.exports) {
        module.exports = api;
    } else if (root.document) {
        install(root);
    }
})(this);
