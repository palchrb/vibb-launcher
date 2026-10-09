# 21a - Import a Vibb Pi library into the music library

User (2026-10-09): bring the Pi's curated library over in one go instead of retyping it. Builds on design 21 §1
(step 1, `server/src/music.rs`, `handlers/music.rs`, migration 0049). Server only; no API or phone change.

## Input

- One uploaded file, at most 1 MB: the Pi's `/etc/vibb/library.json` (`VIBB_LIBRARY`), or the Pi's `GET /library`
  answer (same shape plus `image`/`new` fields, ignored). Shape (palchrb/vibb `pi/vibb/library.py`
  `normalize_library`): `{"sections": [{"name", "spotify_user"?, "entries": [{"name", "target", "order", "cache",
  "resume"}]}]}`. Unknown fields are ignored; at most 50 sections and 500 entries are read. Not JSON or no
  `sections` list -> 400 with the reason by the field, nothing stored.
- The PWA says where to find it: `scp <pi>:/etc/vibb/library.json .`.

## Mapping (pure, no network)

- `target` -> `music::parse_link` (the same parser as "Add", so the same normalised target, source and `key`). No
  `check_link`: vibb's names are kept and a dead feed shows up as the phone's entry error (`reportState`), as for any
  entry. Import makes zero outgoing requests.
- `name` -> cut to `MAX_NAME_CHARS` characters, then `music::clean_name` (a long Pi name is cut, not refused);
  empty -> the row is skipped.
- `order` -> `play_order` (same three values; anything else -> `auto`), `cache` -> `cache` (-1 / 0-100, else
  `DEFAULT_CACHE`), `resume` -> `resume` (default true). The Pi's values are kept as they are.
- Skipped, each with its reason in the preview and the result: Spotify (`spotify:`, `open.spotify.com`,
  `spotify.link/`; phase 3), Storytel (`storytel:`; phase 2), a local folder (starts with `/`: "upload the files as an
  own-files entry"), anything `parse_link` refuses, a `spotify_user` section's entries, a target or key already in
  the library, a repeat within the file, the 200-entry limit (`MAX_ENTRIES`; rows are taken in file order).
- Positions and downloads are not imported (no position mirroring, design 20).

## Flow

1. `/music`, a collapsed "Import from Vibb" section at the bottom (anchor `#import`): file input + "Preview".
2. `POST /music/import` (preview, nothing stored): the page again at `#import` with a table per section:
   - a category select per section: the existing categories, plus "New: <section name>". The default is the
     category whose name equals the section name (trim, case-insensitive), else "New: <section name>";
   - per row: name, source, order/cache/resume, and a tick (default on; skipped rows show their reason and no tick);
   - phone ticks (default: every phone), "Import N entries". The parsed importable rows ride in the form as one
     hidden field (the normalised JSON the server just produced), re-parsed and re-checked on confirm - the
     confirm never trusts it beyond what a fresh upload would allow.
3. `POST /music/import/confirm`: one transaction:
   - creates the "New:" categories (icon/colour copied from the `default_kind` category of their first row's
     source; the parent edits them afterwards), sort after the existing ones;
   - inserts the ticked rows in file order (`sort` after the existing entries, as `NEXT_SORT`), re-checking
     duplicates and the limit inside the transaction;
   - ticks them on the chosen phones, but never past a phone's limit (the same `MAX_LIBRARY_BYTES` check as `set_device_entry`, QA #4);
     a row that doesn't fit stays in the library unticked on that phone and is named in the result.
   Then one `nudge` for the phones that changed, one event `music_library_imported` ("N entries, M categories,
   K skipped"), and a redirect to `/music#import` with the counts (and the skipped rows with reasons) as the notice.
   A re-import of the same file adds nothing and says so.

## Limits and safety

- Admin-only like the rest of `/music`, same CSRF/form handling. The file is parsed in memory and never stored.
- Names go through `clean_name`, targets only through `parse_link`; nothing from the file reaches SQL or HTML
  unescaped. The page keeps its scroll position (`#import`), never jumps to the top.

## Tests (`server/src/tests/music.rs`)

Both input shapes; each source and each skip reason; mapping of order/cache/resume including bad values; category
match by name (case/space) and "New:" creation with the copied icon/colour; duplicates against the library and
within the file (target and key); the 200 limit mid-file; the per-phone limit on ticking; re-import adds nothing;
the confirm re-checks a tampered hidden field (a Spotify row, a 201st row, an unknown category id); zero calls on
the fake fetcher; one event; 400 on non-JSON and over 1 MB; the notice is shown at `#import`.

## Implementation status

Not started.
