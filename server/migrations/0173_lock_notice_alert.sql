-- Design 129 (docs/design/129-lock-sound.md): the lock screen's "Vibrer ved varsler" switch becomes one choice
-- Av / Vibrer / Lyd, sent as launcher_ui.lock_notice_alert ('off', 'buzz', 'sound'). A stored buzz on becomes 'buzz',
-- off 'off'; the old column (0136) goes.
ALTER TABLE device_policy ADD COLUMN lock_notice_alert TEXT NOT NULL DEFAULT 'off'
    CHECK (lock_notice_alert IN ('off', 'buzz', 'sound'));
UPDATE device_policy SET lock_notice_alert = CASE WHEN lock_buzz_on_notification THEN 'buzz' ELSE 'off' END;
ALTER TABLE device_policy DROP COLUMN lock_buzz_on_notification;
