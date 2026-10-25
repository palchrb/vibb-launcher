# Code QA: launcher-glance-b (glance contact photos, the group's sender, one sender's count; the provisioning window)

Scope: `git diff main..launcher-glance-b` (4 commits `38bff88b..f2fa1087`, 21 files, launcher + docs) against PLAN's
three Launcher queue items (contact photos on glance rows incl. WhatsApp by the `tel:` URI - the user's decision -,
the group's newest sender with its contact photo and a corner mark; the "Full text" count; the provisioning window
for the app-list loads), design 25 and design 15 "2026-10-25", `launcher/CLAUDE.md`, and
`docs/testing/2026-10-25-evening-run.md` ("Owed after launcher-glance-b"). Reviewed on branch `qa-launcher-glance-b`.

**Verdict: merge after a small fix round (Q-1, and the test gaps Q-3 to Q-5; the docs Q-9 with it).** Privacy holds:
the one new notification read is exactly the WhatsApp sender's `?.uri`, pinned to one line, `tel:` only, and only our
contact's id leaves `glanceTelContact` - nothing reaches prefs, the status report or a log. The row rules are pure and
well tested. One Medium: the provisioning window's "fold into a few loads" only holds when the storm's gaps are 3 s or
less, and the evening run's own numbers suggest many gaps were longer (Q-1). Suite green; 18 of 24 mutations caught.
The 6 that got through are all in the panel's glue or the log scan (Q-3 to Q-5, Q-11).

## Privacy (asked first)

1. **The one new read - right, and pinned.** `GlanceReader.contactOf` (GlanceReader.kt:206-210) reads
   `newest?.getParcelable(SENDER_PERSON, Person::class.java)?.uri` only for `GLANCE_TEL_PACKAGES` (`com.whatsapp`,
   `com.whatsapp.w4b`), only when `read()` got that far (the package is ticked in the glance policy, our user) and only
   from `GlanceLevel.SENDER` up (GlanceReader.kt:199). `LockGlanceGuardTest` pins it hard: Person reads are exactly
   `?.name` and `?.uri`, `\buri\b` appears once (case-insensitive), that line is matched in full, the `contactOf`
   call and its `level >= SENDER` guard are matched, `.key` is still only `sbn.key`, and `icon`/`largeIcon`/
   `getLargeIcon` stay banned (the allowance removed only `uri` from the never-read list; the count rule replaces
   it). Mutations M2c (log the URI), M3b (read `?.key` instead), M3c (the tel rule for every package) were caught.
2. **The number stays in memory.** `glanceTelContact` (GlanceRows.kt) keeps the URI and the normalised number in
   locals and returns `RuleContact.id` (> 0) or null. `GlanceNotice`/`GlanceMessageRow` gained `shape`, `sender`,
   `contactId` (pinned by `the kept facts are exactly the ones the design names`). No number, key or URI field.
   `GlanceStore` is still memory only (the persistence scan covers the three glance files). The status report has no
   glance data. `toString()` stays redacted (`GlanceNameSuffix.Sender` too). No `Log.` in GlanceRows (M2a caught).
   The one gap: `println`/`System.err` isn't scanned (M2b, Q-5).
3. **The id > 0 rule earns its keep.** Before the first unlock the call rules are the DE boot copy, whose
   `RuleContact`s are built with `id = 0` (BootCallPolicy.kt:126) and no photo. So in Direct Boot no row matches,
   and `ElementRoomStore.rooms` throws on CE and is caught to `emptyMap()`. Correct fail-closed behaviour.
4. **Element X: is reading a group sender's key allowed? Yes - nothing new is read; the key gets a new use.**
   `ElementDmReader.facts()` is unchanged. It has always read every `sender_person` key of every Element X
   notification in our user, groups included: the facts are built before `learnElementRoom` refuses a group. Design
   15's rule ("`Person.key` only", `ElementRoomsTest`'s allowlist scan) allows exactly that, and the scan only gained
   `glanceContact`, `elementGlanceContact`, `rooms` and `Long`. GlanceReader itself still reads no key (pinned;
   M3b caught). What widens is the **use**. Before, a group's keys only made the learning rule refuse. Now the
   newest group sender's key is compared in memory with the phone book's Element MXIDs, and our contact's id goes
   to `GlanceStore`'s memory. That reveals nothing the row doesn't already show at SENDER (the sender's name). It
   only matches phone-book contacts and is never stored, logged or sent. Acceptable under design 15's rule. But two
   texts say less than the truth and should say it plainly (Q-9): ElementDmReader's KDoc ("Nothing here is logged or
   reported; the pair goes to ElementRoomStore (CE prefs) only") and PLAN item (1) ("nothing new read ... no person
   key").
5. **Order of reads.** `onNotificationPosted(sbn, rankingMap)` calls `super` first, which runs `ElementDmReader.learn`.
   On connect, `sweep()` (learn) runs before `GlanceReader.connected`. So a DM's first notification already finds the
   room it just taught. Right.

## Correctness

- **Matching.** `PhoneNumbers.numberFromHandle` + `normalize(raw, rules.defaultCc)` is the same pair `contactFor`
  and the call path use, and the server's numbers come from the same vectors (`phone_vectors.json`). The test
  covers national form, `00`, percent-encoding with parameters, `TEL:` in upper case, `sip:`, `mailto:`, a bare
  number, `content://` and an MMI code. It matches only the outbound contacts (`phoneBook`; M12 caught) and only
  managed rules.
- **Row rendering.** The contact's Home avatar goes through `KidAvatars.bindContact`, the same call the missed-call
  row uses. The app's icon becomes the 18 dp badge with a 1.5 dp ring in the row's colour. A missed call keeps the
  red phone (`missedBadge` restores the layout's look on a reused view). No layout change: the badge view, its 18 dp
  and the 36 dp icon are the XML's, and `LockGlanceLayoutTest` is untouched and green. When the icon isn't drawn yet
  the photo shows without a mark. The icon job's `render()` adds the mark later. Fine.
- **TalkBack.** nb "Gruppe %1$s, siste fra %2$s" / en "Group %1$s, latest from %2$s". The count line "Mamma, 3 nye"
  replaces the "3 meldinger" item, so it is never said twice. Right, but unpinned (Q-4).
- **The count.** Only on a DIRECT row at TEXT that shows its text, with count > 1 (M5 caught). Group rows in
  "Full text": see Q-6. The dismiss is unchanged (`GlanceReader.dismiss`, `keysOf`).
- **Provisioning window.** `appLoadProvisioning(now, openedAt)` is `0 until 120 s` (M14 caught). It is opened by
  `MdmDeviceAdminReceiver.onEnabled` (the `set-device-owner` path and the QR path both send DEVICE_ADMIN_ENABLED) and
  by `Enrollment.enroll`'s success (every enrolment, the QR path's `applyProvisioningExtras` included). Memory only,
  reset by `resetForTest`. After the window the normal rules apply (tested). The 17 loads before `set-device-owner`
  are documented as out of scope. **Does a parent-driven change wait 3 s inside the window?** Mostly no: a policy
  change runs an `AppEnforcer` pass, and the hold's end is a normal request, so its list still shows 300 ms after
  the pass. Only a package callback without a pass waits for the trail's 3 s of quiet: a catalog or Play install of
  an allowed app inside the first 2 minutes, or more likely the music app's first install. During a continuing
  storm that can be up to the 30 s cap. Acceptable for the first two minutes of setup, but say so in the KDoc (Q-9).

## Findings

### Medium

**Q-1 - The provisioning storm folds only if its gaps stay at or under 3 s; the test sits on that boundary.**
`ui/home/AppLoads.kt:105-109`, `HomeRound7Test.kt:453-468`. In the window every callback is trail, so a burst loads
after `APP_LOAD_TRAIL_QUIET_MS` = 3 s of quiet. The test's timeline cycles gaps of exactly 1, 2 and 3 s, and the
simulator (`loadsFor`: `pending.first() <= until`) resolves an event at exactly the timeout as a request, so every 3 s
gap folds: 5 loads. A probe with the same helper (added and reverted in this review) gives:

| gaps (cycled) | callbacks 21-110 s | loads |
|---|---|---|
| 1, 2, 3 s (the test) | 45 | 5 |
| 1, 2, 3.2 s | 44 | 17 |
| 1.5, 3.1 s | 40 | 22 |
| 3.1 s | 29 | 30 (one per callback, as without the window) |

The evening run had 26 loads between 20:09:07 and 20:10:36, each after its own callback at 300 ms. That is one
callback per 3.4 s or more on average, so gaps over 3 s were common, not rare. As built, the rerun may show little
change. **Fix:** give the window its own quiet, e.g. `APP_LOAD_PROVISIONING_QUIET_MS = 5_000` (still at most the
trail's 30 s cap), used by `appLoadBurstDue` while `appLoadProvisioning` is true. Test it with jittered gaps up to 4 s
(not exactly on the quiet), asserting at most about 5 loads. Owe the rerun the callback timestamps, so the gap
distribution is measured instead of assumed (Q-9).

### Low

**Q-2 - An Element X group's photo and name can come from different messages.** `badges/ElementDmReader.kt:63-65`
vs `badges/GlanceReader.kt:164-168`. The name line's sender is `fromOthers.lastOrNull()`: bundles with `sender_person`
**or** `sender`. `ElementDmFacts.senderKeys` only keeps bundles with `sender_person`. If the newest bundle has only a
`sender` CharSequence, `senderKeys.lastOrNull()` is an older message's sender, and the row shows "Fotballgjengen ·
Pappa" with Mamma's photo. In practice the platform's `Message.toBundle` writes both keys together, so this is
defensive. **Fix:** keep `senderKeys` aligned with `fromOthers`: a bundle with `sender` but no `sender_person`
becomes a `null` key. Learning already fails closed on a null, and the glance then shows the app icon. Add an
`ElementRoomsTest` case.

**Q-3 - The panel's badge choice isn't pinned (M4, M4b, M16 not caught).** `lock/GlancePanel.kt:1418-1422`, 1501-1509.
The test only checks that three `when` lines exist. It doesn't check their order (a missed call losing its red phone
to the app mark passed), a constant-false guard, or `appMark`'s `VISIBLE`. **Fix:** a pure
`glanceRowBadge(missedShown, hasContact, hasIcon): GlanceRowBadge { MISSED, APP_MARK, NONE }` in GlanceRows.kt with a
table test. The panel then binds `when (glanceRowBadge(..))`, and the source check pins that one line and
`appMark`'s `badge.visibility = View.VISIBLE`.

**Q-4 - TalkBack's group line isn't pinned (M13 not caught).** `lock/GlancePanel.kt:1424-1429`. Dropping the
`glance_group_spoken` branch, so the group was read as just its name, passed. **Fix:** a pure
`glanceSpokenName(row): GlanceSpokenName` (Group(title, sender) / WithSuffix / Plain) tested with the existing
`glanceSpokenGroup` cases, or at least a source pin of the branch, like the badge's.

**Q-5 - The log scan misses `println`/`System.err` (M2b not caught).** `LockGlanceGuardTest.kt:246-254`.
`System.err.println(number)` in `glanceTelContact` passed every test, and on Android it lands in logcat as
`W/System.err`. **Fix:** ban `println(`, `print(`, `System.out`, `System.err` and `printStackTrace` in `glanceFiles`
+ GlancePanel.kt, and in ElementRooms.kt/ElementDmReader.kt via `ElementRoomsTest`.

**Q-6 - Group rows in "Full text" show no count. Recommend showing it (the user's call).**
`badges/GlanceRows.kt:251`. A group row in "Full text" shows "Fotballgjengen" over "Pappa: Kommer kl 5" and loses the
count entirely, while TalkBack still reads "5 meldinger" for it, because the suffix isn't `NewCount`. "Name only"
shows "WhatsApp · 5 meldinger" under the group. The user's ask was framed by one sender, but its reason (several
messages fold into one row that shows only the newest) applies to a group just the same. The number is the
conversation's new messages, which is what the kid wants to know. **Recommendation:** `NewCount` for GROUP at TEXT
too ("Fotballgjengen · 5 nye"), one condition plus a test line. TalkBack then reads "Gruppe Fotballgjengen, siste
fra Pappa, Pappa: Kommer kl 5, 5 nye". Ask the user before changing it; the implementer left it out on purpose.

**Q-7 - The suffix is ellipsized with the name.** `lock/GlancePanel.kt:1398`, `view_lock_glance_row.xml` (title
`maxLines=1`, `ellipsize=end`). The title can be up to 60 characters, so a long group name hides "· Pappa", and a
long name hides "· 3 nye". This is the information the items add. **Fix:** ellipsize only the name. Either use a
second `wrap_content` TextView for the suffix in a horizontal row with the name at weight 1, or shorten the name with
`TextUtils.ellipsize` to the width minus the suffix. Update `LockGlanceLayoutTest`'s parts if the XML changes.

**Q-8 - `contactId` is resolved only when the notification is read.** `badges/GlanceReader.kt:199`. Removals are
immediate, because `glanceRowContact` re-checks the phone book now. But a contact the parent adds, an Element room
learned later, or the CE rules arriving after a pre-unlock sweep only show a photo from the next post of that
conversation. Accept and document it (design 25 "2026-10-25"), or call `GlanceReader.resweep()` after a CE
`CallPolicyStore.refresh` that changed the contacts.

**Q-9 - Docs.**
(a) Evening run, "Owed after launcher-glance-b", the provisioning row: "fewer than the 43 here" includes the 17
pre-DO loads. The baseline after `set-device-owner` is 26, so state a target (a handful) and ask for the callbacks'
timestamps (Q-1).
(b) `AppLoads.kt:85` still says "Any other request in the burst (an install, ...) keeps 300 ms / 2 s". Inside the
window an install's callback is trail. Add a line saying so (3 s, up to 30 s during the storm).
(c) `launcher/CLAUDE.md`: the window clause is inserted before "; any other request in the burst brings back 300 ms /
2 s", which now reads as if it were about the window. Move the window clause after that sentence.
(d) `ElementDmReader` KDoc (ElementDmReader.kt:17-24) and PLAN item (1): say that a group's newest `sender_person`
key, already read, is now also matched for the glance's photo (memory only, our contact's id), as the privacy
section above puts it.

### Nit

**Q-10** - `bg_glance_app_mark.xml` comment: "the same 2 dp ring". The mark's ring is the 1.5 dp padding over a solid
oval, not the missed badge's 2 dp stroke. Fix the comment or make it 2 dp.

**Q-11** - `elementGlanceContact`'s `takeIf { it != facts.selfKey }` is untested (M15 not caught). It is defensive,
because the kid's own MXID is never a phone-book contact. Add a case where the self key is also a phone-book Element
contact, or drop the check.

## Mutation pass (24, reverted with `git checkout`; tests: GlanceRows, LockGlanceGuard, ElementRooms, HomeRound7, LockGlanceLayout, LockGlance, LockMediaGuard, PhoneNumbers)

| # | Mutation | Result |
|---|---|---|
| M1 | `glanceTelContact` accepts any URI scheme | caught |
| M2a | the number logged with `Log.d` in GlanceRows | caught |
| M2b | the number printed with `System.err.println` | **NOT CAUGHT** (Q-5) |
| M2c | the URI logged in the reader | caught |
| M3a | DM path falls back to the newest sender's key | caught |
| M3b | reader reads `?.key` instead of `?.uri` | caught |
| M3c | the tel rule for every package | caught |
| M4 | the corner mark's branch never taken | **NOT CAUGHT** (Q-3) |
| M4b | `appMark` leaves the badge GONE | **NOT CAUGHT** (Q-3) |
| M5 | the count on a single message | caught |
| M6 | the window 20 min | caught |
| M7 | the window not opened at enrolment | caught |
| M7b | the window not opened at `onEnabled` | caught |
| M7c | `AppLoadHold.trails` ignores the window | caught |
| M8a | an Element group's sender from the oldest message | caught |
| M8b | the row's contact from the oldest notification | caught |
| M9 | a contact's photo at APP_ONLY | caught |
| M10 | id > 0 dropped | caught |
| M11 | the group's sender suffix in "Full text" too | caught |
| M12 | inbound-only contacts matched | caught |
| M13 | TalkBack's group string dropped | **NOT CAUGHT** (Q-4) |
| M14 | the window's end inclusive | caught |
| M15 | the group's self-key check dropped | **NOT CAUGHT** (Q-11) |
| M16 | a missed call shows the app mark, not the red phone | **NOT CAUGHT** (Q-3) |

Score: **18 / 24**.

## Suite

`flock ~/build.lock ./gradlew --no-daemon --max-workers=2 -PwarningsAsErrors=true assembleDebug testDebugUnitTest`
from `launcher/` (no emulator running): BUILD SUCCESSFUL, 1804 tests in 178 classes, 0 failures, 0 skipped.

## Emulator checks to add (to "Owed after launcher-glance-b")

- The storm rerun: log each `LauncherApps` callback's elapsed time (package count only) next to each "apps loaded"
  line, so Q-1's gap distribution is measured. Target: a handful of loads after `set-device-owner` (baseline 26).
- A group with a 40+ character name in "Name only", and a long DM name in "Full text" with 3 messages: the suffix
  stays readable (Q-7).
- A contact added to the phone book while their WhatsApp row is on the lock: the photo shows after the next message
  (Q-8, as documented).
- WhatsApp's real `sender_person` URI shape, on the test account only: the photo shows for a phone-book number; the
  app icon shows for a `content://` or missing URI. Don't paste `dumpsys notification --noredact` output with real
  numbers into the docs.
- After a reboot, before the first unlock: rows show the app icon, no photo, no crash (`id = 0`, CE unreadable).
- First lock render after a cold start: the photo shows first, then the app mark once the icon job has drawn it.

## Fix round (2026-10-25, the implementer)

Every finding fixed in **6b4962b0** (one commit; the coordinator's decisions: Q-1 a 5 s quiet, Q-6 yes, Q-8 documented).

| # | Fix |
|---|---|
| Q-1 | Fixed in 6b4962b0: `APP_LOAD_PROVISIONING_QUIET_MS` 5 s (`appLoadTrailQuietMs`; `appLoadBurstDue`/`appLoadBurstWaitMs` take the quiet, `runAppLoads` reads it each round through `trailQuietMs`, `Application` passes `AppLoadHold.trailQuietMs`), the 30 s cap kept. `HomeRound7Test`'s timeline now cycles gaps of 1.4, 3.2, 2.7, 4.0, 0.9, 3.6, 2.1, 3.9, 1.8 and 3.3 s: 34 callbacks 21-110 s are loads at 0, 22.3, 52.4, 82.5 and 114 s (pinned); with the trail's 3 s they are 19 loads, without the window one each. The debug build's "apps loaded" line ends "; callbacks at <elapsed>ms (<package count>), …" (`appLoadCallbacksNote`, `AppLoadHold.takeCallbacks`, the newest 64; no package names), so the storm rerun measures the gaps. |
| Q-2 | Fixed in 6b4962b0: `ElementDmReader`'s senders are the bundles with `sender_person` **or** `sender` (the glance reader's "from others"); one without a sender person is a `null` key (learning fails closed, the glance shows the app icon). `ElementRoomsTest` pins both filters as the same rule and that `sender` is only checked for presence. |
| Q-3 | Fixed in 6b4962b0: pure `glanceRowBadge(missedShown, hasContact, hasIcon)` -> `MISSED`/`APP_MARK`/`NONE` with a full table test; the panel binds `when (glanceRowBadge(..))` (pinned), and `appMark`/`missedBadge` both end in `badge.visibility = View.VISIBLE` (pinned). |
| Q-4 | Fixed in 6b4962b0: pure `glanceSpokenParts(row)` (`Name`, `Group`, `NewCount`, `Text`, `Count`, redacted `toString`s) tested for DM, group, both levels, no sender and APP_ONLY; the panel's mapping of `Group` to `glance_group_spoken` and both strings' values are pinned. |
| Q-5 | Fixed in 6b4962b0: `LockGlanceGuardTest` bans `println(`, `print(`, `System.out`, `System.err` and `printStackTrace` in GlanceReader/Store/Rows and GlancePanel; `ElementRoomsTest` the same in ElementRooms, ElementRoomStore and ElementDmReader. |
| Q-6 | Fixed in 6b4962b0 (decided yes): `NewCount` for DIRECT and GROUP rows at TEXT showing their text with a count > 1 ("Fotballgjengen · 5 nye"); TalkBack "Gruppe Fotballgjengen, siste fra Pappa, 5 nye, Pappa: Kommer kl 5". |
| Q-7 | Fixed in 6b4962b0: the name line is a horizontal `wrap_content` row (`glance_row_name_line`): the name (`wrap_content`, weight 1, one line, ellipsized) and the suffix `glance_row_suffix` ("· %1$s", `glance_name_suffix`, not ellipsized, 4 dp after it) - the row is as wide as its content until it is full, then only the name gives way. `LockGlanceTest` pins the structure; `glance_name_with` is gone. |
| Q-8 | Fixed in 6b4962b0 (documented, no code): design 25 "2026-10-25" and `launcher/CLAUDE.md` - a contact added later, a room learned later or rules after a pre-unlock sweep show from that conversation's next post; a removal is immediate. |
| Q-9 | Fixed in 6b4962b0: (a) the evening run's provisioning row states the 26-load baseline and a handful as the target, and asks for the callbacks' times; (b) `AppLoads.kt`'s trail comment says an install inside the window is trail (5 s, up to 30 s); (c) `launcher/CLAUDE.md`'s window clause moved after "any other request ... 300 ms / 2 s" and gained the 5 s and the debug line; (d) `ElementDmReader`'s KDoc and PLAN item (1)'s Built note say that a group's newest `sender_person` key, read by design 15 all along, is now also matched in memory against the phone book's MXIDs, only our contact's id kept, nothing stored. |
| Q-10 | Fixed in 6b4962b0: `bg_glance_app_mark.xml`'s comment names the 1.5 dp padding ring. |
| Q-11 | Fixed in 6b4962b0: `ElementRoomsTest` adds a phone book where the kid's own MXID is an Element contact - the newest sender being the session is no photo. |

Emulator checks: the QA's six added to `docs/testing/2026-10-25-evening-run.md`, "Owed after launcher-glance-b".

**Suite**: `assembleDebug testDebugUnitTest -PwarningsAsErrors=true` - BUILD SUCCESSFUL, 1807 tests, 0 failures.

**The six surviving mutants, re-run** (GlanceRows, LockGlanceGuard, ElementRooms, LockGlance tests):

| # | Mutation | Now |
|---|---|---|
| M2b | the number printed with `System.err.println` | caught (`LockGlanceGuardTest`, the standard-stream ban) |
| M4 | the corner mark's branch never taken | caught (`GlanceRowsTest`, the corner table) |
| M4b | `appMark` leaves the badge GONE | caught (`GlanceRowsTest`, the `VISIBLE` pin) |
| M13 | TalkBack's group string dropped | caught (`GlanceRowsTest`, the spoken parts' panel pin) |
| M15 | the group's self-key check dropped | caught (`ElementRoomsTest`, the self-in-phone-book case) |
| M16 | a missed call shows the app mark, not the red phone | caught (`GlanceRowsTest`, the corner table) |

All six now fail a test (each applied alone, the four classes run, reverted with `git checkout`).
