-- FCM by Firebase installation ID (qa-fixround-2026-10-06 #2): the launcher registers by FID
-- (firebase-messaging 25.1+) and reports which kind its `fcm_token` is - "fid" or "token" (a
-- legacy registration token). FIDs are sent as `message.fid`, tokens as `message.token`
-- (firebase.google.com/docs/cloud-messaging/send/admin-sdk: `token` is deprecated and only
-- accepts FIDs during the migration period). NULL = an older launcher: decided by the shape.
ALTER TABLE device_push ADD COLUMN fcm_token_kind TEXT;
