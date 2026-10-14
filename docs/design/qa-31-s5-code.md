# QA 31 step 5 (code) - Home Screen and Web Filter

Reviewer: code QA, 2026-10-13. Branch `design-31-s5`, `58e97c1f..f15e30c2` (one commit, 27 files, server and docs).
I read it against `docs/design/31-pwa-redesign.md`:
- §1.2, §1.9, §3.0, §3.2 (Home Screen, Web Filter), §4 (§4.2, §4.5 #3/#11-#13), §5 (§5.1 incl. §5.1.4, §5.2, §5.5,
  §5.6), §8 step 5 and §9;
- "Step 4 implementation status" with its fix-round table, and "Step 5 implementation status" with its 9 choices;
- `qa-31-s4-code.md` and `qa-31-s12-code.md` for the format and the bar.

I also read the code the step touches or leans on:
- `handlers/device_pages.rs` (`view_home`, `view_filter`, `notification_access_note`, `read_failed`, the loaders),
  the `devices.rs` diff (`update_policy`, `update_launcher_ui`, the device page), all of `handlers/dns_filter.rs`'s
  diff (`FeedState`, `back_to`, `write_failed`, the domain and list routes, `/dns`, `/dns/log`, `set_dns_log`),
  `handlers/wallpapers.rs`, `attention.rs`, `dns_engine::looks_like_a_domain`, `display_time.rs` (`when`, `exact`,
  `parse_db_utc`, `Zone::load`);
- `device_home.html`, `device_filter.html`, the `device_detail.html`, `dns_filter.html`, `dns_log.html`, CSS and head
  diffs;
- every changed test, `tests/device_pages.rs`'s step 5 part in full;
- `scroll-restore.js`, `autosave.js`, the admin router (`main.rs`), `csrf_guard`, the `dns_custom_domains` and
  `device_blocklist_overrides` schema;
- the docs diff (`server/CLAUDE.md`, `server/DEPLOY.md`, the spec's status section).

Line numbers are `f15e30c2`'s. No product code was changed: the mutations below were temporary and are reverted, and
`git status` was clean before this file was written.

**Verdict: merge; the fix round is small and can go with the next step.** The step does what §8 step 5 asks:
- two GET routes, `/devices/{id}/home` and `/devices/{id}/filter`, inside the admin router's full-auth, CSRF
  (`Sec-Fetch-Site`/`Origin`) and session layers, next to `/lock`; unknown phone 404, every read the page needs a 500;
- H1 is right: `section=quick` / `section=filter` bind `NULL` for the other part and `COALESCE` keeps it, no section
  writes both (an older tab), an unknown section is a 400 before anything is written, `kiosk_desired = 1` and the
  lock-task features in every case; the split test covers both directions and the no-section case;
- every form on both pages returns to its own page with **no fragment**, so `scroll-restore.js` restores the exact
  place with JS; every auto-saving control has a `<noscript>` Save inside its own form; the add rows are plain
  submits whose `submit` event `scroll-restore.js` also hooks. The redirect table is built from the rendered forms in
  page order and posts each one (§5.1.6);
- `/dns?device=X` → `/devices/X/filter`, `/dns/log?device=X` → `/devices/X/filter#log` (a parsed `i64`, so nothing
  from the query reaches the `Location` unparsed); no link in the server still points at the old URLs or `#launcher`;
- the phone for a write comes from the path (`/override`, `/dns/log/{id}`, `/policy`, `/launcher`, `/wallpapers`)
  or from the deleted row (`DELETE … RETURNING device_id`); `back` only chooses between two fixed targets; a
  non-numeric `device_id` is a 400, never "every phone"; foreign keys are on, so an unknown phone or list is a 404;
- log entries, domains, categories and list names are askama-escaped (pinned with `<b>`/`<i>` payloads); the raw
  feed error stays on the global page; a phone's garbled `blocked_at` reads "Time unknown";
- the adb command and package names are only behind the warning's ⓘ (`note_warning.html`); no "UTC" in the visible
  texts; the Attention row and the page share `notification_access_note`, so they can't disagree;
- no API change: `policy_json_keys_snapshot` passes untouched, `launcher/` is not in the diff, the DNS engine only
  had `looks_like_a_domain` made `pub(crate)`.

On the eight choices the review was asked to look at:
- **(1) The inline select** (Default / On / Off) is the §3.0 picker pattern and one control per row. Keeping
  `clear` / `enabled` is right and costs two match arms: a tab still open on the old `/dns?device=` page posts them,
  and the mapping (`clear` → default, else the checkbox) is exactly the old meaning. It can go with step 14.
- **(2) The stricter routes** refuse nothing a phone could use: the launcher matches exact names, so a URL, a
  wildcard, a bare word or a non-ASCII name stored before never blocked anything. No old client path breaks: the
  global page's forms post no `device_id` (every phone), its Remove forms send an empty urlencoded body, which
  `Form` accepts. The cost is the refusal's page (L1).
- **(3) A second Remove landing on `/dns`** is a jump to another page and another tab (M1).
- **(8) A refused domain as a bare 400** - see L1: rare on the phone's page (the field has `pattern`), easy on the
  global page, and it doesn't meet the bar earlier rounds set for refused forms.

What is left:
- **M1:** a double tap on Remove, or Remove from a second tab, lands on Settings › Web Filter at the top.
- **L1:** a refused domain is a dead-end text page, and the global page's fields have no `pattern`.
- **L2:** a failed read of the blocked-domain log is a 500, but nothing tests it (a mutation survived).
- **L3:** nothing pins the log's times as local: a raw UTC clock passes every test (a mutation survived).
- **L4:** `setting=default` for an unknown phone or list is a redirect, not the documented 404.

Counts: 0 High, 1 Medium, 4 Low, 5 Nits.

## What I ran

All cargo runs used my own target dir (`~/.cache/handy-target/qa-31-5`), under `~/build.lock`.
- **`cargo test`, unchanged:** 768 passed, 0 failed (the implementer's count).
- **`cargo fmt --check`:** clean.
- **`cargo clippy --all-targets --locked`:** the same 13 warnings as on main (`models.rs` dead fields incl.
  `DeviceBlocklistOverride`, `dns_engine.rs`, `storytel.rs`, the old `sort_by` in `devices.rs`). None is in the
  step's new code.
- **`node --test jstest/*.test.js`:** 16 passed.
- **The mutations.** Each was run with targeted filters, then reverted:

  | # | Mutation | Caught by |
  |---|---|---|
  | 1 | `section=filter` also writes the quick mask (`Some(mask)` instead of `quick.then_some`) | `device_pages::a_policy_section_leaves_the_other_section_alone`, `home_and_filter_forms_return_to_their_page` |
  | 2 | an unknown `section` writes both (as no section) | `a_policy_section_leaves_the_other_section_alone` |
  | 3 | Remove takes the phone from a form `device_id` instead of the deleted row | `home_and_filter_forms_return_to_their_page`, `the_filter_routes_refuse_bad_input` |
  | 4 | a non-numeric `device_id` becomes "every phone" | `the_filter_routes_refuse_bad_input` |
  | 5 | the wallpaper grid without its `<noscript>` Save | `home_screen_holds_the_moved_forms` |
  | 6 | the log switch returns to `/filter#log` | `home_and_filter_forms_return_to_their_page`, `cleanup::dns_log_is_off_by_default_and_opt_in_per_phone` |
  | 7 | no `/dns?device=` redirect | `old_per_phone_filter_urls_redirect_to_the_filter_page` |
  | 8 | a log entry's domain `|safe` | `web_filter_holds_the_phones_parts` |
  | 9 | an unreadable wallpaper list renders an empty grid | `home_and_filter_fail_on_an_unreadable_part` |
  | 10 | an unreadable blocked-domain log renders an empty list | **nothing** (L2) |
  | 11 | no domain check (only "not empty") | `the_filter_routes_refuse_bad_input` |
  | 12 | an unknown `setting` reads as default | `the_filter_routes_refuse_bad_input` |
  | 13 | `back_to` ignores `back` (any phone → its filter page) | `the_filter_routes_refuse_bad_input` |
  | 14 | `update_policy` redirects for an unknown phone (no 404) | `a_policy_section_leaves_the_other_section_alone` |
  | 15 | the log's `when` is the UTC clock (`t.format("%H:%M")`) instead of `zone.when` | **nothing** (L3) |

  Two of fifteen survived (L2, L3).

## Medium

### M1. A second tap on Remove lands on Settings › Web Filter, at the top
`src/handlers/dns_filter.rs:522` (`Ok(None) => return Redirect::to("/dns")`), choice 3,
`tests/device_pages.rs` (`the_filter_routes_refuse_bad_input`, which pins `/dns` as the expected answer).

Remove is a plain submit with no JS guard. On a phone over the tailnet to a Pi, a double tap sends two POSTs before
the first answer lands; the browser follows the **last** navigation, whose row is already gone, so the parent ends on
`/dns`: another page, the Settings tab, scrolled to the top. Remove in a second tab (or after Back) does the same. That
is exactly what the user's rule (§5.1) forbids, and it reads as "the domain went somewhere else". The choice's
reason - "the phone comes only from the row" - is about which phone a **write** touches; nothing is written when the
row is gone, so the redirect target may use any phone id the form names.

**Fix:** add `<input type="hidden" name="device_id" value="{{ device.id }}">` to the two Remove forms on
`device_filter.html`, and on `Ok(None)` return `back_to(&form, form device_id parsed as i64)` (a parsed number only
builds a URL; the delete itself still never reads it). Change the test's second tap to expect the phone's page, and
keep a case that a form `device_id` naming another phone can't change which row is deleted (the row id is the path).

## Low

### L1. A refused domain is a dead-end text page; the global page's fields have no `pattern`
`src/handlers/dns_filter.rs:315-321` (the 400), `templates/dns_filter.html:66,88` (no `pattern`), choice 2.

The new check is right, but its answer is a bare `text/plain` 400 ("That isn't a domain name like example.com -
nothing was added.") with no page, tab bar or link back. Earlier rounds held refused forms to "reopen the group with a
flash" (§3.0; qa-31-s4-code L1 for the Child PIN). On the phone's page the field's `pattern` catches almost every
case before the post (what's left: a label over 63 characters), so this is rare there. On the global Settings › Web
Filter page the fields have no `pattern`, and pasting a link (`https://www.youtube.com/watch?v=…`) is the most likely
wrong input a parent makes: it now lands on that text page, where it used to be stored.

**Fix:** at least add the same `pattern` and `title` to the two global fields (one line each; step 14 restyles the
page). Better, for the phone's page: redirect a refusal to `/devices/{id}/filter?notice=bad_domain` (with `back=filter`)
and show the flash under the add row of its group, as §3.0 has it; the global page can keep the 400 until step 14.
Test: a refused domain posted from the phone's page lands on its page with the flash in `#blocked`/`#allowed`.

### L2. An unreadable blocked-domain log is a 500, untested
`src/handlers/device_pages.rs:1070`.

Mutation 10 (`Err(_) => Vec::new()`) passed every test: `home_and_filter_fail_on_an_unreadable_part` renames
`device_wallpapers`, `dns_blocklists` and `dns_custom_domains`, but the log is only read while it is on, and the test's
phone has it off. An empty log reads as "Nothing blocked yet." - the false "all clear" the 500 is there to prevent.

**Fix:** in that test, turn the log on for the phone and add `("device_dns_events", Section::Filter)` to the table.

### L3. Nothing pins the log's times as local
`src/handlers/device_pages.rs:1083-1084` (`when`, `exact`), `tests/device_pages.rs` (`web_filter_holds_the_phones_parts`).

Mutation 15 (the UTC clock instead of `zone.when`) passed: the test asserts there is no "UTC" or "+00:00" and that a
`title` exists, not which clock time is shown. Local time on the parent's pages is a §4.2 rule and has a test on every
earlier page (`parent_pin::tests::every_event_row_in_local_time`).

**Fix:** set the Phone Setup zone to `Europe/Oslo` in the test, post an event at a fixed time today (e.g. 10:00 UTC,
or "now" rounded so it stays today) and assert the row shows `12:00` (summer) and the `title` the exact local time.

### L4. `setting=default` for an unknown phone or list is a redirect, not a 404
`src/handlers/dns_filter.rs:356-364` (`None =>` the `DELETE`), `server/CLAUDE.md:1104` ("404 unknown phone or list").

A `DELETE` that matches nothing is no foreign-key error, so `/dns/devices/999/blocklists/2/override` with
`setting=default` answers 303 → `/devices/999/filter` (then a 404 page), and an unknown list on a real phone 303 →
its filter page as if saved. Harmless, but the doc and the handler's comment promise a 404, and the test only checks
`on`.

**Fix:** for `default`, check the phone and the list first (`SELECT 1 FROM devices WHERE id = ?`, the same for
`dns_blocklists`; 404 each), then the `DELETE` as now. Add `default` to the test's 404 cases. Or reword the doc to "on/off for an unknown phone or list 404".

## Nits

- **N1.** §4.5 #11's footer is "Blocks ads and adult sites on this phone."; the page says "Blocks the lists and
  domains below on this phone." The new text is more exact (the lists are configurable), but it isn't recorded as a
  choice; either add it to the status section or use the spec's.
- **N2.** The notification warning's detail is two sentences ("… only missed calls. Android can't turn this on for
  Vibb."); §4.2 allows a title + one sentence, and §4.5 #3 has "Unread counts, the lock-screen notifications and the
  music card are off." The second sentence fits the ⓘ.
- **N3.** The domain field's `pattern` refuses a trailing space the server would trim (`trim()`), and Android
  keyboards add one after a picked suggestion: allow it, `pattern="\s*[A-Za-z0-9_\-]+(\.[A-Za-z0-9_\-]+)+\.?\s*"`.
- **N4.** A domain added twice is stored twice and shown twice (`dns_custom_domains` has no unique key; pre-existing).
  Now that the route checks its input, `INSERT … SELECT … WHERE NOT EXISTS (same domain, list_type, device_id)` is a
  cheap guard.
- **N5.** `launcher/CLAUDE.md:446` still says the VPN toggle is "surfaced in `device_detail.html`'s device page"; it
  is on the phone's Web Filter page now. (Also pre-existing and seen while reading: the log is ordered by the phone's
  `blocked_at` text, so a garbled time like "yesterday-ish" sorts first and its "Time unknown" row tops the list;
  `ORDER BY received_at DESC` or `id DESC` would put it where it arrived.)
