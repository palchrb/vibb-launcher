# Server notes: dns

Moved verbatim from server/CLAUDE.md on 2026-10-19; the core file's 'Where the rest is' lists every note.

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
  `'cloudflare'`. Both values are **kept in backups** (design 36 §7, `backup_secrets::a_snapshot_keeps_the_custom_dns_server`).
- **Wire**: `PolicyResponse.dns_upstream` (always the key; `null` unless Custom with a valid pair - then
  `dns_upstream_provider` is `"cloudflare"` for an older launcher): `{kind, url | host+port, rev, backup: {kind, url |
  host+port}}`. Status `dns_upstream` `{rev, in_use: custom|backup, since_ms, reason}` stored through the known words
  only (`reason` only with `backup`: bootstrap/connect/tls/http/refused/timeout; `since_ms` 2000..2100), never an
  address; capability `dns_custom_v1` (`dns_server::DNS_CUSTOM_CAPABILITY`, sent by the launcher from step 2 on).
  Contract `testdata/dns_upstream_status.json`, byte-identical in the launcher's test resources (root `CLAUDE.md`).
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
  nothing. A save returns to `/dns?open=custom-server#server` (the row open again), a pick to `/dns#server`. Each hint and the backup prefill follow their own stored value, not the pair's validity, and the picker's 400 names the missing or equal field (`check_custom`, shared with Save). While Custom is in force the footer has one line per set-up phone
  (`dns_filter::phone_line`: "Your server in use", "Backup server since 10:42 - <reason>", "Cloudflare - update Vibb
  One on this phone ..." without the capability, "Waiting for the phone" without a report at the current `rev`, "Web
  filter off" when the phone's filter switch is off); a stored but no longer valid pair says so instead. The ⓘ (cut
  2026-10-24, pwa.md) says a name means DNS-over-TLS and an https:// address DNS-over-HTTPS, and that the phone reaches
  the server directly, not through Tailscale, so a server at home works only on the home Wi-Fi.
- **Security event** `dns_server_changed` ("DNS server changed") on every change of the choice or either address,
  detail `{"server":..,"kind":..,"backup_kind":..}` - kinds only, never a host or path; nothing logs either value.
- Tests: `dns_server::tests` (every row and refusal, normalized, hint, the wire, the sanitizer), `tests::custom_dns`
  (save/refusals/Forget/the event, the policy shapes, a pair that isn't valid, the status contract and both copies,
  the footer's lines), `policy_json_keys_snapshot` (`dns_upstream` and its `backup`), the settings redirect table,
  `migrations_73_to_92_in_order`.
