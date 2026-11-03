// Passkeys in the PWA (design 74 step 1). Loaded by the pages that have passkey controls (the sign-in page, Account,
// Lock Screen & PINs), deferred. Every control is `[data-passkey-ui]`, hidden in the HTML: this script shows them only
// where the browser can use passkeys (a secure context with `PublicKeyCredential`, D4.4) and shows the
// `[data-passkey-unsupported]` rows instead where it can't ("needs https" on http, else "this browser can't").
// - The sign-in page's "Sign in with passkey" (`[data-passkey-login]`, D1.4): a `login` challenge from
//   `POST /auth/passkey/options`, `navigator.credentials.get` with UV required, the assertion posted in its form to
//   `/auth/passkey`. Where `isConditionalMediationAvailable()` says so, a conditional get runs from the start (the
//   username field is `autocomplete="username webauthn"`); the button aborts it and asks again with the same
//   challenge (iOS standalone and older Android don't offer passkeys in autofill reliably).
// - "Confirm with passkey" (`[data-passkey-reauth]`, D1.6) in a form that asks for the password again: the form's
//   other required fields first, then a `reauth` challenge bound to the form's route
//   (`POST /account/passkeys/reauth-options`), the assertion into the form's `assertion`, the password field emptied
//   and no longer required, and the form sent.
// - Account's "Add Passkey…" (`[data-passkey-add]`, D1.2): the name and the re-auth to
//   `POST /account/passkeys/options`, `navigator.credentials.create` with the answer, the credential into the form's
//   `credential` and the form sent - the server's 303 lands on the new row.
// - Step 2: the passkey-only sign-in page (`[data-passkey-only]`, §6.4) has only the button - no autofill, nothing
//   on its own; the autofill's get starts again with fresh options once they are 4.5 minutes old; and only a 401 or
//   a redirect that lands on /login counts as signed out (an HTML error page is an error, qa-passkeys-1 B-RN3).
// - Step 3, the app lock: the lock screen (`#unlock`, `[data-passkey-unlock]`, D3.5; the user's pick A "Pust",
//   2026-11-03) is one button the size of the screen, and nothing starts without a tap (iOS shows its own "Continue"
//   sheet for a prompt without a gesture). The first tap asks for the passkey this browser unlocked with last (an
//   `unlock` challenge from `POST /lock/options` with `hint` = localStorage `handy-lock-passkey`); after a cancel or a
//   failure the red line shows, the hint says "Tap to try again", and every tap after asks with every passkey. Each
//   tap's challenge is fetched ahead, so the prompt opens within the tap (iOS needs the gesture). The assertion is
//   posted to `/lock`, the page replaced with the answer's `next`. Account's "Lock with Face ID" (`[data-app-lock]`,
//   D3.2): a change runs a re-auth assertion for `/account/lock` first (its challenge fetched on the first touch of
//   the control), then the form is sent; a cancel puts the control back. A 423 (the server's lock) goes to /lock and ends the flow there.
// A cancelled prompt (`NotAllowedError`, `AbortError`) is the browser's and sends nothing (D1.5). The words come from
// the page (`data-w-*`, design 70 §5.3). ES5 with promises, no framework. Tests: node --test jstest/passkey.test.js
(function (root) {
    "use strict";

    // base64url without padding <-> bytes (an ArrayBuffer or a view).
    function b64url(buffer) {
        var bytes = new Uint8Array(buffer.buffer ? buffer.buffer.slice(buffer.byteOffset, buffer.byteOffset + buffer.byteLength) : buffer);
        var text = "";
        for (var i = 0; i < bytes.length; i++) text += String.fromCharCode(bytes[i]);
        return btoa(text).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
    }

    function unb64url(text) {
        var base = String(text).replace(/-/g, "+").replace(/_/g, "/");
        while (base.length % 4) base += "=";
        var raw = atob(base);
        var bytes = new Uint8Array(raw.length);
        for (var i = 0; i < raw.length; i++) bytes[i] = raw.charCodeAt(i);
        return bytes.buffer;
    }

    function descriptors(list) {
        return (list || []).map(function (c) {
            var out = { type: c.type || "public-key", id: unb64url(c.id) };
            if (c.transports) out.transports = c.transports;
            return out;
        });
    }

    // The server's JSON options as `navigator.credentials.get`'s `publicKey`.
    function requestOptions(json) {
        return {
            challenge: unb64url(json.challenge),
            rpId: json.rpId,
            timeout: json.timeout,
            userVerification: json.userVerification,
            allowCredentials: descriptors(json.allowCredentials),
        };
    }

    // ...and as `navigator.credentials.create`'s.
    function creationOptions(json) {
        return {
            challenge: unb64url(json.challenge),
            rp: json.rp,
            user: { id: unb64url(json.user.id), name: json.user.name, displayName: json.user.displayName },
            pubKeyCredParams: json.pubKeyCredParams,
            authenticatorSelection: json.authenticatorSelection,
            attestation: json.attestation,
            excludeCredentials: descriptors(json.excludeCredentials),
            timeout: json.timeout,
        };
    }

    // A PublicKeyCredential as the server reads it (webauthn::Registration / Assertion): written out by hand, the same
    // on every browser (some have no toJSON).
    function credentialJSON(cred) {
        var r = cred.response;
        var out = { id: cred.id, rawId: b64url(cred.rawId), type: cred.type, response: { clientDataJSON: b64url(r.clientDataJSON) } };
        if (r.attestationObject) {
            out.response.attestationObject = b64url(r.attestationObject);
            if (typeof r.getTransports === "function") out.response.transports = r.getTransports();
        } else {
            out.response.authenticatorData = b64url(r.authenticatorData);
            out.response.signature = b64url(r.signature);
            out.response.userHandle = r.userHandle && r.userHandle.byteLength ? b64url(r.userHandle) : null;
        }
        return out;
    }

    function supported(win) {
        return !!(win.isSecureContext && win.PublicKeyCredential && win.navigator && win.navigator.credentials);
    }

    // The prompt was cancelled or replaced: nothing to say (D1.5).
    function cancelled(err) {
        return !!err && (err.name === "NotAllowedError" || err.name === "AbortError");
    }

    function w(el, name) {
        return (el && el.getAttribute && el.getAttribute("data-w-" + name)) || "";
    }

    function show(el, text) {
        if (!el) return;
        el.textContent = text;
        el.hidden = !text;
    }

    // A form's fields as a urlencoded body.
    function encode(fields) {
        var parts = [];
        for (var name in fields) {
            if (Object.prototype.hasOwnProperty.call(fields, name)) {
                parts.push(encodeURIComponent(name) + "=" + encodeURIComponent(fields[name] == null ? "" : fields[name]));
            }
        }
        return parts.join("&");
    }

    // The session ended: a 401 (the server's `{signed_out: true}`), or a request the session check sent to the
    // sign-in page (a followed redirect that landed on /login). Nothing else is - not `csrf_guard`'s 403 page, nor any
    // other HTML: that is an error to show where the parent is (step 2, qa-passkeys-1 B-RN3).
    function signedOut(win, res) {
        if (res.status === 401) return true;
        if (!res.redirected || !res.url) return false;
        try {
            var path = new win.URL(res.url).pathname;
            return path === "/login";
        } catch (e) {
            return false;
        }
    }

    // The app is locked (design 74 D3.4): the server answers 423 to anything but the lock screen's own calls.
    function locked(res) {
        return res.status === 423;
    }

    // To the lock screen, back to this page afterwards (app-lock.js's way when it is on the page).
    function toLock(win) {
        var l = win.location;
        var next = (l.pathname || "/") + (l.search || "") + (l.hash || "");
        if (win.handyLock && typeof win.handyLock.lock === "function") win.handyLock.lock(next);
        else l.replace("/lock?next=" + encodeURIComponent(next));
    }

    // POSTs `fields` and answers {ok, status, json, signedOut}. Locked: to the lock screen, and the answer never comes
    // (the flow ends there).
    function post(win, url, fields) {
        return win.fetch(url, {
            method: "POST",
            credentials: "same-origin",
            headers: { "Content-Type": "application/x-www-form-urlencoded" },
            body: encode(fields),
        }).then(function (res) {
            if (locked(res)) {
                toLock(win);
                return new win.Promise(function () {});
            }
            if (signedOut(win, res)) return { ok: false, status: res.status, json: {}, signedOut: true };
            return res.json().then(
                function (json) { return { ok: res.ok, status: res.status, json: json, signedOut: !!json.signed_out }; },
                function () { return { ok: false, status: res.status, json: {}, signedOut: false }; }
            );
        });
    }

    // Signed out mid-flow (qa-passkeys-1 B-L6): to the sign-in page, not "didn't work".
    function toSignIn(win) {
        win.location.assign("/login");
    }

    // The path a form's re-auth challenge is bound to: its `data-reauth-route` (Add Passkey's re-auth is taken by
    // its options call, `/account/passkeys/options` - qa-passkeys-1 B-M1), else the path it posts to.
    function routeOf(win, form) {
        var own = form.getAttribute("data-reauth-route");
        if (own) return own;
        var action = form.getAttribute("action") || "";
        try {
            return new win.URL(action, win.location.href).pathname;
        } catch (e) {
            return action.split("#")[0].split("?")[0];
        }
    }

    function passwordField(form) {
        return form.querySelector("input[name=\"current_password\"], input[name=\"password\"]");
    }

    // The form's required fields other than the password are filled (the re-auth sends the form at once).
    function othersValid(form, password) {
        var fields = form.querySelectorAll("input[required], select[required], textarea[required]");
        for (var i = 0; i < fields.length; i++) {
            if (fields[i] !== password && fields[i].checkValidity && !fields[i].checkValidity()) {
                if (form.reportValidity) form.reportValidity();
                return false;
            }
        }
        return true;
    }

    function send(form) {
        if (form.requestSubmit) form.requestSubmit();
        else form.submit();
    }

    // ---- Sign-in (D1.4) -------------------------------------------------------------------------------------

    // A challenge lives 5 minutes on the server, which hands a session's live one out again for 4 (passkeys::
    // LIVE_REUSE) with the time it has left as `timeout`. The autofill's get starts again with fresh options this
    // long before that time ends (and 4.5 minutes after a fetch at most), so a parent who comes back to an open page -
    // or a reload that got an older challenge again - never picks a passkey for a challenge that died under it
    // (step 2, qa-passkeys-1 B-RN2, qa-passkeys-2 limiter L1). The button fetches anew when less than a minute is left.
    var CONDITIONAL_REFRESH_MS = 270000;
    var REFRESH_BEFORE_END_MS = 30000;
    var BUTTON_MIN_LEFT_MS = 60000;

    function installLogin(win, form) {
        var button = form.querySelector("[data-passkey-login]");
        var error = win.document.querySelector("[data-passkey-error]");
        var options = null;
        var fetchedAt = 0;
        var endsAt = 0;
        var conditional = null;
        var refreshAt = 0;
        var refreshTimer = null;
        var creds = win.navigator.credentials;
        // The passkey-only page (§6.4) has no username field for the autofill: only the button, never on its own.
        var autofill = !form.hasAttribute("data-passkey-only");
        var now = function () { return win.Date ? win.Date.now() : Date.now(); };

        // One challenge serves the conditional get and the button's (it is spent only by a sign-in) for a minute, so
        // a prompt opened from it has its 5 minutes (the server hands the same session's live one back anyway).
        function fetchOptions() {
            if (options && now() - fetchedAt < 60000 && endsAt - now() > BUTTON_MIN_LEFT_MS) return win.Promise.resolve(options);
            return post(win, "/auth/passkey/options", {}).then(function (answer) {
                if (!answer.ok) throw { name: "refused", message: answer.json.error || "" };
                options = answer.json;
                fetchedAt = now();
                endsAt = fetchedAt + (typeof options.timeout === "number" ? options.timeout : 300000);
                return options;
            });
        }

        function signIn(cred) {
            form.querySelector("input[name=\"assertion\"]").value = JSON.stringify(credentialJSON(cred));
            form.submit();
        }

        function failed(err) {
            if (cancelled(err)) return;
            show(error, (err && err.name === "refused" && err.message) || w(form, "failed"));
        }

        // The autofill's get (conditional mediation) where the browser offers it. Its failures are silent: the page
        // says why already (a banned address's line, qa-passkeys-1 B-N5), and the button says the rest.
        function startConditional() {
            var pkc = win.PublicKeyCredential;
            if (!autofill) return;
            if (!pkc || typeof pkc.isConditionalMediationAvailable !== "function" || !win.AbortController) return;
            pkc.isConditionalMediationAvailable().then(function (available) {
                if (!available) return;
                var controller = new win.AbortController();
                conditional = controller;
                fetchOptions().then(function (json) {
                    if (conditional !== controller) return null;
                    refreshAt = Math.min(fetchedAt + CONDITIONAL_REFRESH_MS, endsAt - REFRESH_BEFORE_END_MS);
                    if (win.setTimeout) {
                        if (refreshTimer && win.clearTimeout) win.clearTimeout(refreshTimer);
                        refreshTimer = win.setTimeout(refreshConditional, Math.max(1000, refreshAt - now()));
                    }
                    return creds.get({ mediation: "conditional", publicKey: requestOptions(json), signal: controller.signal });
                }).then(function (cred) {
                    if (cred) signIn(cred);
                }, function () {});
            }, function () {});
        }

        // The autofill's get from options that are getting old: aborted and asked again with fresh ones (a timer, or
        // the page shown again - a phone's background tab has its timers held).
        function refreshConditional() {
            refreshTimer = null;
            if (!conditional || now() < refreshAt) return;
            conditional.abort();
            conditional = null;
            options = null;
            startConditional();
        }

        if (autofill && win.document.addEventListener) {
            win.document.addEventListener("visibilitychange", function () {
                if (win.document.visibilityState !== "hidden") refreshConditional();
            });
        }
        if (autofill && win.addEventListener) win.addEventListener("pageshow", refreshConditional);

        if (button) {
            button.addEventListener("click", function () {
                show(error, "");
                if (conditional) conditional.abort();
                conditional = null;
                fetchOptions().then(function (json) {
                    return creds.get({ publicKey: requestOptions(json) });
                }).then(function (cred) {
                    if (cred) signIn(cred);
                    else startConditional();
                }, function (err) {
                    failed(err);
                    // The autofill offers passkeys again after a cancelled or failed prompt (B-N2).
                    startConditional();
                });
            });
        }
        startConditional();
    }

    // ---- Re-auth (D1.6) -------------------------------------------------------------------------------------

    // Runs the re-auth ceremony for `form`; resolves with true once its `assertion` holds one.
    function reauth(win, form, error) {
        var password = passwordField(form);
        return post(win, "/account/passkeys/reauth-options", { route: routeOf(win, form) }).then(function (answer) {
            if (answer.signedOut) {
                toSignIn(win);
                return null;
            }
            if (!answer.ok) throw { name: "refused", message: answer.json.error || "" };
            return win.navigator.credentials.get({ publicKey: requestOptions(answer.json) });
        }).then(function (cred) {
            if (!cred) return false;
            form.querySelector("input[name=\"assertion\"]").value = JSON.stringify(credentialJSON(cred));
            if (password) {
                password.value = "";
                password.required = false;
            }
            return true;
        }, function (err) {
            if (!cancelled(err)) show(error, (err && err.name === "refused" && err.message) || w(error, "failed"));
            return false;
        });
    }

    // A form only a passkey confirms (a passkey-only parent's re-auth forms, "Kun passkey"'s switch and its switch-off
    // page; the button is `[data-passkey-only]`): its submit - Enter in a field included - runs the passkey first,
    // never sends a password it doesn't have (qa-passkeys-2 M1).
    function passkeyOnly(form) {
        return !!form.querySelector("[data-passkey-reauth][data-passkey-only]");
    }

    function installReauth(win, button) {
        var form = button.closest("form");
        if (!form) return;
        var error = button.parentNode.querySelector("[data-passkey-error]");
        if (error && !error.getAttribute("data-w-failed")) error.setAttribute("data-w-failed", w(button, "failed"));
        function start() {
            show(error, "");
            if (!othersValid(form, passwordField(form))) return;
            reauth(win, form, error).then(function (done) {
                if (!done) return;
                if (form.hasAttribute("data-passkey-add")) add(win, form);
                else send(form);
            });
        }
        button.addEventListener("click", start);
        if (button.hasAttribute("data-passkey-only")) {
            form.addEventListener("submit", function (e) {
                var assertion = form.querySelector("input[name=\"assertion\"]");
                if (assertion && assertion.value) return;
                e.preventDefault();
                start();
            });
        }
    }

    // ---- Adding (D1.2) --------------------------------------------------------------------------------------

    function fieldError(form, field) {
        return form.querySelector("[data-passkey-field-error=\"" + field + "\"]");
    }

    function add(win, form) {
        var nameError = fieldError(form, "name");
        var passwordError = fieldError(form, "password");
        show(nameError, "");
        show(passwordError, "");
        var name = form.querySelector("input[name=\"name\"]");
        var password = passwordField(form);
        var assertion = form.querySelector("input[name=\"assertion\"]");
        var fields = {
            name: name ? name.value : "",
            current_password: password ? password.value : "",
            assertion: assertion ? assertion.value : "",
        };
        // The re-auth is spent by this one request: never sent twice.
        if (password) password.value = "";
        if (assertion) assertion.value = "";
        return post(win, "/account/passkeys/options", fields).then(function (answer) {
            if (answer.signedOut) {
                toSignIn(win);
                return;
            }
            if (!answer.ok) {
                show(answer.json.field === "name" ? nameError : passwordError, answer.json.error || w(form, "failed"));
                if (password) password.required = true;
                return;
            }
            return win.navigator.credentials.create({ publicKey: creationOptions(answer.json) }).then(function (cred) {
                if (!cred) return;
                form.querySelector("input[name=\"credential\"]").value = JSON.stringify(credentialJSON(cred));
                form.submit();
            });
        }).then(null, function (err) {
            if (password) password.required = true;
            if (cancelled(err)) return;
            show(passwordError, err && err.name === "InvalidStateError" ? w(form, "exists") : w(form, "failed"));
        });
    }

    function installAdd(win, form) {
        form.addEventListener("submit", function (e) {
            if (form.querySelector("input[name=\"credential\"]").value) return;
            e.preventDefault();
            // A passkey-only parent's add starts with the passkey (its own submit listener, installReauth).
            var assertion = form.querySelector("input[name=\"assertion\"]");
            if (passkeyOnly(form) && !(assertion && assertion.value)) return;
            add(win, form);
        });
    }

    // ---- The app lock (step 3) ------------------------------------------------------------------------------

    // A challenge for `url` fetched ahead of the tap that uses it (iOS opens WebAuthn only within a user gesture, and
    // a fetch between the tap and the prompt can lose it): `ready()` is the fresh options or null, `fetch()` gets new
    // ones. A challenge lives 5 minutes; options are used for 4.
    var AHEAD_MS = 240000;
    // The lock screen fetches a fresh challenge this often while it is on screen (qa-passkeys-3 client L2), so a tap
    // after a long wait still prompts at once.
    var UNLOCK_REFRESH_MS = 210000;

    function ahead(win, url, fields) {
        var options = null;
        var fetchedAt = 0;
        var asking = null;
        var now = function () { return win.Date ? win.Date.now() : Date.now(); };
        return {
            ready: function () {
                return options && now() - fetchedAt < AHEAD_MS ? options : null;
            },
            // Spent by one prompt: the next try asks again.
            spend: function () {
                options = null;
            },
            fetch: function () {
                if (asking) return asking;
                asking = post(win, url, fields()).then(function (answer) {
                    asking = null;
                    if (answer.signedOut) {
                        toSignIn(win);
                        return new win.Promise(function () {});
                    }
                    if (!answer.ok) throw { name: "refused", message: answer.json.error || "" };
                    options = answer.json;
                    fetchedAt = now();
                    return options;
                }, function (err) {
                    asking = null;
                    throw err;
                });
                return asking;
            },
        };
    }

    // The prompt for `challenge`'s options: at once when they are ready (within the tap), else after fetching them.
    // `signal` (optional) aborts it.
    function promptWith(win, challenge, signal) {
        function get(json) {
            var request = { publicKey: requestOptions(json) };
            if (signal) request.signal = signal;
            return win.navigator.credentials.get(request);
        }
        var ready = challenge.ready();
        challenge.spend();
        if (ready) return get(ready);
        return challenge.fetch().then(function (json) {
            challenge.spend();
            if (signal && signal.aborted) throw { name: "AbortError" };
            return get(json);
        });
    }

    // The passkey this browser unlocked with last (its `rawId`, base64url), per origin = per device: the lock screen's
    // first tap asks for it alone (the server narrows to it only when it is the parent's).
    var LAST_PASSKEY_KEY = "handy-lock-passkey";

    function lastPasskey(win) {
        try {
            var id = win.localStorage.getItem(LAST_PASSKEY_KEY);
            return id && id.length <= 1024 && /^[A-Za-z0-9_-]+$/.test(id) ? id : "";
        } catch (e) {
            return "";
        }
    }

    function keepLastPasskey(win, id) {
        try {
            win.localStorage.setItem(LAST_PASSKEY_KEY, id);
        } catch (e) {}
    }

    // The lock screen (§6.5, D3.5; the user's pick A "Pust", 2026-11-03): the whole screen is the button, and nothing
    // starts on its own (on an iPhone a prompt without a tap gets WebKit's "Continue" sheet first). The first tap asks
    // for the passkey this browser used last when there is one; cancelled, timed out or refused, the red line shows,
    // the hint turns to "Tap to try again", and the next tap asks with every passkey of the parent's (one removed or
    // lost never strands the parent). The challenge the next tap uses is always fetched ahead.
    function installUnlock(win, form) {
        var button = form.querySelector("[data-passkey-unlock-button]");
        var error = form.querySelector("[data-passkey-error]");
        var hintLine = form.querySelector("[data-passkey-unlock-hint]");
        var next = form.querySelector("input[name=\"next\"]");
        if (!button) return;
        var every = ahead(win, "/lock/options", function () { return {}; });
        var hint = lastPasskey(win);
        // The challenge the next tap uses: the kept passkey's first, every passkey's after a cancel or a failure.
        var current = hint ? ahead(win, "/lock/options", function () { return { hint: hint }; }) : every;
        // The open try ({controller, stale}) or null. A tap while it is open is dropped (a quick double tap would start
        // a second, refused prompt) - unless the page was hidden and shown again since (`stale`): a PWA sent to the
        // background during Face ID may get a prompt WebKit never settles, so that tap aborts it and asks afresh. An
        // aborted or replaced try's result is ignored. Never aborted on the showing itself: on Android the passkey
        // sheet can hide the page, and the try that brings it back must still unlock.
        var open = null;
        function visible() {
            return win.document.visibilityState !== "hidden";
        }
        function prefetch() {
            if (!current.ready()) current.fetch().then(null, function () {});
        }
        // §6.5: after a failed or cancelled try, the red line over the hint, which now says "Tap to try again".
        function failed(text) {
            show(error, text || w(error, "failed"));
            var retry = w(hintLine, "retry");
            if (retry) hintLine.textContent = retry;
            current = every;
            prefetch();
        }
        // The prompt's credential: posted; done, the page goes on (refused, the red line shows).
        function unlockWith(cred) {
            if (!cred) return failed();
            var json = credentialJSON(cred);
            return post(win, "/lock", {
                assertion: JSON.stringify(json),
                next: next ? next.value : "",
            }).then(function (answer) {
                if (answer.signedOut) return toSignIn(win);
                if (!answer.ok) return failed(answer.json.error);
                keepLastPasskey(win, json.rawId);
                // The use starts now in this tab too (app-lock.js's record), so the page doesn't lock again.
                try {
                    win.sessionStorage.setItem("handy-used", String(win.Date ? win.Date.now() : Date.now()));
                } catch (e) {}
                win.location.replace(answer.json.next || "/");
            });
        }
        // The tap (a click: Enter and Space too): the prompt at once, within the gesture.
        button.addEventListener("click", function () {
            if (open && !open.stale) return;
            if (open && open.controller) open.controller.abort();
            var running = { controller: win.AbortController ? new win.AbortController() : null, stale: false };
            open = running;
            show(error, "");
            var prompt;
            try {
                prompt = promptWith(win, current, running.controller ? running.controller.signal : null);
            } catch (err) {
                // A synchronous throw (options the browser can't read): the red line, never a dead screen.
                prompt = win.Promise.reject(err);
            }
            prompt.then(function (cred) {
                if (open !== running) return;
                return unlockWith(cred);
            }).then(function () {
                if (open === running) open = null;
            }, function (err) {
                if (open !== running) return;
                open = null;
                failed(err && err.name === "refused" ? err.message : "");
            });
        });
        prefetch();
        // On screen for long (a tablet on a stand): fresh options before the ready ones age out.
        function refresh() {
            if (visible()) {
                current.spend();
                prefetch();
            }
            if (win.setTimeout) win.setTimeout(refresh, UNLOCK_REFRESH_MS);
        }
        if (win.setTimeout) win.setTimeout(refresh, UNLOCK_REFRESH_MS);
        // A page shown again (a phone holds a hidden one, or back from the back-forward cache): an open try may never
        // settle now, so the next tap may replace it; fresh options if the ready ones aged out.
        function shown() {
            if (open) open.stale = true;
            prefetch();
        }
        if (win.document.addEventListener) {
            win.document.addEventListener("visibilitychange", function () {
                if (visible()) shown();
            });
        }
        if (win.addEventListener) {
            win.addEventListener("pageshow", function (e) {
                if (e && e.persisted) shown();
            });
        }
    }

    // Account's "Lock with Face ID" (§6.1, D3.2): every change behind a passkey that works here.
    function installAppLock(win, form) {
        var control = form.querySelector("[data-app-lock-pick]");
        var error = form.querySelector("[data-passkey-error]");
        var assertion = form.querySelector("input[name=\"assertion\"]");
        if (!control || !assertion) return;
        var saved = form.getAttribute("data-saved") || "0";
        var challenge = ahead(win, "/account/passkeys/reauth-options", function () {
            return { route: routeOf(win, form) };
        });
        function value() {
            if (control.type === "checkbox") return control.checked ? control.value : "0";
            return control.value;
        }
        function revert() {
            if (control.type === "checkbox") control.checked = saved !== "0";
            else control.value = saved;
        }
        function prefetch() {
            if (!challenge.ready()) challenge.fetch().then(null, function () {});
        }
        control.addEventListener("pointerdown", prefetch);
        control.addEventListener("focus", prefetch);
        control.addEventListener("change", function () {
            if (value() === saved) return;
            show(error, "");
            promptWith(win, challenge).then(function (cred) {
                if (!cred) return revert();
                assertion.value = JSON.stringify(credentialJSON(cred));
                send(form);
            }, function (err) {
                revert();
                if (!cancelled(err)) show(error, (err && err.name === "refused" && err.message) || w(error, "failed"));
            });
        });
    }

    // ---- The page -------------------------------------------------------------------------------------------

    function install(win) {
        var doc = win.document;
        var i;
        if (!supported(win)) {
            var rows = doc.querySelectorAll("[data-passkey-unsupported]");
            for (i = 0; i < rows.length; i++) {
                var text = rows[i].querySelector("[data-passkey-unsupported-text]") || rows[i];
                text.textContent = win.isSecureContext ? w(rows[i], "browser") : w(rows[i], "http");
                rows[i].hidden = false;
            }
            return false;
        }
        var ui = doc.querySelectorAll("[data-passkey-ui]");
        for (i = 0; i < ui.length; i++) ui[i].hidden = false;
        var login = doc.getElementById("passkey-sign-in");
        if (login) installLogin(win, login);
        var buttons = doc.querySelectorAll("[data-passkey-reauth]");
        for (i = 0; i < buttons.length; i++) installReauth(win, buttons[i]);
        var adds = doc.querySelectorAll("[data-passkey-add]");
        for (i = 0; i < adds.length; i++) installAdd(win, adds[i]);
        var unlock = doc.querySelector("[data-passkey-unlock]");
        if (unlock) installUnlock(win, unlock);
        var locks = doc.querySelectorAll("[data-app-lock]");
        for (i = 0; i < locks.length; i++) installAppLock(win, locks[i]);
        return true;
    }

    var api = {
        install: install,
        b64url: b64url,
        unb64url: unb64url,
        requestOptions: requestOptions,
        creationOptions: creationOptions,
        credentialJSON: credentialJSON,
        supported: supported,
        routeOf: routeOf,
        encode: encode,
        AHEAD_MS: AHEAD_MS,
        UNLOCK_REFRESH_MS: UNLOCK_REFRESH_MS,
        LAST_PASSKEY_KEY: LAST_PASSKEY_KEY,
    };
    if (typeof module !== "undefined" && module.exports) {
        module.exports = api;
    } else if (root.document) {
        install(root);
    }
})(this);
