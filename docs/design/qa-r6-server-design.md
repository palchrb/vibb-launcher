# QA - server round 6 design: 48 (Activity log, Requests, clock words, command retention) and 51 (glance text per app)

Reviewed on `main` at `7942585f` against the code. Design QA only: nothing run, nothing else edited.

## Design 48 - one Activity log; Locate's Requests

**48-1 High - §0 misdescribes Today, so "Today goes" silently drops a control and breaks the no-jump rule.**
`#today` is not "the phone's events of the day plus `relock.answers`": it holds (a) the **lifts** - today's pauses and
extra time with **End now** (`device_detail.html:182-192`, `DeviceDetailTemplate.lifts`, `devices.rs:751-752`), (b)
`relock.answers`, (c) the **PIN notices** from `device_pin_events` (`parent_pin::notices`, `device_detail.html:208-213`),
and (d) the `today` flash. No `parent_events` are on the summary at all today. End now posts to `…/lifts/{id}/end#today`
and `lifts::back_to` redirects `back=summary` to `/devices/{id}?done=…#today` (`lifts.rs:38-58`, `summary_group("lifts")
= "today"`); with `#today` gone the End-now save lands at the top with its flash nowhere. The PIN notices' "wrong tries"
line and "Typed where your child could see? Change it." tail have no `parent_events` kind (`parent_events.rs:35-46`), so
they vanish too. Fix: say where a running lift with End now lives (a row in Quick actions, or a lift row in Activity
with its own form) and repoint `summary_group("lifts")` and the `today` flash to that group; state that `pin_used` /
`pin_lockout` events replace the PIN notices and whether the wrong-tries notice is dropped on purpose; fix §0.

**48-2 Medium - the `relock.answers` de-dup rule is moot and its premise is wrong.** With Today gone there is no card
left to drop a line from. The two also do not "say the same": an answer says Answered / Cancelled / Expired / "No
answer yet" with `The phone said: "…"` (`window_relock.rs:875-882, 917-937` - a failed command reads "Answered"), a
command row says Done / Failed / Waiting / Sent / Received / Not confirmed with the reworded `answer()`
(`locate.rs:305-394`). Fix: stop rendering `relock.answers` (drop the field or leave it unused; `last_answers` stays -
the blocks need it, `window_relock.rs:75, 97`) and delete the retain/de-dup loop at `devices.rs:1629-1643`; the command
row is the only line for a command.

**48-3 Medium - `display_time::day_and_time` duplicates `Zone::at`, and its words are Norwegian on an English PWA.**
`Zone::at` (`display_time.rs:181-191`) already gives "10:59" / "yesterday 21:40" / "12 Oct 08:12" / "12 Oct 2025 08:12"
- the "clock on every row" the user asked for. Every other time word on these pages is English (`Zone::when`,
"yesterday", "Expires 12:30…"); "i dag / i går / 15. okt" would be the only Norwegian. Also §0 names
`display_time::relative` for today's command rows; they use `Zone::when` (`locate.rs:420`). Fix: use `Zone::at` for
`CommandRow.asked` (or add "today " in front inside `at` if a bare clock reads too thin - one function, not two); the
test strings become "13:41", "yesterday 14:50", "15 Oct 14:02".

**48-4 Medium - Requests' membership is not in the status enum's terms.** "What waits" and "what failed or expired"
leave out the delivered-but-unanswered states `Sent` (NotYet / OfferedAgain / Lost / CantConfirm), `Received`
(overdue or not) and `Erasing` (`models.rs:755-789`, words at `locate.rs:356-392`). "Not confirmed" and "No answer" are
red failures; a `Sent`/`Received` Lock is in flight but not cancellable. Fix: define it on `CommandStatus`: shown =
`Waiting | Sent{NotYet,OfferedAgain,CantConfirm} | Received{overdue:false} | Erasing` at any age, plus `Failed | Expired
| Sent{Lost} | Received{overdue:true}` requested within 24 h, plus the newest row of `ring`/`lock`/`wipe` whatever its
status and age (say whether a cancelled/replaced newest counts); `Done` locates never; parent-cancelled rows never
(the Cancel's flash says it). One test per bucket.

**48-5 Medium - "hidden when empty" loses the Cancel's landing.** Cancelling the only waiting request empties
Requests: the group isn't rendered, `#recent` doesn't exist, the page lands at the top and the "Cancelled. The phone
will never get it." flash (`locate.rs:524-532`) is never shown. The summary already guards this
(`device_detail.html:174`: rows or flash). Fix: render Requests (and Activity) when it has rows **or** a flash; a test
cancelling the last waiting row sees the flash in the group.

**48-6 Medium - the retention predicate names status words that don't exist.** `device_commands` has no status column
and no `queued` word (`0010_find_my_device.sql:23-31`, `0065`, `0068`); status is computed. A lock, wipe, `end_windows`,
`allow_blocked` or `install_update` never delivered must survive any age - "a lost phone that comes back weeks later
must still be wiped or locked" (`models.rs:581-587`; the pop, `device_api.rs:352-360`). Fix: spell it in SQL like the
pop: `DELETE FROM device_commands WHERE requested_at < datetime('now','-30 days') AND (acknowledged_at IS NOT NULL OR
cancelled_at IS NOT NULL OR delivered_at IS NOT NULL OR command IN (<EXPIRING_COMMANDS>))` (a delivered one is far past
`REDELIVER_WINDOW_MINUTES`; an expiring one far past its hour). Tests: a 31-day-old undelivered wipe and lock are kept,
a 31-day-old expired locate and a done ring are pruned, `Pruned.commands` counts them (and the literal in
`tests/cleanup.rs:361` gains the field).

**48-7 Medium - the 7-day window takes away the only Cancel of an old waiting override command.** The summary is the
only place an `end_windows` / `allow_blocked` / `start_pause` can be cancelled (`OVERRIDE_KINDS`, `locate.rs:37`;
Locate shows only `LOCATE_KINDS`). A "Lock again" waiting on a phone offline for 8 days drops out of Activity with its
Cancel. Fix: the command query takes `cancellable` rows at any age plus the rest within 7 days; a test with an 8-day-old
waiting `end_windows` still showing Cancel.

**48-8 Low - the Cancel's redirect does change.** `cancel_command` sends `back=summary` to `…?cancel=…#recent`
(`locate.rs:850-853`) and the partial's form action carries `#recent` (`partials/recent.html:13`); both become
`#activity` for the summary. Say whether Locate's group keeps `id="recent"` or becomes `#requests`, and change
`device_locate.html:116` ("can be cancelled under Recent").

**48-9 Low - one list, two time vocabularies and an unnamed sort key.** Event rows keep "4 h ago" next to command rows
saying "13:41"; and "newest first" must name the event's time (`happened_at`, which may come days late, or
`created_at`; the inbox orders by id, `notifications.rs:460`). Fix: in Activity give event rows `Zone::at` of
`happened_at` too and sort both by the moment they happened (requested / happened); the inbox keeps `relative`.

**48-10 Low - the event row's `open` from another page.** `open` is a POST that, for a pruned event or a non-local
target, falls back to `/notifications{back_query}` (`notifications.rs:497-515`): the summary's form must carry hidden
`from=devices` and `device={id}` so that lands on this phone's filtered inbox. The `InboxRow` is built inline in
`page()`; name the extracted builder, and drop the avatar (it is this phone's own page). The "Everything for this phone ›"
link likewise `/notifications?from=devices&device={id}`.

**48-11 Low - successful Update Locations fill Activity.** "Every kind" lets a parent's repeated Update Location taps
push events out of the 10 rows, while Requests hides them as noise. Acceptable as the history (the user's (4)), but say
it was chosen; otherwise apply the same rule.

Standing rules: nothing a phone sent is rendered as HTML (no `|safe` in the five templates; `answer()` text and event
words are escaped) - fine. No migration in 48. `check_forms` exists for Locate (`tests/locate_page.rs:72`) and the
settings pages (`tests/settings_pages.rs:104`); name the one the summary test uses. Interaction with Requests: the same
lock/wipe can show in both places with its Cancel, each returning to its own page - fine once 48-8 is done.

**Verdict 48: not ready - revise.** 48-1 must be decided (where the lifts' End now and the PIN notices go) and §0
corrected; 48-2 to 48-7 are spec fixes of a sentence or two each.

## Design 51 - "show the text" per app

Checked first: an old launcher does **not** fail its policy on object elements. `PolicyResponse.lockGlance` is a raw
`JsonElement?` (`PolicyResponse.kt:128`) and `lockGlancePolicy` keeps only string primitives
(`GlanceRows.kt:47-57`: `(it as? JsonPrimitive)?.takeIf { p -> p.isString }`), so objects are dropped and the old
launcher shows missed calls only, as the note says. Migration 0083 is the next free number (highest on `main` 0082,
`server/CLAUDE.md` "The next free number is 0083"; no local branch holds 0083).

**51-1 Medium - the top-level `show_text` does nothing for an old launcher, and the kept global switch is not what
PLAN decided.** §1.1 keeps `lock_glance_text` "to keep the policy readable by an old launcher", but an old launcher
given objects has an empty `apps`, so its `show_text` is moot. PLAN's entry says the per-app switch comes "in place of
the global one"; §1.4 keeps it as "Show the text for new apps" - a second control nobody asked for. Fix: drop the
global switch from the page; a new tick starts at sender only (the privacy-safe default, and what the
`devices_get_lock_glance_default` trigger's Element X row gets anyway); keep the column (frozen, used once for 0083's
backfill) and send top-level `show_text: false` only as the fallback for an element without its own flag.

**51-2 Medium - the save is unspecified and would land on the group, not the row.** Each app is already its own form
posting `package_name` + `on` to `/lock-screen/apps`, returning to `#lock-screen-app-<pkg>` (only an unticked unlisted
row goes to `#notifications`) (`device_lock.html:203-211`, `handlers/lock_glance.rs:137-143`). "Every save comes back to
`#notifications`" would scroll a long list to the group top. A second checkbox named `text_<package>` in the same form
would be posted with every tick save and the handler's `INSERT … DO NOTHING` can't tell a tick from a text change. Fix:
a separate mini-form per ticked row, `POST /devices/{id}/lock-screen/apps/text` with `package_name` + `show_text`,
`UPDATE … WHERE device_id AND package_name` (no row = 400, nothing written), logged like `text_log_line`, back to
`#lock-screen-app-<pkg>`; the switch only on ticked rows.

**51-3 Low - an additive shape avoids the old-launcher gap.** The user accepted the gap; still, the repo's own pattern
removes it at no cost: send the string shape to a launcher without a new capability (`lock_glance_v2`) and the object
shape to one with it (`LOCK_GLANCE_CAPABILITY`, `lock_glance.rs:20`). If kept as proposed, the release note §1.3 names
is the only mitigation - say it in the launcher release's notes too.

**51-4 Low - where `showText` is read.** Not in `GlanceRows`: the level is computed at read time in
`GlanceReader.kt:114` (`glanceLevel(…, showText = policy.showText, …)`) and logged in `GlanceStore.kt:146`. With `apps:
Map<String, Boolean>`, `glanceRows`' `it.packageName in policy.apps` still works (map keys); the reader takes
`policy.apps[pkg] == true`. Name those two files; `UNKNOWN`/`SERVER_DEFAULT` become maps.

**51-5 Low - the global switch is not in `#notifications`.** It is `#lock-screen-text` in the group above, with the
footer "Anyone holding the phone can read the message text." (`device_lock.html:170-180`). With 51-1 that row and
`save_text` go (or the route stays for an old page and does nothing new); the footer's warning moves under the
Notifications group.

**51-6 Low - §1.5's "Fossify Clock is not in this list" is not true.** `card_apps` lists every allowed (or reported)
app (`lock_glance.rs:170-200`), so an allowed Fossify is listed and tickable. It is harmless: its timer/stopwatch
notifications are ongoing / alarm-category and `glanceCandidate` skips them, and design 42's `ClockReader` reads its
own three channels without `GlancePolicy`. Reword §1.5 to that; design 42 is unaffected.

**51-7 Low - contract tests named precisely.** `policy_json_keys_snapshot`'s key list doesn't change; the value is
pinned at `tests/device_api.rs:446-451` and `PolicyResponseCompatTest.kt:495-515` - both move in the one commit (the
fresh phone's default becomes `{"apps": [{"package": "io.element.android.x", "show_text": false}], "show_text":
false}`). Optionally a shared `server/testdata/lock_glance_policy.json` byte-identical in the launcher's test
resources, as the other contracts. `migrations_73_to_82_in_order` (`tests/music_spotify.rs:182`) extends to 0083;
`server/CLAUDE.md` "Data model" moves to 0084. Server tests also: the per-app log line; unticking and re-ticking resets
the flag to the default.

Standing rules: both directories in one commit, stated; old launcher never fails (verified above); no-jump only with
51-2; nothing from the phone is rendered (the server only stores ticks).

**Verdict 51: go after 51-1 and 51-2** (Lows as spec edits).
