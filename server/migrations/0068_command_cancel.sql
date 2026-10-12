-- Command expiry and cancel (the user's decision, 2026-10-12): a command that hasn't reached the
-- phone can be cancelled on the Locate page; an enrollment into the row cancels the ones left for
-- the earlier phone. cancel_reason: 'parent' or 'enrolled'. Expiry (start_pause, ring, stop_ring,
-- locate not fetched within an hour) is computed from requested_at, not stored.
ALTER TABLE device_commands ADD COLUMN cancelled_at TEXT;
ALTER TABLE device_commands ADD COLUMN cancel_reason TEXT;
-- "Allow these apps" carries the apps the Status card listed, with the keys it showed (QA of
-- cmd-expiry, Low 1): JSON [{package_name, signer}], NULL for every other command.
ALTER TABLE device_commands ADD COLUMN payload TEXT;
