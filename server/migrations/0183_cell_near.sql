-- Design 137 N4 (docs/design/137-cell-neighbours.md): each asked cell's row keeps Apple's neighbours around it.
-- `near` = a nonce and AES-256-GCM of the cell's overlapping neighbours under a key derived from (K, "near|" and the
-- cell's canonical form), the same string as the AAD; an empty list is an empty sealed list. NULL = not from a full
-- call (a Wi-Fi row, a cell row from before this, a call made while throttled): such a cell row is asked again.
-- Only a column is added; every row stays.
ALTER TABLE wifi_lookup_aps ADD COLUMN near BLOB;
