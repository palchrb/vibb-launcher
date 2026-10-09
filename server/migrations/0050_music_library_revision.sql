-- The music library's revision (qa-21-step1-code #3): one counter that every change to anything
-- a phone's library is built from moves, by trigger - so no code path can forget it. The server
-- caches each phone's built library under the revision it was built at (`music::LibraryCache`)
-- and rebuilds only when the revision has moved. A new table that feeds the library (design 21b's
-- listings) gets the same three triggers in its own migration, or its writer calls
-- `music::bump_library_revision`.
--
-- New table and triggers only: safe on an existing database.
CREATE TABLE music_library_revision (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    revision INTEGER NOT NULL DEFAULT 0
);
INSERT INTO music_library_revision (id) VALUES (1);

CREATE TRIGGER music_entries_insert_revision AFTER INSERT ON music_entries
BEGIN
    UPDATE music_library_revision SET revision = revision + 1 WHERE id = 1;
END;

CREATE TRIGGER music_entries_update_revision AFTER UPDATE ON music_entries
BEGIN
    UPDATE music_library_revision SET revision = revision + 1 WHERE id = 1;
END;

CREATE TRIGGER music_entries_delete_revision AFTER DELETE ON music_entries
BEGIN
    UPDATE music_library_revision SET revision = revision + 1 WHERE id = 1;
END;

CREATE TRIGGER music_categories_insert_revision AFTER INSERT ON music_categories
BEGIN
    UPDATE music_library_revision SET revision = revision + 1 WHERE id = 1;
END;

CREATE TRIGGER music_categories_update_revision AFTER UPDATE ON music_categories
BEGIN
    UPDATE music_library_revision SET revision = revision + 1 WHERE id = 1;
END;

CREATE TRIGGER music_categories_delete_revision AFTER DELETE ON music_categories
BEGIN
    UPDATE music_library_revision SET revision = revision + 1 WHERE id = 1;
END;

CREATE TRIGGER music_files_insert_revision AFTER INSERT ON music_files
BEGIN
    UPDATE music_library_revision SET revision = revision + 1 WHERE id = 1;
END;

CREATE TRIGGER music_files_update_revision AFTER UPDATE ON music_files
BEGIN
    UPDATE music_library_revision SET revision = revision + 1 WHERE id = 1;
END;

CREATE TRIGGER music_files_delete_revision AFTER DELETE ON music_files
BEGIN
    UPDATE music_library_revision SET revision = revision + 1 WHERE id = 1;
END;

CREATE TRIGGER device_music_entries_insert_revision AFTER INSERT ON device_music_entries
BEGIN
    UPDATE music_library_revision SET revision = revision + 1 WHERE id = 1;
END;

CREATE TRIGGER device_music_entries_update_revision AFTER UPDATE ON device_music_entries
BEGIN
    UPDATE music_library_revision SET revision = revision + 1 WHERE id = 1;
END;

CREATE TRIGGER device_music_entries_delete_revision AFTER DELETE ON device_music_entries
BEGIN
    UPDATE music_library_revision SET revision = revision + 1 WHERE id = 1;
END;
