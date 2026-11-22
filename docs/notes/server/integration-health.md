# Server notes: integration health (design 116 part B, consolidation C2)

`server/src/integration_health.rs`, migration `0158_failures_visible.sql`. Every call this server makes to an outside
service records its outcome in `integration_health` (one row per key and scope); Attention's orange `Integration` row
reads it. The design is `docs/design/116-failures-visible.md` §2; the register of outside dependencies is
`docs/plan/dependency-register.md`.

## The store

- `ok(db, key, scope)` after a call that worked; `failed(db, key, scope, code, class)` after one that didn't. One
  upsert each; a write error is logged and never fails the call. `ok` writes only when the row is failing or its
  `ok_at` is over 10 minutes old, so a per-request caller (Spotify, Storytel) costs nothing.
- `key` is the integration's name (`github`, `fdroid`, `met`, `caldav`, `caldav_write`, `calendar_link`,
  `music_feed`, `storytel`, `spotify`, `blocklist`, `backup`, `wifi_lookup` - design 125, a 24-h grace, `stop` at its
  day-long pause step); `scope` is `''` (family-wide) or `app:<id>`,
  `account:<id>`, `source:<id>`, `device:<id>`, `entry:<id>`, `feed:<id>`, `file:<name>`.
- `class` is the call site's: `transient` (the other side is away), `parent` (the parent can mend it), `stop` (it
  stopped itself). Each site maps its codes in one `class_of`; `None` = not recorded (our own state, our own waits).
  Where the codes are an enum the match has no `_` arm; where they are text (`calendar_feeds::FeedError`,
  `calendar_kid_events`) a test reads every code the source writes and checks each has its class.
- `raised(row, grace, now)`: no row once the newest outcome is a success; `stop` is `Fix` at once; `parent` twice is
  `Fix`; otherwise `Down` after 3 failures **and** the key's grace (`grace()`: 6 h `met`/`caldav_write`, 24 h
  `music_feed`, 12 h the rest). A row with neither time in 30 days goes (`retention::prune`).
- `for_device(db, id, now)` joins the failing rows to the phones whose features use them (one query: ticked apps and
  Vibb One on every phone, the weather switch, the phone's calendar sources and accounts, its kid-events writes, its
  music entries, Storytel and Spotify by the policy, blocklists in force with the filter on) and groups them by
  `Title`, the scopes summed. `Title::of(key, problem)` names the row; a key without a title shows on no phone.

## Feeding it from a boundary integration (design 110's `integrations::Integration`)

An integration behind the boundary feeds the same store with one call per outcome, under its own name as the key:
`integration_health::ok(&state.db, "map_links", "")` after a call that worked and
`integration_health::failed(&state.db, "map_links", "", code, Class::Transient)` (or `Parent`/`Stop`) after one that
didn't - a scope only if its failures belong to some phones. Its in-memory `HealthCell::ok`/`error` can make these
calls next to its own writes. For an Attention row it then needs a `Title` variant (words in both catalogs, a target
page), an arm in `Title::of`, its grace if not 12 h, and - with a scope - its mapping clause in `FOR_DEVICE`. Without
those it is recorded but shows nowhere, which is the safe default.
