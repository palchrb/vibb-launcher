# 139 - App groups with a shared daily limit, and apps outside the filter

Status: design, revision 1, 2026-11-26 (branch `app-groups-design`), read against `main` b777a957e. Not built. PLAN's
locked list item 7, approved by the owner on 2026-11-20 ("jeg synes begge ideene fra upstream høres veldig bra ut ...
legg inn dette i planen vår som noe vi vil ha"), from `docs/review/2026-11-20-upstream-review.md`. Both directories.
Part A (app groups) is the main design: one migration, one new contract file, two changed ones. Part B (chosen apps
outside the filter VPN) is a separate feature with no shared code. It is designed in §13 of this note, because it is
small and its open choices fit next to A's. It is built on its own branch. Android 14-17: every public API used is at
or under minSdk 34, except two calls on 35+ behind a `SDK_INT` gate with a path for 34 (§10).

Marks: **[code]** read in our code at b777a957e; **[sdk]** read in `platforms/android-37.0/data/api-versions.xml` on
this machine; **[d57]** design 57's AOSP reading (2026-10-21, `docs/design/57-voip-call-screen-over-lock.md` §1);
**[assumed]** not checked, step 0's probe or the emulator gate checks it (§9).

## 0. Short answer

- **A group** is a named set of a phone's apps with **one daily limit for all of them together**, set per weekday
  ("Spill": Mon-Fri 1 t, Sat-Sun 2 t). A group belongs to one phone. An app is in at most one group.
- **The phone counts** the time the group's apps are **on screen**, from Android's usage events. It needs Android's
  "App usage data" access, which a device owner can't grant itself, so it becomes a design 114 setup step,
  `usage_access`, needed while the phone has a group. The count goes into **today's screen-time ledger**, so the day,
  the trusted clock, the reset at midnight and the unreadable record work as they do today.
- **At the limit** the group's apps are **suspended**, the same call a time rule uses (`EnforcementPlan.suspend`), but
  only those apps. If one of them is in front, Home comes forward. A call is never cut.
- **Without the access** a group with a limit today is closed (fail closed, the standing rule). The PWA shows one orange
  line through design 114's setup row.
- **It fits what is there:** the daily screen-time limit still counts the whole phone, and a used-up group doesn't
  stop the phone. Rules lock as today, and a used-up group stays closed under a rule even if the rule allows its app.
  "Pause all restrictions" and the offline override lift groups. The parent-code rule pause and the "+15 min" screen
  time don't. **Extra time for a group** is new: a lift target and a "Be om mer tid" from the group, if the owner
  wants it (Q3).
- **PWA:** Device › Time Rules gets an "Appgrupper" group, with a row per group and today's minutes. Each group has
  an editor page: name, apps, the seven day limits. The summary's Skjermtid sheet gets a "Hva" choice.
- **Part B (§13):** a per-app choice on the app page puts an app outside the DNS filter. The app is left out of the
  tunnel and **kept off lockdown's allow list**. So Bare Vibb, the kid's "Mobildata av" and the travel cuts still cut
  it, and only the filter is skipped.
- **Size:** A is L (server S+, launcher M+, a gate). B is M.

## 1. What upstream built, and what we take

Upstream (siesta5787, GPL-3.0): server e11ec64b7 (0.23.0) and launcher f7dea2083 (0.54.0), read with `gh api` for
this note.
- Its table `app_groups` is per device: `packages_json`, `daily_limit_minutes` (one number), one allowed-hours window,
  and a Pause/Allow override until a time. The phone's last usage report is kept on the row.
- The launcher's `AppGroups.kt` sums foreground time per package from `UsageEvents` (RESUMED to PAUSED, cut at screen
  off) over today plus a 3 h lookback. A per-minute tick in the push service checks the limits and sends the kid home.
  Without usage access **the limit is not enforced** (fail open). Hours and Pause still apply.

What we take: the idea, per-phone groups, and suspension (never hiding). What we do differently, as the review
recommended (`2026-11-20-upstream-review.md` row f7dea2083):
1. The server owns the groups, and they travel in `time_policy`.
2. There is no per-minute tick of its own. The screen-time tracker's existing checkpoint and one-shot timer do the
   timing (designs 93/100: no 60 s polling while the screen is off).
3. The access gets an on-phone path (design 114's setup step), never adb only.
4. Without the access the group is closed, never left unlimited.
5. Shared contract files.

Upstream's hours window and Pause/Allow are not in this design. Our rules decide when (Q5), and our lifts are the
override. No upstream code is copied. If the build adapts any of it, the file's header names siesta5787's
kids-launcher-mdm f7dea2083 (GPL-3.0) beside our own licence line. Nothing goes into the MIT directories (`ui/list/`,
`apps/`). They only get a one-line call into GPL code, as `PlatformRefusals.plannedOff` does today.

## 2. Today (`main` b777a957e)

- **Screen time** is one budget for the whole phone (`timerules/ScreenTime.kt`, `ScreenTimeTracker.kt` [code]). It
  counts while the screen is on and unlocked, outside our free screens. It is event-driven: a 60 s checkpoint while
  counting, and a one-shot timer at the budget's end (`budgetRunsOut`, remaining + 1 s). The ledger
  (`ScreenTimeLedger`, CE prefs `time_rules_state`) follows the trusted day (`observe`/`observeTrusted`), and an
  unreadable ledger counts today as used up.
- **The lock**: `decideTimeLock` gives `LockReason`, and `computeEnforcementPlan` (`server/EnforcementPlan.kt:178`
  [code]) suspends every controllable app outside `neverRestrict`, the alarm apps, the lock's usable apps and the
  calendar sync apps. With the kiosk on, the pinned list shrinks to the usable apps. Apps suspended only by the lock
  stay visible (not hidden).
- **Home** hides every suspended app (`apps/AppFilter.kt:68`, `isMdmSuspended`, MIT) except the camera the PIN lock
  holds (`CameraLock.suspendedForLists`).
- **Lifts**: `time_lifts.target` is `CHECK (target IN ('rule','budget'))` (migration 0025 [code]), and
  `time_request_answers.lift_id` references it (0149). A budget lift adds minutes once per id to the day it is first
  seen.
- **"Be om mer tid"** (design 100 §8, r13) asks for screen time only: the time-rule screen's pill (used-up budget
  only), the anchor notice's action and the Mer tid disc. There are three a day (`TIME_REQUESTS_A_DAY`).
- **Usage access** is declared nowhere. `app_access_usage_access` ("Data om appbruk") names it only for *other* apps
  (design 68). Design 57 planned it for VoIP and was superseded, unmerged.
- **Setup steps** (design 114, `contracts/core/setup_steps.json`): each row has a `when` condition, the phone's
  pages, and the PWA's count and orange.

## 3. Decisions

- **D1 - Per phone.** A group belongs to one phone (its own apps, its own kid's limits), as the bedtime does. There is
  no "for everyone" set and no per-phone switch (Q1).
- **D2 - One group per app.** Saving an app that is in another of the phone's groups is refused. The phone still
  counts an app in two groups (a race, a restored backup) in both and fails closed.
- **D3 - Limits per weekday.** There are 7 entries, Monday first: minutes 0-1440, or `null` for no limit that day. `0`
  closes the group that day ("no games on school days"). This is the shape of `daily_budget_json`, with the same
  partial on the page.
- **D4 - What counts.** A group's time is the time **any** of its apps is **on screen** while the screen is on and the
  keyguard is down. Two of its apps side by side count once. Sound in the background (an audiobook with the screen off)
  doesn't count. The group's time is also screen time, because the screen is on.
- **D5 - The ledger is ours; usage events only say who was in front.** At every fold the phone reads the events since
  the last fold and adds each group's on-screen time to today's ledger. The sum is capped by the elapsed realtime of
  the span, so a wall-clock jump can't add time. Usage events are stamped by the wall clock. That clock is locked
  (`DISALLOW_CONFIG_DATE_TIME` plus automatic time) whenever the time policy has a rule, a budget or now a group, so a
  kid can't set it back.
- **D6 - Fail closed.** A group with a limit today is closed (every app of it suspended) when:
  - the access isn't granted;
  - the ledger is unreadable;
  - a read throws (then the span counts for **every** group, because we don't know which app was in front).

  `days` of the wrong size is a limit of 0, as a wrong-size budget is today.
- **D7 - At the limit:** the group's apps are suspended, not hidden. They leave the kiosk's pinned list (lock task
  clears their tasks). If one was in front, Home comes forward with the group's sheet (Q2). The DO's start is exempt
  from the background-start limits, as the time-rule screen's start already relies on. During a live call (our call
  list, or audio mode in call/communication) the cut waits until the call ends.
- **D8 - Stricter wins.** A used-up group's apps stay suspended under a rule that allows them and under the used-up
  budget's messaging apps: `lockUsableApps - usedUpGroupApps`. The time-rule screen offers no row for them.
  `neverRestrict` (our package, the system dialer, the recents provider, SOS, WebView, the input methods) and the alarm
  apps are never suspended by a group, whatever it lists. The server doesn't offer them anyway: it offers the phone's
  reported, allowed apps.
- **D9 - Lifts.**
  - "Pause all restrictions" and the offline parent override lift groups (the standing rule: they lift everything).
  - The parent-code rule pause (design 98), a rule lift, "Slå på regel" and the "+N min" screen time leave groups as
    they are.
  - Extra time for a group is its own lift (§5.2), added once per id to the day it is first seen, like a budget lift
    (Q3).
- **D10 - Counted during a pause too.** Groups are lifted during the pause and the override, but their time still
  counts, so the parent sees it and the limit holds after.
- **D11 - Data minimal.** On Android 14 `queryEvents(begin, end)` returns every app's events [d57]. They are read in
  memory for one fold and dropped, never stored or sent. On 35+ the query asks only for the group's packages and the
  event types we need (`UsageEventsQuery.Builder.setPackageNames`/`setEventTypes`, API 35 [sdk]). The report sends
  minutes per group, never per app and never which app.

## 4. The phone

### 4.1 Counting (pure `timerules/AppGroups.kt`, glue in `ScreenTimeTracker`)

- **The fold** (pure): `groupScreenMs(events, from, to, carry, groups) -> (perGroupMs, carryOut)`.
  - `events` are `(timeMs, package, class, kind)` with kind RESUMED, STOPPED, DESTROYED (an activity that dies while
    visible), SCREEN_NON_INTERACTIVE, SCREEN_INTERACTIVE, KEYGUARD_SHOWN or KEYGUARD_HIDDEN (API 28/29 [sdk]).
  - An app is on screen from an activity's RESUMED until that activity's STOPPED or DESTROYED, per (package, class).
    PAUSED isn't the end. So a paused but visible window counts: split screen, and picture-in-picture with the kiosk
    off (lock task refuses PiP [d57]).
  - Screen off or keyguard shown cuts everything. A group's time is the union of its apps' spans.
  - `carry` is what was on screen at `from`, kept in memory with the fold's wall time. After a process start it is
    rebuilt from a read 3 h back, as upstream does.
  - The result is capped: each group at most `elapsed(to) - elapsed(from)`.
- **When it runs:** in `ScreenTimeTracker.update` (screen on/off, user present, our activities' resume/pause, every
  re-check) and at its 60 s checkpoint, only while the policy has a group. The read is a binder call, so it runs on
  the IO executor, and the result is folded on the main thread (the ANR lessons of 2026-11-26).
- **The timer:** next to `budgetRunsOut`, `groupRunsOut` at `min(remaining of the open groups) + 1 s`, posted only
  while the screen is on and unlocked. When it fires it folds again. A group used up starts `TimeRulesRuntime.recheck`.
  Otherwise the timer is armed again with the new minimum. Time can't grow faster than the clock, so it never fires
  late. With the screen off there is nothing to count and nothing armed.
- **The ledger** (`ScreenTimeLedger`) gains `groupUsedMs: Map<Long, Long>`, `groupExtraMinutes: Map<Long, Int>` and
  `appliedGroupLifts: List<Long>` (at most 50). A new day clears the first two in the same branches that clear `usedMs`
  and `extraMinutes`. `PreviousDay` keeps them too, so a carried reset that is undone brings the groups back.
  `unreadableLedger` closes every group with a limit today (D6).
- **The access:** `timerules/UsageAccess.kt` reads `granted: Boolean?` as the service decides it [d57]:
  `unsafeCheckOpNoThrow(OPSTR_GET_USAGE_STATS)` is `MODE_ALLOWED`, or `MODE_DEFAULT` with `PACKAGE_USAGE_STATS`
  granted. A throw gives `null`, which counts as not granted for D6. It is read at each fold and each report. A
  change (`startWatchingMode`) posts a re-check and a sync.
- **The manifest** declares `<uses-permission android:name="android.permission.PACKAGE_USAGE_STATS"
  tools:ignore="ProtectedPermissions"/>`. Without it Vibb One isn't on Android's page, and an `appops set` is reset at
  once [d57]. A manifest test keeps it there.

### 4.2 Deciding and enforcing

- `TimeRulesRuntime.snapshot` gains `groups: List<GroupState>` (id, name, limit today, used, extra, open/used up/no
  access). The pure `groupStates(policy, ledger, access, overrideOrPause, dow)` computes them.
- `computeEnforcementPlan` gets `groupClosedApps: Set<String>`. Those apps are added to `suspend` (not `hide`), taken
  out of the kiosk's pinned list, and taken out of `lockUsableApps` (D8). The plan's other inputs are unchanged.
- `restrictCreateWindows` and the date/time lock are also set while a group exists, as they are for a budget today
  (`budgetSet || groupsSet`).
- `TimeRuleAlarm`'s boundaries include midnight while a group has a limit (the day's reset opens the groups). A group
  lift's arrival is an accepted policy, which runs a re-check.
- **At the cut** (D7): after the apply, if the package in front (the fold's `carry`) is in a group that just closed
  and no call is live, Home is started with the group's sheet open.

### 4.3 What the kid sees (Q2 decides; this is (b), the recommendation)

- **Home and the app list** keep a closed group's apps, dimmed. The rule is GPL, in `timerules/`; `AppFilter` calls
  it, as it calls `PlatformRefusals.plannedOff`. A tap opens the group's sheet in Home's kid-sheet style (swipe down
  closes it, as every kid sheet since 2026-11-17):
  - the group's name;
  - "Brukt opp i dag" ("Ikke i dag" when today's limit is 0);
  - while the access is missing: "Kan ikke telle nå" alone, with no ask;
  - under it, the "Be om mer tid" block of the time-rule screen. It uses the same pure `lockAsk` states (idle,
    pending, answered, capped) with the group's request (Q3).
- **Before the cut (Q4, recommended b):** in the last 5 minutes, while one of the group's apps was in front at the
  last fold, the anchor notification's title is "{group}: 5 min igjen". This is r13's one notification, with no
  sound and no second notice. The screen-time title returns after.
- The time-rule screen and the glance stay as they are.

### 4.4 The report

`time_state` gains `usage_access` (bool, left out when unreadable) and `groups: [{id, used_minutes, extra_minutes,
closed}]`, where `closed` is true when used up, closed by `0`, or closed by D6. The keys are pinned in
`time_status_report.json`.

## 5. The server

### 5.1 Migration (the next free number at build time)

Main's next free number today is 0181. The post-release chain about to merge takes 0181-0184, so this one is probably
0185.

```sql
CREATE TABLE app_groups (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id  INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    name       TEXT    NOT NULL,                 -- 1-40 characters, trimmed
    apps_json  TEXT    NOT NULL DEFAULT '[]',    -- package names, at most 50 (D2 checked on save)
    days_json  TEXT    NOT NULL,                 -- 7 entries Monday first: minutes 0-1440 or null (D3)
    created_at TEXT    NOT NULL DEFAULT (datetime('now'))
);
CREATE INDEX idx_app_groups_device ON app_groups (device_id);

CREATE TABLE app_group_lifts (                    -- Q3 (a)/(b) only
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    device_id  INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    group_id   INTEGER REFERENCES app_groups(id) ON DELETE SET NULL,
    group_name TEXT    NOT NULL,                 -- Activity's words once the group is gone
    minutes    INTEGER NOT NULL,
    created_at TEXT    NOT NULL DEFAULT (datetime('now')),
    expires_at TEXT    NOT NULL,                 -- delivered 12 h, as a budget lift
    created_by TEXT
);
CREATE INDEX idx_app_group_lifts_device ON app_group_lifts (device_id, created_at);
ALTER TABLE time_request_answers ADD COLUMN group_lift_id INTEGER REFERENCES app_group_lifts(id) ON DELETE SET NULL;
```

Group lifts get their own table, not a third `time_lifts.target`. SQLite can't change a CHECK, and rebuilding
`time_lifts` would mean dropping a table that `time_request_answers.lift_id` references. With `foreign_keys(true)`
(`main.rs:2117`) that DROP would null every answer's `lift_id`. Retention prunes `app_group_lifts` with the lifts
(`LIFT_DAYS`). `backup_schema` gets the two tables.

### 5.2 Wire (contract `contracts/core/app_groups.json`, new; README row)

```json
"time_policy": {
  "groups": [{"id": 3, "name": "Spill", "apps": ["org.example.game"], "days": [60, 60, 60, 60, 60, 120, 120]}],
  "group_lifts": [{"id": 9, "group_id": 3, "minutes": 15, "expires_at_ms": 1793077800000, "by": "Mamma", "at_ms": 1793034600000}]
}
```

- Both arrays are always sent (`[]` when empty). The launcher reads an absent one as `[]`: the server ships first,
  and the phones get no compatibility code (CLAUDE.md).
- A stored group that doesn't read is a 500 (`PolicyError::CorruptTimePolicy`), never fewer groups. This is the rule
  for rules.
- `policy_json_keys_snapshot` and `PolicyResponseCompatTest` pin the keys.
- The status keys of §4.4 go into `TIME_STATE_FIELDS`, with one new field kind for the array (at most 50 objects of
  the four known keys; anything else is dropped).
- A request for more time (design 100, `time_request.json`) may carry `"group_id": 3`. The answer then names
  `group_lift_id` in place of `lift_id`. Both cases go in the contract.
- The contract has a policy, a report, a request and an answer for a group. `app_groups.json` holds the rows both
  sides test. `time_status_report.json` and `time_request.json` get their new rows. Everything goes in one commit.

### 5.3 Routes and pages

- `GET/POST /devices/{id}/groups/new`, `/devices/{id}/groups/{gid}` and `POST .../{gid}/delete`
  (`handlers::app_groups`, template `device_group.html`, tab Devices).
- The editor's form follows the Bedtime page: Save in the sticky bar with its flash, Name, **Apper** (the checkbox list
  of `partials/exceptions.html` fed by `scope_apps` for this phone - the phone's reported, allowed apps; apps in another
  of its groups are not offered; a ticked app that is no longer allowed or installed keeps its note and stays ticked),
  **Grense** (`partials/budget_rows.html`), and Slett gruppe… as a `<details>` confirm outside the form.
- A refusal renders the page again (400) as typed, with the reason under the field (`autofocus`, `aria-invalid`) and
  in the bar.
- Where each form returns (no save jumps to the top):

  | Action | Returns to |
  |---|---|
  | Save | `?saved=1#group` |
  | Add | the new group's page, `?saved=added#group` |
  | Delete | `/devices/{id}/time?notice=group_deleted#groups` |
  | A group deleted in another tab | `#groups` with `notice=gone`, nothing written |

- `every_form_on_the_time_pages_returns_to_its_group` covers them.
- **Device › Time Rules** gets the group **Appgrupper** (`#groups`) after Daily Limit:
  - one row per group to its editor: the name over "{days} · {n} apper" (`budget_summary` plus the count), with
    today's state on the right as a quiet value (below);
  - "Legg til gruppe" as a row action;
  - the heading's (i);
  - one orange line while `usage_access` is false and a group has a limit (it is wrong: the group is closed), with
    its fix behind the (i);
  - "Kan ikke leses" in `text-alarm` for a group that doesn't read.
- **The summary's Quick actions:** the Skjermtid sheet gets a "Hva" segmented choice ("Skjermtid", then each group's
  name) only while the phone has a group. It posts `target=group&group={gid}` to `/devices/{id}/lifts`, which writes
  `app_group_lifts` (a group of another phone is a 404). The open request's card names the group, and its tiles give
  to the group.
- **Activity and log:**
  - Activity has "{group}: 15 min ekstra" with the giver.
  - The security log gets `app_group_saved`, `app_group_deleted` and `app_group_lift`, with the group's name as a
    parameter.
  - Each one nudges the phone.
  - A request for a group raises the existing `extra_time_request` event, worded with the group. No new push kind.
- **Setup (design 114):** a row `usage_access` (required, `read_by` phone, `when: "app_groups"` - the policy has a
  group with a limit on some day). Its features are `["app_groups"]`, its phone page is
  `android.settings.USAGE_ACCESS_SETTINGS` (with `package:`, then without), and its names are "Data om appbruk" / "App
  usage data". The phone's reading is `UsageAccess.granted`. The flow, the PWA's count, Attention and the orange all
  come from 114's machinery. The new `when` value is read on both sides from the policy (`SetupInputs`, the server's
  `setup_status`), and the new feature `app_groups` goes into the contract's `features`. No new Attention kind is needed.

## 6. Words (nb / en; CLAUDE.md "Words in the UI")

| Where | nb | en |
|---|---|---|
| PWA group heading | Appgrupper | App groups |
| (i) at the heading | Appene i en gruppe deler én grense. Telefonen teller tiden de er på skjermen. | The apps in a group share one limit. The phone counts the time they are on screen. |
| Row sub-line | Man–fre 1 t · lør–søn 2 t · 3 apper | Mon–Fri 1 h · Sat–Sun 2 h · 3 apps |
| Row value | 40 av 60 min · Brukt opp · Ingen grense | 40 of 60 min · Used up · No limit |
| Add | Legg til gruppe | Add group |
| Orange line | Telefonen kan ikke telle | The phone can't count |
| (i) under it | Appene i gruppene er stengt til Data om appbruk er på. Under en pause på telefonen: Oppsett › Data om appbruk. | The groups' apps are closed until App usage data is on. During a pause on the phone: Setup › App usage data. |
| Editor title | Ny gruppe / {name} | New group / {name} |
| Fields | Navn · Apper · Grense | Name · Apps · Limit |
| Delete | Slett gruppe… · Slett | Delete group… · Delete |
| Refusals | Gi gruppen et navn · Velg minst én app · {app} er i {group} · Høyst 50 apper | Give the group a name · Choose at least one app · {app} is in {group} · At most 50 apps |
| Deleted | Gruppen er slettet. | The group is deleted. |
| Quick sheet | Hva · Skjermtid · {group} | What · Screen time · {group} |
| Request card | Ba om mer tid til {group} kl. 14:02 | Asked for more time for {group} at 14:02 |
| Activity | {group}: 15 min ekstra | {group}: 15 min extra |
| Setup row | Data om appbruk | App usage data |
| Kid's sheet | Brukt opp i dag · Ikke i dag · Kan ikke telle nå | Used up today · Not today · Can't count now |
| Kid's notice (Q4 b) | {group}: 5 min igjen | {group}: 5 min left |

The request states on the kid's sheet ("Be om mer tid", "Spurt kl. 14:02 · venter på svar", "Mamma ga deg 15 min",
"Du har spurt tre ganger i dag.") are the existing strings. The three-a-day cap is shared with screen time.

## 7. Tests (lean)

- **Launcher, pure:**
  - `AppGroupsTest`: the fold (RESUMED to STOPPED; two apps of one group side by side counted once; screen off and
    keyguard cut; an app on screen across the read's start, by `carry`; the elapsed cap; DESTROYED without STOPPED);
    `groupStates` (weekday limit, `0`, `null`, a lift applied once per id, no access, unreadable ledger, the override
    and the pause lift but still count).
  - `ScreenTimeTest`: a new day clears groups; a carried reset brings them back.
  - `EnforcementPlanTest`: closed group apps are suspended, not hidden, out of the pins, out of the usable apps;
    `neverRestrict` and the alarm apps are untouched.
  - The contract tests over `app_groups.json`, `time_status_report.json` and `time_request.json`.
  - A manifest test for `PACKAGE_USAGE_STATS`. `SetupStepsTest` picks up the new row from the contract.
- **Server:** `tests::app_groups`:
  - the pages (save, add, refuse in place, delete, the return table, D2 overlap, another phone's group 404);
  - the policy against the contract;
  - a group lift and its Activity line;
  - a group request answered with a group lift;
  - `TIME_STATE_FIELDS` keeping only the known keys;
  - `policy_json_keys_snapshot`;
  - `setup_status` with the new row.

## 8. Emulator rows (a new §6 section at build time; Android 14 and 17 - the lowest and highest it touches)

Test app: any allowed F-Droid app plus a second one as the group's other app. Group "Test", limit 2 min today.

1. **Step 0 (before the build's step 2):**
   - Vibb One is on Android's App usage data page during a pause.
   - On 15+, check whether the switch is greyed (restricted settings, ECM [d57]) for a phone set up by the QR and
     self-updated. If it is, the (i)'s fix line gets "trykk, OK, Appinfo › ⋮ › Tillat begrensede innstillinger".
   - Grant it. `cmd appops get` says allow, and a `queryEvents` shows the test app's RESUMED/STOPPED.
   - Also note what happens to a visible activity whose package is suspended with the kiosk off.
2. Use the app 2 min. It closes, Home comes forward with the sheet, and the tile is dimmed. A tap opens the sheet.
3. The two apps alternated count against one limit. Screen off and the PIN lock count nothing.
4. "Be om mer tid" from the sheet: the PWA's card names the group, the parent gives +15, and the app opens.
5. Revoke the access: the group closes, the PWA's setup row and the group's orange line show, and the kid's sheet
   says "Kan ikke telle nå". Grant it again: the group opens.
6. "Pause all restrictions": the group opens and its time still counts. End the pause: it closes again.
7. A rule that allows the test app, with the group used up: the app stays closed, and the time-rule screen has no row
   for it.
8. A call (the emulator's console call, with a group app in front at the limit): nothing is cut until it ends.
9. Kiosk off: the cut brings Home. Picture-in-picture of a group app counts.
10. Q4 (b): in the last 5 minutes the anchor notification's title names the group.

## 9. Steps

0. **Probe (emulator 14 and 17, owner's OK for the emulator):** row 1 of §8, on a throwaway branch that only declares
   the permission.
1. **Server and contract** (one commit with the launcher's contract tests): the migration, the pages, the policy, the
   report fields, the group lifts and request, setup row `usage_access`.
2. **Launcher:** the manifest, `UsageAccess`, the fold and the ledger, the plan, the timer, the cut, the report.
3. **Launcher:** the dimmed tiles, the sheet with the ask, and Q4's notice.
4. Code QA, then the emulator gate (§8) on 14 and 17. The release ships the server first.

Docs with the build:
- `docs/notes/server/time-rules.md` and `docs/notes/launcher/time-rules.md` (a section each);
- `contracts/README.md`;
- `docs/testing/emulator.md` (the new section and §4's `appops set ... GET_USAGE_STATS allow` as a shortcut, never
  the only way);
- `docs/setup/` (one line under the setup steps).

## 10. Android 14-17

- `queryEvents(long, long)` is API 21. `UsageEvents.Event.ACTIVITY_RESUMED/STOPPED` is 29;
  `SCREEN_INTERACTIVE/NON_INTERACTIVE` and `KEYGUARD_SHOWN/HIDDEN` are 28. `queryEvents(UsageEventsQuery)` with
  `setPackageNames`/`setEventTypes` is 35 [sdk]: behind `SDK_INT >= 35`, with the plain query on 34 (D11). Lint's
  NewApi stays on.
- The access is an app-op that a device owner can't grant. `hasQueryPermission` has no device-owner exemption on 14-16
  [d57]. 17 is checked by step 0.
- Restricted settings (ECM) exist on 15+ only [d57]. Step 0 checks whether a QR-provisioned, self-updated Vibb One is
  guarded.
- Android 16 names the page "App usage data" (our design 68 note). On 17 step 0 checks the name and that
  `ACTION_USAGE_ACCESS_SETTINGS` with `package:` still opens the app's own page.
- Background activity starts: the device owner is exempt on 14-17, which the time-rule screen's start relies on today.
  Nothing new.
- Suspension and lock task behave as they do for a rule today (design 105's unpin clears the task). Kiosk off on 14
  and 17 is checked by row 9.
- Checked on: nothing yet. Step 0 and the gate run on 14 and 17. The Jelly (16) gets the owner's row after the release.

## 11. Not chosen

- **`UsageStatsManager.registerAppUsageObserver`** would call back exactly at a group's limit, and the service lets a
  device owner use it (`hasObserverPermission` [d57]). But it is a `@SystemApi` (not in `api-versions.xml` [sdk]),
  so it would mean reflection, and it counts from its registration, so we would still need the events to count.
  Not worth a hidden-API path.
- **Counting from our own launches** (we start most apps from Home) misses Recents, notifications and app-to-app
  starts.
- **A per-group "Stopp nå"** (upstream's Pause): "Slå på regel" covers the whole phone. A per-group stop can come later
  if asked.
- **Hours per group** (upstream's window): Q5.

## 12. The owner's questions (only what changes the build)

**Q1 - Groups per phone, or for everyone?**
- (a) Per phone: each phone's own groups and limits, picked from its own apps.
- (b) Everyone's groups with a switch per phone, as the rules work.

Recommended: **(a)**. Children differ in age and apps, and it is half the PWA. Two phones need the group made twice.

**Q2 - What the kid sees when a group is used up (a visible cost).**
- (a) The group's apps leave Home and the app list until tomorrow or extra time. This is today's rule for any
  suspended app, with nothing new to build, but the grid changes under the kid's finger and nothing says why.
- (b) They stay dimmed, and a tap shows the group's name, "Brukt opp i dag" and "Be om mer tid".

Recommended: **(b)**. It costs a GPL hook in `AppFilter`, a dim state on the tile and one kid sheet.

**Q3 - Extra time for a group.**
- (a) The parent gives it (the Skjermtid sheet's "Hva"), and the kid can ask for it from the group's sheet.
- (b) The parent only.
- (c) None: the parent edits the limit.

Recommended: **(a)**. Asking is the kid's one path, and (c) means editing the limit and remembering to put it back.
(a) and (b) need the `app_group_lifts` table and the contract rows. (a) also needs `group_id` on a request.

**Q4 - A warning before the cut (a visible cost).**
- (a) None: the app closes mid-game.
- (b) In the last 5 minutes, while a group app is in front, the anchor notification's title is "{group}: 5 min igjen".
  It is r13's one notification, with no sound and no new notice.

Recommended: **(b)**. It reuses r13's title and adds no new notification.

**Q5 - Hours per group (upstream's "games only 15-19").**
- (a) Not now: the rules decide when, for the whole phone.
- (b) Each group gets its own allowed window per weekday.

Recommended: **(a)**. It doubles the editor and the phone's boundaries. It can be added later without changing (a)'s
data.

**Visible costs, called out (decided by the standing rules, not questions):**
- **Fail closed without the access** (no insecure fallbacks; no adb beyond the QR). After the update, a phone with a
  group has that group's apps closed until the parent turns on App usage data on the phone during a pause. The PWA
  shows one orange line until then.
- **An unreadable record or a failed read closes the groups.** This is today's rule for screen time, now for groups
  too.
- **Vibb One reads every app's foreground events on Android 14** (the query can't be narrowed there). They stay in
  memory for one fold and are never stored or sent. On 15+ only the group's apps are asked for.

## 13. Part B - chosen apps outside the filter VPN

Upstream: server 08af9149b (`vpn_bypass_packages`, a card on the device page) and launcher 547c77e1d
(`addDisallowedApplication` for each, with the tunnel rebuilt when the installed set changes). The case is an app
that refuses to run under any VPN, typically a bank app.

**Ours, in short:**
- **The choice** is on the app's own page (design 127), in its Tilgang group: a row **"Filter"** with "På" / "Av",
  default På. It is offered only for an allowed app that the phone doesn't see as a browser. Off is the parent's
  choice: a quiet note behind the row's (i), never orange (CLAUDE.md, 2026-11-18). When design 127's step 3 lands, its
  Nett row ("Av") wins over this row.
- **The phone** puts each such app in `addDisallowedApplication` in every shape (FILTER, SINK, PASS), beside our
  package and rkpd (`KidVpnService.establish`, `KidVpnService.kt:303` [code]). It **never** adds them to lockdown's
  allow list (`MobileNetworkRuntime.writeAlwaysOn`, `lockdownAllowList` [code]). In lockdown an app outside the tunnel
  and off the allow list has no network (the reason rkpd is on that list [code]). So:
  - In the **filter** and **pass** shapes (lockdown off) the app uses Wi-Fi or mobile directly, without the DNS
    filter. That is the point.
  - In the **sink** (Bare Vibb on cellular, the kid's Mobildata av, the abroad and roaming cuts) it is cut like every
    other app. This corrects the review, which assumed it must join the allow list.
  - Design 115's hold rule is untouched: it reads Vibb One's own network, and every cut that isn't ours still cuts
    Vibb One too.
- **Never excluded:** a package that resolves a generic `https://` VIEW of a made-up host (a browser), Chrome's login
  role (design 119), and the `neverRestrict` packages (the system WebView, Play's core). The music app isn't a browser
  and may be excluded. A refused package is reported (`refused: browser`), and the PWA's row says "Kan ikke" behind
  its (i).
- **Rebuild:** a change in the set of installed excluded apps re-establishes the tunnel through the existing shape
  switch (design 120's ordering). There is no new tunnel path.
- **Data:** a new key `filter` in design 127's `device_app_denials` (0166). Its CHECK lists the keys, and SQLite
  can't widen it, so the next free migration rebuilds that table. No table references it, so the DROP touches nothing
  else. The policy's `app_permissions` rows gain `filter: "deny"` (`contracts/core/app_access.json`, both sides in one
  commit).
- **Tests:** a pure `vpnExcluded(shape, filterOff, installed, browsers)` (every shape excludes; lockdown's list never
  grows); the server's page and policy.
- **Emulator** (14 and 17): with the excluded app, a blocked test domain resolves in it and not in another app. Under
  Bare Vibb on the emulator's cellular it has no network. The kid's Mobildata av cuts it.
- **Android 14-17:** `addDisallowedApplication` (API 21) and `setAlwaysOnVpnPackage(.., lockdownAllowlist)` (API 29),
  nothing new.
- **Size:** M (server S, launcher S, gate).

**Words:**

| Where | nb | en |
|---|---|---|
| Row | Filter | Filter |
| Choices | På · Av | On · Off |
| (i) when Av | Appen bruker nettet uten Vibbs filter. Bare Vibb og Mobildata av stopper den fortsatt. | The app uses the network without Vibb's filter. Bare Vibb and Mobile data off still stop it. |
| Refused | Kan ikke: appen er en nettleser. | Can't: the app is a browser. |

**The owner's question for B:**

**QB1 - Build B now, or when an app needs it?** No app on the Jelly is known to refuse our VPN yet.
- (a) Build it now.
- (b) Keep it designed, and build it the day an app (Vipps, a bank) refuses the VPN.

Recommended: **(b)**. The review's own note: "it matters only if a real app refuses a VPN". The design is ready, and it
is a day's work then.
