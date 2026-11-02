# Code QA: `calendar-in-rules` (0ad8f174), "Kalender usable during a time rule"

Read-only review of `git diff main...calendar-in-rules` (32 files, one commit). Nothing was built. The findings come
from reading the code, with call paths traced through PinLockActivity, PinLockRuntime, GlanceOpener, LockActivity,
TimeRulesRuntime, MdmSyncWorker and AppEnforcer, and through server `schedules`, `bedtime`, `time_rules` and
`startup`.

**Verdict: OK to merge after M1.** The rest can go in the same small fix round or be left. **Counts: High 0, Medium 1,
Low 4, Nit 5.**

## What was checked and holds

- **`calendarUsable`.** It is `active.all { calendarAllowed }` over the active rules that no lift covers, so several
  rules give the intersection (a phone's bedtime with an everyone rule too). The used-up budget alone gives false.
  Rule + budget keeps the rule's answer, which matches "rule exemptions are not cut by the budget". The offline
  override and the pause both give `UNLOCKED` (`overrideActive()` covers `RestrictionsPause`). A policy without the
  key decodes as false (`PolicyResponseCompatTest`, `TimeRulesTest`). `key()` only gains `|calendar` when it is on,
  so keys stored before this change stay equal and no apply is triggered at the update.
- **`redirectIfLocked`.** It checks the pref first, then a fresh snapshot, and an error counts as "no". A divergence
  can only let Kalender through when the fresh snapshot is unlocked or allows Kalender.
- **`LockWatch`.** It is needed: a second rule of the same reason starting moves only `lock_key`, and the runtime's
  `LockActivity.start` fires only on a change of reason (`TimeRulesRuntime.recheck`, `changedTo`). It is registered
  between resume and pause, guarded by `on`, and the activity holds it, so nothing leaks. SharedPreferences only
  notifies a value that really changed. The parent switching Kalender off under a running rule moves the key, so the
  page sends the kid out.
- **The one-shot mark.** It is set only in `afterUnlock`, inside the `lockReason != NONE && !hasLiveCall` branch, and
  taken on the first resume. It is never kept: taken while LOCKED, expired, or with a hand-over waiting, it is simply
  dropped. Back, Home and redirects can't reopen. The glance hand-over is set synchronously in `PinLockActivity.go()`
  (`GlanceOpener.open` → `run` → `handedOver = …`) in the same main-thread message as the `unlocked` dispatch. So
  `handOverPending()` is already true when LockActivity resumes, and there is no race. The rows come from `render()`
  in `onStart`/`onNewIntent`, which runs before `onResume`, and they hold only usable things. Kiosk on: the app's
  launch goes the same way as a tap. Android mode: `start` waits for the keyguard (`afterAndroidUnlock`). If that
  takes more than 10 s the mark is stale and nothing opens.
- **`android-r2`.** It merges cleanly with this branch (`git merge-tree`, no conflicts), and the logic doesn't
  overlap. R2 touches the lock task and the status-bar heals, not `afterUnlock` or LockActivity.
- **Ways out of Kalender's pages.** There are no links: `activity_event.xml` has `autoLink="none"`,
  `linksClickable="false"`, `textIsSelectable="false"`, and no other click target leaves the package. The date and
  time pickers are in-process dialogs. The three activities are `exported="false"`. Only Home's tile,
  `GlanceOpener` and LockActivity start them. Exception: the text fields of "Ny avtale", see M1.
- **API change in both directories in one commit.** Server `PolicyTimeRule.calendar_allowed` with
  `policy_json_keys_snapshot`, which now pins a rule's keys. Launcher `TimeRule.calendarAllowed` with
  `PolicyResponseCompatTest`, including the older-server case.
- **Server: every write path carries `calendar_allowed`.** Create, update, bedtime upsert, `split_rule` and
  `copy_as_bedtime` all write it. Every `TimeRuleRow` read is `SELECT *`. `insert_converted` (legacy) correctly takes
  the default 0.
- **Server: Exceptions list logic.** It is correct:
  - A phone's rule and its bedtime offer allowlist ∩ reported.
  - An everyone rule offers the union.
  - NULL allowlist offers what the phone reported. An allowlist that doesn't read offers nothing and logs it.
  - Saved apps outside the list stay ticked, with a note, and are deduplicated.
  - The calendar row is shown when Kalender is on for a phone in scope or the rule has it, so a hidden row never
    loses a stored "on".
  - The refusal paths re-render from the posted fields.
- **Server: PRG anchors.** Unchanged; there is no new form or handler.
- **Tests.** Good coverage on both sides: `the_exceptions_offer_the_vibb_calendar_and_the_allowed_apps` (scopes,
  notes, nb, save/serve, the hidden row, bedtime), `TimeRulesTest`, `TimeLockModelTest` (unlockOpenRow, freshness,
  wiring), `LockGlanceTest`, `DayTaskTest`.

## Medium

### M1: The "Ny avtale" text fields open a way out of the lock under a time rule

**Where:** `launcher/app/src/main/res/layout/activity_event_edit.xml:29` and `:93`, and `ui/day/EventEditActivity.kt`
(no hardening).

**Scenario.** Under a bedtime that allows only Vibb-kalender, the kid opens "Ny avtale". He types a phone number or
a URL in the title, selects it, and the floating toolbar offers:
- the TextClassifier's smart action ("Call" → `ACTION_DIAL`, "Open" → a browser, an address → maps);
- "Share" → the system sharesheet, with unsuspended targets such as GMS Quick Share;
- the `PROCESS_TEXT` and "Web search" entries.

Suspended apps answer with "app paused". But the lock leaves the system dialer unsuspended: `MdmSyncWorker`'s doc
says "the lock suspends every app but ours, the system dialer and the lock's usable apps". With calls not managed,
the system dialer holds the dialer role, so "Call" reaches it under a no-calls rule. The time-rule screen says
"Every other way to the system dialer is closed during the lock".

Until now no text field of ours was reachable under a rule. The project already set the bar for a field over the
lock in `ui/ReEnrolDialog.kt:121-133` (revision check N4: "nothing it offers can start an activity over the lock").

**Fix.** Apply the same `noTextActions` to both EditTexts: custom selection and insertion `ActionMode.Callback`s that
return false, plus `isLongClickable = false`. Also `setTextClassifier(TextClassifier.NO_OP)`. Either do it always
(copy and paste in a kid's own event title is little loss) or only while `lockReason != NONE`. Lift the helper into
a shared place, and add a DayTaskTest-style source check that both fields get it.

## Low

### L1: A stale glance hand-over suppresses the one-thing open

**Where:** `lock/GlanceOpener.kt:91`, `fun handOverPending(): Boolean = handedOver != null`.

**Scenario.** A hand-over is set at an unlock whose time-rule screen never resumes, for example a call coming in.
It stays in memory. At the next unlock, `openHandedOver` drops it as expired, but `handedOver == true` was already
read, so `unlockOpenRow` returns null and the only usable thing doesn't open.

**Fix.** Make `handOverPending()` also check `SystemClock.elapsedRealtime() - handedOverAt in 0..GLANCE_HAND_OVER_MS`.
Simpler: let `openHandedOver` return whether it took a fresh target, and use that result.

### L2: The Kalender row is gated on `dagenOn()`, not on what Home shows

**Where:** `ui/LockActivity.kt:242`, `CalendarStore.dagenOn()`.

**Scenario.** `calendar_on_home` defaults to 1 (0103), so on a phone with no calendar set up (Home has no Kalender
tile because `tileShown()` is false), a rule with Vibb-kalender on still gets a Kalender row. With nothing else
usable, the PIN lands the kid in an empty Kalender after every unlock. The server side has the same gate:
`calendar_shown` from `calendar_on_home`. Not a bypass.

**Fix.** Use `CalendarStore.tileShown()` for the row (Home's own condition), or say in the note that this is
deliberate.

### L3: Migration order and the pre-migration backup

**Where:** `server/migrations/0119_time_rule_calendar.sql`, with `startup.rs:257` (`pre_migration_backup` compares
`MAX(version)` with the latest).

**Scenario.** If a server release ships with 0119 before CalDAV's 0117 lands, the release that later brings 0117
starts on a DB whose max is already 0119. `from >= latest` is true, so there is no `pre-migrate-*.db` before 0117
runs. sqlx still applies 0117 out of order.

**Fix.** Merge CalDAV first, or renumber CalDAV's migration above 0119 (0120) when it lands after this one, as
`server/CLAUDE.md` already hints ("renumber at the merge"). Say it explicitly at merge time.

### L4: The keyboard becomes reachable under a Kalender-only rule

**Where:** "Ny avtale" brings up the IME under a rule.

**Scenario.** The IME is never suspended (`keptInputMethods`). Gboard's own surfaces become reachable at bedtime:
GIF/sticker search (Tenor content), its settings, and "Help & feedback". This was already true under a budget lock
or a rule with a messaging app exempt, so it is not new to the phone, but it is new to a rule that offers only
Kalender.

**Fix.** Nothing in code is needed. Tell the user, since he approved "Ny avtale" without this detail. Optionally pass
`IME_FLAG_NO_PERSONALIZED_LEARNING` on the fields.

## Nit

- **N1: merge conflicts.** `server/src/tests/music_spotify.rs` (the migration list must become `116, 118, 119`, plus
  the prose list) and `server/CLAUDE.md` (the "next free number" line) conflict with main
  (`git merge-tree main calendar-in-rules`). They are expected; resolve them at the merge.
- **N2: `LockWatch` duplicates an existing mechanism.** It builds its own listener on SharedPreferences, but
  `TimeRulesRuntime.addLockListener` (design r13 §1.4) already hands each recheck's snapshot to listeners after
  `lock_reason`/`lock_key` are written. Using it would save the second snapshot in `calendarUsableNow`. Keep the
  current version if the pref-listener pattern (as in LockActivity) is preferred.
- **N3: the en sub-line.** `server/src/i18n/en.json` `device.exceptions.calendar_sub`, "Kalender on the phone, new
  events included". The English tile is "Calendar" (`values/strings.xml` `day_title`). Better: "Calendar on the
  phone, including new events". The nb text is fine.
- **N4: an everyone-rule note can be wrong.** In `ScopeApps::exceptions`, a saved package that one phone allows but
  hasn't reported, while another phone reports it without allowing it, gets "Not allowed on any phone", which isn't
  quite true. The union also counts phones where the everyone rule is switched off. Harmless; tightening the note
  wording would be enough.
- **N5: per spec, but worth telling the user.** Bedtime's default is `calls_allowed = true`. Under a bedtime with no
  apps and Kalender off, every PIN unlock at night now lands in the phone book. This is what (b) says ("the phone
  book counts as one").
