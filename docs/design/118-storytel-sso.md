# 118 - Storytel with Google or Apple sign-in

An options study and a recommended design, 2026-11-18. The user asked for it on the same day: "helst ville jeg gjort det
via server men er ikke krise om man må unlocke telefonen fra pwa/pin og gjøre det manuelt i music app". It builds on
design 26b (the server's Storytel client) and design 58 (the phone gets only a short-lived token from
`POST /api/devices/music/storytel/token`). `S/` = `server/src/`, `L/` = `launcher/app/src/main/java/com/kidslauncher/mdm/`,
`M/` = `music/app/src/main/java/me/vibb/music/`. Prior art: debba/storytel-player (MIT), branch `develop`:
`src/modules/sso.ts` and `server/storytelApi.ts`, read 2026-11-18.

## 0. In short

- **Recommended: no SSO code at all.** The parent makes a Storytel password for the Google or Apple account through
  Storytel's own reset. Storytel's login page links to it as "Glemt innloggingsmetode eller passord? Tilbakestill
  passordet ditt", and its help tells Apple users to do exactly that. The parent then saves the e-mail and that
  password on Vibb Phone the same way as today. This is server-side, and design 58 stays as it is. The cost is one
  (i) line in the PWA (XS). The user's one try decides it (ASK 1).
- **Fallback, if Storytel won't take that password: "Google or Apple" on Vibb Phone through a bookmark (option B).**
  - The parent signs in on storytel.com in their own browser.
  - A one-time bookmark from Vibb Phone posts Storytel's Firebase refresh token straight to the server.
  - The server keeps the token sealed. Each hour it turns it into a Firebase ID token, which it uses for the shelf
    and hands to the phones through design 58's route. The phones' code and the shared contract don't change.
  - Size: about 1.5 rounds (§4.8).
- **Not recommended: the sign-in window on the phone (option C).** Google has blocked its sign-in inside an Android
  WebView since 2021 (`disallowed_useragent`). On the phone, option C therefore works only for Apple accounts, unless
  we fake a browser, which Google's policy forbids and which can break at any time. It would also cost more than B
  (about 2 rounds) and would reopen design 58's rule.

## 1. Facts (checked 2026-11-18 unless marked)

**Storytel's web sign-in** (www.storytel.com/no/login and its JS, fetched without an account):
- **It is Firebase, Google Identity Platform.**
  - The project is `storytel-accounts-prod`.
  - The API key is public in the page (`firebaseApiKey`, `AIza…`).
  - `authDomain` is the page's own host (`authDomain: window.location.host`), so the OAuth handler is
    `https://www.storytel.com/__/auth/handler`.
- **Password, Google and Apple all sign in inside the page** through the Firebase SDK. The page then sends the Firebase
  ID token to Storytel's backend (`POST /api/graphql/login`, header `x-gip-token`), which sets the `storytel_session`
  cookie (debba: "a Firebase session cookie").
- **The login page offers** "Fortsett med Google", "Fortsett med Apple", e-mail with a password, and "Glemt
  innloggingsmetode eller passord? Tilbakestill passordet ditt" (`/no/reset-password`). It has strings for "Angi
  passord" and "Nytt passord er angitt! Du kan nå bruke det nye passordet ditt til å logge inn". It has none for a
  sign-in link by e-mail, and none for a TV or device code.
- **Storytel's help** (article 360014466119, "Sign in with Apple"): Apple sign-in works only for accounts created with
  it, and someone moving to Android must "reset the password … on our website". With Hide My Email the account's
  e-mail is the `privaterelay.appleid.com` address (unverified for Storytel).
- **Headers:** `content-security-policy: frame-ancestors 'none'`, `x-frame-options: DENY`,
  `cross-origin-opener-policy: same-origin`. There is no `connect-src`.

**Which token works where** (debba's `storytelApi.ts`, `getApiBearer` / `getLegacyActionToken`):

| Endpoint | Password account | SSO account | Ours |
|---|---|---|---|
| `api.storytel.net/*` Bearer: `libraries/bookshelf`, `assets/v2/consumables/{id}/abook` (the 302 mint), bookmarks | `login.action`'s `accountInfo.jwt` | the **Firebase ID token** | the server's shelf and the phone's mint (`M/storytel/StorytelApi.kt`, `ASSET_PATH`) |
| `www.storytel.com/api/*.action?token=` | `accountInfo.singleSignToken` | the `storytel_session` cookie | none |

- **So for an SSO account, the phone's mint takes the Firebase ID token** as the same opaque `Bearer`.
- **Nothing else along the way cares what the token is:**
  - The launcher's courier takes any 1-8192 printable characters (`StorytelTokenCourier.kt` `JWT`).
  - The music app never decodes the token, and it uses `expires_in_s`.
  - The server's `jwt_life` reads `exp`, which a Firebase ID token has (`iat` + 3600).
- **We never need `storytel_session`.**

**Firebase's tokens:**
- **The ID token lives 1 h.** It comes from `POST https://securetoken.googleapis.com/v1/token?key=<apiKey>` with
  `grant_type=refresh_token`.
- **Its claims carry `email`** (and `name`/`picture` when the provider gave them), `user_id` and
  `firebase.sign_in_provider`. That puts the family's e-mail inside the token, which is design 58's Q-A again (ASK 3).
- **debba sends `Referer`/`Origin: https://www.storytel.com`** on that call. The key is most likely restricted to
  Storytel's referrer (not verified).
- **The refresh token has no expiry.**
  - Google documents it as revoked by a Firebase password or e-mail change, a disabled or deleted user, or an
    explicit revoke.
  - Every answer carries a `refresh_token`. In Firebase it comes back unchanged, but the SDK stores whatever comes
    back (`updateFromServerResponse`), and so does this design.
  - A Google or Apple password change does not revoke it (Firebase never learns of it).
- **Google blocks its OAuth endpoint in embedded WebViews** ("Upcoming security changes to Google's OAuth 2.0
  authorization endpoint in embedded webviews", Google Developers blog, 2021-06-29). An Android WebView is
  recognised by its `X-Requested-With` header and its user agent's `wv`. debba avoids this only because Electron
  passes as desktop Chrome.

**What is impossible, precisely:**
- **Google never redirects to us.** Storytel's Google and Apple OAuth clients redirect only to Storytel's handler.
- **Our domain can't be used with Firebase.** Firebase takes a `continueUri`/`requestUri` only on the project's
  authorized domains, which only Storytel sets.
- **Our PWA can't read storytel.com's cookies or IndexedDB** (the same-origin policy).
- **It can't frame storytel.com** (`frame-ancestors 'none'`, DENY).
- **It can't talk to a window it opened** (COOP same-origin cuts `window.opener`).
- **Apple's web flow posts its code** (`response_mode=form_post`), so it is never in a URL a person could copy.

## 2. The options

### A. Pure server

**A1 - A password on the SSO account (Storytel's reset), then today's path.**
- **How:** the parent opens storytel.com › Logg inn › "Tilbakestill passordet ditt", gets the e-mail, sets a password,
  and saves the e-mail and that password on Vibb Phone › Music › Storytel. Storytel's web checks passwords in
  Firebase, and `login.action` takes them as today. Whether `login.action` takes a password added this way is the one
  unknown: ASK 1's try.
- **What we store:** today's sealed `{email, password}`. Nothing changes.
- **What a leak gives:** the password, a login anywhere until it is changed. Changing it revokes every Firebase refresh
  token too, so it is the cleanest remedy of all the options.
- **Google and Apple keep working** in Storytel's own apps, because the password is added beside them (unverified;
  ASK 1 asks for a check in Storytel's app).
- **Robustness:** the same as today (`login.action`).
- **Size:** XS. Fits design 58 exactly.

**A2 - The server runs the Google flow, and the parent pastes the URL back.**
- **How:**
  1. The server calls Identity Toolkit `accounts:createAuthUri` (Storytel's key, `providerId: google.com`,
     `continueUri` = Storytel's handler) and gets `authUri` and `sessionId`.
  2. The parent opens `authUri` and signs in with Google, which redirects to
     `www.storytel.com/__/auth/handler?state=…&code=…`.
  3. The parent copies that address bar and pastes it into the PWA.
  4. The server calls `accounts:signInWithIdp` (`requestUri` = the pasted URL, `sessionId`) and gets the ID token and
     the refresh token.
- **What is wrong with it:**
  - It is Google only, since Apple form-posts its code.
  - It needs Storytel's referrer faked on every call.
  - It depends on Storytel's handler page leaving the URL alone (unverified: it may redirect or rewrite at once).
  - Copying a long URL on an iPhone is the worst part of the whole flow.
  - The server does Storytel's web's own steps on Storytel's behalf.
- **Security:** as in B, and the code lives only in the parent's clipboard and the POST.
- **Size:** M plus a spike. Not recommended.

**A3 - Email-link sign-in, a TV code, or Storytel's partner OAuth.** None is available.
- Storytel's web has no e-mail-link or device-code sign-in (§1). Testing whether Firebase's email link is enabled
  would send the parent a real e-mail, and its `continueUrl` must be Storytel's anyway.
- Storytel's partner OAuth (Sonos and the like) is bound to the partners' client ids.

### B. The parent's own browser hands the server the token (bookmarklet)

- **How:** a one-time bookmark (`javascript:…`) made for this parent by Vibb Phone.
  - On a storytel.com page, after the parent has signed in there with Google or Apple, it reads IndexedDB
    `firebaseLocalStorageDb` › `firebaseLocalStorage` › the entry `firebase:authUser:<apiKey>:[DEFAULT]` ›
    `value.stsTokenManager.refreshToken` (debba's read).
  - It then submits a hidden form, `POST https://<the PWA>/music/storytel/sso/catch`, with `pair` (a one-time code
    baked into the bookmark), `k` (the apiKey) and `rt`.
  - A form navigation needs no CORS, there is no `connect-src` to stop it, and the body never reaches the browser's
    history.
- **What the parent does:** signs in on storytel.com, adds the bookmark, and taps it there.
  - On a computer that means dragging a button to the bookmarks bar.
  - On an iPhone it means copying the code, bookmarking any page, then editing that bookmark's address and pasting
    (about 8 taps, behind the (i)).
- **What we store:** the refresh token and the apiKey, sealed.
- **What a leak gives:** the whole account, with no end date. The family can end it only with a Firebase password
  change (which means doing A1's reset anyway) or a "sign out everywhere", if Storytel offers one (not verified).
  That is weaker than A1's remedy.
- **The browser keeps its own session** under the same refresh token. That is harmless while Firebase doesn't rotate
  it. If it ever did, one side would be signed out. The server then says "sign in again".
- **Running our own code on a site where the parent is signed in** is a pattern we shouldn't teach widely. Ours is
  open source, short and shown in full.
- **Robustness:** it breaks if Storytel moves Firebase's persistence (IndexedDB to memory or cookies), turns on App
  Check, or tightens the key.
- **Size:** M (server S+, PWA S, the fake S).
- **Fits design 58:** the phone never logs in. The phones get exactly what they get today: a short-lived bearer
  through the token route.

Other ways to carry it are worse:
- Copying it out of DevTools works on a computer only and is too technical.
- A QR adds a second device for nothing.
- An address with the token in its `#` lands in the browser's synced history.

### C. On the phone, once, by hand

- **How:**
  1. The parent unlocks the phone (the PWA's pause or the Parent PIN).
  2. A "Storytel" row in the parent's area opens a WebView on `www.storytel.com/<cc>/login`.
  3. After the sign-in the app reads the refresh token as in B (`evaluateJavascript`, only on `www.storytel.com`) and
     posts it straight to a new device route, `POST /api/devices/music/storytel/sso`.
  4. The route is accepted only while the parent has armed it in the PWA: 15 minutes, one use.
  5. The phone then wipes the WebView and goes back to design 58's token route.
- **Host: the launcher, not the music app.**
  - The launcher holds the device token and the parent gate.
  - The music app's rule is "the kid's screens open … no web views" (`SourceScanTest`). Its one WebView is
    Spotify's headless one.
- **The WebView's isolation:**
  - its own process (`android:process=":storytel_sso"`) with `WebView.setDataDirectorySuffix` (API 28+, so
    Android 14-17 alike);
  - no `addJavascriptInterface`;
  - `shouldOverrideUrlLoading` allows only `www.storytel.com`, `accounts.google.com` and `appleid.apple.com`;
  - after a capture or a cancel: `CookieManager.removeAllCookies`, `WebStorage.deleteAllData`, the data directory
    deleted and the process ended;
  - never Storytel's own "log out", which could revoke the token.
  The phone's DNS filter must let those hosts through for the window (design 102's login windows are the precedent).
- **Design 58's rule changes:** from "the phone never logs in" to "the phone never logs in unattended and never keeps
  a Storytel credential: one parent-gated capture, handed to the server and wiped". The route and its answers need a
  new contract file, `server/testdata/storytel_sso_capture.json`, shared with the launcher.
- **What breaks it:**
  - Google, at once: the sign-in gets `403 disallowed_useragent` (§1). Getting past it means changing the user agent
    and dropping `X-Requested-With` (androidx.webkit's `setRequestedWithHeaderOriginAllowList`). That is against
    Google's policy, and Google may close it again at any time. Apple's web flow is not blocked this way.
  - Everything that breaks B breaks C too.
- **Size:** server S, launcher M, a contract and an emulator gate on 14 and 17. That is about 2 rounds, for Apple
  accounts only. Not recommended.

### D. Anything better

- **A headless browser on the server, streamed to the parent:** huge, and Google blocks automated browsers.
- **A native Google or Apple sign-in on a phone:** the token it gives is for our client id. Storytel's Firebase takes
  only tokens for its own clients.
- **Storytel's own app on the phone:** its tokens can't be read.
- So nothing beats A1.

### Comparison

| | A1 password via reset | A2 paste URL | B bookmark | C phone WebView |
|---|---|---|---|---|
| Google / Apple | both | Google | both | Apple (Google only with a faked browser) |
| Parent's work | one reset e-mail, once | a long paste | sign in + a bookmark | unlock + sign in on the kid's phone |
| Stored | e-mail + password | refresh token | refresh token | refresh token |
| Leak remedy | change the password | a reset (A1) | a reset (A1) | a reset (A1) |
| Breaks when Storytel… | drops `login.action` | changes its handler, App Check | moves its persistence, App Check | the same as B, and Google |
| Size | XS | M + a spike | M | ~2 rounds |
| Design 58 | unchanged | unchanged | unchanged | its rule changes |

## 3. Recommendation

1. **Now: A1** (ASK 1). Add one line behind the Storytel login's (i) (§4.5), and nothing else.
2. **Only if A1 fails for the family's account: B**, as designed in §4. The server side in §4.1-§4.4 also serves C or
   A2 should either ever be wanted.
3. **C stays written here** in case Apple-only is ever enough. It is not built.

## 4. The fallback (B)

### 4.1 Storage

- **`music_storytel` keeps one login at a time** (one family account), now of one of two kinds. Migration (the next
  free number at build time; map links holds 0159): `login_kind TEXT NOT NULL DEFAULT 'password'`
  (`password`|`sso`), and `sso_provider TEXT` (`google`|`apple`|`other`, from the first ID token's
  `firebase.sign_in_provider`).
- **These are plain columns**, so the card and `Plain::read` never open the seal (design 58 QA N-8).
- **The sealed plaintext by kind:**
  - `password`: `{email, password}`, as today. The associated data stays `storytel-v1:<fp>`.
  - `sso`: `{api_key, refresh_token}`, under a new associated data `storytel-sso-v1:<fp>`, so one kind can never be
    opened as the other. A mismatch is `KeyUnusable`.
- **`StoredLogin` becomes an enum** (`Password { email, password }` | `Sso { api_key, refresh_token }`), still with no
  `Debug` or `Display` (`secret_types_have_no_debug` covers the new types).
- **The `storytel_session` cookie is never captured or stored.**

### 4.2 The "login" of an SSO account

- **`login_with` gains a branch.** For `Sso` it calls `securetoken.googleapis.com/v1/token?key=<api_key>`:
  - the body: form `grant_type=refresh_token&refresh_token=…`;
  - `Referer: https://www.storytel.com/` and `Origin: https://www.storytel.com` (ASK 4);
  - through `music_fetch`: `Kind::Login`, `Reach::Public`, the same budgets.
- **Its answer:**
  - `id_token` is the `Jwt`.
  - Its life is `min(expires_in, the claims' exp)`, through `jwt_life`'s floors and caps.
  - When the answer's `refresh_token` differs from the stored one, it is sealed and written in the same step:
    `UPDATE … WHERE generation = ?`, and the generation does **not** move, so the phones' sessions stay.
- **Only the server ever uses the refresh token.**
- **What an outcome does, mapped onto today's machinery:**
  - A 400 with `TOKEN_EXPIRED`, `INVALID_REFRESH_TOKEN`, `USER_DISABLED` or `USER_NOT_FOUND` is a **refusal**:
    `login_refused` with its hour's cooldown. The card says sign in again.
  - A 400 or 403 naming the key or the referrer (`API_KEY_*`, `PERMISSION_DENIED`) is a refusal with its own code,
    `sso_key`: "Storytel's sign-in stopped working for this server".
  - A 5xx, 408, 429 or a transport error is an **outage**, with today's backoff.
  - A 200 without an `id_token` is `no_jwt`.
- **The bookshelf with the ID token works as today.** A 401 there forces one refresh, as a 401 forces one re-login
  today.
- **The probe at save is today's:** one refresh and one shelf read, within `PROBE_BUDGET`. A refusal stores nothing.
- **`payload_has_email`'s log line now says `yes`** for SSO: that is ASK 3.

### 4.3 What the phone gets (unchanged code)

| Kind | `jwt` in the token route's `ok` | Its life | Holds the e-mail |
|---|---|---|---|
| password | `login.action`'s `accountInfo.jwt` | 24 h today (design 58 step 2's measurement) | no |
| sso | the Firebase ID token | 1 h | yes (ASK 3) |

- **The route, its answers and `storytel_token_answers.json` don't change.** The launcher and the music app don't
  change.
- **A phone that wants a fresh link days later** (to start a book on Sonos, say) asks the route as it does now. The
  server answers with its kept ID token, or refreshes first when less than the margin is left. One refresh an hour
  serves every phone and the sweep.
- **The prefetch fits a 1 h token as it is:** `min(10 min, life/3)`. The phone-caused logins are capped at
  `max(6, 2 × ceil(3600/3600))` = 6 an hour.
- **A stolen phone holds at most one hour of access** (24 h today). §7.2's remedy becomes: remove the phone, then
  reset the Storytel password (A1), which revokes the server's refresh token too, then sign in again here.

### 4.4 The two kinds side by side

- Saving either kind replaces the other. The generation moves (+1), the cooldown and backoff clear, and the phones
  drop their token at the new generation, as on a password save today.
- "Clear" removes either kind.
- Events: `storytel_login_saved` gains the kind (a word, never a value).
- The sweep, the gate (`storytel_gate!()`: "a login is stored" now means either kind) and the import are unchanged.

### 4.5 The PWA (Music › Settings › Storytel)

- **A1 (step 0):** one sentence added to the end of `music.settings.storytel_info`:
  - nb: "Bruker dere Google eller Apple hos Storytel? Lag et passord der med «Tilbakestill passordet ditt», og bruk
    det her."
  - en: "Use Google or Apple at Storytel? Make a password there with “Reset your password”, and use it here."
- **B (only if built):**
  - The login cell gets a choice above the form: "Passord" / "Google eller Apple" (en "Password" / "Google or
    Apple").
  - Choosing Google or Apple shows the new page `/music/storytel/sso` instead of the e-mail and password fields.

| Where | nb | en |
|---|---|---|
| Page title | Google eller Apple | Google or Apple |
| Step 1 | Logg inn på storytel.com | Sign in at storytel.com |
| Step 2 (button) | Bokmerke | Bookmark |
| Step 3 | Trykk bokmerket på storytel.com | Tap the bookmark on storytel.com |
| (i), the only explanation | Dra «Bokmerke» til bokmerkelinja. På iPhone: kopier, legg til et bokmerke, rediger adressen og lim inn. Gjelder i 15 minutter, én gang. | Drag “Bookmark” to the bookmarks bar. On iPhone: copy, add a bookmark, edit its address and paste. Works once, for 15 minutes. |
| State, saved | Google / Apple (a quiet value on the login row) | Google / Apple |
| Orange, refused | Storytel-innloggingen virker ikke - logg inn på nytt | The Storytel sign-in stopped working - sign in again |
| Catch page, ok | Lagret. Du kan lukke denne fanen. | Saved. You can close this tab. |
| Catch page, no token | Fant ingen Storytel-innlogging her | No Storytel sign-in found here |
| Catch page, used/expired | Bokmerket er brukt eller for gammelt | The bookmark is used or too old |

- Every word goes through `i18n` in both catalogs.
- A save never jumps to the top (memory "PWA save no jump").

### 4.6 The bookmark and the catch route

- **`GET /music/storytel/sso`** (full auth) makes a `pair` code: 32 random bytes, hex.
  - Its SHA-256 is kept in memory with the admin's id, for 15 minutes, one use, at most 3 open server-wide.
  - The bookmark's text is a constant with only `pair` and the PWA's own origin filled in.
  - The bookmark reads one IndexedDB entry. On no match it shows `alert` with the "no token" words, and it posts
    nothing.
- **`POST /music/storytel/sso/catch`** has no session: the admin cookie is SameSite=Strict, so a cross-site POST
  carries none. It follows the Spotify callback's precedent in `main.rs`.
  - The admin gate still applies.
  - It is exempt from the app lock and from the Origin check, since its Origin is storytel.com. The live `pair` is its
    only key: hashed, compared in constant time and consumed before anything else.
  - The body is at most 8 KiB.
  - `k` must match `AIza[0-9A-Za-z_-]{35}`, or a `FAKE-` value under `STORYTEL_BASE`. `rt` must be 1-4096 printable
    characters.
  - The probe follows (§4.2). Accepted, it is sealed with the security log's `storytel_login_saved` (kind `sso`,
    the admin of the `pair`).
  - It answers 303 to `/music/storytel/sso/done`, a same-site step whose page shows the result.
  - A refused probe stores nothing, and the `pair` stays used.

### 4.7 Tests and the fake

- **`scripts/fake-storytel.py`** gains:
  - `POST /v1/token`: Firebase's answer shape. `--sso-refuse` answers `TOKEN_EXPIRED`, `--sso-rotate` returns a new
    refresh token each time, `--sso-key` answers `API_KEY_HTTP_REFERRER_BLOCKED`, and `--sso-ttl S` sets the life.
  - `GET /no/login`: a page that writes one fake `firebaseLocalStorageDb` entry, so a desktop browser can run the real
    bookmark against the fake under `STORYTEL_BASE`.
  - `--selftest` covers both.
- **The bookshelf and the asset 302 take** `Bearer FAKE-IDTOKEN-<n>` like `FAKE-JWT-<n>`.
- **Every value is fake:** `FAKE-REFRESH-<n>`, `FAKE-IDTOKEN-<n>`, the apiKey `FAKE-API-KEY-0000`, never `AIza…` and
  never `eyJ…`.
- **`STORYTEL_BASE`** also redirects the securetoken host (loopback only, design 58 §2.6).
- **Server-only testdata:** `server/testdata/storytel/fake_securetoken_*.json`, its answers, also read by the fake.
- **Server tests:**
  - seal and open each kind, and a kind never opens as the other;
  - the refresh's outcomes each map to their code;
  - a rotated token is stored and the generation doesn't move;
  - the token route serves the ID token with its life;
  - catch: a missing, used or expired `pair`, a bad `k` or `rt`, no session needed, the Origin exemption scoped to
    this one route;
  - the probe refuses and stores nothing;
  - the i18n tests.
- **The shared contract files don't change**, so no launcher or music tests change.

### 4.8 Steps and sizes

| Step | What | Size |
|---|---|---|
| 0 | A1: the (i) line nb+en (and ASK 1's try) | XS |
| 1 | Server: the migration, the enum, the refresh, the outcomes, the probe, the card's words, the fake's `/v1/token` | M |
| 2 | PWA: the choice, `/music/storytel/sso`, the bookmark, catch/done; the fake's login page; a manual check in a desktop browser and iPhone Safari against the fake | S+ |
| 3 | Code QA and security QA, then the fix round | S |

Steps 1-3 total about 1.5 rounds, server only. The phones see no release.

## 5. ASKs

1. **Try A1 once, before anything is built?** Recommended: yes.
   - Do Storytel › Logg inn › "Tilbakestill passordet ditt" with the account's e-mail (with Apple's Hide My Email, the
     `privaterelay` address), then save that e-mail and password on Vibb Phone › Music.
   - Check that Google or Apple still opens Storytel's own app.
   - If it saves, design 118 ends at step 0.
2. **If A1 fails: build B (the bookmark in your own browser), not C (the phone)?** Recommended: B. Google refuses its
   sign-in inside an app's WebView, so C would serve Apple accounts only. B is also the server-side path you
   preferred.
3. **With SSO, the token the phone holds in memory carries the family's e-mail** (Firebase's claims). Design 58
   cleared that question with "no" for the password's token. Choose:
   - (a) accept it: the token lives an hour, in memory only, and is never logged. Recommended.
   - (b) the server mints each audio URL itself (design 58's branch (b)). That is bigger, and the phones change.
4. **Should the server send Storytel's web address as `Referer`/`Origin` to Google's token endpoint**, as
   storytel-player does? Storytel's key most likely needs it. Recommended: yes. It is Storytel's own key, used for
   the family's own account. If Storytel ever turns on App Check, SSO stops, and the card says so.
5. **One Storytel login at a time** (a new save of either kind replaces the old)? Recommended: yes.

## 6. Not verified here

- Whether `login.action` takes a password added through the reset to a Google- or Apple-created account (ASK 1).
- Whether Storytel's key is restricted to its referrer, and whether App Check is enforced. Both need a real refresh
  token.
- Firebase's handler page after a foreign `state` (A2's paste), and Storytel's "sign out everywhere".
- Whether Storytel's web keeps Firebase in IndexedDB on Safari, as debba saw on Chromium. B's manual check runs
  against the fake, and the user's first real run settles it.

Sources:
- debba/storytel-player `develop`: `src/modules/sso.ts`, `server/storytelApi.ts`.
- www.storytel.com/no/login and its scripts (2026-11-18).
- support.storytel.com article 360014466119.
- [Google Developers blog, 2021-06-29](https://developers.googleblog.com/2021/06/upcoming-security-changes-to-googles-oauth-2.0-authorization-endpoint.html).
