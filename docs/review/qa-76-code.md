# Code QA: 76 - pin the server's certificate issuer (`server-issuer-pin-2`, ae3006fa)

Security review by Opus, 2026-11-04, of the one commit ae3006fa on main 6061715c (61 files, both directories), against
`docs/design/76-server-issuer-pin.md` and the design reviews `docs/review/qa-76-design.md` (C-1, H-1, H-2, R-1..R-5).

Read in full: `ServerPins.kt`, `ServerPinStore.kt`, `ServerTransport.kt`, the diffs of `MdmApi.kt`, `MdmSyncWorker.kt`,
`PowerEvents.kt`, `Provisioning.kt`, `SettingsFragmentLauncher.kt`, both network security configs, the strings, the
tests (`ServerPinsTest`, `ServerPinTransportTest`, `TestOnlyChain`, `ServerTransportTest`, `PolicyResponseCompatTest`);
`server_tls.rs`, migration 0129, `handlers::devices::server_tls`, the `device_api`/`device_pages`/`parent_events`
changes, the template, both i18n files, `tests::server_tls`; the CI path lists, `CLAUDE.md`, `DEPLOY.md`. Checked
around them: every OkHttp client in the launcher (`grep OkHttpClient.Builder`, `createMdmApi`, `createMusicServer`,
`toServer`), `HttpURLConnection`/WebView/`@Url` uses, the music app's server references (comments only), the server's
device-route redirects, `csrf_guard`, `settings_log::log_saves`, `foreign_keys(true)`, DEPLOY.md's listener and Caddy
sections.

**Verdict: the mechanism is right and honours every design finding; one deployment gap breaks the user's no-QR
recovery, and the device page misleads the parent in exactly the lockout case.** The pin, the flag's rule, the fence,
fail-closed, the ring's drop rule, the server's window and stickiness, auth and CSRF all hold. Fix H-1 and M-1 before
merge (both small); the Lows can go in the same round.

Counts: **0 Critical, 1 High, 1 Medium, 5 Low, 4 nits.**

## What holds (verified)

- **B is right.** Recomputed every pin with design D1's command from the ten PEMs in `src/test/resources/server_pins/`:
  all eight `BUILTIN_PINS` (`ServerPins.kt:32-41`) match. X1, X2, USERTrust RSA/ECC and Sectigo R46/E46 are
  byte-identical (DER) to Debian's `/etc/ssl/certs`; `root-yr.pem`, `root-ye.pem`, `root-yr-by-x1.pem` and
  `root-ye-by-x2.pem` fetched from `letsencrypt.org/certs/gen-y/` today are byte-identical to the test copies, and each
  cross-sign carries its root's key. YR and YE are in (C-1). The test resources hold certificates only.
- **The pinner's host** is `origin.host` from `path.baseUrl.toHttpUrlOrNull()` (`ServerTransport.kt:194,218`), the
  string OkHttp compares; `serverPinner` returns `null` unless `findMatchingPins(host)` is non-empty, and `null` gives
  an interceptor that throws `ServerPinRefused` before a socket (`:106-111,219`): it fails closed, never open (H-2,
  L-4). Tested for a name and an IP literal.
- **Every server client goes through `toServer`'s Direct branch**: `createMdmApi` (sync, enroll, PowerEvents' shutdown
  client, WeatherPull, AppDownloads/self-update via `downloadTrackedApp(@Url)`, AppInstallReceiver),
  `CommandListenerService.buildClient` (the SSE, per connect) and `createMusicServer` (the courier, the music files,
  Spotify/Storytel tokens). The only other OkHttp client is `buildDohClient` (third-party DoH). No
  `HttpURLConnection`, `DownloadManager` or WebView reaches the server; the music app has no server client. The tsnet
  SOCKS path is Direct with a proxy and is pinned end to end (tested through a SOCKS5 relay).
- **The origin fence** is the first interceptor (`:200-204`) and `followRedirects(false)`/`followSslRedirects(false)`
  are set (`:197-198`); the server's device routes never redirect (all `Redirect::` uses are PWA pages); download URLs
  are relative (`/api/devices/apps/{id}/download`, `device_api.rs:2179`), so the fence changes nothing real.
- **The refusal catch** matches only the `Certificate pinning failure!` prefix (`ServerPins.kt:129`); OkHttp's
  `RetryAndFollowUpInterceptor` rethrows the original `SSLPeerUnverifiedException` (not recoverable: no second route),
  so it is seen. `ServerPinRefused`/`ServerOriginRefused` are `IOException`s: every caller already treats them as an
  unreachable server (`fetchPolicy` catches `Exception`, the report is best-effort, the SSE retries).
- **The flag** (`nextFlag`, `ServerPins.kt:96-100`): on only from `enforce: true` over `PASSED`; off only from
  `enforce: false` over `PASSED` or `TAILNET`; everything else keeps it. `PASSED` is judged on the policy response's
  own cleaned chain (`response.raw().handshake`, `MdmSyncWorker.kt:118`), `TAILNET` from `apiPath is
  ServerPath.Tailnet` (R-5), `NONE` (no handshake, not tailnet) keeps it. One caller, inside `if (freshPolicy != null)`
  after `storeAcceptedPolicy` - a rejected-suspect or undecodable policy never reaches it, the cache paths never call
  it (source scan). A MITM with a store-valid certificate outside B gets `FAILED`: it can neither switch the flag on
  nor off, and stripping `server_tls` keeps it. `enforce` must be a JSON Boolean (`"true"`, `null`, arrays: keep).
  Only a setup QR clears it (`Provisioning.kt:37`, before the enroll); `applyProvisioningExtras` is reached only from
  Settings' scanner (parent code) and `onProfileProvisioningComplete` (a system broadcast). The Server URL edit and
  the lock's "Ny oppsettkode" don't touch it.
- **`debug_http` can't occur in a release build**: a Direct http path exists only with `BuildConfig.DEBUG`
  (`serverPath`, `:139-140`), and even an https answer without a handshake would only keep the flag.
- **Network security config**: the release file has no `debug-overrides` and no `src="user"` (asserted by
  `ServerTransportTest`); `debug-overrides` lives in `src/debug/res/xml/` and Android honours it only for a
  debuggable app; release has no `isDebuggable`, and there is no third build type.
- **The ring** drops sent rows only after a 2xx over `PASSED` or `TAILNET` (`dropOutside`, judged on the report's own
  handshake, `MdmSyncWorker.kt:370`): a MITM answering 2xx can't erase them (R-2a).
- **The server**: `next_state` uses `Utc::now()` (receive time), 72 h from the first in-set sighting, 3 distinct UTC
  days, Wi-Fi and mobile once mobile was reported (R-3); an outside row or `seen.in_set: false` sets `stopped_at`
  without restarting the window (R-2b); `on` and `off` never move on a report; `enforcing: true` adopts `on` only from
  `watching`; `watching` sends `{}` (R-1). `sanitize` keeps 8 rows, names 64, hosts 253 (chars), pins 1..44 of the
  base64 alphabet, networks `wifi`/`mobile`/`other`; junk drops a row, never the report. The push is one a day per
  phone (UTC date), never while `off`. The policy carries `server_tls` only for a `server_pin_v1` phone (last
  report's capabilities). Concurrent intake and a parent action are each one transaction; SQLite refuses the stale
  writer (BUSY / BUSY_SNAPSHOT), so no lost update.
- **The parent's actions**: in the signed-in admin router, behind `csrf_guard` (every non-GET outside `/api/devices/`);
  `off` needs the ticked box; each writes its own security event under `admin.username` (so `log_saves` skips the
  generic `settings_saved`), `off` raises `server_tls_off` to every parent (no switch), every action sends the SSE
  nudge, and each lands on `#server-tls` (form action and redirect), never the top of the page.
- **Migration 0129**: one row per phone, `ON DELETE CASCADE` with `foreign_keys(true)` (`main.rs:1784`) and
  `AUTOINCREMENT` ids, so a deleted phone's state can't pass to a new one; `CHECK` on `state`.
- **i18n**: the server's strings are in `en.json` and `nb.json` (the only two), the launcher's in `values/` and
  `values-nb/`; the nb labels match the app's ("Serveradresse", "Skann oppsettskode", "adminsiden").
- **Contract**: `server/testdata/server_tls_report.json` and `launcher/app/src/test/resources/server_tls_report.json`
  are byte-identical (`cmp`), compared by `tests::server_tls::the_contract_matches_the_launchers_copy` and
  `ServerPinsTest`; both copies are in `launcher.yml`'s and `server-ci.yml`'s path lists and in the root `CLAUDE.md`.
- **Test material**: no `PRIVATE KEY` anywhere in the tree; `TestOnlyChain` makes its keys in memory per run. The
  okhttp-tls 4.12.0 entries in `verification-metadata.xml` (jar `969896cc…`, module `1b2cd709…`) equal Maven
  Central's `.sha256` files, and the downloaded files' SHA-1 equal Central's `.sha1`.
- **Offline**: a refusal is an unreachable server - the cached policy, time rules, the PIN lock, calls and 112
  (Telecom, read from `CallPolicyStore`'s memory) are untouched; no code path treats a TLS failure differently from a
  dead server.

## High

**H-1 - The no-QR recovery over the tailnet doesn't exist in the documented deployment.**
- The user's requirement: recovery without the QR on the home network, by `http://<the server's Tailscale IP>`
  (`ServerPath.Tailnet`). The code, `server_cert_explain`, `server_tls.footer` (`en.json:2239`) and the new DEPLOY.md
  paragraph (`DEPLOY.md:222-237`) all say it "works at once".
- But nothing answers plain http on the tailnet IP in any setup DEPLOY.md describes:
  - the server "never listens publicly by itself: it binds loopback" (`BIND_ADDR` `127.0.0.1:3100`,
    `DEVICE_BIND_ADDR` `127.0.0.1:3101`, DEPLOY.md's table);
  - the Caddy example (the user's setup, "alt går via caddy") has `https` sites only: `pi.tail1234.ts.net` and
    `:8444` bound to the tailnet IP, and the public `phones.example.com`. Caddy's port 80 only redirects its named
    hosts to https, and a request to `http://100.x.y.z/` carries the IP as its Host, which matches no site. The
    launcher doesn't follow redirects, so either way no policy arrives;
  - `tailscale serve` gives https only, unless configured with `--http`;
  - DEPLOY.md recommends `ADMIN_DEVICE_API=off` and a tailnet ACL that lets phones reach the phone port only.
- Scenario: the check is on; one day the chain Android validates leaves B (an LE root change before a launcher update
  ships, an Android store change, Caddy pointed at another CA). The parent follows the phone's dialog and sets Server
  URL to `http://100.101.102.103`. Nothing comes back, and the dialog promised it would. What remains is the QR re-scan
  with the parent code; a phone whose parent code isn't set needs a factory reset.
- Step 0 of the design ("that the server answers plain http on its Tailscale IP from a phone") isn't done.
  `security.md` says so, but it is the precondition of the user's decision, not a QA nicety.
- Fix (docs and config, no new mechanism):
  - DEPLOY.md, "The phones' certificate check": a Caddy site that serves the phone listener over plain http on the
    tailnet IP only, for example
    ```
    http://100.101.102.103:3102 {
        bind 100.101.102.103
        reverse_proxy 127.0.0.1:3101 { header_up -Tailscale-User-Login }
    }
    ```
    (or the `tailscale serve` equivalent), the ACL line that lets phones reach that port, and that the recovery
    address is then `http://<IP>:3102`.
  - The same address in the device page's footer and the phone's explanation (the IP and the port, not "the
    server's Tailscale IP" alone).
  - Do step 0 on the user's server before release: from a phone with the tailnet joined, `http://<IP>:<port>` set in
    Settings gives "Sync now" success.
  - Name the partial alternative in DEPLOY.md: a phone on a public name can switch to `https://<host>.ts.net:8444`
    (`tailscale cert` is Let's Encrypt, in B). That only helps when the public certificate left B, not when B itself
    is the problem.

## Medium

**M-1 - The device page says the phone took the change when it can't have; the parent is misled in the lockout
case.**
- The card is drawn from the server's `state` alone (`server_tls.rs:564-630`). The phone's own `phone_enforcing` is
  stored but never shown (R-1 asked for "on (the phone's)").
- Lockout scenario: the phone enforces and refuses the server's new chain. The parent presses "Turn off the
  certificate check". The flash says "The certificate check is off. The phone hears it now." (`en.json:2240`), the
  line says "Off since …, by …", and the refused alarm is hidden while `off` (`.filter(|_| row.state != State::Off)`,
  `server_tls.rs:611`). But the phone can't hear it: its only way in is the tailnet (H-1), and the SSE nudge goes to
  a stream it can't open. The parent waits for a phone that will never sync.
- The other way round: "Turn the check on now" for a phone whose chain is outside B (it reports `seen` rows) shows "on
  - only Let's Encrypt or ZeroSSL" for good, while the phone never takes `true` (`FAILED`) and stays unpinned.
- Fix:
  - While `phone_enforcing` differs from what the state sends (`off` with `Some(true)`, `on` with `Some(false)`), the
    line adds "the phone hasn't taken it yet (last report …)". For `off`, add "if the phone refuses this server, set
    its Server URL to <recovery address> (H-1)".
  - Keep the refused alarm visible while `off` until a report with `enforcing: false` arrives.
  - Word the flash "… The phone takes it at its next sync" rather than "hears it now".

## Low

**L-1 - The refused anchor is parsed from text the attacker's certificate partly writes.**
- `pinFailureAnchor` (`ServerPins.kt:127-134`) cuts the chain block at the **first** "Pinned certificates for"
  (`:130`). OkHttp writes each certificate's `subjectDN.name` into that block, and the leaf's subject belongs to whoever
  got it issued.
- Scenario: the attacker the design exists for is one with a compromised store CA key (the eMudhra case), who can mint
  any subject. A leaf CN `ISRG Root X1 Pinned certificates for x` cuts the block inside the leaf's own line. The "last
  line" is then the leaf's, and the ring, the report, the device page's alarm and the push all say "Refused a
  certificate … from ISRG Root X1". The parent reads it as a store change, not an attack. The parsed pin isn't
  checked either. A malformed pin makes the server's `sanitize` drop the row, while the phone drops it from its ring
  after the next passing 2xx: the alarm is silently gone.
- Fix: `substringBeforeLast("\n  Pinned certificates for ")`. Take the last line matching
  `^    sha256/[A-Za-z0-9+/]{43}=: `, and return `null` (an "unknown issuer" refusal, still recorded) when it doesn't
  match. Add a test row with such a subject.

**L-2 - A phone of an install outside B pushes "saw a certificate…" every day, for good.**
- R-2(c) says "the first outside sighting **of a phone that had in-set ones**". `next_state` pushes any new outside
  row, once a UTC day (`server_tls.rs:287-296`).
- On an install behind Cloudflare/GTS (never switched on, "works as today"), the phone's ring never drops (no
  connection passes). Each Wi-Fi/mobile switch adds a row, so a kid who goes to school and comes home makes a new row
  every day, and every day brings one push to every parent, forever.
- The parents learn to swipe it away, which costs the alarm its value on the installs that do switch on. The only
  way to silence it is to turn the check off.
- Fix: push only when the phone had in-set sightings (`row.window_from.is_some()` or `state == On`), as R-2(c)
  says. Otherwise the device page shows it and no push is sent.

**L-3 - A stolen device token can switch a phone on early, silently.**
- `enforcing: true` in any report adopts `on` from `watching`, even when `stopped_at` is set (`server_tls.rs:258-262`).
  No event, no push.
- Whoever held a MITM before `on` holds the bearer (as today). On a split-horizon install (the case R-3 guards
  against), a forged `enforcing: true` turns the server `on`. The phone then takes `true` at its next passing sync,
  and later refuses on the other network, which is the brick R-3 exists to prevent.
- Not an escalation of trust, a lockout lever. Fix: adopt only from a report that also has a `seen.in_set: true`, and
  not while `stopped_at` is set (there, show "on (the phone's)" and offer the buttons). Or write it as a security
  event `server_tls_on` with "the phone's report" as the actor, so it is at least seen.

**L-4 - Phone-supplied text reaches pushes unfiltered.**
- `sanitize` clips `name` and `host` but keeps control and bidi characters (`server_tls.rs:131-133`).
- Askama escapes the page, but the push title and the inbox read "{phone} saw a certificate for your server from
  {issuer}". With the bearer (or the L-1 subject) the issuer can be a 64-character phrase with U+202E or newlines,
  in a notification every parent trusts.
- Fix: in `text()`, drop `char::is_control` and the bidi controls (U+200E/F, U+202A-E, U+2066-9).

**L-5 - The alarm's time is when the server heard it, not when it happened.**
- `received()` (`server_tls.rs:559-561`) shows the receive time. A refusal on hostile Wi-Fi at 14:02, reported over
  mobile data at 18:30, reads "Refused … on Wi-Fi at 18:30".
- Receive time is right for the window (R-3), not for the diagnostic. Fix: show the phone's `at_ms` when it is
  within a day of `received_at` and not in the future, otherwise the receive time.

## Nits

- `PowerEvents.prepare` runs at the sync's start (`MdmSyncWorker.kt:99`), before `nextFlag`. After the flag turns on,
  the shutdown's prepared client (the location fix and the bearer) stays unpinned until the next sync's `prepare`.
  Call `PowerEvents.prepare(context)` again after `setEnforce` when the flag changed.
- `ServerPinStore.enforce(null)` reads as off (`ServerPinStore.kt:30`), which is fail-open in form. It is unreachable
  today: `Application.instance` is set before any client, and the boot-cover process has no server client. Say so,
  or read the flag from the passed context in `toServer`.
- `POST /devices/{id}/server-tls/watch` is accepted in any state (the page offers it only while `off`). From `on` it
  moves the server to `watching` without telling anyone. It is harmless, since `{}` keeps the phones' flags, but
  refuse it outside `off`, as the page does.
- A phone stopped only by `seen.in_set: false` with no outside row shows "watching - on from …" (`card`'s `(Some(_),
  None)` arm), although it won't switch on. It is unlikely in practice, because the observer records the same chain
  as a row.

## Lockout review (the brief's item 4)

- **Caddy's fallback to ZeroSSL**: ZeroSSL's RSA/ECC intermediates are under USERTrust RSA/ECC (in Android's store,
  in B). The AAA Certificate Services cross-sign carries USERTrust's key, so it passes whichever anchor Android
  picks. R46/E46 cover Sectigo's dedicated TLS roots. **In the set.**
- **Let's Encrypt's Generation Y**: YR/YE and their cross-signs share keys, so a Conscrypt update adding YR doesn't
  matter (C-1 resolved).
- **A CDN, or split horizon**: never switched on by itself while mobile shows a CDN chain (R-3). A forged
  `enforcing: true` can force it (L-3). DEPLOY.md's CDN paragraph is right.
- **A school or captive-portal proxy**: its private CA fails Android's validation before the pin (release trusts no
  user CA), so nothing changes and no row is written.
- **Lock, calls, 112**: unaffected (see "What holds").
- **Recovery without adb**: the QR re-scan (parent code) works. **Without the QR**: only once H-1 is fixed.

## Tests run

- Server (`CARGO_TARGET_DIR=~/cargo-targets/qa-pin`, `line-tables-only`): `cargo test` 1743 passed, 0 failed;
  `cargo fmt --check` clean; `cargo clippy --all-targets` no warnings.
- Launcher (`--no-daemon --max-workers=2`, debug with the tsnet stub): `ServerPinsTest`, `ServerPinTransportTest`,
  `ServerTransportTest`, `PolicyResponseCompatTest`, `PowerEventsTest`, `SecurityLowsBTest` - BUILD SUCCESSFUL.
- No emulator was started. Design step 0 and section 6's emulator rows are still open (H-1 depends on step 0).
