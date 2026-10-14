-- Softer built-in wallpapers (design 34 P8, QA qa-34-design M1/N2). Green, sky and sunset were
-- loud; they become calm dark gradients. Rows are matched by builtin_key, never by id, and keep
-- their id, key and sort, so every phone that has one ticked or picked keeps it, softened (the
-- launcher shows the colours the policy sends). Green was a 'color' row: it becomes a 'gradient'
-- in the same UPDATE, since checked_colours() wants exactly two colours for a gradient and one
-- for a colour - two colours on a 'color' row would drop it from the policy.
UPDATE wallpapers SET kind = 'gradient', colors = '["#2A4A4F","#101820"]', label = 'Fjord'
    WHERE builtin_key = 'green';
UPDATE wallpapers SET colors = '["#34406A","#14141F"]', label = 'Evening sky'
    WHERE builtin_key = 'sky';
UPDATE wallpapers SET colors = '["#5A3A4A","#1A1420"]', label = 'Dusk'
    WHERE builtin_key = 'sunset';
