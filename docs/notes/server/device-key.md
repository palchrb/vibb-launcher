# The hardware-bound phone key, S1: observe (design 129)

The design is `docs/design/129-hardware-bound-phone-key.md` (revision 2). S1 is the server alone; the launcher's part
(L1) and the commit (S2) come later. **In S1 the bearer keeps working for every phone**: a key is stored `observed`,
signatures are verified and their failures logged, and nothing can lock a phone out.

## The attestation check (`src/attestation/`)

- `der.rs` - a small DER reader of our own (no new crate): high tag numbers (`[709]`), definite lengths, BOOLEAN as
  0x00/0xFF only, nothing else. `x509.rs` - the certificate's parts (the signed bytes, serial, names, validity, the key, the extensions) and
  `signed_by` for every algorithm pair the chains use (RSA PKCS#1 SHA-256; ECDSA P-256 and P-384 with SHA-256 or
  SHA-384, ring's). `key_description.rs` - the KeyDescription's fields the check reads; unknown tags are skipped, tags
  must be ascending (the Kotlin library refuses `tags_not_in_ascending_order`).
- `mod.rs::verify` - design 129 §3 items 1-10 in order, each failure one word (`Refusal`). No names are compared:
  the signatures bind the chain (code QA L2). The app (tag 709) is read from the software list, or the hardware list
  when only it has one (L3). Times are ASCII digits before anything is sliced (M3; `x509::tests` changes every byte of
  a real chain). It follows Google's Kotlin
  verifier (`github.com/android/keyattestation`): the KeyDescription is the leaf's alone (any certificate above it
  that carries one is refused); the leaf's own validity is never read; a factory chain (its first intermediate names a
  `serialNumber`, under the RSA root) is never refused for an expired certificate; every other certificate is read
  against the server's time. Purposes: SIGN, and VERIFY beside it is taken (Keystore adds it to some keys; the Kotlin
  library's test chains carry `[2, 3]`) - any other purpose is refused.
- `roots.rs` - the two pinned root keys (SHA-256 of the SPKI). `testdata/attestation/roots/` holds the certificates
  from Google's page; `tests::the_pins_are_the_roots` computes the pins from them. A new root comes in a release.
- `status_list.rs` - Google's revocation list: fetched by `main` (never the tests) at start and then by its
  `Cache-Control`, clamped to 1-24 h, failures retried hourly; the last good copy in `data/attestation/status.json`.
  Older than 7 days (or never fetched): registrations get 503 `list_stale` (`retry_after_s` 3600), and Settings shows
  "Googles nøkkelliste er utdatert" once the list aged or a fetch failed (not in a fresh start's first seconds). Each
  good fetch is read against every stored key's serials: `device_keys.revoked_at` set or cleared (§6.3; the key keeps
  working).
- `boot.rs` - step 2's reading from the RootOfTrust (`verified`, `grapheneos`, `unknown`) and the GrapheneOS list
  `grapheneos_boot_keys.json`, written by `scripts/grapheneos-boot-keys.py` (it verifies GrapheneOS's OpenSSH signature
  with `ssh-keygen -Y verify` against the signer it pins, and refuses an older `timestamp`; `--selftest` in
  `server-ci.yml`'s changes job). S1 records and shows the reading, never enforces it.
- The provisioning info (OID ...2.1.30) is checked for its place (the certificate above the leaf) and form (a CBOR
  map). The documented schema has no lost-device flag (read 2026-11-24), so `lost` is a reserved word for now.
- `LAUNCHER_SIGNATURE_CHECKSUM` is the app check (§3 item 10, Q8), decoded to 32 bytes. Unset: a startup warning and
  every registration refused `app` (the phones keep their bearer). A malformed value is ignored with an error, as
  every `ForkConfig` setting is (`config.rs`): never a refused start.

## Keys, routes and the edge (`src/device_key/`)

- Migration 0174: `device_keys` (a random 16-byte key id, the SPKI and point, what the attestation said, the
  registering address, `unusual`, `state`, `via`, `revoked_at`, `last_signed_at`), `device_key_challenges` (one per
  phone, 10 min), `device_addresses` (each phone's bearer addresses, `net::Client::key`, 30 days, pruned by
  `retention`), `sig_clock` (the signature clock's floor), and on `devices` the enrolment code used with a key (for a
  lost answer), the last refusal's reason and a key conflict.
- `KeyIndex` - `key id -> device, point` in memory for every key not `retired`, rebuilt at start and after every key
  write (enrol, register, revoke, delete).
- The edge (`limits::device_edge`): without a valid bearer, a blocked client is refused and the request takes its
  unauthenticated slot before any verify (code QA L6); a `Vibb-Signature` is read from the headers alone - the key id in the index, the
  window (`sig_now - 60 s ..= sig_now + 30 s`), `Vibb-Body-SHA256`, one P-256 verify, then the nonce (recorded only
  after a verify; 4096 a key, then 429). A valid one takes no unauthenticated slot. The body is wrapped in
  `HashingBody` (no buffering, outside the status route's inflation); a mismatch fails the route's extractor and the
  edge answers 400 `Vibb-Auth: bad_body`. An empty body is compared before the route runs. **When the phone's own
  valid bearer came with the request (S1, code QA M2)** nothing acts against it: the body's hash is only logged, a
  full nonce set is no 429, and its refused signature is never counted against the client. The status route inflates
  its own gzip after the whole compressed body was read and hashed (`device_api::report_body`: one member, nothing
  after it, the device limit; L4).
  `tests::device_key::every_device_post_reads_its_body` pins that every POST device route reads its body through an
  extractor.
- The auth layer (`security::require_device_token`): a valid signature authenticates as its phone; a refused one is a
  401 with `Vibb-Auth: <reason>` (every reason but `stale` counted and logged as `device_auth_failed`) - unless the same
  phone's valid bearer came with it (observe: logged, the bearer authenticates). A request with no credential at all
  gets `Vibb-Auth: missing`. Bearer requests note their address (at most hourly per address); signed ones note
  `last_signed_at` (at most every 10 min).
- `Vibb-Time` (`sig_now`, ms) is on every device answer, the edge's own 429/503 included. The signature clock is
  `max(wall at start + elapsed, base + elapsed - elapsed / 10)` with `base = max(wall clock, stored + 100 s)` (the 10 s
  write, both windows; `sig_now_at`), monotonic time only, written every 10 s and at shutdown: a base ahead of the wall
  clock runs at nine tenths of real time until the wall clock catches it up (the 129 L1 gate's finding 3: it used to
  stay 100 s further ahead after every quick restart, for good).
- Routes: `GET /api/devices/key`, `GET /api/devices/key/challenge`, `POST /api/devices/key` (32 KiB), `POST
  /api/devices/key/conflict`; the enrolment's `chain`/`proof` (the enrol body limit is 32 KiB). The chain is checked
  before any transaction. A registration is `unusual` when the phone's bearer never came from that address in the 30
  days, at least an hour before the registration's challenge (`ADDRESS_KNOWN_BEFORE`: the registration's own bearer
  calls never make an address known). A conflict report needs a stored key (409 `none` otherwise). Logged, with the
  phone's name: `device_key_bound` (the address, unusual, at setup), `device_key_refused` (throttled),
  `device_key_conflict`.
- A lost enrolment answer (§5.1, code QA H1): at most two repeats, each with a proof whose ECDSA `r` was never seen
  for the code (`devices.enroll_used_proof_rs`, `enroll_repeats`), logged as `device_enrolled` "asked again"; Revoke
  Access and any later enrolment clear the kept code. One live key per phone is a unique index.
- The signature clock's floor after a restart is the stored value + 100 s (the 10 s write and both windows, M1); the
  clock never runs faster than real time, so the floor still covers every `t` accepted before a stop.
- L1's code QA: a key index whose reload after a key write failed three times keeps the other phones' entries, drops
  that phone's (a removed key must never sign) and is **stale**: an unknown key id is then `SigCheck::Unavailable` - a
  503 with `Retry-After` (the phone's own bearer still authenticates in S1), never `unknown_key`, which the phone
  counts towards "lost" - and a request retries the reload at most every 10 s (`retry_if_stale`); a reload that works
  ends it. A valid signature that comes with **another** phone's bearer is refused (`bad_sig`).
- The policy's `device_key` (`{state, key_id?, spki_sha256?, rotate}`, `rotate` always false in S1) is pinned by
  `policy_json_keys_snapshot`.
- A re-enrolment replaces the row's keys, challenge and addresses (another phone); Revoke Access removes the keys;
  deleting a phone cascades and refreshes the index.

## The PWA

About This Phone › Connection's "Innlogging" row is a quiet value: "Eldre innlogging", "Maskinvarenøkkel" (behind
the (i): TEE or StrongBox, the attestation version, the security patch, the system read, the root, revoked or not,
and only what is wrong: revoked, not signed yet, an unusual address), "Nøkkel avvist (grunn)" or "Annen nøkkel
registrert". Nothing is orange in S1 but Settings' two lines: the stale list, and no `LAUNCHER_SIGNATURE_CHECKSUM`
(then no phone's row says "refused" - it is the server's setting, code QA L10).

## The contract

`contracts/core/device_key.json`, written by `scripts/device-key-vectors.py` (a throwaway key from a fake phrase;
`--check` in `server-ci.yml`'s changes job). `tests::device_key::the_contract_rows` runs the crypto, clock, window and
answer rows; `the_contract_routes` and `the_edge_rows` produce the route rows through the real routes. Since L1 it is
byte-identical in `launcher/app/src/test/resources/` (`the_contract_matches_the_launchers_copy`), with the `phone` and
`phone_nudge` rows (the registration flow's table, the launcher's alone to run: `DeviceKeyContractTest`). The
launcher's side is `docs/notes/launcher/security.md`, "The hardware-bound phone key".

## E1: a new phone's enrolment requires a key (design 129b)

The design is `docs/design/129b-enrolment-requires-a-key.md` (revision 2, the owner's answers in §11). Phones enrolled
before it keep their bearer and register as above.

- `POST /api/devices/enroll` (`handlers::device_api::enroll`, `src/enrol_key.rs`): no `LAUNCHER_SIGNATURE_CHECKSUM` is
  `503 server_setup` (3600 s) and no setup code of either kind is made (`regenerate_code` back to Connect's
  `#setup-code`, which says the server needs setting up). §3 runs first with no database, against the **last good
  list when stale** (`routes::check_enrol`): a refusal is the phone's; a chain that would pass under a stale list (or
  with none fetched) is `503 list_stale` (60 s), no row, and asks `StatusList::ask_fetch` (at most one a minute; the
  fetch loop wakes on it). A passing key enrols as S1 did (`enrol_with_key`, one transaction: the code's attempt rows
  go, any earlier exception is cleared) - the S1 lost-answer repeat unchanged.
- Not passing (`enrol_key::waiting_try`, one `BEGIN IMMEDIATE`): the live code's phone; none - an exempt row's repeat
  (`used_at`, within `keep_until`, at most `EXEMPT_REPEATS` 2, a fresh bearer each) or the counted `401`; a right code
  without `attempt` is `426 {reason: "update_app"}` (`devices.enroll_old_app_at`, the orange "{name} har en eldre Vibb
  One", logged `enroll_failed` "eldre Vibb One", never counted as a guess); an allowed row enrols exempt
  (`key_exempt_at/_reason/_by`, the row `used_at` and kept until the code's expiry + 10 min, the code's other rows gone,
  `device_enrolled` "Uten maskinvarenøkkel."); else the row upserted - `429` with `Retry-After` under 10 s since its
  last try, a new row only under 8 a code (the ninth waits with no row, logged once per code) - and `409 {state:
  "waiting", reason, number, retry_after_s}` (`wait_pace`: 15/30/60 s). A waiting try never counts against the client's
  bucket or the typed pause. `device_key_refused` "ved oppsett: ... venter på en forelder" once per attempt row.
- A setup code made before 0175 has no tag: its confirm lands on "Ny kode - se igjen." - the parent makes a new code.
  An `attempt` that isn't 16 bytes of Base64url is a 400 (never the older launcher's 426). A typed code's lost answer
  (S1's key repeat, D9's exempt repeat) is looked up even when no typed code is live any more; a miss is the counted
  401 (the typed pause counts only while a typed code is live). D9's repeat needs the exception still standing: a
  passing registration ends both.
- The number (`enrol_key::number`): `u32_be(SHA-256("vibb-attempt-v1" || 0x00 || attempt_hash)[0..4]) mod 1 000 000`;
  the server keeps only the attempt's SHA-256. The PWA never shows it.
- The confirm, `POST /devices/{id}/key-exception` (`enrol_key::confirm`; `passkeys::reauth_route` takes the route):
  `admin_auth::reauth` first (a wrong one counts `reauth_wrong` and logs `device_key_exempt_refused`), then one
  `BEGIN IMMEDIATE`: the form's tag must be the live code's (`devices.enrollment_code_tag`, made by `new_code`; else
  "Ny kode - se igjen." or "Telefonen er koblet til."), the typed number one live, not allowed row's; exactly one with a
  covered reason (all but `app`, `challenge`, `proof` - those 400, and no button) is allowed by the compare-and-set and
  the code's expiry moved to at least now + 10 min (`device_key_exempted`); none counts `enroll_wrong_numbers` - the
  fifth ends the code; two is "Lag en ny oppsettkode.". Every answer is a redirect to the summary or Connect at
  `#key-exception` (`?key_error=` re-opens the confirm with its field's error - the design's "rendered there", as a
  redirect so the password never sits in a rendered POST).
- The pages: `partials/key_wait.html` (`enrol_key::Section`) in `<section id="key-exception">` on the summary and
  Connect, empty when nothing shows; the Devices list's badge counts `attention::Kind::KeyWait`/`OldApp`
  (`enrol_key::facts`, also for a phone not set up). An allowed, unused row shows the quiet "Venter på telefonen",
  beside the orange line while another attempt still waits (§6bm's finding 1). About's "Innlogging" is the quiet "Uten maskinvarenøkkel" with
  "Valgt av {parent} {date} ({reason})." behind the (i) while exempt and keyless.
- `GET /api/devices/key` and the policy's `device_key` answer `{state: "exempt", rotate: false}`; a passing
  registration ends the exception (`device_key_bound` "Avslutter unntaket."); a refusal with the exception's own reason
  isn't logged again.
- The token a re-enrolment of the same row replaced (`TokenIndex::refresh` with a new token retires the row's old one
  for `security::RETIRED_FOR`, an hour, in memory) is refused as before - `401`, or the answer of a signature by a key no phone has (the replaced phone's, removed
  at the re-enrolment) - but writes no `device_auth_failed`; a refusal naming one of the row's current keys is logged
  as always: the phone's old stream reconnecting with it a few times (§6bm's finding 2). Revoke
  Access and the delete forget the row's retired tokens, so they log again; so does a restart.
- The rows die with the code: `new_code` deletes them, Revoke Access too, the phone's delete cascades, and
  `retention::prune` runs `enrol_key::SWEEP_SQL` (a used row lives to its `keep_until`).
- D13: `launcher_check`'s `Verdict::TooOld` blocks the setup QR while its launcher's versionCode is under
  `ENROL_ATTEMPT_FIRST_CODE` (430003, `launcher-v0.43.0-rc.3` - the first tag after every tag made before this step,
  to be tagged from this step's merge commit; the release notes say so;
  `launcher_tag_codes.json`'s `enrol_attempt_first`, checked by `scripts/release-version.sh --selftest` and
  `launcher_check::tests`).
- X (§8): `GET /api/devices/key/challenge` and `POST /api/devices/key` answer `503 {reason: "address_history",
  retry_after_s}` while the phone has no `device_addresses` row first seen over an hour ago (`address_history_wait`);
  signed requests note their address too (`security::require_device_token`); `device_key::unusual_cleared` is S2's
  re-check, written and tested now.
- Migration 0175: `enroll_key_attempts`, and on `devices` the code's tag, the wrong numbers, the older-launcher mark and
  `key_exempt_*`; `device_keys` rebuilt with `security_level` allowing `software` (E2's emulator keys). Tests: `tests::enrol_key`, `enrol_key::tests`, `tests::device_key`.

## `emulator_keys` (design 129b §7, step E2)

**Not a security check**: the emulator gate's way to let the debug launcher enrol with a key. The Cargo feature
`emulator_keys` (never in `default`) compiles `attestation/emulator.rs`; `attestation/mod.rs` has
`compile_error!("emulator_keys is for debug builds only")` for any build without `debug_assertions`, and
`server-ci.yml`'s changes job fails if `server-release.yml` or the Dockerfile names it, or if `server-release.yml` or
`server/Cargo.toml` names debug assertions; `main` also panics at start in such a build without them. While compiled in: a warning at
every start, Settings' orange "Serveren godtar testnøkler fra emulatorer" (`settings_hub`, `emulator-keys`), and About's
orange "Testnøkkel fra emulator" on every key stored with `root = 'emulator'` (in any build: the stored root says it).

- `verify` hands a chain to the profile **only when its root is not pinned**; a pinned root's chain is the release
  profile's alone. The profile takes exactly the gate images' shape (design 129 §7.3): three P-256 certificates, each
  signed by the next with ECDSA SHA-256; a self-signed root named exactly `CN=Droid Unregistered Device CA, O=Google Test
  LLC` (the two attributes in any order, the string type not read); the provisioning info on the intermediate and the
  root; attestation and KeyMint version 300 or 400, the two equal; both levels `Software`, §3 item 9 read from the
  software list, the hardware list empty; the app `me.vibb.launcher.debug` with the digest in
  `LAUNCHER_DEBUG_SIGNATURE_CHECKSUM` (`ForkConfig::launcher_debug_signature_checksum`, read only by such a build;
  `Expect::debug_signature_digest`); revocation as §3, the intermediate's and the root's validity read. The boot state
  comes from the software list and is never enforced (`unknown`). `Attested.root` is `Root::Emulator` (`emulator`).
- Refusals: not the emulator's root or shape (a fourth certificate, another curve, another name, not self-signed, a TEE
  claim, no provisioning info on the root) `root` - as without the feature; a hardware-list tag or another version
  `properties`; the release package, another digest or none set `app`.
- Stored as `software` with `root = 'emulator'` (migration 0175 lets the column say it); About reads the level
  from the column ("Bare programvare").
- Vectors: `testdata/attestation/emulator/` (the two gate images' shapes and one chain per refusal), written by
  `scripts/emulator-attestation-vectors.py` from throwaway keys derived from fake phrases and made-up values - never a
  capture (`--check` in `server-ci.yml`'s changes job). They are made up in the emulator's shape, so **the emulator
  gate's first run is the profile's first real check**; real captures (one per image, Android 14 and 16, each with the
  `now` it was read at) are added as rows at that gate - done 2026-11-24 (`docs/testing/emulator.md` §6bm): both images'
  chains passed unchanged, kept in `scripts/emulator-attestation-captures/` and copied as `captured-*.pem`. `attestation::emulator_tests` runs them: with the feature each
  row's verdict, without it every row `root`. `tests::emulator_keys` covers the two orange lines, the setting, an
  enrolment through the real route (the feature only: the `enroll` block's fixed QR code set on the phone, the proof
  from the throwaway leaf) and the key index leaving an emulator key out without the feature.
- **Never trusted without the feature**: `KeyIndex::reload` leaves out every `root = 'emulator'` row in a build
  without `emulator_keys` (a database moved from a gate server), so such a key's signatures are `unknown_key`.
- CI's build job also runs `cargo clippy --all-targets --features emulator_keys`, `cargo test --features emulator_keys
  -- attestation device_key emulator_keys`, and checks that `cargo check --release --features emulator_keys` fails with
  the `compile_error!`.

## Not in S1

The bound state, `held`,
`device_old_token`, `key_required`, rotation and the step-2 orange line are S2's.
