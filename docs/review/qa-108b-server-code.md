# Code QA: design 108b R23-S, the server part of app push R2 + R3 (with the security review of `vapid.rs`)

Reviewed on `up-r2r3-server` at 89f2401ba (two commits on the contract commit 290321c2b), 2026-11-17. Read: the diff
`290321c2b..89f2401ba` in full; design 108b revision 2 with "Decided 2026-11-17" (Q3 (c) + (d), the signature before
`exp`/`aud`); `docs/review/qa-108b-design.md`; `docs/notes/server/unified-push.md`; `server/CLAUDE.md`; the R1 code it
touches (`unified_push/{publish,device,store,matrix,edge,mod}.rs`, `webpush::valid_browser_key`); the vectors
`server/testdata/unified_push_vapid.json` (every row's name and outcome, the clock, the origins). Cargo wasn't run:
Server CI is green on run 44054960254.

**Verdict: no High, no Medium; 7 Low.** The VAPID check does what the design and the user's Q3 decision say, in the
decided order, and I found no way to get a push past it or into the phone's bucket without the registered key's
private half. The Lows are a fail-open corner and a per-request cost in the stored-key read, two PWA lines that can
claim more than holds, and test and doc gaps.

---

## Security review of `vapid.rs` and the hook

What holds (checked, no finding):

- **Where it sits.** `publish` runs the size checks, the secret's lookup (an unknown secret is 410 and counts toward
  the ban; a gone one is 410/404), then the check, and only then `accept` (the buckets, the queue, the nudge). A refused
  push never calls `accept` or `rates.never_seen`, so it charges no bucket, wakes nothing and doesn't count toward the
  ban (R1's M5). The edge (`unified_push::edge`) admits the client (4 in flight, its request bucket) and reads the body
  before the handler, so the crypto runs only for a client the edge admitted **and** a request that named a live
  secret. A party without an endpoint never gets to the crypto.
- **Header parsing.** `headers.get` takes the first `Authorization`/`Crypto-Key` (a duplicate can't help: whatever is
  read must still verify with the registered key). A value with bytes above 0x7f fails `to_str` and reads as missing
  (401). More than 1024 bytes in either header is `Bad` before any parsing. The scheme is split at the first ASCII
  whitespace and compared case-insensitively. Params are split at `,` (the RFC form) or `;`/`,` (`Crypto-Key`), then
  trimmed and unquoted, with names compared case-insensitively. A repeated `t`/`k` takes the last, which is harmless
  for the same reason as a duplicate header. A JWT or a key never holds `,`, `;` or `=` (the trailing `=` of a padded
  value stays in the value, and `b64` drops it).
- **Base64url.** `URL_SAFE_NO_PAD` (base64 0.22) refuses non-zero trailing bits, so every accepted encoding is
  canonical. The tolerance (the standard alphabet, padding) only maps to the same bytes. The signature is verified
  over the token's **raw text** (`token[..header.len() + 1 + claims.len()]`, always a char boundary because it ends
  at the second `.`), so the tolerance can't make two texts verify as one.
- **The JWT.** Exactly three parts (a 5-part JWE is `Bad`). The header must be a JSON object with `alg` exactly
  `"ES256"`, so `none`, `HS256` with the public key as an HMAC secret, and `ES256K` are refused. `crit` is refused
  whatever its value, and with it RFC 7797's `b64: false`. `kid`, `jku`, `x5u`, `jwk` and `typ` are never read. The
  claims must be an object. The signature must be 64 bytes, r‖s, so ASN.1 is refused.
- **The verify.** `ring`'s `ECDSA_P256_SHA256_FIXED` with **the registered key's bytes**. The request's `k` only has
  to equal those bytes (step 2); it is never used as the key. ring validates the point and refuses r or s out of
  range. Malleability (r, n−s) doesn't matter: nothing is keyed on the signature.
- **The order.** form → key → shape → signature → `exp` → `aud`, so `Time`/`Address` (503) are reachable only with
  a token signed by the registered key. A forger only ever sees 401 or 403. Vectors `expired_bad_signature` and
  `wrong_aud_bad_signature` pin this.
- **`exp`.** Any JSON number (`as_f64`, finite, floored, saturating to i64), in `[now − 5 min, now + 25 h]`. Missing
  or not a number is `Time`, reachable only by the key holder (see the note under L5).
- **`aud`.** A string, or an array holding one, normalised by `check_origin` (scheme and host lower-cased, port 443
  dropped, a lone `/` tolerated, no user, path, query or fragment). It is compared with `accepted_origins`: the
  current origin and every `up_previous_origins` row with `until_at > now`. Those are stored through the same
  `check_origin`, so the two forms agree.
- **Oracles and timing.** The registered key is a public key (Mastodon publishes it in `/api/v2/instance`), so
  comparing it in variable time and answering `WrongKey` (403) vs `Bad` (403) leaks nothing secret, and both are
  403 anyway. 401 vs 201 shows whether a registration is keyed, but only to someone who already holds its secret.
- **Logging.** No `Authorization`, `Crypto-Key`, path or body is logged: the phone listener has no `TraceLayer`, and
  the new `tracing` calls carry only a registration id or a database error.
- **The refusal stamp.** `lookup` returns `vapid_refused_at`. `note_refusal` writes only when it is null or a minute
  old, so a refused flood takes SQLite's write lock about once a minute per registration (the test checks the second
  refusal within the minute). A burst that reads the same stale stamp writes once per request in flight. That is
  bounded by the edge (4 per client, 32 handlers in all) and harmless.
- **The gateway.** A keyed registration's pushkey is `rejected` before `minimise` and `accept`, and stamped. So a
  homeserver, which never signs, can't push a keyed endpoint around the check. An unkeyed push queued through our
  gateway sets `gateway_at`, throttled as `outside_gateway_at` is.
- **The key at registration.** `valid_vapid` keeps R1's 87-character alphabet check and adds `point()`: 65 bytes,
  `0x04`, and accepted by `ring::agreement`. Anything else is 400 before the transaction.
- **The retry's key update.** It needs the device's bearer, the same `reg`, **the endpoint secret** (`stored ==
  hash`) and the same package. So an app server or anyone else holding only the endpoint can't replace the key: the
  device API isn't reachable with the endpoint alone. An attacker holding the device's credential **and** the secret
  could replace it, but that credential already lets them register their own rows (R1). A keyless retry keeps the
  key. A key added to a keyless row is taken (Fedilab's first start without its instance's key, then a later start
  with it). The update clears `vapid_refused_at`. The same key writes nothing, and the transaction is dropped
  (rolled back) as in R1.
- **`refresh`.** Its SQL is the PWA's mark, and `refused` wins when both hold. Every listed `reg` is in `regs` (both
  select from `up_registrations` by `device_id`), and `the_contract` checks it. `last_push_at` and
  `vapid_refused_at` are both `datetime('now')` text, so comparing them as strings is sound.

## High

None.

## Medium

None.

## Low

**L1. The stored-key read runs an ECDH with a random key on every push to a keyed registration, and fails open if
the RNG errors.** `stored_key` → `point` → `webpush::valid_browser_key` generates an ephemeral P-256 key
(`SystemRandom`) and runs an agreement. That is two scalar multiplications per request, **before** the form and shape
checks. So the design's "a garbage token costs nothing" (§4.3) doesn't hold: every request to a keyed endpoint costs
about as much as the verify itself, even with no `Authorization`. Worse, if `generate` fails, `valid_browser_key`
returns `false`. `stored_key` then logs "isn't a P-256 point" and returns `None`, and `publish` and `notify` take the
push **unchecked**. On Linux, getrandom doesn't fail after boot, so this is a corner case, but it is a security check
that fails open on an error. **Fix:** decide L7's "not a point" once per stored key, not per request. Keep a small map
from `(registration id, key string)` to the verdict next to `WARNED`. Make an RNG error fail closed: refuse with 503
rather than read "no key". `valid_browser_key` needs a tri-state, or `point` a variant that returns the error. The
point check at registration can stay as it is (a 400 there is already fail-closed).

**L2. The PWA says "Only pushes signed by the app's server" for a registration whose key the check ignores.** The
group query's `MAX(vapid IS NOT NULL)` counts a stored key that isn't a P-256 point (L7: an R1 server stored any 87
characters). The check reads such a key as no key and takes every push. So the parent is told about protection that
doesn't run. **Fix:** compute `signed` in Rust from the rows' keys through the same `stored_key`, so it equals what
`publish` enforces. Alternatively, leave such rows out of `signed` and show nothing.

**L3. The refused warning misdescribes the 503 cases.** `note_refusal` runs for every outcome but `Taken`, so a
correctly signed push refused for `exp` (our clock) or `aud` (our origin) stamps the same `vapid_refused_at`. The PWA
then says "Pushes not signed by the app's server were refused". That is wrong for exactly the cases the design gives
a 503 because **we** may be at fault, and it points the parent away from the server's clock or origin setting. **Fix
(cheap now, since 0153 is unreleased):** store the kind as well (`vapid_refused_why`: `unsigned` for 401/403, `time`
for 503 `exp`, `address` for 503 `aud`). Word the `time`/`address` line as the server's fault, for example nb "Push
signert av appens server ble avvist fordi serverens klokke eller adresse ikke stemmer, sist {when}". If the
design keeps one text, say so in §4.7.

**L4. No signed push is accepted at the route in any test, and the docstring claims what the test doesn't check.**
The vectors' `now` is fixed in the past, so `vapid_at_the_route` can only reach `Time` (503) with a correctly
signed token. Nothing at the route shows that `publish`'s plumbing (the headers it reads, `accepted_origins`, the real
clock) **takes** Mastodon's push or refuses a wrong `aud` (`Address`, which comes after `Time`). That is §14.1's
main risk, "a check that refuses a real sender", and R23-G's row 5 is the first place it would show. Separately, the
docstring says a refusal "charges [no] bucket [and doesn't count] toward the ban", but the test asserts neither (the
code does hold, see above). **Fix:** sign one fresh token in the test with `ring`'s
`EcdsaKeyPair::from_private_key_and_public_key`, using the test key's scalar (the SHA-256 of the fixed phrase; it is
derived, never committed, and the secrets rule holds). Assert `rfc_mastodon`'s shape with `exp = now + 24 h` gives
201 and a foreign `aud` gives 503. Assert the bucket and ban through `rates` after the refusals (one `charge` that
still succeeds, `banned(client)` is `None`), or drop the claim from the docstring.

**L5. The draft form reads only the first `Crypto-Key` header.** A sender that sends `dh=` and `p256ecdsa=` as two
`Crypto-Key` lines, which HTTP allows instead of one comma-joined line, gets `Missing` (401). Mastodon then destroys
the subscription. The design calls the draft form unsupported for payloads (L10), so this is a nit. **Fix:** read
`headers.get_all("crypto-key")`, join the values with `,`, and apply `HEADER_MAX` to the join. *Note, no change
asked:* a correctly signed token without `exp`, or with a non-number `exp`, gets 503 forever, and Mastodon drops each
push after 5 retries but keeps the subscription. Only a broken key holder can produce it. The design's §4.3
classifies it as "time" on purpose.

**L6. The new PWA lines have no rendering test.** The group query's three new columns, among them
`COALESCE(MAX(… AND (gateway_at IS NULL OR gateway_at < outside_gateway_at)), 0)` and the 7-day `CASE`, and the
FluffyChat `missing_open` text are exercised by no test. `i18n::tests` only checks that the keys exist. A slip there
would show only on the device. **Fix:** one test that seeds a registration with a key, an `outside_gateway_at` and a
`vapid_refused_at`, renders `group`, and checks `data-row="signed"`, `data-warning="outside"` and
`data-warning="vapid-refused"` with the app's name. It should also check an allowed, unregistered FluffyChat shows
the `missing_open` text.

**L7. The notes describe a departure that wasn't built.** `docs/notes/server/unified-push.md` says
"`KNOWN_UP_APPS` is `(package, name, missing_key)`". The code is `(package, name, opens_itself: bool)`, with the
key chosen in `handlers/app_push.rs`. The notes also say "a refused flood takes no write lock". It takes one about
once a minute per registration (the design's own wording). **Fix:** correct both lines. Also correct design §8's
`missing_key`, or note the departure there.

## The builder's departures

- **`KNOWN_UP_APPS` as `(package, name, opens_itself)`, the words chosen in `handlers/app_push.rs`.** Accepted. With
  two apps and two texts, a bool is simpler than a key per row, and both texts are in en and nb. A third app with
  its own text would bring the key back. Only the notes are wrong (L7).
- **The three gateway rows tested in Rust (`vapid_at_the_route`) instead of `unified_push_gateway.json`.**
  Accepted. The file is server-only: only `tests::unified_push` reads it, and neither `fake-homeserver.py` nor the
  launcher does, so nothing on another side loses the rows. The Rust test covers all three: a keyed pushkey is
  `rejected`, the refusal is stamped (L9), and a gateway push sets `gateway_at`. Design §7's "three new
  `unified_push_gateway.json` rows" should say where they went.
- **M4's one-key-call-an-hour limit on the phone only.** Accepted. A key update needs the device's bearer and the
  endpoint secret, goes through the device API's own edge, and costs one `UPDATE` inside the `BEGIN IMMEDIATE` that
  every R1 register already takes. A server-side cap would need a new refusal the phone has to map, for no gain
  against an attacker who already holds the device's credential.

## The rest checked

- **Q3 (c) + (d) as decided:** 401 + `WWW-Authenticate: vapid` for no `Authorization` of either form or no key;
  403 for a wrong key, a bad form, `alg`, `crit`, size or signature; 503 + `Retry-After: 300` only for `Time`/
  `Address`. (d)'s `refused` is listed while the stamp is within a day and no push was queued after it.
- **R2's mark:** `gateway_at` only for a queued push through `matrix::notify`, at most once a minute. Marked =
  `outside_gateway_at` within 7 days and `gateway_at` null or older. The policy and the PWA use the same expression.
- **The contract:** `the_contract` produces `refresh_outside` from a real Matrix body at `/up/`, clears it with a real
  gateway push, and produces `refresh_refused`. The register rows `ok_vapid`, `vapid_changed` (the key stored, the
  stamp cleared), `vapid_not_a_point` (400, nothing changed) and the keyless retry (the key kept, H1) all pass. The
  `policy_json_keys_snapshot` gains `refresh`. Migration 0153 is in `migrations_from_73_in_order`.
- **PWA:** the three lines and FluffyChat's text are in en and nb (nb has no English words but "unifiedpush.org").
  "«Sist sett utenfra» under Innstillinger › Push for apper" and "“Last seen from outside” in Settings › App push"
  match the existing `app_push.settings.title`/`.seen`. Askama escapes the app's name. No save was added, so nothing
  can jump to the top.
- **DEPLOY.md:** the clock line is in "App push".
- **The vectors:** they cover every row in §10, including Mastodon's exact spelling. The `alg_none` row stands for
  `HS256` too (the same branch).

---

## Handled

Fixed on `up-r2r3-server` (the commit after d29316c0b):

- **L1.** `vapid::stored_key` keeps the point verdict per key string (a map, emptied past 4096 entries), so the ECDH runs
  once per key, not per request. The RNG error **fails closed**: `webpush::browser_key_check` tells an error from "not a
  point", `stored_key` returns `Stored::Unavailable` (never cached), and `publish` and the gateway answer 503 +
  `Retry-After: 300` instead of taking the push unchecked. The registration's 400 is unchanged. The warning is now once
  per key and carries no registration id.
- **L2.** `signed` is computed in Rust from the package's keys (`GROUP_CONCAT(vapid)`) through `stored_key`: a key that
  isn't a point shows no line (`Unavailable` counts as signed, since pushes are refused).
- **L3.** Migration 0153 (unreleased) gains `vapid_refused_why` (`unsigned`, `time`, `address`; NULL reads as
  `unsigned`); `note_refusal` takes it from `Outcome::why`; the gateway stamps `unsigned`. The PWA words the `time` and
  `address` lines as our clock / our address (`app_push.vapid_refused_time`, `_address`, en and nb), apart from the
  sender's. Not changed: the policy's `refused` still lists any refusal within a day (the phone re-reading its key is
  harmless, and the contract rows seed no `why`).
- **L4.** `a_signed_push_is_taken_at_the_route` signs live with the throwaway test key (scalar derived from the vectors'
  phrase in the test, never committed): a push for the current origin is 201 and queued, a foreign `aud` 503 stamped
  `address`, an `exp` a week out 503 stamped `time`. The docstring no longer claims the bucket and ban are asserted (they
  hold by the code's order, `accept` and `never_seen` after the check).
- **L5.** Every `Crypto-Key` line is read and joined with `,`; the test sends the draft form on two lines.
- **L6.** `the_apps_group_lines_of_108b` renders the group and checks `data-row="signed"` (and its absence for a
  non-point key), `data-warning="outside"` with the app's name, the three refusal texts and FluffyChat's `missing_open`.
- **L7.** The notes' `KNOWN_UP_APPS` is `(package, name, opens_itself)` and the write-lock wording is "about once a
  minute per registration".

Not changed: the design note (`docs/design/108b-unified-push-r2-r3.md`: §8's `missing_key`, §7's gateway rows, §4.7's
one text) - another agent may be writing in `docs/design/`; the three departures are recorded here and in the notes.
