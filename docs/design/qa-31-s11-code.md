# QA 31 step 11 (code) - the Apps tab, the app page and Add App

Reviewer: code QA, 2026-10-13. Branch `design-31-s11`, `4d225da4..4e9e9160` (one commit, 14 files, server only).
I read it against `docs/design/31-pwa-redesign.md`:
- §1.5, §3.0, §3.3, §4.4, §5.1-§5.7;
- §8 step 11 and the "Step 11 implementation status" with its 9 choices;
- the Apps and AnnaApps mockups;
- `qa-31-s1-code.md` and `qa-31-s2-code.md` for the house style.

I also read the code the step touches or leans on:
- `handlers/tracked_apps.rs`: the whole diff, plus `fetch_latest_release`, `stream_to_file`, `download_asset`,
  `sync_one_app`, `sync_release`, `check_pins`, `check_new_package`, upload, `confirm_package`, `forget_package_name`
  and `delete_tracked_app`;
- `handlers/devices.rs`: the Apps card build (`:1380-1560`), to check "N allowed";
- the three templates, `partials/app_header.html`, the CSS diff and `static/scroll-restore.js`;
- `tests/tracked_apps.rs`, `tests/catalog_pins.rs` and the `tests/music.rs` change.

Line numbers are `4e9e9160`'s. No product code was changed: the mutations and the page-dump test below were
temporary and are reverted, and `git status` was clean before this file was written.

**Verdict: merge after a small fix round.**
- Every form keeps its POST target, field names and redirect.
- Forget and Remove are behind `<details>` confirms.
- The pins, the refused-release memory and the package-confirm flow behave as before.
- The templates escape everything.
- The CSS-only source switch works without JS on the browser floor.
- "N allowed" is the device page's tick count by construction.

The one real regression is where a refused upload or package confirmation shows its result:
- **M1:** the refusal now renders deep inside Source & Signing on a page that loads at the top. A refused upload,
  even "Signed with a different key", looks like nothing happened. Before this step, the error was the first line of
  the page.

The rest are a `SyncProblem` kind that mixes local disk errors and a permanent size refusal into "Download failed"
(L1), and guards that no test pins (L2-L6).

Counts: 0 High, 1 Medium, 7 Low, plus nits.

## What I ran

All cargo runs used my own target dir (`server-target-qa31s11`, deleted afterwards), under the shared build lock.
- **`cargo test`, unchanged:** 731 passed, 0 failed.
- **`cargo fmt --check`:** clean.
- **`cargo clippy --all-targets`:** 13 warnings, the known ones:
  - `models.rs` (9), `dns_engine.rs` (2) and `storytel.rs` (1);
  - `devices.rs:1530`, step 2's old `sort_by`, moved down by this diff.

  None is in the step's new code.
- **`node --test jstest/*.test.js`:** 16 passed.
- **The browser pass.** A temporary test dumped four pages:
  - the app page, with a `Download` failure whose detail is a 600-character signed GitHub CDN URL, and the name
    `Element <b>X</b>`;
  - the tab;
  - Add App;
  - the page a refused upload renders.

  Headless Chromium rendered them at **390 × 844, light and dark, with JS on and off**. The sandbox has no fonts, so
  only the layout was checked, not the text.
  - No horizontal scroll on any page. The opened ⓘ panel wraps the long URL (scrollWidth 390, panel 326/326).
  - On the refused-upload page, the flash's top is at **y = 983 (JS) / 1071 (no JS)** in an 844 px viewport, at
    scrollY 0 (M1).
  - The headless shell crashes on `<input type="file">` here, so the file input was swapped for a text input in a
    copy for this measurement.
- **Mutations.** Each was a temporary edit, run against `cargo test -- tracked_apps catalog_pins music`, then
  reverted. The mutants were in two batches with distinct killers. Batch A passed as a whole, so each of its mutants
  survives.

  | Mutation | Result |
  |---|---|
  | `forget_package_name` redirects to `/apps` | killed (`every_form_on_the_app_pages_returns_to_its_page`) |
  | `allowed_count` ignores the phone's selection | killed (`the_apps_tab_lists_the_phones_and_the_catalog`) |
  | Source & Signing never opens on a refusal | killed (`catalog_pins::the_first_release_pins…`, `an_invalid_asset_regex_is_refused_on_edit…`) |
  | `confirm_package` success redirects to `/apps` | **survives** (L3) |
  | GitHub 404 classified `GithubError` | **survives** (L2) |
  | download errors classified `Unreadable` | **survives** (L1) |
  | Forget's form moved out of its `<details>` (one tap again) | **survives** (L4) |
  | `\|safe` on the failure detail, the catalog label and the upload flash detail | **survives** (L5) |
  | `allowed_count` counts Play's core apps | **survives** (L6) |

## Checked and fine

- **Every form keeps its POST target and behaviour.**
  - The old page had ten forms: details, display, confirm-package, forget, check, include-prereleases, upload,
    enabled, is-launcher and delete. All ten are on the new page, with the same field names: `name`/`github_repo`/
    `asset_pattern`/`release_tag_prefix`, `package`, `include_prereleases`/`enabled`/`is_launcher` (value `on`),
    `release_label`/`apk`.
  - No handler's redirect changed. Display goes to `#display`, Remove to `/apps`, Add to the new app, and the rest to
    the app's own path.
  - The two pre-releases rows are github-only, as before.
- **The redirect table** posts every form rendered on a GitHub app's page and a manual app's page. A form without a
  row panics, so a moved form can't keep a stale target. The one gap is the confirm-package form, which isn't
  rendered there (L3).
- **Sync, pins and refusals.**
  - `refused` keeps the kind across the hourly skip ("not downloaded again" keeps "Signed with a different key").
  - Forget clears it.
  - `check_pins` and `check_new_package` map to OtherApp, OtherKey, CantCheck, Taken and Server, which is right for
    every branch.
  - The upload path removes the file on every refusal, as before.
  - `SyncFailure.at` (`utc_now`, `%Y-%m-%d %H:%M:%S`) parses with `parse_db_utc`.
- **The raw detail.** It sits only in the ⓘ panel and the log.
  - The server sends no credential to GitHub: the API call is unauthenticated, and there is no token in any URL.
  - reqwest's `… for url (…)` can hold a short-lived signed CDN URL for a public release asset. Anyone can get one,
    so it is not a secret.
  - Server paths (`data/tracked_apps/3/…part`) appear only to the signed-in admin.
  - The whole message was on the page before this step. Now it is behind ⓘ, so this is an improvement.
- **Escaping.**
  - There is no `|safe` in the three templates or the header.
  - `style="background: …"` takes only `tile_of`'s static hex.
  - Phone hrefs are built from an id and a package that passed `valid_package_name`.
  - The GitHub link is prefixed with `https://github.com/`, so it can't become `javascript:`.
  - `Element <b>X</b>` rendered as text in the dump. Nothing pins this in a test, though (L5).
- **The CSS-only source switch.**
  - It uses two real radios with the same `name`, placed before `.group` and `.github-only`, and sibling selectors
    only: no `:has()`.
  - The GitHub fields are not `required`, so a hidden field can't block a submit.
  - The server ignores GitHub fields for `manual`.
  - Focus shows on the row through `:focus ~ … label[for]`. The `:focus:not(:focus-visible)` reset is dropped as an
    invalid rule on Chrome 80-85 and Safari 14, so focus still shows there (§5.7).
  - In forced colours, the check mark uses `CanvasText`.
- **The `subpage` flag.** `is defined` is resolved at compile time per including template. Only the two rebuilt
  pages set it, and the other pages compile without it (the full suite passes). See N1 for one sharp edge.
- **"N allowed".** `allowed_count` is the device page's two passes:
  - reported apps, minus Play's core, ticked by the allowlist;
  - catalog apps that aren't the launcher or Play's core and aren't reported installed, ticked by
    `device_tracked_apps`.

  It reads the same "latest report" query, and a corrupt allowlist shows "List unreadable", as the page warns. The
  launcher's pinned row is `disabled` without `name="selected"`, so the test's tick count excludes it too. The tab's
  "N phones" and the app page's On phones list both read `device_tracked_apps`.
- **Confirms and no-JS.**
  - Forget and Remove are `<details>` with a POST inside (§5.6, M6).
  - Every auto-saving switch has a `<noscript>` Save.
  - Check Now is a plain POST.
  - The new pages have no inline script.
- **Accessibility and contrast.**
  - Switches are `role="switch"` in a `<label>`.
  - The ⓘ summaries in fields have `aria-label`s and are 44 px targets.
  - The field errors have `aria-invalid` and `aria-describedby`.
  - Avatars and tiles are `aria-hidden`.
  - `--red-text` `#B0001A` on white is about 7:1. `--label-2` on white is 5.3:1.

## Medium

### M1. A refused upload or package confirmation shows its reason off-screen: the page loads at the top, the flash is ~1000 px down inside Source & Signing

- **Where.**
  - `tracked_apps.rs:1891`, `:1896` and the label/file/save refusals in `upload_tracked_app_release`. Also
    `confirm_package` at `:2186`.
  - All of them **render** the page in the POST's response, at `/apps/tracked/{id}/upload` or `…/confirm-package`.
  - The flash is in the Upload group (`tracked_app_detail.html:197`) or under the package row (`:103`), inside
    `<details id="source" open>`.
- **Why it lands at the top.**
  - `scroll-restore.js` restores only on the same path (`restoreTarget`), and `/upload` isn't the page's path.
  - Nothing else moves the page: there is no fragment and no `autofocus` in the flash.
- **Failure.**
  - The parent uploads a new version of a manual app that is signed with another key.
  - The page comes back at its top: Version (unchanged), Last upload, Show on phones as, On phones.
  - "Signed with a different key" sits at y = 983 (y = 1071 without JS) on an almost empty page, measured. On a real
    page it is further down, under the tab bar.
  - Before this step, the same refusal was a red box under the title.
  - The parent most likely concludes the upload went through, or did nothing. For the two security refusals (another
    key, another app), that is the wrong conclusion.
  - The same applies to "Vibb didn't install that app" and "Couldn't save. Nothing was changed." on confirm-package.
- **Fix.** Make the result visible where the page lands. Either option works on the browser floor without JS.
  - **(a)** When `upload_error` or `package_error` is set, also render one red row at the top of the page, in the
    `#check` group: "Upload refused: Signed with a different key". Its ⓘ holds the detail, as the failed-check row
    does. Keep the in-group flash.
  - **(b)** Give the two forms a fragment, `action="/apps/tracked/{id}/upload#upload"` and
    `…/confirm-package#release`. The rendered response keeps it and scrolls to the open group.
    - On success, the 303's fragment-less `Location` then inherits `#upload`, which lies inside the closed Source &
      Signing.
    - So redirect explicitly, to `/apps/tracked/{id}#check`, or to a `?…` that opens Source & Signing.
  - Add a test: the refused upload's response has the refusal before Show on phones as (a), or the forms' actions
    carry their fragments (b).

## Low

### L1. `SyncProblem::Download` also covers local disk errors and a permanent size refusal

- **Where.**
  - `sync_release` maps every `download_asset` error to `Download` (`tracked_apps.rs:999`).
  - These errors include `can't create … .part` and `can't write … .part` (`:322`, `:353`), `can't move the download
    into place` (`:362`), and the 1 GB limit (`:334`, `:381`, `:410`).
- **Failures.**
  - The Pi's SD card is full. The app page and the catalog say "Download failed", and the parent presses Check Now
    again and again. The step's own `Server` kind ("Something went wrong") exists for exactly this. `create_dir_all`
    one line earlier (`:980`) already uses it.
  - A release whose APK is over 1 GB is refused before any byte is fetched. It shows "Download failed" every hour,
    which reads as transient, although it never changes until the release does.
- **Also:** nothing pins the `Download` kind. Turning every download error into `Unreadable` survives the suite.
- **Fix.**
  - Let `stream_to_file`/`download_asset` return a `Failure`, or a small enum mapped at `:999`:
    - temp-file and rename IO → `Server`;
    - over the limit → a new `TooBig` ("Release is over 1 GB");
    - network, stall, size mismatch, not a ZIP → `Download`.
  - Add a unit test per kind. The `stream_to_file` tests with canned chunks are already there.

### L2. The GitHub classification is untested

- **Where.** `fetch_latest_release`, `tracked_apps.rs:177-195`.
- **What survives.** Classifying a 404 as `GithubError` passes every test. So does any other swap among
  `GithubUnreachable`, `RepoNotFound`, `GithubError` and `NoRelease`.
- **Failure.** A typo'd repo then says "GitHub returned an error" instead of "Repo not found on GitHub", and nothing
  catches it.
- **Fix.** Pull the status → kind choice into `fn github_problem(StatusCode) -> SyncProblem` and unit-test it with
  404, 403 and 502. Map `newest_matching_release`'s `Err` in a tested helper too, or test it through a local listener
  as the download tests do.

### L3. The redirect table never sees the confirm-package form; its success redirect is unpinned

- **Where.** `tests/tracked_apps.rs:1393` (`every_form_on_the_app_pages_returns_to_its_page`).
- **The gap.** The test renders an app with a package and a manual app with no candidates, so
  `/confirm-package` is never rendered. Its "a form without a row panics" guard never fires for it.
- **What survives.** The existing confirm test checks only `confirmed.status.is_redirection()` (`:417`), so a
  `confirm_package` that redirects to `/apps` passes.
- **Fix.**
  - Seed one candidate in the redirect-table test: a report with a package "from me.vibb.launcher", as
    `:371-380` does.
  - Add the row `"/confirm-package" => &[("package", …)]`.
  - Or at least use `assert_redirect(&confirmed, &page, None)` at `:417`.

### L4. The Forget confirm test doesn't check that the form is inside the confirm

- **Where.** `tests/tracked_apps.rs:1294-1303`. The test checks that the forget action appears somewhere after
  `id="forget"`, and that `id="forget"` is on a `<details class="confirm`.
- **What survives.** Closing the `<details>` right after its summary leaves the form outside, so Forget is one tap
  again (§5.6's M6). That passes.
- **Fix.** Assert that the action lies between `id="forget"` and the next `</details>`. Do the same for `id="remove"`.

### L5. No test pins escaping on the three rebuilt pages

- **What survives.** Adding `|safe` to the failure detail (`tracked_app_detail.html:32`), the catalog label
  (`apps_list.html:38`) or the upload flash detail (`:197`) passes.
- **Why it matters.** These carry outside text:
  - reqwest and APK-parser messages;
  - the phone-reported package in the confirm refusal;
  - the device name in `Taken`;
  - the display label.
- **Fix.** Put `<b>x</b>` in an app name, its display label, a device name and a `Failure` detail in one page test,
  and assert `&lt;b&gt;` on `/apps` and the app page.

### L6. `allowed_count`'s Play-core filter is unpinned

- **Where.** `tracked_apps.rs:1257`. The tab test's report has no Play core package, so dropping
  `.filter(|app| !is_play_core(…))` passes.
- **Failure.** If that filter goes, a phone with Play shows "5 allowed" while its page ticks 3. That is exactly the
  disagreement choice 2 promises can't happen.
- **Fix.** Add one `PLAY_CORE` package, allowed, to the report in `the_apps_tab_lists_the_phones_and_the_catalog`.
  The device page must still tick 3 and the row read "3 allowed".

### L7. Add App's `role="radiogroup"` wraps the GitHub fields, More Options and its switch

- **Where.** `tracked_app_add.html:25-72`. The `div` with `role="radiogroup" aria-labelledby="source-header"` holds the
  two radios, but also the repo field, the filter, the tag prefix, the package and the pre-releases switch. It has to,
  for the sibling selectors.
- **Failure.** A screen reader announces "Source, radio group" on entering the repo field, and treats the text inputs
  and the switch as part of the choice.
- **Fix.** Drop `role` and `aria-labelledby`. Two native radios with one `name` already form a group ("1 of 2"), and
  the visible "Source" header comes just before them. Or name each radio fully, e.g. `aria-label="Source: GitHub
  releases"`.

## Nits

1. **`partials/app_header.html:6`.** `subpage is not defined` hides the bar for any definition, so
   `{% let subpage = false %}` hides it too. Say so in the partial's comment ("set it to anything"), so step 3 doesn't
   try `false`.
2. **`style.css?v=11`.** Step 3's branch is at `v=10` now. If it changes CSS, it will also go to 11, and the second of
   the two to merge must take `v=12`. Otherwise a browser keeps the first one's CSS.
3. **`server/DEPLOY.md:422-428`** still says "Apps > Add", "asset filename filter" and "package name:
   `me.vibb.launcher`". The page now says "Add App" and "File name contains" (under More Options), and the add form's
   package isn't saved.
4. **`tracked_apps.rs:961`.** The refused-release detail says `"Check now" tries again`, but the button is now "Check
   Now".
5. **`version_of` uses `rfind("-v")` (`:1167`).** A tag such as `v2.1.0-v8a` reads as "8a". Prefer the first `-v`
   before a digit, or strip only the row's prefix and a leading `v`.
6. **Phone order differs.** `/apps` sorts phones with `ORDER BY name` (`:1331`, binary, like Devices), and the app page
   with `COLLATE NOCASE` (`:1706`), so "anna" sorts differently on the two.
7. **`:1227`.** The launcher's sub-line says "all phones" even when no phone is set up yet ("No phones" would be true).
8. **The two flash ⓘ icons** are both labelled "More about this" (`tracked_app_detail.html:103`, `:197`). Name them,
   e.g. "More about the refused upload".
9. **Dead or duplicated CSS.**
   - `.group-tiles` (`style.css:1579`) repeats step 2's `.group-avatars` (`:429`).
   - `.flash-line`, `.flash-info` and `.catalog-empty` have no rules: harmless hooks, but nothing styles them.
10. **The tab's phone row** opens the device page at its top. The Apps card there is far down and has no `id`
    (`device_detail.html:319`). Choice 1 defers this to step 6; if step 3 touches that card first, an `id="apps"` and
    `/devices/{id}#apps` would make the "one tap shorter" path real now.
