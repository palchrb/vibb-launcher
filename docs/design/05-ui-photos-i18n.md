# Step 5: launcher UI, badges, contact photos, i18n, airplane-mode switch

PLAN "Next features (agreed 2026-10-05)". Mockups (320×568 dp): Main = Home, PhoneBook = grid + sheet,
ContactCard = sheet with photo. S = kid-phone-server, L = kids-launcher-mdm, branch `handy`. Steps 1-4 rules
(fail closed, call rules, direct boot, schedule lock, hardening) are unchanged.

## Decisions

- **Home** (`HomeActivity`, `activity_home.xml`): ground `#14213D`, white ink. Big `TextClock` (52 sp, bold) +
  localized date ("søndag 5. oktober", best pattern `EEEEdMMMM`), Android's own status bar above (kiosk keeps
  `SYSTEM_INFO`). Row of Home contacts: 76 dp avatar (photo or initial on the palette colour), green 28 dp call
  badge, orange missed-call badge (icon + count) top-right; tap and long-press open the
  contact sheet (changed 2026-10-06; it was "tap calls at once", 02 decision). Below a `GridLayoutManager` grid, **3 columns, 4 if the parent says so**: Phone book tile first
  (green, book icon), then every app the drawer would show (`AppFilter`: suspended/hidden apps, kid-hidden apps and
  the blocked system dialer are left out), alphabetical, 60 dp round icon + 13 sp label + orange unread badge.
- **Drawer stays** behind swipe-up: same `AppFilter`, so it shows nothing the grid doesn't; it is the way to the
  PIN-gated Settings. Swipe-left Quick Controls unchanged. The "add to home screen" (minimalist) menu entry is
  hidden on the grid (all apps are on Home now); hide/rename stay. LockActivity, kiosk and the bedtime redirect run
  exactly as before (`onResume` order unchanged).
- **Phone book** (`PhoneBookActivity`): back button + title, 3-column grid of 64 dp avatars with missed badges; the
  "Emergency call" row becomes a red "112" tile (no readable rules); calls-off/empty texts above. Tap → bottom sheet
  (`ContactSheet`, a bottom `Dialog`): handle, 132 dp photo with a 4 dp ring in the contact colour, missed badge, name 26 sp,
  missed text ("1 tapt anrop i dag 13:05", red), Call (green) and Message (blue) buttons 64 dp, "Message opens in
  <app label>" (label from PackageManager), Close. `tel:` dialogs unchanged.
- **Missed calls**: from the call log (`READ_CALL_LOG`, already self-granted), last 14 days, `MISSED_TYPE` only
  (rejected/blocked calls never count). Count per contact = missed calls after the latest of: "seen" mark (kid opened
  the contact sheet, or tapped Call/the Home button) and the last outgoing or answered call with that number
  (calling back clears it). Seen marks live in CE prefs `missed_calls_seen` (number → ms).
- **App badges**: `BadgeListenerService` (NotificationListenerService) counts active, clearable, non-summary
  notifications per package (`notification.number` when > 0, else 1), our own package excluded, held in memory
  (`BadgeStore`) and pushed to Home. **Permission**: there is no device-owner API for notification-listener access
  (`DevicePolicyManager.setPermittedCrossProfileNotificationListeners` only limits work profiles;
  `NotificationManager.setNotificationListenerAccessGranted` needs `MANAGE_NOTIFICATION_LISTENERS`, a system
  permission), and Settings shows "restricted setting" for sideloaded apps. So: adb at provisioning, before
  enrolling (debugging is blocked afterwards): `adb shell cmd notification allow_listener
  me.vibb.launcher/com.kidslauncher.mdm.badges.BadgeListenerService` (package `me.vibb.launcher` since 2026-10-06). The status report sends
  `notification_listener_enabled`; the device page warns when it is false. Without it the grid just has no badges.
- **Contact photos**: one photo per address-book contact (shared by every device that has it, like the name).
  Upload on the calls page (multipart, ≤ 10 MB, JPEG/PNG/WebP sniffed by content, not by name). **Re-encoded
  server-side** with the pure-Rust `image` crate, already in the tree via `totp-rs`'s QR feature (png only); we add
  its `jpeg` and `webp` features (zune-jpeg / image-webp, pure Rust). Decode with limits (≤ 8000 px a side,
  ≤ 256 MiB), apply EXIF orientation, centre-crop square, resize to ≤ 512 px, encode JPEG q85 - which drops all
  metadata (EXIF/GPS/XMP). Runs in `spawn_blocking`. Stored as `data/contact_photos/<sha256>.jpg`
  (`AppState.photo_dir`), `contacts.photo_hash` = the hex SHA-256 of the stored file. Files no contact references are
  deleted after every replace/remove/contact or device delete. Not in backups (DB only) - after a restore the
  parent re-uploads; the device keeps showing the initial.
- **Device route** `GET /api/devices/contact-photos/{hash}` (bearer): 404 unless the hash is a 64-hex string and a
  contact on *this* device has it. `call_policy.contacts[].photo` = hash or null. Admin view route
  `/contact-photos/{hash}` (session) for the thumbnails.
- **Launcher cache**: after every accepted sync, `ContactPhotos.sync` compares wanted hashes (managed rules) with
  `filesDir/contact_photos/` (CE storage): downloads missing ones (≤ 1 MB, SHA-256 must equal the name, temp file +
  rename), deletes unwanted ones. Decoded bitmaps in a small LRU. `RuleContact.photo` is in `last_call_rules` (CE);
  the DE boot copy (`BootCallPolicy`) carries numbers only - **nothing photo-related goes to DE**, and the
  direct-boot `InCallActivity` shows no photo.
- **i18n (L)**: every string in `values/strings.xml` (English default, `unqualifiedResLocale=en`) gets a
  `values-nb` translation; hard-coded UI text moved to resources; plurals for counts. Per device:
  `launcher_ui.language` = "system" | "nb" | "en" (server device page), applied with
  `LocaleManager.setApplicationLocales` after an accepted policy, only when it differs (Android persists it;
  unknown value → system). Server text stays English.
- **Policy** gains `launcher_ui: {language, home_columns}` (always sent) and `hardening.disallow_airplane_mode`.
- **Airplane mode**: per-device switch `disallow_airplane_mode`, **default off** (the family travels) →
  `UserManager.DISALLOW_AIRPLANE_MODE` while managed; like the other hardening switches not lifted by override/pause.

## Files

- S: `migrations/0024_launcher_ui_photos.sql` (`contacts.photo_hash`, `device_policy.launcher_language`,
  `home_columns`, `disallow_airplane_mode`, `device_status.notification_listener_enabled`), `src/photos.rs` (process,
  store, prune), `handlers/calls.rs` (upload/remove/view), `handlers/device_api.rs` (route, `photo`, `launcher_ui`,
  status field), `handlers/devices.rs` (launcher card, airplane switch, warning), templates, `models.rs`, tests
  `src/tests/launcher_ui.rs`.
- L pure: `ui/home/HomeModel.kt` (grid model, columns, avatar style, badge text), `calls/MissedCalls.kt`,
  `badges/BadgeCounts.kt`, `calls/PhotoCache.kt`, `ui/LauncherLocale.kt`; Android: `ContactPhotos.kt`,
  `BadgeListenerService.kt`/`BadgeStore.kt`, `HomeActivity` + adapters, `PhoneBookActivity` + `ContactSheet`,
  layouts/drawables, `values-nb/strings.xml`, `Hardening.kt` (+ `AIRPLANE_MODE`), DTOs.

## Tests

- S (TestApp): upload JPEG/PNG → stored re-encoded JPEG without EXIF, hash in `call_policy`; bad type / too big /
  undecodable → 400, nothing stored; replace/remove prunes the old file; device route only for its own contacts,
  needs bearer, rejects bad hashes; `launcher_ui` defaults + form round trip + invalid values; airplane switch
  default off + round trip; listener warning; key snapshot updated.
- L (JVM): grid model (phone-book tile, columns 3/4/invalid, badges, 99+), avatar initials/colours/emergency,
  missed-call counting (seen, call-back, answered, other numbers, rejected), badge counts (ongoing, summary,
  `number`, own package), photo cache plan (download/delete/invalid hashes), locale resolution, hardening
  airplane default, DTO compat (old policy without the new keys).

## Implementation status (2026-10-05)

Done on `handy` (not pushed). S: `bd70f8a` (migration 0024, `photos.rs`, routes, Launcher card, airplane switch,
listener warning, tests), `454f1d3` CLAUDE.md. L: `84724fb` (DTOs, `AIRPLANE_MODE`, pure models + tests), `42d8e44`
(Home, phone book, sheet, `ContactPhotos`, `MissedCallsRepo`, `BadgeListenerService`, `LauncherLocales`), `782ff0a`
(`values-nb`, `TranslationsTest`), `c371fab` CLAUDE.md. Server 76 tests (was 67), `fmt --check` clean, clippy unchanged
(25 warning lines). Launcher 191 JVM tests (was 168), `assembleDebug` + `assembleRelease` build. Lint doesn't run
offline (androidTest deps not cached), so `TranslationsTest` checks keys/placeholders. No emulator here: the UI is
build-checked only - the device checklist below is the real test.
Choices: home-contact tap calls, long-press opens the sheet (since 2026-10-06 both open the sheet); drawer kept; old minimalist list removed; every string
translated (not only handy's); photos pruned by scanning the directory; `InCallActivity` shows no photo (direct boot).
Fix round after `qa-step5-code.md` (#1-#7): S `5b83dc3` (40 MP/128 MiB cap, crop+shrink before rotate, one upload at
a time, one file lock for store/commit/prune, photos in backups + startup recovery or NULL); L `32b1d24` (bounded
background decode, 404s remembered), `3f36a66` (language only from Home, not in a call; filtering off main, debounced
badges), `496081a` (TranslationsTest per quantity). Tests: server 80, launcher 194. Open: `allow_listener` after reboots
on the Jelly Star; icon look; device check that a language change mid-call waits until Home.

## Device checklist (Jelly Star, release; plus 02/04 checklists)

1. Home matches the mockup at 3 and 4 columns; clock/date in nb and en; status bar visible in kiosk.
2. `cmd notification allow_listener …` before enrolling; Element X message → badge with count, gone after reading;
   the server warning disappears. Without the grant: no badges, warning shown.
3. Missed call from an allowed contact → badge on Home and in the phone book; opening the sheet or calling back
   clears it; a rejected stranger never shows up.
4. Upload a portrait phone photo with GPS EXIF: upright on the phone, the file under `data/contact_photos/` has no
   EXIF (`exiftool`); replace/remove reaches the phone after a sync and old files are gone from `filesDir`.
5. Before first unlock after reboot: incoming call from a contact still works (no photo, no crash).
6. Language nb/en/system from the server switches the launcher without a restart loop; LockActivity, phone book,
   in-call screen, PIN dialogs are translated.
7. Airplane switch on: airplane mode greyed in Settings/power menu/QS; off: works. Override/pause don't lift it.
8. Bedtime with the new Home: LockActivity still comes up, Home/Recents don't bypass it, Emergency call works.
