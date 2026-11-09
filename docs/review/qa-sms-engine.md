# Code QA (security): design 77 step 1, the SMS engine (`sms-engine`)

Branch `sms-engine` (5391b54c, 9d83d996, a818551c on main 92cb9a34), read in full against design 77 revision 3 and
`qa-77-design.md`. No code changed by this QA.

## Verdict

- **Fix M1, then merge into an integration branch, not into `main` (M2).** The engine itself is sound: the sender type
  is read from the raw PDU, no sender gets in for being an emergency number, the hold never evicts, drops run before
  expiry and expiry before the release, the release matches the exact sender, and nothing of a held body leaves the
  phone (post, report, logs, notifications, backup checked). Every component beyond the role's four is private, and
  each of the four is guarded by its system permission. The old wire keys never loosen a channel.
- 0 Critical, 0 High, 2 Medium, 8 Low, 6 Nits.
- Tests run here: launcher `sms.*`, `calls.*`, `PolicyGateTest`, `EnforcementPlanTest`, `PolicyResponseCompatTest`
  394/0 (`-PwarningsAsErrors=true`); server `cargo test -- sms calls policy migrat` 102/0; `cargo fmt --check` clean.
- No failing test committed: neither Medium is reachable from a JVM test without changing production code (M1 lives in
  `AppEnforcer`'s glue, M2 is a release question). No finding is an exploit an outside sender can drive.

## Findings

### Medium

- **M1. With the rules unknown, the other apps' SMS denials are lifted while the engine keeps receiving**
  (`server/AppEnforcer.kt:457`, `:1075`). `applySmsPermissions(..., smsMode == ChannelMode.MANAGED, ...)` treats
  `smsMode == null` (fail closed) as "not managed" and sets every target, Play services included, back to
  `PERMISSION_GRANT_STATE_DEFAULT`. In that same state D15 keeps the role ours and `DISALLOW_SMS` off
  (`EnforcementPlan.kt:301`), so every message still reaches the platform's `SMS_RECEIVED` fan-out. The policy fix is
  gone, so an allowed app may ask for `RECEIVE_SMS` again and the kid can grant it; a system app's pre-grant may come
  back. The held stranger's text then reaches that app. D15 says the opposite in so many words: "The other apps'
  permission denials and the SMS-app suspension still hold." The trigger is narrow (the CE cache and `last_call_rules`
  both unreadable, or an `apply()` before the first unlock, L2), but this is the fail-closed path the design promises.
  Fix: deny unless SMS is "Ikke styrt" or "Av" - pass `smsMode == ChannelMode.MANAGED || smsMode == null`. In "Av",
  `DISALLOW_SMS` already stops everything. Add the pure decision to `SmsRole.kt` (`smsPermissionsDenied(mode)`) with a
  test row for `null`.

- **M2. Merged into `main`, the next `server-v` tag breaks every new phone until a launcher with the engine is the
  stable release** (`migrations/0130_sms.sql:11`, `handlers/calls.rs` `channel_row`/`save_settings`, no capability
  gate). The server is tagged from `main` often, and the provisioning QR installs `releases/latest`, which has no
  `sms_engine_v1` until steps 1-4 ship. A phone created then gets calls "Ikke styrt" and SMS "Styrt av Vibb". Its
  launcher reads the derived keys `managed: true, calls_enabled: true, sms_enabled: false`. So:
  - calls are contacts-only, with the new phone's empty phone book: nothing but emergency calls in or out;
  - `DISALLOW_SMS` is set, and every SMS is lost;
  - the PWA says calls are "Not managed" and SMS "Managed by Vibb".
  Nothing in the PWA explains it: "Oppdater Vibb One først" and "Telefonen må oppdateres" are step 4's. The same goes
  for an existing phone whose parent picks "Managed by Vibb" in the ungated picker. This fails closed, so no security
  is lost, but it is a functional break. §9 already says steps 1-4 ship together. Fix: merge step 1 into an
  integration branch (`sms`) and merge steps 1-4 into `main` together, as `e7-integration` was. If step 1 must reach
  `main` first, add a PLAN line that no `server-v` tag carries 0130 until a stable `launcher-v` has `sms_engine_v1`,
  and bring the server half of step 4's capability gate forward.

### Low

- **L1. The notification's Reply is routed by extras that any holder of its PendingIntent can rewrite**
  (`sms/SmsNotifier.kt:138-142`, `SmsReplyReceiver`). The PendingIntent is `FLAG_MUTABLE` (needed for `RemoteInput`).
  Its intent has no action and no data, so two conversations are told apart only by `conversationId.hashCode()`.
  - Fill-in merges extras over ours. So a notification listener (a watch or car companion the parent allowed) can
    send `EXTRA_CONVERSATION` = any conversation and a fresh `EXTRA_CLIENT_ID` each time. That texts any `sms_out`
    contact as the kid, past `setAuthenticationRequired`. The gate still holds.
  - Two conversations whose ids share a `hashCode` share one PendingIntent, and `FLAG_UPDATE_CURRENT` rewrites the
    other's extras. A reply then goes to the wrong person, FM #595's lesson (rare with ULIDs, but free to rule out).
  Fix: carry both ids in the data URI (`vibb-sms://reply/<conversation>/<clientId>`, as `SmsSentReceiver` does) and
  read them from `intent.data`. Fill-in can't change a set data URI.
- **L2. The DE boot copy reads SMS as "Av"** (`calls/BootCallPolicy.kt:135`, `calls/CallRules.kt:80`).
  `bootPolicyState` builds `CallRules(smsEnabled = false, ...)` without `smsMode`, so the default makes it `OFF`.
  An `AppEnforcer.apply()` before the first unlock (rare, e.g. `BootHome.callMayHaveEnded`'s deferred camera apply)
  then has three effects on an SMS-managed phone:
  - it sets `DISALLOW_SMS`, and the messages the platform holds for the unlock are dropped if the platform dispatches
    them before the unlocked apply clears it;
  - it resets the other apps' SMS denials (M1);
  - it keeps the role, which is right.
  Fix: make the boot state's SMS channel unknown (`CallPolicyState.smsMode` returns `null` for BOOT-sourced rules, or
  add `sms_mode` to the still unreleased boot copy v2). The engine never runs before the unlock anyway.
- **L3. In "Ikke styrt" with the role still ours, rule 4 blocks every message** (`sms/engine/SmsRules.kt:80`,
  `server/AppEnforcer.kt:1003-1005`). The role goes back only after the package loop, and not at all when no SMS app is
  installed or `mdm.sms.role_taken_by_us` was lost in a preferences reset. Until then each SMS is blocked: a `blocked`
  row, no body, lost. The parent chose "Alle kan sende SMS", and `DISALLOW_SMS` is off. D4 calls rule 4 "a guard that
  can't happen", but it can. Fix (the architect's call): in `UNMANAGED` with the role ours, keep and show the message
  unfiltered, and let step 4's findings say "Vibb One is still the SMS app".
- **L4. `pending_push` grows without a bound and is never pruned in step 1** (`sms/SmsReceivers.kt:89-103`,
  `SmsRuntime.kt:75-78`).
  - Every MMS notification, from anyone, is a row of up to 2 KB. The row holds the stranger's `From` and subject,
    kept until step 3b exists.
  - An oversize push is only logged. D7 says it is counted as blocked `malformed`.
  Fix: cap the table (100 rows) and drop rows older than 7 days at `syncTick`. Count an oversize push, or one past the
  cap, in `blocked.malformed`.
- **L5. A one-off flood blinds the parent's waiting list for 30 days** (`server/src/sms.rs:327-346`,
  `SmsRuntime.postHeld`). `HELD_ROWS_A_PHONE` (500) counts every row of the last 30 days, `blocked` and final ones
  included. The phone posts one `blocked`/`over_cap` row per message (up to 500 kept), so about 500 texts from one
  stranger fill the server's bound. From then on every later held row answers `capped`: the parent never hears of it,
  and it expires unseen. The kid stays safe (fail closed). Fix: give held rows their own bound, or keep `blocked`
  rows out of it (their counts are in `sms_state.blocked`), or have the phone post at most N `blocked` rows an hour
  and only count the rest.
- **L6. A re-delivered SMS is stored twice** (`SmsEngine.receiveSms`). A telephony-process restart between our commit
  and its raw-table delete re-dispatches the row at its next start. Nothing de-duplicates it: the kid sees the message
  twice, or two held rows. Fix: a unique key `(sender, SMSC timestamp, body SHA-256)` checked in the receive
  transaction, kept for a day.
- **L7. D7's 3 s budget is neither measured nor bounded** (`SmsRuntime.receive`). The receive waits on the engine
  thread's write transactions. Most are short, but `markRead` walks a whole conversation in one transaction and the
  `TRUNCATE` checkpoint waits for readers. It also waits on `CallPolicyStore.refresh`'s monitor during a sync. Fix:
  time each receive, count those over 3 s in `sms_state` (and `last_error`), and page `markRead`.
- **L8. "Ikke styrt" doesn't give back what "Styrt av Vibb" denied** (`AppEnforcer.applySmsPermissions`).
  `DEFAULT` after `DENIED` only clears the policy fix; the grant stays revoked. Play services' phone-number checks and
  SMS Retriever, and any SMS app the parent allows, stay broken after a managed period, though the sub-line promises
  "every messaging app works". Fix: in step 0, check what `DEFAULT` leaves. If the grant stays revoked, remember which
  pre-granted permissions we denied, and on leaving "Styrt av Vibb" set `GRANTED` then `DEFAULT` for those.

### Nits

- **N1.** `templates/device_calls.html:81`: "the handler writes every switch, a missing one is off" - a missing
  channel mode is now a 400 (`error.unknown_channel_mode`).
- **N2.** `docs/testing/emulator.md` §6v has no scripted double-send with one `clientId` (QA L8's emulator step).
  The notification's double tap is the only front end so far. Add it with step 2's compose bar.
- **N3.** `sms_rules.json`, `SmsEngineTest`, emulator.md: `+4741414141` (new on this branch) is a plausible real
  Norwegian mobile number. Use the repo's fake series (`+479000xxxx`).
- **N4.** `SqliteMessageStore.Helper.onConfigure` sets `secure_delete` through `rawQuery` and never reads it back.
  Android's SQLite is built with `SQLITE_SECURE_DELETE`, so it holds today. A read-back (`PRAGMA secure_delete` = 1),
  logged into `last_error` when it isn't, would make R2-L3 a checked fact.
- **N5.** `SmsRuntime.mmsDataAllowed` reports mobile data on (`isDataEnabled`), not D14's MMS APN. It is documented,
  but the field's name says more than it measures. Step 3b should revisit it (deviation 4 below).
- **N6.** `docs/notes/server/calls-and-contacts.md`: say that a new phone is "managed" from its first sync (hardening,
  the update fence's night window, the backup lock and the red PIN card all read `managed()`/`state.managed`), not
  only that its summary changes (deviation 2 below).

## The builder's deviations

1. **Two Off/Managed/Not-managed rows, a dropdown, no capability gate.** *Acceptable in code, not for release on its
   own.*
   - Nothing is less restricted. The migration maps all six combinations exactly (`the_migration_keeps_every_phone_as_it_was`).
   - A new launcher reads the same behaviour from the modes.
   - An older launcher gets `derived_old_keys`, which never opens a channel. Each of the nine pairs is checked against
     the old launcher's reading in `SmsContractTest`'s `modes` rows.
   - Without the gate, an old launcher turns SMS "Managed" into lost SMS, and calls "Not managed" with SMS restricted
     into contacts-only calls. It fails closed, but silently. See M2.
2. **New phones get SMS managed.** *As designed (D2).* The consequence is wider than the summary row: `managed()`
   makes the phone "managed" from its first sync. That means hardening, the update fence, the backup lock, the PIN
   card and the contact count. This is right for a phone that filters SMS, but needs a line in the notes (N6) and is
   the root of M2.
3. **`sms_held_drop` lists the rows the parent deleted in the last 24 h whose state is still held or dropped.**
   *Acceptable.* A released or expired row needs no drop, and the phone ignores ids that aren't `HELD`. It stays
   within 24 h and 100 rows, newest first.
4. **`mms_data_allowed` reports whether mobile data is on.** *Acceptable for step 1.* `isDataEnabledForApn(TYPE_MMS)`
   is a system API. The error is only a false "pictures need mobile data" while the per-SIM "MMS-meldinger" switch is
   on. Revisit in 3b (N5).
5. **Boot copy v2 keeps `calls_enabled` and checks it agrees with `calls_mode`.** *Good.* A disagreeing copy is
   corrupt, and so fails closed. `bootCopyFaithful` compares the mode too. The copy still lacks the SMS channel (L2).
6. **Blocked rows keep the cleaned sender.** *Better than the design's raw text cut to 20.* What the phone keeps is
   exactly what the intake takes (`sender_ok` on every `clean` row), and a raw sender's controls never reach the
   database.
7. **Interim placeholders** (the tap opens Home, `ComposeActivity` only toasts or closes, the Message button only
   while SMS is unmanaged). *Acceptable.* None of them sends. `ComposeActivity` asks the same gate, and the
   `SENDTO`/`smsto:` path can't send at all until step 2. In "Styrt av Vibb" the kid has no way to start a text in
   step 1, which is fine since steps 1-4 ship together (M2).
8. **`DENY_PLAY_SERVICES_SMS` on.** *As designed (§8.6, "until then this rule stands").* It must be confirmed by step
   0 on the Jelly Star before the release: phone-number verification, Find My Device, Play's checks and SMS Retriever
   OTPs in allowed apps. L8 matters if it ever goes off again.

## Checked and found right

- **Receive gate.**
  - `SmsSenderType` reads TON 101 from every part's raw TP-OA, and `ofParts` requires one kind and one address across
    all parts. `3gpp2`, a short PDU, a non-DELIVER MTI and a missing `format` are `MALFORMED`, which blocks.
  - No allow path for an emergency number: phone `allowedBy` + `isEmergency`, server
    `sms_in && !emergency::is_emergency`.
  - The callback window is the call path's, platform-confirmed, connected, one hour.
  - D17: a letter is required on both sides; one fold (NFC + `to_lowercase` / `lowercase(Locale.ROOT)`), with
    `ẞ`/`ß`/`ΣΩ` on the shared vectors.
  - Unicode digits, `*`/`#`, fullwidth `+` and letters in a BCD address don't normalise and are blocked. `0047` and
    `+47` meet in one E.164 form. A short code is held unless it is a contact with `sms_in`.
- **Hold.**
  - Pools 30/20 and per-sender caps 10/3. Only `HELD` rows count, and an over-cap message becomes a `blocked` row.
  - Drops, then expiry, then release, also at process start.
  - Expiry nulls the body, then `secure_delete` and a `TRUNCATE` checkpoint run.
  - `HELD_RULES` is capped at 200 and re-judged at every refresh.
- **Fail closed.**
  - `judgeFresh` rejects a `call_policy` without the modes once they were seen (`channel_modes_seen` survives a
    preferences reset), and an unknown mode always.
  - `managed` derived true whenever either channel is restricted (R2-L7).
  - A v1 boot copy reads as unknown.
  - `DISALLOW_SMS` is set in "Av", and in "Managed" or unknown rules without the role.
  - The other SMS apps are suspended whenever SMS isn't "Ikke styrt".
  - "Ikke styrt" in the PWA lifts everything without adb.
- **Receivers.**
  - Their own single-thread executor, commit before `finish()`, every `Throwable` caught.
  - `SMS_DELIVER`/`BROADCAST_SMS`, `WAP_PUSH_DELIVER`/`BROADCAST_WAP_PUSH`, `RESPOND_VIA_MESSAGE`/
    `SEND_RESPOND_VIA_MESSAGE`; `ComposeActivity` exported and inert; the result and reply receivers not exported
    (`SmsRoleTest` pins the manifest).
  - The sent and delivered PendingIntents are explicit and mutable, which the platform's fill-in needs, and each is
    distinct by its data URI.
- **Send.**
  - `insertOutgoing` decides the `clientId`, and a different body is refused.
  - `SENDING` is committed before the radio. `UNCERTAIN` comes after 10 minutes and is never re-sent; a late result
    still applies.
  - The gate runs again at drain, for every origin: reply, respond-via-message, the engine API.
  - Short codes only as `sms_out` contacts. 1,600 characters, multipart, never MMS.
- **Server.**
  - The intake checks the id, kind, class and the sender by class (no control, bidi or newline; HTML is the page's
    escaping), the times against the server clock, forward-only states, 50 rows, 16 KiB, 500 a phone with
    `PhoneLine::SmsHeldCapped`.
  - `sms_state` keeps known keys only. Retention is 30 days after `at`.
  - The contract is byte-identical and compared on both sides. The CI path lists and the root CLAUDE.md are updated.
  - `policy_json_keys_snapshot` and `PolicyResponseCompatTest` gained the keys.
- **Quality.** Lean JVM tests over a fake with the store's dedup contract. en + nb on both sides. Fixtures use
  `.invalid` hosts. The style matches.

## Fix round

On `sms-engine` after merging this review (the coordinator's decisions: M2 by a gate, not an integration branch;
PLAN.md stays main's). Launcher `assembleDebug assembleRelease testDebugUnitTest -PwarningsAsErrors=true` 2159/0;
server `cargo fmt --check` clean, `cargo clippy --all-targets` no warnings, `cargo test` 1788/0. The contract
`sms_contract.json` is unchanged. Emulator check re-run (the receive path changed: L3, L6, L7): an allowed SMS shown, a
stranger held and posted, the notification's reply sent and delivered (`docs/testing/emulator.md` §6v).

- **M1** - `smsPermissionsDenied(mode)` (`sms/SmsRole.kt`): denied in "Styrt av Vibb" and with the rules unknown
  (`null`), as D15 says; `AppEnforcer` passes it. `SmsRoleTest` has the `null` row; `BootCallPolicyTest` shows a v2
  boot copy reads as unknown and keeps the denials.
- **M2** - a gate, with the old behaviour for new phones (server):
  - `0130_sms.sql` (not pushed before this round) now defaults `sms_mode` to `unmanaged`: a new phone starts with
    both channels "Ikke styrt", exactly as before 0130, so a launcher from `releases/latest` works unchanged
    (`a_new_phone_reads_as_before_0130`: `managed: false`, `calls_enabled`/`sms_enabled` true, no contacts, calls
    unmanaged).
  - Without `sms_engine_v1` in the phone's report only the pairs an older launcher can express are offered
    (`sms::older_launcher_expresses`): the others show disabled with "Oppdater Vibb One først" / "Update Vibb One
    first", and the save refuses them in place (400, no jump; the stored pair itself still saves, so another field's
    save never fails and nothing loosens).
  - A stored pair the phone can't express keeps the fail-closed derived keys, and the calls page says "Telefonen må
    oppdateres: SMS går tapt og samtaler er bare til kontakter til da" (or the half that applies).
  - With the capability, SMS's managed choice reads "Styrt av Vibb (anbefalt)" / "Managed by Vibb (recommended)". No
    phone is switched automatically.
  - Notes (N6 too): `docs/notes/server/calls-and-contacts.md` says a phone with either channel not "Ikke styrt" is
    "managed" from its first sync (hardening, the update fence's night window, the backup lock, the red PIN card), and
    why new phones start with both "Ikke styrt".
- **L1** - the Reply's PendingIntent is explicit, action `REPLY_ACTION`, both ids in its data URI
  (`vibb-sms://reply/<conversation>/<clientId>`, `replyData`/`replyIds`), `FLAG_MUTABLE` only there (RemoteInput);
  the receiver reads only the data URI. The open intent is told apart by `setIdentifier(conversation)`. No request
  code comes from a `hashCode`. `SmsRoleTest` checks the parse.
- **L2** - the DE boot copy is version 3 with `sms_mode` (validated, compared by `bootCopyFaithful`); a v1 or v2 copy
  reads as unknown, so SMS fails closed through M1's rule.
- **L3** - `SmsRules`: SMS "Ikke styrt" while the role is still ours keeps and shows every message, unfiltered (a
  sender without a usable address gets its own `unknown:` conversation). The hand-back is retried at every apply, and
  `role_taken_by_us`, the previous holder and L8's record survive a preferences reset (`SmsPrefs.KEPT_ON_RESET`).
  Vectors in `sms_rules.json`, a test in `SmsEngineTest`.
- **L4** - `SmsEngine.keepPush`: at most 100 pushes of 2 KB, pruned after 7 days at every sync; an oversize push or
  one past the cap is counted as blocked `malformed`. Tested.
- **L5** - the server bounds held rows and blocked rows separately, 500 each a phone in 30 days; a blocked flood can't
  hide a later held message (`blocked_rows_have_their_own_bound`).
- **L6** - `received_key`: a SHA-256 of the sender as received, the SMSC time and the body, checked and stored in the
  receive transaction, kept a day; a re-delivery is `Received.Duplicate` (not counted, not notified). Tested.
- **L7** - every receive is timed from the broadcast's arrival; one over `RECEIVE_BUDGET_MS` (3 s) goes to
  `last_error` with its time only and is counted (`slow_receives`). `markRead` is one `UPDATE` (no walk inside a
  transaction the receive path may wait for); every other engine-thread transaction is one statement or one row.
- **L8** - `smsPermissionPass`: a denial records the permissions that were granted before; leaving it writes `GRANTED`
  then `DEFAULT` for those (the grant back, the policy fix gone), `DEFAULT` for the rest
  (`mdm.sms.denied_permissions`). Step 0 still checks what `DEFAULT` leaves on the Jelly Star. Tested.
- **N1** - the template comment says a missing or unknown channel mode is a 400.
- **N2** - deferred to step 2: the scripted double-send with one `clientId` comes with the compose bar.
- **N3** - `+4741414141` and its neighbours became the fake series `+47900000xx`/`+47900001xx` in `sms_rules.json`,
  `SmsEngineTest` and emulator.md.
- **N4** - `SqliteMessageStore` reads `secure_delete` back after setting it; `SmsRuntime` puts "store: secure_delete
  is off" in `last_error` when it isn't.
- **N5** - deferred to step 3b (D14's MMS APN), as the comment on `mmsDataAllowed` says.
- **N6** - with M2, above.
