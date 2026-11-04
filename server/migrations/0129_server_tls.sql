-- Design 76: the launcher's certificate check (docs/design/76-server-issuer-pin.md), per phone. The phone always checks
-- the chain Android validated for this server against its built-in set (Let's Encrypt, ZeroSSL's Sectigo) and reports
-- what it sees (`server_tls` in the status report); the server switches the check on (`server_tls.enforce` in the
-- policy) once that phone has shown only in-set chains for 72 hours of receive time on three days, on Wi-Fi and
-- mobile data when it has reported mobile (`server_tls::next_state`). No row = `watching` from the start: the
-- default for every phone, a restored backup's and a re-created one's, and `watching` sends `{}` - no `enforce` - so a
-- phone keeps its flag (R-1). Only the parent's `off` ever sends `false`.
--
-- `window_from` is the first in-set sighting's receive time since watching began; `window_days` the distinct days
-- (UTC dates) and `window_networks` the networks (`wifi`, `mobile`) of in-set sightings since, as JSON lists.
-- `mobile_seen`: the phone has reported mobile data at all. `stopped_at`: an outside sighting stopped the automatic
-- switch-on (R-2: it doesn't restart the window; the parent's "Turn the check on now" overrides it). `outside_json`:
-- the last 8 outside rows the phone sent (`{kind, host, name, pin, network, at_ms, received_at}`), for the device
-- page. `outside_pushed_on`: the UTC date of the last outside push (one a day per phone). `phone_enforcing`: the
-- last report's `enforcing`. `changed_at`/`changed_by`: the last switch, by a parent's name (NULL = the server or
-- the phone).
CREATE TABLE device_server_tls (
    device_id INTEGER PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
    state TEXT NOT NULL DEFAULT 'watching' CHECK (state IN ('watching', 'on', 'off')),
    window_from TEXT,
    window_days TEXT NOT NULL DEFAULT '[]',
    window_networks TEXT NOT NULL DEFAULT '[]',
    mobile_seen INTEGER NOT NULL DEFAULT 0,
    stopped_at TEXT,
    outside_json TEXT NOT NULL DEFAULT '[]',
    outside_pushed_on TEXT,
    phone_enforcing INTEGER,
    changed_at TEXT,
    changed_by TEXT
);
