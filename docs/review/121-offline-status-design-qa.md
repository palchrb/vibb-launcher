# Design QA - 121, the phone's status leaves green within minutes of lost contact

Reviewed: `docs/design/121-offline-status-within-minutes.md`, branch `design-121`, commit 652986918 (revision 1).
Read only, docs only (no cargo, Gradle or emulator). 2026-11-19.

Read: the root, server and launcher CLAUDE.md, `server/DEPLOY.md` (proxy sections), `docs/notes/launcher/push-and-play.md`,
`streams.rs`, `phone_status.rs`, `airplane_mode.rs`, `handlers/device_api.rs` (`commands_stream`), `config.rs`,
`parent_events.rs` (kinds, `offs_now`), `logins::night_push`, `assistant/AirplaneRoute.kt`, design 98 §1-2, the
`mobile_network.json` contract, axum 0.8.9's `ListenerExt::tap_io` and `connect_info.rs`, socket2 0.6.5. Outside
sources, read 2026-11-19: Caddy's global options page; Caddy's `modules/caddyhttp/server.go` (the three `KeepAlive*`
fields: "If zero, the default is 15s / 15s / 9") and `app.go` (`net.ListenConfig{KeepAliveConfig: {Enable:
KeepAliveInterval >= 0, Interval, Idle, Count}}`); caddy-l4's `layer4/app.go` (`addr.ListenAll(ctx, net.ListenConfig{})`)
and `modules/l4proxy/proxy.go` (`net.Dial`, a downstream EOF or error half-closes the upstream).

**Counts: 2 High, 5 Medium, 8 Low.**

## What holds

- **Caddy's 15 s probes are real and documented.** `keepalive_idle`, `keepalive_interval` (15 s each) and
  `keepalive_count` (9) are Caddy's documented defaults and are passed straight into Go's `net.KeepAliveConfig` on every
  accepted connection. Linux counts the idle time from the last packet received (the comment's ACK resets it), sends no
  probe while data is unacknowledged, and gives up by `tcp_retries2` then. §1.3's arithmetic (15 probes per 240 s gap,
  ~225/h, ~104 B each, ~23 KB/h) and §2's detection time (102.2 s model timeout at a 200 ms RTO base, later on a
  mobile RTO, plus 0-240 s to the next comment) are right.
- **`keepalive_idle 5m` is the right knob, and better than turning keepalive off.** Caddy turns keepalive off with a
  negative `keepalive_interval` (`Enable: false`), but as I read Go's `newTCPConn`, a `KeepAliveConfig` with `Enable`
  false *and* `ListenConfig.KeepAlive == 0` (Caddy leaves it at 0) falls back to Go's 15 s default. So `-1` would
  probably change nothing. DEPLOY.md should say "use `keepalive_idle`, not `keepalive_interval -1`", so nobody
  "simplifies" it later.
- **`tcp_retries2` is per network namespace** (Linux 4.6+, measured in §3). Docker accepts `net.*` sysctls for a
  container with its own network namespace. Asking the owner to set it fits "no host actions from the PWA": it is a
  DEPLOY.md step the owner takes, like `write_idle`.
- **`TCP_USER_TIMEOUT` (B) is buildable as written.** axum 0.8.9 has `ListenerExt::tap_io`, and `ConnectInfo<SocketAddr>`
  works through `TapIo` (`impl Connected<IncomingStream<TapIo<L, F>>> for L::Addr`). socket2 0.6.5 has
  `SockRef::set_tcp_user_timeout` behind `feature = "all"` on Linux, and the musl targets are Linux. It is silent on the
  wire, and it works with the 240 s keepalive: it only acts while data is unacknowledged.
- **Doze and the ACK.** The anchor is an FGS, so Doze's firewall lets its packets in, and the kernel ACKs without our
  process running (on the public path; for tsnet see L3). A phone without its anchor goes unanswered and reads as gone,
  which is honest: it can't be nudged either.
- **Bytes.** The comment is ~86 B down plus a 52 B ACK (≈140 B). The airplane close is ~140 B once per turn-on. C's
  ~310 B per echo is plausible. §5 adds nothing per hour. Q5's +0.7 KB/h and +5 wakeups/h are right.
- **The words.** "Flymodus på siden 21:03", "Ingen kontakt · sist sett for 4 min siden", and the setting "Ingen kontakt"
  with one (i) sentence all follow "Words in the UI". The push stays off by default ("a push only as a choice").

## High

### H1 - §1.2, §4.1, §8 M1: the box the design tunes may not be the box that holds the phones' TCP

**Problem.** The handoff of 2026-10-20 (the `write_idle` fix) records the user's path as: "the user's home Caddy is
2.11.6 (LXC; **an Oracle Caddy layer4 node forwards raw TCP to it**)". If that hop is still there:

- **The phone's TCP is held by caddy-l4 on the Oracle VPS, not by the home Caddy.** caddy-l4 listens with an empty
  `net.ListenConfig{}` (`layer4/app.go`). Go then gives every accepted phone connection its default 15 s/15 s/9
  keepalive, and caddy-l4 has no option to change it. So the ~225 probes/h reach every phone from the VPS.
- **§4.1 on the home Caddy changes nothing for the phone.** `keepalive_idle 5m` there only stops probes to the VPS.
  `tcp_retries2 = 8` in the home LXC only acts on its peer, the VPS, which never vanishes. Detection rests entirely on
  the VPS socket. When that socket dies, caddy-l4 half-closes upstream (`closeWrite`), the home Caddy reads EOF, and the
  server sees the close (so M2's end-to-end measure stays valid).
- **M1 run on the home Caddy would "confirm" 15 s timers that are probes to the VPS**, and the user would apply a
  change that does nothing.

The same applies to other front ends: Traefik (Go defaults, as far as I know no TCP keepalive option) and Tailscale
Funnel (the phone's TCP ends on Tailscale's ingress servers) can't be tuned at all. nginx (http or `stream`) can:
`so_keepalive` is off by default, plus the sysctl in its namespace.

**Fix.**
- Add **M0, "who holds the phones' TCP"**: on the home Caddy, `ss -tn state established '( sport = :443 )'`. If the
  peers are the VPS's address rather than carrier addresses, the VPS holds them. Then run M1 where the phones' peers
  are, and filter on a phone's address.
- Write §4.1 per holder:
  - **(a) Caddy terminates the phones' TCP:** as written.
  - **(b) caddy-l4 or another untunable L4 hop:** `tcp_retries2 = 8` in the VPS's namespace alone. That gives detection
    in ~1.7-2.5 min (probes or retransmissions, both ~100-150 s) at today's probe cost. Removing the probes means
    replacing the forwarder: HAProxy `mode tcp` (no `option clitcpka` by default) or nginx `stream` (no `so_keepalive`),
    with `send-proxy`/`proxy_protocol` if the client address is passed through today.
  - **(c) Traefik or Funnel:** today's probes and speed. Set the sysctl where you can.
- Re-ask Q1 with the holder known.
- The sentence "Caddy 2.11.6 on another host ... opens its own HTTP/1.1 connection" stays true for the second hop.

### H2 - §4.3, §4.5: flight mode turns orange after 10 min and then raises the push

**Problem.** `airplane_mode::on_since` reads a `going_on` report as "on" only for `GOING_ON_MS` (10 min). Only a
report with `on: true` keeps it longer, and a phone on mobile data alone sends none: it has no network. So with §4.3
and §4.5 as written:

- **The dot is wrong after 10 min.** It is grey "Flymodus på siden 21:03" for 10 minutes, then orange "Ingen kontakt"
  for the rest of the evening. The design's own test row ("`going_on` older than 10 min and no stream (orange)") bakes
  this in.
- **The push fires.** The airplane exclusion lapses at 10 min, and "Ingen kontakt med Emma" comes at 30 min, which is
  exactly what Q2 and §4.5 mean to prevent. A night in flight mode is the commonest case.

`GOING_ON_MS` exists for a turn-on that failed. Such a phone reports again at once (`airplane_failed`), so it is in
touch.

**Fix.** For the dot and the sweep, read flight mode as on while the latest report says `going_on` or `on` **and
nothing has reached the server since** (no request, no report, no stream opened after `reported_at` plus a few seconds'
slack). Keep the 10-minute expiry only for a phone that is in touch. Replace the test row with "`going_on` 3 h ago, no
contact since: grey, airplane words, no push", and keep a row for "`going_on` 15 min ago, a stream opened since:
normal state".

## Medium

### M1 - §0, §1.1, §4.3: the 10 minutes count from the last contact, not from the close, and Offline skips the new rule

**Problem.**
- **Today's numbers are overstated.** `no_contact` is `now - last_contact >= 10 min`, and last contact is the last
  request or report: a stream's close is never a contact. An idle phone's last sync is 0-52 min old (backstop 30 min,
  up to 1.75× late), so today it usually turns orange the moment its stream closes, or grey Offline when the last sync
  is over 45 min old. §0's and §1.3's "plus up to 10 min of green after that" holds only for a phone that was in touch
  in the last 10 min. Today's real delay is the TCP detection (15-20 min, capped by the 60-min Online window).
- **The 1-minute debounce doesn't cover Offline.** With the new rule, a phone whose last contact is over 45 min old
  goes straight to grey "Offline · sist sett 20:14". That happens the moment the stream closes: on a Wi-Fi/mobile
  handover, a Caddy reload, a server restart. So the flash the old 10-minute pick was meant to hide still happens.
- **A vanished phone's colour depends on the sync phase.** It shows orange for a few minutes then grey, or grey at
  once.

**Fix.**
- For a phone whose stream was open, count the Away/Offline windows (not the "sist sett" words) from the stream's
  close. The open stream proves reachability up to the close minus at most ~6.5 min.
- Add rows: last contact 50 min ago and stream closed 30 s ago → green; closed 2 min ago → orange, not grey.
- Correct §0/§1.3's "today".

### M2 - §4.5: the push condition misses most outages

**Problem.** The push needs "no-contact (§4.3) has lasted 30 min", but `no_contact` exists only in Away, and Away ends
at last contact + 45 min. Take an idle phone that vanishes with its last sync *a* minutes old: detection takes ~4 min,
so no-contact lasts about 41 − *a* min. That reaches 30 min only when *a* < ~11, roughly a third of the time. Behind an
untuned proxy the phone stays Online for up to 60 min and then goes Offline, so the push never fires. A period keyed by
the in-memory close time also changes across a server restart, which would push twice for one outage.

**Fix.** Define the period as "no open stream and no contact", Away or Offline, starting at max(close, last contact).
Raise the push 30 min into it. Key it by the last contact, which is persisted, so a restart doesn't repeat it. Add
sweep rows for a phone whose last sync was 25 min before it vanished, and for an untuned proxy (stream "open", last
contact 70 min ago).

### M3 - §4.1 step 2: the sysctl doesn't survive a reboot in an LXC, and Docker's host network refuses it

**Problem.**
- **Proxmox LXC.** In an unprivileged Proxmox LXC (the home Caddy is one), `/proc/sys` is read-only except
  `/proc/sys/net`. `systemd-sysctl.service` has `ConditionPathIsReadWrite=/proc/sys/`, so a `/etc/sysctl.d` file inside
  the container is skipped at boot. The setting is gone after the next reboot, silently: the status goes slow again
  with nothing to show it.
- **Docker.** Docker refuses `net.*` `sysctls` for `network_mode: host`; there the host's value applies. And a port
  published through `docker-proxy` (userland proxy, e.g. for IPv6) holds the client's TCP in the host's namespace, not
  Caddy's.

**Fix.**
- LXC: put `lxc.sysctl.net.ipv4.tcp_retries2: 8` in the container's `/etc/pve/lxc/<id>.conf` on the Proxmox host, or
  a oneshot unit in the container.
- Docker: say what applies on host networking.
- M3: re-check `sysctl net.ipv4.tcp_retries2` after a reboot of the proxy.
- A plain VM's `/etc/sysctl.d` is fine, but it changes every connection on that VM. Prefer the proxy's own namespace.

### M4 - §4.3: flight mode is grey, but the parent's "Ingen data" abroad is orange

**Problem.** A phone abroad whose parent chose "Ingen data" (design 97) loses contact on purpose. §4.5 leaves it out
of the push, but §4.3 shows it as orange "Ingen kontakt". "A choice the parent made is never a warning" (CLAUDE.md,
Words in the UI).

**Fix.** Treat it like flight mode: the dot is grey with a short state ("I utlandet · ingen data"), shown while the
last report says roaming with data off by the parent's choice and nothing has reached the server since. Add rows.

### M5 - §8: the measurements don't settle the question as written

**Problem.** M1 doesn't name the box (H1), and `docker exec <caddy> ss` fails on the official Alpine-based Caddy image,
which has no `iproute2`. M2 and M3 count "command stream closed" lines, but those include handovers and Caddy
reloads.

**Fix.**
- Run M0 (H1) first.
- Use `nsenter -t $(docker inspect -f '{{.State.Pid}}' <caddy>) -n ss -tnoi state established '( sport = :443 )'`
  (or `pct enter` for an LXC, whose Debian has `ss`), filtered on one phone's address.
- In M2, note the phone's mobile-only state and the time Vibb's switch was turned on.

With those changes M0-M3 are about six commands and settle Q1.

## Low

- **L1 (§0 vs §1.3).** §0 says today's 2.5 min happens in "a third of the cases", §1.3 says "~40 %". With a 150 s probe
  kill against a 240 s comment period it is ~38 %. Use one number.
- **L2 (§4.4).** Make the airplane close's delivery certain, at no cost.
  - Close the stream first (2b), then send step 2's awaited `going_on` POST. On HTTP/2 the POST rides the same
    connection after the `RST_STREAM`, so TCP order makes its 2xx proof that the proxy got the close. If
    `closeForAirplane` goes through `ServerClients.streamDropped`, the pool is evicted and a GOAWAY/FIN closes the
    connection, which is even better.
  - Make sure the close doesn't take the up→down edge's 30 s reconnect wake lock (hole 3), so there is no new battery
    cost.
- **L3 (§4.2).** Say how narrow B is.
  - It only helps a phone on `http://100.x:3101` whose path is a kernel-mode tailscaled on the server's host. With
    `tailscale serve` or a userspace-networking tailscaled (common in a container), the server's socket goes to
    loopback.
  - None of the user's phones use it today (option (c)).
  - On the tsnet path the phone's TCP is gVisor's, inside the launcher process: the ACK needs the process, not just the
    kernel.
  - On Linux 5.11+ `TCP_USER_TIMEOUT` also aborts a connection whose receive window stays zero for longer than the
    timeout, even while the probes are answered. An APK or music download whose reader pauses over 100 s would break
    (they resume, but it is a new failure). Consider setting it on the device listener only, or building B only when
    a tailnet phone exists.
- **L4 (§4.3).** After a server restart, `state()` is `None`, so "no stream seen since the server started" shows orange
  at once until the phone reconnects. That contradicts "a server restart never shows it". Count from the server's start
  time as if a stream closed then.
- **L5 (§4.4, Android's toggle).** On `ACTION_AIRPLANE_MODE_CHANGED` the receiver closes the stream even when Wi-Fi
  stays on (Android 12+ remembers Wi-Fi in airplane mode), which costs a needless reconnect. Use the same
  transport/Wi-Fi test as `airplaneClosesStream`.
- **L6 (§4.5).** Reuse the 23:00-07:00 family-zone hours of `logins::night_push` instead of defining them again (raising
  at 07:00 instead of holding is fine). Take the migration number at build time: 0159 is merged, and parallel rounds may
  take 0160.
- **L7 (§8 E1-E3, Android 14-17).** CLAUDE.md asks a version-dependent gate to run on the lowest and highest version it
  touches, 14 and 17. The note names 16 and "the API 34 image if one is installed". Name 17, or say why the airplane
  broadcast and the Doze firewall are checked unchanged in AOSP 17. The design's version line covers 14-16 only.
- **L8 (§4.1, carrier NAT).** The concern is right: 240 s idle through the user's carriers has never been tested,
  because Caddy's probes (or the L4 hop's) always kept the path warm. Before design 121 is applied, phones on the old
  tailnet path went through WireGuard's own traffic. Ask M3's count per carrier (Wi-Fi vs mobile). If a NAT is under
  240 s, the outcome is a reconnect every ~5 min (≈30 KB/h), worse than the probes. `keepalive_idle 2m` is the way out,
  as written.

## The questions

- **Q1 (the Caddy change, `tcp_retries2 = 8`).** Holds only for the box that holds the phones' TCP. Run M0 first (H1).
  If that box is caddy-l4 on the VPS, the question becomes:
  - `tcp_retries2 = 8` there (fast detection, the probes stay), and
  - whether to swap the forwarder (HAProxy/nginx `stream`) to drop ~23 KB/h per phone.

  8 over 7 holds.
- **Q2 (flight mode grey).** Holds, but only with H2's fix. As written it is grey for 10 min, then orange, then pushed.
- **Q3 (push off by default, 30 min fixed, never at night).** Holds, with M2's period: Away or Offline, keyed by the
  last contact.
- **Q4 (no echo C).** Holds. If the holder can't be tuned (caddy-l4, Traefik), offer the forwarder swap before the echo.
- **Q5 (keepalive stays 240 s).** Holds.
