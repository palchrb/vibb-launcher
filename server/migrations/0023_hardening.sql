-- Hardening (design docs/design/04-hardening.md in the handy workspace): per-device switches for
-- Android user restrictions the launcher sets while the phone is managed. Sent to the phone as
-- `hardening` with explicit values (handlers::device_api::build_policy). Not lifted by the
-- phone's offline override or pause - only by turning a switch off here.
--
-- Defaults: everything on except safe boot. disallow_debugging_features = 1 means no adb on the
-- phone (no `adb install -r` fix-forward, no logcat); disallow_safe_boot stays off until a launcher
-- build has been proven on the phone, because safe mode is the way past a launcher that crashes
-- before it renders. lock_location = DISALLOW_CONFIG_LOCATION + location turned on.
ALTER TABLE device_policy ADD COLUMN disallow_factory_reset INTEGER NOT NULL DEFAULT 1;
ALTER TABLE device_policy ADD COLUMN disallow_add_user INTEGER NOT NULL DEFAULT 1;
ALTER TABLE device_policy ADD COLUMN disallow_modify_accounts INTEGER NOT NULL DEFAULT 1;
ALTER TABLE device_policy ADD COLUMN disallow_config_vpn INTEGER NOT NULL DEFAULT 1;
ALTER TABLE device_policy ADD COLUMN disallow_usb_file_transfer INTEGER NOT NULL DEFAULT 1;
ALTER TABLE device_policy ADD COLUMN disallow_debugging_features INTEGER NOT NULL DEFAULT 1;
ALTER TABLE device_policy ADD COLUMN disallow_safe_boot INTEGER NOT NULL DEFAULT 0;
ALTER TABLE device_policy ADD COLUMN lock_location INTEGER NOT NULL DEFAULT 1;
