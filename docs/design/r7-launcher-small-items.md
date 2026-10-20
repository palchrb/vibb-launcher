# Launcher round 7 - small items (proposal)

Asked 2026-10-20. Each item is its own section with its own tests; they ship in one launcher round after 0.31.0.
§1 is written; §2 (catalog apps protected from force-stop, the battery exemption shown to the kid) and §3 (the
global wake-on-notification switch) follow in this file before the round's design QA.

## 1. Any allowed app may ring over the lock

The user, 2026-10-20: "det er ikke sånn at vi bare automatisk kan sense om en app har call funksjon med den call
style biten? Isåfall kan den få automatisk tilgang" and "La oss legge inn at en tillatt app kan vise calls skjerm over
lås". Builds on design 17/17b (`lock/VoipCallPlan.kt`, `lock/VoipCalls.kt`, `badges/VoipCallReader`), launcher only
plus one status field in both directories.

### 1.0 Today

The reading is generic: a ring is a notification of category `call` with a full-screen intent (or an incoming
CallStyle whose FSI Android dropped but that has an answer action), from an unsuspended package; the in-call phase is
the app's foreground-service notification of category `call` (or Element's channel) plus audio `IN_COMMUNICATION`.
Only the candidate set is fixed: `voipCandidates(effective)` (`VoipCallPlan.kt:47`) is the contacts' messaging apps
(`messagingAppPackages`: Element X, Signal/Molly, never the SMS app) while calls are managed and on,
`KNOWN_VOIP_PACKAGES` while unmanaged, nothing while calls are off or unknown. `VoipCalls.eligible` then drops
suspended packages (not allowlisted = suspended; a time rule suspends what it doesn't leave usable). A new app
(WhatsApp, Teams, a school's app) needs a launcher change today.

### 1.1 Decisions

1. **The candidate set is every allowed app.** `voipCandidates(effective, allowed, exclude)` keeps its gates - calls
   managed and on, or unmanaged; nothing while calls are off or `UnknownFailClosed` - and, when they pass, answers
   `allowed` minus `exclude` instead of a fixed list. `allowed` is the enforced policy's effective allowlist as
   `NotificationRuleRuntime.policy.allowed` already computes it in `apply()` (the allowlist plus the contacts'
   messaging apps plus a time lock's usable apps - the last matters not: a suspended app never rings anyway).
   `exclude` is the SMS app (today's rule: Google Messages posts call-category notifications for RCS video calls that
   our rules never saw) and our own package. **Fallbacks**: no enforced policy yet in this process
   (`NotificationRuleRuntime.policy == null`) or `allowlist == null` (apps unmanaged) -> today's sets (the messaging
   apps, or `KNOWN_VOIP_PACKAGES`), so a phone before its first apply and a calls-only phone behave as before.
2. **What the kid can do with it, accepted**: an allowed app that posts a call-style ring gets its own screen over
   the PIN lock for that ring (and, if it keeps a call-category foreground notification with audio in communication,
   for the call). The app is the parent's choice, the kiosk keeps every other app locked (the exemption pins that one
   package), and the 2-minute ring limit, the 15 s grace, the 3 h cap and "another call, the emergency flow and an
   alarm always win" stay. A kid with a "fake call" app gets into that app without the PIN - visible to the parent
   through §1.1.3.
3. **The parent sees which apps rang.** `lock_state.voip_rang`: `[{"package": "<pkg>", "last_at_ms": <wall ms>}]`,
   the packages whose ring the lock accepted (phase RINGING began) in the last 7 days, newest first, at most 8, valid
   package names only; always sent (empty list). Kept in `VoipCalls`' CE prefs `voip_call` (`rang_v1`, package ->
   wall ms, pruned at each write and each report; nothing but package names and times). Server: `kid_lock::LockState`
   gets `voip_rang` (sanitized like `voip_fsi_denied`: `valid_package_name`, capped `MAX_FSI_DENIED`, a non-numeric
   or future time dropped), the Lock Screen card shows an info note per package "Calls from <label> rang over the
   lock · <when>" (`LockNote::RangOverLock`, the app's catalog name or the package; a quiet line, not a warning).
   Contract: `StatusReportRequest.LockStateReport.voipRang` (`LockStateReport` is built in `PinLockRuntime` only) and
   the server's `step10` test, one commit for both directories.
4. **Unchanged**: the reader reads no text (`VoipCallGuardTest`), the in-call phase's rule (an app without a
   call-category foreground notification gets the lock back after the grace, its call going on in the background -
   a known limit, noted for the device checks), the kiosk-off lock-task list (the ringing package is pinned as
   today), `voip_fsi_denied`, the ring card and 17b's FSI-first send.

### 1.2 Tests (required)

`VoipCallPlanTest`: the new `voipCandidates` table - managed and on with an allowlist (every allowed app, the SMS
app and our package out), managed and off / unknown (none), unmanaged with an allowlist (the allowlist), the two
fallbacks (no policy yet, `allowlist == null`), and that `allowed` never widens the gates. `VoipCallGuardTest`
unchanged and still green (no new read of text). `VoipCalls`: `voip_rang`'s prune (7 days, 8 entries, newest
first), a ring of a non-candidate never recorded; the status field's encoding in `LockStateReport`. Server
`kid_lock`: `voip_rang` sanitized (package names, cap, times), the card's note; `step10`'s lock-state round trip
with the field. `PolicyResponseCompatTest` untouched (no policy change).

### 1.3 Device checks

Emulator (§6 of `docs/testing/emulator.md`, the Element X flow of design 17 as the regression): Element still rings
over the lock in both kiosk modes; with Element removed from the contacts' message apps but kept on the allowlist it
still rings (the new rule); with Element off the allowlist it doesn't. Jelly Star with Signal: a Signal call rings
over the lock and the device page shows "Calls from Signal rang over the lock · today 14:02".
