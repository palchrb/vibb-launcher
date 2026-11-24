# Design 129b: A new phone's enrolment requires a proven key (design 129 addendum)

Status: design, revision 1, 2026-11-24 (branch `hw-key-enrol-design`, read against `main` 3402da309: S1, L1 and the
L1 gate's fixes merged). Server and launcher; the music app is untouched. No code yet.

The owner decided on 2026-11-24, before S2:

1. From now, a **new** phone's enrolment requires a proven hardware key: a refused attestation fails the enrolment,
   the setup code stays live, and the phone shows why.
2. **No server-wide opt-out.** `LAUNCHER_SIGNATURE_CHECKSUM` is simply required (Phone Setup already shows no QR
   without it).
3. **A per-phone exception** for a phone that can't prove its key: the PWA says "<phone> couldn't prove its key:
   <reason>" with "Sett opp uten maskinvarenøkkel"; the parent confirms with the PWA's re-auth, it is logged, and that one
   phone (that one setup code, that one enrolment attempt) enrols on the bearer. About This Phone then shows a quiet
   "Uten maskinvarenøkkel" (a parent's choice is never a warning). Never server-wide, never silent.
4. **Phones already enrolled** (the owner's Jelly Star) are unaffected: they register their key over the bearer as L1
   does, and S2 later commits them.
5. **Emulators**: the debug launcher on a server built with the Cargo feature `emulator_keys` must still enrol (§7.3's
   corrected list); a release server never accepts that chain.

**Why a note of its own** and not more sections of design 129: 129 is at revision 2 with Q1-Q10 decided, S1, L1 and the
gate's fixes built, and its §5.1, §8 and §9.1 describe S1's "observe" enrolment, which is history the code QA rounds refer
to. This step has its own decisions, API, migration, copy, tests and questions, and gets its own design QA. A build
agent reads this note and design 129 §3, §5.1, §5.2, §7.3, §8 and §9.1; 129 points here from those sections.

Marks: **[code]** read in the code at 3402da309; **[assumed]** to be checked by the build or the gate.

## 0. Short answer

- `POST /api/devices/enroll` with a chain that passes design 129 §3 answers as in S1 (bearer + `observed` key). With a
  chain that is refused - or no chain because the phone's Keystore couldn't make one - it **no longer enrols**: it
  answers `409 {state: "waiting", reason, retry_after_s}`, keeps the code live, and stores **one waiting row** for that
  phone's code: the attempt (a random secret the phone sends with every try for this code), the reason, the address and
  the time. A code-only body is a `400 key_required`.
- The phone **keeps asking with the same code and the same attempt**, at the server's pace (15 s, then 30 s, then 60 s),
  showing "Telefonen kan ikke lage en sikker nøkkel (<reason>). Spør en forelder."
- The PWA shows the waiting row as **one orange line** on the phone's summary, Phone Setup, Attention and the Devices
  list, with **"Sett opp uten maskinvarenøkkel…"**: a `<details>` confirm that names the attempt (time, address) and asks
  for the parent's password or passkey (`admin_auth::reauth`, the re-auth of Show Child PIN and the backups). Confirmed,
  the row is **allowed for that attempt only**, the code lives at least ten more minutes, and the security log gets
  `device_key_exempted`.
- The phone's next try with that attempt enrols on the bearer: `200 {device_id, device_token, key: {state: "exempt",
  reason}}`. About This Phone › Connection's "Innlogging" reads "Uten maskinvarenøkkel", quiet. If a later try's chain
  passes instead (Google's list fetched again, the key pool refilled), the phone enrols with its key and the exception
  is simply unused.
- `emulator_keys` (§7) accepts exactly the gate images' chain shape, for the debug launcher; the release workflow and
  the Dockerfile can't enable it.
- New migration **0175**. Build: E1 (server) and L2 (launcher) in one branch and one merge (the contract file is
  shared), E2 (`emulator_keys`) beside them; the gate on Android 14 and 16.

## 1. Today [code]

- `handlers::device_api::enroll`: §3's check runs first without the database (`device_key::routes::check`, the
  challenge from the code); then the one `UPDATE devices ... WHERE enrollment_code_hash = ? AND expires > now RETURNING`
  consumes the code whatever the key's verdict. A refused chain is stored as `devices.key_refused_reason` and answered
  `{device_id, device_token, key: {state: "refused", reason}}`; a body without `chain` enrols on the bearer alone.
  `list_stale` and a missing `LAUNCHER_SIGNATURE_CHECKSUM` (`app`) are the server's state, never the phone's refusal.
- A wrong, used or expired code is `enroll_failed` (401, counted for typed codes in the client's bucket and the typed
  pause). Codes live `TYPED_MINUTES` 15 and `QR_MINUTES` 30 (`enrollment.rs`); one live code per phone row
  (`devices.enrollment_code_hash`).
- The launcher (`server/Enrollment.kt`): `enrolKey` makes (or reuses, per challenge) the enrolment key; no key (a
  `KeyGenFailure`, `keys_unavailable`) sends the code alone. An answer outside `CODE_REFUSED_STATUSES` (400, 401, 403,
  404, 410) is a transient failure; the QR's kept extras (`QrExtras`) are retried at the start of every sync run
  (`retryPending`), a refused code is marked and dropped. "Koble til" (`setup/ConnectActivity`), Settings' "Enroll now"
  and `ReEnrolDialog` call the same `Enrollment.enroll`.
- The PWA's re-auth: `admin_auth::reauth(state, session, admin, Proof)` - the password (gated Argon2) or a passkey's
  assertion for a challenge bound to the form's route (`passkeys::reauth_route`), three wrong in a row end the account's
  sessions (`admin_users.reauth_wrong`); a passkey-only parent can only use a passkey (`partials/reauth_passkey.html`).
  There is no separate TOTP step-up: this is the one re-auth for sensitive actions.
- Phone Setup (`provision_qr.html`) shows no QR without the checksum (`missing_checksum`), but the typed code's section
  still offers "New setup code".

## 2. Decisions

**D1. Which enrolments.** Every enrolment through a setup code from this release on, a re-enrolment of an existing
phone row included (a reset phone is a new phone; the key is gone with the reset anyway, design 129 §6.2). Phones
enrolled before it keep their credential and run L1's registration flow (owner's point 4); nothing here touches them.

**D2. What fails an enrolment.** Every §3 refusal (`chain`, `root`, `revoked`, `expired`, `software`, `properties`,
`app`, `lost`, `challenge`, `proof`; `boot` from S2), Google's list too old (`list_stale`), and no key at all, which the
phone names: `keystore` (generation failed) or `keys_unavailable` (the attestation-key pool is empty, design 129 §2).
Each is answered `409 waiting` (§4.1) and the code stays live. `list_stale` and `keys_unavailable` are usually
transient: the phone's next try may pass, and a passing try always wins over a waiting row (D6).

**D3. The server without `LAUNCHER_SIGNATURE_CHECKSUM` makes no setup code at all**: Phone Setup's typed-code section
shows the same "needs setup" note as the QR section (`devices.qr.needs_setup`), `POST /devices/{id}/regenerate-code` and
the QR's POST refuse with that line, and `POST /api/devices/enroll` answers `503 {reason: "server_setup",
retry_after_s: 3600}` to a code made before the setting went away - never a waiting row, so the exception can't
become a server-wide opt-out by being pressed for every phone. The phone treats it as transient (its QR extras are kept).

**D4. The waiting row is per phone row and bound to its live code and one attempt.** At most one row per phone
(`enroll_key_waits`, keyed by `device_id`), holding the code's hash; a row whose code is no longer the phone's live code
(a new code made, the code consumed, expired) is dead: every read joins it to `devices.enrollment_code_hash` and
`enrollment_code_expires_at > now`, and it is deleted at the next code, at enrolment, at Revoke Access, with the phone
(cascade), and by the hourly retention sweep.

The **attempt** is 16 random bytes the phone makes once per setup code (Base64url, `attempt` in every enrol body for
that code, kept with the enrolment key's challenge in the key book so a process death keeps it); the server keeps only
its SHA-256. It is what the parent's confirmation applies to: the parent saw *this* attempt's time and address, so a
second holder of the code can't ride the exception. Why not the refused key's SPKI: a phone with no key has none, and a
refused chain's SPKI proves nothing more than the attempt secret does (both are only as strong as the code, which is
the credential today). The secret travels only in the TLS-protected enrol body, lives at most the code's life (+10 min,
D7), and is never logged.

- A try with the row's attempt: `attempts + 1`, `last_at`, the latest `reason` (and SPKI, kept for the log only).
  Logged once, at the row's creation (`device_key_refused`, §6), not per try.
- A try with **another** attempt while the row is not allowed: it replaces the row (`rev + 1`), logged again ("another
  attempt"). A parent's form carries the `rev` it showed, so a confirmation never lands on an attempt the parent didn't
  see (§5.2).
- A try with another attempt while the row **is** allowed: `409 waiting` for that try, the row untouched, logged
  ("another attempt while allowed"). The exception stays with the attempt the parent confirmed.
- A try faster than 10 s after the row's `last_at`: `429` with `Retry-After` (the phone's own pace never does it).

**D5. The phone's tries don't count as failures.** The code was right: neither a waiting answer nor a poll counts
against the client's enrolment bucket or the typed-code pause (S1 counted a refusal; that would block a phone that
polls). Guessing still counts: the code is checked before any waiting row is read or written (§4.2). The typed pause
and a blocked client still answer 429 first, as today.

**D6. A passing chain always wins.** Every try runs §3 in full, first, with no database. A try that passes enrols with
the key as S1 does (bearer + `observed` until S2; `{device_id, key_id}` from S2), deletes the waiting row and never
touches `key_exempt_*` - an allowed but unused exception leaves nothing behind but its log line.

**D7. The parent's confirmation** (`POST /devices/{id}/key-exception`, full auth, `<details>` confirm):
- needs `admin_auth::reauth` (password or passkey; `["devices", n, "key-exception"]` joins `passkeys::reauth_route`);
  a wrong one counts `reauth_wrong` as every re-auth form does and logs `device_key_exempt_refused`;
- needs the row live, its `rev` the form's, and its reason one the exception covers (D8);
- sets `allowed_at`/`allowed_by`, and moves the code's expiry to at least now + 10 minutes (`EXEMPT_GRACE_MIN`;
  `state.enrollment.refresh` after it, so a typed code stays live for the typed-code gate), so a parent who took a
  while still reaches the phone's next try;
- logs `device_key_exempted` with the parent, the phone, the reason, the attempt's time and address.
Confirming is permission, not enrolment: the row becomes "Venter på telefonen" until the phone's next try.

**D8. Which reasons the exception covers.** Every reason of D2 but `app`, `challenge` and `proof`:
- `app` (the key's app is not Vibb One signed with this server's key): on an honest system that means the asking app
  is not ours, so the bearer would go to a foreign app. The line shows without the button; (i) says to install Vibb One
  from the official release. (Q1.)
- `challenge` and `proof`: the body doesn't match its own code - a bug or a replayed body, never the phone's hardware.
  The phone signs a fresh proof every try (design 129 §5.1), so an honest phone's next try is clean. No button.

**D9. The exempt enrolment.** The phone's try with the allowed attempt (and a chain still refused, or none) runs the
enrolment as S1's code-only path did - one `UPDATE ... RETURNING` on the live code - and sets `devices.key_exempt_at`,
`key_exempt_reason` and `key_exempt_by`; the old phone's keys and addresses go as at any enrolment; `device_enrolled`
says "without hardware key". **No repeat for a lost answer** (S1's repeat needs a key whose fresh proof it can count,
§5.1): a lost exempt answer leaves the phone with a used code (401), and the parent makes a new code and confirms again.
Rare (one round trip) and never a weaker path.

**D10. After an exempt enrolment.** `GET /api/devices/key` and the policy's `device_key` answer `state: "exempt"` while
the phone has no key. The phone's registration flow then **tries a key once a week** (`EXEMPT_RETRY_MS` = 7 days, in
memory like L1's backoff; a process start may try once more): an OS update or a refilled RKP pool may let it pass. A
registration that passes ends the exception (`key_exempt_*` cleared, logged in `device_key_bound`, "ends the
exception"), and S2 treats that key as any other. A refusal of an exempt phone with its exception's own reason is not
logged again. (Q3.)

**D11. The phone while waiting.** The enrolment's caller (QR, "Koble til", "Enroll now", "Ny oppsettkode") gets
`EnrollFailure.Waiting(reason, retryAfterS)`; one process-wide `EnrolWait` keeps trying the same code with the same
attempt and the same enrolment key at `retry_after_s` (clamped 10-300 s), each try under `QrExtras`' mutex and
`NonCancellable` once sent, until an answer ends it: `200` (enrolled, with a key or exempt), a code refusal (401 and the
others of `CODE_REFUSED_STATUSES`: the code was replaced or ran out), or 45 minutes since the first waiting answer (the
longest code, 30 min, plus the 10-minute grace and slack). A transient failure (no network, 5xx, 429) keeps the
schedule. The QR's kept extras stay kept while waiting (`QrExtrasAfter.WAIT`), so after a process death the next sync
run's `retryPending` gets `409` again and starts `EnrolWait` once more; a typed code lives only in the process (the
screen is back to its field after a process death, and the parent types it again - the attempt secret is in the key
book, so the same attempt is sent). The enrolment key is kept while waiting (the same SPKI on every try), deleted on a
code refusal or an exempt enrolment.

**D12. The kid's phone says one line** wherever the enrolment shows its state ("Koble til", the Settings dialog,
`ReEnrolDialog`): "Telefonen kan ikke lage en sikker nøkkel (<reason>). Spør en forelder." - and a busy indicator while
the next try runs. Nothing else (§5.4).

**D13. Android 14-17.** Nothing here is version-gated: the launcher's part is an HTTP answer, a timer and a line of
text; Keystore is touched exactly as L1 does. `keys_unavailable` is likelier on a phone launched with Android 16
(remote provisioning only, design 129 §10), which is why it waits rather than fails. The gate runs on 14 and 16 (never
17: its image is broken; the code targets 14-17).

## 3. Data (migration 0175)

`server/CLAUDE.md` says the next free number is 0175 (0174 is S1's `device_keys`); no other branch on `origin` or in
the local worktrees carries a 0175 (checked 2026-11-24). If one lands first, take the next.

```sql
-- 0175_enroll_key_waits.sql (design 129b)
CREATE TABLE enroll_key_waits (
    device_id    INTEGER PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
    code_hash    TEXT    NOT NULL,          -- the live code it belongs to (enrollment::code_hash)
    attempt_hash BLOB    NOT NULL,          -- SHA-256 of the phone's attempt secret
    spki_sha256  BLOB,                      -- the refused key's, when a chain came (the log's only)
    reason       TEXT    NOT NULL,          -- one word of D2
    address      TEXT    NOT NULL,          -- net::Client::describe of the attempt's first try
    rev          INTEGER NOT NULL DEFAULT 1,
    attempts     INTEGER NOT NULL DEFAULT 1,
    first_at     TEXT    NOT NULL,
    last_at      TEXT    NOT NULL,
    allowed_at   TEXT,
    allowed_by   INTEGER REFERENCES admin_users(id) ON DELETE SET NULL
);
ALTER TABLE devices ADD COLUMN key_exempt_at TEXT;
ALTER TABLE devices ADD COLUMN key_exempt_reason TEXT;
ALTER TABLE devices ADD COLUMN key_exempt_by INTEGER REFERENCES admin_users(id) ON DELETE SET NULL;
```

`key_exempt_*` are cleared by any later enrolment of the row, Revoke Access, and a passing registration (D10). A
backup carries both (they are ordinary rows); `server/CLAUDE.md`'s data model and the next free number move on.

## 4. API

### 4.1 `POST /api/devices/enroll`

Bodies (the contract's `enroll` rows):

```jsonc
{"enrollment_code": "...", "attempt": "<16 bytes, Base64url>", "chain": ["<Base64 DER>", ...], "proof": "<Base64url>"}
{"enrollment_code": "...", "attempt": "<16 bytes, Base64url>", "key_error": "keystore" | "keys_unavailable"}
```

| Case | Answer |
|---|---|
| The chain passes | `200 {device_id, device_token, key: {key_id, state: "observed"}}` (S1's; from S2 `{device_id, key_id}`); the waiting row goes |
| Refused or no key, the row not allowed for this attempt | `409 {state: "waiting", reason, retry_after_s}` |
| Refused or no key, the row allowed for this attempt | `200 {device_id, device_token, key: {state: "exempt", reason}}` |
| A try within 10 s of the row's last | `429`, `Retry-After` |
| No `attempt`, or neither `chain` nor `key_error`, or an unknown `key_error` | `400 {reason: "key_required"}` (the code stays live; a code refusal on the phone) |
| No `LAUNCHER_SIGNATURE_CHECKSUM` | `503 {reason: "server_setup", retry_after_s: 3600}`, `Retry-After` |
| A wrong, used or expired code | `401` as today, counted as today |
| The lost answer of a key enrolment | S1's repeat, unchanged (§5.1) |

`retry_after_s` of a waiting answer is the server's pace: 15 s for the row's first 2 minutes, 30 s until 10 minutes,
60 s after (`wait_pace(first_at, now)`, contract rows). The phone follows it (D11).

`list_stale` was S1's `{device_token, key: {state: "refused", reason: "list_stale", retry_after_s: 3600}}`; it is now a
waiting reason like the others (its `retry_after_s` the pace above, not an hour: a list fetched again lets the next try
pass). S1's `refused` enrolment answer is gone, and so is the launcher's `KeyRegistration.refusedAtEnrolment` (the L1
gate's finding 2 can't happen: a refused key never enrols).

### 4.2 The order on the server

1. The enrol gate, the body (32 KiB), the code's shape, the typed pause and the client's bucket, as today.
2. No checksum: 503 `server_setup` (before any work).
3. §3 with no database (the challenge from the code), or the body's `key_error`.
4. Passing: S1's transaction as today (and the waiting row deleted in it).
5. Not passing: one transaction - `SELECT id FROM devices WHERE enrollment_code_hash = ? AND expires > now`; none is
   `enroll_failed` (401, counted); else read the phone's waiting row: allowed for this attempt -> D9's enrolment in the
   same transaction; else D4's upsert and `409`.

### 4.3 `GET /api/devices/key` and the policy

`state` gains `"exempt"`: `{state: "exempt", rotate: false}` (no `key_id`, no `spki_sha256`) while `key_exempt_at` is set
and no key is stored. The policy's `device_key` the same; `policy_json_keys_snapshot` keeps its keys (no new key; a new
value), `PolicyResponseCompatTest` decodes the value.

### 4.4 The PWA's route

`POST /devices/{id}/key-exception` - form fields `rev`, `back` (`summary` | `provision`), `password` / `assertion`.
Answers: a redirect to the page it came from at `#key-exception` with `?notice=key_exception` (success), or the page
rendered there with the confirm open and the field's error (wrong re-auth), or the flash `key_exception_changed` (the
`rev` moved: another attempt came in) / `key_exception_gone` (the code ran out or was used) / 400 for a reason D8
doesn't cover. `Reauth::SignedOut` and `Busy` answer as every re-auth form does.

## 5. Copy (nb / en)

Checked against CLAUDE.md "Words in the UI": one orange line for what is undone, the explanation behind (i) alone, a
parent's choice quiet, fewer words on the kid's phone.

### 5.1 The waiting line (orange): the phone's summary, Phone Setup above the QR, Attention, the Devices list's badge

| Key | nb | en |
|---|---|---|
| `device.key_wait` | {name} kunne ikke bevise nøkkelen sin: {reason} | {name} couldn't prove its key: {reason} |
| `device.key_wait.info` (i) | Uten maskinvarenøkkel er telefonens innlogging en kode som kan kopieres fra telefonen. Kjenner du ikke igjen forsøket: lag en ny oppsettkode. | Without a hardware key, the phone's sign-in is a code that can be copied off the phone. If you don't recognise the attempt, make a new setup code. |
| `device.key_wait.app_info` (i, `app`, no button) | Vibb One på telefonen er ikke signert med serverens nøkkel. Installer Vibb One fra den offisielle utgivelsen. | Vibb One on the phone isn't signed with the server's key. Install Vibb One from the official release. |
| `device.key_exception` (the confirm's summary) | Sett opp uten maskinvarenøkkel… | Set up without hardware key… |
| `device.key_exception.attempt` (a row in it) | Forsøk · {time}, {address} | Attempt · {time}, {address} |
| `device.key_exception.confirm` (submit) | Bekreft | Confirm |
| `device.key_exception.waiting` (quiet, after the confirm) | Venter på telefonen | Waiting for the phone |
| flash `key_exception_changed` | Et nytt forsøk kom inn. Se over og bekreft igjen. | A new attempt came in. Check it and confirm again. |
| flash `key_exception_gone` | Oppsettkoden er utløpt. | The setup code has expired. |

The password field and "Confirm with passkey" are the existing re-auth strings and partial.

`{reason}` reuses `device.about.key.reason.*` (design 129 §9.1: `chain`/`root` "ukjent utsteder" / "unknown issuer",
`revoked` "tilbakekalt" / "revoked", `software` "bare programvare" / "software only", `app` "feil app-signatur" / "wrong
app signature", `boot` "ulåst eller ukjent system" / "unlocked or unknown system", the rest "ugyldig" / "invalid"), with
three new ones: `keystore` "nøkkellageret feilet" / "the key store failed", `keys_unavailable` "venter på nøkler fra
Google" / "waiting for keys from Google" (§9.1 named it, the PWA has no string yet), `list_stale` "Googles nøkkelliste er
utdatert" / "Google's key list is out of date".

### 5.2 About This Phone › Connection, "Innlogging" (quiet)

| Key | nb | en |
|---|---|---|
| `device.about.key.exempt` | Uten maskinvarenøkkel | Without hardware key |
| `device.about.key.exempt.detail` (i) | Valgt av {parent} {date} ({reason}). | Chosen by {parent} on {date} ({reason}). |

From S2, `boot` as the exception's reason also shows step 2's orange "Ukjent eller ulåst system" (§8): that is a fact the
chain proved, not the parent's choice.

### 5.3 Security log

| Event | nb | en | Detail |
|---|---|---|---|
| `device_key_refused` (exists; new detail) | Maskinvarenøkkel avvist | Hardware key refused | "{name}, ved oppsett: {reason}, venter på en forelder" / "{name}, at setup: {reason}, waiting for a parent"; "another attempt" variants; the address |
| `device_key_exempted` (new) | Satt opp uten maskinvarenøkkel | Set up without hardware key | the parent, the phone, the reason, the attempt's time and address |
| `device_key_exempt_refused` (new) | Bekreftelse avvist | Confirmation refused | the parent, the phone; password or passkey |
| `device_enrolled` (exists; new detail) | - | - | "uten maskinvarenøkkel" / "without hardware key" |
| `device_key_bound` (exists; new detail) | - | - | "avslutter unntaket" / "ends the exception" |

### 5.4 The kid's phone (launcher strings)

| Key | nb | en |
|---|---|---|
| `enrol_key_wait` | Telefonen kan ikke lage en sikker nøkkel ({reason}). Spør en forelder. | This phone can't make a secure key ({reason}). Ask a parent. |

`{reason}`: the same short words as §5.1 (`enrol_key_reason_*`). This replaces design 129 §9.1's "Telefonen kan ikke
lage en sikker nøkkel" (which had no reason and nothing to do).

### 5.5 No jump

The partial `partials/key_wait.html` renders the line, the (i) and the confirm inside `<section id="key-exception">` on
both pages; the form posts to `/devices/{id}/key-exception#key-exception` with `back`; an error renders the same page with
the `<details>` open; success redirects to the same page at `#key-exception`, where the quiet "Venter på telefonen" row
keeps the landing (an empty `<section id="key-exception">` when nothing shows, as Phone Setup's `#launcher`). Pinned by
a test (§9.1).

## 6. The window between the refusal and the confirmation

- The code lives its own life (15 or 30 min); the phone tries every 15-60 s with the same attempt; each try re-runs §3
  (at most one per 10 s per phone: a chain is at most six signature checks); nothing is enrolled.
- The parent sees the line once the first try is stored (on the next page load - no live refresh; Q2 on a push).
- The parent confirms: the code lives at least 10 more minutes; the phone's next try (within a minute) enrols exempt.
- The parent makes a new code instead: the row is dead at once; the phone's next try with the old code gets 401 and
  stops ("Koden virket ikke", the existing `re_enrol_failed`).
- The code runs out before anything: the row is dead; the phone gets 401 and stops.
- Someone else holds the code and tries first: their attempt is the row; the parent sees an address they don't know
  and makes a new code (the (i) says so). If they try after the parent confirmed the phone's attempt, they get `409`
  and a log line; they never get the exception.

## 7. `emulator_keys` (E2): design 129 §7.3 built

A Cargo feature, not in `default`. With it, `attestation::verify` takes a second profile **only when the root is not one
of the pinned keys**, and accepts exactly:

- **three certificates, all P-256**, each signed by the next (ECDSA P-256 SHA-256);
- a root that is **self-signed** (its signature verifies with its own key) and whose subject is exactly
  `CN=Droid Unregistered Device CA, O=Google Test LLC` (by name and self-signature, not a pinned key: it changes with
  every wiped image);
- the provisioning-info extension on the intermediate **and** the root;
- `attestationVersion` and `keyMintVersion` 300 or 400, both security levels `Software`, the authorisation tags read
  from the **software** list with the hardware list empty (any tag in the hardware list refuses `properties`), the same
  property rules as §3 item 9 applied to that list;
- the app `me.vibb.launcher.debug` with the digest in `LAUNCHER_DEBUG_SIGNATURE_CHECKSUM` (a setting read only by an
  `emulator_keys` build; unset or the release package: `app`);
- the revocation list and validity as §3 (Google's test serials are never on it; the certificates' times against the
  server's);
- no boot enforcement (§3 item 13, from S2): the reading is `unknown`, and such a phone always shows step 2's orange
  line.

What it never does: accept a fourth certificate, another curve, another root name, a `TrustedEnvironment` claim on a
non-pinned root, the release package, or anything for a chain whose root *is* pinned (the release profile runs alone
then). `Attested` records `root: "emulator"`, About This Phone names it ("Emulator (test)").

CI: `server-ci.yml`'s build job runs `cargo clippy --all-targets --features emulator_keys` and `cargo test --features
emulator_keys attestation device_key`; its always-run changes job fails if `server-release.yml` or `server/Dockerfile`
names `emulator_keys`. Vectors: `server/testdata/attestation/emulator/` - one chain from each gate image (Android 14
`compat34`, 16 `aosp36`), captured from the gate's own enrolment with a fake code (emulator certificates from Google's
test CA, never a family phone's), each row with the `now` it was read at; negative rows made from them (the release
package, the root renamed, a fourth certificate, a hardware-list tag). Without the feature the same rows are `root`.

## 8. What S2 then changes

- A passing enrolment answers `{device_id, key_id}` (bound, no token), as design 129 §5.1 says; a `key_required` code-only
  body was already this step's.
- `boot` joins D2's reasons (§3 item 13 enforced): a phone whose verified boot state fails waits for the parent like any
  other refusal, and the exception covers it; its About shows "Uten maskinvarenøkkel" and step 2's orange line.
- An exempt phone keeps its bearer: S2's edge refuses a bearer only for a phone with a bound key. S2's commit doesn't touch
  exempt phones (no key); design 129 §9.1's orange `key_none` and `key_refused` lines skip a phone with `key_exempt_at`.
- An exempt phone that registers a passing key in S2 binds it as any registration does (the bearer dropped, the
  streams closed, design 129 §5.6) and the exception ends (D10).
- Q4 below: whether S2's orange `key_refused` line on a phone enrolled before this step offers the same button.

## 9. Build steps

| Step | What | Size | Done when |
|---|---|---|---|
| E1 (server) | Migration 0175; the enrol handler's new order and answers (§4.1-4.2; `wait_pace`); D3's code refusal on Phone Setup, the typed code's POST and the QR's POST; the waiting row's upsert, liveness join, retention sweep and clean-ups (new code, enrolment, Revoke, delete); `POST /devices/{id}/key-exception` with `admin_auth::reauth` and `reauth_route`; `partials/key_wait.html` on the summary and Phone Setup; Attention and the Devices list's badge; About's "Uten maskinvarenøkkel"; `GET /key`'s and the policy's `exempt`; D10's end of the exception at a passing registration; the four security-log entries; nb/en strings; `TestApp::enrolled_device` enrols with a `TestKey` chain (fake attest) so the suites keep working, and the ~28 tests that post `/api/devices/enroll` themselves are moved to a key body or to the new answers; `docs/notes/server/device-key.md` and `server/CLAUDE.md` (0175) | ~1100 lines incl. tests | code QA |
| E2 (server) | `emulator_keys` (§7): the profile, `LAUNCHER_DEBUG_SIGNATURE_CHECKSUM`, the vectors, CI | ~400 lines + vectors | code QA |
| L2 (launcher) | The attempt secret in the key book (per enrolment challenge, cleared by `newEnrolment`); `key_error` when no key; `EnrollFailure.Waiting`, `QrExtrasAfter.WAIT`; `EnrolWait` (pure `enrolWaitStep` + the process runtime under `QrExtras`' mutex); the line on "Koble til", Settings' enrol dialog and `ReEnrolDialog`; `KeyRegistration`'s `exempt` (a week); `refusedAtEnrolment` removed; nb/en strings; `docs/notes/launcher/security.md` | ~600 lines incl. tests | code QA |
| Contract | `server/testdata/device_key.json` (written by `scripts/device-key-vectors.py`, byte-identical in the launcher's test resources): E1 and L2 change it together, **one branch, one merge** (API in both dirs) | in E1/L2 | both suites green |
| Gate | §9.3, Android 14 and 16, against an `emulator_keys` server and a release-feature one | - | the owner's OK to run it |

E1 and L2 may be built by two agents in their own worktrees (the contract rows agreed first, from §9.2) and merged
together; E2 is independent and can go first. The server ships first in the release, as always.

### 9.1 Tests

Server (`tests::enrol_key`, new; `device_key::tests`; `attestation::tests`):
- a passing key enrols `observed`, the waiting row goes; a refused one is `409 waiting` with the code still live and
  the row stored (reason, address, attempt hash), **not counted** in the client's bucket or the typed pause; a wrong
  code still `401` and counted;
- the same attempt again: `attempts + 1`, no second log line; within 10 s: `429`; another attempt: `rev + 1`, logged;
  another attempt while allowed: `409`, the row untouched, logged;
- the confirm: right password -> allowed, the code's expiry at least now + 10 min, `device_key_exempted`; a passkey's
  assertion for `/devices/{id}/key-exception` (the route listed in `the_reauth_routes`); a wrong password -> the page at
  `#key-exception` with the confirm open, `reauth_wrong + 1`, `device_key_exempt_refused`; a stale `rev` -> the
  "changed" flash; a dead code -> the "gone" flash; `app`, `challenge`, `proof` -> 400 and no button on the page;
- the allowed attempt's try enrols exempt (`key_exempt_*` set, `device_enrolled` "without hardware key", the row
  gone); a passing try after the confirm enrols with the key and leaves `key_exempt_*` empty;
- a new code, Revoke Access and deleting the phone remove the row; the retention sweep removes dead rows;
- `key_required` (no attempt; neither chain nor `key_error`; an unknown `key_error`); `server_setup` without the
  checksum, and Phone Setup making no code of either kind then;
- `GET /key` and the policy say `exempt`; a passing registration of an exempt phone ends it (`device_key_bound` "ends the
  exception");
- the pages: the orange line on the summary, Phone Setup, Attention and the Devices list; "Venter på telefonen" after
  the confirm; About's quiet "Uten maskinvarenøkkel" with its (i); both languages; **no jump** (every form's action and
  every redirect ends in `#key-exception`, and the section exists when empty);
- `the_contract_routes` produces the new `enroll` and `register` rows; `wait_pace` runs the contract's rows;
- `emulator_keys`: §7's rows with the feature, `root` without it.

Launcher (`DeviceKeyContractTest`, `EnrolWaitTest` new, `QrExtrasTest`, `KeyRegistrationTest`):
- the bodies encoded exactly (with `attempt`, with `key_error`); every new answer decoded and mapped (`waiting` ->
  `Waiting`, `exempt` -> enrolled with the key deleted, `key_required` -> refused, `server_setup` -> transient);
- `enrolWaitStep` runs the contract's `enrol_wait` rows (the delay from `retry_after_s`, clamped; stop on a code refusal,
  on 200, after 45 min; keep the schedule on a transient failure);
- `qrExtrasAfter(Waiting)` is `WAIT` (the extras kept, not marked);
- the attempt secret is one per enrolment challenge, survives a reload of the key book and goes at `newEnrolment`;
  the enrolment key is kept while waiting;
- `keyStep` with `exempt`: wait a week, then register; the `phone` rows' new `exempt` cases;
- the strings exist in nb and en (`enrol_key_wait`, the reason words).

### 9.2 Contract rows (`server/testdata/device_key.json`)

- `enroll.body_with_key` gains `attempt`; new `enroll.body_no_key` (`attempt`, `key_error`); `enroll.answers` gains
  `waiting` (one per reason word of D2), `exempt`, `key_required`, `server_setup`; `answers.refused` and
  `answers.list_stale` go; `enroll.exception_reasons` (D8's list) and `enroll.wait_pace` (`{since_first_s} ->
  retry_after_s`: 0, 119, 120, 599, 600, 3000).
- `register.get_key` gains `exempt`; `policy` gains `exempt`.
- New `enrol_wait`: `{answer, retry_after_s, since_first_ms}` -> `{action: retry|stop|enrolled, delay_ms}` (L2's alone to
  run, as `phone` is).
- `phone` gains `exempt` cases (the step-1 answer `exempt`: wait, a week after the last try register).

### 9.3 The emulator gate (Android 14 and 16 only)

The owner's OK first. Debug launcher; a server with `emulator_keys` and `LAUNCHER_DEBUG_SIGNATURE_CHECKSUM`, and a
release-feature one, each fresh; a typed code and a QR (scanned in Settings' scanner) for each row where it matters.

| Row | Expected |
|---|---|
| `emulator_keys` server, fresh enrolment | enrolled with the key at the first try (`observed`, root "Emulator (test)"), no waiting row |
| `emulator_keys` server without `LAUNCHER_DEBUG_SIGNATURE_CHECKSUM` | `409 waiting` `app`; the PWA's line without the button; the phone's line; a new code clears it |
| Release-feature server, fresh enrolment | `409 waiting` `root`; the phone's line "(ukjent utsteder). Spør en forelder."; tries at 15 s, then 30 s (server log); the PWA's orange line on the summary, Phone Setup, Attention and the list |
| The confirm, a wrong password then the right one | the page stays at `#key-exception` both times; then "Venter på telefonen"; the phone enrols exempt within its next try; About "Uten maskinvarenøkkel"; the security log's three lines |
| The exempt phone's registration flow | `GET /key` `exempt`; no registration in the run (logcat `KeyRegistration`), and none on the next syncs |
| A new code while waiting | the old row gone from the page; the phone's next try `401`, it stops with "Koden virket ikke" |
| Process death while waiting (QR) | `adb shell am kill`; the next sync run's retry gets `409` and the tries resume with the same attempt (one row, `attempts` rising) |
| `list_stale` (the stored list 8 days old, the fetch blocked), then the fetch unblocked | `409` `list_stale`; after the list is fresh the next try enrols with the key (on `emulator_keys`) |
| An enrolled phone from before this build (install over it) | unaffected: it syncs on its bearer and registers its key as L1 does |
| Android 14 vs 16 | the same on both |

## 10. Questions for the owner (with recommendations)

- **Q1. No button for `app`** (D8): the key's app is not Vibb One signed with this server's key, so the exception would
  hand the bearer to another app. *Recommended: no button; the line says to install the official Vibb One.* The
  alternative is to allow it like the rest.
- **Q2. A push to the parents when a phone starts waiting?** *Recommended: no* - the parent is setting the phone up
  right then, the phone says "Spør en forelder", and the line is on Attention and the Devices list. A push would be a
  second channel for a moment the parent is already in.
- **Q3. An exempt phone tries its key once a week, and a key that passes ends the exception by itself** (D10, logged).
  *Recommended: yes.* The alternative is never to try again (the exception stays until a new setup code).
- **Q4. From S2, a phone enrolled before this step whose registration is refused** (design 129 Q3: it keeps its bearer
  with the orange "Nøkkel avvist"): *recommended: the same "Bruk uten maskinvarenøkkel…" confirm on that line*, which
  sets `key_exempt_*` and turns the line quiet - a parent's choice is never a warning. Not built in this step; S2's
  design takes it if the owner agrees.
- **Q5. The button at once for the transient reasons** (`list_stale`, `keys_unavailable`)? *Recommended: yes* - the
  phone keeps trying and a passing try always wins (D6), so confirming early costs nothing but a phone that might have
  had a key; the alternative (the button only after ten minutes) makes the parent wait at the phone.
