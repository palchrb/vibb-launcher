# 63 - Many families on one server: an assessment

Assessment only (2026-10-22). Nothing is built, nothing is committed.

**The question:** how hard is it to make the setup properly multi-family (multi-tenant) and multi-child? One server
would serve many families. Each family has its own parents, phones, music library and rules, and families are strictly
isolated from each other. This builds on the separate "several parent accounts in one family" assessment, which is
assumed to land first (a family has N parent accounts).

`S/` = `server/src/`, `M/` = `server/migrations/`, `L/` = `launcher/app/src/main/java/com/kidslauncher/mdm/`. Line
numbers are today's (`main` at 70583aa9).

Naming: PLAN.md's parked idea (PLAN.md:431-437) uses other letters. Its (A) "instance per family" is (C) here, its
(B) "file per family" is (B) here, and its (C) "`family_id` everywhere" is (A) here.

## 0. The short answer

- **Today the server is single-tenant through and through.** It has 49 tables. 23 are keyed by `device_id` and so
  reach a family only through `devices`. The other 26 are family-wide or instance-wide, 9 of them singletons
  (`CHECK (id = 1)`). It has 183 admin routes, 104 of which take an id or hash in the path, and ~650 runtime
  `sqlx::query` call sites, none of them compile-checked macros. The process also holds a dozen in-memory singletons
  (Storytel session, Spotify tokens, library cache, sweep gate, token index).
- **The phones need nothing**, provided the server routes a phone by its token (or by host name), not by a path
  prefix.
- **Recommendation:** for the open-source product, **(C) one instance per family** (2-3 steps, S/M each). If a
  hosted service for strangers is a real goal, use **(B) one SQLite file per family in one process** (about 10-12
  steps, XL overall, after multi-parent). **Never (A) `family_id` everywhere.** It costs more than (B), and every
  future feature carries a cross-family leak risk.

## 1. Today's isolation: what is global

Everything is "the family" implicitly: a signed-in admin sees every row. `load_active_admin` reads
`SELECT * FROM admin_users WHERE id = ?` and nothing else (S/security.rs:340-353). An admin route with a device id
just reads that id (104 parameterised routes). In a shared database each one is an IDOR (a page reached by guessing
another family's id).

Sizes: **S** = under one step, **M** = one step (one design -> QA -> impl -> QA round), **L** = 2-3 steps,
**XL** = 4+ steps. The costs below are for (A). Under (B) most of the DB rows cost nothing (a singleton per file is
correct), and what's left is the in-memory state, files, jobs and the instance-level outside services.

### 1.1 Cheap (S each): family settings, singleton tables

| What | Where | Note |
|---|---|---|
| `global_schedule` (budget, frozen schedule) | M/0017_schedules_page.sql:22 | family's default time rules |
| `provisioning_settings` (Tailscale key, Wi-Fi) | M/0018_provisioning_settings.sql:9 | per family, or gone when hosted (no tailnet) |
| `call_settings` (default country code) | M/0022_calls.sql:42 | |
| `dns_filter_settings` (feeds on/off, custom DNS) | M/0009_dns_filter.sql:11 | |
| `music_settings` (sweep hours) | M/0051_music_sweep.sql:12 | |
| `notification_settings` | M/0078_parent_events.sql:31 | |
| `music_library_revision` | M/0050_music_library_revision.sql:10 | global: one family's edit rebuilds every family's cached libraries (S/music.rs:1236) |
| `music_storytel` (sealed login) | M/0072_music_storytel.sql (`id = 1`, music.md:16) | one Storytel login per family |
| `music_spotify_app` (client ID) | M/0074_music_spotify.sql:11 | must stay per family: a Spotify dev-mode app has at most 5 users (music.md:250-252) |
| Global time rules / custom domains (`device_id IS NULL`) | M/0025_time_rules.sql:11, M/0011:13 | 9 `device_id IS NULL` reads in S/ |

### 1.2 Medium (M each): family-wide tables and global UNIQUEs

- `contacts` (global address book, `phone_number UNIQUE`, M/0022_calls.sql:20) -> unique per family.
- `music_categories`, `music_entries` (`target UNIQUE`, `key UNIQUE`, M/0049_music.sql:36-37), `music_listings`,
  `music_items`, `music_files`, `music_check_requests`, `music_spotify_accounts` (`spotify_user UNIQUE`,
  M/0074:18).
- `wallpapers` (built-ins + uploads, `builtin_key UNIQUE`, M/0028:18): built-ins shared, uploads per family.
- `dns_blocklists` (feed definitions): the downloaded feeds can stay a shared cache (S/dns_engine.rs:18-50, public
  lists). The on/off state goes per family.
- `parent_events`, `push_subscriptions`/`push_deliveries` (via `admin_id`), `security_events`, `banned_ips`. Bans and
  login events stay the operator's. A family sees only its own.
- `admin_users` (`username UNIQUE`, M/0001_init.sql:3) and the `tower_sessions` table (S/main.rs:299): the login has
  to find the family. That comes after multi-parent.
- Unscoped list queries that would touch every family: `SELECT id FROM devices` in S/time_rules.rs:457,
  S/handlers/settings.rs:318, S/handlers/tracked_apps.rs:2137; `SELECT * FROM devices ORDER BY name` in
  S/handlers/tracked_apps.rs:1387; S/handlers/music_import.rs:144. These nudge or list every phone on the server.

### 1.3 Expensive (L/XL): shared machinery

- **The app catalog** `tracked_apps` (+ APKs in `data/tracked_apps`, S/handlers/tracked_apps.rs:29). The launcher row
  is created at start (`ensure_launcher_row`) and pushed to every phone. Split it into operator rows (launcher, Vibb
  music, shared APK cache) and family-private rows (a family's own uploads and repos, which are private: they show
  what a child runs). **L.**
- **GitHub rate limit:** the sync polls hourly (S/handlers/tracked_apps.rs:1117), unauthenticated
  (S/config.rs:146, no token anywhere). That is 60 requests an hour per IP. Per-family catalogs, or N instances on
  one IP as in (C), run out quickly. A `GITHUB_TOKEN` setting is useful anyway. **S.**
- **Files under `data/`** have fixed paths: `contact_photos`, `dns_feeds`, `wallpapers` (S/main.rs:437-439),
  `music_files/<entry id>/`, `music_covers`, `music_art` (S/music.rs:34-38), `tracked_apps`. The hash-addressed
  stores can be shared under (A), but then "files no row names" (Music › Settings' Delete Files…, music.md:60-63) and
  `photos::recover_missing` (S/main.rs:482) scan other families' files. Under (B), entry ids repeat across families,
  so per-family dirs are required. **M.**
- **Keys** in `data/keys/`: `music-secret.key` seals Storytel and Spotify (S/music_secret.rs:28), `kid-pin.key` seals
  the Child PIN (S/kid_pin.rs:21), and `vapid-p256.pk8` is the Web Push identity (S/push.rs:28). The VAPID key can
  stay per instance. Sealing keys should become per family, derived as HKDF(instance key, family id) with the family
  in the AAD. A sealed blob then can't move between families, and deleting a family's salt shreds its secrets.
  **S-M.**
- **Background jobs**, all spawned once over the one pool (S/main.rs:490-524): scheduled backups, live mirror,
  app-update check, release-hash backfill, catalog sync, blocklist refresh, retention, music sweep, easy-PIN sweep,
  audit flusher, push sender, plus session expiry (S/main.rs:308). Each must loop over families, fairly. **L.**
- **In-memory state keyed by a bare device id or global** (`AppState`, S/main.rs:70-150):
  - `command_notify` (a broadcast of `i64`), `command_streams`, `limits`, `tokens` (`TokenIndex`, token hash -> `i64`,
    S/security.rs:436-490) and `music_libraries` (S/music.rs:1236). Fine under (A), where ids are global. Under (B)
    ids repeat per file, so they must key on (family, device).
  - `storytel` is one `Account` (one JWT, one shelf; music.md:164-173) and `spotify` is one token cache. Both become
    one per family. **M.**
- **Server-wide throttles** that one family can exhaust for all:
  - `music_fetch` is one request in flight server-wide, 200 ms apart (S/music_net.rs:512-518). With N families,
    sweep latency grows N-fold. It needs a fair per-family queue.
  - `PasswordGate`: 1 argon2 running, 4 queued, else 429 (S/admin_auth.rs:55).
  - Typed setup codes pause server-wide after 20 failures an hour (S/enrollment.rs:15, :34): one attacker pauses
    every family's typed codes. QR codes still work, which is acceptable.
  - `audit` caps rows per minute across all clients (S/audit.rs:6). **M.**
- **Backups and restore** are the whole database: `VACUUM INTO` + zip (S/handlers/backups.rs:188), and restore swaps
  the file (connectivity.md, "Docker and startup"). A pre-migration backup copies the whole DB at every migration.
  Under (A), a per-family export/import with id remapping across ~45 tables is new work (**XL**), and one family's
  "restore" can't roll back the others. Under (B), it's today's code pointed at the family's file (**S**).
- **Host-level features**: `/update/*`, `/system/*` (reboot, Tailscale), the external drive, the root watcher. These
  are the operator's. The Docker deploy kind already drops them (connectivity.md, "Docker and startup"), so a hosted
  build starts from it. **S.**
- **Public admin with real auth.** The gate passes only loopback and tailnet unless `ADMIN_PUBLIC=on`
  (S/gate.rs:65). Argon2, mandatory TOTP, `__Host-` cookie, CSRF and per-IP bans are already good enough to face the
  internet. What a hosted service lacks:
  - sign-up or invites;
  - account recovery without the operator's CLI (`reset-2fa` runs on the host; recovery codes or e-mail);
  - per-family quotas (music uploads up to 300 files x 500 entries, wallpapers, APK uploads);
  - an SSRF rule: RSS feeds may be fetched on the LAN (`Reach::Lan`, music.md:104-113). Hosted, "the LAN" is the
    operator's network, so it must be off.

  **L.**
- **GDPR:** the server holds children's location history, call state, app lists, PIN events, contact photos, and
  Storytel/Spotify credentials. Hosting other families' data makes the operator their processor or controller.
  Deletion and export per family are needed (Art. 15/17/20). In one database, deleted data lives on in every backup
  until it ages out. One file per family deletes cleanly (file, dir, its backups, its key salt). **M** of code, plus
  paperwork.
- **SQLite at scale:**
  - Every status report stores a full installed-app snapshot (S/handlers/device_api.rs:1153-1169, no dedupe),
    kept 30 days. The phones report at least every 30 minutes and on every nudge (L/push/BackstopAlarm.kt:22).
    `device_status` dominates the file.
  - With one database, every family shares one WAL writer. A backup or pre-migration copy also grows with the total
    of all families.
  - With one file per family, each file stays the size of today's Pi database.

## 2. Three architectures

### (A) One database, `family_id` everywhere (row-level tenancy)

What it takes:

- `family_id` on ~26 tables, with UNIQUEs made per family.
- An ownership check at all 104 parameterised routes, and a review of ~650 query sites.
- Jobs per family, keys per family, and the export/import above.

SQLite has no row-level security, and sqlx's runtime queries aren't checked at compile time. One missed `AND
family_id = ?` lets one family see another's data. With children's locations behind it, that's the worst bug this
project could ship. It also isn't a one-time cost: every future feature, and every agent-written query, must remember
the filter.

Making it structurally safe:

1. **Device boundary.** A `Family` extractor (from the session) and an `OwnedDevice` type that only
   `family.device(id)?` can make (one ownership check, 404 otherwise). Device-keyed tables (23 of 49) are then reached
   only through an `OwnedDevice`.
2. **DB access in one place.** `AppState.db` becomes private to a `store` module whose functions take `&Family`. A
   test fails on any `sqlx::query` outside `store/` (today it's in 50 files).
3. **Composite foreign keys.** `(family_id, contact_id) -> contacts(family_id, id)` and so on, so the DB refuses
   cross-family links. In SQLite that means rebuilding most tables in frozen migrations. **L** of migration risk on
   its own.
4. **An isolation test suite.** Two families in `TestApp`. Every route from `main.rs` is called with the other
   family's ids and must answer 404. It can be generated from the route list, like
   `tests::listeners` already reads `device_routes.rs`.
5. One could shadow tables per connection with `TEMP VIEW`s filtered by family (SQLite resolves temp names first).
   That's clever but fragile with a shared pool and writes, so it's not recommended.

**Size:** 16-22 steps (XL+), and a permanent tax on every feature. The only win: cross-family queries for an operator
are one SQL statement.

### (B) One process, one SQLite file per family

- **Layout:** `data/control.db` holds families, logins (username -> family, memberships), sessions, and two global
  indexes: token hash -> (family, device) and live setup-code hash -> family. Each family gets
  `data/families/<id>/` with `kidphone.db`, its file stores, its backups and its key salt.
- **Routing:** an admin request goes through session -> family. A phone goes through the token index -> family; a
  setup code goes through the code index. The phone needs no family in the URL.
- **What stays as it is:** all ~650 queries and all 9 singletons keep their SQL text. A handler can't reach another
  family's rows without opening another pool on purpose.
- **What changes:**
  - `AppState` splits into `Shared` (config, net, limits, the catalog cache, DNS feeds) and `FamilyCtx` (pool,
    dirs, keys, Storytel/Spotify sessions, library cache, sweep handle).
  - The 218 `State<AppState>` extractors move to a `Family` extractor. This is mechanical but touches every
    handler.
  - In-memory maps key on (family, device).
  - Jobs become one scheduler over families.
  - Migrations run per file, lazily at first open or all at start. Each file gets its own pre-migration backup.
  - Pools open lazily with an idle timeout, so 1000 families don't hold 1000 x N connections.
- **What it costs:**
  - N pools.
  - Migrations x N. A broken migration fails per family, which needs a "family offline" state, not a dead server.
  - Per-family jobs.
  - Cross-family operator views iterate the files (usage, last seen). They're rare and fine.
  - Sharing (catalog rows, the NRK sweep of one series for 100 families) needs a deliberate shared cache.
- **Today's install** becomes family 1 by moving the file. The single-family self-hosted mode is (B) with one family,
  the same code path.

**Size:** 10-12 steps, XL overall (§5).

### (C) One instance per family (containers)

Today's Docker image per family: its own `data/` volume, its own keys, a port and a Caddy site (a subdomain). The
phones already take the server URL at enrolment. Isolation is total, and backup, restore and GDPR deletion come free
with the volume.

What it costs:

- An ops script (create, update all, remove) and a Caddy template.
- N processes. The server is built for a Pi Zero 2 W (server/CLAUDE.md:47), so each is small, but measure RSS
  before promising a number.
- N sweeps.
- The GitHub limit: two catalog rows per instance hit 60 per hour per IP at about 30 instances. It needs
  `GITHUB_TOKEN` (§1.3).

What it can't do:

- one login for a parent in two families (co-parents in two households);
- a shared catalog or download cache;
- an operator console, quotas or billing;
- sign-up.

**Size:** 2-3 steps (M + S + S).

## 3. Multi-child

- **Several phones per family exist already**: `devices`, each with its own policy, contacts, time-rule override,
  music ticks (`device_music_entries`) and Spotify binding (one account per phone, music.md:266).
- **There is no child entity.** A phone has only a `name` (`devices`: id, name, setup-code and token columns).
  A child would add:
  - a `children` row (name, picture) that groups that child's phones, a later phone (today's re-enrol into the same
    row keeps history), and a vibb box (design 61);
  - time rules per child, shared by their devices, between the family default and the per-phone override;
  - the Spotify account and the music ticks per child, so a new phone or a box inherits them;
  - Locate and notifications worded by child.
- **This is family-internal.** Under (B) it's ordinary single-file work, independent of tenancy, and the policy
  contract is unchanged (the server still resolves everything per device). **M-L, 2-3 steps.** It can come before,
  after or without multi-family.

## 4. The phone and music-app side

- **Nothing changes** if a phone is routed by its token (B) or the family has its own host name (C).
  - The token is already the phone's whole identity (`DeviceRef { id }`, S/security.rs:424-429).
  - The enrol code is unique, so a global code index finds the family without changing the code's shape
    (S/enrollment.rs:25-27).
  - The music app reaches the server only through the launcher (`L/music/MusicServer.kt:49-70`, bridge), so it
    changes nothing either.
- **Avoid a path prefix** (`https://host/f/<family>/`). Retrofit's relative routes would work
  (`@GET("api/devices/policy")`, L/server/MdmApi.kt:39-67). But the catalog's `download_url` is root-absolute
  (`/api/devices/apps/{id}/download`, S/handlers/device_api.rs:1902) and would skip the prefix.
- **Hosted means no tailnet.** Phones use the existing public phone listener (`DEVICE_BIND_ADDR`) over HTTPS. The
  launcher already falls back to plain networking when tsnet isn't running (L/server/MdmApi.kt:117-129). The
  provisioning QR then carries no Tailscale key.
- **The contract files** (`phone_vectors.json`, the policy snapshot, `music_*.json`, `spotify_hosts.json`, and the
  others in the root CLAUDE.md) don't change.

## 5. Recommendation

**Order:** multi-parent (separate assessment) -> hygiene (now) -> (C) if families are wanted at all -> (B) only when a
hosted service is decided. Multi-child is independent and can come anywhere.

**(C), the self-hosted answer, 2-3 steps:**

1. `GITHUB_TOKEN` for the catalog sync. **S.**
2. `deploy/multi/` (or a DEPLOY.md section): a compose template per family, a Caddy snippet per subdomain, "update
   all", "remove a family" with its volume. **M.**
3. Docs: the operator is a GDPR processor for each family. **S.**

**(B), the hosted answer, about 10-12 steps:**

| # | Step | Size |
|---|---|---|
| 1 | `control.db`: families, logins and memberships (on top of multi-parent), sessions, token and setup-code indexes | L |
| 2 | Split `AppState` into `Shared` + `FamilyCtx`; `Family` extractor for admin and device routes (218 handlers, mechanical) | L |
| 3 | Per-family dirs and keys (HKDF per family, family in the AAD); today's install becomes family 1 | M |
| 4 | In-memory state keyed by (family, device): streams, nudges, limits, library cache, Storytel/Spotify sessions | M |
| 5 | One scheduler over families for the 12 jobs; fair music fetch queue; lazy pools, per-file migrations, "family offline" | L |
| 6 | Catalog split: operator rows + shared APK cache, family-private rows | M |
| 7 | Hosted mode: `ADMIN_PUBLIC`, `Reach::Lan` off, no tailnet provisioning, quotas, throttles sized per family | M |
| 8 | Sign-up/invite, recovery without the CLI (recovery codes; e-mail optional) | L |
| 9 | Operator console: families, usage, suspend, delete | M |
| 10 | Per-family backup, export and deletion (GDPR) | S-M |
| 11 | Isolation test suite: two families, every route with the other's ids, every job | M |

**Cheap hygiene now** (it keeps (B) and (C) cheap and makes (A) no worse):

- **No new hard-coded `data/...` paths.**
  - Derive every path from `state.data_dir`.
  - Today `main.rs:437-439`, `music.rs:34-38`, `tracked_apps.rs:29` and the three key files are constants.
  - Moving them is S and helps (B) and the tests.
- **Instance-wide state goes in env/`config.rs`, never in the family's database.** Don't add tables that mix "the
  operator's" and "the family's" data. Family singletons (`CHECK (id = 1)`) are fine under (B), so keep them.
- **New in-memory maps go through a key type** (`DeviceKey`), not a bare `i64`. Then (B) changes one type.
- **No family or server identity in the device API.**
  - Keep phones routed by token, and keep the URLs the server sends relative.
  - Fix the leading `/` of `download_url` when that code is next touched.
- **No new server-wide "one at a time" gates** without a note on fairness.
- **Dedupe the installed-app snapshot** in `device_status`. It's worth doing for the Pi anyway.
- **Not recommended now: a `family_id` column convention.** Under (B) and (C) it's dead weight. Under (A), a column
  without the extractor and tests gives false safety.

## 6. Open questions for the user

1. **Hosted or self-hosted?** Will you run one server for other families, or does each family (or a technical friend)
   run their own? This decides between (C) and (B).
2. **Who are the families?** Relatives and friends (5-20, trusted) or strangers (sign-up, abuse, support)? For the
   first, (C) is enough.
3. **Open-source feature or a path to a commercial service?** GPL-3.0 (not AGPL) lets anyone host it without sharing
   changes. If a service is the goal, decide that before building (B) in the open.
4. **Can a parent belong to two families** (co-parents in two households)? Only (B) can give one login for both.
5. **Storytel and Spotify when hosted:** are you willing to hold other families' Storytel passwords? Should each
   family still bring its own Spotify developer app? A shared app caps at 5 users.
6. **Is the tailnet dropped when hosted?** Phones would then talk to a public HTTPS listener only.
7. **Multi-child:** do you want a child entity (phones + box + rules + accounts per child)? It's worth doing on its
   own, whatever the answer to 1.
8. **GDPR:** as an operator in Norway you'd answer to Datatilsynet. Is that acceptable, or is that the reason to stay
   with (C) run by each family?
