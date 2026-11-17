-- Design 115 (app push held while the apps have no network, docs/design/115-up-held-without-network.md §2.8): the
-- report's `unified_push.held`, cleaned (unified_push::clean_report): the reason the phone holds its app-push queue
-- (`no_network`), NULL when it doesn't (`none`) or the launcher sends nothing. Read by 108c's "waiting" row (arm (a)),
-- which counts only the policies served after the last held report. Kept with its status row (no retention change).
ALTER TABLE device_status ADD COLUMN up_held TEXT;
