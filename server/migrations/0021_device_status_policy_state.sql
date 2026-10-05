-- What the launcher reported about its own policy handling and its local kill-switch, so the
-- device page can warn about a phone that isn't enforcing the policy the server thinks it is.
--
-- policy_state: "ok", or why the last sync didn't apply a fresh policy - "cache_corrupt" (the
-- cached policy can't be decoded; the phone keeps its current restrictions), "rejected_suspect"
-- (a fresh policy looked like a server falling back to defaults and was ignored), or
-- "fresh_decode_failed" (the server's response couldn't be decoded). NULL from launchers that
-- predate this column.
--
-- restrictions_paused: the PIN-gated "pause all restrictions" switch in the launcher's Settings
-- is on (it turns itself off after a fixed time).
ALTER TABLE device_status ADD COLUMN policy_state TEXT;
ALTER TABLE device_status ADD COLUMN restrictions_paused INTEGER NOT NULL DEFAULT 0;
