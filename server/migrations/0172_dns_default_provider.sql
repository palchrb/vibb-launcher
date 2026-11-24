-- The web filter's resolver: Quad9 first by default, Cloudflare's security resolver as the fallback, all
-- encrypted (dns_server::DEFAULT_PROVIDER). The old default, Cloudflare, moves to the new one, and so does
-- Mullvad (its public DNS ended 2026-11-02, no longer offered); a custom server stays.
UPDATE dns_filter_settings SET upstream = 'quad9', updated_at = datetime('now')
WHERE upstream IN ('cloudflare', 'mullvad');
