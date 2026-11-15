-- Design 98 (Flymodus through the Digital assistant role, docs/design/98-airplane-mode-assistant.md §6.3): the
-- report's `airplane_mode`, cleaned field by field (airplane_mode::clean), read by the summary's lines and the
-- Oppsett card. No table of periods: the latest report holds the last 10.
ALTER TABLE device_status ADD COLUMN airplane_mode_json TEXT;
-- The Oppsett card's "Ikke nå": the needed steps as they were when the parent hid the card (NULL = shown).
ALTER TABLE device_policy ADD COLUMN setup_card_hidden TEXT;
