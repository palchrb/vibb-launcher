-- The admin request that wrote a security-log line (`settings_log`): a random id the signed-in routes' middleware
-- sets for each request, NULL for a line written outside one (a phone's, the server's own, rows from before this). A
-- save is matched to its own request's lines, never to another request's by the same parent.
ALTER TABLE security_events ADD COLUMN request_id INTEGER;
CREATE INDEX idx_security_events_request ON security_events(request_id) WHERE request_id IS NOT NULL;
