# Server notes: dns

Moved verbatim from server/CLAUDE.md on 2026-10-19; the core file's 'Where the rest is' lists every note.

## How phones get their filter

The server runs no DNS listener: each phone filters on-device (the launcher's `KidVpnService`). `dns_engine.rs` holds `CompiledBlocklist`/`compile_blocklist`. `GET /api/devices/dns-blocklist` serves a phone's fully resolved, category-tagged list and `POST /api/devices/dns-events` ingests what got blocked (`device_dns_events`, admin page `/dns/log`). A phone's feed overrides are `device_blocklist_overrides`; `dns_custom_domains.device_id` is NULL for a global domain. `PolicyResponse.dns_filter_version` is an opaque per-device token (the global compiled-list hash plus the phone's override and custom-domain state), so the phone fetches the full list (100k+ domains) only when it changed. The compile downloads every defined feed whatever its global `enabled` flag, so a phone's override can turn a globally-off feed on for that phone alone. `device_policy.vpn_filter_enabled` (default on) is the per-device switch for `KidVpnService`. `rustls` stays a dependency: reqwest needs the process-wide `CryptoProvider` `main.rs` installs (see the `rustls` gotcha in `server/CLAUDE.md`).

### How the lists are downloaded (2026-11-25)

Web filter: lists are fetched over https from public addresses only. `dns_engine::fetch_feed` goes through
`AppState::blocklist_fetch` (its own `music_net::HttpSource`, never the music gate; the canned fetch in the tests) with
`Reach::Public` and `Kind::Blocklist` (the 32 MB cap, 30 s): no private, tailnet or loopback answer, no proxy from the
environment. Redirects are followed one hop at a time, at most 5, and only to `https://`. A refusal is
`FeedFailure::Refused` (health code `refused`, the parent's to mend; "not fetched: ..." behind the list's (i)). Add a
List takes only an `https://` address (`bad_list`). A list saved earlier with an `http://` address is unchanged for now,
waiting on the owner's decision about such lists: still fetched at that address, through the same public-only client,
with nothing new shown on its row.
`tests::outbound_clients` fails on a reqwest client built outside the files it lists, each with its reason.

## A custom DNS server for the Web Filter (design 36 step 1, `docs/design/36-custom-dns.md`, migration `0079_custom_dns.sql`)

- **The server never contacts either address** - no resolve, no probe, no fetch (no SSRF surface); it only parses.
  The phone is the only client (step 2, launcher).
- **`src/dns_server.rs`** (pure): `parse_dns_server(&str) -> Result<DnsServer, Refusal>` classifies what the parent
  typed as DoH (`https://`, or a scheme-less value with a path) or DoT (`tls://`, a bare name or IP, port 853 unless
  given) per design 36 §2's table, or refuses it (`Refusal`, worded by `dns_filter::refusal_text`): `http://`, other
  schemes, `host:443` without a scheme, user info, a query/fragment (but a trailing `{?dns}`), a path on `tls://`,
  ports 0/53/80/>65535, bad path characters or > 256, non-LDH hosts, numeric look-alikes (`1.1`, octal/hex/shorthand,
  `dns.123`), zone ids, and what can never be a server (localhost, loopback, unspecified, multicast, broadcast, IPv6
  link-local, checked after mapping IPv4-mapped/-compatible IPv6). Any other address is allowed (private, tailnet,
  `*.ts.net`, single-label). `DnsServer::normalized()` is what is stored and parses to itself; two servers are the
  same when their normalized forms are equal; `hint()` is `…` + the last two labels of a longer name (never the path
  or the first label - it may be a client id) and the kind. `valid_pair`, `upstream_of` (the policy's object),
  `event_detail` (kinds only), `sanitize_dns_upstream`/`parse_dns_upstream` (the status field).
- **Data** (0079): `dns_filter_settings.custom_server`/`custom_backup` (normalized, `''` = not set),
  `custom_rev` (up on every change of either and on Forget; compared by equality only), `device_status.dns_upstream_json`.
  `upstream` may be `'custom'`; a reader that finds it without a valid pair (both parse, not equal) treats it as
  `dns_server::DEFAULT_PROVIDER`. The providers are `dns_server::PROVIDERS` (`quad9`, `cloudflare` - the
  phone's fallback order behind the parent's choice, all encrypted; 2026-11-24); the default is `quad9`, and migration
  0172 moved a stored `cloudflare` (the old default, so a parent who picked Cloudflare is moved too) and `mullvad`
  (dropped: Mullvad's public DNS ended 2026-11-02) there. Both values are **kept in backups** (design 36 §7, `backup_secrets::a_snapshot_keeps_the_custom_dns_server`).
- **Wire**: `PolicyResponse.dns_upstream` (always the key; `null` unless Custom with a valid pair - then
  `dns_upstream_provider` is the default): `{kind, url | host+port, rev, backup: {kind, url |
  host+port}}`. Status `dns_upstream` `{rev, in_use: custom|backup|provider, since_ms, reason, unreachable_since_ms,
  unreachable_until_ms, provider}` stored through the known words only (`provider`, the one that answered last, only
  in provider mode and only one of `PROVIDERS`) (`reason` only with `backup`:
  bootstrap/connect/tls/http/refused/timeout; every time from 2000 up to the report's arrival + 5 min -
  `CLOCK_SLACK_MS`, a phone clock ahead; `unreachable_until_ms` only with a kept `unreachable_since_ms`, and neither
  when it ends before it starts), never an address, inside `dns_upstream_json` (no migration; a row stored
  before design 67 reads back with both times `None`). Contract `testdata/dns_upstream_status.json`, byte-identical in the launcher's
  test resources (root `CLAUDE.md`). Design 67's launcher reports in provider mode too (`rev` 0, `provider`, no since or reason) and
  only while its tunnel is up; the two times are "DNS can't be reached" (`unreachable_since_ms` alone: ongoing; both:
  the last ended outage), read by the phone as at least 3 dropped queries over at least 30 s with no answer between,
  on a validated network (launcher/CLAUDE.md, "DNS can't be reached").
- **PWA** (Settings › Web Filter, `#server`): the picker gets "Custom server" (`POST /dns/upstream`; Custom without a
  valid stored pair renders the page in place, 400, the Custom Server row open, "Type your server's address first.");
  the "Custom Server" row (`<details id="custom-server">`, its sub-line the server's hint) holds one form, `POST
  /dns/upstream/custom` (`dns_filter::set_custom`, full auth): "Your server" and "Backup server", both `type="text"`,
  **write-only** (never filled from storage; the backup prefilled `https://1.1.1.3/dns-query` - `BACKUP_PREFILL`,
  Cloudflare for Families over DoH, so it also answers where port 853 is blocked (design 36 step 3's QA) - only while
  none is stored; a stored `tls://1.1.1.3` stays as saved, no migration), hints "Set
  (…dns.example · DNS-over-HTTPS)." / "Not set.", "Forget Custom Server" while something is stored. All or nothing:
  each field typed = parsed, empty = the stored value; a refusal, a missing value or the same server twice renders
  in place (400, `Cache-Control: no-store`, the typed values back in the body only, `aria-invalid` and the reason under
  the field); something typed and valid stores both normalized, sets `upstream = 'custom'`, moves `custom_rev` if
  either changed; Forget with both fields empty clears both and puts Custom back on Cloudflare; nothing typed changes
  nothing (Forget puts Custom back on the default provider). A save returns to `/dns?open=custom-server#server` (the row open again), a pick to `/dns#server`. Each hint and the backup prefill follow their own stored value, not the pair's validity, and the picker's 400 names the missing or equal field (`check_custom`, shared with Save). While Custom is in force the footer has one line per set-up phone
  (`dns_filter::phone_line`: "Your server in use", "Backup server since 10:42 - <reason>", "Waiting for the
  phone" without a report at the current `rev` or
  with a `provider` report (even at rev 0), "Web filter off" when the phone's filter switch is off; design 67: "DNS
  can't be reached since 10:42 - neither your server nor the backup answers" while an outage goes on at the current
  `rev`, in place of the custom/backup words); a stored but no longer valid pair says so instead. With a provider
  (`Lookups::Provider`, also for a stale Custom) a phone with an outage has an orange line: "DNS can't be
  reached since 10:42 - no DNS server answers from where the phone is" from a `provider` report (none of the chain
  answers); otherwise a quiet "Anna · Cloudflare answers" from the report's `provider` (2026-11-24: the parent's setup,
  not a warning). The picker offers Quad9, Cloudflare, Custom; its ⓘ says the default and the order. An ongoing line from a
  phone that is Offline or Disconnected (the Attention rows' rule, `connection_of`) adds " · at last report (2 h ago)".
  An outage that ended within the last 24 h (`ENDED_OUTAGE_SHOWN`; design 67 §6: no push) and lasted at least 5 min
  (`MIN_ENDED_OUTAGE`, qa-67-code M1: Android keeps a fading Wi-Fi or a cellular dead spot VALIDATED for a while, so a
  walk out of the door can make a short one) shows **whatever the report's rev or mode** (the
  fixed servers bumped it): " · couldn't be reached 10:42-11:05" after the phone's line, alone in provider mode ("Anna
  · DNS couldn't be reached 10:42-11:05"); never while the phone's filter is off. Times through `zone.when`. The ⓘ (cut
  2026-10-24, pwa.md) says a name means DNS-over-TLS and an https:// address DNS-over-HTTPS, and that the phone reaches
  the server directly, not through Tailscale, so a server at home works only on the home Wi-Fi.
- **Security event** `dns_server_changed` ("DNS server changed") on every change of the choice or either address,
  detail `{"server":..,"kind":..,"backup_kind":..}` - kinds only, never a host or path; nothing logs either value.
- **The secure-DNS bypass** (design 67 §2, `docs/design/67-dns-bypass.md`; the phone's side in launcher/CLAUDE.md,
  "The browsers' secure DNS"): nothing new in the policy or the blocklist contract - the Web Filter switch drives it.
  The lists group ("Lists for every phone" since 2026-11-01) has a fixed row without a switch, "Secure DNS servers" / "Built into Vibb One, on with the
  filter" (`data-list="builtin-doh"`; the parent can't lift it, design 67 §6), and its footer says what it does:
  "Chrome's own secure DNS is turned off, and the best-known public secure-DNS servers are blocked. Firefox and Cromite
  can still be set to another secure-DNS server by hand." **What a switch means for the phones** (the user, 2026-10-26; 2026-11-01): a list's
  switch is every phone's default and a phone's own Web Filter page can set it otherwise (`device_blocklist_overrides`),
  so each list row's line says it from the phones whose own setting differs from the switch, by name
  (`dns_filter::list_scope`, `data-scope`): "On for every phone", "On for every phone except Testtelefon", "Off for
  every phone", "Off · on for Testtelefon" (`settings.web_filter.*`; an own setting equal to the switch isn't named);
  the footer starts "A phone's own Web Filter page can set a list otherwise for that phone." **A new phone gets "Ads &
  tracking" on** (design 126 C1): `devices::insert_device_with_policy` writes the phone's own override
  (`ads_default_override`) in the create's transaction when the seeded list (`models::SEEDED_ADS_LIST`, name and
  address) exists and is off for every phone - none when it is on for every phone, so the phone follows a later change
  of the switch; phones made before keep what they had. Settings' hub counts the lists in force on at least one phone
  (the switch on, or an own override on), so its "No lists" is never wrong; test `dns_blocklist::a_new_phone_gets_the_ads_list_on`. Test
  `device_pages::web_filter_holds_the_phones_parts`. The Each Phone group's footer has lines only where something
  is owed, only while that phone's filter is on (`dns_filter::bypass_lines`, `data-bypass-phone`): a line for each browser of
  `DOH_IGNORING_BROWSERS` (server only) on its allowlist - Cromite ("set its Secure DNS to Off on the phone, or it
  won't load pages"), the Firefox family - Firefox, Beta, Nightly (`org.mozilla.fenix`), Fennec F-Droid, IronFox -
  ("its own secure DNS can get around the filter"; final words after E3 check 11) and Opera ("may", until E3 check 16
  says whether it reads the managed config it gets). The Allowed Domains footer (both pages) says the built-in hosts
  stay blocked, and an allow entry they cover is kept (it lifts nothing) with a notice on that save, "<host> is a
  secure-DNS server and stays blocked" (`?notice=doh_allowed&domain=`, shown only for a host `is_builtin_doh_host`
  covers; qa-67-code L1). The server's copy of the list is `testdata/doh_hosts.txt`, byte-identical with the
  launcher's asset (root `CLAUDE.md`).
- Tests: `dns_server::tests` (every row and refusal, normalized, hint, the wire, the sanitizer with the outage's
  times and `provider`), `tests::custom_dns` (save/refusals/Forget/the event, the policy shapes, a pair that isn't
  valid, the status contract and both copies, the footer's lines, `the_lines_say_when_dns_cant_be_reached` - both modes,
  24 h, under 5 min, at last report, an old rev, a provider report under Custom -, `the_secure_dns_bypass_on_the_page`,
  `an_allowed_secure_dns_host_says_it_stays_blocked`), `dns_server::tests::doh_hosts_match_the_launchers_asset`, `policy_json_keys_snapshot`
  (`dns_upstream` and its `backup`), the settings redirect table, `migrations_73_to_95_in_order`.

## Filter on, tunnel down (design 116 part C, security review S-M2)

The report's `vpn_filter` (`{state: up|down|off, down_ms?, period?}`, `mobile_network::clean_vpn_filter`, stored in
`device_status.vpn_filter_json`) is the phone's own view of its filter at report time. `mobile_network::filter_down`
raises "Web filter is down" (Attention `FilterDown`) once it is `down` for 10 minutes while the parent's switch is on and
nothing lifts it; the phone's Web Filter page and the `/dns` page's line for that phone ("Emma: down") say the same, and
the event `filter_down` is raised once per `period`. A missing `vpn_filter` (an older launcher) raises nothing. Every
blocklist download also records `integration_health('blocklist', 'feed:<id>')` (a typed `dns_engine::FeedFailure`).
