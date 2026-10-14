-- The launcher is called "Vibb One" (design 34 P11, step 12). Its catalog row (is_launcher = 1)
-- takes the new name only while it still has one of the two default names - the mockups' "Vibb"
-- and the old runbook's "Kids Launcher" (there is no seed: the parent creates the row). Any other
-- name is the parent's own and stays. Nothing matches the row by name (the package and
-- is_launcher do), so provisioning and updates are unaffected.
UPDATE tracked_apps SET name = 'Vibb One'
    WHERE is_launcher = 1 AND name IN ('Vibb', 'Kids Launcher');
