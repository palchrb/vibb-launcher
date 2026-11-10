# 82 - Meldinger as one more app: its lock-screen notifications and its icon set where every other app's are

Status: design note by the architect, 2026-11-10, on `main` at 9bc29f00, branch `design-82`. Nothing built. Server
and launcher, one small API addition (`lock_glance.sms`), no new shared contract file. Builds on design 77 (the
message engine), round 7 / design 34 (the lock's notification rows, `lock_glance`) and design 37 ("Name and icon",
`launcher_ui.app_display`).

**Revision 2, 2026-11-10**: the design QA's fourteen findings (four Mediums, ten Lows), each checked against the code
and all applied - §13 says where each went.

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

1. A reaction's buzz is **80 ms** on `main` (`L/sms/SmsNotifier.kt:44` `REACTION_VIBRATION = longArrayOf(0, 80)`), not
   40; branch `sms-follow-book-ground`, not merged yet, makes it 40 on the user's wish. This note names no length
   for W3: it is "the reaction buzz's length".
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
  (`handlers/tracked_apps.rs:735`) refuses the prefix; the allow switch (`toggle_app`, `handlers/devices.rs:2264`)
  and the permissions target (`perms_target`, `:1436`, which serves `save_app_permissions` `:1304` and
  `save_lock_media` `:1332`) refuse it; `lock_glance::valid_package` and `save_app_display` accept **exactly** the
  known key and refuse every other name under the prefix; `policy_apps` never puts a reserved name in
  `lock_glance.apps`; `card_apps` never lists one as an "unlisted tick". Every other field that takes a package (a
  time rule's exempt apps, the relock windows, music) needs no refusal: the name is never reported, so there it is
  an app not on the phone, like any unknown name.
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
  what each phone shows today **or less**: Name only, or Full text where the switch was on, and the text only while
  SMS is managed (D3). The one case that changes: calls managed, SMS not, the switch on
  (`handlers/calls_sms.rs:1014-1031` writes it whatever the mode) - that phone shows the text today and the name
  after this. Stricter, never looser.
- The trigger `devices_get_lock_glance_default` is recreated to seed Meldinger at Name only beside Element X.
- `device_policy.sms_lock_text` stays as a column and **nothing reads or writes it** (as `lock_glance_text` after
  0083); the `DevicePolicy` field goes.

**D3. On the wire: `lock_glance.sms`, one of `"off"`, `"name"`, `"text"`**, always sent by a server that has this
design. From the row: none -> `off`, 0 -> `name`, 1 -> `text`; **`text` is sent as `name` unless `sms_mode` is
`managed`** (the only time the parent sees the row, D7) - the server owns that rule, the phone only reads the word.
`call_policy.sms_lock_text` is still sent for older launchers, derived: `true` only when `lock_glance.sms` is `text`.

**A failed read keeps today's fallback** (`handlers/device_api.rs:373-384`: "never a 500 that would cost the phone its
rules", pinned by `tests/lock_glance.rs:285-300`) and fails closed with it: the policy carries
`lock_glance: {"apps": [], "sms": "off"}` - never an absent `sms`, which a new launcher would read as the older rule
(D4) - and `call_policy.sms_lock_text` false. There is one read per policy: `build_call_policy` (`:642`, the field at
`:694`) is handed the level from it and never reads the table itself.

**D4. The launcher's reading fails closed.** `GlancePolicy` gains `sms`: `OFF`, `NAME`, `TEXT` or `LEGACY`.

| `lock_glance` | `sms` |
|---|---|
| an object with `sms` `off` / `name` / `text` | that |
| an object with any other `sms` value | `OFF` |
| an object without `sms` (an older server) | `LEGACY` |
| present but not an object (today's `else -> UNKNOWN`, `L/badges/GlanceRows.kt:54-58`) | `OFF` |
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

**D7. The Notifications group.** Meldinger's row is listed **while `sms_mode` is `managed`**, labelled
`device.sms.messages` ("Messages" / "Meldinger"; the chosen name with W1). It is **the group's first row**, with or
without an allowlist: with one the card lists the apps in the Apps page's order, not by name
(`handlers/devices.rs:1074-1133`), and there D10 pins Meldinger under Vibb One, whose own row the card leaves out;
one place in both cases is easier to find than two. The words:

- Off stored, the phone has reported and has not claimed `messages_app_v1`: "This phone still shows the name and how
  many - update Vibb One" / "Denne telefonen viser fortsatt navn og antall - oppdater Vibb One". The note is **its
  own field on the row**, gated on reported-and-not-capable as `lock_glance::notes` is (`lock_glance.rs:113-130`) -
  never `LockScreenApp.note`: that field means "ticked but not listed", makes the form post `unlisted=1` and aim at
  `#notifications` (`device_lock.html:289-291`), and an Off save then lands on the group
  (`handlers/lock_glance.rs:126`) - a jump. Meldinger's row stays listed at Off and is never "unlisted", so every
  save returns to `#lock-screen-app-me.vibb.builtin.messages`.
- No notification access while Meldinger is listed **and not Off**: the note becomes "Missed calls and Messages only"
  / "Bare tapte anrop og Meldinger", "Vibb One has no notification access, so the other apps' notifications don't
  show. The fix is under Home Screen on the phone's page." / "Vibb One har ikke varseltilgang, så varsler fra de andre
  appene vises ikke. Løsningen står under Hjem-skjerm på telefonens side." Not listed, or Off: it stays as today.
- `device.lock.wake_sub` becomes "For the apps on the lock below and missed calls" / "For appene på låsen nedenfor og
  tapte anrop" (Meldinger is now one of the apps below). The footer stands.
- The security log never shows the key. `log_line` / `text_log_line` (`lock_glance.rs:134-158`) store `{package}` as a
  parameter that is worded when read (`en.json` / `nb.json` :829-832), so a label put in at write would freeze the
  writer's language. The built-in key picks **its own catalog keys** instead, with no package parameter:
  `security.detail.lock_screen_messages.on` / `.off` - "phone {device}: Messages on the lock screen: on" / "telefon
  {device}: Meldinger på låseskjermen: på" (`off` / `av`) - and `security.detail.lock_screen_messages_text.on` /
  `.off` - "phone {device}: Messages' text on the lock screen: on" / "telefon {device}: meldingsteksten til Meldinger
  på låseskjermen: på" (`off` / `av`). With W1 the lines still say Messages / Meldinger, not the chosen name.

**D8. Calls & Contacts.** The switch goes. In its place under Messages one link row, "On the lock screen" / "På
låseskjermen" with the level as its value, to `/devices/{id}/lock#lock-screen-app-me.vibb.builtin.messages`. The link
row is drawn **only while `sms_mode` is `managed`**: `#sms-settings` is drawn whatever the mode
(`device_calls.html:447`, `:520-545`), and the row the link points to is not (D7). The `/calls/sms` handler stops
reading `sms_lock_text` (`handlers/calls_sms.rs` ~1014), so a stale page's post changes nothing; the Avansert summary
line `device.calls.adv.lock_text` goes.

**D9. The icon is a `launcher_ui.app_display` entry under the key** - the existing table, save and policy shape; absent
= Automatic. The form for a built-in (`AppDisplayForm` with a `builtin` flag):

- no "App logo" radio, and `own` is refused for the key (there is no app logo to show);
- "Automatic" is today's look and previews as it: the chat glyph on `#347470`. A chosen glyph with colour Automatic
  keeps the teal. **A colour applies only with a chosen glyph, as for every app**: the table keeps no row without a
  name or an icon (`server/src/app_display.rs:74-76`), `wire_entry` sends a colour only with a glyph (`:325-337`)
  and the launcher's `appDisplayMap` drops one without;
- no name field and no On Home switch in the core (W1, W2); a posted `label` or `on_home` for the key is ignored.

On the phone one helper, `messagesLook()` (glyph: `AppGlyphs.ICONS[icon]`, else chat; tile colour: the entry's
`AppGlyphs.COLORS[color].tile` **only when the entry has an icon**, else `kid_messages_tile`), feeds all three places
of §1: the Home tile, the time-rule screen's row, and the lock row's badge and fallback icon (the badge's fixed
drawable is tinted). A change reaches each by a named path:

- **Home**: `GridTile.Messages` carries the look, so `tileChange` sees it; `AppDisplay.refresh` already calls
  `reloadApps()` on a changed map and `renderGrid` (`L/ui/HomeActivity.kt:826-840`) builds the tile.
- **The lock row** does not re-bind today: the panel renders only when `SmsHome.summary.glance != smsRows`
  (`L/lock/GlancePanel.kt:246-248`), and `GlanceSmsRow` (`L/badges/GlanceRows.kt:460`) carries no look. So **the row
  carries the look**, as `GridTile.Messages` does, set in `SmsHome.refresh`; and `AppDisplay.refresh` calls
  `SmsHome.refresh(context)` when the key's entry changed, so the rows differ and the panel renders.
- **The time-rule row** reads the helper at each bind.

The notification's small icon stays Vibb One's.

**D10. The Apps page row.** One switch-less row, "Messages" / "Meldinger", pinned directly under Vibb One's in
"Allowed", with the sub "Part of Vibb One" / "En del av Vibb One" and under it only the display form: no allow
switch, no permissions, no update form. Listed while `sms_mode` is `managed`, or while a display row is stored for
the key (so a chosen icon can always be reset); in that second case the sub adds "SMS is not managed by Vibb" / "SMS
er ikke styrt av Vibb". When the phone has not claimed `messages_app_v1`, the form's note
says "Shows on the phone after Vibb One is updated" / "Vises på telefonen når Vibb One er oppdatert". A save returns
to `#app-me.vibb.builtin.messages` and a refused one re-renders with the form open, as every row.

## 3. Server changes

- Migration (D2). `builtin.rs` (D1). `lock_glance.rs`: `valid_package`, `policy_apps`, `card_apps`, the level word
  for the policy (D3), the row's own note, the no-access variant and the built-in's log keys (D7); the policy's
  `lock_glance` struct gains `sms`.
- `handlers/devices.rs` `lock_screen_card`: adds Meldinger's row first while SMS is managed; `toggle_app` and
  `perms_target` refuse the prefix (D1). `handlers/lock_glance.rs` needs nothing but D1's check (`log_line` and
  `text_log_line` pick the keys).
- `handlers/device_api.rs`: the one read's level goes to `build_call_policy`, which derives `sms_lock_text`; the
  fallback of a failed read gains `sms: "off"` (D3); `cap_installed_apps` drops the prefix.
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
copy) gains one section, `app`, filled by step (§11):

- **step 1**: `key`; `lock`: rows of (stored choice, `sms_mode`) -> `lock_glance.sms` and the derived
  `sms_lock_text`; `lock_read`: about ten rows of D4's table with a call-policy state -> the level (each word; an
  unknown word -> off; not an object -> off; no `sms` with `sms_lock_text` true / false -> text / name; absent fresh
  -> legacy; absent cached -> off);
- **step 2**: `capability` (the launcher claims it only here; the server's constant for D7's note is step 1's and is
  checked against the contract from step 2) and `display`: the key's `app_display` entry.

**Server, new:** `sms::tests` produce the `lock` rows; `lock_glance::tests` (the package rules, `policy_apps` drops
the key, the card lists Meldinger first and only while managed, the row's own note, the no-access variant only when
not Off, the built-in's log keys); `tests/app_display.rs` (the key's row is saved and sent, `own` refused, a colour
only with a glyph); one intake test (a reported app under the prefix is dropped); one test of D1's two refusals.

**Server, existing tests that must change:**

- `tests/lock_glance.rs`: the default-row tests (`the_migration_gives_existing_devices_element_x_and_the_switch_off`,
  `a_new_device_gets_element_x_by_the_trigger_and_a_delete_cascades`, and whichever else counts a new device's rows -
  a device now has two); one new migration test (the seed follows the old switch); and
  `an_unreadable_table_sends_no_apps_and_the_rest_of_the_policy` (`:285-300`), extended to pin exactly
  `lock_glance == {"apps": [], "sms": "off"}` and `call_policy.sms_lock_text` false;
- `tests/device_pages.rs:734`: a scalar subquery on `device_lock_glance_apps` that assumes one row per device - it
  names the package now;
- `tests/device_api.rs:546-549` (`policy_json_keys_snapshot`): the `lock_glance` literal gains `sms`;
- `tests/sms_page.rs` (the switch is gone, an old post changes nothing, the link row only while managed) and the
  posts in `tests/calls.rs` and `tests/calls_simpler.rs`.

**Launcher:** `SmsContractTest` (the rows), `SmsGlanceTest` (`smsLockLevel`), `PolicyResponseCompatTest` (`:594`'s
`setOf("apps")` becomes `apps` and `sms`; `:616`'s not-an-object case keeps the apps unknown and reads `sms` Off; the
key's entry decodes), one `AppDisplay` test (a reserved name answers null from `entry`), `HomeModelTest` (a changed
look is a full rebind), one equality case (a changed look makes the lock's SMS row differ).

**Docs, in the commit that changes the code:** the root `CLAUDE.md`'s SMS contract paragraph (the `app` section and
`messages_app_v1`), `server/CLAUDE.md` (the migration's line and the next free number, the
`device_lock_glance_apps` line of the data model, `lock_glance` in the policy's route line), and the area notes the
change touches (`docs/notes/server/hardening-and-pins.md` "The lock's glance page", `launcher-ui.md` "App names and
icons", `calls-and-contacts.md`; `docs/notes/launcher/lock-and-glance.md`, `calls.md`).

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
the server's intake), and the Apps row is then also listed while the phone reports history. Its own sub (the stock
one names sync apps): "Off: Messages still receives and notifies, but the kid doesn't see it on Home, except during a
pause." / "Av: Meldinger tar fortsatt imot og varsler, men barnet ser den ikke på Hjem, unntatt i en pause." An older
launcher ignores it (the same update note as D10). Size **S-M** (the report key is most of it). **The core without it:** no switch for the
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
`VIBRATE`), for the reaction buzz's length: only while locked, not during a call or ringing, not when the ringer is
silent, not under Do Not Disturb or a time rule's silence, and never for a row set to Off. No sound.

**Where it is decided.** `noticeCame()` returns at `:1508` when the wake is not allowed - the wake switch off, its
gap, the hourly cap - and after it never wakes when the proximity sensor reads near. The buzz is decided **before
that return**, by its own pure plan beside `NoticeWakePlan`: independent of the wake switch and of proximity (a
pocket is where a buzz matters, and there the screen never lights), with its own gap between buzzes. It vibrates with
`USAGE_NOTIFICATION` attributes, so Android's own vibration settings apply.

Not recommended: a switch for Meldinger alone (the special case this note removes), or a buzz choice per app (a third
control on every row). Tests: `policy_json_keys_snapshot` / `PolicyResponseCompatTest` for the key and one pure plan
test beside `NoticeWakePlan`. Size **S-M**, server S + launcher S-M. **The core without it:** unchanged - a new
message lights the screen if the wake switch is on, and makes no sound or buzz while locked.

## 10. What does not change

The message engine, the receive and send rules, what is held for the parent, the system notification and its
channels, the unread badge, the apps' lock rows and their reader, the allowlist, the policy's `app_display` shape.

## 11. Steps and sizes

One branch, two commits that each build and pass alone; one server release and one launcher RC, either first. **No
launcher RC is cut between the steps**: step 1's launcher honours Off but claims `messages_app_v1` only in step 2, so
a build of step 1 alone would get D7's "update Vibb One" note while already hiding the row.

1. **The lock level** - `server/` and `launcher/` in one commit with the contract's `key`, `lock` and `lock_read`:
   the migration, `builtin.rs` and the reservation, `lock_glance.sms` and the derived `sms_lock_text`, the
   Notifications row and its words, Calls & Contacts' link row; the launcher's reader, level, hook and wake. Server
   **M**, launcher **S-M**.
2. **The icon** - the Apps row and the built-in form; the launcher's look helper in its three places; the launcher
   claims `messages_app_v1` here, and the contract gains `capability` and `display`. With W1 and W2 if chosen. Server
   **M**, launcher **S-M** (+ S for W1, + S-M for W2).

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
   today until you change it - or less: the text only while SMS is "Styrt av Vibb". (Today a phone with calls managed,
   SMS not, and the old switch on still shows the text of its old messages; after this it shows the name.) OK?
6. On a phone with an older Vibb One, **Off still shows the name and how many** until Vibb One is updated; the page
   says so on the row. The text never shows unless you chose Full text.
7. Meldinger sits right under Vibb One on the Apps page and first under Notifications. Say if it should sit
   elsewhere.

## 13. The design QA, finding by finding (revision 2)

No High, four Mediums, ten Lows. Each was checked against the code; all fourteen stand and are applied.

| # | | Where it went |
|---|---|---|
| 1 | M | D3: a failed read keeps today's fallback - `{"apps": [], "sms": "off"}`, `sms_lock_text` false, one read; §6 extends the test |
| 2 | M | D9: a colour only with a chosen glyph; `messagesLook()` reads the colour only when the entry has an icon |
| 3 | M | D7: the update note is its own field on the row, never `LockScreenApp.note`; every save returns to the row |
| 4 | M | D8: the link row only while `sms_mode` is `managed` |
| 5 | L | D7: Meldinger is the group's first row, with or without an allowlist (§12 item 7) |
| 6 | L | D7: the built-in's own security-log keys, en and nb, no package parameter |
| 7 | L | D2 and §12 item 5: "or less: the text only while SMS is managed" |
| 8 | L | D4's table: present but not an object -> Off |
| 9 | L | D7: the no-access variant only while Meldinger is listed and not Off |
| 10 | L | D9: the lock row carries the look; `AppDisplay.refresh` refreshes `SmsHome` on a changed entry |
| 11 | L | §6 and §11: the contract's `app` section by step; no launcher RC between the steps |
| 12 | L | D1: `toggle_app` and `perms_target` named; every other package field needs no refusal |
| 13 | L | §6: the existing tests and the docs that must change |
| 14 | L | §9: the buzz is decided before `noticeCame()`'s return, with its own gap and `USAGE_NOTIFICATION` |

Also corrected: the reaction buzz's length is not a number here (§1 item 1, §9).

## 14. As built (branch `meldinger-app`, 2026-11-10)

The user's answers to §12: W1 yes, W2 yes, W3 yes as §9 recommends (default off, its own third commit); points 4-7 as
written. So the branch has three commits, not §11's two. No emulator ran in the build round: what only a phone can
show is listed in the build report for the emulator pass.

**Step 1, the lock level** - as D1-D8, with these choices where the note left room or the code differed:

- **Migration 0135** (`0135_messages_lock_level.sql`). A device without a `device_policy` row is seeded at Name only.
- **`save_app_display` refuses the whole reserved prefix in step 1** (400); step 2 opens exactly the key. D1 asks for
  "exactly the known key" - until the built-in form exists there is nothing for a row under the key to do.
- **The row's own note is `LockScreenApp.hint`** (D7's "its own field"), drawn under the label in `device_lock.html`;
  `note` stays `None` for Meldinger, so the form never posts `unlisted=1`.
- **The card's tests are integration tests** (`tests/lock_glance.rs`), not `lock_glance::tests` as §6 says: the row's
  place and the mode gate are `lock_screen_card`'s (`handlers/devices.rs`), not the pure module's. The pure module's
  tests cover `messages_row`, the notes, the log keys and the package rules.
- **`device.lock.wake_sub`** read "For the apps on the lock below, missed calls and messages" on `main`; it is D7's
  wording now, in both languages.
- **The contract's rows.** `app.lock`: `{stored, sms_mode, sms, sms_lock_text}` (`stored` null = no row). `app.lock_read`:
  `{lock_glance?, fresh, sms_lock_text, sms, level}` - `lock_glance` absent = no key, `sms_lock_text` null = no
  managed call rules, `sms` the word read (`legacy` for a server without it) and `level` what the lock shows. The
  server's `the_app_lock_rows` produces every `lock` row and checks that each word it sends is read as itself.
- **`GlancePolicy.sms` has a default, `OFF`**: every construction that does not say otherwise fails closed
  (`UNKNOWN`); `SERVER_DEFAULT` says `LEGACY`.
- **The wake at Off**: `SmsNotifier.wakeIfAlerts` still spends the swipe mark (`SmsGlance.comeBack`, as before) and
  only then finds no row to gain - the level changes nothing but the wake.
- **The launcher's policy log line does not name the level**: `LockGlanceGuardTest` allows only the counts there.
- **`GlanceStore.refreshPolicy`** calls the hook before it decides whether the apps changed; a change of the level
  alone returns without the clear and the resweep (D5).

**Step 2, the icon, with W1 and W2** - as D9, D10, §7 and §8, with these choices:

- **The row is listed** while `sms_mode` is `managed`, while a row is stored for the key (a name, an icon or On Home
  off), or - W2 - while the phone's newest report with an `sms_state` says `has_history` (the code QA's S2, below). The sub adds "SMS is not managed by
  Vibb" whenever SMS is not managed (D10 names only the stored-row case; with W2 the history case reads the same).
- **The update note** shows only when the phone has reported and that report lacks `messages_app_v1` - never for a
  phone that has not reported yet.
- **The Apps row is a synthetic `UnifiedAppRow`** with `AppView.builtin`; `partials/device_app_row.html` draws its
  own short branch for it (the tile, the name, the sub, the form) - no switch, no permissions, no update line.
- **The preview** of a built-in's Automatic is the form's `data-auto-glyph` / `data-auto-tile` (`MESSAGES_LOOK`:
  `chat` on `#347470`), which `static/app-display.js` reads (`?v=5`); a chosen glyph with colour Automatic keeps that
  tile. The colours are not disabled at Automatic: a colour posted without a glyph is dropped by the save, as for
  every app.
- **A posted `own`** for the key is refused at the icon field with its own words, `apps.display.error.icon_builtin`
  ("Pick one of the icons or Automatic."); every other name under the prefix stays refused as no app's name.
- **W1's name in the PWA**: the Apps row (with "Messages" before the sub when a name is set, as an app's own name) and
  the Lock Screen row. Calls & Contacts' headings and the security log keep "Messages" / "Meldinger". A failed read of
  the name on the lock card is logged and the page's word is shown.
- **`sms_state.has_history`** is read by `sms::reported_history` from the newest report that carries an `sms_state`
  (the code QA's S2); a failed read is the Apps page's ordinary read failure, as its other reads.
- **The launcher's `MessagesLook` carries the name too** (`glyph`, `tile`, `name`), so a renamed Meldinger rebinds
  the Home tile and re-renders the lock's rows by the same two paths as a changed icon. `AppDisplay.entry` and
  `builtin` sit on two pure functions, `appEntry` and `builtinEntry`, which the tests read.
- **`AppDisplay.refresh` refreshes `SmsHome`** on a changed entry whether or not it reloads the apps (the start-up
  call passes `reload = false`).
- **The lock row's badge**: only the fill of `bg_glance_sms_badge` is recoloured (`GradientDrawable.setColor` on a
  mutated copy), so its ring stays; D9 says "tinted", which would have coloured the ring too.
- **The report's `has_history`** is the engine's own read at report time (`conversations().isNotEmpty()`), with
  `SmsHome.summary.hasHistory` only when the engine does not answer: the summary is empty in a new process until
  Home's first refresh, and the report must not say "no history" for that.
- **W1's six places** are §7's; Meldinger's header keeps `KidHeader.bind`'s string id and sets the name on the title
  after it. The notification channels' names and the notification's small icon are unchanged.
- **W2 on the phone**: `SmsHome.tileShown()` is the one place (`onHomeListable(entry, windowOpen) &&
  messagesTileShown(...)`, the window being the pause or the offline override, as `AppFilter`'s); Home's tile and the
  time-rule row both read it. Nothing in the MIT directories changed.
- **The contract**: `app.capability`, `app.display` (the entry exactly as the policy sends it, with `on_home:
  false`) and `report.sms_state.has_history`.

**Step 3, W3: the buzz** - as §9 (the owner's yes of 2026-11-10: one switch, default off, one short buzz, no sound),
with these choices:

- **The switch** is the cell `#lock-buzz` right under the wake's: its own form and route (`POST
  /devices/{id}/lock-buzz`, `lock_glance::save_buzz`, `device_policy.lock_buzz_on_notification`, migration 0136),
  saved, nudged and logged exactly as the wake's (`lock_buzz_changed`, "phone 3: buzz for a notification: on").
- **The plan** is `lock/NoticeBuzzPlan.kt` `noticeBuzzAllowed`; `PinLockRuntime.noticeBuzz` is the first thing
  `noticeCame()` decides once it has a context and its one read of what the phone is busy with (the code QA's L3),
  so none of the wake's returns is before it.
- **The gap** is `BUZZ_GAP_MS`, 10 s, in memory - §9 names no number. Rows that come together are one buzz; a notice
  a little later has its own. There is no hourly cap.
- **"Not during a call or ringing"** is the wake's whole `NoticeBusy`: our call, the system's, a VoIP ring or call,
  the emergency flow - and also a ringing alarm, the boot's mark and the first 2 minutes of a process (a new process
  reads the notifications that are already there, and has forgotten its last buzz).
- **The ringer**: Silent is no buzz; Vibrate and Normal buzz. **Do Not Disturb** is any interruption filter but
  "all". A ringer mode or a filter that can't be read is no buzz.
- **A time rule's silence** is asked of the rule itself too (`ZenRuleRuntime.ruleSilent()`), not only of the filter:
  the silence the time lock asks for counts where our Do Not Disturb rule could not be switched on, and while Find
  My Device's ring holds it off.
- **"Never for a row set to Off"** needs no code of its own: `noticeCame()` is reached only for a notice that gains
  a row on the lock (`GlanceStore.putPosted`, `SmsNotifier.wakeIfAlerts`) or a missed call's alerting post.
- **The buzz** is one 40 ms shot (`NOTICE_BUZZ_MS`, the reaction buzz's length) at the default amplitude with
  `USAGE_NOTIFICATION` attributes; the log says "Notice buzz" and nothing of the notice.
- **Not seen on a phone**: step 0's look at the Jelly Star was not part of this round, and no emulator ran. Open for
  the device pass: that Android plays the buzz from the background while the lock is up, that Android's own
  vibration settings switch it off, and that nothing else vibrates for the same notice (a phone outside lock task,
  or with Android's own lock screen as the gate, may let the notification itself vibrate).

**The code QA's round** (two reviewers, one per side: no High, no Medium; every Low fixed in one commit on top of
the three). No wire change, no contract change, no migration.

- **S1, a save never lands on an anchor that is gone.** `save_app_display` returned to `#app-me.vibb.builtin.messages`
  always - also when the save took the row's last reason away (Reset, or On Home back on with nothing else chosen,
  with SMS not managed and no reported threads), and without JS the page then opened at its top. For the key, when no
  row is kept and neither `sms_mode = 'managed'` nor `sms::reported_history` holds (`messages_row_listed`; a failed
  read counts as not listed), the save returns to `#allowed`, the group, which is always on the page.
- **S2, the threads survive a restart.** The launcher sends `sms_state: null` before the first unlock, and
  `reported_history` read only the last report - an unmanaged phone with old threads lost its Apps row after a
  reboot until the next report after an unlock. It reads the newest report that carries an `sms_state` now (`AND
  sms_state_json IS NOT NULL`). Left as it is: the status history keeps `STATUS_HISTORY_HOURS` of reports, so a phone
  that stays locked longer than that after a restart still loses the row until it is unlocked - the row comes back
  with the first report after the unlock, and nothing on the phone depends on it.
- **S3**: the policy test stores Full text before it says "a stored Full text goes out as the name", and checks
  `call_policy.sms_lock_text` false with it.
- **S4, the buzz switch says when the phone can't buzz yet.** The server may be updated before the phone, and the
  switch then saves and logs against a Vibb One that never buzzes. The buzz has no capability of its own: it ships
  in the launcher release that reports `messages_app_v1`, so that capability is the rule - the same as the Apps
  row's "older Vibb One" note. A phone that has reported and whose last report lacks it shows an orange line under
  the switch ("This phone does not buzz yet - update Vibb One", `device.lock.buzz_needs_update`,
  `LockTemplate.lock_buzz_needs_update`), whatever the switch says, so the parent reads it before switching it on;
  never before the first report.
- **S5**: `docs/testing/emulator.md`'s run log says how the level is set since migration 0135.
- **S6**: the Norwegian sub-line reads "Én kort vibrering, ingen lyd, ..." - "vibb" is the product's name, not a word
  for a vibration.
- **S7**: Calls & Contacts' link to the lock row takes the key from `builtin::MESSAGES` in the template.
- **S8**: the migration-order test's message names every migration up to 0136.
- **L1, a failed read never keeps the lock's rows.** `SmsHome.refresh` kept the whole old summary when the engine's
  read failed, so rows built at Full text stayed on the lock after the parent turned the level down. A failed read
  now drops the lock's rows (`summary.copy(glance = emptyList())`); the next good read brings them back. Kept: what
  Home shows - `hasHistory`, `unread`, `roleHeld` and `unreadByNumber` (the tile and the badges). They say nothing
  the kid hasn't seen, and dropping them would take the tile off Home for a read error.
- **L2, the level's rule is pure.** `smsGlanceRows(level, conversations, messages)` builds the rows - none at Off,
  where no message is read; the name and the count alone at Name only - and `smsGainsRow` takes the level (never at
  Off). `SmsHome.refresh` and `SmsNotifier.wakeIfAlerts` only call them; one test in `SmsGlanceTest` holds both.
- **L3, the buzz reads the system last.** `noticeBuzz` answers from memory first - the switch, `LockMode.LOCKED`,
  the gap (`noticeBuzzGapOver`) - before the ringer and the filter are read. `noticeCame()` reads `NoticeBusy`
  once and hands it to the buzz and to the wake's first gate (the gate after the proximity read reads it again,
  300 ms later, as before). `LockWakeTest` pins the order by source.
- **L4**: Meldinger's task label (Recents) follows W1's name at every render, not only at `onCreate`
  (`MessagesActivity.labelTask`, set only when the name changed).
- **L5**: two doc comments said the text shows while `call_policy.sms_lock_text` is on; they say Full text now.
