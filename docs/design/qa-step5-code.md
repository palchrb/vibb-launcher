# QA step 5 - code review (UI, photos, badges, i18n, airplane switch)

Reviewed: S `bd70f8a`, `454f1d3`; L `84724fb`, `42d8e44`, `782ff0a`, `c371fab` (branch `handy`). Tests run:
server `cargo test` 76 passed; launcher `testDebugUnitTest` green. Decoder limits checked with a scratch
program against the locked `image 0.25.10`. **No High findings.** Steps 1-4 paths re-checked and unchanged:
`HomeActivity.onResume` still calls `redirectToLockScreenIfLocked()` before it renders anything. Grid and drawer
use the same `AppFilter` (suspended, kid-hidden and blocked system dialer left out), and the drawer's Settings is
still PIN-gated. Home contacts and the sheet call only numbers from `phoneBookView` (the same source as before). The
DIAL/`tel:` path still goes through `decideOutgoing`. 112 shows only for `UnknownFailClosed`. The long-press menu is
hide/rename only, as before. There are no widgets. App info is reachable only through the old `AppAction`
launch-failure dialog, which the drawer already had. `PhoneBookActivity`/`HomeActivity` stay non-direct-boot. The
photo cache and seen marks are CE-only, and `bitmap()` returns null before unlock.

## Findings

1. **Medium - L `calls/ContactPhotos.kt:52-58` (decode) + `ui/home/KidAvatars.kt:37`.**
   `BitmapFactory.decodeFile` runs with no bounds check and no `inSampleSize`, on the main thread, inside
   `catch (e: Exception)`. An `OutOfMemoryError` is not an `Exception`. The 1 MB cap and the SHA-256 check don't
   help: the hash comes from the same server, so they only prove that the bytes match the policy. A ≤ 1 MB JPEG
   can declare something like 16000×16000 (about 1 GB ARGB). Decoding it would crash Home on every `render()`,
   which is a crash loop of the default launcher/kiosk app (inferred: not run on a device). The trigger needs a
   compromised server/DB or a server bug, because the server always re-encodes to ≤ 512 px.
   **Fix:** decode with `inJustDecodeBounds` first and reject anything over 1024 px a side (or downsample to
   avatar size). Catch `Throwable`/`OutOfMemoryError` and fall back to the initial. Decode off the main thread.
   Add a JVM test for the size gate (pure helper).
2. **Low - S `src/photos.rs:68-89`, memory per upload.**
   The limits work: 9000 px a side → "Image size exceeds limit" for PNG and JPEG (verified). But `max_alloc`
   only covers the decoder's buffer. A 1.2 MB PNG of 7990×7990 RGBA decodes to 255 MB. `apply_orientation`
   (rotate) and `crop_imm` then each make a full copy, which measured **~490 MB peak RSS** per upload. Uploads
   have no concurrency limit, and a Pi with 1-2 GB can OOM-kill the server. Only the admin session can upload,
   hence Low.
   **Fix:** cap pixels, e.g. ≤ 40 MP / `max_alloc` 64-128 MiB. Shrink before rotating/cropping: crop with
   `image.view`/`crop` in place, then `thumbnail`, then orient the 512 px result. Gate `process` behind a
   `Semaphore(1)`. Add a test that an image over 8000 px is rejected (none exists today).
3. **Low - S `src/photos.rs:126-160` + `handlers/calls.rs:597-614`, prune race.**
   `store()` writes `<hash>.jpg` before `set_photo` commits `photo_hash`. A concurrent `prune` from another
   request (a second upload/remove, or a contact/device delete) lists the referenced hashes in between and deletes
   the new file. The DB then points at a missing file: the admin page shows a broken image and the device gets
   404 on every sync. `prune` also deletes another request's in-flight `.<hash>.tmp`, so that `rename` fails →
   500. This needs two parent actions at once (two tabs or two parents).
   **Fix:** serialise store+commit+prune with one `tokio::sync::Mutex` in `AppState`. Alternatively, have prune
   skip `.tmp` files and files younger than about a minute.
4. **Low - S restore/missing files (design "Open").**
   After a DB-only restore, `photo_hash` still names files that don't exist. Every sync downloads, gets 404, logs
   a warning and retries forever, and the calls page shows broken `<img>`.
   **Fix:** on startup (or on restore), set `photo_hash = NULL` where the file is missing.
5. **Low - L `ui/LauncherLocales.kt:17-27` (called from `server/MdmSyncWorker.kt:114`), locale switch side effects (inferred).**
   `setApplicationLocales` recreates every running activity. `InCallActivity` (`configChanges` lacks
   `locale|layoutDirection`), `LockActivity` with an open PIN dialog, and a `ContactSheet` `Dialog` (which logs a
   WindowLeaked) all get torn down if the parent changes the language during a call or bedtime.
   **Fix:** add a device check covering a language change during an active call and with LockActivity up. If
   `InCallActivity` misbehaves, defer `apply` while a call is active (`KidInCallService` has state) or add
   `locale|layoutDirection` to its `configChanges` and rebind the texts.
6. **Low - L `ui/HomeActivity.kt:358-370` (`render`), performance (inferred).**
   Every badge/photo/app/pref change re-runs `AppFilter` on the main thread (`isPackageSuspended` per app plus
   `hasUserRestriction`) and `cachedPolicy()`. It also rebuilds the contacts row and calls
   `notifyDataSetChanged`. Chatty notification sources (each count change) will jank Home.
   **Fix:** cache the filtered list and recompute it only on app/pref/policy changes. For badges, only rebind
   the badge views.
7. **Info - L `calls/ContactPhotos.kt:83-111`.**
   Two syncs running together (periodic plus FCM nudge) share the same `.<hash>.tmp`, and each `finally`
   deletes the other's file. Harmless (retried next sync); a per-call unique temp name avoids it.

## Verified OK (no action)
- Photo route `GET /api/devices/contact-photos/{hash}`: bearer layer; 64-hex check before any path is built; only
  served when a contact on *this* device has that hash (other device → 404, tested). The admin `/contact-photos`
  route sits behind full auth. Type is sniffed by content, so SVG/GIF are rejected (tested). Re-encoding drops
  EXIF/GPS (tested). The DB `CHECK` enforces the hash format. The launcher validates the hash before using it as a
  file name/URL and checks the SHA-256, using temp file + rename.
- `BadgeListenerService` reads only package, flags and `number`; it keeps counts in memory only; `getActiveNotifications`
  failures are caught; only a boolean goes to the server. It is exported but guarded by
  `BIND_NOTIFICATION_LISTENER_SERVICE`.
- i18n: 153/153 strings, the same plurals in `values-nb`, and no stray `%` in either file. A script checked every
  `getString`/`getQuantityString` call site: argument count and types match the placeholders (`%1$s`/`%2$d`).
- Airplane: `AIRPLANE_MODE(false)` → `DISALLOW_AIRPLANE_MODE`, managed-only. The server defaults to off; both
  sides have tests.

## Test gaps
- S: no test for over-limit dimensions (8000+ px), for `/contact-photos/{hash}` without a session, or for the prune race.
- L: `TranslationsTest` regex only knows `%s`/`%d` (`%f`, `%,d` and `%%` would slip through). It compares sets,
  not each quantity. Nothing tests photo decode bounds (#1).
