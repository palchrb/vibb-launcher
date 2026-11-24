-- Advanced › Android Auto: the phone hides Android Auto while its apps are managed, unless the parent allows it here.
-- Off for every phone, the existing ones too.
ALTER TABLE device_policy ADD COLUMN allow_android_auto INTEGER NOT NULL DEFAULT 0;
-- The report's `notification_listeners`, cleaned (notification_listeners::clean): the apps with notification access
-- besides Vibb One, each with whether Vibb has turned it off. Read by About This Phone.
ALTER TABLE device_status ADD COLUMN notification_listeners_json TEXT;
