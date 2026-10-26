# synapse-guardian from the PWA: the child's Matrix allow list (2026-10-26)

Research only, nothing built. The user (2026-10-26): "hva skal til for å la denne synapse-modulen være styrbar fra PWA
istedet? Allow list for hvem barnet ditt kan kontakte via matrix." Read as code: `palchrb/synapse-guardian` at
`b52c64ca` (2026-09-25; read with grep/sed only, nothing run), and handy `main` at `a82ae749`.

## Summary

| Question | Answer |
|---|---|
| What the module is | Spam-checker callbacks gating invites (both ways), joins, room creation, aliases, publishing for a set of *protected* local users. One shared rule set: `blocked_users` > `allowed_users` > `blocked_servers` > `allowed_servers` > deny. |
| Where the list lives today | `homeserver.yaml` (static, restart) **plus** state events in a private control room (`guardian.allowed_user` etc.), re-read every 15 s, trusted by the room's power levels. The maubot `!guard` bot writes them. |
| The smallest PWA path | **(D): the Vibb server is one more Matrix client of that room.** A dedicated bot account writes the state events over the client-server API. No new endpoint anywhere, nothing exposed on the tailnet-only Vibb server, Synapse stores and replicates the list. |
| The one module change | Per-child lists. Today every allowed user may reach every protected child (`policy.py:334-355` takes no protected user). A new event type `guardian.contact` (kid + entity), which an older module ignores = fail closed. |
| Vibb server down | Nothing changes: the rules are in Synapse. Synapse unreachable from Vibb: the PWA says "not synced since …"; an added contact waits, a removed one stays allowed until the next sync (shown). |
| The PWA list | The existing contact rows: a contact with a Matrix ID on a phone is the allow entry, under a new per-row switch. New: the phone's own Matrix account, a Settings page for the homeserver/room/token, a status row. |
| Size | Module S (~120 lines + ~150 test), Vibb server M (~600 Rust + ~300 tests), PWA S (~180 template/CSS lines). |

## 1. What the module does today

- **Hooks** (`synapse_guardian/module.py:87-96`): `user_may_invite`, `federated_user_may_invite`,
  `user_may_send_3pid_invite` (always refused for a protected inviter), `user_may_join_room`, `user_may_publish_room`,
  `user_may_create_room` (no public preset/alias/open join rules, `:428-470`), `user_may_create_room_alias`,
  `user_may_send_state_event` (no join-rule other than `invite`, no canonical alias, `:485-509`). Opt-in third-party
  rules: `check_event_allowed` (knocks, `strict_local_events`) and `on_new_event` (instant reload,
  `watch_control_room`), both off by default for cost (`:97-121`). No `check_event_for_spam`, no
  `register_web_resource`, no account data, no own DB table, no HTTP endpoint.
- **Identity of the child**: exact MXIDs in `protected_users` (config or `guardian.protected_user` events), matched
  case-insensitively (`policy.py:330-332`). Only local users; a server admin is not protected (Synapse skips the
  checks for admins; logged at ERROR, `store.py:184`).
- **Decision** (`policy.py:334-355`): `evaluate(other)` - globbed users/servers, block beats allow on a tie, default
  deny; catch-all globs refused in allow lists (`policy.py:125-136`). It is symmetric (no in/out) and **the same for
  every protected user** (DESIGN.md:18-20 lists per-child overrides as a non-goal, README "Phase 2" as an idea).
- **What is gated**: an invite to a protected user from a disallowed sender, local or federated (`module.py:237-273`);
  an invite by a protected user to a disallowed user; an uninvited join unless the room is known locally and every
  joined/invited member is allowed (`known_rooms`, `:407-426`); an invited join re-checks **only the inviter**
  (`:306-328`; members are not checked, and the inviter lookup fails open if Synapse's private store call moves,
  `:330-356`). **Calls** are not seen as such: a Matrix call (Element Call / MatrixRTC state in a room) needs a shared
  room, so gating rooms gates calls. Strangers invited later into a room the child is already in are a documented gap
  (README "Known gaps").
- **Config** (`config.py:71-117`, README:63-87): unknown keys refuse start-up. `control_room` (room ID), the five
  static lists, `uninvited_joins` (`deny`|`known_rooms`), notices (`notify_room`, `notify_via: room|bot`,
  `notify_url` + `notify_secret` to the maubot), `refresh_interval_s` 15, `dry_run`.
- **Control room** (README:224-298, `store.py:91-165`): one state event per entry, `content.entity` = the MXID/glob,
  empty content = removed; honoured only from a **local** sender who is a server admin or holds the power to send that
  event type (`store.py:167-182`); invalid entries are skipped and logged. The module writes `guardian.effective_rules`
  (static + accepted room rules, `publish.py`) back into the room as `notify_user`, on the main process only.
- **Reload**: config = restart; room rules within `refresh_interval_s` per worker (instant with `watch_control_room`).
  An unreadable room keeps the previous rules, else static-only (`store.py:100-106`); an unexpected error in a hook
  denies if the user is protected statically or in the cache (`module.py:521-530`). Fail closed for protected users.
- Nit: DESIGN.md:122,254 still says `refresh_interval_s` 30 and `watch_control_room` default true; README/config say
  15 and false.

## 2. Integration shapes

**(A) The module pulls `GET /api/guardian/allowlist` from the Vibb server.** Flow: module HTTP client (bearer) →
Vibb → JSON → cache with TTL, last good list. Reachability is the problem: the Vibb server is tailnet-only (design 22;
the admin gate passes loopback/tailnet, `docs/notes/server/connectivity.md`), Synapse is public behind a proxy. Either
the Synapse host joins the tailnet (a tailscale node or sidecar next to Synapse, an ACL to one port) or Vibb grows a
public route - the one thing design 22 says no to. Failure: a restart while Vibb is down has no list unless the module
persists the last one (per worker, a file - workers in separate containers each need it); if protected users came from
the pull, a cold start would *unprotect* the child, so they must stay static. Module M (HTTP, persistence, TTL, a new
token), Vibb S, infra change on the Synapse host. **Worse than (D).**

**(B) Vibb pushes to a module web resource** (`register_web_resource`, `/_synapse/client/guardian/allowlist`). The
resource lands on Synapse's public client listener: a new internet-facing write endpoint, guarded by a shared secret
(HMAC + timestamp, rate limit). The POST reaches one process: with workers the others never see it unless the module
stores it somewhere shared (its own table via private datastore APIs, or a file on shared storage) - re-inventing what
the control room already is (persistent, replicated, audited). Synapse down: Vibb retries; Vibb down: last list kept.
Module M-L, Vibb S. **Worse than (D).**

**(C) Account data.** On the *child's* account it is fatal: the child's own Element X can `PUT
/user/{me}/account_data/…` and rewrite their allow list, and no module hook vetoes an account-data write
(`on_account_data_updated` only runs after it). Writing it needs a token *as the child* (admin login-as-user), which
gives the Vibb server the child's rooms - against "no kid content reaches the Vibb server". On the *bot's* own account
it works (the module reads it with `account_data_manager.get_global`), but loses the power-level trust model, the
history, `!guard list` and the effective-rules read-back, and still needs module code (read, cache). **Rejected.**

**(D) The control room, written by a Vibb bot account - recommended.** The module already reads rules from any local
sender with the power to send that type (`store.py:167-182`), and the `!guard` bot is just one such client. The Vibb
server becomes another:
- A local Synapse user `@vibb-guardian:<hs>` (not a server admin, not protected), joined to the control room only,
  power **50**, with the room's `events` map giving `guardian.contact` (and nothing else) level 50 and `state_default`
  100. The human parent stays 100. So a stolen Vibb token can add or remove a child's contacts but cannot unprotect a
  child, allow a server, or touch the static baseline.
- Vibb → `https://<hs>` outbound over the public client API, the way it already calls GitHub/Spotify/Storytel
  (`reqwest`). Nothing new is exposed on either side; the tailnet stance is untouched.
- Synapse stores, replicates (workers within `refresh_interval_s`) and keeps the list across restarts; the room's
  timeline is the audit trail ("@vibb-guardian set guardian.contact … 14:02").
- Read-back: Vibb reads `guardian.effective_rules` (README:270-290) to show what the module actually *enforces* (is
  this phone's account in `protected_users`? are all its contacts loaded?), not just what Vibb wrote.
- Without the module change it already works for a one-child household with plain `guardian.allowed_user` events - but
  shared by every protected child, so siblings would share friends. Hence the one module change below.

### The contract

State event `guardian.contact`, state key = the first 32 hex of `sha256("<kid mxid>\n<entity>")` (two MXIDs can pass
the 255-byte state-key limit; the module reads `content`, never the key), content:

```json
{"protected": "@ola:family.example", "entity": "@mormor:matrix.org",
 "source": "vibb", "added_by": "@vibb-guardian:family.example", "ts": 1793001600000}
```

Empty content = removed (as the existing types). `entity` is an exact MXID (`is_user_id`, no glob - Vibb never writes
a pattern, and the module refuses a glob in this type). Module semantics: `evaluate(other, for_user)` = the existing
order, with `guardian.contact` entries for `for_user` checked at step 2 (an allow that a `blocked_user` still beats).
Every call site already knows the protected party: the invitee (`:248`, `:262`), the inviter (`:266`), the joiner
(`:292`, the members loop `:418-425`). An older module ignores the unknown type: the child cannot reach the new
contact - nothing opens. Flags, deliberately none in v1: in/out and "may call" cannot be enforced separately by room
gating (one shared room carries both chat and calls), and "rooms with only allowed members" is already
`uninvited_joins: known_rooms`; closing the invited-join gap (check members too, `:306-328`) is a separate XS module
change worth a config flag (`invited_joins: inviter|members`).

The sync (Vibb side): **desired** = for each phone with an `own_matrix_id`, every `device_contacts` row with a valid
`message_address` and `matrix_allowed = 1`. **Present** = `GET /_matrix/client/v3/rooms/{room}/state`, the
`guardian.contact` events with `source: "vibb"` (others are the parent's own, never touched). Diff → `PUT
/rooms/{room}/state/guardian.contact/{key}` for each missing, `{}` for each extra. Triggered by every contact/phone
write (the same place that calls `command_notify`), debounced 2 s, and every 15 min as a repair; an hourly rate cap.
Ratelimits: few writes per change; the admin can exempt the bot (`/_synapse/admin/v1/users/<id>/override_ratelimit`).

Shared fixture as the repo's other contracts: `server/testdata/guardian_contacts.json` (`{phones, contacts, events,
decisions: [{protected, other, allowed}]}`), copied byte-identically into the module's `guardian_tests/fixtures/`.
Vibb's test builds `events` from `phones`/`contacts`; the module's test loads `events` into a control room and checks
every `decisions` row. Two repos, so no test can compare the copies: each side pins the file's SHA-256 in a constant
and fails on drift, and the note says a change goes into both.

## 3. The PWA side

- **Schema** (`migrations/0022_calls.sql`; `models.rs:316` `DeviceContactRow`): `contacts(name, phone_number NOT NULL
  UNIQUE)`, per phone `device_contacts(allow_inbound, allow_outbound, show_on_home, message_app, message_address)`;
  `message_address` is the Matrix ID ("Matrix ID (Element only)", `device_calls.html:104`, `valid_matrix_id`,
  `calls.rs:1068`), per phone, not per contact. Nothing names the phone's own account (design 15's `own_matrix_id` was
  dropped). The allow list comes from these rows - no second list.
- **Add** (one migration): `device_policy.own_matrix_id TEXT` ("This phone's Matrix account", must be on the guarded
  homeserver, unique across phones); `device_contacts.matrix_allowed INTEGER NOT NULL DEFAULT 1` ("Can chat and call in
  Element", shown under the Matrix ID only when one is set - not `allow_inbound`/`allow_outbound`, which are phone-call
  switches a parent may set differently); a `guardian_settings` singleton (homeserver URL, control room ID, the bot's
  token sealed like the Storytel login with `music_secret.rs`, `last_ok_at`, `last_error`, `written`, `enforced`).
- **A Matrix-only contact is impossible today**: `phone_number` is `NOT NULL UNIQUE` and the contact's identity. A
  school friend's parent with only a Matrix account needs a contact without a number (a nullable number, unique when
  set, the launcher's phone book skipping it) - a second, separate step; open question 2.
- **Pages**: Settings › Phone Setup gets a "Matrix guardian" row → a page with the three fields, "Test" (whoami, the
  room's power levels: can the bot send `guardian.contact`, is it not admin) and the status line ("Synced 2 min ago ·
  9 contacts · enforced" or "Not synced since 14:02 - can't reach matrix.family.example"). A phone's Calls & Contacts
  page: the own-account field and one status row ("Guardian: 5 allowed · enforced", Attention when the account is not
  in the module's `protected_users`). Saves stay in place (no jump to top).

## 4. Security and privacy

- **The list is sensitive** (who a child talks to). It already lives as unencrypted state in the control room, readable
  by its members and the Synapse admin - who can see room memberships anyway. Vibb adds one more holder; its copy is
  the contact rows it already has. The bot token is a secret at rest (sealed), never logged, never sent to a phone.
- **Write path, both ways authenticated**: Vibb → Synapse over TLS with the bot's token; Synapse decides by power level
  (Synapse refuses the PUT, the module re-checks the sender, `store.py:167-182`). Nothing calls into Vibb.
- **Least privilege**: the bot is power 50 on one type; protected users and servers stay with the human parent; the
  bot is not an admin (an admin is honoured whatever the room says) and joins nothing else (no autojoin).
- **Fail closed**: an unlisted MXID is denied by default; a child with no entries reaches only the static baseline
  (`allowed_servers: [own server]` if the family set it - the README's "local users" gap: keep registration closed);
  Vibb down changes nothing; an old module ignores the new type. The one fail-open direction is **removal lag** while
  Synapse is unreachable - shown on the status line.
- **No message content**: the bot reads only the control room's state (and, later, its `m.notice` blocks); it is in no
  child's room and has no child token.

## 5. Steps

| # | Step | Size | Tests / checks |
|---|---|---|---|
| 1 | Design note (`docs/design/NN-matrix-guardian.md`): this contract, the bot's power levels, the fixture | XS | QA of the design |
| 2 | Module PR on `palchrb/synapse-guardian`: `guardian.contact` (parse, exact MXID only, `evaluate(other, for_user)`, all call sites, `effective_rules` payload, `!guard list` shows them); optional `invited_joins: members` | S | `test_policy` (per-child decisions, block beats contact), `test_store` (bad/glob/empty entries, a low-power sender ignored), `test_module` HomeserverTestCase (a listed MXID's invite accepted for kid A, refused for kid B; unlisted refused; old type unaffected), the fixture's `decisions` |
| 3 | Vibb server: migration, `guardian.rs` (client, diff, sync task + nudge, status), settings handler, Calls-page fields | M | wiremock-style fake Synapse in `src/tests/guardian.rs`: the diff writes only missing/extra `source: vibb` keys, never a parent's; a 403/timeout keeps `last_error` and retries; token sealed; the fixture's `events` built from its `phones`/`contacts` |
| 4 | PWA: Matrix guardian page, own-account field, `matrix_allowed` switch, status rows | S | template tests (no jump, refusal keeps input), status wording |
| 5 | Device/emulator checks on a test Synapse with the module | S | an invite from an unlisted MXID refused; a listed one accepted (Element X on the emulator); a contact switched off → the next invite refused within ~20 s; Vibb stopped → still enforced, a restarted Synapse still enforces; sibling B can't be invited by A's friend; a call from a listed contact rings |
| later | Matrix-only contacts (nullable number); block notices into the PWA inbox (the bot reads the room's notices) | S each | |

## 6. Verdict

1. Don't add an endpoint to either server: the module already has a remote-writable, replicated, audited store - the
   control room - and the Vibb server can write it as an ordinary Matrix client over the public API.
2. One module change is needed for per-child lists (`guardian.contact`, S); it fails closed on an older module.
3. The list is the family address book's Element contacts per phone plus one switch, not a second list.
4. Vibb down or unreachable never widens access; the only lag is a removal while Synapse can't be reached, shown.
5. Total: module S, Vibb server M, PWA S - one round after the design note.

**Open questions (defaults):**
1. Per-child from the start, or one shared list first with plain `guardian.allowed_user` (no module change)? Default:
   per-child (siblings would otherwise share friends).
2. Matrix-only contacts (no phone number) in the same round? Default: no, the next step.
3. Close the invited-join gap (members checked, not just the inviter) in the same module PR? Default: yes, behind
   `invited_joins: members`, off until tried with `dry_run`.
