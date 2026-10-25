-- The shorter push texts (2026-10-25, the user: "Generelt kunne vi også forenklet web push meldingene? Mindre tekst"):
-- a push's own words, "<name>: <fact>" / the time and at most one number ("Anna: turned on" / "07:02 · battery 64 %"),
-- made at raise beside the inbox's long `title`/`summary` (`parent_events::Phone`), sent by `parent_events::pushed`.
-- NULL = none: a row from before this migration pushes its inbox words, and a kind told from `params` (zones, a
-- request for more time) is worded short where it is pushed.
ALTER TABLE parent_events ADD COLUMN push_title TEXT;
ALTER TABLE parent_events ADD COLUMN push_body TEXT;
