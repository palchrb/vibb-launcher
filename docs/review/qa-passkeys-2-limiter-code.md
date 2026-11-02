# Security code QA - passkeys-2 (c04fd933): the step-1 follow-ups (limiter, sign-in pool, autofill refresh)

Scope: `net::limit_key` (/56) and every caller of it; `net::crowd_key` and `make_room_by_crowd`; `LIVE_REUSE` and the
handling of the `passkey_started` row in `login_options`; `passkey.js` (the 4.5-minute refresh, the `signedOut`
check); migration 0118; the dummy hash for a passkey-only name. The Kun passkey flows are the other reviewer's. Read
only, no cargo.

**Verdict: mergeable. No High, no Medium.** The /56 key and the eviction from the biggest crowd do what they claim.
Evicting a parent's live sign-in challenge now needs at least 500 distinct /24s or /48s at once, and it can't be
done from the parent's own neighbourhood. The Lows below are worth a follow-up, most of all L1 (a reused challenge
can die under the autofill and cost a ban point). Counts: **High 0, Medium 0, Low 4, Nit 4.**

---

## Low

### L1 - A reused challenge can die under the autofill: 4-minute reuse + a 4.5-minute refresh from the client's own fetch
`server/src/passkeys.rs:49` (`LIVE_REUSE`), `:228` (`live_sign_in_at`), `:939-946` (`request_options`, a fixed
`timeout: 300000`), `server/static/passkey.js:196` (`CONDITIONAL_REFRESH_MS`).

The server hands a session's live challenge out again while it is younger than 4 minutes, but the client times its
refresh from *its own* fetch (4.5 minutes later), and the options always say `timeout: 300000`.

Scenario:
1. The sign-in page loads at t=0 and gets challenge C.
2. At t=3:50 the parent reloads it, or the iPhone PWA reloads on resume, or a second tab opens in the same session.
   It gets C again (3:50 old), while `fetchedAt` = now.
3. C expires at t=5:00, but the autofill's get stays armed on it until t=8:20.
4. A passkey picked in that window is posted to `/auth/passkey`: `Challenge` is refused. The parent sees the red line,
   and a `passkey_refused` row counts toward the address's ban.

The same happens to the button after the 60 s client cache: a prompt can open on a challenge with about 10 s left.

**Fix:** answer the challenge's remaining lifetime: `timeout` = the remaining milliseconds (or an `issued_at` /
`expires_in` field). `passkey.js` then sets the refresh timer at `min(270 s, remaining - 30 s)` and refetches before
opening the button's prompt when less than ~60 s remain. Alternatively, reuse only while the challenge is younger
than `TTL - CONDITIONAL_REFRESH`, i.e. 30 s, which keeps the reload/two-tab dedupe without a dead window. Add a
jstest for "reused options with 60 s left -> the refresh fires within 30 s".

### L2 - Tailnet IPv6 clients are all one limit key and one crowd
`server/src/net.rs:121` (`limit_key`), `:135` (`crowd_key`).

Every Tailscale node's IPv6 address is in `fd7a:115c:a1e0::/48`, and all of them share one /64 too (the fixed
`...:ab12:4843:cd96::/96`, or the newer `fd7a:115c:a1e0::<32 bits>`). So every tailnet client that reaches the
server over IPv6 is one ban key and one crowd: both parents' phones, a laptop, and the kid's phone. This predates the
change (the /64 already lumped them together) and /56 keeps it.

Two effects:
- 15 wrong passwords from any tailnet node over IPv6 (the kid's phone guessing a parent's password in a browser) ban
  every parent on the tailnet over IPv6 for an hour. For a passkey-only parent that is the whole way in.
- The per-client cap (3) is shared too: 4 signed-out sign-in pages across the family's tailnet devices evict each
  other.

This only applies when the admin listener binds IPv6 and the browsers prefer AAAA (MagicDNS gives both).

**Fix:** key a Tailscale ULA address (`fd7a:115c:a1e0::/48`, the range `config.rs`/`calendar_feeds.rs` already
recognize) by the full address (/128) in `limit_key` and `crowd_key`, as the 100.64.0.0/10 IPv4 addresses already
are, one per node. Add a `net::tests` row.

### L3 - A ban on a shared address keeps a passkey-only parent out, and a valid passkey can't get past it
`server/src/handlers/passkeys.rs:87` (options) and `:195` (`/auth/passkey`), `server/src/security.rs:577`.

Both passkey routes refuse a banned key before anything is verified. Anyone sharing the parent's key can get it
banned with 15 wrong passwords in 15 minutes, and then again every hour: a mobile carrier's CGNAT IPv4, the home NAT,
the tailnet IPv6 (L2), or since this change a mobile carrier's shared /56. Mobile carriers commonly hand each phone a
/64 from pools shared by many subscribers, so the RIPE-690 home-line reasoning doesn't hold for mobile. A
password-and-TOTP parent has the same problem today, but a passkey-only parent has no other way in from that network.
The ban guards against guessing, and a passkey assertion can't be guessed.

**Fix (optional; the parent can still switch networks):** let a banned key get one sign-in challenge (it is already
capped at 3 per key, and the pool can't be flooded by it). At `/auth/passkey`, verify first and apply the ban only on
a refusal, so a *valid* passkey signs in through a ban while a bad one is answered 429 as now. Keep the ban for
`/login`.

### L4 - The household's own row can be orphaned and keep counting (the 4-minute deviation)
`server/src/handlers/passkeys.rs:112-138`.

The rule is sound against the attack it targets. Without it, a client cycling 4+ sessions under one key would evict
its own challenges and get every new one uncounted. But when a session's challenge is gone early (evicted by
`SIGN_IN_PER_CLIENT` or the crowd, or taken by a failed try) and the session asks again within 4 minutes, a new row is
made, and `PRE_SIGN_IN_KEY` is overwritten with its id. The younger row is no longer in any session, so neither
`end_sign_in_attempt` nor a later options call can take it back. It counts for the ban's full 15-minute window.

Household scenario: more than 3 signed-out sign-in pages under one key within 5 minutes. For example two parents,
each with the PWA and Safari, after the sessions ran out, plus a reload or button press within 4 minutes on a page
whose challenge was evicted. Each such re-ask leaks one point. A ban needs about 15 of them in 15 minutes, so this is
unlikely. The periodic refresh (4.5 min) and `visibilitychange`/`pageshow` don't trigger it: they fire only after
4.5 minutes, when the old row is old enough to be taken back.

**Fix:** keep the session's open rows as a short list in the session (at most `SIGN_IN_PER_CLIENT + 1`). A
*successful* sign-in (password or passkey) deletes them all. A refusal or an aged-out re-ask deletes as now (the
current row; rows older than 4 minutes). A family that signs in then pays nothing, and a client churning sessions
still pays one per challenge, since it never signs in.

---

## Nit

- **N1 - Old /64 rows after the upgrade** (`net.rs:121`): `banned_ips` and `security_events.ip_address` rows written
  as `.../64` no longer match any key, so a running IPv6 ban and its 15-minute count are lifted at the upgrade. Both
  last an hour at most and nothing parses the key's format (no caller relies on `/64`; I grepped every `limit_key` and
  `Client::key` caller). The stale rows show on Blocked Addresses until they expire, and Unblock still works by row.
  A line in the release note is enough.
- **N2 - Device limiter and push receipts** (`limits.rs`, `device_api.rs:63`, `security.rs:1375`,
  `notifications.rs:1064`): only failures count, and a valid token never touches the bucket, so phones are unaffected
  by /56. The typed-enrollment block and `/notifications/receipt`'s 429 now lump a mobile carrier's shared /56. That
  only matters if someone else in that /56 fails against *this* server. Acceptable.
- **N3 - Crowd eviction's real cost, for the record** (`passkeys.rs:136`): a parent's challenge (in a crowd holding
  1) goes only when every crowd holds at most as many and it is the oldest. That means at least 500 live entries from
  at least 500 distinct /24s or /48s, re-issued from crowds not already in the pool within the parent's ceremony
  window. That is a residential-proxy-sized pool, reaching the admin listener (tailnet or `ADMIN_PUBLIC`).
  - **From inside the parent's neighbourhood:** with 3 per key, filling the parent's own /24 or /48 to 500 takes 167
    keys inside it. A CGNAT or ISP neighbour has one key, and a /56 customer in the same ISP /48 has one key, so this
    doesn't work.
  - **Weak spot:** if the parent's household itself holds 2 live sign-ins (the PWA and Safari) while the flood holds
    1 per crowd, the household is the biggest crowd and loses its oldest first. That still needs the 500-crowd flood.
  - **Can a passkey-only parent be kept out by eviction?** Only by such a flood, for as long as it lasts. Each retry
    gets a fresh challenge, and the shell's `reset-sign-in` is the last resort. Acceptable. The cheaper keep-out is
    the shared-address ban (L3), not the pool.
  - The loop is O(pool) under the mutex, one pass per issue (the pool is at most 500). Fine.
- **N4 - `signedOut`** (`passkey.js:124`): a 401, or a followed redirect whose final path is `/login` (with any query,
  `?next=` included). `csrf_guard`'s 403, a 500 page, or a redirect to `/auth/change-password` or `/auth/setup-2fa`
  is now an error shown in place, not a false "signed out". This matches what the server sends: every JSON route
  answers `signed_out_json()` (401), and `require_full_auth` redirects to `/login...`. Correct. Step 3's 423 will
  need its own branch.

---

## Checked and fine

- **0118** (`ALTER TABLE admin_users ADD COLUMN passkey_only INTEGER NOT NULL DEFAULT 0`): additive with a default,
  safe on a live database. Applying 0117 (CalDAV) after 0118 on a server that ran 0118 first is fine: sqlx applies any
  migration not yet applied, whatever its number.
- **Dummy hash** (`handlers/auth.rs`):
  - `login_hash` gives `None` for a passkey-only account, so `PasswordGate::verify` runs the same dummy argon2 as for
    an unknown name.
  - The failure branch is shared (`admin.filter(|a| valid && !a.passkey_only)`), and the answer is the same.
  - The only difference is the `detail`/`params` columns, bound into the same pre-argon2 `INSERT`, so there is no
    extra write after argon2 to time.
  - Nothing between the `SELECT` and the response branches on passkey-only before the shared failure path (the
    expired-invite and lock checks come after a *valid* password, which a passkey-only account never has). No timing
    or wording oracle.
- **`make_room_by_crowd`**: counts only `login` entries; on a tie the older challenge's crowd goes
  (`then(b.1.cmp(&a.1))`); it runs after the per-session and per-client caps; the signed-in pool is untouched (its
  `crowd` is stored, never used). The `crowd` for an unresolved client (`ip: None`) is its key, and the gate refuses
  such clients anyway.
- **The "aged-out row" delete** compares `created_at` (`YYYY-MM-DD HH:MM:SS`, `datetime('now')`) with
  `datetime('now', '-240 seconds')`, the same text form. It deletes only this session's stored id with
  `event_type = 'passkey_started'`, so it can't touch another row.

---

## Revision check - 92d4ccb8 (fix round; covers this review and qa-passkeys-2-flows.md)

Read only: `git diff c74b8597..92d4ccb8` (the main merge left out). No cargo.

**Verdict: mergeable. No High or Medium is left in either review.** All the findings below are fixed. What remains is
one Low (RL1: the banned slot can be hogged by someone on the same address) and two Nits.

### This review

- **L1 (reused challenge dying under the autofill): fixed.**
  - `live_sign_in` returns the time left, and `request_options` sends it as `timeout` (capped at 300 000).
  - `passkey.js` sets `endsAt = fetch + timeout` and refreshes at `min(fetch + 4.5 min, endsAt - 30 s)`; the
    `visibilitychange`/`pageshow` checks use the same `refreshAt`.
  - The button refetches when less than 60 s are left.
  - A reused challenge always has at least 60 s left (it is reused only under 4 minutes old). The refresh then
    lands after the 4-minute mark, so the server issues a fresh challenge. No dead window is left.
- **L2 (tailnet keys): fixed, and fine for the device API limiter.**
  - `limit_key` and `crowd_key` return the whole address for `is_tailnet` (100.64.0.0/10, `fd7a:115c:a1e0::/48`).
  - Each tailnet node is now its own ban key, its own device-limiter key (failures only; a valid token still never
    touches it) and its own crowd. Phones on the tailnet no longer share a failure bucket over IPv6.
  - A node can't rotate addresses without the tailnet admin adding nodes, so a whole-address key is safe there.
  - The server sees 100.64/10 only from the tailnet (or from a trusted proxy's XFF naming a tailnet client), never a
    carrier's CGNAT addresses.
- **L3 (a ban on a shared address): fixed. It is not an unbanned channel, and no other-account path opened.**
  - **Options for a banned key:** no row is counted (the key is banned already). The key gets a challenge only
    while it holds no live sign-in challenge (`live_sign_ins_of(key) >= 1` -> 429).
  - **`/auth/passkey` for a banned key:** the assertion is verified as usual.
    - A refusal is logged `passkey_refused` (counted, so the ban keeps getting extended) and answered 429.
    - Only a valid assertion signs in: the session-bound `login` challenge, the user handle naming a ready
      account, the credential that account's, the signature and the counter. A banned client gets nothing a
      non-banned one couldn't, and a passkey can't be guessed.
    - Getting as far as the signature check needs a real credential id and user handle, so the ban bypass gives
      no CPU amplification.
    - The new session is a fresh `cycle_id` one. No existing session or other account is involved. A ban never
      applied to signed-in pages, so nothing changes there.
  - **`/login` and `verify_2fa`:** still banned (unchanged).
  - **Row accounting can't be gamed:** a banned session's garbage POST pops one of its older open rows
    (`end_sign_in_attempt`) and adds one `passkey_refused`. That nets to zero, never below.
  - See RL1-RL3 for the residuals.
- **L4 (orphaned rows): fixed.**
  - `PRE_SIGN_IN_KEY` is now a list of at most 4 rows (an old single id is read too).
  - `note_started` drops the session's rows past 4 minutes, keeps the younger ones and appends the new one.
  - `end_sign_in_attempt` pops the newest. `sign_in_done` deletes them all after a right password (before TOTP,
    alongside the existing `take_back`) or a passkey sign-in.
  - A client churning sessions never signs in, so it still pays one row per challenge. Someone who knows the password
    can clear only their own session's at most 4 rows, which they made themselves: no gain.
- N1-N4 needed nothing.

### qa-passkeys-2-flows.md

- **M1 (passkey-only re-auth taking the password path): fixed.**
  - `reauth` and `reauth_and_hash` answer `Reauth::NeedsPasskey` when the actor (the session's own account, loaded
    fresh) is passkey-only and the proof isn't a passkey. Nothing is checked, counted, logged or written.
  - Every caller handles it: Add Passkey options, passkey removal, the switch, the switch-off page, Add Parent,
    Reset/Withdraw/Remove (`refused_words`) and Show Child PIN (`Reveal::NeedsPasskey`). The matches are exhaustive,
    with no wildcard that could read it as `Right`.
  - In `reauth_and_hash`'s callers only `(Right, Some(hash))` goes on.
  - **Probing:** the only one who can reach it is someone holding the passkey-only parent's session. They learn
    nothing (the Account page says passkey-only anyway), and no password exists to guess.
  - Losing the "3 wrong sign out everywhere" counter for that case loses nothing: a password re-auth can't succeed
    for a passkey-only account, so the counter never protected anything there.
- **The `data-passkey-only` JS:**
  - On a form whose re-auth button is `[data-passkey-reauth][data-passkey-only]`, submit (Enter included) is
    stopped while `assertion` is empty and starts the passkey ceremony.
  - On the Add form, `installAdd`'s listener calls `preventDefault` and steps aside, and `installReauth`'s runs the
    passkey and then `add()`.
  - No path posts an empty password any more. The server answers `NeedsPasskey` if a client still sends one.
- **L1 (last passkey for an address): fixed.** `Shown.last_for_host` (the only one for its RP ID while the account
  has others) adds "…then you can only sign in at <host>" to a passkey-only parent's removal confirm.
- **L2 (other addresses at switch-on): fixed** by always listing the parent's passkey hosts on the switch ("signing in
  works only at …"). Sessions don't keep their host, which the code notes.
- **L3 (terminal line): fixed.** `reset_2fa` reads `passkey_only` in its transaction and says "Passkey Only for X is
  turned off".
- Nits: N2 (`LoginView` loaded only when a page is rendered) and N3 (`PASSWORD_ON_SECRET_KEY` removed on the
  "switched off elsewhere" path) are fixed. N1 (the dash) wasn't checked; it is cosmetic.

### Residuals (all acceptable)

- **RL1 (Low) - someone on the same address can hog the banned slot.** "One live challenge per banned key" is per
  *key*, not per browser. Someone on the parent's address (home NAT, CGNAT, a shared mobile /56) can keep it banned
  and also hold the single slot: they ask again the moment their challenge ends, about one request per 5 minutes,
  polled.
  - The fix helps against a ban someone else caused and walked away from, not against an active co-located
    attacker. That attacker needs to be on the same address and keep it up; the parent can switch network, and the
    shell is the last resort.
  - If it matters later: a long-lived "a passkey signed in from this browser" cookie set at a passkey sign-in
    (`__Host-`, HttpOnly, random, stored hashed) would exempt that browser from the slot limit.
- **RN1 (Nit) - check-then-issue race.** `live_sign_ins_of` and `issue` take the lock separately. A burst from a
  banned key with no live challenge can get up to 3 (the per-client cap) instead of 1. It can't evict a challenge
  already live (the burst sees it and gets 429), and every refusal still counts. Do the check inside `issue_at` under
  the same lock if wanted.
- **RN2 (Nit, the class predates this change) - a banned client still writes per request.**
  `check_and_ban_ip_if_needed` re-upserts the ban and records `ip_banned` on every call while the window's count is
  15 or more. A banned `/auth/passkey` POST with an assertion that doesn't parse adds a `passkey_refused` without
  spending its challenge (the parse fails before the take). So it is about 3 rows per request instead of 2, bounded
  only by request rate. Record `ip_banned` only when a ban starts or renews from expired, and consider answering a
  banned key's unparsable assertion with a 429 without logging it.
