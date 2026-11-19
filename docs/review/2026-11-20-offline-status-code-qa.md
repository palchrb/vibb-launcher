# Code QA - branch `offline-status` (design 121 revision 3)

Reviewed: `git diff 24b7c7967..c058fbd98` (commits 6c50e3b97, c058fbd98) in `/workspace/handy-wt/offline-status`,
against `docs/design/121-offline-status-within-minutes.md` (revision 3 and the revision 2 text it keeps) and
`docs/review/121-offline-status-design-qa.md`. Read only; no Gradle, no cargo run (the reasoning below is from the code).
2026-11-20.

Read: the root, server and launcher CLAUDE.md; `streams.rs`, `phone_status.rs`, `airplane_mode.rs`, `mobile_network.rs`,
`handlers/devices.rs` (`list_row`, `held_of`, `connection_inputs`, `render_device`), `handlers/device_pages.rs`
(`connection_rows`, `view_about`), `handlers/device_api.rs` (the report's insert, `last_seen_at`), `attention.rs`,
the other `connection_of` callers (`locate.rs`, `dns_filter.rs`, `time_answers.rs`); the launcher's
`CommandListenerService.kt`, `AirplaneRoute.kt` (`turnOn`, `runRouteWindow`, `WindowSteps`), `AirplaneRuntime.kt`
(edges), `AirplaneMode.kt`, `MdmSyncWorker.postReportOnly`, `StatusReports.sendReport`, `SyncSchedule.kt`,
`BackstopAlarm.kt`; the tests in the diff; `server/DEPLOY.md`'s new section; the two notes.

**Counts: 2 High, 1 Medium, 7 Low.**

## What holds

- `airplane_mode::held_since` is right as a rule: it reads the latest report only, needs `going_on` or `on`, and ends
  with any contact more than 10 s after the report's `reported_at` (server time, `datetime('now')`, as
  `last_seen_at`). A phone that returns (a sync, a stream, airplane off's `syncOnNetwork`) leaves the grey at once; a
  server restart keeps it (`last_seen_at` is persisted). `on_since` is unchanged in behaviour (`since_ms` refactor).
- The restart grace always ends: `started` is set once (`main.rs` `Default::default()`), and `green_until` is at most
  `started + 6 min` or `reach + 1 min`.
- Held is applied everywhere a dot or the status lead is drawn: the Devices list (`list_row`), the summary header
  (`render_device`) and About's Connection row; `with_roaming` and `with_airplane` skip a held lead, so nothing is
  said twice. The other `connection_of` callers (locate, DNS filter, time answers, Attention) read only
  Offline/Disconnected/freshness and are unaffected.
- i18n: `devices.status.abroad` / `no_data` in both `nb.json` and `en.json`; the airplane words reuse the existing
  `devices.status.airplane_since`. The copy ("Flymodus på siden 21:03", "I utlandet · ingen data") follows "Words in
  the UI": short, grey, no warning for a chosen state.
- No form or save is touched, so the "a save never jumps to the top" rule is not in play.
- The hold blocks every reconnect path in the service (`connect`, `checkStream`, `scheduleReconnect`,
  `followNetwork`), bumps `connectGen` so an in-flight token read is dropped, and the cancelled source's
  `onFailure` is ignored (`eventSource` already null). No drop wake lock, no earlier backstop, as designed.
- `Network.fromNetworkHandle` is API 28; nothing else version-dependent. Android 14-17 is fine on the API surface.

## High

### H1 - Launcher: on success the hold is released with an immediate reconnect, racing the radios' teardown

`CommandListenerService.kt:451-456` (`releaseAirplaneHold` -> `checkStream("airplane", restartAttempt = true)`),
called from `AirplaneRoute.kt:248` (`finally`).

**Problem.** The route's `finally` runs as soon as `AirplaneRuntime.isOn` reads the global setting as on
(`WindowSteps.await` polls every 50 ms) plus the unpin/rehide steps - well before Telephony has torn the data
connection down (the setting flips first; the data call is deactivated through the RIL afterwards, typically 100 ms to
a few seconds). `releaseAirplaneHold` then calls `checkStream(restartAttempt = true)`, which calls `connect()` at once.
The pooled HTTP/2 connection over cellular was deliberately **not** evicted (so the report could ride it), so the new
stream's HEADERS go out on it within tens of milliseconds.

The design says the opposite (§4.4: "on success the network callback reconnects when a network is there (Wi-Fi kept);
on a failure `checkStream` reconnects at once"), and the code comment at `AirplaneRoute.kt:247` repeats it; the code
does both cases the failure's way.

**Failure scenario.** Kid on mobile data taps Flymodus at 21:03. RST_STREAM, then the `going_on` report (21:03:00).
Airplane setting flips at 21:03:02; `finally` reconnects at 21:03:02.05 over the still-live cellular link; the server
opens a stream (`record_access`, `open = 1`). The radio goes; the new stream dangles at the holder.
- The PWA shows **green "Online"** (with the airplane words on the second line for `GOING_ON_MS`, then without) until
  the holder drops the dangling connection: 2.5 min behind a Go front in ~38 % of cases, 15-20 min otherwise -
  exactly the case the hold exists to avoid.
- After the drop: if the reconnect was within 10 s of the report (`HELD_SLACK_MS`), grey Flymodus; if the route took
  longer (`synchronized(AppEnforcer)` can wait on an apply), the contact is past the slack and the phone reads orange
  "Ingen kontakt", then grey "Offline", never Flymodus.
- If the HEADERS don't get out, the open fails, `streamDown` -> `scheduleReconnect` backs off - harmless, but that only
  happens when the race is lost.

**Fix.** Tell the release whether airplane mode came on. On failure keep today's `checkStream`. On success clear the
hold without connecting and remember the held network (`streamNetwork`/`defaultNetwork` at the hold); let the default
network callback decide: reconnect only once `defaultNetwork` is non-null and differs from the held handle (Wi-Fi kept,
or any later network). Since `followNetwork` returned early during the hold, call it once after the release (it then
reconnects only for a different network). Optionally `ServerClients.streamDropped()` after the report, so nothing else
reuses the dying cellular connection. Add a launcher row: on success with the default network still the held one, no
`connect`; with a new network, `connect`.

### H2 - Server: a dangling stream's late close moves `reach`, so a dead phone already orange turns green again and its Offline moves out ~40 min

`streams.rs:293-296` (`reach`, the `open == 0` arm: `at = last_contact.max(stream.since)`).

**Problem.** With the keepalive answers dropped (revision 3), a stream's close is no longer proof that the phone was
reachable shortly before it: a vanished phone's stream is closed by whoever holds its TCP 2.5-20 min after the vanish.
The 35-min rule may already have moved the phone to Away/orange (`reach` = last contact) while the stream was still
"open". When the holder finally drops it, `close()` sets `since = now`, and `reach` jumps to the close: green for
`CLOSE_GRACE_MINUTES` and Away for 45 more minutes.

**Failure scenario.** Last backstop sync 20:35. The phone's battery dies at 21:00 (no FIN). 21:10 (35 min): orange
"Ingen kontakt · sist sett for 35 min siden". 21:17 the front gives up (tcp_retries2): the stream closes ->
`reach` = 21:17 -> **green dot and "På nett · …" line** 21:17-21:18 for a phone dead 17 min and silent 42 min, then
orange again until **22:02** instead of grey Offline at 21:20. This happens whenever the last sync was more than
`35 - d` minutes before the vanish (d = the holder's detection time), i.e. most of the time behind an untuned front -
and it breaks the user's headline rule "not green after 35 min without contact".

**Fix.** Count the close only when the stream was still live when it closed, i.e. within the Online window of the
last contact: in the `open == 0` arm use `at = stream.since.max(last_contact)` only if
`stream.since - last_contact <= ONLINE_WINDOW_MINUTES`, else `at = last_contact`. Add rows to
`the_windows_count_from_the_close_and_the_start`: stream closed 1 min ago, last contact 42 min ago -> orange, last
seen 42 min, Offline at contact + 45; and the existing "closed 30 s ago, last contact 30 min ago" stays green.
(Also consider whether a close 15-20 min after an unclean vanish should restart the full 45-min Away window at all;
with this fix it only does so for a stream that was still within its 35 min.)

## Medium

### M1 - Server: a healthy idle phone whose backstop is late goes grey "Offline" at 45 min with its stream still open

`streams.rs:312-337` (`connection_state`: Online needs contact within 35 min; then `reach` = last contact for an open
stream, Offline when it is over 45 min old).

**Problem.** Design 121's "Known cost" says a late backstop shows orange "Ingen kontakt" from 35 min until the sync
lands. But the backstop is `setAndAllowWhileIdle` (inexact, up to 0.75 x late: 52.5 min, the figure the old 60-min
window was sized for, `streams.rs`' removed comment). Between 45 and ~52.5 min the phone with its stream still counted
open reads **grey "Offline · sist sett 20:14"**, the same as a dead one, and Attention marks its rows "at last report"
(`attention.rs:662`). A phone whose stream is still open past last contact + 45 min is almost certainly alive: the
holder closes a dead connection within ~20 min of the vanish.

**Fix.** Either cap an open stream at Away (orange) - Offline only once the stream is closed (or past a long ceiling
such as 60-90 min) - or state the grey case in design 121's "Known cost" and in `docs/notes/server/pwa.md` and let the
user decide. The first is one more arm in `connection_state` and a test row ("open stream, contact 50 min ago: Away,
orange, not Offline").

## Low

### L1 - The 1 s latch in `holdForAirplane` can let the close land after the report
`CommandListenerService.kt` companion `holdForAirplane` (`done.await(1, SECONDS)`, result ignored). If the main thread
is busy over 1 s, the route goes on with `streamHeld = false`; the posted block still holds and cancels later, and
only the 30-s timer releases. The outcome is benign (the server sees the close after the report; grey still follows),
but the comment "closes ... before its report" is then false. Log it when the latch times out.

### L2 - The 30-s hold cap can run out mid-route
`AIRPLANE_STREAM_HOLD_MS` covers the report's 5 s and a ~1 s window, but `synchronized(AppEnforcer)` in `turnOn` waits
on any running apply without a bound. If the cap fires first, `airplaneHoldEnds` reconnects before the radios go (H1's
symptom). Re-arm the cap when the window starts, or skip the reconnect from the timer while `AirplaneRoute.busy`.

### L3 - Restart grace stretches Away for phones that were already gone
`streams.rs:299-306`. A phone silent 30 min when the server restarts was orange and due Offline at contact + 45 (15 min
after the start); now it is green for 6 min and Offline only at start + 45. Design §4.3 mentions the green minutes,
not the 30 extra orange ones. Worth one line in the design/notes, or bound `at` so Offline stays at
`max(contact + 45, start + RESTART_GRACE)`.

### L4 - About's Connection row says "Borte"/"Away" (grey) while held
`device_pages.rs:487-498`. The colour turns grey but the word stays the raw state; the dot and the list say "Flymodus på
siden 21:03" / "I utlandet · ingen data". The row could carry the held words, or the note should say it keeps the
raw word on purpose. (The row is also orange "Away" in the green minute while the dot is green - older than this
branch.)

### L5 - DEPLOY.md: "direct to the tailnet needs nothing" vs the optional faster close
`server/DEPLOY.md:231` and `:263`. The `tcp_retries2 = 8` paragraph applies to a direct `tailnetIP:port` setup as well
(there the server's own namespace holds the phones' TCP - or tailscaled's, with `tailscale serve` or userspace
networking), but line 231 tells such an owner to skip the section. Say "need nothing for the probes; the faster close
below applies to them too (the server's host or container, or the tailscaled host)".

### L6 - DEPLOY.md: the `ss` check assumes port 443 and a VPS
`server/DEPLOY.md:236-241`. `sport = :443` and "carrier addresses, not your VPS" fit a TLS front; a phone going
straight to `:3101` shows tailnet `100.x` peers on 3101. Use `sport = :<the port the phones connect to>`. Nothing in
the section requires the user's Caddy otherwise.

### L7 - Missing test rows for the two Highs
No server row for a stale-open stream that then closes (H2), and no launcher row for what the release does on success
versus failure (H1); the source-text test in `AirplaneRouteTest` only checks that `releaseAirplaneHold()` is in the
`finally`.
