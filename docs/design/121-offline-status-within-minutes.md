# 121 - The phone's status leaves green within minutes of lost contact

Status: design, revision 1, 2026-11-19 (branch `design-121`). Docs only, no code in this round.

The user (2026-11-19): "burde telefonens status i det minste være oppdatert til ikke grønn før det har gått en time
når server mister kontakt med den" - flight mode as much as lost coverage; a push "hadde ikke trengt å nødvendigvis
... eller det måtte man kunne velge". And, firmly: "dette skal balanseres med at vi skal holde databruk mellom de to
til det minimale!"

Inputs: `server/src/streams.rs`, `phone_status.rs`, `handlers/device_api.rs` (`commands_stream`), `config.rs`,
`parent_events.rs`, `airplane_mode.rs`; the launcher's `CommandListenerService`, `push/SyncSchedule.kt`,
`assistant/AirplaneRoute.kt`; designs 19, 19b, 31 §3.10, 95 §3, 98; `docs/notes/launcher/push-and-play.md`;
`server/DEPLOY.md` ("With your own Caddy"). Primary sources, read 2026-11-19: Caddy's global options page
(caddyserver.com/docs/caddyfile/options), Linux `tcp(7)` and `Documentation/networking/ip-sysctl.rst`, the kernel
commit "ipv4: Namespaceify tcp_retries2 sysctl knob" (2016, Linux 4.6), AOSP netd `SockDiag.cpp`. Measurements made
for this note are in §3.

## 0. Verdict

- **The phone already tells us it is alive, for free.** Every SSE keepalive comment (every 240 s) is answered by the
  phone's kernel with a TCP ACK. Whoever holds the phone's TCP connection can turn "no ACK" into "gone" within a minute
  or two. No new byte and no new wakeup on the phone.
- **On the user's setup that holder is Caddy, not the server.** The server's own socket options can't see the phone
  through it. So the main fix is two lines of host and Caddy config (DEPLOY.md), and the server only acts on what it
  already sees: the stream closing.
- **Caddy's defaults probably cost the phone far more than our whole server line.** Caddy sends a TCP keepalive probe
  every 15 s on an idle connection (its documented default). On the phone's stream that is about 225 probes an hour,
  ~23 KB/h and 225 radio wakeups/h, against 15 comments (~2 KB/h). If M1 confirms it, the same config change that
  gives fast detection removes that cost.
- With the change: the stream closes on the server 1.7-6.5 min after the phone vanishes (about 4 min on average), and the
  dot leaves green 1 min after that (the 10-min grace of 2026-11-01 becomes 1 min, counted from the stream's close). Today: about 2.5 min in a third of the cases, 15-20 min in the rest, plus up to 10
  min of green after that.
- Vibb's own flight-mode switch closes the stream before the radios go: the dot goes grey with "Flymodus på siden
  21:03" at once.
- An optional push "Ingen kontakt med Emma" after 30 min, off by default, never at night or in a known quiet state.
- Unproven until measured on the user's setup: that Caddy probes every 15 s today (M1), and that Caddy closes the
  upstream stream at once when it drops a phone (M2). Three to four commands settle both.

## 1. What happens today

### 1.1 The server's states

- `streams::connection_state`: **Online** = a stream is open and the last contact is at most 60 min old
  (`ONLINE_WINDOW_MINUTES`). **Away** = no stream, last contact at most 45 min old (`AWAY_WINDOW_MINUTES`). Older is
  **Offline**. Last contact = the newest of the last authenticated request, the last report and an open stream's
  `since`.
- `phone_status::dot`: green for Online, **and for Away under 10 min** (`NO_CONTACT_AFTER_MINUTES`, the user's pick of
  2026-11-01: "a sleeping phone drops its stream for a moment all the time"). Orange "Ingen kontakt · sist sett for 12
  min siden" from 10 min. Grey for Offline or turned off.
- The stream counts as open until its response is dropped. Measured (§3): hyper drops an SSE response **at once** when
  the peer's FIN arrives, even with 240 s to the next keepalive. So the server learns at once when its peer closes.

### 1.2 Who holds the phone's TCP

The user's phones talk to `https://<public name>` (DEPLOY.md option (c)): **Caddy 2.11.6** on another host
(`TRUSTED_PROXIES=10.73.10.81`) terminates TLS and HTTP/2 and opens its own HTTP/1.1 connection per stream to the
server's phone listener. The server's socket is to Caddy, over the LAN, and never dies. Only Caddy's socket to the
phone can notice the phone is gone. When it does, Caddy cancels the upstream request and closes that connection, and
the server drops the stream at once (§1.1; to confirm end to end, M2).

### 1.3 How a vanished phone looks to Caddy

Airplane mode, a dead battery, lost coverage: no FIN or RST reaches the server. On Android, netd destroys the sockets
of a removed address with `SOCK_DESTROY` (`SockDiag::destroySockets`), and the kernel's `tcp_abort` tries a RST, but
by then the address and the route are gone. So the server side is told nothing.

Caddy's TCP keepalive (documented defaults: `keepalive_idle` 15s, `keepalive_interval` 15s, `keepalive_count` 9):

- **On a healthy stream** a probe goes after every 15 s without a received packet. The phone's kernel answers each.
  Between two 240 s comments that is ~15 probes: **~225 probes an hour**, each 52 B out and 52 B back (IPv4 with
  timestamps; 72 B each on IPv6): **~23 KB/h, ~0.56 MB/day, and 225 incoming packets an hour that wake the phone's
  radio**. Design 95 measured 0.15-0.2 MB/day for the whole server line after its changes. It measured through a plain
  TCP proxy, not Caddy, so it couldn't see these. Not measured on the user's Caddy yet: M1.
- **When the phone vanishes** and no comment goes in the next ~150 s, the probes kill the connection at ~150 s
  (measured, §3). But when a comment is written in that window (240 s period, so ~60 % of the time), Linux stops
  probing while data is unacknowledged, and the retransmission timer rules: `tcp_retries2` = 15, at least **924.6 s**
  (ip-sysctl.rst), measured still alive at 700 s (§3).
- Caddy's `write_idle` (raised to 5m in DEPLOY.md) doesn't help: a 3-byte write into a dead connection fits the send
  buffer and never stalls.

So today a vanished phone reads green for ~2.5 min + 10 min in ~40 % of cases, and ~15-20 min + 10 min in the rest,
capped by the 60-min Online window.

### 1.4 The phone side, Android 14-17

- The anchor (`CommandListenerService`, a `specialUse` FGS) passes Doze's network firewall (design 19b §0, AOSP
  14-16), and the ACK is the kernel's, sent without our process running. So a sleeping, healthy phone answers every
  comment and every probe.
- If the anchor isn't running, Doze drops incoming packets for our UID. The comment then goes unanswered and the phone
  reads as gone - true, since the server can't nudge it either.
- The launcher already reconnects on a default-network change (1.5 s settle), retries 5 s doubling under a 30 s wake
  lock, and reads a stream silent for 300 s as dead. None of that changes here.

## 2. Options, with their cost per hour on the phone

| Option | Phone bytes/h | Phone wakeups/h | Vanished -> stream closed | Where |
|---|---|---|---|---|
| Today (Caddy defaults) | ~2.1 KB comments + ~23 KB probes (M1) | 15 + ~225 | 2.5 or 15-20 min | - |
| **A. Caddy: `keepalive_idle 5m` + `tcp_retries2 = 8` in Caddy's network namespace** | **~2.1 KB** (probes gone) | **15** | **1.7-6.5 min, ~4 min mean** | DEPLOY.md, the user's host |
| A with `tcp_retries2 = 7` | ~2.1 KB | 15 | 0.9-5.3 min, ~3 min mean | same |
| B. `TCP_USER_TIMEOUT` (100 s) on the server's accepted sockets | 0 | 0 | as A, but only where the server holds the phone's TCP (tailnet, no proxy) | server |
| C. The phone echoes each comment (a tiny POST on the stream's connection) | +~4.6 KB (~310 B each) | 0 new (the radio is up for the comment) | 0.5-4.5 min | both; fallback only |
| D. `SSE_KEEPALIVE_SECS=180` | +0.7 KB | +5 | worst case 1 min shorter | `.env` |
| E. A phone heartbeat every 5 min | +~6-10 KB | +12 | ~5 min | rejected |

Detection time for A: the next comment comes 0-240 s after the phone vanishes, and Caddy's kernel gives it up
102.2 s after its first send with a 200 ms RTO (measured 107.7 s, §3). A mobile RTT gives a larger RTO, so the kill
falls on a later retransmission: ~102-153 s. Mean ≈ 120 + 110 s.

Why 8 and not 7: `tcp_retries2` also decides how long a *living* phone may go without any ACK before Caddy drops it
(a tunnel, a lift). Linux sends no RST when it gives up, so a phone dropped that way stays deaf until its own 300 s
read timeout. That costs a reconnect (~2.5 KB: resumed TLS, the stream request, the reopen sync) and up to 5 min of
lost nudges. 8 is RFC 1122's floor (100 s, ip-sysctl.rst) and tolerates an outage of ~100 s; 7 tolerates ~50 s.

**Chosen: A (with 8) + B + the status rule (§4.3) + the airplane close (§4.4) + the optional push (§4.5).** Zero new
bytes per hour; A removes ~23 KB/h if M1 confirms the probes. C stays written down as the fallback for a proxy that
can't be tuned (Q4).

## 3. Measured for this note (this VM, Linux 6.12, 2026-11-19)

Scratch programs in the session's scratchpad, not committed. A server socket in a user network namespace, its client
in a child namespace over a veth pair. "Vanish" = a blackhole route to the client, so the server's packets are
dropped silently, as for a phone in airplane mode. RTO is the 200 ms floor here.

| Case | Result |
|---|---|
| `TCP_USER_TIMEOUT` 60 s, one 3-byte write into the dead peer | `ETIMEDOUT` after 60.3 s |
| `tcp_retries2` = 8 (namespace-local), one write | `ETIMEDOUT` after 107.7 s |
| `tcp_retries2` = 7, one write | `ETIMEDOUT` after 54.4 s |
| Caddy's keepalive (15/15/9), nothing written after the vanish | `ETIMEDOUT` after 149.9 s |
| Caddy's keepalive (15/15/9), one write after the vanish, `tcp_retries2` = 15 | still open at 700 s (stopped there; the floor is 924.6 s) |
| Healthy idle connection, keepalive idle 15 s: probes in 121 s | 8 (one every 15 s, each answered) |
| Healthy, keepalive idle 300 s, a 3-byte write every 60 s: probes in 360 s | 0 (each write resets the idle time) |
| axum 0.8.9 SSE, keepalive 240 s, the client closes (FIN) | the response dropped after 0 s (the guard's `Drop` ran at once) |
| The same, the client half-closes (shutdown write) | dropped at once |

Also: `tcp_retries2` is writable inside an unprivileged user + network namespace (it is per namespace since Linux
4.6), so it can be set for a Caddy container or LXC alone, without touching the host.

## 4. The design

### 4.1 Caddy (DEPLOY.md, "With your own Caddy" and "Phones' long-lived connection through a proxy")

Two changes, both the user's to make on their host:

1. In the global `servers` block, next to `write_idle`:

   ```
   servers {
   	timeouts { ... }
   	# The phones' stream ACKs a keepalive every 4 minutes; Caddy's 15 s TCP probes are pure cost on it.
   	keepalive_idle 5m
   }
   ```

   Linux counts the idle time from the last packet received, and the phone's ACK of each comment resets it. So with
   5m no probe goes on a healthy stream. Other idle connections are still closed by Caddy's `idle` (5 min).
2. In the network namespace Caddy runs in: `net.ipv4.tcp_retries2 = 8`. On a host or VM, `/etc/sysctl.d/90-caddy.conf`
   and `sysctl --system`; in Docker, `sysctls: [net.ipv4.tcp_retries2=8]` in Caddy's compose service; in an LXC,
   inside the container (per-namespace, measured §3). It changes every TCP connection in that namespace: a client
   with no ACK for ~100 s is dropped instead of after 15+ min.

DEPLOY.md says why (fast "no contact" and no probes), and that any other proxy needs the same two things: no TCP
probes shorter than the keepalive, and a retransmission give-up of about 100 s (nginx: the same sysctl;
`so_keepalive` off, as it is by default). Without them everything still works; the status is just as slow as today.

A carrier NAT that drops a mapping idle for less than 240 s was hidden by Caddy's probes until now. Then every phone's
stream dies silently every few minutes and reconnects after 300 s. M3 checks the night's log for that. The way out is
`keepalive_idle 2m` (probes only in the comment's gaps: +15 probes/h, ~1.5 KB/h), not a shorter comment.

### 4.2 The server: `TCP_USER_TIMEOUT` on accepted sockets (B)

- Both listeners (`main.rs`: the admin listener and the device listener, which may serve the device API alone) get
  `ListenerExt::tap_io` (axum 0.8.9 has it) and set `TCP_USER_TIMEOUT` = **100 s** on each accepted `TcpStream` with
  `socket2::SockRef::set_tcp_user_timeout` (socket2 0.6.5 is already in `Cargo.lock`; a direct dependency with the
  `all` feature; Linux only, `#[cfg(target_os = "linux")]`). A failure is logged once, never fatal.
- No `SO_KEEPALIVE`. The comment is the only probe, so the timeout costs nothing on the wire.
- It matters where the server's socket *is* the phone's: a phone on the tailnet URL through the host's tailscaled.
  Behind Caddy it applies to Caddy's LAN connection and changes nothing.
- A const `PHONE_TCP_USER_TIMEOUT_SECS = 100` in `streams.rs`, beside the other windows, with the comment that ties it
  to `SSE_KEEPALIVE_SECS`. No `.env` knob.

### 4.3 The status rule (server, pure)

- `streams::StreamState` already keeps `since` = when the last stream closed (open = 0).
- `phone_status::no_contact` becomes: Away, **and** the last contact at least **1 min** ago, **and** either no
  stream seen since the server started or the last stream closed at least 1 min ago. `NO_CONTACT_AFTER_MINUTES` 10 ->
  1. A phone that reconnects within seconds (a network change, a server restart) never shows it; a stream the proxy
  killed (the phone silent for ~100 s already) shows it a minute later. This replaces the user's 10-min pick of
  2026-11-01; its reason (a stream dropped for a moment) is covered by counting from the close.
- `ConnectionInputs` gains nothing: `stream` already carries the close time. `connection_state` is unchanged; the
  60-min Online window stays as the floor for a proxy that isn't tuned.
- The words stay: "Ingen kontakt · sist sett for 4 min siden" / "No contact · last seen 4 min ago", orange.
  "Sist sett" stays the last request or report: never a time the server only guessed.
- **Airplane** (`dot` gains the airplane input): while `airplane_mode::on_since` is set and no stream is open, the dot
  is **grey** and the line is "Flymodus på siden 21:03" at once, without the 1-min wait: the phone told us, and it is
  the kid's choice, not a fault (CLAUDE.md, "Words in the UI"). With Wi-Fi kept the phone reconnects in seconds and is
  green with the airplane words on the second line, as today.
- Pages are rendered at load; a parent sees the new state on the next load or return to the page (as today).

### 4.4 Flight mode

**Vibb's own switch** (design 98 §2.2). Step 2 already posts the `going_on` report before the radios go; on HTTP/2 it
rides the stream's connection. New step 2b, right after it:

- If airplane mode will take the stream's network (`streamNetwork` has `TRANSPORT_CELLULAR`, or it is Wi-Fi and
  `wifi_kept` isn't `true`), `CommandListenerService.closeForAirplane()` cancels the stream. On HTTP/2 that is one
  `RST_STREAM` (~90 B with TLS and TCP/IP, and Caddy's ACK): Caddy cancels the upstream, and the server sees the
  close at once.
- It holds the reconnect until the route ends. The route's `finally` releases it: on success the network callback
  reconnects when a network is there (Wi-Fi kept); on a failure `checkStream` reconnects at once.
- Cost: ~140 B once per turn-on, nothing per hour. Pure part: `airplaneClosesStream(transport, wifiKept)`, rows in
  `AirplaneRouteTest`.

**Android's own toggle** (Quick Settings, only reachable in a setup window under lock task): the anchor's
`EdgeReceiver` gets `ACTION_AIRPLANE_MODE_CHANGED` after the setting is written, while the radios are still going
down. On `state = true` it calls the same `closeForAirplane()` (no hold). Whether the `RST_STREAM` gets out before the
link is gone is a race; E2 measures it. It costs nothing when it doesn't. If E2 never sees it arrive, it is dropped.

### 4.5 The optional push "Ingen kontakt" (server)

- `parent_events::Kind::NoContact`, switch `push_no_contact`, **off by default** (as `push_app_updates`, migration
  `0160_no_contact.sql`, `DEFAULT 0`, and `default_on` false). On the notification settings page: "Ingen kontakt" /
  "No contact", with behind its (i): "Når en telefon har vært uten kontakt i 30 min. Ikke om natten." / "When a phone
  has been out of contact for 30 min. Not at night."
- Raised by a server sweep once a minute (`streams::run_no_contact_sweep`, a tokio task like the music sweep): a phone
  whose no-contact (§4.3) has lasted **30 min**. Keyed by the period's start, so once per period; nothing when contact
  comes back.
- Not raised: 23:00-07:00 in the family's zone (at 07:00 the sweep raises it if contact is still lost - never a held
  push for a phone that came back); a phone turned off (`offs_now`), in airplane mode (`on_since`), abroad with the
  roaming lock or "quiet" (its last `data_roaming` says `locked` while roaming: no data on purpose, design 95 §4.3),
  not set up, or without access; the server up for less than 10 min (its stream state starts empty).
- Words, made at raise: title "Ingen kontakt med Emma" / "No contact with Emma", summary "Sist sett 14:02" / "Last
  seen 14:02". Opens the phone's summary.
- No time rule cuts the network today (`time_rules.rs` has none), so none needs an exception. A future one must add
  itself to the "not raised" list.

## 5. Data and wakeups, after

Per idle phone per hour, on the server line:

| Part | Before | After |
|---|---|---|
| SSE comments (3 B + h2 + TLS + TCP/IP, and the ACK) | 15 × ~140 B = 2.1 KB, 15 wakeups | unchanged |
| Caddy's TCP probes (if M1 confirms) | ~225 × 104 B = ~23 KB, ~225 wakeups | 0 |
| Detection (`tcp_retries2`, `TCP_USER_TIMEOUT`) | - | 0 (acts only on a dead peer) |
| Status rule, push | - | 0 (server only) |
| Airplane close | - | ~140 B per turn-on |
| Phone battery | - | no new wake lock, alarm or timer |

## 6. The fallback, if the proxy can't be tuned (C, not built)

The phone answers each comment with `POST /api/devices/alive` (empty body, 204) on the stream's connection. The
`LastByteInterceptor` already sees each body read, and the radio is already up for the comment, so no new wakeup.
About 310 B a time (HEADERS ~40 B + 9 B frame + 22 B TLS + 52 B TCP/IP up, the 204 back, and two ACKs): **~4.6 KB/h,
~110 KB/day**. The server writes its own keepalive (instead of axum's `KeepAlive`) so it knows when one went, and
counts a stream not answered within 30 s as not confirmed. Only if Q4 asks for it.

## 7. Tests

**Server** (`cargo test`):

- `streams`/`phone_status`: no-contact rows - closed 30 s ago with the last contact 20 min ago (green); closed 70 s
  ago (orange); no stream seen and the last contact 2 min ago (orange); open stream (green); Away 50 min (Offline,
  grey). Airplane rows: on, no stream (grey, airplane words, no 1-min wait); on with a stream (green, words on the
  second line); `going_on` older than 10 min and no stream (orange).
- The listener tap: a loopback connection through the tapped listener reads `TCP_USER_TIMEOUT` = 100 000 back
  (`getsockopt` through `SockRef`), Linux only.
- The sweep: raised at 30 min and once per period; not at 23:30 but at 07:00 if still out; not when back by 07:00;
  not while off, in airplane mode, roaming-locked, without a token, or in the server's first 10 min; the switch off
  by default for a parent with no prefs row; the words in nb and en.

**Launcher** (`testDebugUnitTest`): `airplaneClosesStream` rows (cellular, Wi-Fi kept, Wi-Fi not kept, unknown);
the route's hold released on success and on each failure.

## 8. Measurements

**On the user's setup** (a few commands, no code needed; before any build):

- **M1 - Does Caddy probe every 15 s?** On Caddy's host, in Caddy's namespace (`docker exec` or `pct enter` if it is a
  container): `caddy version` and `ss -tnoi state established '( sport = :443 )'`. Each phone stream shows
  `timer:(keepalive,Ns,0)`. N counting down from 15 again and again means the probes are on. `sysctl
  net.ipv4.tcp_retries2` gives today's value.
- **M2 - Today's detection, end to end.** Follow the server's log (`docker logs -f <server> 2>&1 | grep "command
  stream"`) and put the kid's phone in airplane mode (Vibb's switch, or Quick Settings in Åpne for oppsett). Note when
  "command stream closed" comes. Twice. Expected today: ~2.5 min or 15+ min.
- **M3 - After §4.1** (Caddy reloaded, the sysctl set): M1 shows `timer:(keepalive,4min…)` or no timer between
  comments; M2 three times gives 1.7-6.5 min. Next morning `docker logs --since 12h <server> 2>&1 | grep -c "command
  stream closed"` stays small: a count near 12 an hour for one phone means a carrier NAT under 240 s (§4.1).
- **M4 - Data.** Vibb One's mobile data use for a day in Android's Settings (App data usage), before §4.1 and after.
  Expected after: ~0.5 MB/day less if M1 saw the probes.

**Emulator** (`docs/testing/emulator.md` §6bd, new; jelly36, Android 16, and the API 34 image if one is installed -
the gate touches Android's airplane broadcast, the same on 14-17):

- **E1** - Vibb's switch with mobile data only: the server's "command stream closed" within 2 s of the turn-on; the
  PWA's dot grey with "Flymodus på siden"; with Wi-Fi kept, the stream back within ~5 s and the dot green.
- **E2** - Quick Settings' airplane mode in a setup window: does "command stream closed" come at once? Three runs.
  The receiver's close stays only if it does in most of them.
- **E3** - `kill -STOP` the server for 2 min (§5b's tool): the phone's stream closes by its own read timeout as
  before; the dot orange 1 min after the close, green on reconnect.

## 9. Build steps and sizes

1. DEPLOY.md and `Caddyfile.example` (§4.1) - docs, S. Can go first, alone; the user applies it after M1.
2. Server: §4.2, §4.3 and the airplane dot - S.
3. Server: §4.5 (kind, migration 0160, sweep, prefs row, i18n) - S-M.
4. Launcher: §4.4 - S. No contract file changes: no new key in any API body.

## 10. Questions for the user

- **Q1. The Caddy change (§4.1): `keepalive_idle 5m` and `tcp_retries2 = 8` in Caddy's namespace.** Recommended: yes,
  after M1. With 7 instead of 8 the close comes ~1 min sooner on average, but a living phone in a 50-100 s dead spot
  loses its stream for up to 5 min. Recommendation: 8.
- **Q2. Flight mode's dot: grey (like "turned off") or orange (like "no contact")?** Recommended: grey with "Flymodus
  på siden 21:03": it is a known state the kid chose, not a fault.
- **Q3. The push: build it (off by default, 30 min, never at night), and is 30 min right?** Recommended: build it with
  30 min fixed; a choice of minutes only if asked for.
- **Q4. If M1/M3 show the proxy can't be tuned: the phone's echo (§6, ~4.6 KB/h, no new wakeups)?** Recommended: no;
  then keep today's speed rather than pay bytes every hour.
- **Q5. The keepalive stays at 240 s.** 180 s would cut the worst case by a minute for +0.7 KB/h and +5 wakeups/h.
  Recommended: keep 240 s.
