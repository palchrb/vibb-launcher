# QA 75 code review, step 2: the launcher's lock-screen weather

Reviewed: branch `lock-weather`, commits c255176f and e7c8b418 (`git diff c9cdba28..e7c8b418`). I read them against
`docs/design/75-lock-weather.md` (with §12), `docs/review/qa-75-design-launcher.md` (its revision check and the new L1-L3),
`launcher/CLAUDE.md` and `docs/notes/launcher/lock-and-glance.md`. Step 1 (server, contract, symbols) is touched only
where step 2 reads it. I didn't run Gradle or an emulator (CI run 41682818099 and the builder's run are green).

**Verdict: no High, one Medium, nine Lows.** The kid-safety and kiosk side is clean. So are the switch-off gating and
the pull limits. The Medium is the card's nowcast: it is rate-limited only after a successful answer. The Lows are a
rare race on the kept answer, TalkBack's click labels, a few dp of row movement beside the date, and some small gaps.

## Medium

**M1. The nowcast is asked at every card open while the last ask failed or is still running.**
`lock/WeatherLockPanel.kt:398-409`, `weather/WeatherStore.kt:109-118`.

- `freshNow()` returns `now?.takeIf { ... }`, so a `null` answer (a timeout, no network, a 429, a 5xx) is never reused.
- `nowAnswered(null)` also overwrites a good answer kept from a minute earlier.
- `nowAtElapsed` is stamped only when a call completes, so a second open during an ask in flight asks again.
- Each ask builds a new OkHttp client (`createMdmApi`): a new TCP and TLS handshake on the radio.

Design D9 and §5.4 say "at most every 2 min".

*Scenario:* the phone is offline, or the server returns 429 once its 60 asks an hour are used up. A kid flips the card
open and shut 20 times in a minute: about 10 `/weather/now` calls, each its own handshake and up to 3 s. Under the 429
every later open asks again and hits it again. The call isn't cancelled when the card folds or the screen goes off. Its
3 s bound and the generation check keep that harmless on screen, but the radio work is wasted.

*Fix:*
- Stamp the ask when it starts (`nowAskedAtElapsed`) and keep an in-flight flag in `WeatherStore`.
- At an open: with an ask in flight, or with the last ask (any outcome) less than `WEATHER_NOW_REUSE_MS` old, show what
  is kept (possibly nothing) and don't ask.
- Never replace a good kept answer with a failed one.
- Make the rule a pure `weatherNowAsk(askedAtElapsed, inFlight, nowElapsed)` and pin it in `WeatherModelTest`.
- Optionally keep the `Job` and cancel it in `fold`/`stop`.

## Low

**L1. A pull in flight can put back the answer a policy step just cleared, and the pull that step asked for is
dropped.** `weather/WeatherPull.kt:55,77`, `weather/WeatherStore.kt:97-106,121-129`.

- `afterSync` calls `pull()`. That returns `false` when the screen-on pull holds `inFlight`, and nothing re-runs it.
- `pulled()` reads `answer`, computes `kept` and writes it back. A `clear()` on the sync thread between those steps is
  undone.

*Scenario:*
1. The parent sets Location from Regularly to Off.
2. The nudged sync's `policyChanged` clears the answer (`CLEAR_AND_PULL`).
3. A screen-on pull sent a moment earlier lands with the old `ok` answer ("Nydalen").
4. It is kept, `pulledAtMs` is set, and the lock shows the old place for up to 55 min.

That is the "Off never leaves Nydalen on the lock" guarantee (M4 of the design QA). The window is small: a pull's round
trip, usually milliseconds, at most ~6.5 s.

*Fix:*
- Give the store a generation, bumped by `clear()`; `pull()` captures it before the GET and `pulled()` drops a result
  from an older generation.
- When `pull()` finds one in flight, set a "pull again" flag and run once more when it ends.
- Do `pulled()`'s read, compute and write under one lock (or on the main thread).

**L2. TalkBack's click labels read as "Dobbelttrykk for å Trykk for å se dagen".** `lock/WeatherLockPanel.kt:523`;
`values-nb/strings.xml:528-529`, `values/strings.xml:603-604`. A custom `ACTION_CLICK` label is spoken after TalkBack's
own "Dobbelttrykk for å" / "Double-tap to". Today that gives "…for å Trykk for å se dagen" and "…for å Lukk dagen" in
nb, and "Double-tap to Tap to see the day" in en.

*Fix:* make the labels infinitives: "se dagen" / "lukke dagen" and "see the day" / "close the day".

**L3. Beside the date, the mark moves the rows and its degrees sit low (no-profile phones at 14 sp).**
`res/layout/view_lock_weather_mark.xml:16`, the date row in `view_lock_glance.xml`.

- The mark is a `center_vertical` LinearLayout. `LinearLayout.getBaseline()` ignores a centred child's offset (horizontal
  layouts never set `mBaselineChildTop`), so the date row aligns the mark as if its text were at its top.
- The 22 dp symbol is taller than the 19.1 dp text line at 14 sp, so the degrees are drawn ~1.5 dp under the date's
  baseline.
- The row grows from ~19 to 22 dp whenever the beside mark shows.

*Scenario:* on the 360 dp no-profile phone (§5.3's BESIDE_WORDS), every appearance or disappearance of the line (the first
pull, the 3 h hide, the switch) moves the alarm line, "Neste" and the rows by ~3 dp. The L2 fix (`slotHeld`) covers the
own line only. With a profile (16 sp, a 22.4 dp symbol over a 21.8 dp line) it is negligible.

*Fix:* while the switch is on, give the date row a `minimumHeight` of the mark's height, so the row doesn't depend on the
mark's visibility. Then centre the text on the symbol honestly: give the text a `minHeight` equal to the symbol size with
`gravity="center_vertical"`, and override the mark's baseline, or check the offset on the emulator (§6x.6 is the place).

**L4. The held slot stays empty when the screen-on pull fails.** `lock/WeatherLockPanel.kt:151,208`. `slotHeld` is set
at a lock-up whenever an `ok` answer is kept and a pull is due or running. Nothing releases it when that pull fails.

*Scenario:* the phone is offline for a day with an `ok` answer kept. At every lock-up more than 5 min after the last try,
the Jelly Star reserves an empty ~22 dp line under the date. "Neste" yields to it (`glanceNextFits`), and the room comes
and goes from one lock-up to the next.

*Fix:* hold the slot only while the kept answer is young enough that the line was showing recently (e.g. `checked_at`
within `WEATHER_HIDE_AFTER_MS` + 1 h).

**L5. "nb catalog" is guessed from `locales[0]`.** `lock/WeatherLockPanel.kt:277`. Only `values-nb` exists, so a phone in
`nn` gets the English strings with the nb hour ("Rain from 14"). A locale list like `[de-DE, nb-NO]` resolves
`values-nb` but gives "Regn fra kl. 14:00". Design §5.2 / launcher QA L11 ask for the key to be the strings' catalog.

*Fix:* add `<bool name="weather_catalog_nb">` (false in `values`, true in `values-nb`) and read it.

**L6. The card cuts text at large fonts.** `res/layout/view_lock_weather_card.xml:216` and the three `_part` views
(`:65` …).

- The foot "Nydalen · 2° / 7°" ellipsizes at its end, so a long place name ("Sandefjord lufthavn Torp", up to 40
  characters) at font 1.3 on 320 dp hides low/high, the information.
- `maxLines="1"` with no ellipsize on "Ettermiddag" in an ~88 dp column at font 2.0 breaks the word mid-way
  ("Ettermid").

*Fix:*
- Put low/high in its own non-ellipsizing view and let only the place ellipsize.
- Let the part labels autosize (`autoSizeTextType="uniform"` with a floor) or take two lines.

**L7. The chevron doesn't turn on open.** `lock/WeatherLockPanel.kt:256` and `:393-394`. `openCard()` sets
`open = true`, then `repick()` binds the mark with `rotation = 180f`, then `turnChevron()` animates from 180 to 180. The
open flips at once; only the fold has the 150 ms turn.

*Fix:* in `bindMark`, set the rotation only when no animation is running (or not at all), or call `turnChevron()` before
the rebind.

**L8. The date can flip from long to short just after a process start.** `weather/WeatherStore.kt:59-78`,
`lock/WeatherLockPanel.kt:185`.

- `policy` is read from the cache on an IO coroutine. A lock that binds first sees `policyOn == false` and shows
  "Mandag 3. november", then turns to "Man. 3. nov." when the read lands.
- Before the first unlock (CE prefs throw) the read never happens. The long date then stays until the first sync after
  boot, and the date flips then.

That is what D14 wanted to avoid, at boot only.

*Fix:* run `loadAtStart`'s policy part again once the user is unlocked. Also derive `shortDate` from the cached policy
synchronously at the panel's first bind (the glance's `currentPolicyDecision()` already reads it).

**L9. Tests: what §7.3 asked for and isn't there, and one test that pins nothing.**

- Missing: `LockGlanceLayoutTest`'s own-line slot in the 473/497 dp cases with "Neste" yielding (launcher QA L13).
- Missing: "the open card's height scrolls, never clips".
- Missing: a pure test of the nowcast's ask/reuse rule, which would have caught M1.
- Pointless: `WeatherModelTest.kt:135` (`assertTrue(WEATHER_NOW_TIMEOUT_MS == 3_000L && …)`) re-states constants; drop it.

The rest is lean and pins real risks: the entry pick at the edges, the 3 h hide, never pulling while off, the nudge gap,
the policy steps, the fold events, the 1 s window, U+2212, the budgets, the hit rectangle, the contract's pairs in both
catalogs, and the guard.

### Nits (no fix needed to merge)

- `weather_degrees` is defined in both catalogs and never used (`weatherDegrees()` builds the string).
- `ClockFormat.is24(context)` duplicates the public `systemIs24(context)`.
- `afterSync` builds its own client instead of reusing the sync's `api` (one more handshake an hour).
- Opening the card on a page without a tile changes the rows' margin from 22 to 14 dp. `fitNext` takes the card's height
  out but not that 8 dp, so "Neste" can appear at the open, near its threshold.

## Verified OK

- **Kiosk and kid-safety**: the mark and the card only toggle `open`. There is no intent, `PendingIntent`, URL or
  `startActivity` in `weather/` or the panel (`WeatherGuardTest`). Nothing leaves lock task. The licence links are in
  `LegalInfoActivity` (settings), as Nunito's are, not on the lock. The mark and the card aren't in `swipeControls`
  (`LockMediaGuardTest`): a swipe up from them still asks for the unlock.
- **Switch off, or no key: no pulls.**
  - `weatherPullDue` returns false while off, nudged too.
  - `afterSync` pulls at once only on `CLEAR_AND_PULL`, which needs the switch on.
  - A missing `launcher_ui.lock_weather` reads off.
  - A `Fallback` policy reads off, which clears; the next real policy pulls.
- **Pull limits.**
  - One in flight (`AtomicBoolean`); a 15 s call timeout plus `withTimeoutOrNull`.
  - Everything but cancellation is caught.
  - The pull runs inside `syncMutex`, before `commitPendingSelfUpdateIfDue`.
  - No new alarm or wake-up, and no loop: store → repick → bind is keyed on the model; layout listeners never re-pick
    except `layoutNow`'s, which runs on a height change.
- **Rate limits.** At screen-on, 55 min after a settled answer (`ok`/`off`/`no_position`) and 5 min after any try. A
  nudged sync pulls at most every 3 min (≤ 20/h, the server's per-phone cap: a nudge storm can only get a 429, kept as a
  failure).
- **Age.**
  - `checked_at` older than 3 h hides the line. The entry is the one with `at_ms ≤ now < at_ms + 1 h`; past the last
    one, nothing.
  - The minute tick folds an open card when its line goes.
  - A lock-up or resume re-picks before the first frame.
  - `unavailable` and failures keep the old answer (its age still hides it). Nothing kept means nothing shown.
  - `off`, `no_position`, the switch off and a location-mode change clear it; a restart with the switch gone off clears
    the prefs.
- **Words.**
  - Every `forms` pair and `now_keys` key maps through the `when` table to both catalogs with the right placeholder
    (`WeatherContractTest`).
  - Unknown keys, forms, symbols, parts and dark kinds are left out one by one; a `from` without `at_ms` has no words.
  - An `_in` key without positive minutes is no line.
- **Hours and zone.** The `from` hour, "Mørkt kl." and "Oppdatert kl." follow the phone's 12/24-hour setting
  (`ClockFormat`), with instants formatted in the system zone. A `:30` start shows its minutes.
- **Placement.**
  - Decided at a lock-up, a refit, a width change and the first bind (`layoutNow` → `resume`), from the widest of the
    entries' words and the catalog's "Regn fra kl. 14".
  - The mark stays hidden until its place is known. Between those moments `weatherWordsFit` drops words; nothing moves
    or is cut (`setHorizontallyScrolling`).
  - Two instances, at most one visible, nothing reparented. §5.3's table is pinned.
- **A missing symbol never crashes**: an unknown code is `null`, so the mark's image is `GONE` and the card's
  `setImageDrawable(null)`.
- **Threading and teardown.**
  - Prefs are read on IO and written with `apply()` from the pull's and the sync's threads. Fields are `@Volatile`.
  - Listeners are posted to the main thread and removed in `stop()`.
  - The nowcast runs on `lifecycleScope`.
  - The clock's watcher and the layout listeners are on the activity's own views, so nothing leaks past the activity.
- **Privacy.** The place shows only in the open card (design Risk 3, the parent's switch). Logs carry status, counts,
  "place yes/no", the place enum and dp. The DTOs' `toString` is redacted (`WeatherGuardTest`). There is no
  `android.location`.
- **TalkBack.**
  - One node for the mark (a button with expand/collapse and a state) and one for the card. Children are not important
    for accessibility.
  - Whole sentences; plurals on `abs(temp)`; U+2212 read as "minus". The nowcast's sentence is first once it shows.
  - Only the click label's wording is wrong (L2).
- **Hit area.** 48 dp tall, the top capped at the clock's baseline with the rest below, 8 dp wider at each side
  (`weatherHitRect`, pinned). Set only on the visible, clickable mark; cleared otherwise.
- **The look.**
  - A 14 dp dim chevron and a text-only nowcast (no spinner, no bars).
  - "Mørkt kl." in the ink; the current column on white at 6 %.
  - `kid_rain`, `kid_ink` and `kid_core_dim` are ≥ 4.5:1 on both grounds (`KidPaletteTest`). No colour field, and the
    only motion is the chevron's turn.
  - The card sits 14 dp under the face's lines, before the rule card. `placeList` counts it.
- **Small parts.** The capability `lock_weather_v1` is in `STATUS_CAPABILITIES`. The licence paragraph and
  `CC-BY-4.0.txt` are in `LegalInfoActivity`. The time-rule screen's `LockClockFace` is unchanged (no `dateRow`, no
  `shortDate`). The profile's date row is baselined as the date (`box.baseline`, the date's for a horizontal row).

## nb strings (read as a ten-year-old would)

- **Change**:
  - `weather_card_hint` "Trykk for å se dagen" → **"se dagen"**.
  - `weather_card_close` "Lukk dagen" → **"lukke dagen"**.

  TalkBack says "Dobbelttrykk for å …" first (L2).
- **Suggest**:
  - `weather_expanded` / `weather_collapsed` "Utvidet" / "Lukket" → **"Åpen" / "Lukket"**: one picture, open and shut,
    for a card a kid folds out. "Utvidet" is Android's settings word.
- **Ask the user, don't change on my say**:
  - `weather_now_dry_in` "Opphold om ca. 25 min" is the approved mockup's wording, but "opphold" in its weather sense is
    yr's jargon, and a ten-year-old reads it as "a stay". "Tørt om ca. 25 min" or "Regnet stopper om ca. 25 min" would
    land, but the key also covers snow, so "Tørt" is the one that fits both.
- Everything else reads naturally:
  - "Snør nå", "Kanskje litt regn", "Mye vind", "Lyst hele natta", "Mørkt hele dagen";
  - "Regn de neste 90 minuttene", "Oppdatert kl.";
  - "Laveste/Høyeste N grader", "Været nå: Delvis skyet.".
