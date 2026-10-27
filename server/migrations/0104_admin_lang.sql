-- Design 70 (round S-i18n, docs/design/70-pwa-i18n.md §3 at the monorepo root): each parent's PWA language, the
-- i18n code ("en", "nb"). NULL until known: the first signed-in request stores its Accept-Language language, and
-- Settings › Account changes it. No CHECK, so a third language needs no table rebuild; the server reads an unknown
-- value as none.
ALTER TABLE admin_users ADD COLUMN lang TEXT;
