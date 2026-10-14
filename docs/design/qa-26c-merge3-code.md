# QA (code): the merge of main `7936e6d` into `music-3-spotify` (2026-10-14)

Reviewed: `5d7de47`, the merge of `origin/main` `7936e6d` (design 31's CSS clean-up, `style.css?v=25`; the PWA browser
pass of 2026-10-14 with its fix round and QA fix round: every form posts to and redirects to its own group's fragment,
`scroll-restore.js` v3, `TestApp::assert_lands`, `tests::shell::every_form_on_every_page_lands_on_its_own_group`,
`tests::shell::style_css_has_the_shape_browsers_read`, restyled native controls, `style.css?v=26`) into `aa397e0` (the
handover commit), its follow-up `d42df69` and the docs commit `cb2732d`. I read these against root and
`server/CLAUDE.md`, `qa-26c-merge2-code.md` (the template), `docs/testing/2026-10-14-pwa-browser-pass.md` (findings,
fix round, QA fix round), `git diff 5584772 7936e6d`, 26c's Release and "Merge of main (7936e6d)", and
`26c-handover.md`.

**Separating the resolution.** `git merge-tree --write-tree aa397e0 7936e6d` conflicts in exactly the two files the
merge commit names (`templates/device_filter.html`, `templates/partials/music_card.html`). The diff between that tree
(`1f83c08`) and `5d7de47` is the two conflict hunks' markers and nothing else (9 deleted lines, 0 added). Every
auto-merged file is git's own merge. `git diff 7936e6d HEAD -- server/static` is empty, so `style.css`,
`scroll-restore.js` and `autosave.js` are main's. Main changed nothing in `launcher/`, `music/`, `server/migrations/`,
`server/testdata/` or `.github/` since `5584772`.

- **`device_filter.html:72-75`.** Our override mark (`self.overridden(d.id)`, `data-spotify-override`) is in the row
  label. Main's Remove form is `/dns/domains/{id}/delete#blocked` with the hidden `list_type=block`, and the
  `back`/`device_id` hiddens are kept. Right.
- **`partials/music_card.html:29-37`.** Main's Check now (`#entry-<id>`) is there, then our "Open in Spotify" link,
  then the Offline select only `{% if sweep.offline %}`, with main's action `/offline#entry-<id>`. Right.

## Runs (this worktree, `cb2732d`, 2026-10-14)

- `server/` (Rust as in `~/handy-env.sh`; CI pins 1.99.0):
  - `cargo fmt --check` is clean.
  - `cargo clippy --all-targets --locked` gives **13 warnings**: the 12 known plus `storytel.rs`' `chunks_exact`,
    which is main's 13 as the merge section says.
  - `cargo test` **937 passed, 0 failed**.
  - `node --test server/jstest/*.test.js` **30 passed**.
- `launcher/` and `music/` were not rerun. The merge changed nothing there (`git diff aa397e0 HEAD -- launcher music
  server/testdata` is empty), and this review touches neither. 1146 / 845 were green on `d42df69` per the merge section.
- **Contract files** (checked with `cmp`), all identical:
  - both `phone_vectors.json`;
  - `music_library.json`, `music_listing.json` and `music_icons.json`, server vs music;
  - both `bridge_contract.json`;
  - the three `spotify_hosts.json`;
  - `launcher/.../aidl/me/vibb/bridge` vs `music/...` (`diff -r`).
- **Migrations** end `0072_music_storytel.sql`, `0073_music_data_and_storage.sql`, `0074_music_spotify.sql`. 0073 is
  main's, unchanged. 0074 is the only migration file that differs from `7936e6d`.
- **Classes vs `style.css`**: a script lists every `class="..."` token in `templates/**` and non-test `src/**`
  (template expressions stripped) that no selector in `style.css` defines, for `7936e6d` and for `HEAD`.
  - **The same 11 on both**: `app-display-form`, `app-row`, `day-row`, `lock-part`, `source-choice`, `summary-more`,
    and the dynamic prefixes `attention-`, `dot-`, `music-status-`, `state-` and `tone-`. None is Spotify's.
  - No Spotify template uses `card`, `subtitle`, `error`, `success`, `button-danger` or `section-title`.
  - The merge section's "same set as on main" claim holds.
- **Fake secrets in the extended shell test** (`d42df69`) are all obviously fake:
  - `listing::ready`: client ID `0123456789abcdef0123456789abcdef`, refresh token `FAKE-REFRESH-1`, access token
    `FAKE-ACCESS-2` (`testdata/spotify/token_refresh.json`);
  - IDs `0FakePlaylistKveld0001` and `2FakeAlbumX00000000001`.

### Mutations

Each mutation was reverted afterwards (`git status` clean, the migrations back as 0073/0074).

| # | Mutation | Tests run | Result |
|---|---|---|---|
| A | Device › Music's Spotify form without `#music-spotify` | `shell::every_form_on_every_page…`, `device_pages::music_forms_return_to_their_groups` | **both red** |
| B | Web Filter's override mark never rendered (`{% if false %}`) | `shell::`, `music_spotify::`, `device_pages::` | **red**: `the_web_filter_page_says_what_spotify_overrides`, the shell test's part check |
| C | Main's offline action back to `/offline` (no fragment) in the resolved hunk | `shell::every_form…`, `music::every_form_on_the_music_pages…` | **red**: the Music tab's table. The shell test stays green: its `/music` has no NRK row, so it renders no Offline form |
| D | Our side of the same hunk: the Offline select on a Spotify row (`{% if true %}`) | `music_spotify::`, `shell::`, `music::every_form…` | **red**: `entries::the_cards_and_check_now` |
| E | The login exception: `action="/music/spotify/login#accounts"` | `shell::every_form…`, `music::every_form…`, `music_spotify::` | **red**, but only the Music tab's table (`music.rs:5551`); **the shell test stays green** (Low 1) |
| F | A class the clean-up deleted on Spotify markup (the Spotify form `class="card"`) | full | **survived**, 937 green (Low 2) |
| G | Migration order: 0073 renamed to 0075 (design 30's after Spotify's) | full | **survived**, 937 green (Low 3) |

## Findings

0 High, 0 Medium, 4 Low, 3 Nits.

The merge is clean and the two hand resolutions are exactly "ours plus main's form". Every Spotify form on every page
posts to and redirects to its own group:

| Form | Posts to | Redirects to |
|---|---|---|
| `/music/spotify` app | `#app` | `#app` |
| Remove the app | `#app-clear` | `#app` (the confirm is gone with the app) |
| Paste | `#paste` | `#accounts` |
| Account remove | `#account-<id>` | `#accounts` |
| Add | `#add` | `#added` (the result group above it, which scroll-restore leaves at its anchor) |
| Device › Music Spotify group | `#music-spotify` | `#music-spotify` |
| Web Filter Remove | `#blocked` | `#blocked` |

- **Coverage of the redirects.** The Music tab's redirect table and Device › Music's table follow the Spotify
  redirects with `assert_lands`, and `check_forms` runs on all four Spotify pages.
- **`<noscript>` Saves.** The only autosaving Spotify control (the Plays-as picker) has its `<noscript>` Save, which
  `check_forms` now pins on Device › Music. That closes qa-26c-merge2 Low 2 for good.
- **The login exception is narrow and justified.** It is an exact `["music", "spotify", "login"]` match. A fragment
  there would be inherited by the 303 to `accounts.spotify.com/authorize` (a Location without a fragment keeps the
  request's). A refusal re-renders with the button `autofocus`ed, as main's `/login` does.

Nothing in the findings below is a regression of the merge. They are gaps in the guards the merge relies on, and stale
documentation.

### Low 1 - `check_forms` never fires its "leaves the page" assertion when the action has a fragment

- **Where:** `server/src/tests/settings_pages.rs:70-91` (`leaves_the_page`) and `:105-108` (in `check_forms`).
- **Evidence:** `leaves_the_page` splits the whole action on `/`. So `/music/spotify/login#accounts` gives
  `["music", "spotify", "login#accounts"]`, which matches no arm. The form then goes through the normal fragment check,
  and `#accounts` encloses it, so it passes. Mutation E left `every_form_on_every_page_lands_on_its_own_group` green. The
  guard `assert!(!action.contains('#'), "{page}: {action} leaves the page")` can only fire for the numbered deletes,
  never for the fixed paths (`/login`, `/logout`, `/auth/*`, `/devices/new`, …, the Spotify login). The Spotify login is
  still pinned fragment-less by the Music tab's table (`music.rs:5551`), so this branch is covered. `main`'s own
  sign-in/out and add-flow forms are not. The helper is main's; the merge added the Spotify arm to it.
- **Fix (for `main`, then merged here):** in `leaves_the_page`, match on `action.split('#').next()`, so a fragment on a
  leaving form fails `check_forms` with "leaves the page". Re-run mutation E: the shell test must go red.

### Low 2 - Nothing fails when a template uses a class `style.css` doesn't define

- **Where:** `server/src/tests/shell.rs` (no such test); mutation F.
- **Evidence:** the clean-up deleted 36 classes, and both main and this merge relied on a hand check ("the classes used
  but not defined are the same set as on `main`"). A Spotify form given the deleted `card` class leaves all 937 tests
  green. The check holds today (see Runs), but the next merge or a later Spotify step has nothing to stop it.
- **Fix (for `main`, recommended before the next merge):** add a test that collects the `class="..."` tokens of
  `templates/**`, with `{{ }}`/`{% %}` removed, and fails on any not defined in `style.css`. It needs an explicit
  allowlist of the 11 known ones: the dynamic prefixes `attention-`, `dot-`, `music-status-`, `state-`, `tone-`, and
  the six JS/test hooks `app-display-form`, `app-row`, `day-row`, `lock-part`, `source-choice`, `summary-more`.
  Mutation F should then go red.

### Low 3 - The 0073 -> 0074 order the release depends on is unpinned

- **Where:** `server/migrations/`; 26c's Release and handover §1/§4.1 ("Re-check that 0074 stays the highest").
- **Evidence:** the hazard is `pre_migration_backup` comparing only `MAX(version)`: 0073 reaching a server that already
  ran 0074 is applied out of order with no backup. It is guarded only by prose and by each merge agent re-listing the
  directory. Swapping the order (0073 renamed 0075, mutation G) leaves all 937 tests green.
- **Fix:** add a test in `tests::music_spotify` (or next to `migration_0073_…`) that asserts `MIGRATOR.iter()` ends
  `(73, "music data and storage"), (74, "music spotify")`. Its message should name the hazard, so a renumbering on
  either side fails loudly. Remove it, or relax it to "74 is Spotify's", once `main` has carried 0074 past a release.

### Low 4 - `/music/spotify/done` still links `style.css?v=7` under the old product name

- **Where:** `server/src/handlers/music_spotify.rs:860-866` (inline HTML).
- **Evidence:**
  - It is the only page outside `partials/head.html`: `<link rel="stylesheet" href="/static/style.css?v=7">` (main
    is at `?v=26`), and the title "Spotify login · Kids Device MDM" where every other page says "· Vibb Phone".
  - `pages_restore_scroll_after_auto_save` checks templates only, so nothing catches it.
  - The page meta-refreshes after 0 s, so the effect is a stale stylesheet for an instant (a cached `?v=7` from an old
    install), and an old name in the tab and history.
  - Not from this merge (step 1), but this merge is the one that moved the stylesheet version again.
- **Fix:** take the `<link>` and `<title>` from `partials/head.html`. Either render a tiny askama template that includes
  it (it then also gets `scroll-restore.js`, harmless), or format `?v=` from a shared constant. The route and its
  no-session, no-store behaviour stay unchanged.

### Nit 1 - The shell test's "Spotify parts are on the page" covers three of the four Spotify pages

`shell.rs:603-620` asserts a part on Device › Music, Web Filter and `/music/spotify`. The Spotify playlist's entry page
(`format!("/music/entries/{spotify_entry}")`) is only fetched, while the merge section says "asserting each Spotify part
is on the page it checks". Add `(format!("/music/entries/{spotify_entry}"), "data-hint=\"spotify-streams\"")`.

### Nit 2 - The login exception isn't named where the rule is written

- `leaves_the_page`'s doc comment (`settings_pages.rs:67-69`) lists signing in/out, Dismiss, the add flows, New Setup
  Code and the deletes. Add "and Spotify's login (a 303 off this server)".
- `server/CLAUDE.md`:
  - the scroll-restore bullet (`:246-248`: "a new form needs its group's fragment in its action and its redirect")
    should add "except the forms that leave the page (`settings_pages::leaves_the_page`)";
  - the Spotify section (`:871-876`) should say the login form is the one fragment-less form on `/music/spotify`.

### Nit 3 - The branch carries main's stray `server.pid`

`7936e6d` committed a `server.pid` at the repo root (from the browser-pass fix round), and the merge brought it in
(`git ls-files server.pid`). `main` removed it since (`59c7fbe`, with `*.pid` in `.gitignore`), so the next merge of
`main` drops it. Don't resolve that merge by keeping it.

## The handover and Release: stale lines

`origin/main` is now `59c7fbe`, **6 commits past `7936e6d`**: design 34's docs/mockups/QA merge `a64c15a`, docs only,
plus the `server.pid` removal. It touches no code, migration or contract file. `origin/music-3-spotify` is `cb2732d`
(pushed), 44 commits ahead of `origin/main`'s merge base.

`docs/design/26c-handover.md`:

- **`:5`** "Written 2026-10-14 at branch head `102745b`" -> "... at `102745b`; brought up to date at `<the fix round's
  head>` after the merge of main `7936e6d`".
- **`:11-13`** "`origin/main` (`5584772`) is an ancestor … 40 commits ahead … 41 with this document" -> "`origin/main`
  `7936e6d` is an ancestor (`main` has moved on to `59c7fbe` since: design 34's docs and the `server.pid` removal - merge
  it first, §4.1) … N commits ahead (`git rev-list --count origin/main..music-3-spotify` after that merge)".
- **`:15`** "Three merges of `main` … (the last two by a code QA)" -> "Four merges … (the last three by a code QA)", and
  add after `:20`:
  "- `5d7de47` - main at `7936e6d`: design 31's CSS clean-up and the PWA browser pass's fix round (every form posts and
  redirects to its group, `assert_lands`, the CSS guard); follow-up `d42df69` (the Spotify login in `leaves_the_page`,
  the Spotify pages in the shell test). QA `<this commit>`, fix round `<…>`."
- **`:85`** "13 QA files" -> "14 QA files".
- **`:91-94`** "Last local runs (the worktree; the server at `102745b` … `cargo clippy --all-targets` 12 warnings, the
  same 12 as `main`; `cargo test` **934 passed**; `node --test jstest/*.test.js` 25 passed" -> "the server at
  `cb2732d`: `cargo clippy --all-targets --locked` 13 warnings, `main`'s 13; `cargo test` **937 passed**; `node --test
  jstest/*.test.js` 30 passed". Launcher 1146 and music 845 stand, now "on `d42df69`".
- **`:101-103`** "the newest on `a023e4d` … `8ba881b`, `102745b` and this document's commit were not pushed" -> the
  branch is pushed to `cb2732d`. Name the newest green CI head, or say CI for `5d7de47..` is to be checked.
- **`:121`** "as the three earlier merges were" -> "as the four earlier merges were". In the conflict list (`:122-125`),
  add `templates/device_filter.html` and `templates/partials/music_card.html` (this merge's two).
- **`:199-200`** "… 8, merge `5584772` - each with its fix round" -> "… 8, merge `5584772`, merge `7936e6d` - each with
  its fix round".
- **`:203`** "`-step8-code`, `qa-26c-merge2-code.md`" -> "…, `qa-26c-merge2-code.md`, `qa-26c-merge3-code.md`".

`docs/design/26c-spotify.md`, Release "The branch" (`:1898-1916`):

- **`:1903-1904`** "The final head is the handover commit on top of `102745b`. `origin/main` is `5584772`, an
  ancestor" -> "`origin/main` `7936e6d` is an ancestor (main is at `59c7fbe` now: docs only)". Drop "final head"; the
  fourth merge sentence after it already says what follows.
- **`:1907`** "its code QA is owed" -> "its code QA `<this commit>` and fix round `<…>`".
- **`:1909-1914`** "Last full runs (the server on `102745b`; …) … 934 passed … clippy's 12 known warnings … 25 passed
  … CI last ran on `a023e4d` … not pushed" -> "the server on `cb2732d`: 937 passed, fmt clean, clippy main's 13, node 30;
  launcher and music on `d42df69`: 1146 / 845", and the CI sentence updated as in the handover.
- **`:4510-4511`** "Owed: a code QA of this merge (`qa-26c-merge3-code.md`)" -> replaced by its fix-round subsection.

## Verdicts

1. **Conflict resolutions - OK.** Both hunks are ours plus main's form, byte for byte. Nothing else differs from git's
   own merge.
2. **Main's rules over Spotify code - OK but Low 1.** Every Spotify form has its group's fragment and the matching
   redirect, the `<noscript>` Save is there, and `assert_lands` follows every Spotify redirect in both tables. The login
   exception is exact and right. Main's guard for it is weaker than it reads (Low 1); the Music tab's table holds the
   line.
3. **Deleted classes - OK but Low 2.** No Spotify markup uses a deleted class (verified by script, the same 11 as
   main), but nothing pins it.
4. **Migrations - OK but Low 3.** 0073 is main's, then 0074, and 0074 is the highest. The order is unpinned.
5. **Extended shell test - OK but Nit 1.** It really renders the Spotify group, the override mark, the Spotify page and
   a Spotify entry page through `check_forms` (mutations A and B turn it red). All secrets are obviously fake.
6. **Contract files - OK.** All byte-identical, and untouched by the merge.
7. **Docs - Low 4 is code; the handover and Release lines above are stale.** The merge section's account (conflicts,
   follow-up, 937/30, clippy 13, migrations) is accurate and reproduced.

## Fix-round checklist

- [ ] L1: `leaves_the_page` matches the action without its fragment (on `main`, or here with a note for `main`);
      mutation E turns the shell test red.
- [ ] L2: a "every template class is defined in `style.css`" test with the 11-entry allowlist; mutation F turns it red.
- [ ] L3: pin the migrations' tail as 0073 (design 30) then 0074 (Spotify); mutation G turns it red.
- [ ] L4: `/music/spotify/done` takes the stylesheet link and title from the head partial (or a shared constant).
- [ ] N1: assert a Spotify part on the Spotify entry page in the shell test.
- [ ] N2: name the Spotify login in `leaves_the_page`'s comment and in `server/CLAUDE.md` (`:246-248`, `:871-876`).
- [ ] N3: when merging `main` `59c7fbe`, take main's deletion of `server.pid`.
- [ ] The handover and Release lines listed above.
