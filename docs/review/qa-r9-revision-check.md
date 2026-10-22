# Revision check: r9 design note vs `qa-r9-design.md`

Checked `docs/design/r9-launcher-glance-small-items.md` (working tree) against `main` (351a0219). Code references
verified: `GlancePanel.kt` listener `:172-173`, `fitNow` `:640`, log `:666`, `apply` `:692`, `PinLockActivity.kt:287`,
`CONTACTS_FADE_DP` (`ui/home/HomeEdges.kt:30`, 16), `KidSettingsActivity.kt` (in `ui/kidsettings/`) `anyRow :214`,
`device_pages.rs:944-950` (in `server/src/tests/`), `device_home.html:84` comment, `DEFAULT_QUICK_CONTROLS` and its
assertion `tests/device_api.rs:529-533`, `glanceFit`'s shape, `resumeNow`'s lock-up branch, `lock_media_time`,
`bg_music_art_large`, `glanceTimeLine`, `glanceRefitDue`, `LockGlanceGuardTest`. All exist as named.

## Per finding

- R9-1 resolved. §4 launcher code: setters `suspend`, `Dispatchers.IO`, from `lifecycleScope`; re-read after; test pins it by source.
- R9-2 resolved. Residual stated in §4 and device-checked in §5 check 4 (runbook note named, see N4).
- R9-3 resolved. 16 out of the default mask in §4 behaviour, §4 server code, §6; `device_api.rs` stays 15; §6 now says `clock_format_applied` doesn't warn; ⓘ text says grant first.
- R9-4 resolved. 16 dp fade in the layout block and tests; candidate 7 is the "more" line (see N1); scroll heights 70.6/94.6 in the table (recomputed: 457-386.4, 481-386.4).
- R9-5 resolved. §1 fix logs on a fit or available-height change; §5 check 1 reads `available … (pad …)`.
- R9-6 resolved. (a) one-row candidates don't count the "more" line, `rows` = `min(n,3)` except 7, `i < fit.rows` stays; (b) design 42 §1.6 change listed in §2 and pinned in the timer test.
- R9-7 resolved. 501.4 / 523.8, ~43 dp (523.8 - 481 = 42.8). Rechecked.
- R9-8 resolved. `Layout.getDesiredWidth` + 1 dp, no `ellipsize`, `tnum` a no-op, 10 h clip accepted.
- R9-9 resolved. Private `available(height)`, called by listener and `fitNow`, pinned by source; no pure helper.
- R9-10 resolved. No `height()` change; `0dp` + weight 1 pinned; `device_pages.rs` loop (fifth name, unchecked on a new phone - the loop asserts `Some(true)`, so it must be split out; the note says so); template comment; `anyRow`.
- R9-11 resolved. `selectableItemBackgroundBorderless`, no new drawable.
- R9-12 resolved. Linked, "not committed".
- R9-13 resolved. Header carries the `performClick` tie for design 59.

## Consistency

Fit table recomputed from the model (fixed 116, big 78.4, small 56, date 25.6, rowsTop 14, row 56, gap 8, more 24,
tile 174.8/90, +16): 410.8, 466, 458.4, 480.8, scrolls 70.6/94.6 - all correct; chosen candidates correct at 473 and 497.
§3's 174.8 = 12+48+10+16.8+2+56+12+18 correct; 208 dp controls = 48+28+56+28+48 correct. Default mask 15 vs the server test
vs "off by default" agree everywhere. The "more" line counts only in "every row" candidates and in 7; §2, §5 and the open
questions agree.

## New problems

- N1 (Low) Candidate 7 vs "the scroll `GONE` with no rows" (§2, last paragraph). The "more" line lives in the scroll, so
  "no rows" must mean n = 0 (today's `rows == 0 && more == 0`), not `fit.rows == 0`. Say so, or candidate 7 shows nothing.
- N2 (Low) Concurrent setters. `setTouchSounds` blocks for seconds on `loadSoundEffects()`; a second tap meanwhile starts a
  second IO write and the two can finish out of order, leaving the switch and the setting apart. Fix: one in-flight job
  (cancel-and-relaunch is not safe mid-binder; use a `Mutex` or ignore taps while one runs) and the re-read after the last.
- N3 (Nit) "Nothing else in the panel changes" (§2): `apply` also moves the top-margin and `GONE` logic from `lock_glance_list`
  to `lock_glance_scroll` (the layout block says so, and the test reads `rowsTop` from the scroll). Say it in `apply`'s sentence.
- N4 (Nit) §4 residual names a "setup runbook" note but no file; `docs/setup/` has no obvious home. Name it
  (`docs/setup/google-account.md` or a new line) or put it in the implementer brief.
- N5 (Nit) The fits table's 473 column is never explained (497 = 569-72 with both bars; 473 is 24 dp less); say "mockup, plus a
  24 dp cutout" or similar. The log example "pad 56/56" sums to 112, matching 473, not the §1 bars (24+48): harmless, but use
  numbers that match a real case.
- N6 (Nit) Elapsed width comes from the duration's shape; a position past a stale shorter duration (e.g. "10:00" against
  "9:59") clips with no ellipsize. Rare; accept in §3 edge cases or take the max of both shapes.

## Left for the implementer brief

Apply N1-N2 wording; name N4's file; the rest is as the note states. No blocker.
