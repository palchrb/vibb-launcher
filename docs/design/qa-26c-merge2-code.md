# QA (code): the merge of main `5584772` into `music-3-spotify` (2026-10-14)

Reviewed: `a023e4d`, the merge of `origin/main` `5584772` (design 31 steps 4-10 and 12-15: Device › Music, the phone's
Web Filter page, the Music tab with Settings/Categories/Import, the summary, add-phone/sign-in, no brand bar) into
`038822a` (26c step 8's fix round), merge base `571f86f`. Read against root and `server/CLAUDE.md`,
`qa-26c-merge-code.md` (the template), design 31 §3.0, §3.2, §3.4, §3.8, §4, §5.1, §5.2, §8 and the `qa-31-s*-code.md`
rounds it cites (s5, s7 M1, s12, s14 M2, s15), and 26c's "Merge of main (5584772)" section, step 8's status and fix
round, Release.

**Separating the resolution.** `git merge-tree --write-tree 038822a 5584772` conflicts in exactly the 9 files the merge
section names (`server/CLAUDE.md`, `handlers/dns_filter.rs`, `handlers/music.rs`, `tests/music.rs`,
`device_detail.html`, `dns_filter.html`, `music.html`, `music_entry.html`, `partials/music_card.html`). Main changed
146 files since the base, we changed 208, 22 both.

- **Main's side.** Of the 124 files only main changed, 117 equal `5584772` byte for byte; the 7 that don't are the
  deliberate Spotify additions (below). Of the 22 both changed, `device_detail.html`, `dns_filter.html` and
  `handlers/dns_filter.rs` are main's files exactly (our parts moved out). For the other 19 a line-multiset check (every
  line either side added since the base is in the merge unless the other side removed it; no removed line is back)
  flags only the conflict hunks, the re-wrapped `server/CLAUDE.md` import paragraph and three main lines replaced:
  `card()`'s inline `.map(|at| self.zone.until(at, self.now))` (now the `check_after` method, same expression - right),
  and `music_entry.html`'s target row and status header - the header is **Medium 1**.
- **Our side.** Of the 186 files only we changed, 182 equal `038822a`; the 4 that don't are 26c, design 26,
  `tests/music_spotify.rs` (the tests moved with the pages) and `music_spotify.html` (the shell). `launcher/` and
  `music/` differ from `038822a` only in their `CLAUDE.md` (main's 8 lines each).

Deliberate differences from main (each read):

| File | What |
|---|---|
| `src/attention.rs:76-78, 147, 240, 281-282, 317, 358` | `Kind::MusicSpotify`, key `music-spotify`, Action level, "Spotify can't play on this phone", target `/devices/{id}/music#music-spotify`; tests `:598, :688` |
| `src/handlers/admin.rs:127-133, 321-329` | the seven Spotify event labels; `access_denied`, `authorize_error`, `invalid_grant` in `NOT_EVENTS` (25) |
| `src/handlers/device_pages.rs:1042-1057, 1121-1145, 1189-1191` | `FilterTemplate.spotify_on/_overridden/_global`, `overridden()`, the two reads in `view_filter` |
| `src/tests/device_pages.rs:2411-2413, 2457-2459, 2501, 2521-2527` | the Device › Music redirect table: an app, a login, the Spotify form's row, the binding afterwards |
| `templates/device_filter.html:34-39, 71` | the DNS note and the site-wide note under the filter switch, the per-row mark |
| `templates/device_music.html:95, 150-183` | the streaming ⓘ's Spotify sentence; the Spotify group |
| `templates/music_import.html:32` | the ⓘ's Storytel/Spotify sentence (ours from `038822a`'s `music.html`) |
| `handlers/music.rs` (vs main) | `SweepCard.offline/spotify_link`, `MusicTemplate.spotify_state`, `EntryTemplate.spotify*`/`list_title`, `save_entry`'s Spotify arms, `SweepView`'s Spotify fields and `gated`, `check_after()`, `spotify_card`, `spotify_phone_line`, `SpotifyBindingCard`, `MusicCard.spotify`, `spotify_unknown`, `MusicWarnings.spotify_cant_play`, `device_card`'s Spotify feed, `spotify_binding_card`, `save_device_spotify` |
| `templates/music.html:91-98`, `partials/music_card.html:32-36, 46`, `music_entry.html:21, 49, 92-109` | "From Spotify ›", "Open in Spotify" instead of Offline, the entry page's Spotify variants |

Nothing of ours lost: "Plays as" + its lines, warnings, note and footer (`device_music.html:154-183`), the DNS note and
the marks (`device_filter.html:34-39, 71`), "From Spotify ›" (`music.html:93`), the row's count and "Open in Spotify"
(`music_card.html:13, 33-35`), the phones' lines and the context notice (entry page, `music_entry.html:54-59`), the
entry page's order/stream hints, the add form and the step 8 setup text (`music_spotify.html`, only the shell and
"Music card" -> "Music page" changed), the two-phones warning, `spotify_unknown` - all there, each pinned by a moved
test.

## Runs (this worktree, `a023e4d`, 2026-10-14)

- `server/` (Rust 1.97 locally, CI pins 1.99.0): `cargo fmt --check` clean; `cargo clippy --all-targets` **12
  warnings**; `cargo test` **933 passed, 0 failed** (`parent_pin` green this time); `node --test jstest/*.test.js` 25
  passed.
- **Clippy baseline:** `git archive 5584772 server .github` into a scratch dir, same `CARGO_TARGET_DIR`: **12
  warnings, the same 12** (9 in `models.rs`, `dns_engine.rs:54-55`, `handlers/devices.rs:1275`); the merge's
  `models.rs` ones sit 4 lines lower (our `music_spotify_account`). Scratch tree deleted.
- `launcher/`: `./gradlew assembleDebug testDebugUnitTest` BUILD SUCCESSFUL, **1146 tests, 0 failures**.
- `music/`: `./gradlew assembleDebug assembleRelease testDebugUnitTest lintDebug -PwarningsAsErrors=true` BUILD
  SUCCESSFUL, then `testDebugUnitTest --rerun`: **845 tests, 0 failures**; lint 0 errors, 42 warnings (the known ones).
- `python3 -I -B scripts/fake-{sonos,storytel,spotify,audio}.py --selftest`: all four OK.
- Contract files (`cmp`): both `phone_vectors.json`, `music_library/_listing/_icons.json` (server vs music), both
  `bridge_contract.json`, the three `spotify_hosts.json`, the three AIDL pairs - identical. Neither parent's contract
  files changed in the merge. Migrations: 0073 main's (unchanged), 0074 ours, nothing else.
- Mutations (each reverted, `git status` clean): (A) `save_device_spotify` back to `/devices/{id}/music` without the
  fragment - `music_forms_return_to_their_groups` and `the_device_card_binds_a_phone_to_an_account` red; (B) the
  Spotify group's `<noscript>` Save deleted - **933 green** (Low 2).

## Findings

0 High, 3 Medium, 6 Low. The textual merge is right and careful: main's pages win, the Spotify controls landed on the
pages design 31 gives their neighbours, the routes, anchors, redirect table, event labels and the new Attention row
work and are tested, every suite is green. One main change was lost in a conflict (Medium 1). The other two Mediums
are design 31 conformance: the Spotify text on main's new pages kept 26c's sentence shapes (Medium 2), and the
Spotify page is the one page left in pre-design-31 markup with nothing in Release that holds the merge to `main` for it
(Medium 3).

### Medium 1 - A Storytel entry's page says "Episodes" again: main's "Books" header lost

- **Where:** `server/templates/music_entry.html:49` (`{{ list_title }}`), `src/handlers/music.rs:2062-2073`
  (`list_title`); main's line was `{% if storytel %}Books{% else %}Episodes{% endif %}` (`5584772`,
  `music_entry.html:49`, design 31 step 12).
- **Evidence:** `list_title` looks at the target only when `spotify` (`.filter(|_| spotify)`), so every non-Spotify
  entry gets the `None` arm, "Episodes" - a Storytel series' page now heads its books "Episodes", under a field labelled
  "Books on the phone". The resolution took ours (`038822a` had `list_title` from before step 12 existed) over main's
  conditional. No test pins either word on an entry page (`grep Books src/tests`: nothing), so the suite is green. The
  merge section (`26c-spotify.md:4344-4345`) describes `list_title` as "Tracks"/"Episodes" only.
- **Fix:** `list_title: if storytel { "Books" } else { match ... }` (or keep main's template conditional with
  `{{ list_title }}` in its else). Pin all three: a Storytel entry page has `group-header">Books<`, a Spotify playlist
  `>Tracks<`, a Spotify show and an NRK entry `>Episodes<`.

### Medium 2 - The Spotify text on Device › Music and Web Filter isn't in design 31's shapes

- **Where:** `templates/device_music.html:157-159` (warnings), `:170-175` (lines and note as `row-note` sentences),
  `:181` (footer); `src/handlers/music.rs:4357-4440` (`spotify_binding_card`'s strings); `templates/device_filter.html:34-39`.
- **Evidence:** the neighbours on the same page use §4.2's sub-page warning - `Note { title, detail }` through
  `partials/note_warning.html`, a bold title of up to 6 words and one sentence ("Storytel refused the phone's login" +
  "The phone tries again ..."). The Spotify group renders each warning as one bold `attention-text` sentence of up to
  ~25 words ("Spotify plays on one device at a time per account: Ella's and Ola's phones both play as Ella - give each
  child their own account.", "On the phone: Spotify refused this account or the app - check the account under User
  Management in your app's dashboard."), with "device" where §4.3 says "phone". The state lines are sentences in
  `row-note`s ("On the phone: Spotify plays.", "Ella: logged in, log in again before 14 Apr 2027"), where §3.0 says
  "state lines become badges or row values" and main shows the phone's Storytel state as a row in "On the phone"
  (`music.rs:4228-4235`: "Storytel login · Logged in / None / Refused") and Storytel's unsent count as a `row-sub` on its
  switch (`device_music.html:142`). The footer is two sentences, 17 words (§4.2: one, at most 15). On Web Filter the
  first group gets **three** stacked footers (main's plus two `p.group-footer`s), one of 43 words; §3.0 allows "a
  one-line grey footer under the group at most", and the detail belongs in that footer's ⓘ (§4.4). Functionally all
  correct and tested; it reads as the old card text pasted into the new pages - the one place design 31's rebuild
  isn't finished, and the copy tests would then pin the wrong shapes.
- **Fix:** `SpotifyBindingCard.warnings: Vec<Note>` (the `warning(title, detail)` helper), rendered with
  `{% for note in spotify.warnings %}{% include "partials/note_warning.html" %}{% endfor %}`, titles of at most 6 words,
  e.g. "No Spotify app set up" / "Spotify login needs renewing" (needs_login, and the phone's `reauthorize` - one note,
  not two) / "Spotify login can't be opened" (`key`) / "Spotify login ends 14 Apr" / "Two phones share one account" /
  "Spotify refused the account" / "No Spotify Premium" / "This phone can't play Spotify", each with its one sentence
  ("phone", not "device"). The phone's outcome as an "On the phone" row like Storytel's (`Row::new("spotify",
  "Spotify", "Plays" | "Can't reach Spotify" | "Limit reached")`, the alarm ones being notes); the account's
  "log in again before" line and the unsent count as `row-sub`s of the "Plays as" row. Footer: one sentence, e.g. "The
  child's own Premium account, never yours." with "Accounts" as a link or a navigation row to `/music/spotify#accounts`.
  Web Filter: one footer while bound ("Spotify's hosts aren't blocked on this phone while it plays Spotify.") and the
  rest - every app, spotify.com/scdn.co, the site-wide blocks it overrides - as a paragraph of the existing ⓘ panel;
  the per-row marks stay. Update the tests' strings with it.

### Medium 3 - The Spotify page is the one page left in pre-design-31 markup, and nothing gates the merge to main on it

- **Where:** `templates/music_spotify.html:36-210` (the body), 26c's Release (`26c-spotify.md:1891-2060`: no line
  for it), the merge section's "Owed" (`:4388-4392`: "a later step").
- **Evidence:** the shell is right (tab bar, `{% let tab = "music" %}`, "‹ Music" to `/music#spotify`, the large
  title; `shell::every_template_with_the_bar_sets_its_tab` and `every_page_marks_its_own_tab` cover it). The body is
  step 1/2b's: `class="card"` (the only template on the tree that still uses it - `grep -l 'class="card"'
  templates/*.html`), a 70-word intro `subtitle`, `<div class="error">`/`success` boxes instead of notes and flashes,
  and outside any ⓘ a file path, env vars and `.env` (`:38`: `data/keys/music-secret.key`, `MUSIC_SECRET_KEY`, `.env`;
  `:48, :53`: `SPOTIFY_CLIENT_ID`, `SPOTIFY_REDIRECT_URI`) - §4.2's "never shown outside an ⓘ panel"; main shows the
  same key text inside an ⓘ (`music_settings.html:33`). Its forms aren't in the Music tab's redirect table (`tests/
  music.rs:5323-5331`). As the merge's own state it is acceptable: everything works, the shell matches, nothing of
  main is broken, and a rebuild inside a merge would hide behaviour changes. As what `main` gets when the user merges
  the branch it isn't: design 31 is otherwise complete on `main`, and Release reads as if only the DNS capture, §6l
  and the device checks stand between the branch and the merge.
- **Fix:** record it in Release as a step before the merge to `main` (recommended: 26c step 9, the Spotify page as
  groups - app, accounts, add - with flashes in their groups, the intro as a footer + ⓘ, the key/env text inside an
  ⓘ, its forms in `every_form_on_the_music_pages_returns_to_its_page`; the callback and `/done` unchanged), or as an
  open point the user accepts explicitly (Question 1). Either way the merge section's "a later step" names where.

### Low 1 - The Spotify Attention row isn't in the music-warnings test that checks the list's badge

- **Where:** `src/tests/attention.rs:264-345` (`each_music_warning_raises_its_row_and_marks_the_music_row`: five
  cases, no Spotify); `tests/music_spotify.rs:3906-3944` checks the summary only.
- **Evidence:** the summary row, its link and its absence when `ok` or unbound are pinned; the Devices list's
  `data-attention-count`, the Music row's "Needs attention" and the fragment existing on the Music page are not. They
  hold today (one `facts_of` -> `music_warnings` for both, `any()` compares the whole struct), but the test is where a
  sixth music warning is meant to be pinned.
- **Fix:** add a `music-spotify` case: `spotify::tests::save_app` + `store_account`, bind the phone, report
  `{"spotify": "not_premium"}`; the loop's checks then cover the badge, the Music row and `id="music-spotify"`.

### Low 2 - The Spotify picker's `<noscript>` Save is unpinned

- **Where:** `templates/device_music.html:176`; mutation B (deleted) left all 933 tests green.
- **Evidence:** main pins the no-JS Save of Sonos and the ticks (`tests/music.rs:3194, 3336`) and of every autosaving
  form on its other device pages (`tests/device_pages.rs:711-726, 850-857, 1703-1711`); §5.2.
- **Fix:** in `the_device_card_binds_a_phone_to_an_account` (or the redirect-table test, for every form on the page):
  the form posting to `/devices/{id}/music/spotify` contains `<noscript>` with a `type="submit"`.

### Low 3 - The account-side Spotify warnings raise no Attention row

- **Where:** `src/handlers/music.rs:4029-4033` (`spotify_cant_play`: the phone's word only), the merge section
  `26c-spotify.md:4357-4360`.
- **Evidence:** "a login ending" (14 days ahead), "needs renewing", "locked with another key", "two phones on one
  account" and "no app" show only on Device › Music. qa-31-s7-code M1's rule is that a warning which needs the parent
  keeps a signal on the summary; the phone's `reauthorize` follows a dead login only once the child tries to play, and
  the 14-day warning - the one that prevents the failure - never reaches the summary, nor does a shared account (which
  stops one child's music whenever the other plays). The reason given ("`music_warnings` reads the phone's report and
  policy, never the accounts table") is about the current plumbing, not the parent.
- **Fix:** read the bound account's row in `attention::facts` (one more small read per phone, §3.8 allows it) into
  `Sources`, and raise "Spotify login ends soon"/"Spotify login needs renewing" and "Two phones share one Spotify
  account" (≤ 8 words) to `#music-spotify`; or write the decision and its reason into 26c §3.6 so it is a choice, not a
  side effect.

### Low 4 - Spotify's dates are UTC dates

- **Where:** `src/handlers/music.rs:4379, 4387` (`ends.format("%-d %b")`, `"%-d %b %Y"`), `handlers/music_spotify.rs:50-52`
  (`day`), `:247`.
- **Evidence:** main words every stored time through `display_time::Zone` (§3.10, design 31 M4); these format the UTC
  instant, so for the hours around midnight the "log in again before" day is one off in Norway. `check_after` and the
  phone lines' "(as of ...)" do use the zone (`self.zone.until`/`when`) - right.
- **Fix:** format `zone.local(t)` (the `SweepView`'s / `Zone::load` zone) in both handlers.

### Low 5 - §6l, the user's QA checklist, PLAN.md and `server/CLAUDE.md` still describe the cards

- **Where / required change:**
  - `docs/testing/emulator.md:1110-1115`: the row shows "3 tracks", but "Spotify picks the order" is on the entry page
    now - "open the entry: Spotify picks the order". `:1186-1187`: "the card says no logged-in account owns it" -> the
    entry page's notes. `:1200`: "the device card and the PWA's accounts card" -> "the phone's Music page (Spotify
    group) and the Spotify page's accounts", plus a check of the new row: the phone's summary shows "Spotify can't
    play on this phone", gone after the heal.
  - `docs/design/26c-spotify.md:1830-1831, 1859, 1861, 1877, 1882` (QA checklist): "the card"/"the device card" -> the
    entry page / the phone's Music page (and the Attention row for items 1859 and 1882).
  - `PLAN.md:142-146`: "**For design 31 step 12**, which doesn't name Spotify: keep ..." still reads as an open
    request - settled at this merge.
  - `server/CLAUDE.md:783` ("each bound phone's card"), `:796` ("card and add", "the device card's binding hint"),
    `:800` ("the card's read as"), `:859-861` ("For design 31 step 12 ... the row stays"): the Music page / the entry
    page / settled.

### Low 6 - The Music tab's redirect table renders no Spotify entry page

- **Where:** `src/tests/music.rs:5323-5331` (`every_form_on_the_music_pages_returns_to_its_page`'s pages).
- **Evidence:** a Spotify entry's page is a different form (no offline field, no order select in context mode, the
  `order` kept when absent - `save_entry` `:2141`); it is tested by its own tests but not posted by the table that
  checks every Music-tab form lands back at `#settings`/`#status`.
- **Fix:** add a Spotify entry (list mode) and its page to the table's pages; `/music/spotify` joins with Medium 3's
  rebuild.

## Verdicts

1. **Nothing of main lost - OK but Medium 1.** See "Separating the resolution": 117 of main's 124 own files are main's
   bytes, the other 7 are additions; in the shared files only the entry page's header lost main's text.
2. **Nothing of ours lost - OK.** Every control, line and test from `038822a` is on a page (list above);
   `tests/music_spotify.rs`' changes are the same assertions on the new routes and markup (plus the Attention row and
   `/dns?device=` redirect), none weakened (the streaming-before-downloads order check now runs on the ⓘ's paragraphs).
3. **Design 31 conformance - Medium 2, Medium 3.** Right as main does it: the group with `section#music-spotify`,
   `aria-labelledby`, `form.group`, the inline `select.pick` with `pick-arrows` and a `<noscript>` row Save, the flash
   `p.flash.flash-error[role=status]` inside the group, the form's `#music-spotify` action and redirect (as Sonos's),
   the redirect-table row (`device_pages.rs:2457-2459`, binding checked), "From Spotify ›" as Storytel's row with a
   green tile and a value, the library row's sub "Spotify playlist · 3 tracks · 1 phone" and "Open in Spotify" among
   the actions, the import ⓘ (64 words) and the streaming ⓘ (71 words) under 80, `tab` per template. The Attention
   row: kinds right (`reauthorize`, `refused`, `not_premium`, `no_widevine`; `unreachable`/`quota` are transient), 6
   words, Action level, target an element the page has while bound, counted by the summary and the list through the
   same `facts_of` (Low 1 for the list's pin, Low 3 for the account side). Not as main does it: the warning/line/footer
   shapes (Medium 2) and the Spotify page body (Medium 3).
4. **Behaviour - OK.** `check_after` is main's expression in a method, in the Phone Setup zone (`Zone::load`, as the
   code it replaces); the phone lines return `(phone, line)`, gated first, Spotify's capitalised ("Plays as Ella",
   "Not sent - Spotify is off on this phone"), "(as of ...)" through `zone.when`. `device_card` feeds
   `spotify_binding_card` the report's word, the ticked Spotify count and only a `spotify_*` notice (the Sonos/tick
   notices don't see it; `toggle_refusal` ignores it). `view_filter`'s marks are step 3's fix-round logic unchanged:
   bound (`policy.music_spotify_account`) and `spotify::is_spotify_host` for the phone's blocks, the site-wide blocks
   by the same query `/dns` used (`device_id IS NULL AND list_type = 'block'`), now a 500 on a DB error instead of
   `unwrap_or(false)`. The seven event labels name exactly the ids written (`record_security_event`/`log_event` in
   `spotify.rs`, `music_spotify.rs`, `music.rs:4536`). The three login codes in `NOT_EVENTS` are right: they are the
   `details` argument of `spotify_login_failed` (`music_spotify.rs:797-803`, `spotify.rs:1394-1400, 1428`) and
   `access_denied` is the cancel that writes nothing; the event itself is written and labelled, so the security log
   loses nothing.
5. **Suites - OK.** All green (above); clippy's 12 are main's 12.
6. **Docs - OK but Low 5.** The merge section's conflict list, resolutions and validation are accurate (933/1146/845,
   lint, selftests reproduced); its description of `list_title` and the "orange notes" are what Medium 1 and 2 correct.
   Release's open point 8 is closed and the Release "branch" paragraph updated; §6l's "Plays as" and DNS-note lines
   follow the new routes, three later lines don't; `server/CLAUDE.md`'s Spotify section describes Device › Music, the
   Attention row, the library row, the entry page and the Web Filter page, with four stale "card" phrases.

## Fix-round checklist

- [ ] M1: `list_title` "Books" for Storytel; pin Books/Tracks/Episodes on the entry pages.
- [ ] M2: `SpotifyBindingCard.warnings` as `Note`s through `note_warning.html` (≤ 6-word titles, one sentence,
      "phone"); the phone's outcome as an "On the phone" row; the login line and the unsent count as `row-sub`s; a
      one-sentence footer; Web Filter: one footer while bound, the rest in the ⓘ; tests' strings follow.
- [ ] M3: Release (and the merge section) say what happens to the Spotify page before the merge to `main` - a 26c
      step 9 rebuild (recommended) or an accepted open point (Question 1).
- [ ] L1: a `music-spotify` case in `each_music_warning_raises_its_row_and_marks_the_music_row`.
- [ ] L2: pin the Spotify form's `<noscript>` Save; check it red with the Save deleted.
- [ ] L3: account-side Attention rows in `attention::facts`, or the decision written into 26c §3.6.
- [ ] L4: Spotify's dates through the zone.
- [ ] L5: §6l `:1110-1115, :1186-1187, :1200` (+ the Attention row check), the QA checklist lines, `PLAN.md:142-146`,
      `server/CLAUDE.md:783, 796, 800, 859-861`.
- [ ] L6: a Spotify entry page in the Music tab's redirect table.

## Questions for the user

1. **The Spotify page's layout when the branch goes to `main`** (Medium 3): rebuild it as design 31 groups first (a
   small step on this branch: the app, accounts and add form as groups, the setup text and key/env details behind ⓘs;
   no URL changes), or merge with it in the old card layout inside the new shell and rebuild later? QA recommends the
   rebuild first: it is the one page on `main` that would still look and read like the old PWA.

## Not worth fixing

- `spotify_card` spells its count's plural by hand where main has `plural()`, and doesn't clamp a negative
  `item_count` (`.max(0)`) as main's does - a listing never stores one.
- `spotify_phone_line`'s `None => "Spotify is off on this phone"` arm can't be reached (`gated` answers first for an
  unbound phone).
- `device_music.html:13-17`'s "where the forms return" comment doesn't name the Spotify form (its own comment at
  `:150-153` does).
- `MusicTemplate.spotify_state` vs main's `storytel_value` naming.
