-- Design 99 (Snarveier, the kid's quick panel in the shade, docs/design/99-snarveier.md §5.1, §6.1): bit 128 of the
-- quick controls on every existing phone (D2: Home's flashlight tile goes in the same launcher, so a phone without
-- the bit would lose its flashlight outside the lock screen; an older launcher ignores it). New phones get it from
-- `devices::DEFAULT_QUICK_CONTROLS`. The mask lives in `device_policy` alone.
UPDATE device_policy SET quick_controls_mask = quick_controls_mask | 128;
-- The report's `shortcuts`, cleaned (shortcuts::clean): `{shown, hidden, items}`, read by the Home Screen row's
-- sub-line and About This Phone.
ALTER TABLE device_status ADD COLUMN shortcuts_json TEXT;
