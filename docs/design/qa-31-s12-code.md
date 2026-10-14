# QA 31 step 12 (code) - the Music tab

Reviewer: code QA, 2026-10-13. Branch `design-31-s12`, `571f86fa..41572288` (one commit, 18 files, server and docs).
I read it against `docs/design/31-pwa-redesign.md`:
- §1.6, §3.0, §3.4, §4 and §5 (§5.1, §5.2, §5.5, §5.6);
- §8 step 12, §9, "Step 11 implementation status" and "Step 12 implementation status" with its 10 choices;
- design 30 (the storage limit, the data switches, cache-through, played extras) for what the music pages must keep;
- `qa-31-s3-code.md` for the format.

I also read the code the step touches or leans on:
- the `handlers/music.rs` and `handlers/music_import.rs` diffs, `main.rs`'s routes, `display_time::Zone`,
  `storytel::StorytelError::message`, `admin_auth::ADMIN_CSP`;
- every new or changed template (`music.html`, `music_settings.html`, `music_categories.html`, `music_import.html`,
  `music_entry.html`, `music_storytel.html`, `partials/music_card.html`, `partials/music_style.html`);
- `static/music-cards.js`, `static/scroll-restore.js`, `static/autosave.js`, `static/style.css` (for the classes and
  tokens the partial uses);
- every changed test, and the four new ones in full.

Line numbers are `41572288`'s. No product code was changed: the mutations below were temporary and are reverted, and
`git status` was clean before this file was written.

**Verdict: merge after a short fix round.** The tab is what §3.4 asks for, and the parts that matter held up:
- no POST route changed, and every form keeps its field names; the API and `server/testdata/music_*.json` are
  untouched, so design 30's storage limit, data switches, cache-through and played extras are not affected (they
  live on the device page's Music card and the device API, which this step doesn't touch);
- the five `confirm()` deletes are `<details>` confirms, the form inside after the summary; no music page calls
  `confirm()`;
- the redirect table is real: it is built from the rendered forms of seven pages and the import preview, fails on a
  form it has no row for, and enforces "no fragment in the redirect, none in the action";
- the Storytel login is never put back into a page (no `value` on either field), and a refused save's page doesn't
  hold the password (pinned);
- everything is escaped; the only inline `style` values are from fixed tables (`tile_of`, `COLORS`).

What needs fixing:
- **M1:** the Storytel shelf still says "Nothing is tried again before 11:02 UTC" while Music › Settings, one tap
  away, says "before 13:02" for the same cooldown. There is a clean fix.

Counts: 0 High, 1 Medium, 4 Low, 8 Nits.

## What I ran

All cargo runs used my own target dir (`~/.cache/handy-target/qa-31-12`), under the shared build lock.
- **`cargo test`, unchanged:** 753 passed, 0 failed (the implementer's count).
- **`cargo fmt --check`:** clean.
- **`cargo clippy --all-targets --locked`:** the same 13 warnings as `main`; none in the step's files.
- **`node --test server/jstest/*.test.js`:** 16 passed.
- **The mutations.** Each ran with the `tests::music` filter (`music`, `music_storytel`, `music_sweep`, `music_art`;
  145 tests), then was reverted:

  | # | Mutation | Caught by |
  |---|---|---|
  | 1 | `save_sweep` → `/music/settings#sweep` | `every_form_on_the_music_pages_returns_to_its_page`, `music_sweep::changes_wake_the_sweeper` |
  | 2 | a new form on Settings (`/music/catalog-row/x`) | `every_form_…` ("a form without a row in the redirect table") |
  | 3 | the orphans' Delete Files… loses its `<summary>` | `the_music_deletes_are_behind_a_confirm` |
  | 4 | a refused Storytel save echoes the e-mail in its error | **nothing** (L4) |
  | 4b | a refused Storytel save echoes the e-mail and password | `music_storytel::save_probes_then_seals` |
  | 5 | the import preview's body limit `1 MB + 64 KB` → `8 MB` | **nothing** (the handler's own 1 MB check holds; pre-existing, defence in depth) |
  | 6 | `delete_file` drops `AND entry_id = ?` (a file deleted through another entry's URL) | **nothing** (L4; pre-existing) |
  | 7 | "Storytel accepted the login (…)" in UTC | `music_storytel::save_probes_then_seals` |
  | 7b | "Check again after …" in UTC | **nothing** (L4) |
  | 8 | the Offline select loses `data-autosave` | `the_library_rows_and_edit_mode` |
  | 9 | the Offline select loses its `<noscript>` Save | **nothing** (L4) |
  | 10 | a move leaves Edit (`/music#entry-{id}`) | `every_form_…`, `entries_and_categories_move_and_categories_in_use_stay` |
  | 11 | the Storytel login's action loses `#storytel` | **nothing** (L4) |
  | 12 | `phone_music_href` drops `#music` | `the_library_rows_and_edit_mode` |

## Medium

### M1. The shelf's cooldown is in UTC, Settings' is local
`src/storytel.rs:506-527` (`StorytelError::message`, `Cooldown`: `until.format("%H:%M UTC")`), its one production
caller `src/handlers/music.rs:1552` (`render_picker`).

After a refused login, Music › Settings says (local, `storytel_status` at `music.rs:1279`):
"Storytel refused the login at 12:02. Nothing is tried again before 13:02 - …". The shelf, one tap away
(Settings › Pick Books from the Shelf), says "Storytel refused the saved login. Nothing is tried again before 11:02
UTC - …". Same fact, two numbers. A parent who skims past "UTC" waits two hours for nothing, and M4 (§3.4, "Times are
UTC." goes away) is about exactly this. The status's reason ("built where no zone is at hand") doesn't hold:
`render_picker` has `state`, and `Zone::load` is what `settings_page` already calls.

**Fix:** format the cooldown where the zone is known. Either
- in `render_picker`: `Err(StorytelError::Cooldown { until }) => Some(format!(…, zone.until(until, now)))` before the
  generic `Err(err) => err.message()` arm, with `zone = Zone::load(&state.db).await` and
  `now = state.music_sweep.now()`; or
- give `message` a `(zone: &Zone, now)` and pass `Zone::utc_fallback()` in `storytel.rs`'s own tests (`:1502`).

The second is cleaner (no text built twice). `Zone::clock` already appends " UTC" when no Phone Setup zone is set,
so nothing is lost on a server without one. Repoint `music_storytel`'s shelf cooldown assertion to the local time,
as the Settings one already is. The log line at `storytel.rs:855` can stay UTC.

## Low

### L1. The device page's Music card still links to `/music#app` and `/music#storytel`
`templates/device_detail.html:323` ("add it on the <a href="/music#app">Music</a> page") and `:380` ("none saved yet
- add it on the <a href="/music#storytel">Music</a> page").

Both anchors are gone from `/music` (they are on `/music/settings`), so both links open the tab at its top, and the
text sends the parent to a page that no longer has the thing. §5.1.5 and §3.4 (L5) say links move with their
sections. Choice 9 defers this because step 4 rebuilds `device_detail.html` in parallel; but these are two
attribute values on lines step 4 is unlikely to rewrite, and a conflict there is a one-line merge.

**Fix:** point them at `/music/settings#app` and `/music/settings#storytel` and say "Music › Settings" now; or, if it
waits, put it as a must-do in step 4's or step 7's status so it isn't lost.

### L2. `Zone::when` in an "at …" sentence reads badly
`src/handlers/music.rs:1279` and `:1285` ("Storytel refused the login at {}"), with `when`'s outputs "12:02",
"yesterday 21:40" and "12 Oct".

So the card can say "Storytel refused the login at yesterday 21:40." and "… at 12 Oct.". The old UTC text always had
a clock time ("at 9 Oct 14:30 UTC"); `when` drops it before yesterday, which is fine for a past fact, but not after
"at". The other `when` uses ("Last check failed yesterday 21:40: …", "(as of 1 Oct)", "accepted the login (12:02)")
read right.

**Fix:** use the bracket form the accepted line already uses: "Storytel refused the login ({when}). Nothing is tried
again before {until} - …" (and the same at `:1285`). Repoint the one test (`music_storytel`, "refused the login at
12:02").

### L3. The tab's CSS is a per-page `<style>` partial, unlike step 11
`templates/partials/music_style.html` (55 lines), included by the six music pages.

It works: `ADMIN_CSP` (`src/admin_auth.rs:444-445`) has `style-src 'self' 'unsafe-inline'`, and an inline `<style>`
has precedent (`wallpapers.html`, and the old `music.html` had its own block). But:
- step 11 put its tab's CSS in `style.css` with a `?v` bump; this step's status calls the partial a way to avoid
  that bump, which mostly avoids a merge conflict with step 4;
- part of it is not Music-specific: `.title-bar`/`.title-action` (the header's "Edit", which other lists will
  want), `.tile-orange` (beside `style.css`'s other `.tile-*`), `.form-group > .field-switch`, and the
  `details.add-form` disclosure;
- it is re-sent with every music page and is one more thing that ties the admin to `'unsafe-inline'` for styles.

**Fix:** move the rules into `style.css` and bump `?v` once at merge time (after step 4's bump), dropping the
partial and its six includes. At the least, move the generic four; the music-only rules can stay.

### L4. Test gaps (the survivors above)
- **`<noscript>` Save (mutation 9):** nothing in the suite checks any `<noscript>` Save, here or on other steps'
  pages. §5.2 requires one per auto-saving control. **Fix:** a template-walking test next to
  `device_api::pages_restore_scroll_after_auto_save`: every `<form>` holding a `data-autosave` control also holds a
  `<noscript>` with a submit button.
- **Refusal fragments (mutation 11):** the redirect table checks where a *success* lands. Only `#add-link` is pinned
  for where a *refusal* lands (`form_action_of(…, "link")`). **Fix:** in `every_form_…`, for each action with a
  fragment, assert the page it was rendered on (or, for `#import-preview`, the preview) has `id="{fragment}"`.
- **Local times on the rows (mutation 7b):** "Check again after", "Next try" and the cooldown are not pinned in local
  time; only the Storytel status lines are. **Fix:** set a Phone Setup zone in the Check-now limit test and assert
  the local clock.
- **A file deleted through another entry's URL (mutation 6, pre-existing):** add a 404 test for
  `/music/entries/{other}/files/{file}/delete`; the file must still be there.
- **A refused Storytel save (mutation 4):** `assert_no_secret` checks the password ("feil") but not the e-mail.
  Add the e-mail to that check.

## Nits

- **N1.** `music.rs:2843` (`day_text`, used at `:3079` "keeps the list from 9 Oct" and `:3136` "newest: … · 9 Oct")
  and `:545-546` (Storytel "Saved 9 Oct 2026") still take the UTC date. Around midnight they are a day off from the
  local times next to them. Use the zone's date.
- **N2.** Whether the Storytel login is usable is worked out twice: `music_page` (`:457-471`, "Signed in") and
  `settings_page` (`:549-552`, `stale`). One helper keeps the tab's value and Settings' "Enter the login again" from
  drifting.
- **N3.** The tab lists phones `ORDER BY name` (`:445`), an entry's page `ORDER BY d.name, d.id` (`:1937`). Use the
  same order on both.
- **N4.** A Storytel entry's page lost its link to the shelf (old `music_entry.html:30`, "(the Storytel shelf)"); the
  target is now plain text (`music_entry.html:22`). A "Storytel Shelf ›" row in the cover group would bring it back.
- **N5.** A single Storytel book's row reads "Storytel book · 1 book · …". Leave the count out when the kind already
  says it is one book.
- **N6.** A refused import preview (a file that isn't a library) answers at `/music/import#import-preview`, an id that
  page doesn't have then (the error is in `#import`, at the top). Harmless, since `#import` is the top.
- **N7.** The "Edit" title bar is an extra 44px row above the title that appears only from two entries on, so the
  title moves when the second entry is added. Reserve the row always, or put Edit on the title's line.
- **N8.** After a category's Save, ↑ Earlier or ↓ Later, the parent lands on its row with its `<details>` closed, so
  moving a category two places means opening it twice. This was the same on the old page. `?open={id}` on the
  redirect, read when rendering, would keep it open.

## The 10 choices

- **#1 (one commit for 12a and 12b):** agree. The phone lines moved from the rows to the entry page, so they share the
  handler and the tests.
- **#2 (local times now):** agree, apart from M1. The `Zone::when`/`Zone::until` split is right: `until` for "Next
  try", "Check again after" and the cooldown's end, `when` for past events (with L2's wording).
- **#3 (no category in the row's sub-line):** agree. It matches the mockup, and the category is one tap away on the
  entry page.
- **#4 (one-line status in the row):** agree. The status block stays inside `.music-card` with `data-entry`, so
  `music-cards.js`'s selectors (`.music-status[data-busy]`, `.music-status[data-entry=…]`, `.music-card, .card`) all
  still match, on the tab and on the entry page (`div.row.music-card`). The swapped-in block keeps its classes, so
  the row's ellipsis applies to it too.
- **#5 (Edit mode):** agree. `?edit=1` is a GET that changes nothing; moves come back to `/music?edit=1#entry-{id}`,
  and Done goes to `#entries`.
- **#6 (`phone_music_href`):** agree. One function, so step 7 changes one line; mutation 12 shows it is pinned.
- **#7 (the file's row is the confirm's summary):** agree. The summary is the row with a red "Delete…", the form is
  after it inside the `<details>`, and the confirm test counts it. Without JS it opens like every other confirm.
- **#8 (the shelf's links stay `/music#entry-{id}`):** agree. The rows keep `id="entry-{id}"`.
- **#9 (device page links not moved):** disagree, see L1.
- **#10 (no browser pass):** noted. I had none either; the HTML, CSS and JS were reasoned about by reading them.

## Checked and fine

- **Save never jumps to the top:**
  - The sweep picker (`data-autosave`, `<noscript>` Save) posts to `/music/sweep` with no fragment and comes back to
    `/music/settings` with none, so `scroll-restore.js` restores the place (same path, no hash).
  - The orphans' delete and a category's delete come back without a fragment, and their actions have none.
  - The row's Offline select (`data-autosave`, `<noscript>` Save) and Check now come back to `/music#entry-{id}`:
    with JS, the row lands at the viewport's top, not the page's. §5.1.4 keeps `#entry-{id}` for the long list
    explicitly.
  - The entry page's forms come back to their group (`#settings`, `#status`, `#cover`, `#files`, `#file-{next}`).
    The Storytel save, its removal and the import's confirm come back to the group that shows their flash
    (`#storytel`, `#import`).
  - Every form whose answer renders a refusal posts with its group's fragment (`#add-link`, `#add-own`, `#storytel`,
    `#new-category`, `#category-{id}`, `#settings`, `#cover`, `#files`, `#shelf`, `#import-preview`). The refused form
    is re-opened (`open` on its `<details>`) with the error by its field, `aria-invalid` and `autofocus`.
- **Confirms:** orphans, the Storytel login, a category (only when unused; "In use, so it can't be deleted" otherwise,
  and the handler still refuses one in use), a file and an entry. Each is a `<details>`, the POST inside after the
  summary, with no `onsubmit`.
- **Auth and CSRF:** the three new GET routes (`/music/settings`, `/music/categories`, `/music/import`) are in the
  admin router, under `require_full_auth`, CSRF and the session layer. None changes state. `POST /music/import` still
  has its body limit (the layer now also covers the GET, which is harmless), and the confirm keeps its own. The
  preview renders the page in the POST's answer, as before; its hidden `doc` is re-checked in full on confirm, which
  is CSRF-guarded.
- **Ids:** entry routes look the entry up and 404 on an unknown one; a file is looked up by `id AND entry_id`
  (mutation 6 shows that this is not tested, L4); a category by its id.
- **Escaping:** feed titles, the source title, entry, category, phone and book names, notes, statuses, import rows
  and reasons all go through Askama's escaping; no `|safe` in any music template. `the_library_rows_and_edit_mode`
  feeds `<i>` and `<b>` names. The entry page's source link is a target that `music::parse_link` only lets through
  as `http`/`https`, as before.
- **Storytel login:** neither field has a `value`, on success or refusal; the notice is a session flash shown once;
  `save_probes_then_seals` checks the password is in no page, file or log line.
- **Design 30:** the device page's Music card, its `v=2` form, the storage limit, the data switches and the device
  API are untouched; `music_library_snapshot`/`music_listing_snapshot` pass with the files unchanged.
- **Dead code:** `Entered`, `render_plain`, `utc_time`, `ImportCard.open` and the row's `phone_lines` are gone with
  their last users; every class in `music_style.html` is used, and every other class the pages use is in
  `style.css`. `music-cards.js` and its `?v=1` are untouched, as they need no change.
- **Docs:** `server/CLAUDE.md` and `DEPLOY.md` follow the moves; I found no other doc pointing at the old anchors.
