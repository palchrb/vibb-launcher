-- The weather card without a place name (2026-11-04, the user: "Vi dropper navnet"): the server no longer asks
-- Kartverket for the nearest settlement's name, so the per-cell name cache from 0122 goes. MET is the only outside
-- service that sees a cell's centre now. Its rows were a coarse trail of where the family has been: dropping the
-- table erases them at the upgrade.
DROP TABLE IF EXISTS weather_places;
