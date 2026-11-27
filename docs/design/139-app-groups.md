# 139 - App groups with a shared daily limit, and apps outside the filter

Status: design, **revision 3**, 2026-11-27 (branch `app-groups-design`), read against `main` b777a957e. Not built.
**Part A is decided by the owner** (2026-11-27; §12): Q1 (a), Q2 (b), Q3 (b), Q4 (b), Q5 (a), all as recommended.
**Part B waits** (QB1, §13). Step 0's probe runs on emulators 14 and 16 (gate agents; results in
`docs/testing/emulator.md` §6ca/§6cb on branches `gate-1127`/`gate-1127b`), and its findings settle the fold's rows
for kill and PiP (§4.1, §8 row 1) before the build's step 2. Revision 1 was 07f876921; revision 2 (238ceaffb) took
the design QA (2 High, 5 Medium, 7 Low; §14 maps each one). PLAN's locked
list item 7, approved by the owner on 2026-11-20 ("jeg synes begge ideene fra upstream høres veldig bra ut ... legg inn
dette i planen vår som noe vi vil ha"), from `docs/review/2026-11-20-upstream-review.md`. Both directories.
- **Part A (app groups)** is the main design: one migration and one new contract file, plus changed rows in three
  existing ones.
- **Part B (chosen apps outside the filter VPN)** is a separate feature with no shared code. It is sketched in §13
  for later: the owner holds it until we know why Vipps works only during a full pause (QB1).

Android 14-17: every API used is a public one at or under minSdk 34 (§10).

Marks: **[code]** read in our code at b777a957e; **[sdk]** read in `platforms/android-37.0/data/api-versions.xml` on
this machine; **[hidden]** not in that file (a non-SDK constant or method, never used); **[d57]** design 57's AOSP
reading (2026-10-21, `docs/design/57-voip-call-screen-over-lock.md` §1); **[assumed]** not checked, step 0's probe or
the emulator gate checks it (§9).

## 0. Short answer

- **A group** is a named set of a phone's apps with **one daily limit for all of them together**, set per weekday
  ("Spill": Mon-Fri 1 t, Sat-Sun 2 t). A group belongs to one phone. An app is in at most one group.
- **The phone counts** the time the group's apps are **on screen**, from Android's usage events, folded per package.
  - It needs Android's "App usage data" access, which a device owner can't grant itself. So the access becomes a
    design 114 setup step, `usage_access`, needed while the phone has a group.
  - The count goes into **today's screen-time ledger**, so the day, the trusted clock, the midnight split and the
    unreadable record work as they do today.
- **At the limit** the group's apps are **suspended**, by the same plan a time rule uses (`EnforcementPlan.suspend`),
  but only those apps. If one of them is in front, Home comes forward.
  - A VoIP call in a group app keeps that one app until the call ends.
  - Every other app of the group is cut at once.
- **Without the access** a group with a limit today is closed (fail closed, the standing rule). The PWA shows design
  114's own orange line, "Data om appbruk er av".
- **How it fits what is there:**
  - The daily screen-time limit still counts the whole phone, and a used-up group doesn't stop the phone.
  - Rules lock as today, and a used-up group stays closed under a rule or bedtime even when it allows the app.
  - "Pause all restrictions" and the offline override lift groups.
  - The parent-code rule pause and the "+15 min" screen time don't.
  - **Extra time for a group** is a third lift target, `group`, on the one lift path. In v1 only the parent gives it
    (Q3).
- **PWA:**
  - Device › Time Rules gets an "Appgrupper" group, with one row per group and today's minutes.
  - Each group has an editor page: name, apps, the seven day limits.
  - The summary's Skjermtid sheet gets a "Hva" choice.
- **Part B (§13):** a per-app choice on the app page puts an app outside the DNS filter. The app is left out of the
  tunnel and **kept off lockdown's allow list**. So Bare Vibb, the kid's "Mobildata av" and the travel cuts still cut
  it; only the filter is skipped. It overturns design 95 D4.
- **Size:** A is L (server S+, launcher M+, a gate). B is M.

## 1. What upstream built, and what we take

Upstream (siesta5787, GPL-3.0): server e11ec64b7 (0.23.0) and launcher f7dea2083 (0.54.0), read with `gh api` for
this note.
- **The server:** the table `app_groups` is per device: `packages_json`, `daily_limit_minutes` (one number), one
  allowed-hours window, and a Pause/Allow override until a time. The phone's last usage report is kept on the row.
- **The launcher:** `AppGroups.kt` sums foreground time per package from `UsageEvents` (RESUMED to PAUSED, cut at
  screen off) over today plus a 3 h lookback.
  - A per-minute tick in the push service checks the limits and sends the kid home.
  - Without usage access **the limit is not enforced** (fail open). Hours and Pause still apply.

What we take: the idea, per-phone groups, and suspension (never hiding). What we do differently, as the review
recommended (`2026-11-20-upstream-review.md` row f7dea2083):
1. The server owns the groups, and they travel in `time_policy`.
2. There is no per-minute tick of our own. The screen-time tracker's existing checkpoint and one-shot timer do the
   timing, and nothing runs while the screen is off.
3. The access gets an on-phone path (design 114's setup step), never adb only.
4. Without the access the group is closed, never left unlimited.
5. Shared contract files.

Not in this design:
- Upstream's hours window: our rules decide when (Q5).
- Upstream's Pause/Allow: our lifts are the override.

No upstream code is copied. If the build adapts any of it, the file's header names siesta5787's kids-launcher-mdm
f7dea2083 (GPL-3.0) beside our own licence line. Nothing goes into the MIT directories (`ui/list/`, `apps/`). They only
get a one-line call into GPL code, as `PlatformRefusals.plannedOff` does today.

## 2. Today (`main` b777a957e)

- **Screen time** is one budget for the whole phone (`timerules/ScreenTime.kt`, `ScreenTimeTracker.kt` [code]).
  - It counts while the screen is on and unlocked, outside our free screens.
  - `update()` returns early when nothing counts (`if (!appInUse(context)) return`).
  - While counting it has a 60 s checkpoint, plus a one-shot timer at the budget's end (`budgetRunsOut`, remaining
    + 1 s).
  - `accrue` splits a stretch at local midnight.
  - The ledger (`ScreenTimeLedger`, CE prefs `time_rules_state`) follows the trusted day (`observe`/`observeTrusted`).
  - An unreadable ledger counts today as used up and marks the delivered budget lifts as applied.
- **The lock**: `decideTimeLock` gives the `LockReason`. `computeEnforcementPlan` (`server/EnforcementPlan.kt:178`
  [code]) suspends every controllable app outside:
  - `neverRestrict`;
  - the alarm apps;
  - the lock's usable apps;
  - the calendar sync apps.

  With the kiosk on, the pinned list shrinks to the usable apps. Apps suspended only by the lock stay visible (not
  hidden).
  - `timeRulesSet || budgetSet` turns on `DISALLOW_CONFIG_DATE_TIME` with automatic time and zone.
  - `budgetSet` turns on `DISALLOW_CREATE_WINDOWS` (`restrictCreateWindows`).
- **Home and the app list** hide every suspended app (`apps/AppFilter.kt:68`, `isMdmSuspended`, MIT). The one
  exception is the camera the PIN lock holds (`CameraLock.suspendedForLists`).
- **Lifts**: `time_lifts.target` is `CHECK (target IN ('rule','budget'))` (migration 0025 [code]), with `phone_key`
  (0147). `time_request_answers.lift_id` references it (0149). A budget lift adds minutes once per id to the day it is
  first seen.
- **"Be om mer tid"** (design 100 §8, r13) asks for screen time only, and **one request is open at a time**:
  - The phone keeps one `TimeRequestRecord`. `askStep` needs `budgetToday`.
  - The server's `time_answers::open_request` is the day's newest unanswered request, and a newer request supersedes
    it. `fits` takes `target == "budget"` for time.
  - There are three requests a day (`TIME_REQUESTS_A_DAY`).
- **A live call** for the lock windows is `WindowRelock.liveCall` [code]: `OngoingCalls.hasLiveCall`,
  `SelfUpdate.telecomInCall`, `VoipCalls.liveCall` (`pinnedPackage != null`) and the emergency flow. Screen time
  never takes an audio mode as a call (QA step 6 #1: "any app claiming a VoIP audio mode made every app free").
- **Usage access** is declared nowhere. `app_access_usage_access` ("Data om appbruk") names it only for *other* apps
  (design 68). Design 57 planned it for VoIP; it was superseded and never merged.
- **Setup steps** (design 114, `contracts/core/setup_steps.json`): each row has a `when`, the phone's pages, a
  `warn` line, and features (`page`, `anchor`). A page shows a missing step's line at the anchor of the first
  feature the parent has on (`setup_status::warnings`, `feature_on`).

## 3. Decisions

- **D1 - Per phone.** A group belongs to one phone (its own apps, its own kid's limits), as the bedtime does. There is
  no "for everyone" set and no per-phone switch (Q1).
- **D2 - One group per app.** Saving an app that is in another of the phone's groups is refused. If an app still ends
  up in two groups (a race, a restored backup), the phone counts it in both and fails closed.
- **D3 - Limits per weekday.** There are 7 entries, Monday first: minutes 0-1440, or `null` for no limit that day. `0`
  closes the group that day ("no games on school days"). This is the shape of `daily_budget_json`, with the same
  partial on the page.
- **D4 - What counts.** A group's time is the time **any** of its apps is **on screen** while the screen is on and the
  keyguard is down. The fold's rule is in §4.1.
  - Two of its apps side by side count once.
  - Sound in the background (an audiobook with the screen off) doesn't count.
  - The group's time is also screen time, because the screen is on.
- **D5 - The ledger is ours; usage events only say who was in front.** Each fold reads Android's stored events since
  the last folded point and adds each group's on-screen time to today's ledger, by the clock rules of §4.1. Usage
  events are stamped by the wall clock.
  - The wall clock is locked whenever the phone has a group (§4.2, `groupsSet`), so a kid can't set it back.
  - Each fold is capped by the elapsed realtime of its span.
- **D6 - Fail closed.** A group with a limit today is closed (every app of it suspended) when:
  - the access isn't granted;
  - the ledger is unreadable (its delivered group lifts are marked applied too, as budget lifts are today);
  - a read throws (then the span counts for **every** group, because we don't know which app was in front).

  `days` of the wrong size is a limit of 0, as a wrong-size budget is today.
- **D7 - At the limit.**
  - The group's apps are suspended, not hidden. They leave the kiosk's pinned list, so lock task clears their tasks.
  - If one was in front, Home comes forward (Q2). The device owner's activity start is exempt from the
    background-start limits, as the time-rule screen's start already relies on.
  - **A call never ends for it.** Only the VoIP call's own package (`VoipCalls.pinnedPackage`) is held back while it
    is in a closing group. Every other app of the group is cut at once.
  - When `pinnedPackage` changes (in `VoipCalls`' existing `oldPinned != pinnedPackage` branch), the phone posts
    `TimeRulesRuntime.recheck`, which cuts it.
  - The audio mode is never a call here, because a voice-chat game sets `MODE_IN_COMMUNICATION` (screen time's QA
    step 6 #1). A cellular call runs in the dialer, which no group can hold.
- **D8 - Stricter wins.**
  - A used-up group's apps stay suspended under a rule or **bedtime** that allows them, and under the used-up
    budget's messaging apps: `lockUsableApps - groupClosedApps`. The time-rule screen offers no row for them.
  - A group never suspends `neverRestrict` (our package, the system dialer, the recents provider, SOS, WebView, the
    input methods), the alarm apps or **Vibb music** (`ACCESS_NEVER`'s `me.vibb.music`), whatever the group lists.
  - The server doesn't offer any of them: it offers the phone's reported, allowed apps without the Vibb apps.
- **D9 - Lifts.**
  - "Pause all restrictions" and the offline parent override lift groups (the standing rule: they lift everything).
  - The parent-code rule pause (design 98), a rule lift, "Slå på regel" and the "+N min" screen time leave groups as
    they are.
  - Extra time for a group is a `time_lifts` row with target `group` (§5.1). The phone adds it once per id to the
    day it is first seen, like a budget lift.
- **D10 - Counted during a pause, not during setup.** Groups are lifted during the pause and the override, but their
  time still counts, so the parent sees it and the limit holds after. During design 114's setup hold
  (`RestrictionsPause.holdActive()`) the fold and the timer are off: the hold's time is setup, not use.
- **D11 - Data minimal, one query path.** On every version the phone calls `queryEvents(begin, end)`, which returns
  every app's events [d57].
  - They are read in memory for one fold and dropped, never stored or sent.
  - The report sends minutes per group, never per app and never which app.
  - The 35+ narrowed query (`UsageEventsQuery`, API 35 [sdk]) is not in v1. Note for later: it would have to ask for
    the device events Android reports under the package `android` (screen, keyguard, shutdown) and for PAUSED too.

## 4. The phone

### 4.1 Counting (pure `timerules/AppGroups.kt`, glue in `ScreenTimeTracker`)

**The fold is per package.** `groupScreenMs(events, from, to, carry, groups) -> (perGroupSpans, carryOut)` is pure.

- `events` are `(timeMs, package, kind)`. The kinds come from the public constants (API 28/29 [sdk]):
  - ACTIVITY_RESUMED, ACTIVITY_PAUSED, ACTIVITY_STOPPED;
  - SCREEN_INTERACTIVE, SCREEN_NON_INTERACTIVE;
  - KEYGUARD_SHOWN, KEYGUARD_HIDDEN;
  - DEVICE_SHUTDOWN, DEVICE_STARTUP.

  `ACTIVITY_DESTROYED` (24) and `Event.getInstanceId` are [hidden] and never used. The class name isn't a key.
- Each package is **off**, **on**, or **on-paused**:
  - RESUMED makes it on.
  - PAUSED makes it on-paused, still counting: a visible but paused window, such as split screen, or picture-in-picture
    with the kiosk off (lock task refuses PiP [d57]).
  - STOPPED makes it off **only if it is on-paused**. If a RESUMED of the package came after its last PAUSED, the
    STOPPED is an older activity's and is ignored. This covers a same-class relaunch (P1, R2, S1) and moving from
    activity A to B inside one app.
  - Our own package's RESUMED turns every package that is **on** (not on-paused) off. That is the case of an app that
    died in front with no PAUSED (kill -9, `am crash`). A PiP window, being on-paused, goes on.
  - Accepted: an app in split screen beside Vibb One is undercounted.
- **Screen off, keyguard shown and device shutdown/startup** turn everything off. A group's time is the union of its
  packages' on spans, so two of its apps side by side count once.
- **`carry`** is each package's state at `from`.
- **The clocks** (QA M5):
  - The query is in **wall time**: `queryEvents(fromWall, nowWall)`.
  - The fold's spans are mapped to **elapsed realtime** through the wall/elapsed pair measured at each end of the read,
    and capped to the elapsed length of `[from, to]`.
  - The sum is attributed to the **trusted day** with `accrue`'s midnight split: the part after the trusted local
    midnight goes to the new day.
- **The threads:**
  - The read is a binder call, so it runs on the IO executor. Only one read is in flight (single-flight).
  - Its result is applied on the main thread, and `from` moves to the read's end **only when the result is applied**
    to the ledger, saved with it. A failed or dropped read leaves `from` where it was.
- **The last folded point** is persisted in the ledger record: wall time, elapsed time, boot count, and `carry`. After
  a process restart the next fold reads the gap from that point out of Android's stored events.
  - In the same boot, the elapsed cap holds.
  - After a reboot, DEVICE_SHUTDOWN/STARTUP cut the spans. The cap is the trusted time between the point and now: 0 while
    the time is only carried and not yet checked (§2's carried anchor).
- **When it runs:** while the policy has a group, the screen is interactive and the keyguard is down
  (`PowerManager.isInteractive`, `KeyguardManager.isKeyguardLocked`), and design 114's setup hold isn't on. This gate
  is its own and **sits before `update()`'s `if (!appInUse) return`**: our free screens stop the screen-time count,
  not the groups'.
  - It folds on the tracker's events (screen on/off, user present, our activities' resume and pause, every re-check)
    and at the 60 s checkpoint, which keeps running for groups when screen time doesn't count.
  - At screen off it runs one last fold.
- **The timer:** next to `budgetRunsOut`, `groupRunsOut` fires at `min(remaining of the open groups) + 1 s`, under
  the same gate.
  - When it fires it folds again. A group used up starts `TimeRulesRuntime.recheck`; otherwise the timer is armed
    again with the new minimum.
  - Time can't grow faster than the clock, so it never fires late.
- **The ledger** (`ScreenTimeLedger`) gains:
  - `groupUsedMs: Map<Long, Long>`;
  - `groupExtraMinutes: Map<Long, Int>`;
  - `appliedGroupLifts: List<Long>` (at most 50);
  - the last folded point.

  A new day clears the two maps in the same branches that clear `usedMs` and `extraMinutes`. `PreviousDay` keeps them
  too, so an undone carried reset brings the groups back. `unreadableLedger` closes every group with a limit today and
  marks the delivered group lifts applied.
- **The access:** `timerules/UsageAccess.kt` reads `granted: Boolean?` the way the service decides it [d57]:
  `unsafeCheckOpNoThrow(OPSTR_GET_USAGE_STATS)` is `MODE_ALLOWED`, or `MODE_DEFAULT` with `PACKAGE_USAGE_STATS`
  granted. A throw gives `null`, which counts as not granted for D6.
  - It is read at each fold and each report.
  - A change (`startWatchingMode`) posts a re-check and a sync.
- **The manifest** declares `<uses-permission android:name="android.permission.PACKAGE_USAGE_STATS"
  tools:ignore="ProtectedPermissions"/>`. Without it Vibb One isn't on Android's page, and an `appops set` is reset at
  once [d57]. A manifest test keeps it there.

### 4.2 Deciding and enforcing

- `TimeRulesRuntime.snapshot` gains `groups: List<GroupState>`: id, name, limit today, used, extra, and
  open / used up / not today / no access. The pure `groupStates(policy, ledger, access, overrideOrPause, dow)`
  computes it.
- `computeEnforcementPlan` gets two new inputs (QA L3), kept apart from `timeRulesSet`/`budgetSet`:
  - **`groupClosedApps: Set<String>`**: these apps go into `suspend` (not `hide`), out of the kiosk's pinned list and
    out of `lockUsableApps` (D8). The plan also gives `groupOnly`, the apps suspended by a group and nothing else
    (QA L6).
  - **`groupsSet: Boolean`** (the policy has a group): it turns on two flags.
    - `DISALLOW_CONFIG_DATE_TIME` with automatic time and zone (`restrictDateTime`), because the fold reads
      wall-clock events.
    - `DISALLOW_CREATE_WINDOWS` (`restrictCreateWindows`), so an overlay of a closed app can't stay on screen. This
      is a visible cost on a phone with groups but no budget (§12).
- `TimeRuleAlarm`'s boundaries include midnight while a group has a limit, because the day's reset opens the groups.
  A group lift arrives in an accepted policy, which runs a re-check.
- **At the cut** (D7): after the apply, if a package that is on in the fold's `carry` belongs to a group that just
  closed and isn't the VoIP call's, the phone starts Home.

### 4.3 What the kid sees (Q2 decides; this is (b), the recommendation)

- **Home and the app list** keep an app of `groupOnly`, dimmed. The rule is GPL, in `timerules/`; `AppFilter` calls
  it, as it calls `PlatformRefusals.plannedOff`. An app also suspended by anything else (a rule's lock, the
  allowlist, SMS) is hidden as today.
- A tap opens the group's sheet in Home's kid-sheet style (swipe down closes it, as every kid sheet since
  2026-11-17):
  - the group's name;
  - "Brukt opp i dag", or "Ikke i dag" when today's limit is 0, or "Kan ikke telle nå" while the access is missing.

  In v1 (Q3 (b)) the sheet has no ask.
- **Before the cut (Q4, recommended (b)):** in the last 5 minutes, while one of the group's apps was on at the last
  fold, the anchor notification's title is "{group}: 5 min igjen". This is r13's one notification, with no sound and
  no second notice. The screen-time title returns after.
- The time-rule screen and the glance stay as they are.

### 4.4 The report

`time_state` gains:
- `usage_access` (bool, left out when unreadable);
- `groups: [{id, used_minutes, extra_minutes, closed}]`. `closed` is true when the group is used up, closed by a
  limit of `0`, or closed by D6.

The keys are pinned in `time_status_report.json`.

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
```

**One lift path (QA M2):** `time_lifts` is rebuilt with target `'group'` and two new columns:
- `group_id INTEGER REFERENCES app_groups(id) ON DELETE SET NULL`;
- `group_name TEXT`, Activity's words once the group is gone.

Its child `time_request_answers` (`lift_id`) is rebuilt with it, **the 0099 way**, inside sqlx's transaction with
foreign keys on:
1. Drop the three indexes (`idx_time_lifts_device`, `time_lifts_phone_key`, `idx_time_request_answers_lift`).
2. Rename `time_request_answers` to `old_time_request_answers`, then `time_lifts` to `old_time_lifts` (the child's
   `REFERENCES` follows the rename).
3. Create both new tables with today's columns plus the two above, and the CHECK `target IN ('rule','budget','group')`.
4. Copy the rows with their ids.
5. Drop `old_time_request_answers` **before** `old_time_lifts`, so no `ON DELETE SET NULL` runs on a copied answer.
6. Make the indexes again under their old names.

`sqlite_sequence` keeps the highest id, so a lift id is never reused. `backup_schema` gets `app_groups`, and the
rebuilt tables keep their shape plus the two columns.

### 5.2 Wire (contract `contracts/core/app_groups.json`, new; README row)

```json
"time_policy": {
  "groups": [{"id": 3, "name": "Spill", "apps": ["org.example.game"], "days": [60, 60, 60, 60, 60, 120, 120]}],
  "lifts": [{"id": 9, "target": "group", "rule_id": null, "group_id": 3, "minutes": 15,
             "expires_at_ms": 1793077800000, "by": "Mamma", "at_ms": 1793034600000}]
}
```

- `groups` is always sent (`[]` when empty), and the launcher reads an absent one as `[]`.
- A group lift is an ordinary entry of `lifts`, delivered 12 h like a budget lift. An older launcher ignores the
  unknown target. The server ships first, and the phones get no compatibility code (CLAUDE.md).
- A stored group that doesn't read is a 500 (`PolicyError::CorruptTimePolicy`), never fewer groups. This is the rule
  for rules.
- `policy_json_keys_snapshot` and `PolicyResponseCompatTest` pin the keys.
- The status keys of §4.4 go into `TIME_STATE_FIELDS`, with one new field kind for the array: at most 50 objects of
  the four known keys, anything else dropped.
- The server's readers of `time_lifts` (Activity, the summary's running lifts, retention, `fits`) are changed in the
  same commit so that each one either words `group` or leaves it out.
- `fits` never takes a group lift as the answer to a screen-time request.
- `app_groups.json` holds a policy and a report for a group, and `time_status_report.json` gets the new keys. Both
  sides change in one commit. `time_request.json` is unchanged in v1.

### 5.3 Routes and pages

- **Routes** (QA L5), in `handlers::app_groups`, template `device_group.html`, tab Devices:
  - `GET/POST /devices/{id}/time/groups/new`;
  - `GET/POST /devices/{id}/time/groups/{gid}`;
  - `POST /devices/{id}/time/groups/{gid}/delete`.
- **The editor** follows the Bedtime page: Save in the sticky bar with its flash, then:
  - **Navn**;
  - **Apper**: the checkbox list of `partials/exceptions.html`, fed by `scope_apps` for this phone (its reported,
    allowed apps, without Vibb One and Vibb music). Apps in another of its groups are not offered. A ticked app that
    is no longer allowed or installed keeps its note and stays ticked;
  - **Grense**: `partials/budget_rows.html`;
  - Slett gruppe… as a `<details>` confirm outside the form.
- **A refusal** renders the page again (400) as typed, with the reason under the field (`autofocus`, `aria-invalid`)
  and in the bar.
- **Where each form returns** (no save jumps to the top):

  | Action | Returns to |
  |---|---|
  | Save | `?saved=1#group` |
  | Add | the new group's page, `?saved=added#group` |
  | Delete | `/devices/{id}/time?notice=group_deleted#groups` |
  | A group deleted in another tab | `#groups` with `notice=gone`, nothing written |

  `every_form_on_the_time_pages_returns_to_its_group` covers them.
- **Device › Time Rules** gets the group **Appgrupper** (`#groups`) after Daily Limit:
  - one row per group to its editor: the name over "{days} · {n} apper" (`budget_summary` plus the count), with
    today's state on the right as a quiet value;
  - "Legg til gruppe" as a row action;
  - the heading's (i);
  - design 114's warning line for the page `time` at the anchor `groups` (below);
  - "Kan ikke leses" in `text-alarm` for a group that doesn't read.
- **The summary's Quick actions:** while the phone has a group, the Skjermtid sheet gets a "Hva" segmented choice:
  "Skjermtid", then each group's name.
  - It posts `target=group&group={gid}` to `/devices/{id}/lifts`, which writes a `time_lifts` row with target
    `group`.
  - A group of another phone is a 404.
- **Activity and log:**
  - Activity has "{group}: 15 min ekstra" with the giver.
  - The security log gets `app_group_saved` and `app_group_deleted` with the group's name as a parameter. A group lift
    is logged as `time_lift`, worded with the group.
  - Each one nudges the phone.
- **Setup (design 114; QA L5):** a step row `usage_access`:

  | Field | Value |
  |---|---|
  | need | required |
  | read_by | phone |
  | when | `app_groups` (the policy has a group with a limit on some day) |
  | summary | count |
  | level | action |
  | features | `["app_groups"]` |
  | phone_page | `android.settings.USAGE_ACCESS_SETTINGS` (with `package:`, then without) |
  | names | "Data om appbruk" / "App usage data" |
  | warn | `setup.warn.usage_access`: "Data om appbruk er av" / "App usage data is off" |

  - The contract's `features` gains `{"key": "app_groups", "page": "time", "anchor": "groups"}`.
  - `feature_on("app_groups")` is true while a group has a limit on some day.
  - Device › Time Rules calls `setup_status::warnings(.., "time", ..)`, so the orange line is 114's own, linking to
    the step on Device › Oppsett.
  - The phone's reading is `UsageAccess.granted`. The new `when` is read from the policy on both sides
    (`SetupInputs`, `setup_status`).
  - The flow, the count and Attention come from 114 as they are. No new Attention kind.

## 6. Words (nb / en; CLAUDE.md "Words in the UI")

| Where | nb | en |
|---|---|---|
| PWA group heading | Appgrupper | App groups |
| (i) at the heading | Appene i en gruppe deler én grense. Telefonen teller tiden de er på skjermen. | The apps in a group share one limit. The phone counts the time they are on screen. |
| Row sub-line | Man–fre 1 t · lør–søn 2 t · 3 apper | Mon–Fri 1 h · Sat–Sun 2 h · 3 apps |
| Row value | 40 av 60 min · Brukt opp · Ingen grense | 40 of 60 min · Used up · No limit |
| Add | Legg til gruppe | Add group |
| Orange line (114's warn) | Data om appbruk er av | App usage data is off |
| Setup step's (i) addition | Appgruppenes apper er stengt til den er på. | The groups' apps are closed until it is on. |
| Editor title | Ny gruppe / {name} | New group / {name} |
| Fields | Navn · Apper · Grense | Name · Apps · Limit |
| Delete | Slett gruppe… · Slett | Delete group… · Delete |
| Refusals | Gi gruppen et navn · Velg minst én app · {app} er i {group} · Høyst 50 apper | Give the group a name · Choose at least one app · {app} is in {group} · At most 50 apps |
| Deleted | Gruppen er slettet. | The group is deleted. |
| Quick sheet | Hva · Skjermtid · {group} | What · Screen time · {group} |
| Activity | {group}: 15 min ekstra | {group}: 15 min extra |
| Setup row | Data om appbruk | App usage data |
| Kid's sheet | Brukt opp i dag · Ikke i dag · Kan ikke telle nå | Used up today · Not today · Can't count now |
| Kid's notice (Q4 (b)) | {group}: 5 min igjen | {group}: 5 min left |

## 7. Tests (lean)

- **Launcher, pure:**
  - `AppGroupsTest`, the fold:
    - RESUMED, PAUSED, STOPPED;
    - **a same-class relaunch** (P1, R2, S1 keeps it on) and A to B inside one app;
    - **an app killed in front** (RESUMED, then our RESUMED with no PAUSED/STOPPED);
    - **PiP across screen off and on** (on-paused, cut at screen off; what follows by step 0's finding);
    - two apps of one group side by side counted once;
    - screen off, keyguard shown and DEVICE_SHUTDOWN/STARTUP cut;
    - `carry` across a read's start;
    - the elapsed cap, and the midnight split onto the trusted day;
    - a dropped read leaves `from`.
  - `AppGroupsTest`, `groupStates`: weekday limit, `0`, `null`, a lift applied once per id, no access, an unreadable
    ledger closing the groups and marking the lifts applied, the override and the pause lifting but still counting,
    the setup hold not folding.
  - `ScreenTimeTest`: a new day clears groups; a carried reset brings them back; the last folded point persists.
  - `EnforcementPlanTest`:
    - closed group apps are suspended, not hidden, out of the pins and out of the usable apps (bedtime included);
    - `groupOnly` excludes an app the lock also suspends;
    - `neverRestrict`, the alarm apps and Vibb music are untouched;
    - `groupsSet` sets the date/time lock and `restrictCreateWindows`.
  - The cut: the VoIP call's package is held back and the rest is cut, given a call.
  - The contract tests over `app_groups.json` and `time_status_report.json`.
  - A manifest test for `PACKAGE_USAGE_STATS`. `SetupStepsTest` picks up the new row and feature from the contract.
- **Server:** `tests::app_groups`:
  - the pages (save, add, refuse in place, delete, the return table, D2 overlap, another phone's group 404);
  - the policy against the contract;
  - a group lift, its Activity line, and `fits` refusing it for screen time;
  - **the migration on a database with lifts and answers**: every row, id and `lift_id` kept, the sequence kept;
  - `TIME_STATE_FIELDS` keeping only the known keys;
  - `policy_json_keys_snapshot`;
  - `setup_status` with the new row, and the warning on Device › Time Rules.

## 8. Emulator rows (a new §6 section at build time; Android 14 and 16 - the 17 images don't run here, so 17 is unchecked)

Test app: any allowed F-Droid app, plus a second one as the group's other app. Group "Test", limit 2 min today.

1. **Step 0 (before the build's step 2):**
   - Vibb One is on Android's App usage data page during a pause.
   - On 15+, check whether the switch is greyed (restricted settings, ECM [d57]) for a phone set up by the QR and
     self-updated. If it is, the step's (i) gets "trykk, OK, Appinfo › ⋮ › Tillat begrensede innstillinger".
   - Grant it. `cmd appops get` says allow.
   - Dump the events (`queryEvents` in a debug log) for:
     - an ordinary open and close;
     - **a same-class relaunch**;
     - **`adb shell kill -9` and `am crash` of the front app**;
     - **PiP across screen off and on** (kiosk off) - does any event mark it visible again after the screen comes on?
     - split screen.
   - Note what a visible activity does when its package is suspended with the kiosk off.
2. Use the app 2 min. It closes, Home comes forward, and the tile is dimmed. A tap opens the sheet.
3. The two apps used in turn count against one limit. Screen off and the PIN lock count nothing.
4. The parent gives +15 for the group from the Skjermtid sheet. The app opens.
5. Revoke the access: the group closes, Device › Time Rules shows "Data om appbruk er av", and the kid's sheet says
   "Kan ikke telle nå". Grant it again: the group opens.
6. "Pause all restrictions": the group opens and its time still counts. End the pause: it closes again. The setup
   hold counts nothing.
7. A rule, and bedtime, that allows the test app, with the group used up: the app stays closed, and the time-rule
   screen has no row for it.
8. **A call:**
   - a VoIP call in a group app at the limit keeps that app until the call ends, then it closes;
   - the group's other app closes at once;
   - a voice-chat audio mode alone holds nothing.
9. Kiosk off: the cut brings Home. Picture-in-picture of a group app counts.
10. Q4 (b): in the last 5 minutes the anchor notification's title names the group.
11. Kill the launcher's process mid-use. After the restart the gap is counted from Android's stored events.

## 9. Steps

0. **Probe** (emulator 14 and 16, with the owner's OK for the emulator): row 1 of §8, on a throwaway branch that only
   declares the permission. Its findings settle the fold's rows for kill and PiP.
1. **Server and contract** (one commit with the launcher's contract tests): the migration and its rebuild, the pages,
   the policy, the report fields, the group lift, the setup row and feature.
2. **Launcher:** the manifest, `UsageAccess`, the fold, the clocks and the ledger, the plan's two inputs, the timer,
   the cut with the call rule, and the report.
3. **Launcher:** the dimmed tiles, the sheet, and Q4's notice.
4. Code QA, then the emulator gate (§8) on 14 and 16. The release ships the server first.

Docs with the build:
- `docs/notes/server/time-rules.md` and `docs/notes/launcher/time-rules.md` (a section each);
- `contracts/README.md`;
- `docs/testing/emulator.md`: the new section, and §4's `appops set ... GET_USAGE_STATS allow` as a shortcut, never
  the only way;
- `docs/setup/`: one line under the setup steps.

## 10. Android 14-17

- **APIs:**
  - `queryEvents(long, long)` is API 21.
  - `ACTIVITY_RESUMED/PAUSED/STOPPED` and `DEVICE_SHUTDOWN/STARTUP` are 29.
  - `SCREEN_INTERACTIVE/NON_INTERACTIVE` and `KEYGUARD_SHOWN/HIDDEN` are 28 [sdk].
  - `ACTIVITY_DESTROYED` and `getInstanceId` are [hidden] and not used.

  No call needs a `SDK_INT` gate in v1 (D11). Lint's NewApi stays on.
- **The access** is an app-op that a device owner can't grant: `hasQueryPermission` has no device-owner exemption on
  14-16 [d57]. 17 is unchecked.
- **Restricted settings (ECM)** exist on 15+ only [d57]. Step 0 checks, on 16, whether a QR-provisioned, self-updated
  Vibb One is guarded.
- **The settings page:** Android 16 names it "App usage data" (our design 68 note). Step 0 checks on 14 and 16 that
  `ACTION_USAGE_ACCESS_SETTINGS` with `package:` opens the app's own page. 17 is unchecked.
- **Background activity starts:** the device owner is exempt on 14-17, which the time-rule screen's start relies on
  today. Nothing new.
- **Suspension and lock task** behave as they do for a rule today (design 105's unpin clears the task). Kiosk off is
  checked on 14 and 16 by row 9.
- **Checked on:** nothing yet. Step 0 and the gate run on 14 and 16, and 17 stays unchecked until its image runs.
  The Jelly (16) gets the owner's row after the release.

## 11. Not chosen

- **`UsageStatsManager.registerAppUsageObserver`** would call back exactly at a group's limit, and the service lets a
  device owner use it (`hasObserverPermission` [d57]). But it is a `@SystemApi` ([hidden] for us), so it would mean
  reflection, and it counts from its registration, so we would still need the events. Not worth a hidden-API path.
- **The 35+ narrowed query:** it gives a second path for one privacy gain, and the 14 path must be right anyway
  (D11).
- **Counting from our own launches** (we start most apps from Home) misses Recents, notifications and app-to-app
  starts.
- **A per-group "Stopp nå"** (upstream's Pause): "Slå på regel" covers the whole phone. It can come later if asked.
- **Hours per group** (upstream's window): Q5.

## 12. The owner's questions - decided 2026-11-27

| Q | Decision |
|---|---|
| Q1 | (a) Per phone: each phone's own groups and limits. |
| Q2 | (b) Dimmed, and a tap shows the group's name and "Brukt opp i dag". |
| Q3 | (b) Extra time from the parent only in v1. The kid's ask is a later round. |
| Q4 | (b) Yes, the 5-minute warning in the anchor notification's title. |
| Q5 | (a) No hours per group. |
| QB1 | Part B waits (§13). |

The questions as they were put, kept for the record:

**Q1 - Groups per phone, or for everyone?**
- (a) Per phone: each phone's own groups and limits, picked from its own apps.
- (b) Everyone's groups with a switch per phone, as the rules work.

Recommended: **(a)**. Children differ in age and apps, and it is half the PWA. The cost: a group wanted on two phones
is made twice.

**Q2 - What the kid sees when a group is used up (a visible cost).**
- (a) The group's apps leave Home and the app list until tomorrow or until extra time. This is today's rule for any
  suspended app, with nothing new to build, but the grid changes under the kid's finger and nothing says why.
- (b) They stay dimmed, and a tap shows the group's name and "Brukt opp i dag".

Recommended: **(b)**. It costs a GPL hook in `AppFilter`, a dim state on the tile and one small kid sheet.

**Q3 - Extra time for a group.**
- (a) The parent gives it, and the kid can ask for it from the group's sheet.
- (b) **The parent only** (the Skjermtid sheet's "Hva"), for v1.
- (c) None: the parent edits the limit.

Recommended: **(b) for v1**, with (a) as a later round. The kid's ask isn't small: today exactly one request is open at
a time. (a) needs:
- the request to carry its target (`budget` or `group` + `group_id`);
- the phone's record, `requestState` and `askStep` per target (`askStep` takes the group's limit today in place of
  `budgetToday`);
- the server's `open_request` and supersede per target, and `fits` taking only a lift of the same target and group;
- a choice of whether the open slot is shared (a group request waits behind a screen-time one) or one per target, with
  the three-a-day cap shared.

That is about a third of the launcher work again, plus the server's answer path and `time_request.json`. (b) needs
only the lift target, which the rebuild in §5.1 gives anyway. (c) means editing the limit and remembering to put it
back.

**Q4 - A warning before the cut (a visible cost).**
- (a) None: the app closes mid-game.
- (b) In the last 5 minutes, while a group app is in front, the anchor notification's title is "{group}: 5 min igjen".
  It is r13's one notification, with no sound and no new notice.

Recommended: **(b)**. It reuses r13's title and adds no new notification.

**Q5 - Hours per group (upstream's "games only 15-19").**
- (a) Not now: the rules decide when, for the whole phone.
- (b) Each group gets its own allowed window per weekday.

Recommended: **(a)**. (b) doubles the editor and the phone's boundaries, and it can be added later without changing
(a)'s data.

**Visible costs, called out (decided by the standing rules, not questions):**
- **Fail closed without the access** (no insecure fallbacks; no adb beyond the QR). After the update, a phone with a
  group has that group's apps closed until the parent turns on App usage data on the phone during a pause. The PWA
  shows 114's orange line "Data om appbruk er av" until then.
- **An unreadable record or a failed read closes the groups.** This is today's rule for screen time, now for groups
  too.
- **No overlays on a phone with groups**, even with no screen-time limit. `DISALLOW_CREATE_WINDOWS` comes with the
  first group: no chat heads, floating bubbles or other apps' overlay windows. Today only a budget sets it.
- **Vibb One reads every app's foreground events** (the one query path, on every version). They stay in memory for
  one fold and are never stored or sent; only minutes per group leave the phone.
- **Undercount beside Vibb One:** a group app in split screen with Vibb One stops counting when Vibb One resumes. This
  is the price of ending the span of an app that died in front.

## 13. Part B - chosen apps outside the filter VPN (later; the owner's hold, 2026-11-27)

Upstream: server 08af9149b (`vpn_bypass_packages`, a card on the device page) and launcher 547c77e1d
(`addDisallowedApplication` for each, with the tunnel rebuilt when the installed set changes). The case is an app
that refuses to run under any VPN, typically a bank app.

**This overturns design 95 D4** ("no per-app exception, not even the music app"), by the owner's choice of
2026-11-20.

**Ours, in short:**
- **The choice** is on the app's own page (design 127), in its Tilgang group: a row **"Filter"** with "På" / "Av",
  default På.
  - It is offered only for an allowed app that the phone doesn't see as a browser.
  - Off is the parent's choice: a quiet note behind the row's (i), never orange (CLAUDE.md, 2026-11-18).
  - When design 127's step 3 lands, its Nett row ("Av") wins over this row.
- **The phone** puts each such app in `addDisallowedApplication` in every shape (FILTER, SINK, PASS), beside our
  package and rkpd (`KidVpnService.establish`, `KidVpnService.kt:303` [code]).
  - It **never** adds them to lockdown's allow list (`MobileNetworkRuntime.writeAlwaysOn`, `lockdownAllowList`
    [code]). In lockdown an app outside the tunnel and off the allow list has no network; that is why rkpd is on the
    list [code].
  - In the **filter** and **pass** shapes (lockdown off), the app uses Wi-Fi or mobile directly, without the DNS
    filter. That is the point.
  - In the **sink** (Bare Vibb on cellular, the kid's Mobildata av, the abroad and roaming cuts), it is cut like every
    other app. This corrects the review, which assumed it must join the allow list.
  - Design 115's hold rule is untouched: it reads Vibb One's own network, and every cut that isn't ours still cuts
    Vibb One too.
- **May still refuse:** an app that scans the interfaces for `tun0` (`NetworkInterface`) still sees our tunnel, even
  though Android reports no VPN on its own network. The (i) doesn't promise more than "outside the filter".
- **Never excluded:**
  - a package that resolves a generic `https://` VIEW of a made-up host (a browser);
  - Chrome's login role (design 119);
  - the `neverRestrict` packages (the system WebView, Play's core).

  Vibb music isn't a browser and may be excluded. A refused package is reported (`refused: browser`), and the PWA's
  row says "Kan ikke" behind its (i).
- **Rebuild:** a change in the set of installed excluded apps re-establishes the tunnel through the existing shape
  switch (design 120's ordering). There is no new tunnel path.
- **Data:** a new key `filter` in design 127's `device_app_denials` (0166).
  - Its CHECK lists the keys, and SQLite can't widen it, so the next free migration rebuilds that table. No table
    references it, so the DROP touches nothing else.
  - The policy's `app_permissions` rows gain `filter: "deny"` (`contracts/core/app_access.json`, both sides in one
    commit).
- **Tests:** a pure `vpnExcluded(shape, filterOff, installed, browsers)` (every shape excludes; lockdown's list never
  grows); the server's page and policy.
- **Emulator** (14 and 16):
  - With the app excluded, a blocked test domain resolves in it and not in another app.
  - Under Bare Vibb on the emulator's cellular it has no network, and the kid's Mobildata av cuts it.
  - **A connection the app already has open when the sink starts** is cut too (or the row says how long it lives).
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

**The owner's decision for B (2026-11-27): it waits.** First we learn why Vipps works only during a full pause: it
fails even with Chrome fully allowed, so the cause may not be the VPN at all. When Part B is built, an app outside the
filter **must still be cut by "Bare Vibb" and by the kid's "Mobildata av"**: it stays off lockdown's allow list, as
above, and the gate's rows check both.

The question as it was put:

**QB1 - Build B now, or when an app needs it?** No app on the Jelly is known to refuse our VPN yet.
- (a) Build it now.
- (b) Keep it designed, and build it the day an app (Vipps, a bank) refuses the VPN.

Recommended: **(b)**. The review's own note: "it matters only if a real app refuses a VPN". The design is ready, and it
is a day's work then.

## 14. Revision 2 (the design QA of 07f876921)

| QA | What changed |
|---|---|
| H1 | §4.1: the fold is per package (on, on-paused, off). STOPPED ends a span only when no RESUMED came after the last PAUSED. Our own RESUMED ends spans that are on (an app killed in front). DEVICE_SHUTDOWN/STARTUP cut too. DESTROYED and `getInstanceId` are marked [hidden]. Step 0 and `AppGroupsTest` rows were added for the relaunch, kill -9/`am crash` and PiP across screen off/on. |
| H2 | D7: the audio mode is no longer a call. Only `VoipCalls.pinnedPackage` is held back, and the rest is cut at once. `pinnedPackage`'s change posts the re-check. Row 8 was rewritten. |
| M1 | Q3 now spells out the one-open-request model and the per-target work, and recommends (b), parent only, for v1. The kid's sheet has no ask in v1, and `time_request.json` is unchanged. |
| M2 | §5.1: one lift path. `time_lifts` and `time_request_answers` are rebuilt the 0099 way with target `group`, `group_id` and `group_name`. `app_group_lifts` and `group_lifts` were dropped. |
| M3 | D11: one query path in v1. The 35+ narrowed query moved to §11, with the note on the `android` device events and PAUSED. |
| M4 | The gate and step 0 run on 14 and 16. 17 is unchecked where §10 had claimed it. |
| M5 | §4.1: the wall-time query mapped to elapsed time, the midnight split onto the trusted day, a single-flight read, `from` moved only when applied, and the last folded point persisted and read back after a restart. |
| L1 | §4.1: the group gate (interactive, keyguard down, no setup hold) sits before `update()`'s `appInUse` return. |
| L2 | D8: Vibb music is never in a group, and bedtime's exempt apps are named. |
| L3 | §4.2: `groupsSet` and `groupClosedApps` are their own inputs, and the two flags are named. `DISALLOW_CREATE_WINDOWS` without a budget is listed as a visible cost. |
| L4 | D6 and §4.1: `unreadableLedger` marks the delivered group lifts applied. |
| L5 | §5.3: the orange line is 114's `setup.warn.usage_access` through the feature `app_groups` (page `time`, anchor `groups`, `feature_on`). The step's `need`, `summary` and `level` are set. Routes are under `/devices/{id}/time/groups/…`. |
| L6 | §4.2-§4.3: only `groupOnly` apps are dimmed, and an app also suspended by anything else is hidden. |
| L7 | §13: it says that it overturns design 95 D4, adds the open-connection row, and notes `tun0` scanners. QB1 stays (b). |

## 15. Revision 3 (the owner's answers, 2026-11-27)

- §12: the decisions are recorded (Q1 (a), Q2 (b), Q3 (b), Q4 (b), Q5 (a): all as recommended). The design already
  builds exactly these, so no section changed beyond the status and §12.
- §13: Part B is held until we know why Vipps works only during a full pause. The must-keep rule is restated: still cut
  by "Bare Vibb" and "Mobildata av".
- The status names step 0's probe (emulators 14 and 16, gate agents, `docs/testing/emulator.md` §6ca/§6cb on
  `gate-1127`/`gate-1127b`). The build's step 2 waits for its findings.
