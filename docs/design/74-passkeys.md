# 74 - Passkeys for parents, passkey-only accounts, the app lock

Status: design note, 2026-11-01, on `main` at 72134f83 (PLAN: "Passkeys for parents, passkey-only accounts, a Face ID
lock on opening", approved, in design). Server only (WebAuthn in the PWA); no launcher or music change, no shared
contract file. The user, 2026-11-01: "La oss legge inn at man kan selv velge å legge inn en passkey inne på accounts og
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
(the name and the re-auth, through `admin_auth::reauth`; answers the creation options as JSON, the challenge in the
session as `passkey_challenge` {32 random bytes, kind `register`, rp_id, at}, 5 min, one use) and `POST
/account/passkeys` (the browser's answer, form-encoded `credential` = JSON, 8 KiB body), PRG
`/account?done=passkey_added#passkey-<id>`. Options: `rp {id, name: "Vibb"}`, `user {id: webauthn_user_id, name:
username, displayName: display name}`, `pubKeyCredParams` ES256 (-7), EdDSA (-8), RS256 (-257), `authenticatorSelection
{residentKey: "required", userVerification: "required"}`, `attestation: "none"`, `excludeCredentials` = this parent's
passkeys for that RP ID, `timeout` 300 000. `webauthn_user_id` is 16 random bytes per account, made at the first add
- never the row id or the username, so a passkey names nobody. *Alt: no re-auth (a held session could add one).*

**D1.3 - Removing** (`POST /account/passkeys/{id}/remove`, a confirm with the re-auth) deletes the row and moves
`session_epoch` keeping this session, as a password change does: a lost phone's session ends with its passkey. PRG
`/account?done=passkey_removed#passkeys`. Removing the last passkey of a passkey-only account is refused (§2.3). *Alt:
remove only the passkey, sessions stay (a lost phone stays signed in up to 30 days).*

**D1.4 - The sign-in page offers "Logg inn med passkey"** beside username + password, with discoverable credentials:
`GET /auth/passkey/options` (signed out, behind the admin gate; `allowCredentials: []`, `userVerification:
"required"`, the challenge in the session, kind `login`) and `POST /auth/passkey` (form `assertion` = JSON, 4 KiB like
`/login`). The `userHandle` finds the account (`webauthn_user_id`), the credential id must be that account's, the
account read as `login` reads it (`removed_at IS NULL`, an unexpired invite). Conditional UI where it works: the
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

## 2. "Kun passkey" (passkey only) - safe with guard rails

Is it dangerous? No, if the way back never needs the password. A passkey (UV required, phishing-proof, bound to the
domain) is stronger than password + TOTP; the risk is losing every passkey. The guard rails:

**D2.1 - Switching on needs a way back.** Allowed when the account has **at least 2 passkeys, or 1 passkey and another
ready parent** (who can Reset it). The switch itself asks for a passkey assertion now (proves one works on this
host). Otherwise the row is disabled with the reason: "Legg til en passkey til først (eller en forelder til)."
*Alt: 1 synced passkey is enough (iCloud/Google backs it up, but a lost Apple ID is the same lock-out) / ≥2 always.*

**D2.2 - What it does** (one `UPDATE ... AND session_epoch = ? AND removed_at IS NULL`): `passkey_only = 1`,
`password_hash = '!passkey'` (no argon2 string matches it, as `'!removed'`), `totp_secret = NULL, totp_enabled = 0,
totp_last_step = NULL`, `session_epoch + 1` keeping this session; `passkey_only_on`. Nothing dormant is left to steal
from a backup. `AdminUser::ready` becomes "not removed and ((password changed and 2FA on) or (passkey_only and at least
1 passkey))". A password sign-in of the account verifies the dummy hash and says the usual "wrong username or password"
(no hint which accounts are passkey-only); the log line says "passkey only". *Alt: keep the password and TOTP dormant
(easier to switch off, but a secret with no use).*

**D2.3 - The invariant: a passkey-only account always has at least 1 passkey.** Removing the last one is refused ("Slå
på passord først"). Going down to 1 with no other ready parent is allowed with the line "Bare én passkey igjen, og
ingen annen forelder kan nullstille deg. Mister du den, er serverens kommandolinje veien tilbake."

**D2.4 - The ways back, none needing the password.** (a) **Another parent's Reset…** (as today on a ready row):
`reset_parent`'s statement also sets `passkey_only = 0`, and the same transaction deletes the target's passkeys (a
lost phone's passkey must not survive the reset); the target gets a 72 h one-time password and onboards as an invited
parent; the confirm says "Passkeyene til Pappa fjernes også." (b) **The last parent**: `kid_phone_server reset-2fa
<user>` (Docker: `docker exec`, as DEPLOY.md has it today) also clears `passkey_only` and deletes the account's
passkeys, then writes `data/initial-admin-password-<id>` as today; `reset-sign-in <user>` is added as a clearer alias.
So the last remaining parent can't lock themselves out while they have the shell; a server without shell access was
never recoverable either. *Alt: recovery codes printed at switch-on (one more secret to keep; the shell already is it).*

**D2.5 - Switching off** is a page `/account/password-sign-in` (passkey assertion, new password twice, the TOTP QR
and its code, one POST, one `UPDATE`), so the account is never in between; `passkey_only_off`, PRG
`/account?done=password_on#password`. Passkeys stay.

## 3. The app lock ("Lås med Face ID")

**D3.1 - The setting**: Av / 1 min / 5 min / 15 min / 1 time (default Av; 5 min selected first when turning it on).
It belongs to **this browser on this device**: stored in the session (`lock_minutes`) and in a cookie
`__Host-handy-lock` (the minutes only; Secure, HttpOnly, Strict, 400 days) that a new sign-in in this browser copies
into its session, so the 90-day re-sign-in doesn't silently turn it off; Av deletes it. The cookie is a convenience, not
a guard: whoever deletes it still needs a full sign-in. `POST /account/lock`, PRG `/account?done=lock#app-lock`,
`app_lock_set` "5 min". *Alt: per account on every device (a laptop without a passkey would be locked out) / session
only (gone at each re-sign-in).*

**D3.2 - Turning it on needs a passkey that works here**: the form runs an assertion first (`allowCredentials` = this
parent's passkeys for this RP ID). No passkey for this host, or http: the row is disabled with the reason ("Legg til en
passkey på denne enheten først" / "Trenger https"). *Alt: fall back to the password on the lock screen (a weaker unlock
than the sign-in, the user's rule against insecure fallbacks).*

**D3.3 - "Uten bruk"** = N minutes in which the parent did nothing in the app:
- **Server** (the enforcer): the session's `used_at`, moved by a navigation (`Sec-Fetch-Mode: navigate`; without the
  header a GET whose `Accept` has `text/html`), a save (any unsafe method) and the page's own input beacon (`POST
  /api/used`, sent by `static/app-lock.js` on a pointer or key event at most once a minute while the page is
  visible). A script's GET (`/ping`, `/api/session`, Locate's and Install Now's polls, music cards) never counts, so
  a page lying open on the table locks. Written only when it moved 30 s or more (no session write per poll).
- **Client** (so nothing private shows first): pages of a locked-capable session carry `data-lock-ms` on `<html>`;
  `app-lock.js` keeps the last input time and `reconnect.js`'s `hiddenAt`. When the page becomes visible (or comes back
  from the back-forward cache) after N min, or N min pass on screen without input, it sets `html.locked` (CSS hides
  `body > *`) and goes to `/lock?next=<this path>` at once, before `reconnect.js`'s return reload. On `hidden` it sets
  `html.veiled` too (best effort: the iOS app switcher's snapshot may be taken before it).
- The server locks at `used_at + N + 1 min` (slack for the beacon's minute), the client at last input + N, so a save
  tapped just in time isn't refused.

**D3.4 - Enforcement**: inside `require_full_auth`, when `lock_minutes > 0` and the session is past it: a navigation
answers 303 `/lock?next=<path?query>` (a local path, `localPath`'s rule); any other request 423 with no body;
`/api/session` answers 423 too, and `reconnect.js` treats 423 as "go to /lock". Not locked: `/lock*`, `/logout`,
`/static`, `/sw.js`, the manifest, `/healthz`, `/notifications/receipt` (its token is its proof), the device API. A POST
refused by the lock loses what was typed - accepted, since the client locks first.

**D3.5 - The lock screen** (`/lock`, §6.5) shows nothing private. Unlock: `GET /lock/options` + `POST /lock` with an
assertion whose credential and `userHandle` are **the signed-in parent's** (another parent's passkey doesn't unlock
your session); success moves `used_at` and goes to `next`. A bad assertion counts in `reauth_wrong` (3 end every
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
the host `csrf_guard` compares), lowercased, port dropped. Optional `PASSKEY_RP_ID=example.com` sets one RP ID for every
host under it (`handy.example.com`, `lan.example.com`); a host outside it gets no passkey UI, and the start-up exposure
line says so. Each passkey stores its `rp_id`; an assertion is checked against **the stored** `rp_id` (`rpIdHash`) and
an origin that fits it, so a forged Host header can only propose an RP ID, never pass a credential made elsewhere.
*Alt: `PASSKEY_RP_ID` required (nothing until configured) / Related Origin Requests (`/.well-known/webauthn`, Chrome
128+, Safari 18+, not Firefox) for unrelated domains - later if asked.*

**D4.2 - Origins**: `clientDataJSON.origin` must be `https://<host>[:port]` with the host equal to the `rp_id` or
ending in `.<rp_id>`, `crossOrigin` false or absent; `http://localhost[:port]` passes (development with
`INSECURE_COOKIES`), nothing else on http. An IP address is never an RP ID (browsers refuse it).

**D4.3 - A second hostname** (the public name and the tailnet name): a passkey made at one doesn't work at the other.
The sign-in page shows the passkey button only when some parent has a passkey for this host's RP ID (no hostname is
named there: the public page must not reveal the tailnet name); otherwise it is the password page as today. Account
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
- registration: `type` `webauthn.create`, the challenge (taken from the session before the check: one use), the
  origin (§4.2), `rpIdHash` = sha256(rp_id), flags UP, UV and AT set, BS without BE refused, the credential id 16-1023
  bytes and unique in the table, the alg one of the three, the key parsing; `fmt` any, `attStmt` ignored (we trust no
  attestation);
- assertion: `type` `webauthn.get`, challenge, origin, `rpIdHash` against the credential's stored `rp_id`, UP and UV,
  the signature over `authenticatorData || sha256(clientDataJSON)`, then the counter rule (D5.3).
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
are non-zero and the new is not higher, the sign-in is refused and `passkey_cloned` logged ("possibly copied");
otherwise the larger is stored. BS is updated on every use (it can change) and shown as "Synkronisert" / "Bare på
denne enheten". *Alt: log and allow (webauthn-rs's default).*

**D5.4 - Security log events** (each with a label in `admin::event_label`, pinned by
`every_written_event_has_a_label`, and `security.event.*` in `server/src/i18n/{en,nb}.json`):

| id | en | nb |
|---|---|---|
| `passkey_added` | Passkey added | Passkey lagt til |
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

**6.3 The Parents group** (`#parents`): each ready row's sub gets "2 passkeys" and "· Kun passkey" (nothing for 0);
**Reset…**'s confirm on a row with passkeys adds "Passkeyene til Pappa fjernes også." Results land at `#parents`
(as today).

**6.4 The sign-in page** (`/login`, `.auth-card`): the name, "Logg inn"; the red refusal line above the form; Brukernavn
(`autocomplete="username webauthn"`), Passord, **Logg inn** (filled); a thin "eller" divider; **Logg inn med passkey**
(outline, a key glyph) - shown only by `passkey.js`, only when supported and the host has passkeys (D4.3). A passkey
sign-in goes straight in (no 2FA page).

**6.5 The lock screen** (`/lock`, `auth-page`, no tab bar, `no-store`, `<title>Vibb</title>`): the Vibb mark; large
title "Låst"; sub "Lås opp for å fortsette."; **Lås opp** (filled, wide); after a failed or cancelled try the red line
"Fikk ikke låst opp. Prøv igjen, eller logg ut og logg inn på nytt."; at the bottom **Logg ut** (a plain text button,
a POST). No name, no phone, no count, nothing from the page behind.

## 7. Tests and steps

**Tests.** `webauthn::tests`: the CBOR reader's bounds (depth, indefinite, trailing bytes, huge lengths), each alg
against vectors in `server/testdata/webauthn/` (recorded once from an iPhone, an Android phone and a security key, plus
WebAuthn Level 3's test-vector appendix), every refusal (origin, `crossOrigin`, rpIdHash, a used challenge, UV unset,
BS without BE, counter regression, a credential id too long). `src/tests/passkeys.rs` with a `FakeAuthenticator`
(ring-generated ES256 and Ed25519 keys signing real `authenticatorData`): add needs the re-auth; sign-in skips 2FA and
logs; a removed or expired-invite account refused; another parent's passkey can't sign in as you; removal ends the
other sessions; bad assertions count in the per-IP ban and `reauth_wrong`; a forged Host can't use a credential of
another RP ID. Step 2: the switch's guards, the generic password refusal, Reset and `reset-2fa` clearing passkeys and
the flag, the last passkey's removal refused, the switch-off page's single write, `ready()`. Step 3: past N a
navigation → `/lock?next=`, a fetch and `/api/session` → 423, a poll doesn't move `used_at`, the beacon does, the 1-min
slack, the cookie carried into a new sign-in, another parent's credential refused, a notification path kept in
`next`. JS: `jstest/passkey.test.js` (base64url, the JSON shapes, hidden without `PublicKeyCredential`),
`jstest/app-lock.test.js` (idle, hidden, back-forward cache, the beacon throttle, the veil); `reconnect.test.js` for
423. Templates: `no_template_has_inline_script`, `check_forms`' anchors. Manual QA: iPhone PWA, Android Chrome WebAPK,
Mac Safari, a security key, a second hostname, http on the LAN.

**Steps** (each its own branch, design QA before step 1, code QA after each):
1. **Passkeys + sign-in** (M): 0116, `src/webauthn.rs`, the routes, Account's Passkeys group (without the switch), the
   re-auth by passkey, the Parents count, the sign-in page, `static/passkey.js`, events, i18n, the restore line.
2. **Kun passkey + recovery** (S-M): its migration, the switch and its guards, `ready()`, Reset and `reset-2fa` /
   `reset-sign-in`, the switch-off page, DEPLOY.md's recovery line.
3. **The lock** (M): the session keys and the cookie, the check in `require_full_auth`, `/api/used`, `/lock`,
   `static/app-lock.js`, `reconnect.js`'s 423, the veil.

## 8. Open for the user

1. The lock's times: Av / 1 / 5 / 15 / 60 min - also "Med en gang" (every time the app has been away)?
2. The lock per device (this phone remembers it) - or follow the account to every device?
3. Kun passkey needs 2 passkeys, or 1 and another parent - or is 1 synced iCloud passkey enough?
4. The Norwegian word: "passkey" (yours), "passnøkkel" (Apple) or "tilgangsnøkkel" (Google)?
5. Which addresses do you open the PWA at - only the Caddy domain, or the tailnet name too? Passkeys work on one domain
   (or its subdomains with `PASSKEY_RP_ID`).
6. Should every unlock show in the Security Log (merged per hour), or only failures?
