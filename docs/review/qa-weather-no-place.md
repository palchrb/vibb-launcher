# Light code QA: `weather-no-place` (8b5a3b05)

Reviewed 2026-11-04 by reading the diff and the tree; cargo and Gradle not run (builder: cargo 1714/0, launcher 2052/0, node 309/0).

## Result

No High, no Medium.

## Checked, nothing found

- **No outside host but MET on the weather path.** `grep -i kartverket|stedsnavn|geonorge` over `server/src`,
  `server/testdata` (except `weather/README.md`'s history line), `launcher/app/src`, `server/deploy` and Cargo: no code
  hit. The only URL base left is `weather_cache::MET_BASE`; `met_request` is the only request builder, used by the
  forecast and nowcast fills. The remaining hits are docs (NOTICE, DEPLOY, design 75, review/idea notes, PLAN) and
  migrations 0122/0126 comments.
- **Compatibility.** Old launcher / new server: `place: String? = null` defaults a missing key; the only effect is the
  old `placeLater` one extra pull ~2 min after the first ok answer per process (its `placeRetried` flag never resets
  without a place), harmless against the 20/h ask limit. New launcher / old server: `ServerJson` has
  `ignoreUnknownKeys = true`, and `WeatherContractTest` adds `place` to the shared `answer` and checks it decodes equal.
  Stored prefs: `WeatherStore` decodes the kept answer with `ServerJson` inside `runCatching`, so a stored answer that
  still has `place` decodes and is re-encoded without it.
- **Migration 0126** is `DROP TABLE IF EXISTS weather_places`: safe on a database that never had it and on one with it
  (no foreign keys, no index/trigger/view on it; no other reference to `weather_places` left in `server/src`). No other
  local branch or worktree has a 0126 file; `migrations_from_73_in_order` has the row.
- **Erase/prune/Location Off.** `weather_cache::erase` still bumps the generation, deletes `weather_cells` and clears
  the nowcast map; `retention::prune` keeps the `weather_cells` statement (48 h or the longest location retention).
  Only the place statements went. Sole erase caller is still `locate.rs` `update_location_policy` (change to Off).
- **Contract.** `server/testdata/weather.json` and `launcher/app/src/test/resources/weather.json` are byte-identical
  (`cmp`), no `"place"` in either, both in the same commit.
- **i18n/strings.** `weather_foot` is gone from both catalogs with no remaining reference; `weather_updated`,
  `weather_low_high` kept and used. PWA footer en/nb names MET and Yr/NRK only; `donottranslate.xml` legal text MET
  only. Removed constants (`CARD_PADDING_DP`, `DARK_GAP_DP`, `WEATHER_PLACE_RETRY_MS`) have no other users;
  `AtomicBoolean`/`CoroutineScope` imports in `WeatherPull.kt` are still used.
- **Tests removed** are only the place/Kartverket ones (`kartverkets_edges`, `a_place_name_is_cleaned_and_cut`,
  `place_stored`, `lookups_done`); the erase-in-flight, retention and shared-cell tests were kept and adjusted.

## Low

- **L1 - design 75 reads half-superseded.** D13 and §2.2 carry the mark and §13 is added, but other places still
  describe the place as built without a mark: the §0 wireframe (line 22, "Nydalen · 2° / 7°"), §3.1's `weather_places`
  DDL (~286), §3.3 (353, 367), §3.4 `place(...)` and the `place` handler step (373, 381), the §3.5 JSON example
  (398, 422), §5.2 foot (547, 629-630), logs ("place yes", 600), TalkBack (683), the test list (779-782) and the
  licence paragraph (484-488, Kartverket credit). A one-line note under the title ("everything about the place name
  is superseded by §13") would stop a later reader from rebuilding it.
- **L2 - pre-migration/pre-restore backup copies keep the old `weather_places` rows.** Migration 0126's comment says
  "the upgrade erases them", and DEPLOY says backups keep the cells "as they keep the fixes"; the automatic
  pre-migration copy made before 0126 still has the place rows until it rotates out. Worth one clause in DEPLOY.
- **L3 - PLAN.md not touched in the commit.** The queue line still reads "APPROVED ... NOT STARTED"; per the working
  rule it must be marked built/in QA now and done in the merge commit.
