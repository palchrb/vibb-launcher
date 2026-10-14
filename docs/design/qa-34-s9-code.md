# QA 34 step 9 (code): softer built-in wallpapers

Reviewed: `design-34-s9` at `4c0b5da6` against `1d34ad86` (design 34 P8, §5 step 9, qa-34-design M1/N2).

## Summary and verdict

**Ship it.** 0 High, 0 Medium, 0 Low, 2 Nits. The two Nits are in the docs only.

The migration does what P8 and M1 ask:
- It has three UPDATEs keyed on `builtin_key`.
- `green` changes `kind` to `gradient` in the same UPDATE as its two colours.
- Ids, keys and `sort` stay the same.

It is safe on every existing database:
- **The built-in rows are always there.** The handler refuses to delete a built-in (`handlers/wallpapers.rs:231`), and nothing in the server ever UPDATEs a `label` or `colors`. So each UPDATE matches exactly one row, with a known label.
- **The table's CHECKs still pass.** `kind IN (...)` and `(kind = 'image') = (image_hash IS NOT NULL)` hold for a gradient with no `image_hash`, and so does `builtin_key IS NULL OR kind <> 'image'`.
- **No trigger needs the change.** The only wallpaper trigger, `devices_get_builtin_wallpapers`, reacts to new devices, not to wallpaper updates.
- **Phones see the change without a revision bump.** The policy is built from the rows on every fetch, and restarting the server for the migration drops the SSE stream. Every reopen syncs (`push/SyncSchedule.kt`).
- **The launcher redraws.** Its `wallpaperKey` holds `fill.describe()`, so the changed colours, and green's `solid:` to `gradient:`, make it apply the system wallpaper again.
- **Picks and lock choices survive.** The kid's pick (`KEY_PICKED`, an id), `device_wallpapers` and `device_lock_wallpaper` all key on ids, which don't change.
- **The pre-migration backup runs.** 0076 is above 0075, so the backup (which compares the highest version) is taken.

The launcher side is just the six strings. It keeps no built-in colours of its own; the only built-in it hard-codes is `NAVY`, which is unchanged.

Grepping all three apps, the server's `src/`, `templates/` and `static/`, and `testdata/` for the old colours (`#2B8A3E`, `#1C7ED6`, `#F76707`, `#862E9C`) finds only:
- the frozen `0028`;
- the server's unit tests for `checked_colours`/`css_background` and the launcher's `WallpapersTest`/`IconTonesTest`, which use them as generic colours;
- the app-icon palette entries named `sky`/`sunset` (`app_icons.rs`, `AppGlyphs.kt`, both `app_icons.json`). These are a different palette that happens to share the names, and must not change.

**Contrast**, computed independently with WCAG relative luminance:

| Stop | White | `#FF8A8A` (lock emergency link) |
|---|---|---|
| `#2A4A4F` (Fjord) | 9.59 | 4.22 |
| `#34406A` (Evening sky) | 10.06 | 4.43 |
| `#5A3A4A` (Dusk) | 9.82 | 4.33 |
| the three dark stops | 17.9-18.3 | 7.9-8.1 |

White ink with no scrim is right. The red link falls below 4.5:1 on every lighter stop, and `lockScrimForColours` adds the small scrim each one needs (the extended test proves this).

## Findings

### N1 - The docs say only Dusk needs the lock scrim for the red link; all three softened gradients do

`docs/design/34-launcher-polish.md:794` says "Dusk's lighter stop needs a light lock scrim for the red emergency link (#FF8A8A is about 4.2:1 on #5A3A4A)". `launcher/CLAUDE.md:499` repeats it.

`#FF8A8A` is 4.22:1 on Fjord's `#2A4A4F`, 4.43:1 on Evening sky's `#34406A` and 4.33:1 on Dusk's `#5A3A4A`. All three are under 4.5:1, and the 4.2 the doc quotes is Fjord's figure. The code and test are right: `lockScrimForColours` gives each one a scrim, and the extended lock test runs over every built-in. Only the note is wrong, and the next reader would take Fjord and Evening sky as scrim-free on the lock.

*Fix:* in both places, write "each softened gradient's lighter stop needs a light lock scrim for the red emergency link (#FF8A8A is 4.2-4.4:1 on them), which `lockScrimForColours` gives".

### N2 - "the design said 9.6" reads as if the design was wrong; it was right

`docs/design/34-launcher-polish.md:793` and the KDoc at `WallpaperInkTest.kt:60` give "about 9.5-10.1:1". The lowest value is Fjord at 9.59:1, which is 9.6, as the design (`:174`) said. The test's `>= 9.5` bound is fine.

*Fix:* change the note to "about 9.6-10.1:1; the test asserts >= 9.5" and drop "the design said 9.6". In the KDoc, change 9.5 to 9.6.

## Mutations

| # | Mutation | Result |
|---|---|---|
| S1 | 0076 matches by `id = 4/5/6` instead of `builtin_key` | **Survived** (15/15 `wallp*` tests pass). This mutant is equivalent: `0028` inserts the built-ins with explicit ids 1-6 and they can't be deleted, so on every real database the two WHERE clauses pick the same rows. The by-key form is still the right one, and no test can tell them apart. Not a finding. |
| S2 | `green`'s `kind = 'gradient'` dropped (stays `color` with two colours) | Killed by 3 tests: `every_builtin_row_passes_to_policy_after_all_migrations`, `the_softened_builtins_keep_their_ids_and_keys` and `every_phone_gets_the_builtins_with_every_key`. |
| S3 | Migration renumbered to `0077` | Killed by `migrations_73_to_76_in_order`. Reverting the test's range to `73..=75` would fail too, because the expected array lists 76. |
| L1 | `lockScrim` returns no scrim for any ground with a lightest-stop luminance below 0.07, so the red link is left under the rule on the three softened stops | Killed by `WallpaperInkTest` (1 of 13 failed: the lock-ink test, through the new `+ builtins` grounds; the old backgrounds alone did not catch it). |

## Runs

| Part | Command | Result |
|---|---|---|
| Server | `cargo fmt --check`, `cargo clippy --all-targets --locked` | Clean. The 13 clippy warnings are already on the base (`models.rs`, `dns_engine.rs`, `storytel.rs`, `devices.rs`); none is in this diff. |
| Server | `cargo test` | 950 passed |
| Server | `node --test jstest/` | 30 passed |
| Launcher | `./gradlew --no-daemon --max-workers=2 assembleDebug testDebugUnitTest --rerun -PwarningsAsErrors=true` | Passed |

No emulator was used. The emulator check listed in the step 9 status (the picker in nb and en, Home, and the lock over the three new grounds) is still open.

## Counts

0 High, 0 Medium, 0 Low, 2 Nits (N1, N2). Of 4 mutations, 3 were killed. The one survivor (S1) is equivalent.
