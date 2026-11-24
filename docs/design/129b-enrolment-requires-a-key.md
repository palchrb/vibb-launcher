# Design 129b: A new phone's enrolment requires a proven key (design 129 addendum)

Status: design, **revision 2**, 2026-11-24 (branch `hw-key-enrol-design`, read against `main` 3402da309: S1, L1 and
the L1 gate's fixes merged). Revision 1 was 5397423e7; revision 2 takes its design QA (H1-H3, M1-M7, L1-L9; the finding
is named where its fix lands) and adds **X**, the "unusual address" false positive seen on the owner's Jelly Star on
server 0.93.0 (§8). Server and launcher; the music app is untouched. No code yet.

The owner decided on 2026-11-24, before S2:

1. From now, a **new** phone's enrolment requires a proven hardware key: a refused attestation fails the enrolment,
   the setup code stays live, and the phone shows that it waits.
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
gate's fixes built, and its §5.1, §8 and §9.1 describe S1's "observe" enrolment, which the code QA rounds refer to. This
step has its own decisions, API, migration, copy, tests and questions, and its own design QA. A build agent reads this
note and design 129 §3, §5.1, §5.2, §7.3, §8 and §9.1; 129 points here from §0, §5.1, §7.3, §8 and §9.1 (L9).

Marks: **[code]** read in the code at 3402da309; **[assumed]** to be checked by the build or the gate.

## 0. Short answer

- `POST /api/devices/enroll` with a chain that passes design 129 §3 answers as in S1 (bearer + `observed` key). With a
  chain that is refused - or no key because the phone's Keystore couldn't make one - it **no longer enrols**: it answers
  `409 {state: "waiting", reason, number, retry_after_s}`, keeps the code live, and stores **one row for that attempt**
  (an attempt is a random secret the phone makes once per setup code and sends with every try).
- The phone keeps trying with the same code and attempt, and shows **"Venter på en forelder."** with a **six-digit
  number derived from its attempt** ("Kode: 482 913").
- The PWA shows one orange line, "<phone> couldn't prove its key: <reason>", with **"Sett opp uten maskinvarenøkkel…"**:
  a confirm where the parent **types the number from the phone** and re-authenticates (password or passkey). The server
  finds the attempt whose number it is, marks it allowed and logs it; no matching number, nothing allowed (H1).
- The phone's next try with that attempt enrols on the bearer (`key: {state: "exempt"}`); About This Phone reads
  "Uten maskinvarenøkkel", quiet. A try whose chain passes always wins and enrols with the key.
- Server state is never the phone's refusal: without `LAUNCHER_SIGNATURE_CHECKSUM`, or while Google's list is too old
  to let a key pass, the answer is a `503` with no attempt row and no button, and the server's own orange line (M2).
- **Release order (H2):** a stable Vibb One that speaks this API becomes GitHub's "latest" first; Phone Setup refuses
  to make a QR while the QR's launcher is older than that (design 86's launcher check); only then does the server step
  ship.
- `emulator_keys` (§7) accepts exactly the gate images' chain shape for the debug launcher; it **cannot be compiled into
  a release build**, and while compiled in the server says so in orange (H3).
- **X:** a key registration from a phone whose address history is younger than an hour waits instead of being marked
  `unusual`; S2's commit re-checks a stored `unusual` (§8).
- Migration **0175**. Build: L2 (launcher) and E1 (server) in one branch and one merge (the contract file is shared),
  L2 released first; E2 (`emulator_keys`) beside them; the gate on Android 14 and 16.

## 1. Today [code]

- `handlers::device_api::enroll`: §3's check runs first without the database (`device_key::routes::check`, the
  challenge from the code); then one `UPDATE devices ... WHERE enrollment_code_hash = ? AND expires > now RETURNING`
  consumes the code whatever the key's verdict. A refused chain is stored as `devices.key_refused_reason` and answered
  `{device_id, device_token, key: {state: "refused", reason}}`; a body without `chain` enrols on the bearer alone.
  `list_stale` and a missing `LAUNCHER_SIGNATURE_CHECKSUM` (`app`) are the server's state, never the phone's refusal.
  `EnrollRequest` has no `deny_unknown_fields` (`models.rs`), so a newer launcher's extra fields are ignored by 0.93.0.
- A wrong, used or expired code is `enroll_failed` (401, counted for typed codes in the client's bucket and the typed
  pause). Codes live `TYPED_MINUTES` 15 and `QR_MINUTES` 30 (`enrollment.rs`); one live code per phone row. The enrol
  gate allows `enrollment::IN_FLIGHT` (8) enrolments at once, body included.
- The launcher (`server/Enrollment.kt`): `enrolKey` makes (or reuses, per challenge) the enrolment key; no key sends the
  code alone - after a `KeyGenFailure`, and also when `DeviceKeys.make` returns `null` (the three-key cap, a corrupt book)
  or any other exception (M3). An answer outside `CODE_REFUSED_STATUSES` (400, 401, 403, 404, 410) is transient; the
  QR's kept extras (`QrExtras`) are retried at the start of every sync run (`retryPending`).
- The PWA's re-auth: `admin_auth::reauth(state, session, admin, Proof)` - the password (gated Argon2) or a passkey's
  assertion for a challenge bound to the form's route (`passkeys::reauth_route`); three wrong in a row end the account's
  sessions (`admin_users.reauth_wrong`); a passkey-only parent can only use a passkey (`partials/reauth_passkey.html`).
  There is no separate TOTP step-up: this is the one re-auth for sensitive actions.
- Design 86's launcher check (`launcher_check.rs`) fetches the APK the QR names, reads its identity (`ApkIdentity`,
  `version_code` among it) and blocks the QR on a `Verdict` a phone would refuse (`blocks_qr`).
- The QR's launcher is `releases/latest/download/kids-launcher-mdm.apk`; the stable launcher there today is 0.41.0
  (H2), which knows nothing of this step.
- Address history (design 129 §5.2): `device_addresses` is new in 0.93.0; only **bearer-only** requests note their
  address (`security.rs`: a signed request notes `last_signed_at` instead), and `unusual_address` wants the address first
  seen at least an hour before the registration's challenge.

## 2. Decisions

**D1. Which enrolments.** Every enrolment through a setup code from the server step on, a re-enrolment of an existing
phone row included (a reset phone is a new phone; its key is gone with the reset, design 129 §6.2). Phones enrolled
before it keep their credential and run L1's registration flow; nothing here touches them.

**D2. What fails an enrolment (the phone's reasons).** Every §3 refusal (`chain`, `root`, `revoked`, `expired`,
`software`, `properties`, `app`, `lost`, `challenge`, `proof`; `boot` from S2), and no key at all, which the phone names
in `key_error`: `keystore` (generation failed), `keys_unavailable` (the attestation-key pool is empty, design 129 §2) or
`cap` (the three-key cap left no room). **L2 sends `key_error` on every path that has no key** (M3) - a `null` from
`make`, any exception - and the server reads a missing or unknown `key_error` beside no chain as `keystore`, never as a
code refusal. Each is answered `409 waiting` (§4.1) and the code stays live.

**D3. Server state is not a waiting reason** (M2). Without `LAUNCHER_SIGNATURE_CHECKSUM` no setup code of either kind is
made (Phone Setup's typed-code section shows the QR section's "needs setup" note; `POST /devices/{id}/regenerate-code`
and the QR's POST refuse), and `POST /api/devices/enroll` answers `503 {reason: "server_setup", retry_after_s: 3600}` to
a code made before the setting went away. **Google's list:** while it is stale (design 129 §3 item 3), the server still
runs the chain against the **last good copy** (M1): a refusal for any reason is the phone's (`409 waiting`, so a real
refusal wins); a chain that would pass is `503 {reason: "list_stale", retry_after_s: 60}` - no attempt row, no button -
and the server starts a fetch at once (at most one a minute), with Settings' existing orange line. With no copy ever
fetched, every try with a chain is that `503`. Neither 503 is a refusal on the phone: it keeps trying (D11).

**D4. One row per attempt, bound to the live code** (H1, L2). The **attempt** is 16 random bytes the phone makes once per
setup code, sent as `attempt` (Base64url) in every enrol body for that code; the server keeps only its SHA-256. On the
phone it lives in the key book's `enrol_attempts` map, **enrolment challenge -> attempt**, independent of any alias
(M4): a phone without a key has one too, a process death keeps it, `newEnrolment` clears the map.

The **number** is `u32_be(SHA-256("vibb-attempt-v1" || 0x00 || SHA-256(attempt))[0..4]) mod 1 000 000`, six digits shown
as "482 913": the phone computes it from its secret, the server from the stored hash. The phone shows it; **the PWA never
does** - the parent reads it off the phone in front of them. A second holder of the code, from the same home address or
seconds apart, has its own attempt and its own row; it can't see the phone's number, and the parent's confirmation goes
to the row whose number they typed.

- Rows are `enroll_key_attempts`: one per (phone, attempt), holding the live code's hash. At most **8 rows per code**; a
  ninth attempt is `409 waiting` with no row, logged once per code with a count (L2).
- A try with a known attempt updates `attempts`, `last_at` and the latest `reason` (and SPKI, the log's only). Logged
  once per attempt, at its row's creation (`device_key_refused`).
- A row whose code is no longer the phone's live code is dead: every read joins `devices.enrollment_code_hash` and
  `enrollment_code_expires_at > now`; rows go at the next code, at any enrolment of the row (but the used one, D9), at
  Revoke Access, with the phone (cascade) and by the hourly retention sweep.
- A row is tried at most once per 10 s (`429`, `Retry-After`); the phone's own pace never hits it. This bounds writes,
  not CPU (L1): §3 runs before the code is read, and the enrol gate's 8 slots and the body's 5 s bound that work.
- **The code's tag** (M5): `devices.enrollment_code_tag`, 8 random bytes made with each code. The confirm form carries
  it; a form from a page drawn under another code matches nothing and lands as "Ny kode - se igjen" (§5.1).

**D5. The phone's tries don't count as failures.** The code was right: neither a waiting answer nor a poll counts
against the client's enrolment bucket or the typed-code pause (design 129 §5.1 counted a key refusal against the bucket;
that would block a phone that polls - 129 now points here, L9). Guessing still counts: the code is checked before any
attempt row is read or written (§4.2). The typed pause and a blocked client still answer 429 first.

**D6. A passing chain always wins.** Every try runs §3 in full, first. A try that passes enrols with the key as S1 does
(bearer + `observed` until S2; `{device_id, key_id}` from S2), deletes the code's attempt rows and never sets
`key_exempt_*`, also when one of them was allowed. A phone whose pool refills meanwhile enrols with its key (D11 retries
generation).

**D7. The parent's confirmation** (`POST /devices/{id}/key-exception`, full auth, a `<details>` confirm):
- fields: `number` (the six digits typed from the phone, spaces ignored), `tag` (D4), `back`, and the re-auth's
  `password` / `assertion`;
- the re-auth first, `admin_auth::reauth` (`["devices", n, "key-exception"]` joins `passkeys::reauth_route`); a wrong
  one counts `reauth_wrong` and logs `device_key_exempt_refused`;
- then, in one transaction (M6, §4.2): the live rows of this phone whose code hash is the phone's live code, whose code's
  tag is the form's and whose number is the typed one, not yet allowed, with a reason D8 covers. Exactly one -> allowed;
  none -> "Koden stemmer ikke." on the field. **Five wrong numbers per code** end that code (its hash and tag cleared,
  its rows gone, logged `device_key_exempt_refused` "too many codes"): the parent makes a new one. Two rows with the
  same number (one in a million per pair, at most 8 rows) -> neither is allowed; the field asks for a new setup code;
- an allowed row moves the code's expiry to at least now + 10 minutes (`EXEMPT_GRACE_MIN`; `state.enrollment.refresh`
  after it) and logs `device_key_exempted` with the parent, the phone, the reason, the attempt's time and address.
Confirming is permission, not enrolment: the line becomes "Venter på telefonen" until the phone's next try.

**D8. Which reasons the exception covers.** Every reason of D2 but `app`, `challenge` and `proof`, for which the line
shows without the button:
- `app` (the key's app is not Vibb One signed with this server's key) is **help for an honest wrong install, not a
  security boundary** (M1): a dishonest phone can send `key_error: keystore` instead of its chain and get the button.
  The (i) says to install the official Vibb One. (Owner question 3.)
- `challenge` and `proof`: the body doesn't match its own code - a bug or a replayed body. The phone signs a fresh proof
  every try, so an honest phone's next try is clean.

**D9. The exempt enrolment.** The phone's try with the allowed attempt (its chain still refused, or no key) runs, in the
same `BEGIN IMMEDIATE` transaction as the attempt's read (M6): `UPDATE devices ... WHERE id = ? AND
enrollment_code_hash = ? AND expires > now RETURNING` (as S1's code-only enrolment), sets `key_exempt_at`,
`key_exempt_reason`, `key_exempt_by`, marks the attempt row `used_at`, keeps it until the code's expiry + the grace
(`keep_until`), and deletes the code's other rows; the old phone's keys and addresses go as at any enrolment;
`device_enrolled` says "without hardware key".

**A lost exempt answer** (M7): the same attempt again, within `keep_until`, gets a fresh bearer (the token hash replaced,
`TokenIndex` refreshed, the streams closed) at most `ENROLL_REPEATS` (2) times, each logged `device_enrolled` "asked
again"; Revoke Access or any later enrolment deletes the row and ends it. The attempt secret is as strong as the code it
came with (TLS only, lived at most the code's life + 10 min), so a repeat is no weaker than the code itself.

**D10. After an exempt enrolment.** `GET /api/devices/key` and the policy's `device_key` answer `state: "exempt"` while
the phone has no key. The phone's registration flow then tries a key **once a week**, the last try's time kept in the key
book (L3: a process restart never makes it try more often; the try makes one key, within the cap, and deletes it on a
refusal). A registration that passes ends the exception (`key_exempt_*` cleared, `device_key_bound` "ends the
exception"), and S2 treats that key as any other. A refusal of an exempt phone with its exception's own reason is not
logged again. (Owner question 4.)

**D11. The phone while waiting.** The enrolment's caller (QR, "Koble til", "Enroll now", "Ny oppsettkode") gets
`EnrollFailure.Waiting(reason, number, retryAfterS)`; one process-wide `EnrolWait` keeps trying the same code with the
same attempt at `retry_after_s` (clamped 10-300 s), each try under `QrExtras`' mutex and `NonCancellable` once sent,
until: `200` (enrolled, with a key or exempt), a code refusal (`CODE_REFUSED_STATUSES`), or 45 minutes since the first
waiting answer. A transient answer (no network, 5xx, 429, the two 503s, 426) keeps the schedule. **A phone without a
key tries to make one on every try** (M4; the cap applies: a fourth key first deletes the oldest expired, never-named
one - the waiting key's challenge is the live code's, so it is kept). The enrolment key is kept while waiting (the same
SPKI on every try), deleted on a code refusal or an exempt enrolment. The QR's kept extras stay kept while waiting
(`QrExtrasAfter.WAIT`): after a process death the next sync run's `retryPending` gets `409` and starts `EnrolWait` with
the same attempt. A typed code lives only in the process; after a process death, or after the 45-minute stop, the
"Koble til" screen is back to its fields with "Koden er utløpt. Be om en ny." (L7), and the parent types a new code.

**D12. The kid's phone says one line and a number** (L4) wherever the enrolment shows its state ("Koble til", the
Settings dialog, `ReEnrolDialog`): "Venter på en forelder." and below it "Kode: 482 913". The reason is the PWA's alone
(the kid can't act on it; the parent sees it beside the button). (Owner question 1: the owner asked for the reason on the
phone.)

**D13. Release order** (H2). The QR installs whatever launcher `releases/latest` is, and other families' servers take a
new server image the day it ships (Watchtower). So:
1. L2 ships first, in a **stable** launcher release that becomes "latest". L2 must enrol against a server without E1
   (0.93.0 and older): its extra fields are ignored there (§1), and it keeps reading S1's answers (`observed`, and
   `refused` with `KeyRegistration.refusedAtEnrolment`) until E1 is everywhere - the one old path this step keeps, since
   the order forces it; S2 drops it.
2. E1 ships after that. Its Phone Setup refuses to make a QR while the QR's launcher is older than the first L2 code
   (`launcher_check`'s new `Verdict::TooOld { version }`, `blocks_qr`, one orange line "Vibb One i QR-koden er for
   gammel" with the fix behind (i)); the first L2 versionCode is a constant that `release-version.sh --selftest` checks
   against `launcher_tag_codes.json`.
3. A body without `attempt` (an older launcher) is `426 {reason: "update_app"}`: outside `CODE_REFUSED_STATUSES`, so an
   old launcher keeps its QR extras and says it can't reach the server instead of "that code didn't work"; L2 reads
   `update_app` as "Oppdater Vibb One." The PWA's phone summary shows the orange line "{name} har en eldre Vibb One"
   while that code lives (`devices.enroll_old_app_at`).

**D14. No push when a phone starts waiting.** The parent is setting the phone up right then, the phone says "Venter på
en forelder", and the line is on Attention and the Devices list.

**D15. Android 14-17.** Nothing here is version-gated: the launcher's part is an HTTP answer, a timer, a hash and two
lines of text; Keystore is touched exactly as L1 does. `keys_unavailable` is likelier on a phone launched with Android 16
(remote provisioning only, design 129 §10), which is why each try retries generation. The gate runs on 14 and 16 (never
17: its image is broken; the code targets 14-17).

## 3. Data (migration 0175)

`server/CLAUDE.md` says the next free number is 0175 (0174 is S1's `device_keys`); no branch on `origin` or in the local
worktrees carries a 0175 (checked 2026-11-24). If one lands first, take the next.

```sql
-- 0175_enroll_key_attempts.sql (design 129b)
CREATE TABLE enroll_key_attempts (
    id           INTEGER PRIMARY KEY,
    device_id    INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    code_hash    TEXT    NOT NULL,          -- the live code it came with (enrollment::code_hash)
    attempt_hash BLOB    NOT NULL,          -- SHA-256 of the phone's attempt secret; the number derives from it
    spki_sha256  BLOB,                      -- the refused key's, when a chain came (the log's only)
    reason       TEXT    NOT NULL,          -- one word of D2
    address      TEXT    NOT NULL,          -- net::Client::describe of the attempt's first try
    attempts     INTEGER NOT NULL DEFAULT 1,
    first_at     TEXT    NOT NULL,
    last_at      TEXT    NOT NULL,
    allowed_at   TEXT,
    allowed_by   INTEGER REFERENCES admin_users(id) ON DELETE SET NULL,
    used_at      TEXT,                      -- D9: enrolled exempt with this attempt
    repeats      INTEGER NOT NULL DEFAULT 0,
    keep_until   TEXT,                      -- D9: the code's expiry + the grace, for a lost answer
    UNIQUE (device_id, attempt_hash)
);
ALTER TABLE devices ADD COLUMN enrollment_code_tag BLOB;                         -- D4, M5: made with each code
ALTER TABLE devices ADD COLUMN enroll_wrong_numbers INTEGER NOT NULL DEFAULT 0;  -- D7, per code
ALTER TABLE devices ADD COLUMN enroll_old_app_at TEXT;                           -- D13: a body without `attempt`
ALTER TABLE devices ADD COLUMN key_exempt_at TEXT;
ALTER TABLE devices ADD COLUMN key_exempt_reason TEXT;
ALTER TABLE devices ADD COLUMN key_exempt_by INTEGER REFERENCES admin_users(id) ON DELETE SET NULL;
```

`enrollment::new_code` sets the tag and zeroes `enroll_wrong_numbers` and `enroll_old_app_at`. `key_exempt_*` are
cleared by any later enrolment, Revoke Access and a passing registration (D10). `server/CLAUDE.md`'s data model and next
free number move on.

## 4. API

### 4.1 `POST /api/devices/enroll`

Bodies (the contract's `enroll` rows):

```jsonc
{"enrollment_code": "...", "attempt": "<16 bytes, Base64url>", "chain": ["<Base64 DER>", ...], "proof": "<Base64url>"}
{"enrollment_code": "...", "attempt": "<16 bytes, Base64url>", "key_error": "keystore" | "keys_unavailable" | "cap"}
```

| Case | Answer |
|---|---|
| The chain passes | `200 {device_id, device_token, key: {key_id, state: "observed"}}` (S1's; from S2 `{device_id, key_id}`); the code's attempt rows go |
| Refused or no key, the attempt not allowed | `409 {state: "waiting", reason, number, retry_after_s}` (`number` so the phone and the server provably agree; a check, not a secret) |
| Refused or no key, the attempt allowed | `200 {device_id, device_token, key: {state: "exempt", reason}}` |
| The same attempt after its exempt enrolment, within `keep_until`, under `ENROLL_REPEATS` | the same `200` with a fresh token (D9) |
| A try within 10 s of its row's last | `429`, `Retry-After` (transient) |
| A right code without `attempt` | `426 {reason: "update_app"}` (transient on every launcher; D13) |
| No `LAUNCHER_SIGNATURE_CHECKSUM` | `503 {reason: "server_setup", retry_after_s: 3600}`, `Retry-After` |
| Google's list stale and the chain would pass (or no list yet) | `503 {reason: "list_stale", retry_after_s: 60}`, `Retry-After` |
| A wrong, used or expired code | `401` as today, counted as today |
| The lost answer of a key enrolment | S1's repeat, unchanged (design 129 §5.1) |

`retry_after_s` of a waiting answer is the server's pace: 15 s for the row's first 2 minutes, 30 s until 10 minutes,
60 s after (`wait_pace(first_at, now)`, contract rows). S1's `refused` enrolment answer is gone from an E1 server (L2
still reads it from an older one, D13).

### 4.2 The order on the server (M6)

1. The enrol gate, the body (32 KiB), the code's shape, the typed pause and the client's bucket, as today.
2. No checksum: `503 server_setup`.
3. §3 with no database (the challenge from the code; against the last good list when stale, D3), or the body's no-key
   reason. A chain that passes under a stale list: `503 list_stale` (no database).
4. Passing: S1's transaction as today, deleting the code's attempt rows in it.
5. Not passing: `BEGIN IMMEDIATE`; `SELECT id FROM devices WHERE enrollment_code_hash = ? AND expires > now`; none ->
   D9's repeat (an attempt row `used_at`, within `keep_until`) or `enroll_failed` (401, counted). A live code without
   `attempt`: `enroll_old_app_at` set, `426`. Else the attempt's row: allowed -> D9's enrolment in this transaction;
   else upsert (`INSERT ... ON CONFLICT (device_id, attempt_hash) DO UPDATE SET attempts = attempts + 1, last_at = ...,
   reason = ...`; `429` when `last_at` is under 10 s old; a new row only while the code has fewer than 8) and `409`.
   `COMMIT`.

The confirm (D7) is one `BEGIN IMMEDIATE` too, ending in the compare-and-set `UPDATE enroll_key_attempts SET allowed_at
= ..., allowed_by = ? WHERE id = ? AND allowed_at IS NULL AND code_hash = (SELECT enrollment_code_hash FROM devices WHERE
id = ? AND enrollment_code_tag = ? AND enrollment_code_expires_at > now)`; 0 rows -> the "gone" landing (§5.1) - "Telefonen
er koblet til." when the phone enrolled meanwhile, else "Ny kode - se igjen." With per-attempt rows nothing is ever
replaced, so a phone's try and a parent's confirm can't undo each other; a race test runs both from two tasks against one
row, in both orders.

### 4.3 `GET /api/devices/key` and the policy

`state` gains `"exempt"`: `{state: "exempt", rotate: false}` (no `key_id`, no `spki_sha256`) while `key_exempt_at` is set
and no key is stored. The policy's `device_key` the same; `policy_json_keys_snapshot` keeps its keys (a new value, no new
key), `PolicyResponseCompatTest` decodes it. X's wait (§8) is `GET /api/devices/key/challenge` and `POST
/api/devices/key` answering `503 {reason: "address_history", retry_after_s}`.

### 4.4 The PWA's route

`POST /devices/{id}/key-exception` - `number`, `tag`, `back` (`summary` | `provision`), `password` / `assertion`.
Answers: a redirect to the page it came from at `#key-exception` with `?notice=key_exception` (allowed), or the page
rendered there with the confirm open and the field's error (a wrong re-auth, a number that matches no row), or the
"gone" landing (§5.1), or 400 for a reason D8 doesn't cover. `Reauth::SignedOut` and `Busy` answer as every re-auth form
does.

## 5. Copy (nb / en)

Checked against CLAUDE.md "Words in the UI": one orange line for what is undone, the explanation behind (i) alone, a
parent's choice quiet, fewer words on the kid's phone.

### 5.1 The PWA's lines: the phone's summary, Phone Setup above the QR, Attention, the Devices list's badge

| Key | nb | en |
|---|---|---|
| `device.key_wait` (orange) | {name} kunne ikke bevise nøkkelen sin: {reason} | {name} couldn't prove its key: {reason} |
| `device.key_wait.info` (i) | Uten maskinvarenøkkel er telefonens innlogging en kode som kan kopieres fra telefonen. Kjenner du ikke igjen forsøket: lag en ny oppsettkode. | Without a hardware key, the phone's sign-in is a code that can be copied off the phone. If you don't recognise the attempt, make a new setup code. |
| `device.key_wait.app_info` (i, `app`, no button) | Vibb One på telefonen er ikke signert med serverens nøkkel. Installer Vibb One fra den offisielle utgivelsen. | Vibb One on the phone isn't signed with the server's key. Install Vibb One from the official release. |
| `device.key_wait.attempts` (quiet value, more than one attempt) | {count} forsøk | {count} attempts |
| `device.key_exception` (the confirm's summary) | Sett opp uten maskinvarenøkkel… | Set up without hardware key… |
| `device.key_exception.number` (field label) | Kode på telefonen | Code on the phone |
| `device.key_exception.number_wrong` (field error) | Koden stemmer ikke. | That code doesn't match. |
| `device.key_exception.confirm` (submit) | Bekreft | Confirm |
| `device.key_exception.waiting` (quiet, after the confirm) | Venter på telefonen | Waiting for the phone |
| `device.key_exception.connected` (quiet landing, L5) | Telefonen er koblet til. | The phone is connected. |
| `device.key_exception.new_code` (quiet landing) | Ny kode - se igjen. | New code - look again. |
| `device.old_app` (orange, D13) | {name} har en eldre Vibb One | {name} has an older Vibb One |
| `device.old_app.info` (i) | Telefonen må ha en nyere Vibb One for å bli satt opp. Tilbakestill den og skann QR-koden på nytt. | The phone needs a newer Vibb One to be set up. Reset it and scan the QR code again. |
| `settings.provisioning.launcher.too_old` (orange, D13) | Vibb One i QR-koden er for gammel | Vibb One in the QR code is too old |

The attempts are listed in the confirm as quiet rows "{time}, {address}" (never their numbers). The password field and
"Confirm with passkey" are the existing re-auth strings and partial.

`{reason}` reuses `device.about.key.reason.*` (design 129 §9.1), with two new words: `keystore` and `cap` "nøkkellageret
feilet" / "the key store failed", `keys_unavailable` "venter på nøkler fra Google" / "waiting for keys from Google".

### 5.2 About This Phone › Connection, "Innlogging"

| Key | nb | en |
|---|---|---|
| `device.about.key.exempt` (quiet) | Uten maskinvarenøkkel | Without hardware key |
| `device.about.key.exempt.detail` (i) | Valgt av {parent} {date} ({reason}). | Chosen by {parent} on {date} ({reason}). |
| `device.about.key.emulator` (**orange**, H3) | Testnøkkel fra emulator | Emulator test key |

From S2, `boot` as the exception's reason also shows step 2's orange "Ukjent eller ulåst system": that is a fact the
chain proved, not the parent's choice.

### 5.3 Settings (H3)

| Key | nb | en |
|---|---|---|
| `settings.emulator_keys` (orange, while compiled in) | Serveren godtar testnøkler fra emulatorer | The server accepts emulator test keys |
| (i) | Bygget med `emulator_keys`. Bare for testing: en slik nøkkel kan forfalskes. | Built with `emulator_keys`. For testing only: such a key can be forged. |

### 5.4 Security log

| Event | nb | en | Detail |
|---|---|---|---|
| `device_key_refused` (exists; new detail) | Maskinvarenøkkel avvist | Hardware key refused | "{name}, ved oppsett: {reason}, venter på en forelder" / "{name}, at setup: {reason}, waiting for a parent"; the address; once per attempt; "{count} attempts past the limit" once per code |
| `device_key_exempted` (new) | Satt opp uten maskinvarenøkkel | Set up without hardware key | the parent, the phone, the reason, the attempt's time and address |
| `device_key_exempt_refused` (new) | Bekreftelse avvist | Confirmation refused | the parent, the phone; password, passkey, or "too many codes" |
| `device_enrolled` (exists; new details) | - | - | "uten maskinvarenøkkel" / "without hardware key"; "asked again" for D9's repeat |
| `device_key_bound` (exists; new detail) | - | - | "avslutter unntaket" / "ends the exception" |
| `enroll_failed` (exists; new detail) | - | - | "eldre Vibb One" / "older Vibb One" (D13's 426) |

### 5.5 The kid's phone (launcher strings)

| Key | nb | en |
|---|---|---|
| `enrol_key_wait` | Venter på en forelder. | Waiting for a parent. |
| `enrol_key_number` | Kode: {number} | Code: {number} |
| `enrol_update_app` | Oppdater Vibb One. | Update Vibb One. |
| `enrol_code_ran_out` | Koden er utløpt. Be om en ny. | The code has expired. Ask for a new one. |

This replaces design 129 §9.1's "Telefonen kan ikke lage en sikker nøkkel".

### 5.6 No jump

The partial `partials/key_wait.html` renders the line, the (i) and the confirm inside `<section id="key-exception">` on
both pages; the form posts to `/devices/{id}/key-exception#key-exception` with `back`; an error renders the same page with
the `<details>` open; every redirect ends at `#key-exception`, where the quiet row ("Venter på telefonen", "Telefonen er
koblet til.", "Ny kode - se igjen.") keeps the landing, and an empty `<section id="key-exception">` stands when nothing
shows (as Phone Setup's `#launcher`). Pinned by a test (§10.1).

## 6. The window between the refusal and the confirmation

- The code lives its own life (15 or 30 min); the phone tries every 15-60 s with the same attempt, making a key again
  when it has none; nothing is enrolled.
- The parent sees the line once the first try is stored (on the next page load; D14).
- The parent reads the number off the phone, types it, re-authenticates: the code lives at least 10 more minutes; the
  phone's next try (within a minute) enrols exempt.
- The parent makes a new code instead: the rows are dead at once; the phone's next try gets 401 and stops.
- The code runs out: the rows are dead; the phone gets 401 and stops.
- Someone else holds the code and tries too: their attempt is a row of its own ("2 forsøk"); the parent types the number
  from the phone in their hand, which allows only that phone's row. The other attempt gets `409` until the code is used
  or gone, then `401`. If the parent doesn't recognise the situation, the (i) says to make a new code.

## 7. `emulator_keys` (E2): design 129 §7.3 built

**This is not a security check** (H3): the profile accepts a root by its name and its own signature, so anyone can make
a chain it takes. It exists so the debug launcher can enrol on an emulator for the gate, and for nothing else.

- **It can't reach a release build**: `#[cfg(all(feature = "emulator_keys", not(debug_assertions)))]
  compile_error!("emulator_keys is for debug builds only")` in `attestation/mod.rs`. The gate builds the server with
  `cargo build --features emulator_keys` (the debug profile). `server-ci.yml`'s changes job still fails if
  `server-release.yml` or `server/Dockerfile` names the feature (a second line, no longer the only one).
- **While compiled in** the server logs a warning at every start, Settings shows the orange line of §5.3, and every
  phone whose key came through it shows About's orange "Testnøkkel fra emulator" (§5.2).

With it, `attestation::verify` takes a second profile **only when the root is not one of the pinned keys**, and accepts
exactly:

- **three certificates, all P-256**, each signed by the next (ECDSA P-256 SHA-256);
- a root that is **self-signed** and whose subject is exactly `CN=Droid Unregistered Device CA, O=Google Test LLC` (by
  name and self-signature, not a pinned key: it changes with every wiped image);
- the provisioning-info extension on the intermediate **and** the root;
- `attestationVersion` and `keyMintVersion` 300 or 400, both security levels `Software`, the authorisation tags read
  from the **software** list with the hardware list empty (a tag in the hardware list refuses `properties`), §3 item 9's
  rules applied to that list;
- the app `me.vibb.launcher.debug` with the digest in `LAUNCHER_DEBUG_SIGNATURE_CHECKSUM` (read only by an
  `emulator_keys` build; unset, or the release package: `app`);
- the revocation list and validity as §3;
- no boot enforcement (§3 item 13, from S2): the reading is `unknown`, so such a phone also shows step 2's orange line.

It never accepts a fourth certificate, another curve, another root name, a `TrustedEnvironment` claim on a non-pinned
root, the release package, or anything for a chain whose root *is* pinned (the release profile runs alone then).
`Attested` records `root: "emulator"`.

CI: the build job runs `cargo clippy --all-targets --features emulator_keys` and `cargo test --features emulator_keys
attestation device_key` (debug profile). Vectors: `server/testdata/attestation/emulator/` - one chain from each gate
image (Android 14 `compat34`, 16 `aosp36`), captured at the gate's own enrolment with a fake code (emulator
certificates from Google's test CA, never a family phone's), each with the `now` it was read at; negative rows made from
them (the release package, the root renamed, a fourth certificate, a hardware-list tag). Without the feature the same
rows are `root`.

## 8. X: "Registered from an unusual address" right after a server update

Seen on the owner's Jelly Star on 0.93.0: its first key registration was marked `unusual`. A false positive by
construction: `device_addresses` started empty with 0.93.0, and `unusual_address` (`device_key/mod.rs`) wants the address
first seen at least an hour before the challenge, so **every phone that registers in its first hour after the update is
unusual** - and S2 would hold every such phone for the parent (design 129 §8.1).

- **Wait instead of marking** (E1): `GET /api/devices/key/challenge` and `POST /api/devices/key` answer `503 {reason:
  "address_history", retry_after_s}` while the phone has **no** `device_addresses` row first seen more than an hour ago;
  `retry_after_s` is the time until its oldest row is an hour old (3600 with none). A phone with an older row registers as
  today, and an address outside its history is `unusual` as today. L2's registration flow treats `address_history` as it
  treats `list_stale`: it waits `retry_after_s` and asks again, and makes no key before the challenge.
- **Signed requests note their address too** (E1): today only bearer-only requests do (§1), so after L1 the phone's
  history stops growing once it signs. A signed request notes it as a bearer request does (at most hourly per address).
- **S2's commit re-checks a stored `unusual`** against the history at commit time: it is cleared when the registration's
  address has a `device_addresses` row whose `last_seen` is at least 24 hours after the key's registration and within the
  last 30 days - the address stayed the phone's own after the registration, which a registration from elsewhere doesn't
  (its address appears only around the registration). The Jelly's stored mark clears that way at S2. The commit's other
  conditions (a conflict, the boot state) are unchanged.
- Contract: `register` gains the `address_history` answer; the `phone` rows gain "wait `retry_after_s`, no key made".
  Tests: a fresh history waits with the right `retry_after_s`; an hour-old one registers, not unusual; a signed request
  notes its address; S2's re-check as a pure function with rows (written now, used by S2).

## 9. What S2 then changes

- A passing enrolment answers `{device_id, key_id}` (bound, no token), as design 129 §5.1 says.
- `boot` joins D2's reasons (§3 item 13 enforced): the exception covers it; About shows "Uten maskinvarenøkkel" and step
  2's orange line.
- An exempt phone keeps its bearer: S2's edge refuses a bearer only for a phone with a bound key. S2's commit doesn't
  touch exempt phones (no key); design 129 §9.1's orange `key_none` and `key_refused` lines skip a phone with
  `key_exempt_at`. An exempt phone that registers a passing key in S2 binds it as any registration does, and the
  exception ends (D10).
- S2's commit re-checks `unusual` (§8).
- L2's reading of S1's `refused` enrolment answer (D13) goes.
- Owner question 5: whether S2's orange `key_refused` on a phone enrolled before this step offers the same button.

## 10. Build steps

| Step | What | Size | Done when |
|---|---|---|---|
| L2 (launcher), released first (D13) | The attempt map and its number; `key_error` on every no-key path (`cap` new); generation retried on every try; `EnrollFailure.Waiting`/`UpdateApp`, `QrExtrasAfter.WAIT`; `EnrolWait` (pure `enrolWaitStep` + the process runtime under `QrExtras`' mutex); the lines on "Koble til", Settings' enrol dialog and `ReEnrolDialog`; `KeyRegistration`'s `exempt` (a week, kept in the book) and `address_history` wait; S1's enrolment answers still read; nb/en strings; `docs/notes/launcher/security.md` | ~700 lines incl. tests | code QA |
| E1 (server), released after a stable L2 is "latest" | Migration 0175; the enrol order and answers (§4.1-4.2, `wait_pace`, `BEGIN IMMEDIATE`); D3's server states (no code without the checksum; the stale list's last copy, the fetch at once); the attempt rows, the tag, the liveness join, the retention sweep and clean-ups; D9's enrolment and repeat; `POST /devices/{id}/key-exception` (re-auth, the number, five wrong per code, the compare-and-set); `partials/key_wait.html` on the summary and Phone Setup; Attention and the list's badge; About's lines; `launcher_check`'s `TooOld` and the 426's line; `GET /key`'s and the policy's `exempt`; D10's end of the exception; X (§8); the security-log entries; nb/en; tests (§10.1): one `enroll_body(code)` helper in `tests/mod.rs` (a `TestKey` chain through the fake attest, with an attempt) used by `TestApp::enrolled_device` and the ~28 tests that post `/api/devices/enroll`; the tests that run with `launcher_signature_checksum = None` (in `handlers/provisioning.rs` near lines 61, 219 and 687, and `device_key/mod.rs` near 1147) get D3's expectations (L8); `docs/notes/server/device-key.md`, `server/CLAUDE.md` (0175) | ~1400 lines incl. tests | code QA |
| E2 (server) | `emulator_keys` (§7): the profile, the `compile_error!`, the warning and the two orange lines, `LAUNCHER_DEBUG_SIGNATURE_CHECKSUM`, the vectors, CI | ~450 lines + vectors | code QA |
| Contract | `server/testdata/device_key.json` (written by `scripts/device-key-vectors.py`, byte-identical in the launcher's test resources): L2 and E1 change it together, **one branch, one merge** (API in both dirs); the launcher tag from that merge goes stable before the server tag | in L2/E1 | both suites green |
| Gate | §10.3, Android 14 and 16 | - | the owner's OK to run it |

L2 and E1 may be built by two agents in their own worktrees (the contract rows agreed first, §10.2) and merged together;
E2 is independent and can go first (the gate needs it).

### 10.1 Tests

Server (`tests::enrol_key`, new; `device_key::tests`; `attestation::tests`; `launcher_check::tests`):
- a passing key enrols `observed`, the code's attempt rows go; a refused one is `409 waiting` with the number, the code
  still live and the row stored, **not counted** in the client's bucket or the typed pause; a wrong code still `401`
  and counted;
- the same attempt again: `attempts + 1`, no second log line; within 10 s: `429` with `Retry-After`; another attempt: a
  second row ("2 forsøk"); the ninth attempt: `409`, no row, logged once with a count;
- the confirm: the right number and password -> allowed, the code's expiry at least now + 10 min,
  `device_key_exempted`; a passkey's assertion for `/devices/{id}/key-exception` (`the_reauth_routes`); a wrong password
  -> the page at `#key-exception` with the confirm open, `reauth_wrong + 1`, `device_key_exempt_refused`; a wrong number
  -> the field error, and the fifth ends the code; **a stale tab** (a form drawn under the previous code) -> "Ny kode - se
  igjen.", nothing allowed (M5); enrolled meanwhile -> "Telefonen er koblet til." (L5); `app`, `challenge`, `proof` ->
  400 and no button on the page;
- **the race** (M6): a try and a confirm on one row from two tasks, both orders, end allowed-then-enrolled or
  allowed-and-waiting, never lost; two tries of one attempt at once make one row;
- the allowed attempt's try enrols exempt (`key_exempt_*` set, the other rows gone, `device_enrolled` "without hardware
  key"); **a passing try while a row is allowed** enrols with the key and leaves `key_exempt_*` empty; the lost exempt
  answer: two repeats with fresh tokens (the old one refused), the third `401`;
- a new code, Revoke Access and deleting the phone remove the rows; the retention sweep removes dead ones;
- `426 update_app` without `attempt` (right code: `enroll_old_app_at` and the summary's line; wrong code: `401`); a no-key
  body with an unknown `key_error` -> `keystore`; `server_setup` and no code of either kind without the checksum, also
  while an attempt waits; the stale list: a refused chain `409`, a chain that would pass `503 list_stale` with the fetch
  started, no row;
- `GET /key` and the policy say `exempt`; a passing registration of an exempt phone ends it;
- X (§8): the `address_history` waits, the signed request's address, the S2 re-check rows;
- `launcher_check`: an APK whose versionCode is under the first L2 code is `TooOld` and blocks the QR;
- the pages: the orange line on the summary, Phone Setup, Attention and the list; the quiet landings; About's quiet
  "Uten maskinvarenøkkel" and orange emulator line; Settings' emulator line; both languages; **no jump** (every action
  and redirect ends in `#key-exception`, the section exists when empty);
- `the_contract_routes` produces the new `enroll` and `register` rows; `wait_pace` and the number run the contract's
  rows;
- `emulator_keys`: §7's rows with the feature, `root` without it.

Launcher (`DeviceKeyContractTest`, `EnrolWaitTest` new, `QrExtrasTest`, `KeyRegistrationTest`):
- the bodies encoded exactly (with `attempt`; with each `key_error`); every answer decoded and mapped (`waiting` ->
  `Waiting` with its number, `exempt` -> enrolled with the key deleted, `update_app`, `server_setup`, `list_stale`,
  `429` -> transient; S1's `observed` and `refused` still read);
- the number from the attempt matches the contract's rows (and so the server's);
- `enrolWaitStep` runs the contract's `enrol_wait` rows (the delay from `retry_after_s`, clamped; stop on a code refusal,
  on 200, after 45 min; keep the schedule on a transient answer; make a key on a try when none is held);
- `qrExtrasAfter(Waiting)` is `WAIT`;
- the attempt map: one attempt per enrolment challenge, with or without a key, kept across a reload, gone at
  `newEnrolment`; the enrolment key kept while waiting; `key_error` set on every no-key path (`null` from `make`, an
  exception, `KeyGenFailure`);
- `keyStep` with `exempt`: a week from the book's last try, also across a restart; with `address_history`: wait, no key;
- the strings exist in nb and en.

### 10.2 Contract rows (`server/testdata/device_key.json`)

- `enroll.body_with_key` gains `attempt`; new `enroll.body_no_key` (`attempt`, `key_error`: one row per word and one
  unknown); `enroll.answers` gains `waiting` (one per reason of D2, with `number`), `exempt`, `exempt_repeat`, `throttled`
  (`429`, `Retry-After`, transient), `update_app`, `server_setup` (also while an attempt waits), `list_stale` (the new
  `503`); S1's `refused` and `list_stale` rows move to `enroll.answers_s1` (read by L2 until S2, D13);
  `enroll.exception_reasons` (D8); `enroll.wait_pace` (`{since_first_s} -> retry_after_s`: 0, 119, 120, 599, 600, 3000);
  `enroll.numbers` (`{attempt} -> {attempt_hash, number}`, from fixed fake attempts).
- `register.get_key` gains `exempt`; `register` gains `address_history`; `policy` gains `exempt`.
- New `enrol_wait`: `{answer, retry_after_s, since_first_ms, has_key}` -> `{action: retry|stop|enrolled, delay_ms,
  make_key}` (L2's alone to run, as `phone` is).
- `phone` gains `exempt` (wait a week from the last try, then register) and `address_history` (wait, no key) cases.

### 10.3 The emulator gate (Android 14 and 16 only)

The owner's OK first. Debug launcher; a debug-profile server with `emulator_keys` and `LAUNCHER_DEBUG_SIGNATURE_CHECKSUM`,
and a release-feature one, each fresh; a typed code and a QR (Settings' scanner) where it matters.

| Row | Expected |
|---|---|
| `emulator_keys` server, fresh enrolment | enrolled with the key at the first try; About's orange "Testnøkkel fra emulator"; Settings' orange line |
| `emulator_keys` server without `LAUNCHER_DEBUG_SIGNATURE_CHECKSUM` | `409` `app`; the PWA's line without the button; the phone's "Venter på en forelder." and its number |
| Release-feature server, fresh enrolment | `409` `root`; the phone's line and number; tries at 15 s, then 30 s (server log); the PWA's orange line on the summary, Phone Setup, Attention and the list |
| The confirm: a wrong number, a wrong password, then right | the page stays at `#key-exception` each time; then "Venter på telefonen"; the phone enrols exempt within its next try; About "Uten maskinvarenøkkel"; the security log's lines |
| The confirm with a passkey | the same, through "Confirm with passkey" |
| A second emulator with the same code (both images) | two attempts, "2 forsøk"; the first's number allows only the first; the second keeps `409` until the first enrols, then `401` |
| A stale tab | a form drawn before a new code: "Ny kode - se igjen.", nothing allowed |
| The 429 pace | two Settings "Enroll now" taps within 10 s: the second `429`, the phone keeps its schedule |
| A no-key phone (SINK shape with an emptied pool where the image allows; else a debug-only forced `keys_unavailable`) | `409` `keys_unavailable` with no chain; each try tries to make a key; with the pool back the next try enrols with the key |
| A lost exempt answer (the proxy holds the `200` past the phone's timeout) | the next try with the same attempt gets a fresh token; enrolled; one `device_enrolled` "asked again" |
| The exempt phone's weekly try | none on the next syncs or after a process restart; with the phone's and the server's clocks a week on (§6bj's method), one try, refused, its key deleted, not logged again |
| A new code while waiting | the old rows gone from the page; the phone's next try `401`, it stops |
| The 45-minute stop on a typed code | the screen back to its fields with "Koden er utløpt. Be om en ny." |
| Process death while waiting (QR) | `adb shell am kill`; the next sync run's retry gets `409`, the tries resume with the same attempt and number |
| A launcher without `attempt` (the L1 build) | `426`; the phone says it can't reach the server and keeps its QR extras; the PWA's "har en eldre Vibb One" |
| `list_stale` (the stored list 8 days old, the fetch blocked) | a refused chain still `409`; on `emulator_keys` a passing chain `503 list_stale`, no row; unblocked, the next try enrols |
| X: a phone enrolled before (the E1 server over 0.93.0's data) | its key registration waits `address_history` until its history is an hour old, then registers, not unusual |
| An enrolled phone from before this build | unaffected: syncs on its bearer, registers its key as L1 does |

## 11. Owner questions (visible costs first, each with a recommendation)

1. **The kid's phone shows "Venter på en forelder." and a six-digit code; you type that code into the PWA before
   "Bekreft".** One more field, but it is what stops someone else who has the setup code from getting the exception.
   The reason (for example "ukjent utsteder") shows only in the PWA, not on the phone, though you asked for it on the
   phone. *Recommended: yes, as written.*
2. **Release order: a stable Vibb One with this change becomes "latest" first, and Phone Setup makes no QR while the
   QR's Vibb One is older.** The server change waits for that launcher release; until then new phones enrol as today.
   *Recommended: yes.*
3. **No button when the reason is "feil app-signatur"** (Vibb One on the phone isn't signed with your server's key). It
   only helps an honest wrong install (a phone can hide the reason), so the line just says to install the official Vibb
   One. *Recommended: no button.*
4. **A phone set up without a hardware key tries to make one once a week, and if it works the exception ends by itself**
   (logged). *Recommended: yes.*
5. **At S2: should a phone enrolled before this change, whose key is refused, get the same "Bruk uten
   maskinvarenøkkel" button on its orange line**, so it turns quiet? *Recommended: yes; decided with S2's design.*
