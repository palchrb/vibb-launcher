-- A custom DNS server for the Web Filter (design 36). dns_filter_settings.upstream may now be
-- 'custom': the phones forward to custom_server and, while it is unhealthy, to custom_backup. Both
-- are stored normalized by dns_server::parse_dns_server (https://host[:port]/path or
-- tls://host[:port]); '' = not set. A reader that finds 'custom' without a valid pair (both
-- parsable, not equal) treats it as 'cloudflare'. The server never contacts either address.
-- Both values are write-only in the PWA and kept in backups (design 36 section 7).
ALTER TABLE dns_filter_settings ADD COLUMN custom_server TEXT NOT NULL DEFAULT '';
ALTER TABLE dns_filter_settings ADD COLUMN custom_backup TEXT NOT NULL DEFAULT '';
-- Up by one on every save that changes either value and on Forget; the phone echoes it in its
-- status report. Compared by equality only (a restore can move it backwards).
ALTER TABLE dns_filter_settings ADD COLUMN custom_rev INTEGER NOT NULL DEFAULT 0;
-- The phone's dns_upstream report ({rev, in_use, since_ms, reason}, known words only), NULL from an
-- older launcher or while the Web filter is off or Custom isn't configured on the phone.
ALTER TABLE device_status ADD COLUMN dns_upstream_json TEXT;
