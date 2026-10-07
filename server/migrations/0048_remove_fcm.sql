-- Design 19 (docs/design/19-remove-fcm.md at the monorepo root): FCM is gone, the SSE command
-- stream is the only way this server nudges a phone.
--
-- device_push held each phone's FCM registration token / Firebase installation ID - a credential
-- for sending to that phone - plus the FCM health bookkeeping. Nothing reads it any more. It is an
-- FK child of devices (ON DELETE CASCADE); its unique index goes with it.
DROP TABLE device_push;

-- The status log kept the phone's raw `push` report, which carried the same ID. Cleared as
-- cleanup; the column stays, unused (an old launcher's `push` is no longer stored). Neither this
-- UPDATE nor a DROP COLUMN erases freed pages or the update.sh and drive backups - revoking the
-- service-account key (DEPLOY.md, "Removing FCM") is what makes stored IDs useless.
UPDATE device_status SET push_state_json = NULL WHERE push_state_json IS NOT NULL;
