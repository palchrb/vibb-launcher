// Registers the service worker (templates/sw.js: installability and the parent's notifications),
// and sets the app's badge to the bell's unread count where the page has the bell and the browser
// has badges (design 35 §5.3), lets Sign Out take this browser's subscription with it
// (signOut below), and keeps this browser's notifications on (pushSync below: Push never lost silently,
// 2026-11-04). ES5, so an old WebView parses it (design 31 §5.7).
// Tests: jstest/register-sw.test.js.
(function (root) {
    "use strict";

    function install(win) {
        var nav = win.navigator;
        if ("serviceWorker" in nav) {
            win.addEventListener("load", function () {
                // Register, then ask for an update check at once: an installed app (iOS) can keep
                // an old worker for a day otherwise, and a worker fix must reach it at the next open.
                var registered = nav.serviceWorker.register("/sw.js");
                if (registered && registered.then) {
                    registered.then(function (reg) {
                        if (reg && reg.update) {
                            var updating = reg.update();
                            if (updating && updating.catch) updating.catch(function () {});
                        }
                    }).catch(function () {});
                }
            });
        }
        signOut(win);
        pushSync(win);
        var bell = win.document.querySelector("[data-unread]");
        if (!bell || !nav.setAppBadge) return;
        var unread = parseInt(bell.getAttribute("data-unread"), 10) || 0;
        var done = unread > 0 ? nav.setAppBadge(unread) : nav.clearAppBadge();
        if (done && done.catch) done.catch(function () {});
    }

    // Sign Out takes this browser's notifications with it (the user, 2026-10-25): the form
    // (`form[data-sign-out]`, Settings) gets the browser's subscription endpoint in its hidden
    // `endpoint` once it is known - the server deletes that row with the session -, and on submit
    // the browser unsubscribes first. The form goes when that is done or after SIGN_OUT_WAIT ms,
    // whichever is first. No worker or no subscription: the field stays empty, as without JS.
    var SIGN_OUT_WAIT = 300;

    function signOut(win) {
        var nav = win.navigator;
        var form = win.document.querySelector("form[data-sign-out]");
        if (!form || !("serviceWorker" in nav) || !nav.serviceWorker.ready) return;
        var field = form.querySelector("input[name=endpoint]");
        if (!field) return;
        var found = null;
        var lookup = Promise.resolve(nav.serviceWorker.ready)
            .then(function (reg) {
                return reg && reg.pushManager ? reg.pushManager.getSubscription() : null;
            })
            .then(function (sub) {
                if (sub && sub.endpoint) {
                    found = sub;
                    field.value = sub.endpoint;
                }
            })
            .catch(function () {});
        var sending = false;
        form.addEventListener("submit", function (e) {
            if (sending) return;
            sending = true;
            e.preventDefault();
            var gone = false;
            function go() {
                if (gone) return;
                gone = true;
                form.submit();
            }
            win.setTimeout(go, SIGN_OUT_WAIT);
            lookup.then(function () {
                if (!found) return go();
                try {
                    var done = found.unsubscribe();
                    if (done && done.then) done.then(go, go);
                    else go();
                } catch (err) {
                    go();
                }
            });
        });
    }

    // This browser's notifications, checked at a signed-in page's load (Push never lost silently, 2026-11-04: the
    // parent's iPhone lost its subscription without a trace). Only on a page with the tab bar ([data-signed-in]: a
    // signed-in page - the sign-in pages and the lock screen have none, and there the next check is due at once),
    // not on Notification Settings (This Device does it there), once per SYNC_SPACING ms in this tab (sessionStorage),
    // while the permission is granted and Turn Off (or the banner's Hide) wasn't tapped in this browser
    // (localStorage OFF_KEY, notifications.js; storage that can't be read: nothing is done). Then:
    // - the browser's subscription goes to POST /notifications/subscriptions/sync, which answers what it knows of it
    //   (handlers::notifications::sync; the request needs this signed-in session like every page);
    // - none, or one the push service ended or made for another server key: a new one is made with the server's key
    //   and sent (with `replaces`, the dead one); a subscribe the browser refuses without a tap (iOS) shows the
    //   app-wide quiet banner "Notifications are off on this phone" with Turn On (subscribe first in the tap) and Hide;
    // - removed by a parent (Remove, Turn Off or Hide - the server's mark, review M-1): Turn Off in this browser too;
    // - known, stored, refused, or any failure: nothing more.
    var SYNC_KEY = "vibb.push-sync";
    var SYNC_SPACING = 10 * 60 * 1000;
    var OFF_KEY = "vibb.push-off";
    var SYNC_URL = "/notifications/subscriptions/sync";

    function now(win) {
        return win.Date ? win.Date.now() : Date.now();
    }

    // "on" (not turned off), "off", or "unknown" (storage that throws).
    function offState(win) {
        try {
            return win.localStorage.getItem(OFF_KEY) === "1" ? "off" : "on";
        } catch (e) {
            return "unknown";
        }
    }

    function markOff(win) {
        try {
            win.localStorage.setItem(OFF_KEY, "1");
        } catch (e) {
            // Nothing kept: the banner comes back at the next check.
        }
    }

    function forgetOff(win) {
        try {
            win.localStorage.removeItem(OFF_KEY);
        } catch (e) {
            // Nothing kept.
        }
    }

    // Whether this tab is due a check, and the note that it ran; a page without the bar clears it (a sign-in or the
    // lock screen comes before, so the next signed-in page checks at once).
    function due(win, signedIn) {
        try {
            var store = win.sessionStorage;
            if (!signedIn) {
                store.removeItem(SYNC_KEY);
                return false;
            }
            var last = parseInt(store.getItem(SYNC_KEY), 10) || 0;
            var at = now(win);
            if (last && at - last < SYNC_SPACING && at >= last) return false;
            store.setItem(SYNC_KEY, String(at));
            return true;
        } catch (e) {
            return signedIn;
        }
    }

    function b64url(buffer, win) {
        var bytes = new Uint8Array(buffer);
        var raw = "";
        for (var i = 0; i < bytes.length; i++) raw += String.fromCharCode(bytes[i]);
        return win.btoa(raw).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
    }

    function decodeKey(text, win) {
        var b64 = text.replace(/-/g, "+").replace(/_/g, "/");
        while (b64.length % 4) b64 += "=";
        var raw = win.atob(b64);
        var out = new Uint8Array(raw.length);
        for (var i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i);
        return out;
    }

    // The sync's form for a browser subscription (or none): the endpoint, its keys and the key it was made for.
    function fieldsOf(subscription, win) {
        if (!subscription) return {};
        var json = subscription.toJSON();
        var fields = { endpoint: json.endpoint, p256dh: json.keys.p256dh, auth: json.keys.auth };
        try {
            var key = subscription.options && subscription.options.applicationServerKey;
            if (key) fields.key = b64url(key, win);
        } catch (e) {
            // The browser doesn't say: the server takes it as made for its key.
        }
        return fields;
    }

    function post(win, fields) {
        var body = [];
        for (var name in fields) {
            if (fields[name]) body.push(encodeURIComponent(name) + "=" + encodeURIComponent(fields[name]));
        }
        return win.fetch(SYNC_URL, {
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

    // A new subscription with the server's key, sent with `replaces`; a refusal (no tap on iOS) is "needs_tap".
    function renew(win, reg, answer, replaces, asked) {
        var made;
        try {
            made = reg.pushManager.subscribe({
                userVisibleOnly: true,
                applicationServerKey: decodeKey(answer.key, win)
            });
        } catch (e) {
            made = Promise.reject(e);
        }
        return Promise.resolve(made).then(function (subscription) {
            var fields = fieldsOf(subscription, win);
            if (replaces) fields.replaces = replaces;
            if (asked) fields.asked = "1";
            return post(win, fields);
        }, function () {
            return { state: "needs_tap", key: answer.key, words: answer.words };
        });
    }

    // The check: the browser's subscription to the server, and a new one where it answers for one.
    function check(win, reg) {
        return reg.pushManager.getSubscription().then(function (subscription) {
            return post(win, fieldsOf(subscription, win)).then(function (answer) {
                if (answer.state === "none") return renew(win, reg, answer, null, false);
                if ((answer.state === "gone" || answer.state === "other_key") && subscription) {
                    var old = subscription.endpoint;
                    var dropped = function () {
                        return renew(win, reg, answer, old, false);
                    };
                    return Promise.resolve(subscription.unsubscribe()).then(dropped, dropped);
                }
                return answer;
            });
        });
    }

    // The app-wide quiet banner (an Attention row at its lowest level, under the page's title): what it says and
    // Turn On, which subscribes first in the tap (Apple's rule), and Hide, which is a Turn Off in this browser.
    // Inserted without moving what the parent is looking at: a scrolled page keeps its place.
    function banner(win, reg, answer) {
        var doc = win.document;
        var words = answer.words || {};
        var main = doc.querySelector("main");
        if (!main || doc.getElementById("push-banner")) return null;
        var row = doc.createElement("div");
        row.className = "attention push-banner";
        row.id = "push-banner";
        row.setAttribute("role", "status");
        var text = doc.createElement("span");
        text.className = "attention-text";
        text.textContent = words.off || "";
        var on = doc.createElement("button");
        on.type = "button";
        on.className = "attention-dismiss";
        on.textContent = words.on || "";
        var hide = doc.createElement("button");
        hide.type = "button";
        hide.className = "attention-dismiss";
        hide.textContent = words.hide || "";
        row.appendChild(text);
        row.appendChild(on);
        row.appendChild(hide);
        var title = main.querySelector(".page-title");
        var before = main.scrollTop || 0;
        var height = main.scrollHeight || 0;
        if (title && title.parentNode === main) main.insertBefore(row, title.nextSibling);
        else main.insertBefore(row, main.firstChild);
        if (before > 0) main.scrollTop = before + ((main.scrollHeight || 0) - height);
        function gone() {
            if (row.parentNode) row.parentNode.removeChild(row);
        }
        on.addEventListener("click", function () {
            if (on.disabled) return;
            on.disabled = true;
            forgetOff(win);
            renew(win, reg, answer, null, true).then(function (result) {
                if (result && (result.state === "stored" || result.state === "known")) return gone();
                on.disabled = false;
                text.textContent = words.failed || "";
            }, function () {
                on.disabled = false;
                text.textContent = words.failed || "";
            });
        });
        hide.addEventListener("click", function () {
            markOff(win);
            gone();
            // The server marks this browser turned off too (a cookie), so a cleared localStorage or a new sign-in
            // doesn't bring the banner or a silent subscribe back (review M-1).
            post(win, { off: "1" }).catch(function () {});
        });
        return row;
    }

    function pushSync(win) {
        var doc = win.document;
        var nav = win.navigator;
        var signedIn = !!doc.querySelector("[data-signed-in]");
        // This Device reconciles on its own page (notifications.js), without spending this tab's check.
        if (signedIn && doc.getElementById && doc.getElementById("push-on-form")) return null;
        if (!due(win, signedIn)) return null;
        if (!("PushManager" in win) || !win.isSecureContext || !nav.serviceWorker || !win.fetch) return null;
        if (!win.Notification || win.Notification.permission !== "granted") return null;
        if (offState(win) !== "on") return null;
        return Promise.resolve(nav.serviceWorker.ready).then(function (reg) {
            if (!reg || !reg.pushManager) return null;
            return check(win, reg).then(function (answer) {
                if (answer && answer.state === "needs_tap") banner(win, reg, answer);
                // A parent turned this browser off (here or under Getting Notifications): this browser stops asking.
                if (answer && answer.state === "removed") markOff(win);
                return answer;
            });
        }).catch(function () {
            // Signed out, locked, offline, no worker: nothing now; the next check is another page's.
            return null;
        });
    }

    if (typeof module !== "undefined" && module.exports) {
        module.exports = {
            install: install,
            pushSync: pushSync,
            SIGN_OUT_WAIT: SIGN_OUT_WAIT,
            SYNC_SPACING: SYNC_SPACING
        };
    } else if (root.document) {
        install(root);
    }
})(this);
