# 82 - Meldinger as one more app: its lock-screen notifications and its icon set where every other app's are

Status: design note by the architect, 2026-11-10, on `main` at 9bc29f00, branch `design-82`. Nothing built. Server
and launcher, one small API addition (`lock_glance.sms`), no new shared contract file. Builds on design 77 (the
message engine), round 7 / design 34 (the lock's notification rows, `lock_glance`) and design 37 ("Name and icon",
`launcher_ui.app_display`).

The user, 2026-11-10:

> Jeg ønsker å styre notifications for sms på låst telefon helt likt som jeg gjør for de andre appene? Lar det seg
> gjøre? Så jeg kan bestemme på samme sted for andre apper om den får vise notifs, om den viser full tekst eller hva.
> Ønsker også å kunne tilpasse logoen slik som for andre apper.

It can be done. **The core** (§2-§6): Meldinger - the launcher's built-in SMS app - gets a row in the Notifications
group of Lock Screen & PINs with the same three choices as every app (Off / Name only / Full text), and a row on the
phone's Apps page with the "Name and icon" form's glyph and colour. **Three optional widenings**, each its own
section and none needed by the core: W1 a name too (§7), W2 an "On Home" switch (§8), W3 a buzz on a locked phone
(§9). What the user must choose is in §12.

## 1. Today (verified against `main` 9bc29f00)

`L/` = `launcher/app/src/main/java/com/kidslauncher/mdm/`.

**The apps' lock rows.** Table `device_lock_glance_apps(device_id, package_name, show_text)`: no row = Off, 0 = Name
only, 1 = Full text (migrations 0066, 0083). The policy carries `lock_glance: {apps: [{package, show_text}]}`
(`server/src/lock_glance.rs` `policy_apps`), the launcher claims `lock_glance_v1`. The PWA's group is
`server/templates/device_lock.html` `#notifications`: one auto-saving `<select name="show">` per app, posted to
`POST /devices/{id}/lock-screen/apps` (`server/src/handlers/lock_glance.rs` `save_app`), which returns to
`#lock-screen-app-<package>`. On the phone the rows come from the notification listener: `L/badges/GlanceRows.kt`
`lockGlancePolicy` -> `GlancePolicy(apps)`, held by `L/badges/GlanceStore.kt` (set on the main thread in
`refreshPolicy`'s `main.post`, never persisted).

**Meldinger's lock rows** do not come from the listener. `L/sms/SmsHome.kt` `refresh` builds them on the engine thread
from the launcher's own message store, with the sender's name always and the text when
`smsLockText(CallPolicyStore.state)` (`L/sms/SmsGlance.kt:24`: the rules' `sms_lock_text`, only while the call policy
is `Managed`). That is one switch, "Show Message Text on the Lock Screen", on Calls & Contacts > Messages
(`server/templates/device_calls.html` `#sms-settings`, `name="sms_lock_text"`; `device_policy.sms_lock_text`, migration
0130; sent as `call_policy.sms_lock_text` by `server/src/handlers/device_api.rs:694`). **There is no Off**: a new
message always gives a row with the name. A new message wakes the screen through `SmsNotifier.wakeIfAlerts` ->
`PinLockRuntime.noticeCame()`, the same point an app's row and the missed-call notice pass.

**Name and icon.** `device_app_display` (+ `on_home`, migration 0103) -> `launcher_ui.app_display: [{package_name,
label, icon, color, on_home?}]` (`server/src/app_display.rs` `policy_app_display`), saved by
`POST /devices/{id}/apps/display` (`server/src/handlers/devices.rs:1144` `save_app_display`), the form
`server/templates/partials/app_display_form.html` (a label, the icon radios Automatic / App logo / 16 glyphs, the
colour radios, the On Home switch). The launcher reads it by installed package name only (`L/appdisplay/AppDisplay.kt`
`entry(pkg)`; nothing iterates the map).

**Meldinger's look is fixed**: the `chat` glyph on `kid_messages_tile` `#347470`
(`launcher/app/src/main/res/values/colors_kid.xml:57`), drawn in three places - the Home tile
(`L/ui/home/HomeGridAdapter.kt:213`), its row on the time-rule screen (`L/ui/LockActivity.kt:355`) and the lock row
(`L/lock/GlanceSmsRowView.kt`: a small badge on the contact's picture, or the glyph itself without one). The form's
colour key `night` has exactly that tile (`L/appdisplay/AppGlyphs.kt:61`, `server/testdata/app_icons.json`).

**The Apps page** (`server/src/handlers/device_pages.rs` `render_apps`, `server/templates/partials/device_app_row.html`)
lists the phone's reported apps; Vibb One's own row is pinned first with no switch and no forms ("Always on").
Meldinger is not an installed app and is on neither page.

**What the survey had wrong or the code sharpens:**

1. A reaction's buzz is **80 ms** (`L/sms/SmsNotifier.kt:44` `REACTION_VIBRATION = longArrayOf(0, 80)`), not 40.
2. "Only Meldinger could honour a buzz" is not so: every app row that alerts reaches `PinLockRuntime.noticeCame()`
   too (`L/badges/GlanceStore.kt:79`), so the launcher can buzz for everything on the lock (§9).
3. The package grammar cannot make a key uncollidable: both sides accept the same grammar
   (`app_display::valid_package_name`, Kotlin `isValidPackageName`) and so does Android. A key is safe only by
   **reservation** on both sides (D1).
4. `GlanceStore.policy` and `AppDisplay.map` are `@Volatile` immutable values, safe to read from the engine thread. The
   thread question is only the trigger: the glance policy is applied in a `main.post` and the store has no Context (D5).
5. `CallPolicyStore.refresh` tells its listeners after every refresh, not only on a change, so `SmsHome` already
   refreshes on every sync.
6. The level cannot ride `call_policy`: with both channels "Ikke styrt" the state is `Unmanaged` and there are no rules
   to read, so an Off there could not be kept. It cannot be an `apps` entry either: an older server never sends one, so
   "absent" could not mean Off (D3).
7. `GridTile.Messages(badge)` (`L/ui/home/HomeModel.kt:31`) is compared by `tileChange`; an icon change would not
   redraw the tile unless the tile carries its look (D9).
8. `save_app_display` already takes any well-formed package, installed or not. The work for a built-in is the
   refusals, not the plumbing.
9. The Notifications group's "no notification access" note says "Missed calls only ... so messages don't show". That
   is wrong for Meldinger today: its rows need no notification access (D7).

## 2. Decisions

**D1. The key is `me.vibb.builtin.messages`, and the prefix `me.vibb.builtin.` is reserved.** It is well-formed on both
sides, is not Play core (`play::is_play_core`), not `me.vibb.launcher*` and not `me.vibb.music`. One small server
module (`builtin.rs`: `MESSAGES`, `is_reserved(package)`) and one launcher constant hold it. The reservation:

- Server: a reported installed app under the prefix is dropped at intake (`device_api.rs:812` `cap_installed_apps`),
  so it never reaches the Apps page, the allowlist, the lock card or a permissions form; `never_a_catalog_package`
  (`handlers/tracked_apps.rs:735`) refuses the prefix; the allowlist and permissions saves refuse it;
  `lock_glance::valid_package` and `save_app_display` accept **exactly** the known key and refuse every other name
  under the prefix; `policy_apps` never puts a reserved name in `lock_glance.apps`; `card_apps` never lists one as an
  "unlisted tick".
- Launcher: `AppDisplay.entry(pkg)` answers null for a reserved name, and only a new `AppDisplay.builtin(key)` reads
  the key's entry; `glanceApp` drops reserved names from `lock_glance.apps`.

A phone that really has a package of that name: the server never hears of it, so it can never be allowed, and on a
phone with an allowlist it stays hidden and suspended like any app not allowed. It never gets a lock row. On an
**older** launcher without an allowlist it would be dressed with Meldinger's chosen glyph - cosmetic, and only for a
package made to collide.

**D2. The server's one source of truth is a row in `device_lock_glance_apps` under the key.** The existing save
(`save_app`, `write_show`), select markup and anchor work unchanged, so the row auto-saves in place like its
neighbours. A migration (the next free number at build; 0135 today):

- `INSERT OR IGNORE` a row per existing device with `show_text = device_policy.sms_lock_text` (no policy row: 0) -
  exactly what each phone shows today: Name only, or Full text where the switch was on.
- The trigger `devices_get_lock_glance_default` is recreated to seed Meldinger at Name only beside Element X.
- `device_policy.sms_lock_text` stays as a column and **nothing reads or writes it** (as `lock_glance_text` after
  0083); the `DevicePolicy` field goes.

**D3. On the wire: `lock_glance.sms`, one of `"off"`, `"name"`, `"text"`**, always sent by a server that has this
design. From the row: none -> `off`, 0 -> `name`, 1 -> `text`; **`text` is sent as `name` unless `sms_mode` is
`managed`** (the only time the parent sees the row, D7) - the server owns that rule, the phone only reads the word. A
failed read is a 500, never a default. `call_policy.sms_lock_text` is still sent for older launchers, derived: `true`
only when `lock_glance.sms` is `text`.

**D4. The launcher's reading fails closed.** `GlancePolicy` gains `sms`: `OFF`, `NAME`, `TEXT` or `LEGACY`.

| `lock_glance` | `sms` |
|---|---|
| an object with `sms` `off` / `name` / `text` | that |
| an object with any other `sms` value | `OFF` |
| an object without `sms` (an older server) | `LEGACY` |
| absent in a fresh policy (an older server) | `LEGACY` |
| absent or unreadable in the cache, or no policy yet | `OFF` (as the apps: an unknown policy shows nothing) |

`LEGACY` is today's rule untouched (`smsLockText`: text only with `Managed` rules and `sms_lock_text`, otherwise the
name). One pure function `smsLockLevel(policy.sms, callPolicyState)` gives `OFF`/`NAME`/`TEXT` to both readers:
`SmsHome.refresh` (no rows at `OFF`) and `SmsNotifier.wakeIfAlerts` (no wake at `OFF`: nothing to show). The system
notification, the badge and the thread are untouched by the level - as for an app set to Off.

**D5. Threads.** `SmsHome.refresh` keeps reading on the engine thread; `GlanceStore.policy` is safe to read there. The
trigger: in `GlanceStore.refreshPolicy`'s `main.post`, after the state is set, a changed `sms` calls a hook
(`GlanceStore.smsLevelChanged`, set once by `SmsRuntime` to `{ SmsHome.refresh(app) }` - the store still holds no
Context). The app notices are cleared and reswept **only when `apps` changed**, so changing Meldinger's level never
blanks the other rows. Start-up: the level is `OFF` until the cached policy is applied, then the hook brings the rows.
The Home tile needs no trigger for the level (it shows no text); for the icon see D9.

**D6. One capability, `messages_app_v1`**: "this Vibb One honours `lock_glance.sms`, Off included, and the key's
`app_display` entry". A constant on both sides and in `sms_contract.json`. The server uses it for notes only, never
to change what it sends.

**D7. The Notifications group.** Meldinger's row is listed **while `sms_mode` is `managed`**, sorted by its name among
the apps like any other, labelled `device.sms.messages` ("Messages" / "Meldinger"; the chosen name with W1). The words:

- Off stored and the phone has not claimed `messages_app_v1`: a note on the row, "This phone still shows the name
  and how many - update Vibb One" / "Denne telefonen viser fortsatt navn og antall - oppdater Vibb One".
- No notification access while Meldinger is listed: the note becomes "Missed calls and Messages only" / "Bare tapte
  anrop og Meldinger", "Vibb One has no notification access, so the other apps' notifications don't show. The fix is
  under Home Screen on the phone's page." / "Vibb One har ikke varseltilgang, så varsler fra de andre appene vises
  ikke. Løsningen står under Hjem-skjerm på telefonens side." Without Meldinger listed it stays as today.
- `device.lock.wake_sub` becomes "For the apps on the lock below and missed calls" / "For appene på låsen nedenfor og
  tapte anrop" (Meldinger is now one of the apps below). The footer stands.
- The security log's lines for this row (`lock_glance::log_line`, `text_log_line`) say "Messages" / "Meldinger", never
  the key.

**D8. Calls & Contacts.** The switch goes. In its place under Messages one link row, "On the lock screen" / "På
låseskjermen" with the level as its value, to `/devices/{id}/lock#lock-screen-app-me.vibb.builtin.messages`. The
`/calls/sms` handler stops reading `sms_lock_text` (`handlers/calls_sms.rs` ~1014), so a stale page's post changes
nothing; the Avansert summary line `device.calls.adv.lock_text` goes.

**D9. The icon is a `launcher_ui.app_display` entry under the key** - the existing table, save and policy shape; absent
= Automatic. The form for a built-in (`AppDisplayForm` with a `builtin` flag):

- no "App logo" radio, and `own` is refused for the key (there is no app logo to show);
- "Automatic" is today's look and previews as it: the chat glyph on `#347470`. A chosen glyph with colour Automatic
  keeps the teal; a chosen colour with icon Automatic keeps the chat glyph;
- no name field and no On Home switch in the core (W1, W2); a posted `label` or `on_home` for the key is ignored.

On the phone one helper, `messagesLook()` (glyph: `AppGlyphs.ICONS[icon]`, else chat; tile colour:
`AppGlyphs.COLORS[color].tile`, else `kid_messages_tile`), feeds all three places of §1: the Home tile, the time-rule
screen's row, and the lock row's badge and fallback icon (the badge's fixed drawable is tinted). `GridTile.Messages`
carries the look so a change redraws the tile; `AppDisplay.refresh` already calls `reloadApps()` on a changed map (the
build confirms that rebuilds the Messages tile, and re-binds the lock row). The notification's small icon stays Vibb
One's.

**D10. The Apps page row.** One switch-less row, "Messages" / "Meldinger", pinned directly under Vibb One's in
"Allowed", with the sub "Part of Vibb One" / "En del av Vibb One" and under it only the display form: no allow
switch, no permissions, no update form. Listed while `sms_mode` is `managed`, or while a display row is stored for
the key (so a chosen icon can always be reset); in that second case the sub adds "SMS is not managed by Vibb" / "SMS
er ikke styrt av Vibb". When the phone has not claimed `messages_app_v1`, the form's note
says "Shows on the phone after Vibb One is updated" / "Vises på telefonen når Vibb One er oppdatert". A save returns
to `#app-me.vibb.builtin.messages` and a refused one re-renders with the form open, as every row.

## 3. Server changes

- Migration (D2). `builtin.rs` (D1). `lock_glance.rs`: `valid_package`, `policy_apps`, `card_apps`, the level word
  for the policy (D3), the note variant and the log words (D7); the policy's `lock_glance` struct gains `sms`.
- `handlers/devices.rs` `lock_screen_card`: adds Meldinger's row while SMS is managed. `handlers/lock_glance.rs`
  needs nothing but D1's check.
- `handlers/device_api.rs`: `build_call_policy` derives `sms_lock_text`; `cap_installed_apps` drops the prefix.
- `handlers/calls.rs`, `handlers/calls_sms.rs`, `templates/device_calls.html`: D8.
- `handlers/device_pages.rs` `render_apps`, `partials/device_app_row.html`, `partials/app_display_form.html`,
  `static/app-display.js` (the teal Automatic preview), `app_display.rs` (`own` refused for the key): D9, D10.
- `en.json` / `nb.json`: the strings of D7, D8, D10.

## 4. Launcher changes

- `badges/GlanceRows.kt` (`GlancePolicy.sms`, the table of D4, `glanceApp`), `badges/GlanceStore.kt` (the hook, the
  narrower resweep).
- `sms/SmsGlance.kt` (`smsLockLevel` replaces `smsLockText`), `sms/SmsHome.kt`, `sms/SmsNotifier.kt`,
  `sms/SmsRuntime.kt` (sets the hook).
- `appdisplay/AppDisplay.kt` (`builtin`, the reserved prefix), the look helper, `ui/home/HomeModel.kt` +
  `HomeGridAdapter.kt`, `ui/LockActivity.kt`, `lock/GlanceSmsRowView.kt`.
- `server/StatusCapabilities.kt`: `messages_app_v1`, claimed in the step that makes both halves true (§11).

No new permission, no new stored state, no new string in the core.

## 5. Older launchers and older servers

| | Off | Name only | Full text |
|---|---|---|---|
| new server, **older launcher** | the name and how many (it cannot hide); never the text. The PWA says so (D7) | name | text |
| **older server**, new launcher | - (the server has no Off) | as today | as today |

Privacy holds in every pairing: the text shows only where the parent chose Full text, on any version. The one thing
an older launcher cannot do is hide the row at Off, and the PWA says it on the row itself. An older launcher that
receives the key's `app_display` entry matches it to no installed app and ignores it. Either side can ship first.

## 6. Contract and tests (lean)

No new shared file, so no CI path filter changes. `server/testdata/sms_contract.json` (and its byte-identical launcher
copy) gains one section, `app`:

- `capability` and `key`;
- `lock`: rows of (stored choice, `sms_mode`) -> `lock_glance.sms` and the derived `sms_lock_text`;
- `lock_read`: about ten rows of D4's table with a call-policy state -> the level (each word; an unknown word -> off;
  no `sms` with `sms_lock_text` true / false -> text / name; absent fresh -> legacy; absent cached -> off);
- `display`: the key's `app_display` entry.

Server: `sms::tests` produce the `lock` rows; `lock_glance::tests` (the package rules, `policy_apps` drops the key,
the card lists Meldinger only while managed, the note variant, the log words); `policy_json_keys_snapshot`
(`lock_glance` = `{apps, sms}`); `tests/sms_page.rs` (the switch is gone, an old post changes nothing) with the posts
in `tests/calls.rs` and `tests/calls_simpler.rs` updated; `tests/app_display.rs` (the key's row is saved and sent,
`own` refused); one intake test (a reported app under the prefix is dropped); one migration test (the seed follows
the old switch).

Launcher: `SmsContractTest` (the rows), `SmsGlanceTest` (`smsLockLevel`), `PolicyResponseCompatTest` (`lock_glance`
keys `{apps, sms}`, the key's entry decodes), one `AppDisplay` test (a reserved name answers null from `entry`),
`HomeModelTest` (a changed look is a full rebind).

## 7. Optional W1: a name for Meldinger too

**Recommended: yes, in step 2.** The form keeps its name field (less special-casing on the server than removing it);
the name shows in the PWA's two rows and on the phone. One helper, `messagesName(context)` (the entry's label, else
`R.string.messages_title`), in the six places the name is spelled: the Home tile's label and its TalkBack text
(`HomeGridAdapter.kt:213`, `:217`), the time-rule row (`LockActivity.kt:286`), the lock keypad's "Enter your code to
open ..." (`GlancePanel.kt:1100`), the lock row's action "Unlock and open Messages" (`glance_sms_action`, which becomes
`%1$s` in en and nb, `GlancePanel.kt:1341`) and Meldinger's own header and task name (`MessagesActivity.kt:188`,
`:108`). The notification channels' names in Android's settings stay. Same 20-character limit as every app. Size
**S** (launcher S, server next to nothing). **The core without it:** D9's "no name field"; the name is "Meldinger".

## 8. Optional W2: "On Home" for Meldinger

**Recommended: yes, in step 2.** The form's On Home switch, meaning what it means for every app: off Home only.
`tileShown = onHomeListable(entry, windowOpen) && messagesTileShown(...)`, and the time-rule row follows the tile.
Messages still arrive, notify and give lock rows by the level, and a thread still opens from the notification, the
lock row and the phone book's Message button (`L/calls/MessageButtons.kt`), so nothing is lost and a parent can lift
it from the PWA at once. It answers PLAN's open point "hide Meldinger even with old history" - which needs the row
while SMS is **not** managed too, and the server cannot see today whether the tile lingers. So W2 adds one report key,
`sms_state.has_history` (the launcher has it: `SmsHome.summary.hasHistory`; into `sms_contract.json`'s `report` and
the server's intake), and the Apps row is then also listed while the phone reports history. Its own sub (the stock one names sync apps): "Off:
Messages still receives and notifies, but the kid doesn't see it on Home, except during a pause." / "Av: Meldinger
tar fortsatt imot og varsler, men barnet ser den ikke på Hjem, unntatt i en pause." An older launcher ignores it (the
same update note as D10). Size **S-M** (the report key is most of it). **The core without it:** no switch for the
built-in; the tile follows `messagesTileShown` alone, as today.

## 9. Optional W3: a buzz on a locked phone

**Not in the core; recommended as its own small step after a look at the real phone.** Today a locked phone is silent
for every notification: the lock's task features leave out notifications (`L/server/LockTaskHelpers.kt`
`featuresWhileLocked`), which mutes their sound and vibration; only the lock's own ringing for calls and its reminder
ping are heard. PLAN's open point asks for a check on the Jelly Star of what a locked phone does for Signal today -
do that first (step 0, no code).

What can honestly be offered: **one switch beside "Wake the screen for a notification"** - "Buzz for a notification"
/ "Vibrer ved varsler", sub "One short buzz, no sound, for the apps on the lock below and missed calls" / "Ett kort
vibb, ingen lyd, for appene på låsen nedenfor og tapte anrop"; `launcher_ui.lock_buzz_on_notification`, default off.
It covers everything that brings a row to the lock, Meldinger included, because all of it passes
`PinLockRuntime.noticeCame()` (`L/lock/PinLockRuntime.kt:1503`). The launcher vibrates once itself (it holds
`VIBRATE`): only while locked, not during a call or ringing, not when the ringer is silent, not under Do Not Disturb
or a time rule's silence, with a gap between buzzes as the wake has, and never for a row set to Off. No sound.

Not recommended: a switch for Meldinger alone (the special case this note removes), or a buzz choice per app (a third
control on every row). Tests: `policy_json_keys_snapshot` / `PolicyResponseCompatTest` for the key and one pure plan
test beside `NoticeWakePlan`. Size **S-M**, server S + launcher S-M. **The core without it:** unchanged - a new
message lights the screen if the wake switch is on, and makes no sound or buzz while locked.

## 10. What does not change

The message engine, the receive and send rules, what is held for the parent, the system notification and its
channels, the unread badge, the apps' lock rows and their reader, the allowlist, the policy's `app_display` shape.

## 11. Steps and sizes

One branch, two commits that each build and pass alone; one server release and one launcher RC, either first.

1. **The lock level** - `server/` and `launcher/` in one commit with the contract's `app` section: the migration,
   `builtin.rs` and the reservation, `lock_glance.sms` and the derived `sms_lock_text`, the Notifications row and its
   words, Calls & Contacts' link row; the launcher's reader, level, hook and wake. Server **M**, launcher **S-M**.
2. **The icon** - the Apps row and the built-in form; the launcher's look helper in its three places; the launcher
   claims `messages_app_v1` here. With W1 and W2 if chosen. Server **M**, launcher **S-M** (+ S for W1, + S-M for W2).

The core is **M-L** in all. W3 is its own step after its step 0, **S-M**.

## 12. For the user

1. **A name for Meldinger too** (W1)? Recommended: yes - it is nearly free once the icon is there.
2. **An "On Home" switch** that hides the Meldinger tile while messages still arrive (W2)? Recommended: yes - it is
   also the "hide Meldinger even with old messages" you asked about.
3. **A buzz on a locked phone** (W3): nothing; or one switch for everything on the lock - the apps, missed calls and
   Meldinger - default off, one short buzz and no sound (recommended, after we look at what your phone does today); or
   Meldinger alone (not recommended).
4. Meldinger's row under Notifications shows **only while SMS is "Styrt av Vibb"**, and the full text shows only then.
   OK?
5. The old switch on Calls & Contacts goes; one line there points to the new place. Every phone keeps what it shows
   today until you change it. OK?
6. On a phone with an older Vibb One, **Off still shows the name and how many** until Vibb One is updated; the page
   says so on the row. The text never shows unless you chose Full text.
7. Meldinger sits right under Vibb One on the Apps page and by its name under Notifications. Say if it should sit
   elsewhere.
