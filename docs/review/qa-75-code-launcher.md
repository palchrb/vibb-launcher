# QA 75 code review (launcher and symbols): step 1, the weather on the lock glance

Reviewed: `git diff main...lock-weather` at `4fa6bfdb`, the launcher and symbol side only: `scripts/yr-symbols.py` and
its outputs (`res/drawable/weather_*.xml`, `weather/WeatherSymbols.kt`, `values*/weather_symbols.xml`,
`test/resources/weather_symbol_keys.json`, `assets/licenses/CC-BY-4.0.txt`), `NOTICE.md`, the DTOs (`WeatherAnswer.kt`,
`WeatherNow.kt`, `LauncherUi.lockWeather`), `PolicyResponseCompatTest`, `WeatherContractTest`, the launcher copy of
`weather.json` and `.github/workflows/launcher.yml`. Against design 75 (with §12, step 3 dropped) and
`qa-75-design-launcher.md`. The server has its own review.

How it was checked:

- The script was run in a scratch copy with a fresh download from upstream (`python3 -I ... --cache`). The manifest's
  SHA-256 matched, and all 83 drawables, `WeatherSymbols.kt`, both names files, the keys JSON and the licence came out
  byte-identical to the branch, so the output is deterministic and reproducible. `--selftest` passes.
- Render check: the 83 upstream darkmode SVGs were compared with the 83 drawables, each drawable translated back to
  SVG one element at a time. Both were drawn in headless Chromium at 200 px on `#1c1c1e`, then pixel-diffed. 01d, 01n
  and 01m are identical (diff 0). The worst files (03d/n/m, 04, 15) have a mean difference of 1.4/255 and a max of 34,
  and 0.34 % of their pixels differ by more than 24, all inside the dropped blur. I looked at 01d, 01n, 03d, 09, 11,
  13, 12, 15, 06d, 22, 50 and 02m side by side. Sun, moon, gradients, drops, flakes, bolt, fog bars and the polar
  twilight horizon are right. The clouds are a little flatter (design §9 risk 5, as expected). Android's own renderer
  was not run, so the emulator check in §7.3 still stands.
- A survey of every upstream file found the following. Each of the 80 filter groups is the same: `opacity=".3"
  style="mix-blend-mode:multiply"`, one `#B6B6B6` path, and an `feFlood`/`feBlend`/`feGaussianBlur` filter. Each of the
  80 multiply paths comes directly after its base path. Every linear gradient (143) has `x1..y2`, and every radial
  gradient (42) has `cx`/`cy`/`r` with translate + uniform scale.

## Findings

### H - none

### M1 - the answer's `words` shape is pinned nowhere shared, and one test loop never runs

`launcher/app/src/test/java/com/kidslauncher/mdm/weather/WeatherContractTest.kt:80-91`, `weather.json` `answer`.

All four `hours[].words` in the contract's `answer` are `null`, because the recorded day is dry. So `WeatherAnswerWords`
(`key`, `form`, `at_ms`) is never decoded from what the server really serializes (`weather::WordsJson`, with `Form` as
`rename_all = "snake_case"` and `at_ms` skipped when `None`). The loop at :87-90 hits `continue` every time, so it
checks nothing. The `words` vectors don't cover this: their `expect` is a different shape, with `at` as a local
time.

Failure scenario: a server change renames `at_ms`, drops the `rename_all` on `Form`, or nests the words. The launcher
then decodes `form = ""` or `atMs = null`, and step 2 silently shows no words, or "Regn fra kl." with no time. Both
suites stay green.

Fix (server writer and launcher reader):
- `weather::tests::the_contract` should also produce an answer whose entries carry words, at least one `from` with
  `at_ms` and one `now`/`today`. Either lay a `set`-style rain override (as the `words` vectors do) over the recorded
  hours, or add an `answer_words` block.
- `WeatherContractTest` should then assert that at least one hour has words with `form` in `forms[key]`, and that
  `atMs != null` exactly when `form == "from"`.
- Drop `assertEquals(10, forms.size)` (:82). The copy comparison already pins the file.

### M2 - the converter's "fails rather than drawing wrong" has four holes (none bites today)

`scripts/yr-symbols.py`. Design §9 risk 5 and the script's docstring promise that anything unknown fails the run. Four
paths accept a future upstream file and draw it wrong without a word:

- **a. The bake's base** (:413). It takes the *last earlier path with the same `d`* anywhere in the group, not the
  path directly before. Suppose a later upstream draws a shape between the cloud and its multiply layer, for example a
  sun or a bolt that overlaps the cloud. The multiply is then baked into the cloud only, and the shape in between
  loses its shading. Fix: accept only `out.children[-1]` (a `Path` with the same `d`); otherwise count the layer as
  dropped, or fail.
- **b. Filter groups** (:367-370, :328-330). Any `<g filter>` is dropped whatever it holds, and the filter's
  primitives are never read. A later glow on the sun, or a filtered bolt, would vanish from the drawable. Fix: drop
  only today's shape: group opacity < 1, `mix-blend-mode:multiply`, a single path, and filter children ⊆ {`feFlood`,
  `feBlend`, `feGaussianBlur`}. Fail on anything else.
- **c. Gradient defaults** (:211-220). The script defaults `x2` to `"1"`, `cx`/`cy` to `"0.5"` and `r` to `"0.5"`.
  With `userSpaceOnUse`, SVG's defaults are 100 % / 50 % / 50 % of the viewport, which is 100 / 50 / 50 here. A file
  that leaves one out would convert to the wrong geometry. Fix: require `x2`, `y2`, `cx`, `cy` and `r`, and fail when
  one is missing. Keeping 0 as the default for `x1`/`y1` is correct.
- **d. Radius sign** (:220). `scale * r` with `matrix(-a 0 0 -a e f)`, which `uniform()` accepts, gives a negative
  `android:gradientRadius`. `RadialGradient` throws `IllegalArgumentException` ("radius must be > 0") when the
  drawable inflates on the lock screen. Fix: use `abs(scale)`, and fail when the result is ≤ 0.

Each fix is a few lines plus one `SELFTEST_FAILS` row.

### L1 - the self-test runs after the long build

`.github/workflows/launcher.yml:122-125`. A converter regression is reported only after `./gradlew build` has run in
full. The step is cheap and has no dependencies, so it belongs in the always-run `launcher-changes` job next to
`release-version.sh --selftest` (:87-89). There it also covers the script when the build job is skipped.

### L2 - the symbol test is mostly a tautology, and the real i18n gap is untested

`WeatherContractTest.kt:95-103`.
- `weather_symbol_keys.json` and `WeatherSymbols.CODES` are written by the same run from the same list, so
  `assertEquals(keys.keys.toList(), CODES)` (:98) can't fail unless someone hand-edits the files.
- `file.isFile` (:101) repeats what the compile already checks through `R.drawable.weather_*`.
- `CODES` is used only by this test.

The gap that is real: lint runs with `abortOnError = false` (`app/build.gradle.kts:186`). A `weather_symbol_*` missing
from `values-nb` would not fail the build, and TalkBack would silently speak the English name.

Suggest:
- Drop `CODES`, `weather_symbol_keys.json` and the script's `KEYS_JSON` output.
- Keep the check that the answer's codes map.
- Add one check that `values-nb/weather_symbols.xml` names every `weather_symbol_*` in `values/`.

### L3 - a copied policy test

`PolicyResponseCompatTest.kt:502-517` repeats `launcher_ui lock_wake_on_notification decodes, missing is off` (:482)
line for line with another key. One test looping over both keys, each true/false/missing with the cache round trip,
pins the same thing.

### L4 - the key reader is brittle

`scripts/yr-symbols.py:66`. `CODE_RE` needs a trailing comma, and it matches any `name: 'NNx',` line anywhere in
`index.ts`. Suppose a later upstream drops Prettier's trailing comma on the last entry (`heavysnow: '50'`). That code
is then lost without an error: the run prints 82 and a re-pin follows anyway, so the loss is easy to miss. Fix: cut
out the `weatherSymbolKeys = { ... }` block, make the comma optional, and fail if the number of entry lines in the
block differs from the number of matches.

### L5 - "drop shadows" is not quite what was removed

`NOTICE.md:43`, the drawable headers, and the docstring (:24). The dropped groups are a blurred multiply shading at
30 % over the lower part of the cloud, inside its outline. They are not shadows cast under it. CC BY's "indicate
changes" is met either way. For accuracy, say "the soft blurred cloud shading removed".

### L6 - optional: a drift check on real data

`--selftest` uses a synthetic SVG. A converter edit that would change the real outputs, for example to the bake
formula, passes CI without the drawables being regenerated. Optional: vendor one upstream file (for example
`symbols/darkmode/11.svg`, CC BY, about 3 KB) and have the self-test compare its conversion with the committed
`weather_11.xml`.

## Notes (not step-1 defects)

- The OSS licences paragraph (`legal_info_weather`, §4.3) is owed in step 2. Until then, the only attribution inside
  the APK is `assets/licenses/CC-BY-4.0.txt`, whose first line names "Yr weather symbols © 2015 by Yr/NRK". aapt2
  strips the drawables' `©` comments. Avoid a `launcher-v*` tag between this merge and step 2's, or accept the asset
  as the credit for that release.
- English `weather_symbol_02` "Fair" is yr's own string. Spoken alone by TalkBack ("Fair.") it is a little odd for a
  child. Keep it unless an override table is wanted.

## Verified OK

- **Bake maths.** Multiply over an opaque base F at strength k gives F·(1 − k + s·k). The base is opaque, so the dark
  background never enters, and darkmode on a dark lock is right. In 03d, `#DDD` × (`#B3B2B2` at 0.6) gives `#B5B5B5`,
  which is what the file has.
- **What the converter leaves.** Every gradient stop (412) and solid fill (196) comes out fully opaque (`#FF…`). There
  is no `fillAlpha`, no `<group>` and no `<clip-path>`: 82 whole-viewBox clips were dropped, and no other clips exist.
- **Path data.** All 273 arcs have separated flags; Android's PathParser can't read compact `011` flags, and none are
  present. There are no exponents, and only M/L/H/V/C/S/A/Z in both cases. Every gradient has 2-3 items, and the
  smallest radius is 27.88. `minSdk` is 34, so the framework VectorDrawable reads `aapt:attr` gradients natively.
- **Supply chain.**
  - The commit is pinned, and one manifest SHA-256 over all 87 files is checked before any SVG or JSON is parsed and
    before anything is written.
  - The ids read from the not-yet-verified `index.ts` are limited to `[0-9]{2}[dnm]?` before they are used as paths,
    so no path traversal is possible.
  - Downloads go over HTTPS with default verification.
  - The script has no exec, subprocess or eval, and imports only the standard library.
  - The script is not executable: the docs and CI run it with `-I`.
  - Orphan deletion only touches `weather_NN[dnm]?.xml`.
  - `--cache` contents are covered by the same manifest check.
- **Licensing.** `NOTICE.md` gives the title, Yr/NRK, © 2015, the repo link and commit, the CC BY 4.0 link and the
  bundled legal code, and the changes. The names and the table are credited too. MET and Kartverket are credited and
  said not to be bundled. Each drawable's header says what it was converted from.
- **DTOs.**
  - They match the server's `Answer`/`Entry`/`WordsJson`/`Day`/`DarkJson`/`Period`/`NowLine` field by field.
  - Fields the server sends as `null` (`place`, `symbol`, `temp`, `words`, `current`, `low`, `high`, `precip_mm`) are
    nullable. Fields the server skips (`at_ms`, `minutes`) default to `null`.
  - Unknown keys are ignored, and the bare statuses decode to empty lists.
  - `toString()` redacts the place, temperatures and times.
- **Contract.** The two `weather.json` copies are byte-identical, and both suites compare them (launcher :30).
- **Policy.** `launcher_ui.lock_weather` is pinned on both sides: the server's key list in `tests/device_api.rs`, and
  the launcher's true/false/missing cases with the cache round trip.
- **CI.** `launcher.yml`'s path filter has `server/testdata/weather.json` and `scripts/yr-symbols.py`. `server-ci.yml`
  has the launcher's copy. `CLAUDE.md` lists the contract and the script.
- **Size.** In the built debug APK, the 83 drawables plus 185 aapt gradient children take 352 KB uncompressed and
  146 KB compressed, about 0.6 % of 25.7 MB. Build cost is nil: they are plain XML resources, with no codegen in
  Gradle.
- **i18n.**
  - All 41 names are in both `values` and `values-nb`, with the same keys.
  - The nb names are yr's own (Klarvær, Lettskyet, Delvis skyet, Skyet, Regnbyger, Sludd, Kraftige snøbyger og
    torden, ...). They read naturally, and kids know them from yr, so I would change none.
