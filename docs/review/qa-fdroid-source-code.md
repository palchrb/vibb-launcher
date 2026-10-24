# QA (code): an F-Droid repo as a release source (Etar first)

Branch `fdroid-source` on `main` 6011c024, four commits: 66574337 (migration 0094 and the model), 05537dcd
(`src/fdroid.rs` and the sync), 975d10fd (forms, pages, tests), 8467e3a1 (docs). Server only. Reviewed 2026-10-24 on
branch `qa-fdroid-source` (`git diff 6011c024..fdroid-source`, 21 files, +2709/-95). No Gradle, no emulator, nothing
pushed, nothing fixed.

## Summary

- **Fail closed holds on both paths.**
  - The index is streamed to a `.part` file. Its size and SHA-256 are checked against entry.json before the rename.
  - The APK is streamed the same way. Its SHA-256 is checked against the index inside `stream_to_file`, before the
    flush and the rename. A mismatch returns before the row's `UPDATE`, and `TempDownload` removes the part file.
  - The APK's package must equal `fdroid_package` (`check_fdroid_package`). That check runs before the pins, and the
    pins are stored only after the row names the file.
  - An F-Droid app can't take an upload, can't be the launcher, and can't change its source.
  - The phones' catalog entry has the same shape as a GitHub app's (`the_phone_gets_an_fdroid_app_as_any_other`).
- **The http test hook can't reach production.** `Repo::from_stored` allows http only under `cfg!(test)`, a
  compile-time `false` in a release build, and only on `127.0.0.1`/`localhost`. `normalize_repo` never accepts http
  (M23 caught). A stored `http://` repo is a `RepoError` (tested).
- **The version pick is right for index-v2.** I checked it against fdroidserver's `index.py`: `convert_version` writes
  `releaseChannels: ["Beta"]` on every version whose `versionCode` is above the app's `CurrentVersionCode`. So "the
  highest code without a release channel" *is* the suggested version, and the index path agrees with f-droid.org's
  API. index-v2 has no `suggestedVersionCode`; only v1 had it.
- **The redirect policy is untested (Q-1).** "A redirect may not leave https" is part of the trust statement, and
  GitHub's downloads now use the same policy. Both of its rules survived mutation (M9, M10).
- **Two hardening items for a parent-entered URL**:
  - Q-2: refuse private IP literals in the repo address.
  - Q-3: remember a repeated checksum mismatch, instead of downloading up to 1 GB (or a 67 MB index) again every
    hour, forever.
- **Mutations**: 28 run. 21 caught, 1 equivalent (M24), and 6 survived:
  - M9 and M10, the redirect policy (Q-1);
  - M2, the `fdroid_package` arm of the "taken" check (Q-6);
  - M21, the 1 MiB cap;
  - M26, the preference for the `<id>_<code>.apk` file;
  - M28, `file_safe` in the cached file's path.

  The last three are Q-7.
- **Suites**: fmt and clippy are clean (only the 13 known warnings, none on this branch's lines). `cargo test` gives
  1279 passed and 1 failed: the known `the_mock_counter_logs_each_rise`, fixed on `main` by bb327d0e, which this base
  lacks (N-12).

Counts: Blocker 0, High 0, Medium 1, Low 8, Note 14.

## Runs

| Side | Command | Result |
|---|---|---|
| server | `cargo test` (`CARGO_TARGET_DIR=~/.cache/handy-target/qa-fdroid-source`, jobs 2, no debug info, `flock ~/build.lock`; 7.5 GB available at the start) | `test result: FAILED. 1279 passed; 1 failed; 0 ignored; 0 measured; 0 filtered out; finished in 409.52s` - the one failure `tests::zone_watch::the_mock_counter_logs_each_rise` (`panicked at src/tests/zone_watch.rs:536:5: ["device 1: the phone ignored 2 made-up location(s) - an app pretended to be somewhere else (5 in all)"]`) |
| server | `cargo test the_mock_counter_logs_each_rise` (rerun alone) | `test result: FAILED. 0 passed; 1 failed; ...; 1279 filtered out; finished in 0.27s` - it fails every time on this base; bb327d0e on `main` fixes it (N-12) |
| server | `cargo test fdroid` | `test result: ok. 15 passed; 0 failed; 0 ignored; 0 measured; 1265 filtered out; finished in 3.52s` |
| server | `cargo fmt --check` | clean (exit 0) |
| server | `cargo clippy --all-targets` | `Finished`, exit 0; 13 warnings, all on lines this branch doesn't touch (`models.rs` x9, `storytel.rs:144`, `dns_engine.rs:54-55`, `devices.rs:1324`) |

### Mutations (one flip each, the named filter run, reverted; `git status` clean after)

The first pass used the filter `fdroid` alone. That filter misses `an_apk_that_doesnt_match_the_index_is_refused`,
`another_repo_syncs_by_the_index_alone` and `a_tag_is_safe_in_a_file_name`, whose names don't contain "fdroid". So
every mutation that survived the first pass was re-run with `fdroid tracked_apps::tests` (48 tests). The table gives
the final result.

| # | Mutation | Filter | Result |
|---|---|---|---|
| M1 | `parse_fdroid_source`: the launcher refusal off | `tests::fdroid::` | caught (`add_app_refuses_a_bad_fdroid_source_by_its_field`) |
| M2 | the "taken" query without `OR fdroid_package = ?1` | `fdroid tracked_apps::tests` | **survived** (Q-6) |
| M3 | the "taken" query without `id IS NOT ?2` (the app finds itself) | `tests::fdroid::` | caught (`the_edit_form_keeps_one_source_per_app`) |
| M4 | `pick_api` ignores `suggestedVersionCode` | `fdroid` | caught (2: `the_api_suggests_the_version`, the API sync) |
| M5 | `pick`: betas not filtered | `fdroid` | caught (2) |
| M6 | `package_versions`: `beta` always false | `fdroid` | caught (2) |
| M7 | `stream_to_file`: the expected SHA-256 never compared | `fdroid tracked_apps::tests` | caught (`an_apk_that_doesnt_match_the_index_is_refused`) |
| M8 | `download_index`: the SHA-256 never compared | `fdroid` | caught (`the_index_is_cached_by_the_entrys_hash`) |
| M9 | `https_redirects`: https -> http allowed | `tracked_apps` (58) | **survived** (Q-1) |
| M10 | `https_redirects`: `> 5` -> `> 50` | `tracked_apps` (58) | **survived** (Q-1) |
| M11 | `file_safe` keeps every character | `tracked_apps` | caught (`a_tag_is_safe_in_a_file_name`) |
| M12 | `check_fdroid_package`: any package passes | `fdroid` | caught (`fdroid_sync_problems_have_plain_words`, `OtherApp`) |
| M13 | `from_stored`: test http on any host, not loopback only | `fdroid tracked_apps::tests` | caught (2) |
| M14 | `plain_file_name` allows `/` | `fdroid` | caught (`entry_json_names_the_index`) |
| M15 | the API's "already cached" shortcut off | `fdroid` | caught (the request counters) |
| M16 | the source-change refusal off | `tests::fdroid::` | caught |
| M17 | `set_is_launcher`'s F-Droid guard off | `tests::fdroid::` | caught |
| M18 | the upload refusal for F-Droid apps off | `tests::fdroid::` | caught |
| M19 | `remove_older_copies` removes nothing | `fdroid` | caught |
| M20 | a `file.sha256` that isn't the version's key kept | `fdroid` | caught |
| M21 | `get_small`'s 1 MiB cap off | `fdroid tracked_apps::tests` | **survived** (Q-7) |
| M22 | `download_index`: a short index (`written != size`) accepted | `fdroid` | caught |
| M23 | `normalize_repo` accepts http | `fdroid` | caught (3) |
| M24 | `check_pins` before `check_fdroid_package` | `fdroid tracked_apps::tests` | survived - equivalent (N-6) |
| M25 | `MAX_INDEX_BYTES` check doubled | `fdroid` | caught |
| M26 | `pick(Some(code))` without the `<id>_<code>.apk` preference | `fdroid tracked_apps::tests` | **survived** (Q-7) |
| M27 | `index_file`: a cached copy never used | `fdroid tracked_apps::tests` | caught (2) |
| M28 | `sync_release`'s path without `file_safe` | `fdroid tracked_apps::tests` | **survived** (Q-7) |

## 1. Security and fail-closed

### The repo address (SSRF)

- **The rules today.** `normalize_repo` (`fdroid.rs:65`) accepts only an `https://` URL with a host and no user,
  password, query or fragment, at most 200 characters, stored without its trailing slash. `Repo::from_stored`
  (`fdroid.rs:118`) applies the same scheme check again at sync time.
- **What it can still point at.** Any https host is accepted, including `https://127.0.0.1/`,
  `https://169.254.169.254/`, `https://[::1]/`, a `100.64.0.0/10` tailnet address or a LAN name.
- **Why the practical reach is small.**
  - rustls checks the certificate against the public roots, so an IP literal or a LAN host without a real
    certificate fails at the handshake.
  - Cloud metadata services are plain http.
  - What is left is a tailnet or LAN service with a real certificate. Tailscale's `*.ts.net` HTTPS certificates are
    exactly that.
  - Only the parent sees the answers (the error text and serde's messages, not the bodies).
- **What the repo does elsewhere.** The server's other parent-entered URLs, the music sources, deliberately may be
  on the home LAN or the tailnet (`music_net::Reach::Any`, 21b §2.7). A self-hosted F-Droid repo on the LAN is one of
  this feature's stated cases. So refusing private *names* would break a real use. Refusing private *IP literals*
  costs nothing, because no valid certificate exists for them anyway: Q-2.

### Caps, streaming, bombs

- **entry.json and the API answer**: at most 1 MiB, counted on the decoded body. reqwest's `gzip` feature is on, so
  this cap and the others below count decoded bytes. A gzip bomb is bounded by the same caps.
- **The index**:
  - entry.json's `size` must be at most 100 MB (`parse_entry`).
  - The download stops past that size (`written > entry.size`).
  - The `content_length` pre-check is skipped when reqwest decompresses, and the loop still enforces the size.
  - 60 s without data and 10 minutes overall end the download.
  - At most one copy per repo is kept, plus a `.part` file per sync in flight.
  - A parent who adds N repos costs N x 100 MB at most. The parent is trusted for that.
- **The APK**: at most the index's `size` when it lists one, and `MAX_ASSET_BYTES` (1 GB) always. The 30-minute
  overall timeout applies too.
- **The parse** (`package_versions`, on `spawn_blocking`):
  - `IndexSeed` and `PackagesSeed` read keys one at a time.
  - `IgnoredAny` skips every other value without allocating; serde_json's `ignore_str` doesn't buffer.
  - serde_json's recursion limit (128) stops a nesting bomb.
  - Only the one package is materialised. A hostile repo could make that one entry most of the 100 MB, a few
    hundred MB of RAM briefly on a blocking thread. That is acceptable, because the file has passed the hash check
    against the same repo's entry.json; the only "attacker" is the repo itself.

### Paths

- **`plain_file_name`** (`fdroid.rs:313`) allows only `[A-Za-z0-9._-]`, no leading `.`, at most 255 characters and the
  right extension. It is used for the index name (`/sub/...` and `/../...` are refused, tested) and the APK file
  (`../x.apk` is left out, tested).
- **The cache name** is `<16 hex>-<64 hex>.json`; `sha256_hex` validates the hash.
- **The cached APK** is `file_safe(tag)-<code>.apk`. `file_safe` maps everything outside `[A-Za-z0-9._-]` to `_`, so
  no separator can survive. `..` can, but with no `/` it is a plain name.
- **The URL** `<repo>/<file>` is built from the validated pieces only.

### The hash before the rename (proof)

The only writers of `latest_release_file_path` are `sync_release` (`tracked_apps.rs:1249`), the upload
(`:2393`) and the hash backfill (`:574`), which never sets a path.

- **The upload** returns 400 for `source_type = 'fdroid'` before it reads the body (M18 caught).
- **`sync_release`** gets the file from `download_asset` -> `stream_to_file`. For an F-Droid asset,
  `expected_sha256` is always `Some`, because `sync_fdroid` builds the `ReleaseAsset` with the index's hash. The
  comparison sits after the last chunk and before `flush`/`sync_all`/`rename`/`temp.keep()`. Any `Err` there drops
  the `TempDownload`, which deletes the part file, and returns through `??` before the identity check and the
  `UPDATE`.

`an_apk_that_doesnt_match_the_index_is_refused` asserts the result: no tag, no path, no signer pin, an empty app
directory. M7 is caught by it. The index has the same shape (`download_index`, M8 caught), and the copy that was
already good stays in place (tested).

### The redirect policy

`https_redirects` (`tracked_apps.rs:499`) has two rules:

- `previous().len() > 5` is refused. `previous()` holds the first URL too, as `music_net` notes, so this allows 5
  redirects.
- When the first URL was https, any non-https hop is refused.

What this means for GitHub:

- A GitHub asset link is one hop: `github.com/.../releases/download/...` -> 302 ->
  `release-assets.githubusercontent.com` (formerly `objects.githubusercontent.com`), https -> https.
- reqwest's default was `limited(10)` and allowed a downgrade, so the new policy is strictly tighter, with no
  regression at 1-2 hops.
- reqwest still drops sensitive headers on a cross-host hop. The GitHub download client sends none anyway.

Neither rule is tested (Q-1).

### Package check order and the pins

`sync_release` runs four steps in this order:

1. `check_fdroid_package` (the APK's package must be `fdroid_package`, else `OtherApp`; an unreadable package is
   `CantCheck`);
2. `check_pins`;
3. `check_new_package` (system packages, a phone that has it from another store);
4. after the `UPDATE`, `store_pins`.

A refusal is remembered (`set_refused`), as for GitHub. So the first F-Droid release pins F-Droid's signing key and
the package, and a wrong package is never pinned: `fdroid_sync_problems_have_plain_words` asserts `package_name`
stays `''` (M12 caught).

### Launcher, music, system, duplicates

`parse_fdroid_source` (`tracked_apps.rs:1689`) refuses, on both Add App and Details:

- Vibb One's packages (`installed_by_launcher`: the configured component's package, `me.vibb.launcher` and its
  `.debug`);
- `music::MUSIC_PACKAGES` (both ids);
- `never_a_catalog_package`;
- a package another row has as `package_name` or `fdroid_package`.

`set_is_launcher` can't flag an F-Droid row; the SQL guard is M17, caught. Combined with `check_fdroid_package`, an
F-Droid row can never hold the launcher's or the music app's APK.

### The source change

`update_tracked_app` never writes `source_type`. The hidden field only *selects the refusal*: a posted value other
than the app's gives a 400 with nothing written. A forged value can therefore never become a change, and a form
without the field (an old page) is saved as before. The test covers F-Droid -> GitHub (with a cached release),
GitHub -> F-Droid (without one), and the launcher's GitHub row.

### The PWA's words

The new `SyncProblem` titles are within the 6-word rule (`sync_problems_have_short_titles`), plain, and no wording
blames the parent:

- "F-Droid doesn't know this package"
- "The repo didn't answer"
- "The repo's answer can't be used"
- "Download didn't match the repo's checksum"

The first is wrong for another repo (Q-9). The refusals end with "Nothing was saved.", as the GitHub ones do. The
radios need no script (`!html.contains("onchange")`).

## 2. Correctness

- **The API path and the index path.**
  - f-droid.org's API: `suggestedVersionCode`, else the highest listed code.
  - The index path: the highest code without a `releaseChannels` entry.
  - The two agree, because fdroidserver marks exactly the versions above `CurrentVersionCode` as `["Beta"]`
    (verified in `fdroidserver/index.py`, `convert_version`).
  - Both ignore `manifest.nativecode` and `usesSdk`. That is fine for Etar, which ships a universal APK, but not for
    an app built per ABI (Q-4).
- **"Already cached -> stop"** (`sync_fdroid`): the API's code and `tag_of(name, code)` must equal the row's. F-Droid
  never republishes a versionCode; a file `<id>_<code>.apk` is immutable on f-droid.org. So stopping before
  entry.json is right.
  - A self-hosted repo *could* rebuild under the same code. The server then keeps the old file. Android treats a
    same-code APK as the same version anyway. Fine.
  - When the API's `versionName` is blank, the stored tag comes from the index's name and the shortcut misses each
    hour. The cost is one entry.json and then `sync_release`'s own "same tag and asset" stop. Harmless.
- **Cache keyed by the entry's hash.**
  - Two syncs of one app can't run at once: the `app_syncs` guard prevents it.
  - Two apps on one repo (Check Now on both) can run at once. Each writes its own random `.part` file, and both
    rename to the same verified name, so that part is safe.
  - The race is with `remove_older_copies`. A sync that just resolved copy X can lose X to the other sync's removal
    before `read_package` opens it, which gives a transient `RepoError` (Q-8).
- **Leftover cleanup.** `remove_partial_downloads` walks every subdirectory of `tracked_apps_dir`, so it covers
  `fdroid/` by construction. Its test has no `fdroid/` directory. That is acceptable, because the code makes no
  special case.
- **The hourly loop.** Apps are synced one at a time, and an error is logged and skipped, so one bad repo never
  blocks the others. A slow repo delays the rest by at most 10 + 30 minutes.
- **A mismatch is retried at the next hour and not remembered** (`download_asset`'s error isn't `set_refused`).
  - A flapping CDN would heal by itself.
  - A tampered mirror or a stale proxy would cost a full download every hour (Q-3).
- **Migration 0094** adds two plain `TEXT` columns, `source_type` has no CHECK (0008), and `TrackedApp` gains both
  columns (`SELECT *`). `migrations_73_to_94_in_order` is renamed in the five notes (dns, hardening-and-pins, launcher-ui, notifications, zones) and in `server/CLAUDE.md`, and
  "The next free number is 0095". The test's doc comment still ends its rename list at "(was
  `migrations_73_to_90_in_order`)", with no 92, 93 or 94 (N-11).

## 3. Tests

The in-process fake (axum on 127.0.0.1, with the API there and the index path on `localhost`, plus request counters)
covers:

- the API path, the index path, and betas;
- an index whose hash or size doesn't match, and an APK whose hash doesn't match;
- the cache hit and miss, and old copies removed;
- an unreachable repo, and a page that isn't a repo;
- an unknown package on both paths, and another package's APK;
- a stored http address;
- the forms' refusals, the page, the edit form, the launcher switch, the upload, and the catalog with its download.

What the mutations found missing:

- the redirect policy (Q-1);
- the `fdroid_package` duplicate (Q-6);
- the 1 MiB cap, the `<id>_<code>.apk` preference, and `file_safe` at its use (Q-7).

`every_form_on_the_app_pages_returns_to_its_page` seeds a GitHub and a manual app only, so the F-Droid page's forms
(Details with the hidden field, Check Now) aren't in the repo-wide "lands on its group" guard (Q-7).

## 4. The open questions

- **(a) A source change is always refused (remove and re-add): fine.** That is the honest model, since another
  source means another key and a reinstall. Extend it to a *repo* change and a package change once the app has pins
  (Q-5): moving from f-droid.org to IzzyOnDroid is the same key change by another name.
- **(b) No beta switch: fine.** Since index-v2's "Beta" means exactly "above the suggested version", a later switch
  is simply "the highest code" on both paths.
- **(c) IzzyOnDroid by the index: fine.** It is an fdroidserver repo, so it has the same `releaseChannels` rule and
  the same `file.name`/`sha256` shape, and its index is well under 100 MB. Its index changes several times a day, so
  expect a few full fetches a day; entry.json's diffs could cut that later.
- **(d) Mismatch retry: remember after 3** consecutive mismatches for the same (tag, code), and let Check Now try
  again (Q-3). A single retry each hour is right for a flapping CDN. Three in a row is a mirror or proxy that is
  wrong, and it shouldn't cost 1 GB an hour.
- **(e) GitHub asset digests: yes, as a small follow-up.** GitHub's Releases API lists `digest: "sha256:<hex>"` on
  assets (2025). Parse it into `ReleaseAsset.sha256`, which today is `#[serde(skip)]`, when present and well-formed;
  the check before the rename is already in place. A missing digest (older assets) keeps today's behaviour.
  Separately, index-v2's `manifest.signer.sha256` could be compared with the APK's signer at the first pin. That
  check is cheap, and it is worth having before the `entry.jar` round (N-14).

## Findings

### Q-1 (Medium) - the redirect policy is untested (M9, M10 survived)

`tracked_apps.rs:499`. "A redirect may not leave https" is in the trust statement. Since this branch, GitHub's
downloads go through the same policy too. Today either rule can be deleted with all 58 `tracked_apps` tests green.

What to do:

1. Move the decision into a pure function, `redirect_verdict(first: &Url, next: &Url, hops: usize) ->
   Result<(), &'static str>`, and unit-test it with these rows:
   - https -> http is refused;
   - https -> https on another host is followed (GitHub's CDN);
   - the 6th hop is refused and the 5th followed;
   - http -> http is followed (the tests' loopback).
2. Add one live test on the loopback fake: a chain of 6 redirects is refused with "more than 5 redirects".

### Q-2 (Low) - a parent-entered repo may be a private IP literal

`fdroid.rs:65` (`normalize_repo`) and `:118` (`from_stored`). Refuse a host that is an IP literal in
`music_net::is_private`'s ranges (use `music_net::literal_ip`), with the field's sentence.

- This removes loopback, link-local/metadata and tailnet literals.
- Names stay allowed, because a self-hosted repo on the LAN or tailnet with a real certificate is a stated use, as
  for the music sources (`Reach::Any`).
- Optionally add `.no_proxy()` to `fdroid::client()`, as `music_net` does, so an inherited `HTTPS_PROXY` can't sit
  in the trust path. Decide that together with the GitHub client.

### Q-3 (Low) - a checksum mismatch is downloaded again every hour, forever

`tracked_apps.rs:1213` (the `??` after `download_asset`) and `fdroid.rs:487`.

- An APK mismatch costs up to 1 GB per hour.
- An index mismatch costs 67 MB per hour for f-droid.org.

What to do: count consecutive `ChecksumMismatch` results per (tag, code). After 3, `set_refused` it with words
saying so, so that only a new release or Check Now tries again. The index can use the same counter keyed by
entry.json's SHA-256.

### Q-4 (Low) - the pick ignores the ABI and minSdk

`fdroid.rs:690` (`pick`), `:544` (`RawVersion`).

- An app F-Droid builds per ABI has one versionCode per ABI. The highest is often the x86_64 build, which the kid's
  arm64 phone can't install.
- `usesSdk.minSdkVersion` above the phone's is the same story.

What to do: parse `manifest.nativecode` and drop versions whose list is non-empty and has neither `arm64-v8a` nor
`armeabi-v7a`. Until then, add one line to the Add App ⓘ: "for apps with one APK for all phones". Not needed for Etar.

### Q-5 (Low) - Details lets the repo or package change after the app is pinned

`tracked_apps.rs:2533`. A changed `fdroid_package` on a row with a pinned `package_name`, or a changed repo on a row
with a pinned signer, is saved without a word. The next sync then refuses with `OtherApp`/`OtherKey`, and the refusal
is remembered, so the system fails closed but the parent is surprised.

What to do: refuse it at the form with the source change's words, by whether a release is cached:

- a package that isn't the pinned `package_name`;
- a repo other than the stored one once `signer_sha256` is set.

Before the first release, both stay editable.

### Q-6 (Low) - one package, two rows: half of the check, and untested (M2 survived)

`tracked_apps.rs:1689`.

- **The F-Droid side.** The "taken" query's `OR fdroid_package = ?1` arm has no test. Add a row to
  `add_app_refuses_a_bad_fdroid_source_by_its_field` where an existing F-Droid row has the package and no
  `package_name` yet.
- **The other side.** A GitHub or manual row's first release (`check_new_package`, `:702`) doesn't look at other
  rows. It can pin a package that an F-Droid row is waiting for, or that another row already has. That gap is older
  than this branch, but the F-Droid refusal now promises "a package another catalog app already is".
- **What to do there.** Refuse it as `Taken` in `check_new_package` when another row has it as `package_name` or
  `fdroid_package`.

### Q-7 (Low) - small test gaps (M21, M26, M28; the page guard)

- **M21**: no test of entry.json or an API answer over 1 MiB. Serve 1 MiB + 1 bytes from the fake and expect
  `RepoError`.
- **M26**: no index has two APKs of one code. Add a `<id>_<code>.apk` and another file with the same code, and
  assert the named one is picked.
- **M28**: `file_safe` is tested alone, not at its use. Sync an F-Droid version named `1.0/57 beta` and assert the
  path ends `v1.0_57_beta-57.apk` inside the app's directory.
- **The page guard**: add an F-Droid row to `every_form_on_the_app_pages_returns_to_its_page`. Its Details form must
  post the rendered `fdroid_*` values, or the save is refused.

### Q-8 (Low) - the index cache: orphans and a removal race

`fdroid.rs:508` (`remove_older_copies`), `tracked_apps.rs:2995` (the delete).

- **Orphans.** Removing the last app of a repo leaves its index on disk: 67 MB for f-droid.org.
- **The race.** A concurrent sync of another app on the same repo can remove the copy this sync is about to read.
  The result is a transient `RepoError`.

What to do:

- At startup, next to `remove_partial_downloads`, delete cache files whose key matches no stored `fdroid_repo`.
- In `read_package`, on `NotFound`, call `index_file` once more. Alternatively, keep copies younger than a minute.

### Q-9 (Low) - two wording nits

- **`FdroidUnknown`.** "F-Droid doesn't know this package" (`tracked_apps.rs:903`) is shown for IzzyOnDroid or a
  self-hosted repo too. Use "The repo doesn't have this package" (5 words), or word it by the repo label.
- **The download client's user agent.** `download_asset`'s client says `kid-phone-server (self-hosted, github.com)`
  (`:459`) for F-Droid downloads too. Use a neutral one.

### N-1 (Note) - the test-only http is gated

The gate has three parts:

- `cfg!(test) && scheme == "http" && host in {127.0.0.1, localhost}` (`fdroid.rs:121`; M13 caught);
- `API_HOSTS` has its own `#[cfg(test)]` twin;
- the forms never accept http (M23).

A release binary can neither store nor sync an http repo.

### N-2 (Note) - `releaseChannels` is the right field

It is checked against fdroidserver's `convert_version`: `ver["releaseChannels"] = ["Beta"]` exactly when
`versionCode > CurrentVersionCode`. index-v2 carries no `suggestedVersionCode`. The fake's index shape matches
fdroidserver's: `file.name` has the leading `/`, the version's key is the file's SHA-256, and `manifest` holds
`versionCode`/`versionName`.

### N-3 (Note) - gzip and the caps

reqwest's `gzip` is on, so every cap counts decoded bytes and the SHA-256 is computed on the decoded index, which is
what entry.json hashes. With gzip, `content_length()` is `None` and the pre-check is skipped; the loop's cap still
holds.

### N-4 (Note) - paths are safe

Covered in §1: `plain_file_name`, `sha256_hex`, the cache name and `file_safe`. Nothing from the repo reaches the
file system as a path.

### N-5 (Note) - the APK can't be served unverified

The proof is in §1: the only writer for an F-Droid row is `sync_release` after a hash-checked rename. M7 is caught.

### N-6 (Note) - M24 is equivalent

On a first release both checks pass or `check_fdroid_package` alone refuses. On a pinned row, both give `OtherApp`
for another package. The order as written (the F-Droid package first) gives the clearer words. Keep it.

### N-7 (Note) - GitHub's downloads under the new policy

GitHub uses one hop, https -> https, so 5 is ample. The policy also closes the https -> http downgrade that
`limited(10)` allowed. The GitHub *API* client (`:176`) keeps reqwest's default policy, which is fine for
`api.github.com`.

### N-8 (Note) - "already cached -> stop" is right

f-droid.org never republishes a versionCode. A self-hosted repo that does would be ignored at the same code, as
Android itself would treat it.

### N-9 (Note) - the hourly loop

It runs serially, logs and skips, and nothing is lost. A slow repo delays the others by at most 40 minutes.

### N-10 (Note) - one source per app is enforced server-side

The field can't be forged into a change; the handler never writes it. An F-Droid app is refused as the launcher and
for an upload (before the body is read). The launcher's and music's packages are refused as F-Droid packages.

### N-11 (Note) - the migration test's doc comment

`tests/music_spotify.rs:182-184`: the rename list stops at "(was `migrations_73_to_90_in_order`)". Add 92 and 93 (the
zones step 2) and 94 ("was `migrations_73_to_93_in_order`").

### N-12 (Note) - the zone-watch test fails on this base

`the_mock_counter_logs_each_rise` failed in the full run and again alone. bb327d0e on `main` ("the mock counter's rise
and its security line in one transaction; the flaky test") is not in 6011c024. The merge brings the fix; run the full
suite once after it.

### N-13 (Note) - the add form's one-source line

"F-Droid signs apps with its own key" is not true of every f-droid.org app: a reproducible build ships with the
developer's signature. As a warning it errs on the safe side, so keep it.

### N-14 (Note) - the trust anchor until `entry.jar`

The PLAN follow-up is right. Meanwhile, index-v2's `manifest.signer.sha256` lists the APK's signing certificate.
Comparing it with `identity.signer_sha256` at the first pin is cheap. It doesn't add trust against a tampered index,
which could change both, but it does catch a mixed-up mirror, and it is a natural part of the `entry.jar` round.

## Verdict

**Merge after a short fix round.** The core is fail closed and correct:

- the index and the APK are hash-checked before the rename, on both paths;
- the package check comes before the pins;
- the http test hook is compile-time gated;
- the pick matches index-v2's semantics;
- the forms keep one source per app, and keep the launcher and music rows on GitHub;
- the phones' catalog is unchanged.

In the round:

- Q-1: test the redirect policy, since the trust statement and GitHub's downloads rely on it.
- Q-2: refuse private IP literals.
- Q-3: remember a repeated mismatch.

Q-5 to Q-9 are small and can go in the same round. Q-4 can wait for the first per-ABI app, with the one ⓘ line
meanwhile. Question (e) is a good small follow-up for the backlog.
