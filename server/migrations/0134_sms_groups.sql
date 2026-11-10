-- Design 80 D13: "Gruppemeldinger" - whether group texts (group MMS) are allowed at all, sent as
-- `call_policy.sms_groups` (always). On by default: today's behaviour, design 77 D12's members rule under it. Off
-- blocks every group MMS on a launcher claiming `sms_groups_v1`.
ALTER TABLE device_policy ADD COLUMN sms_groups INTEGER NOT NULL DEFAULT 1;
