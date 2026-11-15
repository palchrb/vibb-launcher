-- Design 100 §14.4 (revision 2): a request for more time is open until a parent answers it or the phone's day ends.
-- One row per answered request, keyed by the phone's own request id (the `extra_time_request` event's dedup_key):
-- `yes` (a lift - `lift_id` - from the answer card or any fitting lift made while it was open), `no` (the card's
-- "Nei"), or `superseded` (a newer request of the same day taken at intake; never served to the phone). `by_name` is
-- the answering parent's display name at the answer (NULL: none - the phone then names nobody, D11).
CREATE TABLE time_request_answers (
    device_id INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    request_id TEXT NOT NULL,
    answer TEXT NOT NULL CHECK (answer IN ('yes', 'no', 'superseded')),
    lift_id INTEGER REFERENCES time_lifts(id) ON DELETE SET NULL,
    answered_by INTEGER REFERENCES admin_users(id) ON DELETE SET NULL,
    by_name TEXT,
    answered_at TEXT NOT NULL DEFAULT (datetime('now')),
    PRIMARY KEY (device_id, request_id)
);

-- Design QA R2-M4: before this, any lift made at or after a request answered it (design r13 §2.7). Those requests
-- get their `yes` here - the first such lift of the phone (a phone-key pause never answered one: it has no parent) -
-- so the one rule from now on (an open request has no answer row) needs no second branch for the old rows. Code QA
-- L10: only screen time names its lift (a rule pause that closed one under r13 gave no minutes: the phone shows no
-- "Du fikk 15 min mer" for it), and `by_name` is the giver's display name now, as a new answer's.
INSERT OR IGNORE INTO time_request_answers (device_id, request_id, answer, lift_id, answered_by, by_name, answered_at)
SELECT e.device_id, e.dedup_key, 'yes', CASE WHEN l.target = 'budget' THEN l.id END,
       (SELECT a.id FROM admin_users a WHERE a.username = l.created_by),
       (SELECT NULLIF(TRIM(a.display_name), '') FROM admin_users a WHERE a.username = l.created_by),
       l.created_at
FROM parent_events e
JOIN time_lifts l ON l.id = (
    SELECT MIN(f.id) FROM time_lifts f
    WHERE f.device_id = e.device_id AND f.phone_key IS NULL
      AND datetime(f.created_at) >= datetime(e.created_at)
)
WHERE e.kind = 'extra_time_request' AND e.device_id IS NOT NULL;

CREATE INDEX idx_time_request_answers_lift ON time_request_answers(device_id, lift_id);
