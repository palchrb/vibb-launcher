// Passkeys in the PWA (design 74 step 1). Loaded by the pages that have passkey controls (the sign-in page, Account,
// Lock Screen & PINs), deferred. Every control is `[data-passkey-ui]`, hidden in the HTML: this script shows them only
// where the browser can use passkeys (a secure context with `PublicKeyCredential`, D4.4) and shows the
// `[data-passkey-unsupported]` rows instead where it can't ("needs https" on http, else "this browser can't").
// - The sign-in page's "Sign in with passkey" (`[data-passkey-login]`, D1.4): a `login` challenge from
//   `GET /auth/passkey/options`, `navigator.credentials.get` with UV required, the assertion posted in its form to
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

    // POSTs `fields` and answers {ok, status, json}.
    function post(win, url, fields) {
        return win.fetch(url, {
            method: "POST",
            credentials: "same-origin",
            headers: { "Content-Type": "application/x-www-form-urlencoded" },
            body: encode(fields),
        }).then(function (res) {
            return res.json().then(
                function (json) { return { ok: res.ok, status: res.status, json: json }; },
                function () { return { ok: res.ok, status: res.status, json: {} }; }
            );
        });
    }

    // The path a form posts to: a re-auth challenge is bound to it.
    function routeOf(win, form) {
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

    function installLogin(win, form) {
        var button = form.querySelector("[data-passkey-login]");
        var error = win.document.querySelector("[data-passkey-error]");
        var options = null;
        var fetchedAt = 0;
        var conditional = null;
        var creds = win.navigator.credentials;

        // One challenge serves the conditional get and the button's (it is spent only by a sign-in), for 4 of its 5
        // minutes.
        function fetchOptions() {
            if (options && Date.now() - fetchedAt < 240000) return win.Promise.resolve(options);
            return win.fetch("/auth/passkey/options", { credentials: "same-origin" }).then(function (res) {
                return res.json().then(function (json) {
                    if (!res.ok) throw { name: "refused", message: json.error || "" };
                    options = json;
                    fetchedAt = Date.now();
                    return json;
                });
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

        if (button) {
            button.addEventListener("click", function () {
                show(error, "");
                if (conditional) conditional.abort();
                fetchOptions().then(function (json) {
                    return creds.get({ publicKey: requestOptions(json) });
                }).then(function (cred) {
                    if (cred) signIn(cred);
                }, failed);
            });
        }

        var pkc = win.PublicKeyCredential;
        if (pkc && typeof pkc.isConditionalMediationAvailable === "function" && win.AbortController) {
            pkc.isConditionalMediationAvailable().then(function (available) {
                if (!available) return;
                conditional = new win.AbortController();
                var signal = conditional.signal;
                fetchOptions().then(function (json) {
                    return creds.get({ mediation: "conditional", publicKey: requestOptions(json), signal: signal });
                }).then(function (cred) {
                    if (cred) signIn(cred);
                }, function (err) {
                    if (!signal.aborted) failed(err);
                });
            }, function () {});
        }
    }

    // ---- Re-auth (D1.6) -------------------------------------------------------------------------------------

    // Runs the re-auth ceremony for `form`; resolves with true once its `assertion` holds one.
    function reauth(win, form, error) {
        var password = passwordField(form);
        return post(win, "/account/passkeys/reauth-options", { route: routeOf(win, form) }).then(function (answer) {
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

    function installReauth(win, button) {
        var form = button.closest("form");
        if (!form) return;
        var error = button.parentNode.querySelector("[data-passkey-error]");
        if (error && !error.getAttribute("data-w-failed")) error.setAttribute("data-w-failed", w(button, "failed"));
        button.addEventListener("click", function () {
            show(error, "");
            if (!othersValid(form, passwordField(form))) return;
            reauth(win, form, error).then(function (done) {
                if (!done) return;
                if (form.hasAttribute("data-passkey-add")) add(win, form);
                else send(form);
            });
        });
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
            if (answer.json.signed_out) {
                win.location.assign("/login");
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
            add(win, form);
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
    };
    if (typeof module !== "undefined" && module.exports) {
        module.exports = api;
    } else if (root.document) {
        install(root);
    }
})(this);
