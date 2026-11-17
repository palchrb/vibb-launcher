# Code QA: design 108b, the launcher part (R23-L: liveness, the refresh, the raised NEW_ENDPOINT, the key update)

Branch `up-r2r3`. Reviewed: c2529f31d on top of the contract commit 290321c2b
(`git diff 290321c2b..c2529f31d -- launcher/ docs/`, 21 files). Read against `docs/design/108b-unified-push-r2-r3.md`
(revision 2 and "Decided 2026-11-17": Q1 with the boot re-send and the two-part dead rule, Q2 (a), Q3 (c)+(d)),
`docs/review/qa-108b-design.md`, `docs/notes/launcher/unified-push.md`, `launcher/CLAUDE.md`'s core and the R1 code it
builds on (`UpEngine`, `UpRules`, `UpRuntime`, `UpStore`, `vendor/RaiseToForeground.kt`, `AppEnforcer`'s suspend/hide
pass, `MdmSyncWorker`'s `policyAccepted` call and its 304 path).

This is a reading review. Checked: `cmp` of the two `unified_push.json` copies (identical); no file outside `launcher/`
and `docs/` changed. Not run: Gradle (CI green, run 44052735396) and anything on an emulator (R23-G is owed).

**Verdict: no High, 1 Medium, 9 Low.** I found no path that removes a live app's registration because of a reboot, a
clock jump, a time rule or a stopped app. The remaining path is a single lost send or ack in a boot (M1). The raised
`NEW_ENDPOINT` can't be lost or mistaken for a server message, and nothing blocks in a receiver. `Update` keeps R1's
offline answer (H1 holds). The `refresh` handling can't loop.

---

## Medium

### M1. A ping is sent once per boot: one lost send or ack removes a live app 7 days later

**Failure.** `UpRules.liveness` sends the ping once (`pingId`, `pingSentMs`, `pingBoot`). In the same boot it is never
sent again. Seven days later (and an hour after the last send) the row is removed with `UNREGISTERED`. Only a reboot
re-sends it. A phone that isn't restarted that week, which is the Jelly Star's normal case, therefore gets one chance.
That one send or its answer can be lost on paths the code doesn't recover from:

- **The send is lost after the stamps are stored.**
  - `policyAccepted` and `userPresent` write the row first, then hand the ping to the raise executor. The raise may
    still be binding (up to 5 s) when the launcher process dies, for example when a self-update is installed by the
    same sync, or after a crash. The ping is gone and the row says it was sent.
  - `UpEngine.failed` returns early for the `NEW_ENDPOINT` kind. A `send` that throws therefore counts as sent too.
- **The ack is dropped before the store read.** `ack()` returns without reading the store when:
  - `view().on` is false (`UpRuntime.view` fails closed on an unreadable cached policy);
  - the ack falls over the 30 reads a minute cap.
- **The app's process dies between `onReceive` and the connector's ack.** The raise makes this unlikely, but it can
  happen.

Each is rare. The cost is a live app that gets no push until it is opened again. Element X re-registers at once on
`UNREGISTERED`; FluffyChat and Fedilab wait for their next start. This is the residual wrongful-unregister path the
round was asked to close.

**Fix.** Keep the boot re-send, and also re-send the outstanding ping, with the same id and through the same raise,
when its `pingLastSendMs` is a day old. The dead rule stays as it is: 7 days since the first send and an hour since the
last. A removal then follows about seven unanswered sends, not one.
- No visible cost for a live app, which answers the first send.
- A dead token's app gets one raise a day for a week; its connector ignores the unknown token.
- One `up_rules.json` row: "a ping a day old in this boot: resend, same id".

This tightens Q1's "once per boot" on the safe side only. Say so in the round's report to the user.

---

## Low

**L1. The L3 stamp rule changes nothing, so a row with a stamp ahead of the clock stays frozen.**
- `UpRules.stamp` reads a stamp more than a day ahead as `now`. The age is then 0 at every pass, until real time comes
  within a day of the stamp. After that the age is negative until real time passes it. That is exactly R1's freeze.
  The `up_rules.json` rows pin it ("reads as now: not sent", "known, pending").
- `ack()`'s hourly skip has the same effect: `now - stamp(...) = 0 < HOUR`. So an ack never repairs a future
  `lastAppAckMs`.
- Concretely: a `keyTryMs` written while the clock ran a week ahead holds a pending VAPID key unposted for that week.
  Meanwhile the app's server signs with the new key and is refused (403). A `refreshMs` ahead stops the heal the same
  way.
- It fails safe for liveness: no removal.
- **Fix:** when a pass or `ack()` finds a stamp more than a day ahead, write it back as `now`, so its age starts
  counting. Correct the design's §2 wording ("read as now") to match.

**L2. A transient 4xx drops the new key as "invalid" (the builder's departure).**
- `MdmUpServer.register` maps every 4xx except a 409 with a body to `Invalid`, and `postKey` treats `Invalid` as the
  server's "not a point". It also maps an undecodable 200 to `Invalid` through `SerializationException`.
- Affected answers: 401/403 (auth), 408/413/429 (a proxy) and a 409 without a body. Each drops `vapidPending` and
  writes `NOTE_BAD_KEY` ("sent a key that isn't valid").
- The server keeps the old key while the app's server signs with the new one, so every push gets 403 until the app is
  next started.
- **Fix:** in `postKey`, only a 400 drops the key. Any other answer keeps it pending: give `Registered` the status, or
  add a `Rejected(code)` case. R1's `call` can keep its mapping.

**L3. A REGISTER with the stored key keeps a different pending key, which is posted later.**
- `knownOrUpdate` answers `Known(row)` when `vapid == row.vapid` and leaves `vapidPending` as it is. Take a row with
  `vapid = K1` and `vapidPending = K2`, the K2 post having failed. The app re-registers with K1, so K1 is its key
  again. `retryKeys` still posts K2 an hour later, and the server then refuses every push the app's server signs with
  K1.
- Rare (it needs a key rotated back), and it heals at the app's next start.
- **Fix:** `vapid == row.vapid` with a non-null `vapidPending` returns `Known` with the pending key cleared, written by
  `register`. Add one rule row.

**L4. A failed key post waits a full hour, and gate row 7b expects it sooner.**
- `Update` stamps `keyTryMs` before its call, and `retryKeys` honours M4's hour. After a failed post (no network, a
  5xx), the key is posted at the first accepted policy at least an hour later, not at "the next policy" (§4.6 step 4,
  gate row 7b).
- M4 was written against REGISTER-driven calls. A retry from `policyAccepted` is already bounded by the sync rate.
- **Fix:** retry a failed post at the next accepted policy (or after 5 minutes), keeping the hour for REGISTER-driven
  calls. Otherwise change row 7b to backdate `keyTryMs` with the hook.

**L5. The gate: the backdating hook and `ackSeen`, and two rows missing.**
- **The hook and the in-memory ack limit.** `UpDebugReceiver` changes the store, but `UpEngine`'s `ackSeen` still holds
  the probe's token, read when it acked its registration. Within that hour, the ping's ack in row 1 returns before the
  store read. The probe logs "acked", the ping stays in the store, and row 2's preconditions are then wrong.
  - In production this can't happen: a ping needs 23 days without an ack.
  - **Fix:** have the hook also forget the engine's ack reads for that token (a small `UpEngine` method reached through
    `UpRuntime`), or let the gate wait an hour.
- **No row tests Q1's boot re-send.** Add one: a ping backdated 8 days, the emulator rebooted, then unlocked.
  Expected: a re-send with the same id and no removal; with "ack off", the removal an hour later.
- **No row has a time rule active.** Add the probe suspended by a time rule during row 1's ping, to confirm the ack
  comes back. `AppEnforcer` only suspends for the schedule (it never hides), and R1-G row 5 checked delivery, not the
  ack.

**L6. Judging `pingBoot: Int?` (a departure): right, but an unreadable boot count switches the re-send off.**
- The boot count is better than the design's boolean, which can't tell one boot from another without a
  `BOOT_COMPLETED` receiver.
- But `bootCount` falls back to 0. If `BOOT_COUNT` can't be read, every row's `pingBoot` equals `boot`. A phone that
  was off more than 7 days after a ping then removes the row at the first pass after the unlock, which is the case
  Q1's refinement exists for.
- `BOOT_COUNT` is public and written by the system on AOSP 14-17, so this is a fallback concern only.
- **Fix:** fall back to a boot id that errs toward re-sending, for example the boot instant
  `currentTimeMillis() - elapsedRealtime()` rounded to 10 minutes. A clock change only adds a re-send. Log the
  fallback once.

**L7. The two new notes stay in the Settings row for good, with no date.**
- `UpStore.note` keeps one note per package and kind, never clears it, and the row doesn't show `atMs`.
- After FluffyChat is removed once and registers again, the PIN-gated row still says "FluffyChat didn't answer for 30
  days; its registration was removed" while it works. "Sent a key that isn't valid" stays the same way after a good
  key.
- **Fix:** drop `NOTE_UNANSWERED` for a package when its next registration is confirmed, and `NOTE_BAD_KEY` when a key
  is taken. Alternatively show the note's date.

**L8. A pass sends even when its store write failed.**
- `policyAccepted` (`if (keep != rows) store.write(keep)`) and `userPresent` ignore the write's result before
  `sendPass`.
- With a failing sealed write (a Keystore fault), no `pingId` or `refreshMs` is ever stored. Every sync and every
  unlock then sends a new ping or refresh through the raise, and FluffyChat's foreground notification shows at each
  unlock.
- R1's rewrite had the same pattern, but it was a plain broadcast then.
- **Fix:** send the pass's `NEW_ENDPOINT`s only when the write succeeded. The dead rows' `UNREGISTERED` can follow
  the same rule.

**L9. Tests: three cheap gaps.**
- No engine case shows the origin rewrite going through the raise. `FakeUpPhone` writes both paths into `sent`, so a
  regression to the plain broadcast passes. Assert on `phone.raised` in one rewrite case.
- No case covers a pending row's `Retry` carrying the newest key (§4.6).
- No case shows that `failed()` ignores the `NEW_ENDPOINT` kind.

Each is a few lines. Everything else is covered lean and well: the `liveness`, `refresh` and `pass` rows, the register
rows for H1 and M4, the engine's dead, ping, refresh and key cases, and the raise kind.

**Nits (no finding):**
- Design §8 names the strings `settings_mdm_up_note_*`. The build's `settings_up_unanswered`/`settings_up_bad_key`
  follow the existing `settings_up_*` family, which is the right call (a departure judged fine). Update §8 to match.
- `userPresent` decodes the cached policy and opens the sealed store at every unlock. It runs on the work executor and
  is cheap, so it is acceptable.

---

## The departures, judged

| Departure | Verdict |
|---|---|
| `pingBoot: Int?` from `Settings.Global.BOOT_COUNT` instead of a boolean | Better than the design; keep. The fallback is L6. |
| String names `settings_up_unanswered` / `settings_up_bad_key` | Right (the existing family); fix the design text. |
| Any non-409 4xx on a key post counts as "bad key" | Too wide (L2): only a 400. |
| `UpDebugReceiver` (debug only) through `UpRuntime.withStore` | Right: one executor, the same prefs store, DUMP-guarded, in `checkReleaseHasNoDebugHook` and `DebugHookAbsentTest` (the package path fix there is correct). `withStore` ships in release as an in-process function only, not reachable by IPC. The engine's in-memory `ackSeen` is the one thing it can't reach (L5). |

## Checked and sound

- **Wrongful unregister:**
  - **Reboot:** the re-send comes before any "dead" verdict, and the hour runs from it.
  - **Clock set back:** stamps ahead never bury a row.
  - **Clock set forward:** a row can only die after a ping that went out at least an hour earlier on the same clock.
  - **Process restart:** `BOOT_COUNT` is unchanged, so there is no extra send.
  - **Time rules:** the schedule lock only suspends (`AppEnforcer`: "the schedule lock only suspends"). A suspended
    app is raised and gets the broadcast (R1-G row 5).
  - **Stopped apps:** `FLAG_INCLUDE_STOPPED_PACKAGES` on the raised send too.
  - **Pings answered by a refresh or a REGISTER answer:** they count, since any ack writes when `lastAppAckMs` is 23
    days old.
- **The raised `NEW_ENDPOINT`:**
  - **No loss:** without a raise service or with a failed bind it is sent plainly, and `unbind` flushes the queue.
  - **No ack confusion:** `sent`/`failed` return early for the kind, so its ids never enter `acks` or `delivering`.
  - **No ANR:** the `USER_PRESENT` receiver and the debug receiver only post to the work executor.
  - **Order:** per-app FIFO, so a rewrite's endpoint can't be overtaken by an older one.
- **The in-use gate:**
  - `isInteractive && !isDeviceIdleMode`, with the same API on 14-17.
  - `USER_PRESENT` is registered once, in `build`, in the main process, with `RECEIVER_NOT_EXPORTED` (API 33, below
    minSdk 34). A protected system broadcast reaches an unexported receiver.
  - The launcher (HOME, with the anchor's foreground service) isn't a cached app, so Android 14's queuing of
    context-registered broadcasts doesn't defer it.
  - After a process restart the receiver comes back at the next sync, which builds the engine.
  - The 304 path also calls `policyAccepted`, so passes run at every sync.
- **`Update` (H1, M4):**
  - The answer goes out before any network call.
  - A keyless REGISTER is `Known`, and nothing answers `REGISTRATION_FAILED` for a key.
  - A REGISTER within the hour is `Known` with the key pending, and a pending row is retried with the newest key.
  - The pending key lives until a 200 or a 400, or until its row goes.
- **`refresh`:** at most once a day per row, counted from the attempt. A `reg` the phone doesn't hold, a pending row
  and a dead row are skipped, and an unknown `why` reads as `outside`. One `NEW_ENDPOINT` per row per pass (L2 of the
  design QA); a due ping carries the refresh. Nothing loops: the server's mark ends or fades in 7 days.
- **Thread safety:** every store access is on the work executor (the debug hook's too); the raise state is on its own
  executor.
- **The rest:**
  - i18n: en and nb for both notes, apostrophes escaped.
  - No new API above 34 and no new manifest entry in release.
  - The contract copies are identical. `UpRow`'s new fields default to null, so R1 blobs decode.

---

## Handled (fix round, 2026-11-17)

- **M1** fixed: `UpRules.liveness` re-sends an outstanding ping (same id) when `pingLastSendMs` is a day old
  (`PING_RESEND_MS`), besides the boot's re-send; the dead rule is unchanged. `UpEngine.failed` of a `NEW_ENDPOINT`
  clears the row's `pingBoot` (a lost ping goes again at the next pass; a lost refresh clears `refreshMs`). An ack that
  answers a ping this process sent skips the hourly and per-minute ack limits (`pingsOut`). An ack dropped while UP is
  off in the cached policy, or by a process that died, is covered by the next day's send. Rows in `up_rules.json`; design
  108b §2 and "Decided 2026-11-17".
- **L1** fixed: `UpRules.clamped` writes ahead stamps back as now at every pass; `ack` rewrites an ahead
  `lastAppAckMs`. Design §2 corrected.
- **L2** fixed: `Registered.Rejected(code)`; only a 400 (`Invalid`) drops the key.
- **L3** fixed: the stored key again clears a different pending key (rule row).
- **L4** fixed: a failed post is retried by the next policy at least 5 minutes later (`KEY_RETRY_MS`); the hour stays for
  REGISTER-driven calls. Gate row 7b says so.
- **L5** fixed: the debug receiver calls `UpEngine.forgetAcks(token)`; gate rows 8 (boot re-send) and 9 (time rule) added.
- **L6** fixed: an unreadable `BOOT_COUNT` falls back to the boot instant (now minus uptime) in 10-minute steps, logged
  once: a new boot reads as a new value, a clock change only adds a re-send.
- **L7** fixed by clearing, not dating: `NOTE_UNANSWERED` goes when the registration is confirmed again, `NOTE_BAD_KEY`
  when a key is taken (`UpStore.clearNote`).
- **L8** fixed: a pass sends (and the user-present pass writes) only when its store write succeeded.
- **L9** fixed: the rewrite asserts `phone.raised`; a pending row's retry carries the newest key; the `failed()` case now
  asserts the new behaviour (a failed ping is marked to go again), since "ignores the kind" no longer holds.
- **Nit** (§8 names): design updated to `settings_up_unanswered` / `settings_up_bad_key`.
