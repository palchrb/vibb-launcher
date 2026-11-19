# 121 - The phone's status leaves green within minutes of lost contact

Status: **revision 3 - built**, 2026-11-19 (branch `offline-status`): the user's decision on revision 2, below. Revision
2's text (from "Revision 2 applies") stays as the record; where it differs, revision 3 wins.

## Revision 3 - the decision and what was built

**The decision (the user, 2026-11-19, after the data numbers** - today's phone-server line ~150-200 KB/day, answering
every keepalive +70-95 KB/day: "Ja la oss gjøre det du sier"): **no keepalive answers and no other new periodic
traffic.** The status works from what already reaches the server.

**Built:**

- **Server status rule** (`streams.rs`): Online needs a contact within **35 min** (`ONLINE_WINDOW_MINUTES`, was 60;
  the phone's backstop reports every 30 min - zero extra bytes). Otherwise `streams::reach` (§4.3 without the silent
  stream): the later of the last contact and the stream's close, or the server's start for a phone with no stream
  since it (last contact at most 35 min before the start). Away while reach is at most 45 min old, green until
  `green_until` - `CLOSE_GRACE_MINUTES` (1) after reach, so a reconnect within the minute never shows and the
  windows count from the close (QA M1); after a start at least `RESTART_GRACE_MINUTES` (6, QA L4). Then orange "Ingen
  kontakt · sist sett for 4 min siden"; grey Offline 45 min after reach. "Sist sett" stays the last contact.
  `phone_status::NO_CONTACT_AFTER_MINUTES` is gone. Every page reads it through `connection_state` and
  `phone_status::dot` (the Devices list, the summary's header, About's Connection row); Attention reads Offline as
  before.
- **Chosen quiets, grey** (`phone_status::Held`, the lead after "Turned off"): **flight mode through Vibb's switch** -
  the latest report says `going_on` or `on`, the phone isn't online, and nothing reached the server since
  (`airplane_mode::held_since`, `HELD_SLACK_MS` 10 s for the report's own intake): "Flymodus på siden 21:03" /
  "Airplane mode on since 21:03" for as long as that holds (QA H2), never orange; ended by the next contact. **"Ingen
  data" abroad** - the latest report's `where` is `abroad`, Roaming is Av, no pause lifts it
  (`mobile_network::abroad_no_data`), past the green minute: "I utlandet · ingen data" / "Abroad · no data". About's
  Connection row is grey while held. The Devices list reads the report and the Roaming choice only for a phone that
  isn't online (`devices::held_of`).
- **The phone tells the server before the radios go** (§4.4, reusing design 98's `going_on` report - no new route, no
  contract change): step 2a `CommandListenerService.holdForAirplane` cancels the stream when airplane mode takes its
  network (`airplaneHoldsStream`: not Wi-Fi, or Wi-Fi `wifi_kept` doesn't keep) and holds the reconnect until the
  route's `finally` (`releaseAirplaneHold`, at most `AIRPLANE_STREAM_HOLD_MS` 30 s); no reconnect wake lock. ~140 B
  per turn-on. The RST_STREAM is queued before the report's request is built on the same connection (OkHttp writes it
  from its task queue; the report takes longer to build), so the front normally has the close first; if not, the
  server learns the close when the holder drops the connection.
- **DEPLOY.md** "Optional: fewer probes through a TCP proxy": the user's caddy-l4 on the VPS (~0.56 MB/day of probes
  per phone) -> nginx `stream` with the PROXY protocol, HAProxy, Caddy's `keepalive_idle 5m`, `tcp_retries2 = 8` where
  it lasts, the carrier-NAT check; a direct `tailnetIP:port` setup needs nothing.

**Dropped:** the keepalive answers (§2 U1, §4.2: `POST /api/devices/alive`, `?answers=1`, the server's own keepalive,
`stream_silent`) - the user's call on data. So a phone that vanishes without closing its stream (lost coverage, a flat
battery, Android's own airplane toggle) leaves green when whoever holds its TCP drops the connection (2.5 min in ~38 %
of the cases behind a Go front, 15-20 min otherwise; sooner with DEPLOY.md's `tcp_retries2`), and at the latest 35 min
after its last contact. A turned-off phone is design 38's.

**Left out of this round:** the optional push "Ingen kontakt" (§4.5). Small in itself, but with what exists it is a new
`parent_events` kind (wire, switch, words, Attention's tables), a migration for the off-by-default switch, a settings
row with its (i), and a once-a-minute sweep over every phone that repeats the held/abroad/turned-off rules and keeps
"once per outage" across restarts - more than this round's status change. Its own round when wanted.

**Known cost:** the backstop is an inexact while-idle alarm; when Android delivers it late, a healthy idle phone with
its stream open reads orange "Ingen kontakt" from 35 min until its sync lands (design 95 sized the old 60 min for
that). If it shows in practice, the fix is the backstop's alarm, not the window.

**Tests:** `streams::tests` (the windows from the close and the start, the 35-min rows), `phone_status::tests`
(`a_chosen_quiet_is_grey_for_as_long_as_it_lasts`, the green minute), `tests::airplane_mode::flight_mode_through_vibb_is_grey_until_the_phone_is_heard_from`,
`tests::devices_list` (no restart grace for a server up for hours); the launcher's `AirplaneRouteTest`
(`airplaneHoldsStream` rows, the hold before the report and released in `finally`, no drop wake lock). Android 14-17:
the launcher change uses `Network.fromNetworkHandle` (API 28) and OkHttp's cancel only; no version gate.

## Revision 2

Revision 2 applies
the design QA (`docs/review/121-offline-status-design-qa.md`, H2 M5 L8) and two answers from the user the same day:

- the front: "Caddy l4 på ampere sender rå bytes videre, TLS termineres på caddy hjemme ja. riktig IP sendes videre";
- the scope, firmly: "vi må ikke gjøre dette til bare noe som funker for oss, ønsker at det skal funke på alle mulige
  setups, selv om noen ikke bruker caddy og bare rett til tailnetIP:portnr".

So revision 1's main mechanism (tune whoever holds the phone's TCP) becomes an optional extra in DEPLOY.md, and the
design now rests on a signal that reaches the server through any front with no setting anywhere (§4.2).

The user's ask (2026-11-19): "burde telefonens status i det minste være oppdatert til ikke grønn før det har gått en
time når server mister kontakt med den" - flight mode as much as lost coverage; a push "hadde ikke trengt å
nødvendigvis ... eller det måtte man kunne velge". And: "dette skal balanseres med at vi skal holde databruk mellom de
to til det minimale!"

Inputs: `server/src/streams.rs`, `phone_status.rs`, `limits.rs` (`record_access`, `last_access`),
`handlers/device_api.rs` (`commands_stream`, axum's `KeepAlive`), `config.rs`, `parent_events.rs`, `airplane_mode.rs`
(`on_since`, `GOING_ON_MS`), `logins::night_push`; the launcher's `CommandListenerService`, `push/SyncSchedule.kt`,
`assistant/AirplaneRoute.kt`; designs 19, 19b, 31 §3.10, 95 §3, 97, 98; `docs/notes/launcher/push-and-play.md`;
`server/DEPLOY.md`; the handoff of 2026-10-20 ("an Oracle Caddy layer4 node forwards raw TCP to it"). Primary sources,
read 2026-11-19: Caddy's global options page, Linux `tcp(7)` and `Documentation/networking/ip-sysctl.rst`, the kernel
commit "ipv4: Namespaceify tcp_retries2 sysctl knob" (Linux 4.6), AOSP netd `SockDiag.cpp`, RFC 9113 (HTTP/2: PING is
hop-by-hop) and RFC 7541 (HPACK). caddy-l4's `layer4/app.go` and `modules/l4proxy/proxy.go` as the design QA read
them. Measurements for this note in §3. Android: the phone side was checked against AOSP 14-16 sources; Android 17 is
settled by the emulator gates on the API 37 image (§8).

## 0. Verdict

- **The server can't see a phone vanish through a proxy, and must not rely on one being tuned.** Whoever holds the
  phone's TCP connection (the server itself on a tailnet URL, Caddy, a layer4 hop on a VPS, nginx, Traefik,
  Tailscale's Funnel ingress) decides when a dead connection closes, today after 2.5 or 15-20 min. Only something the
  phone sends reaches the server on every setup.
- **The phone answers the server's keepalive.** Every 240 s the server already writes a 3-byte comment on the stream.
  The phone now answers each with a bodiless `POST /api/devices/alive` on the same pooled connection, at the moment the
  comment woke it anyway. A comment with no answer within 30 s means the phone is gone. **~3-4 KB/h, 0 new radio
  wakeups**, against ~2.1 KB/h for the comments today; no setting on any front.
- **The dot leaves green 1.5-5.5 min after the phone vanishes (~3.5 min on average), on every setup**: the next comment
  0-240 s later, 30 s for the answer, 1 min of grace. Grey only 45 min after that.
- **Flight mode** (Vibb's switch) closes the stream before the radios go: grey "Flymodus på siden 21:03" at once and for
  as long as the phone is in flight mode, never pushed. The parent's "Ingen data" abroad is grey too.
- An optional push "Ingen kontakt med Emma" 30 min into an outage: off by default, once per outage, never at night.
- **Optional, in DEPLOY.md:** a front that sends TCP probes costs the phone more than all of this. The user's caddy-l4
  on the VPS sends one every 15 s: ~23 KB/h and ~225 radio wakeups/h per phone. Swapping it for nginx `stream` removes
  that (§4.6), six times what the answer costs. Nothing in the design depends on it.

## 1. What happens today

### 1.1 The server's states

- `streams::connection_state`: **Online** = a stream is open and the last contact is at most 60 min old
  (`ONLINE_WINDOW_MINUTES`). **Away** = no stream, last contact at most 45 min old (`AWAY_WINDOW_MINUTES`). Older is
  **Offline**. Last contact = the newest of the last authenticated request (`limits::record_access`, every request,
  in memory), the last report (`devices.last_seen_at`) and an open stream's `since`. A stream's close is not a contact.
- `phone_status::dot`: green for Online, and for Away while the last contact is under 10 min old
  (`NO_CONTACT_AFTER_MINUTES`, 2026-11-01). Orange "Ingen kontakt · sist sett for 12 min siden" from 10 min. Grey for
  Offline or turned off.
- An idle phone's last contact is its last sync: 0-52 min old (the 30-min backstop, up to 1.75x late). So when its
  stream closes the 10 min have usually passed: it turns orange at once, or grey Offline at once when the last sync is
  over 45 min old. The 10-min grace holds back only a phone that was in touch in the last 10 min, and a vanished
  phone's colour depends on its sync phase.
- The stream counts as open until its response is dropped. Measured (§3): hyper drops an SSE response **at once** when
  its peer closes. So the slow part is learning that the phone is gone, not the grace.

### 1.2 Who holds the phone's TCP, per setup

| Setup | Holds the phone's TCP | What the server's socket sees |
|---|---|---|
| The phone straight to the server on the tailnet (`http://100.x:3101`), kernel tailscaled on the host | the server | the phone (through WireGuard) |
| The same with `tailscale serve` or a userspace tailscaled | tailscaled | loopback |
| Caddy, nginx, Traefik, HAProxy in front (TLS ends there) | the proxy | the proxy, over the LAN |
| **The user's:** caddy-l4 on an Oracle Ampere VPS forwards raw TCP with the real address to the home Caddy (TLS there) | caddy-l4 on the VPS | the home Caddy |
| Tailscale Funnel | Tailscale's ingress servers | tailscaled |

In every case but the first, the server's socket never dies when the phone does, and nothing the server sets on its
sockets reaches the phone. The holder closes the connection when its own TCP gives up; then the close travels to the
server at once (for caddy-l4: it half-closes upstream, the home Caddy reads EOF and cancels the upstream request).

### 1.3 How a vanished phone looks to the holder

Airplane mode, a dead battery, lost coverage: no FIN or RST reaches the holder (on Android, netd's `SOCK_DESTROY` and
the kernel's `tcp_abort` try a RST, but by then the address and the route are gone).

- **Without TCP probes** (the server on the tailnet, nginx, HAProxy): the next comment is retransmitted until
  `tcp_retries2` (15) gives up, at least **924.6 s** later (ip-sysctl.rst; measured still alive at 700 s, §3). The
  close comes 15-20 min after the vanish.
- **With Go's default probes** (Caddy, caddy-l4, Traefik: idle 15 s, interval 15 s, count 9): when no comment goes in
  the next ~150 s (~38 % of the cases), the probes kill it at ~150 s (measured, §3); otherwise the retransmission rule
  above. And on a healthy stream a probe goes after every 15 s without a received packet, answered by the phone's
  kernel: **~225 probes an hour, ~23 KB/h, ~0.56 MB/day and 225 radio wakeups per phone**. That is the user's case
  today (on the VPS); M1 confirms it.
- Writes don't help: a 3-byte comment into a dead connection fits the send buffer and never stalls (so Caddy's
  `write_idle` can't see it either).

So today the dot leaves green 2.5 min after a vanish in ~38 % of the cases behind a Go front, 15-20 min after in the
rest and on every other setup, capped by the 60-min Online window.

### 1.4 The phone side, Android 14-17

- The anchor (`CommandListenerService`, a `specialUse` FGS) passes Doze's network firewall (design 19b §0, AOSP
  14-16). Each comment wakes the radio and the CPU, and the stream's reader thread reads it (the
  `LastByteInterceptor` sees each body read).
- If the anchor isn't running, Doze drops incoming packets for our UID: the comment goes unanswered and the phone reads
  as gone - true, since the server can't nudge it either.
- The launcher already reconnects on a default-network change (1.5 s settle), retries 5 s doubling under a 30 s wake
  lock, and reads a stream silent for 300 s as dead. None of that changes here.

## 2. Options, with their cost per hour on the phone

Per idle phone, on the server line. "Works everywhere" = with no setting on any front.

| Option | Works everywhere | Phone bytes/h | New radio wakeups/h | Vanish -> server knows |
|---|---|---|---|---|
| Today | - | ~2.1 KB (comments) + ~23 KB probes behind a Go front | - | 2.5 min (~38 %, Go front) or 15-20 min |
| **U1. The phone answers every comment** (`POST /api/devices/alive`) | **yes** | **+~3-4 KB** | **0** | **0.5-4.5 min, ~2.5 min mean** |
| U2. The phone answers every second comment | yes | +~1.5-2 KB | 0 | 0.5-8.5 min, ~4.5 min mean |
| U3. The answer as a DATA frame on a duplex stream request | no: nginx buffers request bodies by default, HTTP/1.1 hops may wait for the end | +~1.3 KB | 0 | as U1 |
| U4. A WebSocket instead of SSE, the phone's pong as the answer | no: nginx and others need upgrade config; a rewrite of the stream | +~2 KB | 0 | as U1 |
| U5. HTTP/2 PING from the phone (OkHttp's `pingInterval`) | no: PING is hop-by-hop (RFC 9113 §6.7), it ends at the first proxy | +~3 KB | +15 (its own timer) | only without a proxy |
| U6. The server's own TCP (`TCP_USER_TIMEOUT`, or `tcp_retries2 = 8` in its namespace) | no: only where the server holds the phone's TCP | 0 | 0 | 1.7-6.5 min, there only |
| U7. Tune the front (§4.6) | no: per front, and some can't be tuned (caddy-l4, Funnel) | 0, or -23 KB with a probe-free front | 0 | 1.7-6.5 min |
| D. `SSE_KEEPALIVE_SECS=180` | - | +0.7 KB | +5 | 1 min shorter worst case |
| E. A phone heartbeat every 5 min on its own timer | yes | +~6-10 KB | +12 | ~5 min |

**Chosen: U1** (with the status rule §4.3, flight mode and abroad §4.3-§4.4, the optional push §4.5). U7 stays as an
optional extra in DEPLOY.md, worth doing behind a probing front for the data alone (§4.6). U2 is the cheaper choice if
the user prefers it (Q1).

### 2.1 What one answer costs (U1)

The comment arrives; within a few ms the phone sends the POST on the same pooled HTTP/2 connection (TLS fronts and
any HTTP/2 path), so no handshake and no new radio wakeup: the radio is already up from the comment and stays in its
tail for seconds. The phone's ACK of the comment rides on the POST (delayed ACK), so it costs nothing extra.

| Packet | Bytes |
|---|---|
| Up: the POST's HEADERS frame (HPACK: method, scheme, authority and path indexed after the first; the token indexed if OkHttp's encoder indexes it, ~10-40 B) + 9 B frame + 22 B TLS 1.3 record + 52 B TCP/IP (IPv4, timestamps) | ~95-125 B |
| Down: the 204's HEADERS (status indexed, `date` and whatever the front adds, ~10-50 B) + 9 + 22 + 52 | ~95-135 B |
| Up: the phone's ACK of the 204 | 52 B |
| Saved: the comment's own ACK, now carried by the POST | -52 B |
| **Per answer** | **~190-260 B** |

15 an hour: **~2.9-3.9 KB/h, ~70-95 KB/day**, against today's comments at ~2.1 KB/h (15 × ~140 B). The idle server
line goes from ~2.1 to ~5-6 KB/h on a probe-free front (~0.13 MB/day); design 95 measured 0.15-0.2 MB/day for the
whole line, so about +50-60 %. IPv6 adds 20 B per packet. E4 measures it on the emulator (§8).

**Wakeups and battery.** No new alarm, timer or radio promotion. The CPU stays up a little longer after each comment:
the POST is sent from the reader's callback under a partial wake lock released on the 204 or after 10 s, typically one
RTT (0.1-1 s). 15 times an hour.

**On a cleartext tailnet URL** (`http://100.x:3101`) the stream is HTTP/1.1 today, so the answer goes on a second,
pooled keep-alive connection (OkHttp keeps it 5 min, the answer comes every 4). Text headers and WireGuard (+~60 B per
packet) make it ~500-600 B a time, ~8-9 KB/h; more if either side closes the idle connection between answers (a new
TCP handshake each time). E4 measures it; if the connection doesn't survive 240 s, the stream client uses HTTP/2 with
prior knowledge on a `http://` tailnet URL (axum's server speaks it), so the answer shares the stream's connection as
on the TLS path.

## 3. Measured for this note (this VM, Linux 6.12, 2026-11-19)

Scratch programs in the session's scratchpad, not committed. A server socket in a user network namespace, its client
in a child namespace over a veth pair. "Vanish" = a blackhole route to the client, so packets are dropped silently, as
for a phone in airplane mode. RTO is the 200 ms floor here.

| Case | Result |
|---|---|
| `TCP_USER_TIMEOUT` 60 s, one 3-byte write into the dead peer | `ETIMEDOUT` after 60.3 s |
| `tcp_retries2` = 8 (namespace-local), one write | `ETIMEDOUT` after 107.7 s |
| `tcp_retries2` = 7, one write | `ETIMEDOUT` after 54.4 s |
| Go's/Caddy's keepalive (15/15/9), nothing written after the vanish | `ETIMEDOUT` after 149.9 s |
| Keepalive (15/15/9), one write after the vanish, `tcp_retries2` = 15 | still open at 700 s (stopped there; the floor is 924.6 s) |
| Healthy idle connection, keepalive idle 15 s: probes in 121 s | 8 (one every 15 s, each answered) |
| Healthy, keepalive idle 300 s, a 3-byte write every 60 s: probes in 360 s | 0 (each write resets the idle time) |
| axum 0.8.9 SSE, keepalive 240 s, the client closes (FIN) | the response dropped after 0 s (the guard's `Drop` ran at once) |
| The same, the client half-closes (shutdown write) | dropped at once |

## 4. The design

### 4.1 Overview

1. The server writes its own keepalive comment and notes when (§4.2).
2. The phone answers each comment with `POST /api/devices/alive` (§4.2).
3. A comment unanswered for 30 s counts as the stream's close for the status (§4.3), which counts every window from
   the close.
4. Flight mode and "Ingen data" abroad are grey states of their own (§4.3, §4.4).
5. An optional push (§4.5).
6. Optional front tuning in DEPLOY.md (§4.6).

### 4.2 The answer (U1)

**Server.**

- `commands_stream` replaces axum's `KeepAlive` with its own: a `tokio::time::interval` of `SSE_KEEPALIVE_SECS`
  merged into the event stream, writing the same comment bytes as today. The stream's entry in `streams` (`OpenStream`)
  gains `comment_at` (when the last comment was handed to the body) and `answers` (whether the phone said it
  answers).
- The phone says it answers with `?answers=1` on the stream request. The server ships before the launcher, so a stream
  without it is trusted as today (open = reachable). No backwards compatibility on the phone side is needed beyond
  that one flag.
- New route `POST /api/devices/alive`: authenticated like every device route (so `record_access` notes it), empty
  body, `204`, no database write. Its route class is the light one (the same rate limit as the policy GET).
- Pure `streams::stream_silent(stream, last_access, now) -> Option<DateTime<Utc>>`: for a stream that answers, `Some(at)`
  when its last comment went at `at - 30 s` and no request from the phone reached the server since (`last_access <
  comment_at`) and now is past `at`. Any request counts, not only the answer: a sync that happened anyway is as good.
  `ANSWER_WINDOW_SECS = 30`, beside `SSE_KEEPALIVE_SECS`.
- The server does **not** close a silent stream: if the phone is alive and only the answer was lost, its stream and its
  nudges keep working, and the next answer makes it Online again. A dead stream is closed by its holder in time, or
  replaced by the phone's next stream (`MAX_PER_DEVICE`).

**Launcher.**

- `CommandListenerService` opens the stream with `?answers=1`. When the reader sees a comment (it already does, for its
  300 s read timeout), it posts `/api/devices/alive` with the same `OkHttpClient` and on the same `Network` as the
  stream, so it rides the stream's pooled connection. A partial wake lock is held from the comment until the 204 or 10
  s. No retry: the next comment is the next chance.
- Not sent while the stream is held for flight mode (§4.4), and not while the stream isn't open.
- Pure part: `answerFor(line)` (a comment line, not an event), rows in the stream's reader test.

**Detection.** The next comment goes 0-240 s after the vanish; 30 s later the stream is silent. **0.5-4.5 min, ~2.5
min on average**, the same on every setup. A front that closes the connection sooner (§4.6) only makes it sooner.

### 4.3 The status rule (server, pure)

**The stream's close.** For the status a stream ends at the earliest of its real close (`StreamState.since` with open
= 0) and the moment it went silent (§4.2). A stream that answers again is open again.

**Reach** (`streams::reach`, pure): when the server last knew the phone was reachable:

- a stream open and not silent: now (Online, as today, while the last contact is at most 60 min old; a stream that
  doesn't answer and is older than that is not trusted, and reach is the last contact);
- no stream open, one ended since the server started: max(last contact, that end);
- no stream seen since the server started, and the last contact within 60 min before the start: the start (it may have
  held a stream when the server stopped; QA L4);
- otherwise the last contact.

`ConnectionInputs` gains `started` (the server's start) and the silent time.

**The states**, with reach in place of the last contact for the windows (About This Phone's Connection row and the dot
alike):

- **Online:** a stream open and not silent, last contact at most 60 min old. Green.
- **Away:** no live stream, reach at most 45 min ago. Green for the first **1 minute** after reach (a network change,
  a proxy reload, a lost answer: the phone reconnects or answers in seconds), then orange "Ingen kontakt · sist sett
  for 4 min siden" / "No contact · last seen 4 min ago". `NO_CONTACT_AFTER_MINUTES` 10 -> 1, counted from reach. After
  a server start the first wait is **6 min** (`RESTART_GRACE_MINUTES`: phones reconnect within their 300 s read timeout
  after a crash, in seconds after a clean stop); a phone that was orange before the restart may read green for those
  minutes - rare, and said here.
- **Offline:** reach more than 45 min ago. Today's grey Offline line.
- "Sist sett" always stays the last contact (the last request or report), never reach.

The 1-minute wait covers every path, and the windows count from the end of the stream, so a close never flashes grey
Offline for an idle phone whose last sync is 50 min old (QA M1).

**Overrides** (in this order; known states the phone or the parent chose: grey, never orange, never pushed):

1. **Turned off** (`offs_now`): grey, as today.
2. **Flight mode.** `airplane_mode::on_since` keeps its 10-min expiry for a `going_on` report only while the phone is
   in touch. New `airplane_mode::held_since` (pure): the latest report says `going_on` or `on`, **no live stream, and
   nothing has reached the server since** (last contact ≤ the report's `reported_at` + 5 s: no request, no report, no
   stream opened after it). Then grey "Flymodus på siden 21:03" / "Airplane mode since 21:03", at once and **for as
   long as that holds** - all night if need be. A failed turn-on reports at once (`airplane_failed`), so it is in touch
   and reads normally. With Wi-Fi kept the phone reconnects in seconds and is green with the airplane words on the
   second line, as today. A battery that dies in flight mode keeps the words: the last thing the phone said.
3. **"Ingen data" abroad** (design 97): the latest report's `data_roaming.where` is `abroad`, the server's stored
   `abroad` choice is `off` (Ingen data) with no lift in force, and no live stream. After the 1-minute wait, grey "I
   utlandet · ingen data" / "Abroad · no data", for as long as that holds. It needs a report that said abroad (Wi-Fi
   at the hotel, or any report that got out while abroad). A border crossed on mobile data alone cuts the data before
   a report can say so; that phone reads "Ingen kontakt" until its next report - the server can't know better.

Pages are rendered at load; a parent sees the new state on the next load or return to the page (as today).

### 4.4 Flight mode on the phone

**Vibb's own switch** (design 98 §2.2). New step 2a, **before** step 2's awaited `going_on` POST, when airplane mode
will take the stream's network (`streamNetwork` has `TRANSPORT_CELLULAR`, or it is Wi-Fi and `wifi_kept` isn't
`true`):

- `CommandListenerService.closeForAirplane()` cancels the stream call: one `RST_STREAM` (~90 B with TLS and TCP/IP) on
  the pooled HTTP/2 connection, which is **not** evicted (a new connection would cost a handshake and time before the
  radios go). Then step 2's POST rides the same connection: TCP order makes its 2xx proof that the front got the close.
  The front cancels the upstream request, and the server sees the close at once. On a cleartext HTTP/1.1 tailnet URL
  the cancel closes the stream's own connection (a FIN, the server's own socket): the same result.
- It holds the reconnect and the answers until the route ends, and the close does **not** take the up->down edge's 30
  s reconnect wake lock: no new battery cost. The route's `finally` releases the hold: on success the network callback
  reconnects when a network is there (Wi-Fi kept); on a failure `checkStream` reconnects at once.
- If the POST fails, the route goes on as today; the server learns by the missing answer (§4.2), and the dot is orange,
  not "Flymodus" (no report got out).
- Cost: ~140 B once per turn-on. Pure part: `airplaneClosesStream(transport, wifiKept)`, rows in `AirplaneRouteTest`.

**Android's own toggle** (Quick Settings, only reachable in a setup window under lock task): no `going_on` report goes
on this path. The missing answer turns the dot orange within minutes; no receiver is added (revision 1's race-prone
close on `ACTION_AIRPLANE_MODE_CHANGED` is dropped: the answer covers it on every Android version).

### 4.5 The optional push "Ingen kontakt" (server)

- `parent_events::Kind::NoContact`, switch `push_no_contact`, **off by default** (as `push_app_updates`: a migration
  with `DEFAULT 0`, its number taken at build time - 0159 is merged and parallel rounds may take the next;
  `default_on` false). On the notification settings page: "Ingen kontakt" / "No contact", behind its (i): "Når en
  telefon har vært uten kontakt i 30 min. Ikke om natten." / "When a phone has been out of contact for 30 min. Not at
  night."
- **The outage** is defined over its whole length: the phone is not Online (Away or Offline, §4.3), starting at reach.
  It ends with any contact (a request, a report, a live stream).
- **Raised** by a server sweep once a minute (`streams::run_no_contact_sweep`, a tokio task like the music sweep) when
  the outage has lasted **30 min**, whether the phone is Away or Offline by then. A stream that doesn't answer (an old
  launcher) and whose last contact is over 60 min old is Offline, so it is raised then.
- **Once per outage, across restarts:** not raised when a `NoContact` event for that phone was already raised after
  its last contact. The events and `devices.last_seen_at` are persisted, and a restart can only move the last contact
  back (the in-memory last request is lost), so a restart that moves reach to the server's start never repeats it.
- **Never at night:** the sweep raises only when `logins::night_push` (the family zone's 23:00-07:00) says `Now`. At
  07:00 it raises if the outage is still on - never a held push for a phone that came back.
- **Not raised** for a phone turned off (`offs_now`), in flight mode (§4.3 override 2), abroad with "Ingen data"
  (override 3), not set up, without access, or in the server's first 6 min (`RESTART_GRACE_MINUTES`).
- Words, made at raise: title "Ingen kontakt med Emma" / "No contact with Emma", summary "Sist sett 14:02" / "Last
  seen 14:02". Opens the phone's summary.
- No time rule cuts the network today (`time_rules.rs` has none). A future one must add itself to the "not raised"
  list.

### 4.6 Optional: tune the front (DEPLOY.md)

Nothing in §4.2-§4.5 needs this. It is for an owner who wants fewer bytes on the phone, or a dead connection freed
sooner. Each step is the owner's own, as `write_idle` was; nothing here is a host action from the PWA.

**Probes cost the phone more than the answer.** A front built on Go's net package (Caddy, caddy-l4, Traefik) sends a
TCP probe every 15 s on an idle connection: ~23 KB/h and ~225 radio wakeups/h per phone, six times the answer's cost.
nginx and HAProxy send none by default; the server on the tailnet sends none.

**The user's setup: caddy-l4 on the Oracle Ampere VPS.**

- caddy-l4 has no keepalive option toward the phone (an empty `net.ListenConfig{}`, as the QA read it; the build
  re-checks the installed version's module docs) and dials home with Go's defaults (probes on the VPS-home link, which
  cost the VPS and the home line, never the phone). The home Caddy's peer is the VPS; it sees the phone's address only
  in the PROXY header, and nothing set there reaches the phone.
- **The step worth taking: swap caddy-l4 for nginx `stream`** (no probes by default; `proxy_timeout` 10 min by
  default, longer than the 240 s comment), keeping the PROXY protocol:

  ```
  stream {                      # Debian/Ubuntu: the stream module is libnginx-mod-stream
      server {
          listen 443;           # and [::]:443 if phones reach the VPS over IPv6
          proxy_pass <home address>:<home port>;
          proxy_protocol on;    # v1; the home Caddy's proxy_protocol wrapper reads v1 and v2
      }
  }
  ```

  HAProxy works as well (`mode tcp`, `send-proxy-v2`, `timeout client 10m`, `timeout server 10m`; no `option
  clitcpka`). If caddy-l4 also routes other names by SNI, nginx's `ssl_preread` does the same. Saves ~23 KB/h and ~225
  wakeups/h per phone (~0.55 MB/day).
- If M3 then shows a carrier NAT dropping 240 s idle mappings (streams closing every few minutes): `listen 443
  so_keepalive=2m:15s:9;` (probes only in the comment's gaps, ~1.5 KB/h). Not a shorter comment.
- `net.ipv4.tcp_retries2 = 8` on the VPS (`/etc/sysctl.d/90-vibb.conf`, `sysctl --system`; it is a VM) frees a dead
  phone's connection after ~100 s instead of 15+ min. Optional: the answer already tells the server.

**Other fronts, short.**

| Front | Probes toward the phone | To drop them | Optional faster close |
|---|---|---|---|
| Caddy terminating the phones' TCP | 15 s | `keepalive_idle 5m` in the global `servers` block (not `keepalive_interval -1`, which probably falls back to Go's 15 s) | `tcp_retries2 = 8` in its namespace |
| nginx, HAProxy | none by default | - | the same |
| Traefik | 15 s (Go; as far as known no option for the accepted side) | swap, or accept | the same |
| Tailscale Funnel | Tailscale's | nothing to set | nothing to set |
| The server on the tailnet | none | - | `tcp_retries2 = 8` in the server's namespace |

`tcp_retries2` (8, not 7: 8 is RFC 1122's 100 s floor and lets a living phone ride out ~100 s of dead spot; 7 only
~50 s, and a dropped living phone stays deaf for its 300 s read timeout) lasts only if set in the right place: a VM's
`/etc/sysctl.d`; a Docker container's own namespace with `sysctls: [net.ipv4.tcp_retries2=8]` (refused on host
networking, where the host's value applies; a port published through `docker-proxy` is held in the host's namespace);
a Proxmox unprivileged LXC with `lxc.sysctl.net.ipv4.tcp_retries2: 8` in `/etc/pve/lxc/<id>.conf` on the host (a
`/etc/sysctl.d` file inside the container is skipped at boot: `systemd-sysctl` needs a writable `/proc/sys`). Check it
after a reboot.

`TCP_USER_TIMEOUT` on the server's sockets (revision 1's B) is not built: the answer covers the tailnet path too, and
on Linux 5.11+ it would also abort a download whose reader pauses over 100 s.

## 5. Data and wakeups, after

Per idle phone per hour, on the server line:

| Part | Today | After (any setup) | The user's setup, with the optional swap |
|---|---|---|---|
| SSE comments (3 B + h2 + TLS + TCP/IP, and the ACK) | 15 × ~140 B = 2.1 KB, 15 wakeups | unchanged | unchanged |
| The answers | - | 15 × ~190-260 B = ~2.9-3.9 KB, 0 new wakeups | the same |
| TCP probes from a Go front | ~23 KB, ~225 wakeups (Go fronts) | unchanged | 0 |
| Status rule, push | - | 0 (server only) | 0 |
| Airplane close | - | ~140 B per turn-on | the same |
| **Total, idle hour** | ~2.1 KB (probe-free) / ~25 KB (Go front) | ~5-6 KB / ~28-29 KB | **~5-6 KB** |
| Phone battery | - | ~0.1-1 s more CPU per comment under a wake lock; no new alarm or radio promotion | the same, 225 fewer radio wakeups/h |

## 6. Rejected, and why

- **U3 (a duplex request body) and U4 (WebSocket)** are a little cheaper per answer (~85-130 B) but need every front to
  stream request bodies or upgrade connections: nginx buffers request bodies by default and needs upgrade config, so
  they break the "any setup, no settings" rule.
- **U5 (HTTP/2 PING)** never reaches the server behind a proxy, and runs on its own timer.
- **E (a heartbeat on its own timer)** costs new wakeups for a slower answer.
- **D (180 s comments)** buys a minute for +0.7 KB/h and +5 wakeups/h.

## 7. Tests

**Server** (`cargo test`):

- `streams::stream_silent`: a comment 20 s ago and no request since (live); 40 s ago (silent, at comment + 30 s); 40 s
  ago with an answer 2 s after it (live); 40 s ago with a sync request instead (live); a stream without `answers=1`
  (never silent).
- The route: `POST /api/devices/alive` with a token answers 204 and moves `last_access`; without one 401; nothing
  written to the database.
- The keepalive: a stream's body yields the comment bytes every interval and sets `comment_at` (paused tokio time).
- `streams::reach` and `connection_state`: open and live, last contact 40 min (Online); silent 30 s ago with the last
  contact 4 min ago (green); silent 2 min ago (orange); open without answers, last contact 70 min (Offline); closed 30
  s ago with the last contact 50 min ago (green, not grey); closed 2 min ago with the last contact 50 min ago (orange,
  not grey); closed 50 min ago (grey Offline); no stream since a start 2 min ago, last contact 20 min before the start
  (green, restart grace); the same 7 min after the start (orange); no stream, last contact 3 h before the start (grey
  Offline). "Sist sett" is the last contact in every row.
- Flight mode: `going_on` 3 h ago, no contact since, no stream (grey, "Flymodus på siden", no 1-min wait); `on` 3 h ago,
  the same (grey); `going_on` 15 min ago with a stream opened since (the normal state); `going_on` 2 min ago with a
  live stream (green, airplane words on the second line); `going_on` then a request 20 s later (normal state).
- Abroad: the last report abroad, `abroad` = `off`, no stream, ended 2 min ago (grey "I utlandet · ingen data"); 30 s
  after (green); `abroad` = `vibb` (orange); a lift in force (orange); the last report home (orange).
- The sweep: raised 30 min into an outage, Away; raised for a phone whose last sync was 25 min before it vanished
  (Offline by then); raised for a stream without answers and a last contact 70 min ago; once per outage; not again
  after a restart; again after a new contact and a new outage; not at 23:30 but at 07:00 if still out; not when back by
  07:00; not while off, in flight mode for 3 h, abroad with Ingen data, without a token, or in the server's first 6
  min; the switch off by default for a parent with no prefs row; the words in nb and en.

**Launcher** (`testDebugUnitTest`): `answerFor` rows (a comment, an event, an empty line); the stream URL carries
`answers=1`; no answer while held for flight mode; `airplaneClosesStream` rows (cellular, Wi-Fi kept, Wi-Fi not kept,
unknown); the close before the POST; no reconnect wake lock on the close; the hold released on success and on each
failure.

## 8. Measurements

**Emulator** (`docs/testing/emulator.md` §6bd, new): on `compat34` (Android 14) and `compat372` (Android 17), the
lowest and highest the gate touches (Doze's firewall for the answer, Android's airplane path); screenshots on
`jelly36`.

- **E1** - Vibb's switch with mobile data only: the server's "command stream closed" within 2 s of the turn-on and
  before the `going_on` report is answered; the PWA's dot grey with "Flymodus på siden", still grey 15 min later; with
  Wi-Fi kept, the stream back within ~5 s and the dot green.
- **E2** - The vanish: an `nft` rule on the host that silently drops the emulator's packets to and from the server's
  port, set right after a comment; the server's log
  says the stream went silent 30 s after the next comment; the dot orange a minute later, green on reconnect. With the
  phone in Doze (`dumpsys deviceidle force-idle`): every comment still answered for an hour (none silent).
- **E3** - `kill -STOP` the server for 2 min (§5b's tool): the phone's stream closes by its own read timeout as before;
  the dot green for a minute after, then orange, green on reconnect.
- **E4 - Bytes.** `tcpdump` on the emulator's link (or the host's), one idle hour through the TLS front: the answers'
  bytes per hour (expected ~2.9-3.9 KB) and no packet outside a comment's second. Once more on a cleartext tailnet URL:
  whether the answer's keep-alive connection survives 240 s (§2.1).

**On the user's setup (optional, for §4.6 only):**

- **M0/M1 - the VPS holds the phones' TCP and probes every 15 s.** On the VPS: `caddy version`, then `sudo ss -tnpo
  state established '( sport = :443 )'` twice, 5 s apart: carrier peers, the `caddy` process, and
  `timer:(keepalive,Ns,0)` with N at most 15 and starting over. (In a container, from its host: `sudo nsenter -t
  "$(docker inspect -f '{{.State.Pid}}' <name>)" -n ss -tnpo ...`, since the official Caddy image has no `ss`; on the
  home LXC, `pct exec <id> -- ss -tn ...` from the Proxmox host shows only the VPS as peer.)
- **M2 - detection end to end, after the build.** On the server's host, `docker logs -f --since 1m <server> 2>&1 |
  grep --line-buffered "command stream"` (the build logs "went silent" too); the kid's phone on mobile data alone;
  pull it out of coverage or let its battery go, note the time. Expected: "went silent" 0.5-4.5 min later.
- **M3 - after the swap.** M1's command shows no keepalive timer between comments; next morning `docker logs --since
  12h <server> 2>&1 | grep -c "command stream closed"` per phone, a night on Wi-Fi vs one on mobile: near 12 an hour
  for one phone means a carrier NAT under 240 s (then `so_keepalive=2m:15s:9`, §4.6).
- **M4 - Data.** Vibb One's mobile data for a day in Android's Settings before and after the build, and after the
  swap. Expected: +~0.1 MB/day for the answers; -~0.55 MB/day for the swap.

## 9. Build steps and sizes

1. Server: §4.2's keepalive, `?answers=1`, `/api/devices/alive`, `stream_silent` - S.
2. Server: §4.3 (`reach`, the windows, the restart grace, flight mode and abroad) - S-M.
3. Server: §4.5 (kind, migration, sweep, prefs row, i18n) - S-M.
4. Launcher: §4.2's answer and §4.4 - S. No contract file: `?answers=1` and the bodiless 204 carry no JSON. The
   server ships first (a stream without the flag is trusted as today).
5. DEPLOY.md (§4.6, optional) - docs, S.

## 10. Questions for the user

- **Q0. Which front does the server have today?** Answered 2026-11-19: caddy-l4 on the Oracle Ampere VPS forwards raw
  TCP with the real address; TLS ends at the home Caddy. It now matters only for the optional §4.6. M1 (`sudo ss -tnpo
  state established '( sport = :443 )'` on the VPS) shows the probes. **Recommendation: run M1 once, to see the probes
  for yourself.**
- **Q1. The phone answers the server's keepalive: every comment, or every second?** Every comment: ~3-4 KB/h more,
  0 new wakeups, the dot leaves green 1.5-5.5 min after a vanish on every setup. Every second: ~1.5-2 KB/h, up to ~9.5
  min. **Recommendation: every comment.** It is the only way that works on any setup without settings, and it costs
  about what the comments already cost.
- **Q2. Flight mode and "Ingen data" abroad: grey for as long as they last, never pushed?** Flight mode: grey
  "Flymodus på siden 21:03" while the last report says flight mode and nothing has reached the server since. Abroad
  with Ingen data: grey "I utlandet · ingen data" once a report said abroad. **Recommendation: yes to both:** states
  the kid or the parent chose, not faults.
- **Q3. The push: off by default, 30 min into an outage (Away or Offline), once per outage (also across a server
  restart), never 23:00-07:00 (raised at 07:00 if still out)?** A trip abroad with Ingen data on mobile data alone
  can't tell the server it crossed, so it reads "Ingen kontakt" and pushes once if the switch is on.
  **Recommendation: build it, 30 min fixed.**
- **Q4. The optional swap on your VPS: caddy-l4 -> nginx `stream` with the PROXY protocol (§4.6)?** Saves ~23 KB/h and
  ~225 radio wakeups/h per phone, six times what the answer costs; nothing else depends on it. **Recommendation: yes,
  when convenient** - it is the larger data saving of the two. The `tcp_retries2` line is optional.
- **Q5. The keepalive stays at 240 s.** 180 s would cut the worst case by a minute for +0.7 KB/h and +5 wakeups/h (and
  +5 answers). **Recommendation: keep 240 s.**
