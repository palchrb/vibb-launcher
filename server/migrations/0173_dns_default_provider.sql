-- The web filter's resolver: Quad9 first by default, Mullvad and Cloudflare as fallbacks, all encrypted
-- (dns_server::DEFAULT_PROVIDER). The old default, Cloudflare, moves to the new one; a custom server stays.
UPDATE dns_filter_settings SET upstream = 'quad9', updated_at = datetime('now') WHERE upstream = 'cloudflare';
