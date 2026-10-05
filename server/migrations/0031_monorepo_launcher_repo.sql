-- The launcher moved into the palchrb/vibb-launcher monorepo (2026-10): new launcher releases
-- are tagged launcher-vX.Y.Z there, next to the server's server-vX.Y.Z releases (which
-- handlers::tracked_apps skips). Repoint a catalog row that still watches the old standalone
-- repo, so an existing install keeps receiving launcher updates. Any other repo is left alone.
UPDATE tracked_apps SET github_repo = 'palchrb/vibb-launcher'
WHERE github_repo = 'palchrb/kids-launcher-mdm';
