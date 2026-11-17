# Design QA: design 115 (app push held without network; the glance row's name)

Read-only review of `docs/design/115-up-held-without-network.md` at a86f2d226. Every line number the note cites was checked
against `main` and matches (±1). `L/` = `launcher/app/src/main/java/com/kidslauncher/mdm/`, `S/` = `server/src/`.

Verdict: the design is sound. Choosing "no pull" over the other two options is right. The hold rule covers every sink
reason, and the 108c arm (a) guard has the right shape. No H findings. There are five M findings to fix in the note
before the build. Two are about when the hold ends (it can end silently, or never end because the phone's own claim is
trusted), one is about the burst after the hold, one is about the glance rule, and one is about the gate.

---

## M1. The end of the hold is judged at the moment the tunnel switches, and from a second view of the network. If that
check misses, nothing asks again until the next `up` event or sync

§2.4 asks for a pull only on a computed held -> none transition inside `shapeChanged`, and §2.3 computes `wanted` from
`applyShape(storedInputs(), bestNet(context), storedShape())`. That is apply()'s rule with the launcher's own
`activeNetwork` (`L/server/MobileNetworkRuntime.kt:186-191`, which reads telephony fresh on every call through `whereNow`).
The service, though, decides with `serviceShape(inputs, lastBest, live, persisted)` (`L/server/KidVpnService.kt:188-194`).
`lastBest` is the watcher's best-matching network. It survives `onLost` (`:106-110`), and telephony is re-read on it at
most every 10 s (`:355`, `TELEPHONY_REREAD_MS`). The two views can disagree for a few seconds. One example is coming home
from abroad: `whereNow` is fresh while the watcher is still throttled, or `inService` flickers to `unsure`. If, at the
single call after `establish` (`:272`), `wanted` still reads SINK, the transition is never seen. The shape then stays
put, so no further `shapeChanged` comes, and the backlog waits for the next push's `up` event or the next sync (the
30-minute backstop). Row 3's "within ~3 s" fails for that run.

The same gap applies to a send-time hold (§2.4, the send). A message refused because `wanted` blipped to sink, while the
tunnel never changed, is never re-pulled: `UpEngine.failed` (`L/push/up/UpEngine.kt:543-549`) asks for nothing, and
`shapeChanged`'s remembered state never became "held".

**Fix to the note:**
- (a) Compute `wanted` from the same view the service uses. Expose `KidVpnService.lastBest` through the companion (it
  is `@Volatile`) and use `serviceShape(storedInputs(), watched, live, storedShape())`. Fall back to `bestNet(context)`
  only when no service is running.
- (b) Make the end trigger depend on `heldSeen`, not on `shapeChanged`'s own previous value. Every `shapeChanged`
  while `heldSeen` is set schedules the "net" pull 2 s later. The pull re-checks `hold()` when it runs. If it is still
  held only because of `wanted` (tunnel and decided both non-sink), it retries once at 10 s.
- (c) `UpEngine.failed` for a held MESSAGE sets `heldSeen`.
- (d) Add an `UpEngineTest` row: a send refused for the hold, then the shape ends with no change of the computed state
  -> the message is delivered.

## M2. The Attention guard takes the phone's word, so a hold that is wrong also hides the Attention row

§2.8 suppresses arm (a) whenever a report with `up_held IS NOT NULL` follows each served policy. If the hold logic
holds while the apps really have a network (M1's divergence, or §9's VPN taken over), every report says held. Pushes
then sit on the server until their TTL, and the parent never gets the "App notifications aren't reaching the phone"
row. That is exactly the case 108c exists for. The server already has an independent reading in the same report:
`device_status.mobile_data_json` (migration 0143) and its `sink`, which `reportedSink` fills from the same inputs
(`L/server/MobileNetworkRuntime.kt:350-357`, `L/server/MobileNetwork.kt:260-270`). A real hold always reports a sink
other than `off`, including while no network is seen and a persisted sink is kept.

**Fix to the note:** the guard counts a held report only when the same row's sink is not `off`:
`... AND up_held IS NOT NULL AND COALESCE(json_extract(mobile_data_json, '$.sink'), 'off') <> 'off'`.
- Add a server test row: held reports with `mobile_data.sink: "off"` -> the row is raised.
- Add the matching line to §2.8's bullets.
- One more emulator row, or a step in row 11: force `vpn_shape=sink` on Wi-Fi through the debug hook -> Attention
  appears.

## M3. The burst after the hold may outlast the 5-second raise. Element X's fetches after that can fail, which is the
same fallback this design removes

All of an app's MESSAGEs go out within about a second: one step of up to 50, `flush()` sends the queue in a tight loop
(`L/push/up/vendor/RaiseToForeground.kt:156-158`), and each send pushes the unbind 5 s out (`:136-139`). So the app is
raised for about 6 s in total. Element X resolves each push with its own network round trip. As far as is known it
starts no foreground service of its own for UnifiedPush (row 5 of §6ay used a probe that does). With 10-20 backlogged
events it can still be working when the raise ends. Then Android 15+'s background network rule, which the note itself
cites in §2.1 and §4, can cut it, and it posts "Du har N nye meldinger". Today one push comes at a time, so this burst
pattern is new with 115. §2.5 only looks at NMS's notification update rate, not at the raise window. The probe in §6
does one parallel GET per MESSAGE at once, so the gate would not catch this.

**Fix to the note:**
- (a) The gate's probe works through MESSAGEs sequentially with a 500 ms "fetch" each, and logs `net=ok|fail` per
  message. Add a row with 20 held messages.
- (b) Name the fix now, to build if (a) or the Jelly Star shows failures: pace one app's sends. When the raise's queue
  holds more than 3, send one every 400 ms. Each send already extends the raise, so the raise covers the backlog.
  Optionally, a cap per hold-end burst.
- (c) Add to §9: a MESSAGE sent in the last moment before the sink starts can still fall back. That race is inherent.

## M4. The glance rule replaces conversation titles, and the gate row as written tests nothing

- The rule (§3) replaces any title that folds equal to the app's label. That includes a MessagingStyle sender or
  conversation name. §3's last bullet calls this "harmless", but MessagingStyle conversation titles must never be
  replaced. The row already carries what is needed (`L/badges/GlanceRows.kt:423-437`): `shape` (DIRECT/GROUP for
  MessagingStyle) and `sender`.
  **Fix:** `glanceShownTitle(row, ownLabel)` substitutes only when `row.shape == GlanceShape.PLAIN && row.sender == null`
  and the title folds equal. That is a plain notification whose title came from `EXTRA_TITLE`. Add `GlanceRowsTest`
  rows: a DIRECT row titled "Element X" -> kept; a GROUP row with conversation title "Element X" -> kept.
- Element X's fallback is most likely a plain `setContentTitle(applicationName)`. A plain notification only gets a
  title at `GlanceLevel.TEXT`, though: `glanceLevel` is APP_ONLY at "Navn" without messages
  (`L/badges/GlanceRows.kt:197-203`, title read only at SENDER and up, `L/badges/GlanceReader.kt:168-182`). So the user's
  row must have been at "Full text", or the fallback is MessagingStyle. The build should confirm with
  `dumpsys notification --noredact` on the Jelly Star, or from Element X's source, that the fallback is plain. If it
  turns out to be MessagingStyle, the PLAIN restriction would leave the bug unfixed, and the user has to decide.
- **Gate row 12:** at "Navn", the probe's plain `notify --es title "UP Probe"` has no title on the glance, so the row
  shows "Chat" with or without the fix (vacuous). **Fix:** tick the probe at "Full text". Add a MessagingStyle notify
  whose conversation title is "UP Probe" -> the glance shows "UP Probe".

## M5. `heldSeen` lives in the engine, but §2.4 refuses the pull in the runtime first. The flag's life is not specified

§2.4 says `UpRuntime.requestPull` checks the hold "before it takes the wake lock", so the engine's `requestPull`, which
"remembers `heldSeen`", never runs for a held `up` event. Its clearing ("then cleared") also doesn't say what happens
when the first run after the hold fails (`api.pull` null, `L/push/up/UpEngine.kt:489-490`). Cleared anyway, the next
pull would leave samples that include the hold.

**Fix to the note:** one owner for `heldSeen`, in the engine. The runtime's early refusal posts a cheap
`engine.noteHeld()` (no wake lock), and so do `shapeChanged` and the send-time failure (M1c). It is cleared only after a
run with `held = true` got an answer. It is in-memory only, which is enough because a process death opens a new stream
generation (`store.rs:446-452` keeps a sample only on the stream it was accepted on). Say this in §2.5.

---

## L1. `decided == SINK` and `wanted == SINK` can hold when Android has no lockdown of ours (§9's accepted risk)

`writeAlwaysOn` records `vpn_shape` before its device-owner check (`L/server/MobileNetworkRuntime.kt:147-151`), and
`wanted` holds even when no VPN service can run. **Fix:** `decided` counts only while
`dpm.getAlwaysOnVpnPackage(admin) == packageName && isAlwaysOnVpnLockdownEnabled(admin)`, which is the thing that
actually cuts the apps when the tunnel isn't up (cheap, device owner only). `wanted` counts only while
`KidVpnService` is running (`running != null`), because it follows within milliseconds. This closes §9's second bullet
instead of accepting it.

## L2. Where `shapeChanged` runs and what it reads

- "At its top" of `writeAlwaysOn` must come after the pref write at `:147`, or `decided` is stale.
- The calls come from `KidVpnService`'s main thread (network callbacks, `reshape`) and from the sync thread.
  `bestNet` -> `whereNow` makes several telephony binder calls (`:294-322`), which design 97's code QA L7 throttled on
  that same thread. `shapeChanged` should post to UpRuntime's `work` executor and compute there.
- Use `applicationContext`, because `onDestroy`'s call comes from the service context.

## L3. The "net" pull should take the wake lock and not share `flush`

`pullLater` returns while an ack flush is pending (`L/push/up/UpRuntime.kt:424-427`), and the scheduled executor's
delay doesn't run while the CPU sleeps in Doze. **Fix:** `shapeChanged` acquires the pull's 30 s wake lock before
scheduling, and uses its own future (not `flush`), so neither can swallow the other.

## L4. The send-time check is computed once per MESSAGE on the raise executor

A burst of 100 sends means 100 `hold()` evaluations, each with telephony reads on cellular. **Fix:** compute `hold()`
once per pull step (the engine passes it with the batch) plus once per flush, or cache it for 500 ms.

## L5. The pings and the refresh

The ping's `NEW_ENDPOINT` during a hold makes Element X try to register its pusher with the homeserver, which fails
under the sink. Check in Element X's source that a failed `onNewEndpoint` registration does nothing worse than log. If
it unregisters or shows a warning, hold the ping as well: it has a 7-day dead window
(`L/push/up/UpRules.kt:286-311`). The refresh's `held` must be threaded through `UpRules.pass` (`:327-335`) for both
callers, `policyAccepted` and `userPresent` (`L/push/up/UpEngine.kt:382`, `:404`). Say "the next accepted policy or
unlock".

## L6. One more cut that isn't modelled: the filter's DNS upstream is down

On Wi-Fi under the FILTER shape, an unreachable custom DNS or DoH upstream (design 67's "DNS can't be reached",
`ReachTracker` in `KidVpnService`) leaves the apps unable to resolve while Vibb One (outside the VPN) still pulls. Add it
to §2.1's and §9's not-covered lists. A later hold reason could come from the reach outage.

## L7. The report through the heartbeat

`unified_push` must be added to `S/status_heartbeat.rs`'s `REPORT_KEYS` (`:51-103`). Otherwise `kept_body` drops it, and
every heartbeat-expanded row during a hold stores `up_held` NULL, so the guard fails on most syncs.
`the_report_keys_are_the_structs` (`:331-350`) will fail the build until it is added, but the note should name it in
§2.8. Add a test row: a full held report, then a heartbeat -> the new row's `up_held = 'no_network'`.

## L8. Migration number

0156 is the next free number (0155 is the last). Design 114 (`docs/design/114-setup-status.md:245`) also takes "the next
free number at build time". If the two are built in parallel worktrees, the second to merge renumbers.

## L9. Glance normalisation details

- Fold the label through `cleanGlanceText(label, GLANCE_TITLE_MAX)`, the same cut as the title, so a long label
  truncated with "…" still matches.
- `AppDisplay.ownLabel` returns `null` when `getApplicationInfo` fails, not the package name that `shownLabel` falls back
  to (`L/appdisplay/AppDisplay.kt:197-202`).
- Optionally apply NFC before `lowercase(Locale.ROOT)`.

## L10. The UpRules rows and the gate rows

- `hold` rows to add:
  - "Wi-Fi, the kid's Off" (pass -> none)
  - "cellular roaming that can't be placed, abroad armed" (`unsure && roaming`, `L/server/MobileNetwork.kt:179` ->
    held)
  - "decided sink, no tunnel, Wi-Fi seen" (the service is dead under lockdown -> held)
- Gate row 9: an expired row is not deleted until the hourly prune. Expect "not delivered; still in `up_messages` past
  `expires_at_ms` or pruned", not "the row gone".
- Gate row 11 needs a negative control: the same with `up_held` set to NULL by SQL -> the row is raised, which proves
  the guard is what kept it quiet. With M2: the same with `mobile_data_json.sink = 'off'` -> raised.
- The gate is otherwise feasible with the §6ay rig and `scripts/fake-homeserver.py` as it is (`notify --count --gap-ms`,
  `up --ttl`, event ids `$fakeEvent%04d`, `:33-47`, `:101-112`). The next free section is §6ba (§6az exists).

## Checked and fine

- §2.2: no pull is consistent with R2's pull and ack.
  - Pending acks ride the first pull after the hold, and the server deletes acked keys before it hands any out.
  - Arm (b) cannot fire: a message sent before the hold, or refused at the send, has `handed_ms == fetched_ms` until
    the pull after the hold (`S/unified_push/health.rs:136-141`).
  - The loop rule ends a held step without spinning.
- `held` is omitted when false: `ServerJson` has no `encodeDefaults` (`L/server/ServerJson.kt:15-18`).
- `Pull` has no `deny_unknown_fields` (`S/unified_push/device.rs:219-222`), and `stream = None` makes the first hand-out
  null `stream_ms` (`store.rs:446-452`).
- `KidVpnService` runs in the main process, so a companion `@Volatile tunnelShape` works. `onRevoke` and `onDestroy`
  both reach `stopSelfCleanly` (`:573-593`). `tunnelLost` keeps `shape`, which is correct: lockdown still cuts the apps.
- Leaving the sink writes lockdown off before `establish` (`MobileNetwork.kt:233-235`), so 2 s after the tunnel switch
  is safe.
- Arm (a)'s served instants are kept in memory, the last 4 per phone (`S/unified_push/mod.rs:103-109`). The guard's
  `+1 s` handles `reported_at`'s second resolution in the safe direction.
- The contract files are already in both CI path lists, and the ASK defaults are coherent.
