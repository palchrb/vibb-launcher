-- Why a phone's install failed (upstream review 2026-11-20, `install_failure.rs`): Android's code name or the
-- launcher's own kind, NULL for a progress report or a failure without one. Never Android's free text.
ALTER TABLE device_install_progress ADD COLUMN reason TEXT;
