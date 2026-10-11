-- Design 24 §4: the parent PIN (override PIN) is easy to guess - all one digit, a run like
-- 123456, or a common PIN. A warning only, never a refusal. 1/0 written with every new PIN;
-- NULL = not checked yet: a PIN saved before this existed (the startup sweep checks it once,
-- `parent_pin::run_easy_sweep`) or no PIN.
ALTER TABLE device_policy ADD COLUMN override_pin_easy INTEGER;
-- When the parent PIN was last saved or removed on the device page (ms since the epoch; NULL = before
-- this existed): the Status card's notices about the phone's parent-PIN events start after it.
ALTER TABLE device_policy ADD COLUMN override_pin_set_at_ms INTEGER;
