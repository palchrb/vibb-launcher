-- Design 24 §4: the parent PIN (override PIN) is easy to guess - all one digit, a run like
-- 123456, or a common PIN. A warning only, never a refusal. 1/0 written with every new PIN;
-- NULL = not checked yet: a PIN saved before this existed (the startup sweep checks it once,
-- `parent_pin::run_easy_sweep`) or no PIN.
ALTER TABLE device_policy ADD COLUMN override_pin_easy INTEGER;
