-- The launcher moved into the palchrb/vibb-launcher monorepo (2026-10): new launcher releases
-- are tagged launcher-vX.Y.Z there, next to the server's server-vX.Y.Z releases (which
-- handlers::tracked_apps skips). Repoint a catalog row that still watches the old standalone
-- repo, so an existing install keeps receiving launcher updates. Any other repo is left alone.
-- The repointed row also gets include_prereleases off: launcher-vX.Y.Z-rc.N release candidates
-- are prereleases and must never roll out to every phone by themselves (DEPLOY.md). An admin
-- can turn it back on deliberately.
UPDATE tracked_apps SET github_repo = 'palchrb/vibb-launcher', include_prereleases = 0
WHERE github_repo = 'palchrb/kids-launcher-mdm';
