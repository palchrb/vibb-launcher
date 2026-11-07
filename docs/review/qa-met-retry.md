# QA: MET quick retry and the cut-forecast reader (`met-retry`, 4acf065c)

Reviewer: Opus, 2026-11-04. Scope: `git diff main...origin/met-retry` (server only). `cargo test weather` on the branch: 35 passed.
Spec: PLAN.md "MET: a quick retry after a 503; the "unreadable" cell".

Result: **0 H, 2 M, 3 L, 3 nit**. The retry ladder, the Retry-After parsing and the lock structure are sound; the defects are in the
cut-body handling and in two edges of the retry.

## What was checked and is right

- **Retry-After parsing** (`weather_cache.rs:152`). Seconds or HTTP-date against MET's `Date` (our clock only when `Date` is missing);
  negative and unparseable values are ignored (ladder); over 60 s ends the quick tries (`quick_wait`); 0 becomes 1 s. A date in the
  past is negative and so ignored. `Retry-After` is also used by `failed()` as a floor on a 5xx.
- **Ladder.** `tried` is counted after each request: waits 5 s then 15 s, `tried >= 3` stops, so at most 3 requests. Only 500/502/503/504;
  429 and 403 never enter `quick_wait` and keep their old branches and codes. A network error or timeout (`Err`) is not retried.
- **Floor.** `failed()` does `max(5-60 min, retry)`, so Retry-After 600 gives 10 min and 120 gives 5 min. See L-1 for the top end.
- **Locking.** Task A stamps `next_fetch_at = retry_at` in SQL, drops the lock, sleeps, re-locks, re-reads and continues only while the
  stamp is still its own. Walked through:
  - Two tasks on one cell: an ask during the wait sees a row that is not due and spawns nothing. A task already queued on the lock
    re-reads the row under the lock, finds it not due and returns. After the retry's own request, other asks again see a future
    `next_fetch_at` (the result is written or re-stamped before the lock is dropped), so no double fetch.
  - Lost wakeup: there is no signal to lose; the sleeper wakes by timer and the stamp is the only state.
  - Phone wait: unchanged, `timeout(forecast_wait, task)` (6 s); the task keeps running detached.
  - Erase mid-wait: `Ok(None)` or a re-inserted row (new row, `next_fetch_at` NULL) both return; the final `UPDATE ... WHERE id = row.id`
    on an erased id touches nothing. The in-memory `row` (with the hours) is dropped on return.
  - Shutdown: sleepers are plain tasks and are dropped with the runtime; the DB keeps a future stamp, so the row is simply due again
    after a restart.
  - Number of sleepers: at most one per cell (the stamp), each asleep at most 2 x 60 s; request starts are still serialised by the lock
    and spaced by `Gated` (200 ms), so there is no storm across cells. During a real MET outage a cell now costs 3 requests per backoff
    cycle instead of 1.
- **User-Agent / terms.** `met_request` is unchanged for every attempt; `If-Modified-Since` is re-sent from the freshly read row; no
  request is made before an `Expires` (a retry only follows a 5xx, which carries no cached forecast).
- **Fixture** `met_complete_mountain_view.json`: public MET data, coordinates `[-122.08,37.42,8]`, `updated_at` 2026-11-04; no
  59.80/10.48 or any other identifying value anywhere in the tree (grepped at HEAD, excluding PLAN.md and docs/review). Fine.
- **Logs.** Every new line names `cell = row.id` (and `tried` or `code`); no coordinates, no body.
- **Cut reader, garbage.** `IgnoredAny` must fail with an EOF error (`is_eof`), so everything before the cut is syntactically valid JSON;
  a whole body, an HTML page, an empty body and "wrong before the end" are `Unreadable`. Deep nesting stops at serde's depth limit (not
  EOF, so `Unreadable`). Only entries that parse as complete objects count, and `hours_of` is the same filter the whole-body reader uses.
  Our own size cap is excluded by `!response.truncated`, and a body-read error in `music_net` is a `Network` error, so a short 200 really
  is MET's.
- **merge_cut.** New hours replace the overlap, old hours before the first and after the last are kept (24 h floor unchanged), sorted and
  deduplicated. A cut body cannot remove stored hours; it can only replace the overlap with data from a newer run.

## Findings

### M-1 A cut body is stored with its `Last-Modified`; a later 304 clears `cut` and keeps the cut hours as whole
`weather_cache.rs:398` (`last_modified: response.last_modified.clone()` also for `note = Some("cut")`) and `:446-449` (the 304 branch sets
`last_error = NULL`).
Scenario: MET sends the cut 200 with `Last-Modified: L`. We store L. After `Expires` we send `If-Modified-Since: L`; the forecast product
at the backend is unchanged (the cut was a cache artefact, as in E9), so MET answers 304. `NotModified` stamps `checked_at`, resets
`failures` and writes `last_error = NULL`: the cell now looks healthy with its ~20 hours, and the next real refresh is whenever the model
run's `Last-Modified` moves (typically an hour or more). Our conditional request also stops us from ever getting a clean copy that MET's
edge might otherwise have refetched unconditionally.
Fix: for a cut body store `last_modified = NULL` (so the next fetch is unconditional), and let the 304 branch keep `last_error = 'cut'` if
the row has it (or simply never send IMS when `last_error = 'cut'`). Add a test: cut, advance past `Expires`, canned 304 must not happen /
must not clear `cut`.

### M-2 A cut forecast is shown with no marker that it ends early; windows that run past the last hour are judged on part of their hours
`weather.rs:170` (the reader), consumers `span()` (`weather.rs:571`), `words()`/`judge()` (`:388`), `day()` (`:768`).
`parse_cut_forecast` accepts "at least one whole hour" and nothing downstream knows the series ended early. On a first fetch (no old
hours) the only data is the cut. E9's cut kept 15-30 hours (the test says so). Scenario: cell first asked at 21:00 local, cut after
tomorrow 08:00. Tomorrow's 07-18 window holds 1-2 hours, both dry, so `words` says "dry/clear tomorrow" and `periods` give tomorrow's
afternoon `precip_mm 0.0` (not null: `span()` only returns nulls for a span with no hours at all), and `day().high` is the high of the
first hours only. Right data, wrong claim. The front edge was handled on purpose (code QA M1: "the period now running has lost its start"),
the back edge never had to be. A whole body always reaches 9 days, so a guard at the back edge changes nothing for it.
Fix (pick one): (a) in `merge_cut`'s caller, when the merged series ends before the end of the local day after tomorrow's window, treat the
spans/windows that extend past the last stored hour as having no data (a `horizon` in `words`/`periods`/`day`); or (b) cheaper: drop the
trailing partial local day from a cut `new` only when the old hours do not cover it (the cell's zone is not known in the cache, so (a) is
the honest one); or (c) accept as is and document that "tomorrow" may be understated after `cut`. If (a) is chosen, `weather.json` vectors
stay valid (their series are long) but run the launcher contract test.

### L-1 A 5xx `Retry-After` can freeze a cell for 24 h
`weather_cache.rs:165-166` (`min(86_400)`), used by `failed()` for status_5xx at `:509-510`. A 503 with `Retry-After: 86400` (or a date a
day ahead) means no refresh and old hours for 24 h, longer than the 60 min top of the ladder and than a 429 (2 h doubling, though its
Retry-After floor has the same 24 h). Safe against MET's terms, but one bad header costs a day of stale forecasts per cell and the 24 h
merge floor then drops the older hours. Suggest clamping a 5xx's floor to 1-2 h (keep 24 h only if MET's own 429 docs justify it).

### L-2 Every due ask now blocks for up to FORECAST_WAIT when MET is failing, and a cold cell has a 0.9 s margin
`weather_cache.rs:337-345` with `FORECAST_WAIT = 6 s`. Before, a due ask on a cell with stored hours got MET's fast 503 in ~50 ms and
answered with the old hours. Now it waits for the 5 s sleep and the second request; with MET really down the phone's card load takes
6 s every time a cell is due (every 5-60 min per cell, per ask that lands on it first). On a cold new cell the happy path (503, 5 s,
200) needs the second request under ~0.9 s, which MET's ~1 s cold fill (the user's curl) does not guarantee; then that phone gets
`unavailable` and the next ask has the data (acceptable, but then the first ladder step buys little for the asking phone).
Not a correctness bug. Consider answering a phone that already has hours right away (only wait when `hours_json` is empty), or keep as is
and note it.

### L-3 `retry_at` is truncated to whole seconds; the stamp, not the row id, identifies the sleeper
`weather_cache.rs:120,327`. `stamp()` drops the sub-second part of `now + wait`, so the row reads as due up to 1 s before MET's
`Retry-After` has elapsed (for `Retry-After: 1` essentially at once). An ask landing in that gap spawns a fetch that queues for the lock
the sleeper is about to take; whichever runs first fetches and the other sees a changed row and returns, so no double fetch, but one
request can go out up to 1 s early. Round up (`now + wait + 1 s` for the stamp, or `stamp` of the ceil). The sleeper also compares only
`next_fetch_at` text; compare `row.id` as well (an erase, a new row and another task's retry stamp landing in the same second would
otherwise be mistaken for ours; very unlikely).

### L-4 Test gaps for the new control flow
The two integration tests cover 3 tries then backoff, 503+503+200, Retry-After 600, and a synthetic cut. Not covered: an erase during the
wait (`Ok(_) => return`), a second ask during the wait making no request (the claim the whole structure rests on), the IMS header on the
retry request, a cut over a row that already has hours (`merge_cut` only in the unit test), and M-1. The cut integration test also does
not assert which hours were stored. Add the first two (a `quick_second` of 50 ms and a short `forecast_wait` make them deterministic) and
the hours assertion; skip the rest.

### L-5 The Mountain View `unreadable` is explained only by assumption
The spec asked to find why the Mountain View cell answered `unreadable`. The new fixture reads whole, and E9's cut body was not recorded
(the test truncates the Oslo body to the same share). So the cause there is a presumed cut. Cheap help for next time: log the response's
length (and `Content-Length`/status, never the body) on the `unreadable` path (`weather_cache.rs:481-490`, currently silent apart from the
generic failed line), so the next occurrence can be told from a shape change.

### nit
- `weather.rs:186` finds `"properties"` by byte search anywhere in the prefix (could match inside a string or a nested object). Harmless for
  MET's actual head, but a structural check (key at depth 1) would be exact.
- `weather_cache.rs:152`: `Retry-After: 99999999999999999999` (overflows i64) falls to the ladder, although "over 60 s ends the quick
  tries" would be the consistent reading; the unit test pins the ladder for it.
- Fixture README: "recorded 21:18 UTC" next to `updated_at` 19:18Z in the body; correct (model run vs fetch) but worth one word.

## Fix round (2026-11-04, same branch)

| Finding | Fix |
|---|---|
| M-1 | A cut body is stored with `last_modified = NULL` (`weather_cache.rs`, `Fetched::Hours`), so the next ask is unconditional; the 304 branch keeps `last_error = 'cut'` (`CASE WHEN last_error = 'cut'`). Test `a_cut_body_over_stored_hours_keeps_them_and_sends_no_validator` (also: the old hours past a cut stay, byte for byte). |
| M-2 | `weather::covers(hours, tz, end)`: the last stored hour must end at or after the window's end. `span` (periods, tonight's span), `words` (today's window, tonight, tomorrow), `tonight` and `day`'s low and high return unknown (null period, no words, no card) otherwise. The launcher contract (`weather.json`) is unchanged: every vector and the launcher copy still pass, so no launcher run was needed. Test `what_runs_past_the_last_hour_is_unknown`. |
| L-1 | `failed()`: a 5xx's `Retry-After` floor is at most 2 h (unit test: 86400 gives 120 min). |
| L-2 | `forecast()` waits on a one-shot the fetch task fires when it goes to sleep for a quick retry and the row has stored hours (or ends); a cell without hours waits up to `FORECAST_WAIT` as before. Test `a_phone_with_hours_does_not_wait_for_the_quick_retry` (answers in under 300 ms, a second ask makes no request, the retry lands in its task). |
| L-3 | `stamp_up` rounds `retry_at` up to the whole second; the sleeper also compares the row id. |
| L-4 | Tests: erase mid-wait (`an_erase_in_the_quick_retrys_wait_ends_it`), a second ask in the wait, `If-Modified-Since` on the retry, a cut over stored hours, M-1. The tests' `quick_second` is 10 ms (was 1 ms) so a 60 s `Retry-After` is 600 ms, longer than the tests' 500 ms phone wait. |
| L-5 | The `unreadable` path logs `bytes`, `kind` (`json`, `json_prefix`, `not_json`) and `truncated`, by row id only; the log lint test still holds. |
| nit | An all-digit `Retry-After` that overflows i64 counts as very long (ends the quick tries). `"properties"` and `"timeseries"` are found structurally (`key_at`: right depth, strings skipped; test heads with the names inside a string or a deeper object). README wording fixed. |
