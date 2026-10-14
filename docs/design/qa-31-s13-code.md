# QA 31 step 13 (code) - the Locate tab

Reviewer: code QA, 2026-10-13. Branch `design-31-s13`, `474a6015..93925e36` (one commit, 15 files, server and docs).
I read it against `docs/design/31-pwa-redesign.md`:
- §1.8, §3.0, §3.6 (Locate), §3.10, §4, §5 (§5.1 incl. §5.1.4, §5.2, §5.5, §5.6), §8 step 13 and §9;
- the step 12 status and fix-round table, and "Step 13 implementation status" with its choices;
- design 33 (OSM tiles direct, origin-only Referer, the CSP);
- `qa-31-s4-code.md` and `qa-31-s12-code.md` for the format and the bar.

I also read the code the step touches or leans on:
- `handlers/locate.rs` in full, the `models.rs` diff (`CommandStatus`, `CancelledBy`, `Unconfirmed`, `command_label`),
  `device_api::command_result` (what the phone's answer is stored as), `display_time::Zone`;
- `device_locate.html` in full, the CSS diff, `static/scroll-restore.js`;
- the launcher's answers (`MdmSyncWorker.dispatchPendingCommand`, `locateResultMessage`, `wipeResultMessage`,
  `endWindowsResult`), to check `reword` against what phones really send;
- `tests/locate_page.rs` in full and every repointed test (`locations.rs`, `command_receipts.rs`, `command_expiry.rs`,
  `time_rules.rs`);
- the docs diff (`server/CLAUDE.md`, `launcher/CLAUDE.md`, `docs/testing/emulator.md`).

Line numbers are `93925e36`'s. No product code was changed: the mutations below were temporary and are reverted, and
`git status` was clean before this file was written.

**Verdict: merge after a short fix round.** The tab is what §3.6 and §8 step 13 ask for, and the parts that matter
held up:
- **Erase Phone…** still needs the phone's exact name (`wipe`, `:1032-1038`): a wrong, empty or differently cased
  name re-renders the page with the confirm open and queues nothing (pinned, and a case-insensitive mutation is
  caught); the name is checked against the phone in the form's own URL, so it can only erase that phone; the
  `<details>` confirm needs no JS and the typed name never reaches a URL.
- **Ring, Stop Ringing, Lock, Update Location, Erase** keep their POST routes, so auth and CSRF are the admin router's
  as before (`main.rs` is untouched: they sit in the admin router behind `require_full_auth` and `csrf_guard`). `queue_command` checks the phone inside its transaction: an
  unknown id is a 404 with nothing queued (pinned for all five), a DB error a 500 "nothing was queued".
- **No API or contract change:** `device_api.rs`, `server/testdata/` and the launcher are untouched, so
  `pending_command` and the policy JSON are as they were (`policy_json_keys_snapshot` and the launcher's
  `PolicyResponseCompatTest` don't move). `locate-result.json`'s new `text` is read only by this page's script;
  `result` stays, so nothing that read it before breaks.
- **Design 33 is kept:** the tile layer, `referrerPolicy: 'strict-origin-when-cross-origin'` and the CSP's `img-src`
  are unchanged and pinned (a mutation is caught); no place name, no reverse geocoding, nothing new leaves the
  server - the popup is built from server text with `textContent`.
- **The redirect table** is built from the rendered forms in page order, fails on an unlisted form, checks that every
  fragment names an element and that each auto-save has a `<noscript>` Save. Update Location → `#location`, the
  actions → `#actions` (Recent right under it), Cancel → `#recent` with its line - all as the status says.
- **Texts:** times are local through `Zone` (no "UTC" outside the fallback zone, pinned in Tokyo time); the command ids
  became labels; `reword` covers every answer today's launcher sends; everything a phone sends is escaped by askama
  or goes through `js_string` / `textContent`.

What needs fixing:
- **M1:** the two Location Settings pickers are the last group of a long page, and their `<noscript>` Saves land at
  the page top; so do both pickers, even with JS, after a refused erase.
- **L1-L3:** the poll's `#location` reload and the escaping of a phone's answer are pinned by nothing (two mutations
  survived); the server's own placeholder answers "ok" / "failed" show as the row's line.

Counts: 0 High, 1 Medium, 3 Low, 3 Nits.

## What I ran

All cargo runs used my own target dir (`~/.cache/handy-target/qa-31-13`), under `~/build.lock`.
- **`cargo test`, unchanged:** 775 passed, 0 failed (the implementer's count).
- **`cargo fmt --check`:** clean.
- **`cargo clippy --all-targets --locked`:** the same 13 warnings as on main (`models.rs` 9, `dns_engine.rs` 2,
  `storytel.rs` 1, `devices.rs:1414` 1). None is in the step's code.
- **`node --test server/jstest/*.test.js`:** 16 passed.
- **The mutations.** Each was run against the named filters, then reverted:

  | # | Mutation | Caught by |
  |---|---|---|
  | 1 | the erase name compared case-insensitively | `locate_page::a_wrong_name_opens_the_erase_confirm_with_the_refusal` |
  | 2 | `queue_command` without the unknown-phone `return Ok(false)` | `locate_page::unknown_phones_and_failed_reads` |
  | 3 | `queue_command`'s existence read on the pool instead of `tx` | **nothing** (not observable without fault injection; no finding) |
  | 4 | the poll's `location.replace(…)` without `#location` | **nothing** (L1) |
  | 5 | no `<noscript>` Save on "Keep history for" | `locate_page::every_form_on_the_locate_page_returns_to_its_group` (`check_forms`) |
  | 6 | Recent's asked time as `%H:%M UTC` instead of `zone.when` | `locate_page::recent_names_each_command_…`, `locate_page::the_fix_and_the_answer_…` |
  | 7 | Recent's line `{{ detail\|safe }}` | `locations::a_delivered_command_is_not_shown_as_done` - only through the apostrophe in "can't confirm", not a phone's answer (L2) |
  | 7b | the Update Location answer `{{ a.answer.text\|safe }}` | **nothing** (L2) |
  | 8 | the tile layer's `referrerPolicy: 'unsafe-url'` | `locations::the_map_loads_osm_tiles_with_an_origin_only_referer` |
  | 9 | the erase form's action without `#erase` | `every_form_on_the_locate_page_returns_to_its_group` |
  | 10 | "Every" shown whatever the mode | `locate_page::warnings_and_location_settings` |
  | 11 | `locate-result.json`'s `text` = the raw result | `locate_page::the_fix_and_the_answer_read_in_local_time` |
  | 12 | Ring, Stop Ringing, Lock and Erase back without `#actions` | `every_form_on_the_locate_page_returns_to_its_group` |

  Three of thirteen survived (3, 4, 7b); 7 was caught by accident.

## Medium

### M1. Location Settings is the last group of a long page; its Saves land at the top
`src/handlers/locate.rs:937` (`location-retention`), `:998` (`location-policy`); `templates/device_locate.html:132-163`.

Both pickers redirect to `/devices/locate?device={id}` with no fragment, so only `scroll-restore.js` keeps the place.
That holds with JS on this page, but not in two cases:
- **Without JS** (§5.2: every auto-save has a `<noscript>` Save for this path), the Save reloads the page at the top.
  Location Settings is the last group, under the map (300 px), the fix rows, the warnings, the action tiles, Erase
  Phone…, the footer and Recent's 10 rows: on a 390 px phone the parent lands well over a screen above what they
  just saved. §5.1.4 drops the fragment only for "same-page auto-saves on **short** pages"; qa-31-s3-code M3 accepted
  the top landing because Advanced is short. This page is not.
- **After a refused erase, with JS:** the refusal renders at the POST's URL, `/devices/{id}/command/wipe` (choice 9).
  `scroll-restore.js` restores only on the same path, so a picker changed from that page lands at the top too.

**Fix:** redirect both to `/devices/locate?device={id}#location-settings` (the section already has the id) and give
the two forms' actions the same fragment, then move their rows in the redirect table. With JS the cost is small:
the group is the last on the page, so the browser can scroll it only as far as the page's end, which is where the
parent already is. (If an exact restore with JS is wanted, the `<noscript>` Save buttons could carry a
`name`/`value` that `autosave.js`'s `form.submit()` never sends, and the handlers add the fragment only then; more
code for little.)

## Low

### L1. The poll's reload to `#location` is pinned by nothing
`templates/device_locate.html:230`.

After Update Location the page polls `locations.json` and, on a newer fix, reloads with
`location.replace('/devices/locate?device={id}#location')`. That reload is the JS half of "Update Location lands at
the map". Mutation 4 (dropping `#location`) passed every test; the reload would then land at the top, since a
`location.replace` is no form submit and `scroll-restore.js` saved nothing.

**Fix:** in `the_fix_and_the_answer_read_in_local_time` (or `time_rules::update_location_now_queues_locate`, which
already loads `?requested=1`), assert the page contains
`location.replace('/devices/locate?device={id}#location')`.

### L2. Nothing pins that a phone's answer is escaped
`templates/device_locate.html:41` (the Update Location answer), `:109` (Recent's line and its `title`);
`src/handlers/locate.rs:212-227` (`answer`, choice 8).

Choice 8 shows an answer this server doesn't know "as the phone sent it". That text comes from the phone (up to 500
characters, `device_api::command_result`), so it falls under security review H2's rule: nothing a phone sends
reaches the PWA as HTML. Askama escapes it today, but mutation 7b (`|safe` on the answer row) passed every test, and
mutation 7 was caught only because a fixed status line contains an apostrophe. The old page's test
(`the_locate_page_builds_no_html_from_stored_values`) covers the stored fix, not the answer.

**Fix:** extend `the_locate_page_builds_no_html_from_stored_values`: store `<img src=x onerror=alert(1)>` as the result
of a `locate` and of a `lock`, and assert the page has `&#60;img` (or `&lt;img`) and no `<img src=x` - in the
answer row, Recent's line and a reworded row's `title` - and that `locate-result.json` returns it as plain JSON text
(the script shows it with `textContent`).

### L3. The server's placeholder answers show as the row's line: "Done · ok"
`src/handlers/locate.rs:212-227`; `src/handlers/device_api.rs:1544-1546`; pinned the wrong way in
`src/tests/locations.rs` (`a_delivered_command_is_not_shown_as_done`, `row("locate").contains(">ok<")`).

When a phone answers without a message, `command_result` stores the server's own `"ok"` or `"failed"`. `reword`
doesn't know them, so Recent reads "Update Location · 10:58 · Done" with "ok" under it, or "Failed" with "failed".
These are not the phone's sentence (choice 8's reason) but a code the server made up, and the row's word already
says it.

**Fix:** in `answer`, treat `"ok"` and `"failed"` like an empty answer (`None`), so the row shows its word and no
line (or the status line). Repoint the `locations.rs` assertion to "Done and no `row-sub`".

## Nits

- **N1.** The wait's last line (`device_locate.html:248`) says "it gets the request when it is back", but Update
  Location expires after an hour (the footer says so two groups down). "No answer yet. The phone may be offline; it
  gets the request if it is back within an hour."
- **N2.** A wipe whose call returned while the system went down answers "wiping (…)" with `succeeded = 1`, so its
  row reads "Erase Phone · Done" with "Erasing" under it, while a wipe known only from its receipt reads "Erasing"
  (orange) with "An erased phone stops reporting." Both mean the same; mapping a `wipe` with a successful answer to
  `CommandStatus::Erasing` would make them one row.
- **N3.** `locate-result.json`'s 500 on a failed read is untested (`unknown_phones_and_failed_reads` checks the page
  and `locations.json`); one more `get_page` after the rename of `device_commands` would pin it.

## Checked and fine

- **The implementer's choices:** Recent under the actions with 10 rows (1, 3), the actions landing on `#actions` (2),
  "Every" only for Regularly with the stored interval kept (4, the handler's `COALESCE`), `aria-current="true"` in the
  switcher (5), phones not set up in the switcher (6; an enrolment cancels what waited, as before), design 24b's
  labels (7), unknown answers shown escaped (8, but see L2 and L3), the erase refusal in the POST's answer (9, but
  see M1), the security log left in UTC (10, step 14).
- **`queue_command`:** the existence check, the replace, the used-up mark and the insert share one transaction; an
  early `return Ok(false)` drops (rolls back) it; the security events and the nudge come after the commit.
- **Reads:** every read on the page, `locations.json` and `locate-result.json` is a 500 on a DB error. A missing
  `device_policy` row falls back to a new phone's defaults, the same rule `device_pages::load_policy` keeps.
- **The poll:** it shows the reworded `text` with `textContent`, keeps polling while the answer is a fix (now also
  "fix, 3 s old" without an accuracy, which the old `'fix '` prefix stopped on), and stops on any other answer.
- **Words:** "10 min ago" / "Within 40 m" with the exact local time in `title`; the popup says the same or
  "Newest location" for a newer point; Recent's lines carry no "UTC", "sync", "launcher" or "deliver" (unit test);
  every launcher answer has a §4 rewording, and the raw one stays in `title`.
- **Layout:** `.map-box` is its own stacking context under the fixed tab bar; the CSS uses no `:has()` or nesting
  (§5.7); `style.css?v=16` (step 5 may take 15; resolved at the merge, as the status says).
