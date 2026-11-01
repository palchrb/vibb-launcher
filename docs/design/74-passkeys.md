# 74 - Passkeys for parents, passkey-only accounts, the app lock

Status: design note, 2026-11-01, on `main` at 72134f83, revised after the design QA the same day (3 High, 5 Medium,
the Lows; PLAN: "Passkeys for parents, passkey-only accounts, a Face ID lock on opening", approved, in design).
Server only (WebAuthn in the PWA); no launcher or music change, no shared contract file. The user, 2026-11-01: "La oss legge inn at man kan selv velge å legge inn en passkey inne på accounts og
parents? Der kan man vel også det velge å kun bruke passkey for kontoen din og ikke tillate passord innlogging? Eller
er det farlig? Og også da velge om man skal velge og kreve biometric og passkey etter x min for pwa?"

**Today** (`docs/notes/server/connectivity.md`, "Admin auth", "Parent accounts"): password (argon2 behind
`PasswordGate`) then a mandatory TOTP step (`pending_admin_id`, 5 min); the session cookie `__Host-handy`, 30 days
unused (`SESSION_INACTIVITY_DAYS`) and 90 days at most (`SESSION_MAX_DAYS`, `SIGNED_IN_AT_KEY`); `session_epoch` ends
every session of an account; up to 8 equal parents (design 62), a not-ready one onboarded by a 72 h one-time password,
another parent's **Reset…** (`parents::reset_parent`) and the shell's `reset-2fa <user>` as the ways back; the
password re-asked by Account's password change, Reset Two-Factor Login, Add Parent, Withdraw, Reset… and Lock Screen &
PINs' Show Child PIN, counted by `admin_auth::reauth` (3 wrong in a row end every session).

Each decision below: **the recommendation**, then the alternatives in one line.

## 1. Passkeys as an added sign-in

**D1.1 - Who and how many.** Every ready parent can add passkeys for their own account, up to 10, each with a name
(prefilled from the browser: "iPhone", "Android", "Mac", "Security key"), its time added, last used (time and from
where, `Names::logged`'s form) and whether it is synced. Only you add or remove your own; another parent sees the count
(§6.3) and can Reset you (§2). A not-ready account (a one-time password, no 2FA yet) adds none. *Alt: no cap / one
passkey per parent.*

**D1.2 - Adding asks for the password again** (or an existing passkey). A session someone else holds for a minute must
not be able to plant a lasting way in that survives a password change. Two requests: `POST /account/passkeys/options`
(the name and the re-auth, through `admin_auth::reauth`; answers the creation options as JSON with a `register`
challenge, D1.7) and `POST /account/passkeys` (the browser's answer, form-encoded `credential` = JSON, 8 KiB body), PRG
`/account?done=passkey_added#passkey-<id>`. `passkey_added` is also pushed to every parent and put in their inbox
(like `parent_added`): "Mamma la til en passkey «iPhone»" - a planted passkey is seen at once. Options: `rp {id, name: "Vibb"}`, `user {id: webauthn_user_id, name:
username, displayName: display name}`, `pubKeyCredParams` ES256 (-7), EdDSA (-8), RS256 (-257), `authenticatorSelection
{residentKey: "required", userVerification: "required"}`, `attestation: "none"`, `excludeCredentials` = this parent's
passkeys for that RP ID, `timeout` 300 000. `webauthn_user_id` is 16 random bytes per account, made at the first add
- never the row id or the username, so a passkey names nobody. *Alt: no re-auth (a held session could add one).*

**D1.3 - Removing** (`POST /account/passkeys/{id}/remove`, a confirm with the re-auth) deletes the row and moves
`session_epoch` keeping this session, as a password change does: a lost phone's session ends with its passkey. PRG
`/account?done=passkey_removed#passkeys`. Removing the last passkey of a passkey-only account is refused (§2.3), the
count checked inside the `DELETE` itself (`... AND NOT (passkey_only AND (SELECT COUNT(*) ...) <= 1)`), never by a read
before it. *Alt: remove only the passkey, sessions stay (a lost phone stays signed in up to 30 days).*

**D1.3b - The way back deletes passkeys from step 1 on**: `parents::reset_parent` (another parent's Reset…), a soft
Remove, and `reset-2fa <user>` (with its alias `reset-sign-in <user>`) delete the account's passkeys in the same
transaction, and the password-change and Reset Two-Factor Login forms list your passkeys under them ("Passkeyene dine
virker fortsatt: iPhone, Mac - fjern dem under Passkeys om en er på avveie"), so no reset leaves a lost phone's
passkey working.

**D1.4 - The sign-in page offers "Logg inn med passkey"** beside username + password, with discoverable credentials:
`GET /auth/passkey/options` (signed out, behind the admin gate and the per-IP ban - a banned key gets 429, and each
options call counts toward the login's rate limit, so the challenge store can't be flooded; `allowCredentials: []`,
`userVerification: "required"`, a `login` challenge, D1.7) and `POST /auth/passkey` (form `assertion` = JSON, 4 KiB
like `/login`). The `userHandle` finds the account (`webauthn_user_id`; here it is required, with `allowCredentials`
empty), the credential id must be that account's, and **the account must be ready** by the one predicate of D2.2
(not removed, no pending invite or forced password change): a not-ready account is refused with the same red line as a
bad assertion. Conditional UI where it works: the
username field is `autocomplete="username webauthn"` and `static/passkey.js` starts a `mediation: "conditional"` get
when `PublicKeyCredential.isConditionalMediationAvailable()` says so; the button is always there too (iOS standalone
and older Android Chrome don't offer passkeys in autofill reliably).

**D1.5 - A passkey sign-in is both factors**: user verification required (Face ID, fingerprint, the phone's code, a
security key's PIN) is something you have plus something you are or know. Success is the same completion as
`verify_2fa`: `cycle_id`, `SIGNED_IN_AT_KEY`, `reset_after_login`, `login_success` with detail "passkey · iPhone", the
`next` page or Devices; no TOTP page. A bad assertion (signature, origin, challenge, an unknown credential) is one red
line "Passkeyen virket ikke. Prøv igjen, eller logg inn med passord." and counts in the per-IP ban as a wrong password
does (`net::limit_key`); a cancelled prompt (`NotAllowedError`) is the browser's and sends nothing. *Alt: a passkey
replaces only the password and TOTP still follows (no gain on a phone).*

**D1.6 - Re-auth by passkey.** Every form that asks for the password again (the list above) gets a "Bekreft med
passkey" button beside the field when this parent has a passkey for this host: it fetches a `reauth` challenge (bound to
the form's route), runs a get with `allowCredentials` = this parent's passkeys and puts the assertion in a hidden
`assertion` field; `admin_auth::reauth` takes either. A bad assertion counts in `reauth_wrong` like a wrong password.
With `allowCredentials` set (re-auth, the lock, the switch) `userHandle` may be null: the credential id alone finds the
row, which must be the signed-in parent's; a non-null `userHandle` must match it.

**D1.7 - Challenges live server-side, not in the session copy.** Each request loads and writes back its own copy of the
tower-session, so a challenge kept there could be taken twice by two requests in flight. Instead an in-memory
`AppState.challenges` (a `Mutex<HashMap>`, at most 1000 entries, 5 min, swept on insert; a restart drops them - the
ceremony is simply tried again): an entry is {32 random bytes, kind (`register`/`login`/`reauth`/`unlock`/`switch`),
the session id, the admin id and `session_epoch` (none for `login`), the rp_id, the route it is for}. `take(challenge,
kind, session id, admin, rp_id, route)` removes the entry under the lock and answers it only when every field matches -
one use, atomic. A sign-in's session id changes at `cycle_id`, after the take.

## 2. "Kun passkey" (passkey only) - safe with guard rails

Is it dangerous? No, if the way back never needs the password. A passkey (UV required, phishing-proof, bound to the
domain) is stronger than password + TOTP; the risk is losing every passkey. The guard rails:

**D2.1 - Switching on needs a way back.** Allowed when the account has **at least 2 passkeys, or 1 passkey and another
ready parent** (who can Reset it) - counted inside the switch's `UPDATE` (its `WHERE` holds both counts), never by a
read before it, so a removal in flight can't slip between. The switch itself asks for a passkey assertion now (proves one works on this
host). Otherwise the row is disabled with the reason: "Legg til en passkey til først (eller en forelder til)."
*Alt: 1 synced passkey is enough (iCloud/Google backs it up, but a lost Apple ID is the same lock-out) / ≥2 always.*

**D2.2 - What it does** (one `UPDATE ... AND session_epoch = ? AND removed_at IS NULL`): `passkey_only = 1`,
`password_hash = '!passkey'` (no argon2 string matches it, as `'!removed'`), `totp_secret = NULL, totp_enabled = 0,
totp_last_step = NULL`, `session_epoch + 1` keeping this session; `passkey_only_on`. Nothing dormant is left to steal
from a backup. **One readiness predicate** (`AdminUser::ready`, and its SQL twin for the statements' conditions): "not
removed, no unexpired-invite or forced-change state, and ((password changed and 2FA on) or (passkey_only and at least 1
passkey))"; `load_active_admin`/`require_full_auth`, the passkey sign-in, Add's and Reset's actor conditions and the
counts of "another ready parent" all use it. While `passkey_only` is on, `/auth/setup-2fa` and
`/auth/change-password` (GET and POST) refuse (back to Account `#passkey-only`), so TOTP or a password can't creep back
beside it; only D2.5 turns them on. `login` with a passkey-only account's name verifies **the dummy hash** (the same
argon2 time as an unknown name) and says the usual "wrong username or password" (no hint which accounts are
passkey-only); the log line says "passkey only". *Alt: keep the password and TOTP dormant
(easier to switch off, but a secret with no use).*

**D2.3 - The invariant: a passkey-only account always has at least 1 passkey.** Removing the last one is refused ("Slå
på passord først"), by the `DELETE`'s own condition (D1.3). Going down to 1 with no other ready parent is allowed with the line "Bare én passkey igjen, og
ingen annen forelder kan nullstille deg. Mister du den, er serverens kommandolinje veien tilbake."

**D2.4 - The ways back, none needing the password.** (a) **Another parent's Reset…** (as today on a ready row):
`reset_parent`'s statement also sets `passkey_only = 0`; the same transaction deletes the target's passkeys from step 1
on (D1.3b); the target gets a 72 h one-time password and onboards as an invited
parent; the confirm says "Passkeyene til Pappa fjernes også." (b) **The last parent**: `kid_phone_server reset-2fa
<user>` (Docker: `docker exec`, as DEPLOY.md has it today) also clears `passkey_only` (the deletion of the passkeys
is step 1's, D1.3b), then writes `data/initial-admin-password-<id>` as today; `reset-sign-in <user>` is added as a clearer alias.
So the last remaining parent can't lock themselves out while they have the shell; a server without shell access was
never recoverable either. *Alt: recovery codes printed at switch-on (one more secret to keep; the shell already is it).*

**D2.5 - Switching off** is a page `/account/password-sign-in` (passkey assertion, new password twice, the TOTP QR
and its code, one POST, one `UPDATE`), so the account is never in between; `passkey_only_off`, PRG
`/account?done=password_on#password`. Passkeys stay.

## 3. The app lock ("Lås med Face ID")

**D3.1 - The setting**: Av / 1 min / 5 min / 15 min / 1 time (default Av; 5 min selected first when turning it on).
It belongs to **this browser on this device**: stored in the session (`lock_minutes`) and in a cookie
`__Host-handy-lock` (the minutes only; Secure, HttpOnly, Strict, 400 days) that a new sign-in in this browser copies
into its session - **only when that parent has a passkey for this RP ID** (else the lock could never be opened: the
cookie is left alone and the lock stays off in that session), so the 90-day re-sign-in doesn't silently turn it off; Av
deletes it. When a parent loses their last passkey for an RP ID (a removal, a Reset), every session of theirs at that
RP ID gets `lock_minutes = 0` (removal and Reset already end the other sessions; this one is cleared in the same
request), so no session loops on a lock it can't open. The cookie is a convenience, not
a guard: whoever deletes it still needs a full sign-in. `POST /account/lock`, PRG `/account?done=lock#app-lock`,
`app_lock_set` "5 min". *Alt: per account on every device (a laptop without a passkey would be locked out) / session
only (gone at each re-sign-in).*

**D3.2 - Turning it on needs a passkey that works here**: the form runs an assertion first (`allowCredentials` = this
parent's passkeys for this RP ID). No passkey for this host, or http: the row is disabled with the reason ("Legg til en
passkey på denne enheten først" / "Trenger https"). *Alt: fall back to the password on the lock screen (a weaker unlock
than the sign-in, the user's rule against insecure fallbacks).*

**D3.3 - "Uten bruk"** = N minutes in which the parent did nothing in the app:
- **Server** (the enforcer): the session's `used_at`, moved only by **a user-activated navigation** (`Sec-Fetch-User:
  ?1` - a tapped link, a tab, a typed address), **a save** (any unsafe method) and **the page's own input beacon**
  (`POST /api/used`, sent by `static/app-lock.js` on a pointer or key event at most once a minute while the page is
  visible). A scripted `location.reload()` (`auto-reload.js`, `install-now.js`, `pull-refresh.js`, `reconnect.js`'s
  return reload) is a navigate request too but carries no `Sec-Fetch-User`, so it never counts; nor does a script's GET
  (`/ping`, `/api/session`, Locate's and Install Now's polls, music cards). A browser that sends no `Sec-Fetch-*`
  (Safari before 16.4) moves it by saves and the beacon only. A page lying open on the table locks. Written only when it
  moved 30 s or more (no session write per poll).
- **Client** (so nothing private shows first): pages of a lock-on session carry `data-lock-ms` on `<html>`;
  `app-lock.js` keeps the last input time **in sessionStorage** (`handy-used`, so it survives the page's own reloads
  and navigations; unreadable = now, the server still enforces) and `reconnect.js`'s `hiddenAt`. Once the lock is due,
  the scripted reloads stop: `auto-reload.js`, `install-now.js`, `pull-refresh.js` and `reconnect.js` ask
  `window.handyLock.due()` first and go to the lock instead. When the page becomes visible (or comes back
  from the back-forward cache) after N min, or N min pass on screen without input, it sets `html.locked` (CSS hides
  `body > *`) and goes to `/lock?next=<this path>` at once, before `reconnect.js`'s return reload. On `hidden` it sets
  `html.veiled` too (best effort: the iOS app switcher's snapshot may be taken before it).
- The server locks at `used_at + N + 1 min` (slack for the beacon's minute), the client at last input + N, so a save
  tapped just in time isn't refused.

**D3.4 - Enforcement**: in `security::admin_or_why` (the one place `require_full_auth`, `require_session` and
`session_check` read the session through), when `lock_minutes > 0` and the session is past it, a new `Why::Locked`:
a navigation answers 303 `/lock?next=<path?query>` (a local path, `localPath`'s rule); any other request 423 with no
body; `/api/session` answers 423 too, and `reconnect.js` treats 423 as "go to /lock". **An explicit exempt list**
(`lock::EXEMPT`, by matched route): `/lock`, `/lock/options`, `/logout`, `/static/*`, `/sw.js`, the manifest and its
icons, `/healthz`, `/notifications/receipt` (its token is its proof), the device API (no session). Everything else
behind a session is locked, the onboarding routes behind `require_session` too; a test walks every router group
(admin, host, `require_session`, the JSON routes, `/api/session`) and asserts locked-or-exempt. A POST
refused by the lock loses what was typed - accepted, since the client locks first.

**D3.5 - The lock screen** (`/lock`, §6.5) shows nothing private. Unlock: `GET /lock/options` + `POST /lock` with an
assertion whose credential is **the signed-in parent's** (`userHandle` may be null with `allowCredentials` set; when
present it must match; another parent's passkey doesn't unlock your session); success moves `used_at` and goes to `next`. A bad assertion counts in `reauth_wrong` (3 end every
session, as today). The prompt always waits for a tap ("Lås opp"): iOS needs a user gesture for WebAuthn, and one
behaviour on both is easier to trust. "Logg ut" signs this session out (Settings' Sign Out form, the push endpoint
included). Logged: `app_unlocked`, merged per parent and hour ("×4") so the Security Log isn't flooded;
`app_unlock_failed` per bad assertion.

**D3.6 - Pushes while locked**: notifications show as today (their text is the phone's own lock screen's business);
a tap's receipt marks the event read as today, the service worker opens the path, the server answers `/lock?next=…`
and the unlock lands there. The badge is unchanged.

**D3.7 - The session stays 30 days unused / 90 at most**: the lock neither extends nor shortens it; an unlock is
not a sign-in (`SIGNED_IN_AT_KEY` stays, the 90-day clock runs on), but the session id is cycled (`cycle_id`), cheap
and safe.

## 4. RP ID and hostnames

**D4.1 - The RP ID is the request's host by default**: `Host`, or `X-Forwarded-Host` from a trusted peer (exactly
the host `csrf_guard` compares), lowercased, port dropped, one trailing dot dropped (`handy.example.com.` is
`handy.example.com`); an IP literal or a name that isn't a valid DNS name gets no passkey UI. Optional
`PASSKEY_RP_ID=example.com` sets one RP ID for every host under it (`handy.example.com`, `lan.example.com`); it is
**checked at start** (a valid lowercase DNS name with a dot or `localhost`, not an IP, not a public suffix such as `com`
or `co.uk` - a short built-in list of the common ones, the browser refuses the rest anyway; a bad value refuses to start
with the reason), and a host outside it gets no passkey UI, which the start-up exposure line says. Each passkey stores its `rp_id`; an assertion is checked against **the stored** `rp_id` (`rpIdHash`) and
an origin that fits it, so a forged Host header can only propose an RP ID, never pass a credential made elsewhere.
*Alt: `PASSKEY_RP_ID` required (nothing until configured) / Related Origin Requests (`/.well-known/webauthn`, Chrome
128+, Safari 18+, not Firefox) for unrelated domains - later if asked.*

**D4.2 - Origins**: `clientDataJSON.origin` must be **exactly this request's origin**: `https://` + the host D4.1
derived (Host or the trusted `X-Forwarded-Host`, normalized) + its port when not 443 - never "any host ending in
`.<rp_id>`". Only the RP ID may be a registrable suffix of the host; the origin is one host, so a sibling subdomain
under a shared `PASSKEY_RP_ID` (another service at `wiki.example.com`) can't relay an assertion it got there.
Optional `PASSKEY_ORIGINS` (a comma list of full origins, each checked at start to fall under the RP ID) is the
allowlist for a proxy whose outside name differs from what reaches the server. `crossOrigin` must be false or absent.
`http://localhost[:port]` passes **only with `INSECURE_COOKIES`** (development); nothing else on http. An IP address is
never an RP ID (browsers refuse it).

**D4.3 - A second hostname** (the public name and the tailnet name): a passkey made at one doesn't work at the other.
The sign-in page shows the passkey button **whenever the browser supports passkeys** (a secure context with
`PublicKeyCredential`), never depending on which hosts have passkeys - so the button reveals nothing about the
family's other addresses; at a host without any, the browser's own sheet says there is no passkey for this site (or
offers another device's QR), and the password stays beside it. No hostname is named on the page. Account
(signed in) says it: each row "for handy.example.com" when the family's passkeys span more than one RP ID, and the
group's footer "Passkeys du legger til her virker bare på <host>. På andre adresser logger du inn med passord." A
passkey-only parent at a host without their passkey can't sign in there - said at switch-on when the server has seen
other hosts (the hosts of the account's sessions and passkeys).

**D4.4 - http on the LAN**: not a secure context, `PublicKeyCredential` is undefined; `passkey.js` hides every
passkey control and Account shows one row "Passkeys trenger https (denne siden er http)". The lock can't be turned on
there. `localhost` is a secure context (works).

**D4.5 - iOS** (home-screen PWA, standalone): WebAuthn works there since iOS 16 with iCloud Keychain, and with
1Password/Bitwarden as providers since iOS 17; a passkey made in Safari works in the PWA (Keychain, same RP ID); every
`create`/`get` needs a tap; conditional UI in standalone mode is unreliable, hence the button. Face ID's prompt falls
back to the phone's code by itself. **Android**: Chrome and its installed WebAPK, passkeys in Google Password Manager
or (Android 14+) any Credential Manager provider; Firefox for Android is patchy (the password stays the way in); a
phone without Play services needs a provider app. **Desktop**: Chrome, Safari, Firefox, Edge; a phone's passkey via the
QR "hybrid" flow (same RP ID); a security key with a PIN (resident keys required).

## 5. The verifier, storage, events

**D5.1 - An own minimal verifier on `ring`, not `webauthn-rs`.** `webauthn-rs` (0.5) is MPL-2.0, which is
GPL-3.0-compatible (MPL §3.3, its files stay MPL), but `webauthn-rs-core` depends on `openssl`: a C library into
the static musl builds on both native runners and the distroless image (vendored OpenSSL, perl, minutes per build) and a
second crypto stack beside ring, for attestation formats we will never read. With `attestation: "none"` the server's
job is small: `src/webauthn.rs` (~450 lines + tests) on `ring` (in the tree for Web Push), `sha2`, `base64`, `serde_json`:
- a bounded CBOR reader (definite lengths only, depth ≤ 4, unsigned/negative ints, byte and text strings, arrays,
  maps; any other major type, an indefinite length or trailing bytes is a refusal; ~120 lines) for
  `attestationObject` and the COSE key;
- keys: ES256 (COSE kty 2, crv 1, x/y → the uncompressed point, `ECDSA_P256_SHA256_ASN1`), EdDSA (kty 1, crv 6,
  `ED25519`), RS256 (kty 3, n/e, `RsaPublicKeyComponents` with `RSA_PKCS1_2048_8192_SHA256`); stored as the COSE bytes;
- registration: `type` `webauthn.create`, the challenge (D1.7's atomic take: one use), the origin (§4.2), `rpIdHash`
  = sha256(rp_id), flags UP, UV and AT set, BS without BE refused; the attested credential data read in order (AAGUID,
  the id length, the id, the COSE key as one CBOR item) and, **when ED is set, the extensions map after it** (read as
  one bounded CBOR map and dropped; ED unset with bytes left over, or ED set with none, is a refusal; no trailing bytes
  after either); **the authData credential id equal to `rawId` and to `id`'s base64url**, 16-1023 bytes, unique in the
  table; the COSE key's `alg` (3), `kty` (1) and `crv` (-1) agreeing with each other (-7 = kty 2 + crv 1, -8 = kty 1 +
  crv 6, -257 = kty 3) and the alg one of the requested three; the key parsing (P-256 point on the curve, Ed25519 32
  bytes, RSA modulus 2048-8192 bits); `fmt` any, `attStmt` ignored (we trust no attestation);
- assertion: `type` `webauthn.get`, challenge, origin, `rpIdHash` against the credential's stored `rp_id`, UP and UV,
  the BE flag equal to the stored one, the signature over `authenticatorData || sha256(clientDataJSON)`, then the
  counter rule (D5.3); `userHandle` as D1.4 and D1.6 say.
*Alt: `webauthn-rs` with vendored OpenSSL (more code we don't use, a C build) / a RustCrypto stack (`p256`, `coset`;
new crates to verify).*

**D5.2 - Storage**: migration **`0116_passkeys.sql`** (the next free number on `main` today; take the next free one at
the build if `main` moved; the file is frozen from its first push):

```sql
CREATE TABLE admin_passkeys (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    admin_id INTEGER NOT NULL REFERENCES admin_users(id),
    credential_id BLOB NOT NULL UNIQUE,
    public_key BLOB NOT NULL,        -- the COSE key as registered
    alg INTEGER NOT NULL,            -- -7, -8 or -257
    rp_id TEXT NOT NULL,
    name TEXT NOT NULL,              -- security::display_name's rule, 1-32 characters
    sign_count INTEGER NOT NULL DEFAULT 0,
    backup_eligible INTEGER NOT NULL,
    backed_up INTEGER NOT NULL,
    transports TEXT,                 -- getTransports() as JSON, for allowCredentials
    created_at TEXT NOT NULL DEFAULT (datetime('now')),
    last_used_at TEXT,
    last_used_from TEXT
);
CREATE INDEX admin_passkeys_admin ON admin_passkeys(admin_id);
ALTER TABLE admin_users ADD COLUMN webauthn_user_id BLOB;
```

Step 2's own migration (the next free number then) adds `admin_users.passkey_only INTEGER NOT NULL DEFAULT 0`. The lock
needs no table. Passkeys are in backups with `admin_users`; a restore brings back a passkey removed since, so Backups'
restore confirm says "Foreldrekontoer og passkeys kommer tilbake som de var i denne sikkerhetskopien." A soft Remove
of a parent deletes their passkeys in its transaction.

**D5.3 - Counters and backup flags**: most synced passkeys always send 0. When both the stored and the new counter
are non-zero and the new is not higher, the sign-in is refused and `passkey_cloned` logged ("possibly copied").
The store is atomic: `UPDATE admin_passkeys SET sign_count = ?1, ... WHERE id = ?2 AND (?1 = 0 OR sign_count < ?1)`
- no row (a parallel use already stored as high a counter) is the same refusal, so two replays of one assertion can't
both pass. BE is fixed at registration (an assertion with another BE is refused); BS is updated on every use (it can
change) and shown as "Synkronisert" / "Bare på denne enheten". *Alt: log and allow (webauthn-rs's default).*

**D5.4 - Security log events** (each with a label in `admin::event_label`, pinned by
`every_written_event_has_a_label`, and `security.event.*` in `server/src/i18n/{en,nb}.json`):

| id | en | nb |
|---|---|---|
| `passkey_added` | Passkey added | Passkey lagt til (also a push and an inbox entry to every parent, D1.2) |
| `passkey_removed` | Passkey removed | Passkey fjernet |
| `passkey_refused` | Passkey refused | Passkey avvist |
| `passkey_cloned` | Passkey refused: possibly copied | Passkey avvist: kan være kopiert |
| `passkey_only_on` | Passkey only switched on | Kun passkey slått på |
| `passkey_only_off` | Password sign-in switched on again | Passordinnlogging slått på igjen |
| `app_lock_set` | App lock changed | Applås endret |
| `app_unlocked` | App unlocked | Appen låst opp |
| `app_unlock_failed` | App unlock failed | Opplåsing mislyktes |

`login_success` keeps its label, detail "passkey · <name>". Each writer writes its own event, so `settings_log` adds no
second row. Every page string goes through the i18n layer (nb + en, design 70).

## 6. Pages (the grouped-list style; every save lands on its anchor)

**6.1 Settings › Account** (`/account`), two new groups between Two-Factor Login and Sessions:

*Passkeys* (`#passkeys`)
- One row per passkey (`#passkey-<id>`): title the name; sub "Lagt til 1. nov · Sist brukt i dag 14:02" and
  "Synkronisert" / "Bare på denne enheten" (+ "for <host>" when several RP IDs); trailing **Fjern…** - a confirm
  "Fjern «iPhone»? Andre innlogginger av deg logges ut." with the re-auth, Fjern (red) / Avbryt →
  `#passkeys`.
- **Legg til passkey…** (`#add-passkey`, a `<details>`): Navn (prefilled), the re-auth (password field and/or
  "Bekreft med passkey"), **Lag passkey** (filled) → the new row `#passkey-<id>`; a refusal renders here, open, the
  reason under the field.
- **Kun passkey** (`#passkey-only`): a switch row, sub "Ingen passord eller kode - bare passkey"; on → a confirm with
  what it does and the way back ("En annen forelder kan nullstille deg; ellers serverens kommandolinje"), "Bekreft
  med passkey" → `#passkey-only`; disabled with its reason (D2.1). When on, the Password and Two-Factor groups are one
  row "Passord og kode: av" with **Slå på passord igjen…** (D2.5).
- Footer: "Logg inn med Face ID, fingeravtrykk eller en sikkerhetsnøkkel i stedet for passord og kode." (+ D4.3's line).
- No support / http: the group is one row "Passkeys trenger https" (D4.4).

*Lås appen* (`#app-lock`)
- **Lås med Face ID** - a select row: Av / 1 min / 5 min / 15 min / 1 time; sub "Gjelder denne enheten. Lås opp med
  passkey (Face ID, fingeravtrykk eller telefonens kode)." Choosing a time runs the assertion, then the form posts →
  `#app-lock` with the group's flash "Lagret". Disabled with its reason (D3.2).

**6.2 The re-auth fields** everywhere (Show Child PIN, Add Parent, Withdraw, Reset…, Reset Two-Factor Login,
the password change): "Bekreft med passkey" (outline) beside or instead of the password field; the refusal under it.
The password change (`#password`) and Reset Two-Factor Login (`#reset-2fa`) also list your passkeys under the button
(D1.3b's line, each name a link to its row `#passkey-<id>`).

**6.3 The Parents group** (`#parents`): each ready row's sub gets "2 passkeys" and "· Kun passkey" (nothing for 0);
**Reset…**'s confirm on a row with passkeys adds "Passkeyene til Pappa fjernes også." Results land at `#parents`
(as today).

**6.4 The sign-in page** (`/login`, `.auth-card`): the name, "Logg inn"; the red refusal line above the form; Brukernavn
(`autocomplete="username webauthn"`), Passord, **Logg inn** (filled); a thin "eller" divider; **Logg inn med passkey**
(outline, a key glyph) - shown by `passkey.js` whenever the browser supports passkeys (D4.3). A passkey
sign-in goes straight in (no 2FA page).

**6.5 The lock screen** (`/lock`, `auth-page`, no tab bar, `no-store`, `<title>Vibb</title>`): the Vibb mark; large
title "Låst"; sub "Lås opp for å fortsette."; **Lås opp** (filled, wide); after a failed or cancelled try the red line
"Fikk ikke låst opp. Prøv igjen, eller logg ut og logg inn på nytt."; at the bottom **Logg ut** (a plain text button,
a POST). No name, no phone, no count, nothing from the page behind.

## 7. Tests and steps

**Tests.** `webauthn::tests`: the CBOR reader's bounds (depth, indefinite, trailing bytes, huge lengths), each alg
against vectors in `server/testdata/webauthn/` (recorded once from an iPhone, an Android phone and a security key, plus
WebAuthn Level 3's test-vector appendix, plus **registrations with an extensions map** (`credProps`, `credProtect`,
ED set) and the refusals of ED set without one and ED unset with bytes left), every refusal (origin - a sibling
subdomain under the RP ID included -, `crossOrigin`, rpIdHash, a used challenge, UV unset, BS without BE, BE changed,
counter regression, the authData id differing from `rawId` or `id`, a COSE key whose alg, kty and crv disagree or
whose alg wasn't requested, a credential id too long). `config` tests: `PASSKEY_RP_ID` (an IP, `com`, `co.uk`,
upper case refused at start), `PASSKEY_ORIGINS` outside the RP ID refused, a trailing dot in Host, localhost only with
`INSECURE_COOKIES`. `src/tests/passkeys.rs` with a `FakeAuthenticator` (ring-generated ES256 and Ed25519 keys signing
real `authenticatorData`): add needs the re-auth and pushes `passkey_added` to every parent; sign-in skips 2FA and
logs; a removed, expired-invite or forced-change account refused; another parent's passkey can't sign in as you; a
null `userHandle` with `allowCredentials` passes, a wrong one fails; removal ends the other sessions; **Reset…, a soft
Remove and `reset-2fa`/`reset-sign-in` delete the passkeys** (step 1); bad assertions and the signed-out options GET
count in the per-IP ban, a banned key's options are 429; `reauth_wrong`; two parallel takes of one challenge - one
wins; two parallel uses of one counter - one wins; a challenge from another session, kind, route or epoch refused; a
forged Host can't use a credential of another RP ID; the button shows at a host with no passkeys. Step 2: the
switch's guards counted in the statement (a parallel removal), the dummy hash for a passkey-only name (the same
`PasswordGate` path as an unknown name), setup-2fa and change-password refused while on, Reset and `reset-2fa`
clearing the flag, the last passkey's removal refused, the switch-off page's single write, the one readiness
predicate in `require_full_auth`. Step 3: past N a navigation → `/lock?next=`, a fetch and `/api/session` → 423;
**every router group walked** (admin, host, `require_session`, JSON, `/api/session`) locked or in `lock::EXEMPT`; a
navigation without `Sec-Fetch-User` (a scripted reload) and a poll don't move `used_at`, a tapped one, a save and the
beacon do; the 1-min slack; the cookie carried into a new sign-in only with a passkey for this RP ID; losing the last
passkey turns the lock off in that parent's sessions (no loop); another parent's credential refused; a notification
path kept in `next`. JS: `jstest/passkey.test.js` (base64url, the JSON shapes, hidden without `PublicKeyCredential`),
`jstest/app-lock.test.js` (idle, hidden, back-forward cache, the sessionStorage input time across a reload, the beacon
throttle, the veil, `handyLock.due()`); `auto-reload.test.js`, `install-now.test.js`, `pull-refresh.test.js` and
`reconnect.test.js` (no reload once due, 423 → the lock). Templates: `no_template_has_inline_script`, `check_forms`'
anchors. Manual QA: iPhone PWA, Android Chrome WebAPK, Mac Safari, a security key, a second hostname, http on the LAN.

**Steps** (each its own branch, design QA before step 1, code QA after each):
1. **Passkeys + sign-in** (M): 0116, `src/webauthn.rs`, the challenge store, the routes, Account's Passkeys group
   (without the switch), the re-auth by passkey, the Parents count, the sign-in page, `static/passkey.js`, events and
   the `passkey_added` push, i18n, the restore line, the start-up checks of `PASSKEY_RP_ID`/`PASSKEY_ORIGINS`, and
   **Reset…, Remove and `reset-2fa`/`reset-sign-in` deleting the passkeys**; the one readiness predicate.
2. **Kun passkey + recovery** (S-M): its migration, the switch and its guards, the predicate's passkey-only arm, the
   setup-2fa/change-password refusals, the dummy hash, Reset and `reset-2fa` clearing the flag, the switch-off page,
   DEPLOY.md's recovery line.
3. **The lock** (M): the session keys and the cookie, `Why::Locked` in `admin_or_why` with `lock::EXEMPT`, `/api/used`,
   `/lock`, `static/app-lock.js`, the scripted reloads asking `handyLock.due()`, `reconnect.js`'s 423, the veil.

## 8. Open for the user

1. The lock's times: Av / 1 / 5 / 15 / 60 min - also "Med en gang" (every time the app has been away)?
2. The lock per device (this phone remembers it) - or follow the account to every device?
3. Kun passkey needs 2 passkeys, or 1 and another parent - or is 1 synced iCloud passkey enough?
4. The Norwegian word: "passkey" (yours), "passnøkkel" (Apple) or "tilgangsnøkkel" (Google)?
5. Which addresses do you open the PWA at - only the Caddy domain, or the tailnet name too? Passkeys work on one domain
   (or its subdomains with `PASSKEY_RP_ID`).
6. Should every unlock show in the Security Log (merged per hour), or only failures?
7. Face ID falls back to the phone's own code (the passkey's user verification is the phone's). If the child knows the
   code of your phone, the lock (and a passkey sign-in on that phone) is weak against them - change the code, or is
   that fine?
8. With `PASSKEY_RP_ID`: does that domain also host other services (a wiki, a NAS)? The origin check keeps them from
   relaying (D4.2), but a passkey for the whole domain is then also offered on their pages.
