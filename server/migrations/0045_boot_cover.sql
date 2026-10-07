-- The boot cover (design docs/design/16-boot-home.md option D = step 16b at the monorepo root): the
-- launcher's own direct-boot-aware Home with the Vibb mark from the boot animation to the unlock,
-- instead of the phone's stock launcher. Off by default - test it on the phone model first.
ALTER TABLE device_policy ADD COLUMN boot_cover INTEGER NOT NULL DEFAULT 0;
