-- Calls & SMS (design docs/design/02-calls.md in the handy workspace).
--
-- calls_managed = 0 means the launcher leaves calls alone (call_policy.managed = false is still
-- sent explicitly - see build_policy). When managed, calls_enabled = 0 blocks every call except
-- emergency calls, and sms_enabled = 0 blocks SMS (and the default SMS app, because of RCS).
-- The two switches are independent per device.
ALTER TABLE device_policy ADD COLUMN calls_managed INTEGER NOT NULL DEFAULT 0;
ALTER TABLE device_policy ADD COLUMN calls_enabled INTEGER NOT NULL DEFAULT 1;
ALTER TABLE device_policy ADD COLUMN sms_enabled INTEGER NOT NULL DEFAULT 1;
-- Which app a contact's Message button opens unless the contact says otherwise.
ALTER TABLE device_policy ADD COLUMN default_message_app TEXT NOT NULL DEFAULT 'sms'
    CHECK (default_message_app IN ('none', 'sms', 'element', 'signal'));

-- Global address book: siblings share grandparents. The number is stored normalised (src/phone.rs:
-- E.164, or a 3-6 digit short number) and is the identity of a contact; the name is shared by
-- every device that has the contact.
CREATE TABLE contacts (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL CHECK (length(name) BETWEEN 1 AND 60),
    phone_number TEXT NOT NULL UNIQUE,
    created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

-- Which contacts a device has, and what each may do there. Mirrors device_tracked_apps.
-- message_app NULL = the device's default_message_app; message_address is the Matrix ID the
-- Element button opens (SMS and Signal use the contact's number).
CREATE TABLE device_contacts (
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    contact_id INTEGER NOT NULL REFERENCES contacts(id) ON DELETE CASCADE,
    allow_inbound INTEGER NOT NULL DEFAULT 1,
    allow_outbound INTEGER NOT NULL DEFAULT 1,
    show_on_home INTEGER NOT NULL DEFAULT 1,
    message_app TEXT CHECK (message_app IS NULL OR message_app IN ('none', 'sms', 'element', 'signal')),
    message_address TEXT CHECK (message_address IS NULL OR length(message_address) BETWEEN 1 AND 255),
    sort_order INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (device_id, contact_id)
);

-- Singleton. Numbers typed without a country code get this one. Changing it later does not
-- renormalise numbers already stored.
CREATE TABLE call_settings (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    default_country_code TEXT NOT NULL DEFAULT '47' CHECK (
        default_country_code GLOB '[1-9]'
        OR default_country_code GLOB '[1-9][0-9]'
        OR default_country_code GLOB '[1-9][0-9][0-9]'
    )
);
INSERT INTO call_settings (id) VALUES (1);

-- What the launcher reports about calls: capabilities_json is a JSON array of strings
-- ("call_policy_v1" = this launcher enforces call_policy), call_state_json is the launcher's
-- CallState object (dialer role, restrictions, emergency calls). Both NULL from older launchers.
ALTER TABLE device_status ADD COLUMN capabilities_json TEXT;
ALTER TABLE device_status ADD COLUMN call_state_json TEXT;
