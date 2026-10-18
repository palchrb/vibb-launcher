# QA 36 step 1 (code) - a custom DNS server with a parent-chosen backup: server + wire

Code QA, 2026-10-18. Commit under review: `c40a5fdf` (branch `dns-s1`, base `main` `c570886e`). Read against
`docs/design/36-custom-dns.md` (§2-§8 and "Step 1 implementation status"), `docs/design/qa-36-design.md`, the root and
server `CLAUDE.md`.

**Verdict: good to merge after the two Medium test gaps are closed.** There is no High finding. The grammar
matches every row and refusal in §2, including the "can never be a server" list after IPv4-mapped/-compatible mapping,
`host:443`, `1.1`, octal/hex, `%`, and `tls://host/` taken as DoT with no path. Equality is by `normalized()`.
Policy: `dns_upstream_provider` stays `"cloudflare"` under Custom, and `dns_upstream` is null for any pair that isn't
valid. The status report is stored through known words only. The PWA form is write-only, a refused save comes back
in place as a 400 with `no-store`, and the security event carries kinds only. The address never reaches a log, URL
or redirect, and backups keep both values (pinned by a test). Every redirect goes to `/dns#server`, and
`check_forms` covers the new form. The contract copies are byte-identical, and the CI path filters and the root
`CLAUDE.md` are updated. Two guards are correct in the code but no test pins them: auth on the new POST, and
write-only on the refusal page. The mutations below show both.

## Runs

| Check | Result |
|---|---|
| `cargo test --locked` (server) | 983 passed, 0 failed, 0 ignored |
| `cargo fmt --check` | clean |
| `cargo clippy --all-targets --locked` | 13 warnings, the known 13 (no new ones) |
| `node --test jstest/` | 34 passed |
| launcher `testDebugUnitTest -PwarningsAsErrors=true` | 1296 passed, 0 failed |

## Findings

### Medium

**M1. No test pins the session on `POST /dns/upstream/custom` (or on `set_upstream`, which now records an event).**
`server/src/main.rs:1135` puts the route in `admin_routes`, behind `require_full_auth`, and the global `csrf_guard`
covers it. The code is right. But mutation 7 (move the route to `public_routes` and drop the `CurrentAdmin`
extension) passes `custom_dns`, `settings_pages` and `admin_auth`, so a later refactor could expose an unauthenticated
write of the family's DNS server.
*Fix:* add both routes to a "refuses without a session and writes nothing" test, like
`time_rules::new_admin_routes_refuse_without_a_session_and_write_nothing`. It should check for a redirect to `/login`
with nothing stored and no event, and send one cross-site POST (`Sec-Fetch-Site: cross-site`) with a valid session
to check for a 403.

**M2. Write-only on the refusal page is untested.** `refusals_render_in_place_and_write_nothing` runs with nothing
stored, so an echo of a stored value can't show up. Mutation 9 (the server input falls back to
`settings.custom_server` when its field was left empty) passes every test.
*Fix:* store a pair (a DoH URL with an id in the path, plus a backup), then post a refusal that leaves the server
field empty and types a bad backup. Assert that the 400 body contains neither stored value nor its id (only the
hints), and that the server input is `value=""`.

### Low

**L1. Hints and the backup prefill depend on whether the stored *pair* is valid, not on each stored value**
(`server/src/handlers/dns_filter.rs:454-463`, and the picker refusal at `:537`). §6 says the backup field is
prefilled "when none is [stored]", with one hint per field. With a stale or equal stored pair:
- Both hints say "Not set.", while Forget shows and the footer says "The saved addresses aren't accepted any more".
- The backup input shows `1.1.1.3` although a backup is stored. A parent who then types only "Your server"
  silently replaces a valid stored backup with Cloudflare for Families.

The same happens on the picker's 400, which always prefills.
*Fix:* compute each hint from its own `parse_dns_server(stored)`. Prefill only when `custom_backup` doesn't parse
(empty or stale). Keep `stale` as it is, for the footer line.

**L2. A successful save folds the Custom Server row back up** (`server/templates/dns_filter.html:136`, redirect
`/dns#server`). There is no jump: scroll-restore keeps `#server`, the form's ancestor, in place. But the house rule
("a form in a closed `<details>` returns with it open (`?open=`)", server `CLAUDE.md`, the 6f fix for "Show on
phones as") is not followed. The only sign that the save worked is the new hint in the summary's sub-line.
§6 literally says 303 `/dns#server`, so this is the orchestrator's call. Either redirect to
`/dns?open=custom-server#server` (and render the row open), or accept the closed row and its sub-line as the
confirmation.

**L3. The picker's 400 always puts "Type your server's address first." under "Your server"**
(`dns_filter.rs:537-538`), even when a valid server is stored and only the backup is missing, stale or equal.
*Fix:* reuse `set_custom`'s per-field logic with both fields empty. The missing or equal field then gets its own
reason: `BACKUP_NEEDED` or `BACKUP_SAME` under the backup.

**L4. Phones that aren't set up get a footer line** ("Kari · Waiting for the phone", `dns_filter.rs:296-304`,
`FROM devices d`, no enrolment filter). A phone that was never connected isn't waiting for anything.
*Fix:* leave out phones without `enrolled_at`, or say "Not set up".

**L5. Merging with design 35 step 1 (`notif-s1`, `50574052`): conflicts and silent overlaps.**
- (a) `device_api::status`'s INSERT: both add columns. The merged list needs 39 placeholders and both binds, in
  order.
- (b) `migrations_73_to_79_in_order` must list `(78, "parent events")` and `(79, "custom dns")`.
- (c) The server `CLAUDE.md` "Data model" paragraph.
- (d) Both branches bump `head.html` from `style.css?v=30` to `?v=31` and both append to `style.css`. Git merges
  this cleanly, with no conflict. Bump to `?v=32` at the merge, or the second deployed stylesheet can stay cached.

Nothing else in this commit needs to change for the migration order (open point 1, below).

### Nits

- **N1.** `server/src/dns_server.rs:274` also strips a trailing `{?dns}` from `tls://` and scheme-less values
  (`tls://dns.example{?dns}` is taken as DoT). It is harmless. Strip it only when the value is DoH.
- **N2.** `240.0.0.0/4` (reserved) and NAT64 forms of loopback (`64:ff9b::7f00:1`) are allowed. §2 doesn't list
  them, and there is no SSRF surface (the phone is the only client). Leave as is unless wanted.
- **N3.** `server/src/tests/custom_dns.rs:220`: 14 refusals × 2 fields is 28 full page renders over HTTP. It
  repeats `dns_server::tests::refuses`, and the handler only maps a `Refusal` to its text. Leaner: one refusal per
  field over HTTP, plus a unit test that every `Refusal` has its own non-empty text (the memory's "lean tests").
- **N4.** A two-label name is shown whole in the hint (`abc123.example`), per §2 ("more than two labels"). A client
  id in that position would show. That is the spec's choice. Mention it in the ⓘ's advice to keep an id in the path.

## The implementer's open points

1. **0079 before 0078.** Nothing more is needed in this commit if both step 1s merge before any release. The test
   already names the rule. Hazard to say out loud: never run a build of `dns-s1` alone on the Pi or on a long-lived
   dev/emulator database. sqlx would later apply 0078 out of order, and `pre_migration_backup` (`MAX(version)`)
   would take no backup. A database that ran it needs a fresh `data/`. At the merge, see L5 (b).
2. **"Waiting for the phone" vs "Web filter off".** Acceptable. §4.2 sends the field only while the VPN runs, so the
   server can't tell a paused VPN from a phone that hasn't taken the new `rev` yet. The server-side switch is the
   only "off" it can know.
3. **The picker's 400 keeps "Custom server" selected.** Acceptable: it shows the choice the parent made next to the
   reason, and choosing again saves normally. See L3 for the wording. **The 400's URL is the POST URL (a reload posts
   again).** Acceptable, and the same as the other refusal pages (calls, time rules, app display, Phone Setup):
   `no-store` makes the browser ask before resending, and a resend writes nothing.

## Mutations (each reverted; tree clean afterwards)

| # | Mutation | Caught by |
|---|---|---|
| 1 | `localhost`/`*.localhost` no longer refused | `dns_server::tests::refuses` |
| 2 | `same_as` compares the host only | `dns_server::tests::normalized_parses_to_itself` (`custom_dns` passes: its equal case shares the host) |
| 3 | `hint()` includes the path | `dns_server::tests::hint_hides_the_path_and_the_first_label`, `custom_dns::a_custom_server_is_saved_write_only_and_lands_on_its_group` |
| 4 | `valid_pair` accepts equal servers (the policy emits `dns_upstream` for an invalid pair) | `custom_dns::custom_without_a_valid_pair_is_cloudflare` |
| 5 | the provider word `custom` sent under Custom | `custom_dns::custom_server_in_the_policy`, `custom_without_a_valid_pair_is_cloudflare` |
| 6 | refusal without `Cache-Control: no-store` | `custom_dns::refusals_render_in_place_and_write_nothing` |
| 7 | `/dns/upstream/custom` moved to the public routes, no `CurrentAdmin` | **NOT caught** (M1) |
| 8 | the save redirects to `/dns` without `#server` | `custom_dns::a_custom_server_is_saved_write_only_and_lands_on_its_group` |
| 9 | the refusal page echoes the stored server into its input | **NOT caught** (M2) |
| 10 | the backup prefilled while one is stored | `custom_dns::a_custom_server_is_saved_write_only_and_lands_on_its_group` |
| 11 | the status `reason` kept with `in_use: custom` | `dns_server::tests::sanitize_dns_upstream_keeps_known_words_only` |
| 12 | the event detail carries the host | `custom_dns::a_custom_server_is_saved_write_only_and_lands_on_its_group` |
| 13 | launcher `customUpstreamFrom`: a quoted port (`"853"`) accepted | `PolicyResponseCompatTest` (`dns_upstream never fails the policy ...`) |
| 14 | launcher: a missing backup falls back to the primary | `PolicyResponseCompatTest` |
