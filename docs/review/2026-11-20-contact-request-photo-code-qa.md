# Code QA: contact-request-photo (2026-11-20)

Branch `contact-request-photo`, commits 2eaaf2458 and e0bc1c3ee, on main 360dcf44a. Approved 2026-11-19: the kid's
contact request carries its picture.

Read: the whole diff (server, launcher, contract, template, i18n, docs), plus the code it leans on: `photos.rs` (decode,
`process_contact`, `Store::prune`/`recover_missing`/`add_to_zip`, the `FILES` lock), `device_routes.rs` (layering,
body limits), `handlers/calls.rs` (`add_contact`, `add_family_contact`, `view_photo`), `device_api::contact_photo`,
`backups.rs`, `retention.rs`, the launcher's `ContactRequests.kt`, `PictureEncoder.kt`, `MdmSyncWorker` call site.
No Gradle and no cargo run (the existing build covers the branch's own tests; nothing here needed an empirical check).

## Verdict

**No H, no M.** The feature is sound. The image path is bounded, authorisation is per phone, the file lifecycle is
lock-correct, the migration is additive, and the phone's retry is bounded. Six L findings and two notes follow. None
blocks the merge.

## What was checked and holds

- **The 64 KiB cap before buffering.** The route's own `DefaultBodyLimit::max(PHOTO_MAX_BYTES)` overrides the
  router's 512 KiB, the same pattern as `app_logos`. The `Bytes` extractor reads through `http_body_util::Limited`, so
  a large `Content-Length` is refused before any read, and a chunked body stops at 64 KiB + 1. Both give 413.
  `require_device_token` and `device_edge` never read the body.
- **The image parser.**
  - The size is read from the header before anything is decoded (`into_dimensions`, at most 512 a side).
  - `process_contact`'s `decode` then runs with the shared limits: JPEG/PNG/WebP only, at most 8000 a side,
    128 MB allocation. The worst case after the 512 check is about 2 MB (16-bit RGBA, 512²).
  - The work runs on `spawn_blocking` under `PROCESSING`, so a decoder panic becomes a `JoinError`, then
    `Undecodable`, then 422. It never takes down the server.
  - Re-encoding strips all metadata (EXIF/GPS, text chunks), and orientation is applied first.
  - 0×N images are refused in `decode`.
- **Authorisation.**
  - `photo_wanted` and the `UPDATE` are both scoped to `device_id = ? AND id = ? AND state = 'open' AND asked_at > live`.
    Another phone's id gets 409 and nothing is written (the contract has an `another_phones` row).
  - The phones' `GET /api/devices/contact-photos/{hash}` still checks only `device_contacts ⋈ contacts`, so no phone
    can fetch a request's picture. The kid's own phone gets it only once it is a contact's photo on that phone.
  - `/contact-photos/{hash}` is behind the admin session.
  - `asked_at` is clamped to now at intake, so a future clock cannot keep a request, or its picture, live.
- **Files versus prune.**
  - The upload stores both files and writes the row while holding `lock_files()`; the guard lives to the end of the
    `stored` block, through the `UPDATE`'s await.
  - `Store::prune` takes the same lock, and `referenced_sql` now unions `device_contact_requests` for both stores. A
    waiting request's files are never pruned.
  - Add Contact copies the three columns to the contact before `mark_added` clears them, in one transaction, so a
    concurrent prune always sees the hash named by one row or the other.
  - Decline runs `UPDATE` then `prune`. On an upload that loses the race (`rows_affected == 0`, a 409), the upload
    prunes its own files.
- **Races between upload and add/decline.**
  - Decline before the upload's `UPDATE`: 409, then prune. Decline after: the columns are cleared, then prune.
  - Add after the upload: the picture is copied (new number) or dropped.
  - Add before the upload's `UPDATE`: the upload gets 409, then prune.
  - The only rough edge is L3 below.
- **Backup and restore.**
  - `backups::build_backup_zip` adds every file in both directories.
  - `recover_missing` (startup, after migrations) checks the request rows through the new `referenced_sql`. A lost
    square or original forgets the request's picture whole (both `forget_sql` lists clear all three columns), which
    keeps the "all three or none" invariant.
- **Retention.**
  - Lapsed or decided rows lose their columns; `run_pruning` prunes the files when either count is above zero.
  - A deleted phone's rows cascade (`ON DELETE CASCADE`), and `handlers/devices.rs` prunes after the delete.
  - `tests/cleanup.rs` knows the new `Pruned` field.
- **Migration 0160.** It has three nullable `ADD COLUMN`s, with no default and no rewrite, and is instant on the
  user's DB. 0160 is free on `origin/main` and every unmerged origin branch (`server-small` stops at 0159), and
  `migrations_from_73_in_order` lists it.
- **The launcher.**
  - Photos go only for ASKED records with `photo && !photoSent`, after the request loop, in the same sync that got
    ACCEPTED (records are re-read).
  - DONE is persisted through `update()`, which compares lists, so the new field makes it write.
  - RETRY ends the pass. An ASKED record lives at most 7 days (`reconcileContactRequests`), and at most 5 are open,
    so retries are bounded.
  - A missing or unshrinkable file is DONE without a post.
  - The id is 32 hex and goes in through `@Path`.
  - `Response<Unit>` works on Retrofit 2.11.
  - `Bitmap.compress` writes no EXIF.
  - The picture is decoded by the main process from its own encode (D8 holds).
  - No API above 34 and nothing version-dependent: Android 14-17 is unaffected.
  - `photoSent` defaults to `false`, and there is no old-format handling (none is needed).
- **The contract.** `photo` is byte-identical in both copies. The server produces `answers` on both listeners; the
  launcher maps every status the server answers. Both 413 cases come from the real limit.
- **The PWA.**
  - One key in each language (`device.requests.photo`: "Bilde fra {name}" / "Photo from {name}"), short, with no
    explanation line.
  - The request row's anchor (`#request-<id>`) and both redirects (`#requests`, `#contact-<id>`) are unchanged, so a
    save never jumps to the top.
  - The CSS classes used exist (`group-small`, `avatar-small`, `avatar-photo`).
  - The `<img>` has `alt=""` next to the name: decorative, correctly.

## Findings

### L1: a failed file store leaves an orphan until some later prune

`server/src/handlers/contact_requests.rs:358-372`

**Scenario:** the original stores, then the square fails (disk full). The handler returns 500 from inside the block
without calling `photos::prune`, so the original sits unreferenced. `retention::run_pruning` prunes only when a
request row changed, so the file can stay until the next Add Contact, decline or photo change. The phone retries;
with the disk still full this repeats each sync (bounded, see L5).

**Fix:** do not return from inside the block. Let `written`'s error fall through to the existing
`Err(err) => { prune; 500 }` arm. Either map it into the same `Result`, or call `crate::photos::prune(&state)` after
the guard is dropped.

### L2: WebP is accepted under an `image/jpeg` label; the contract says JPEG or PNG

`server/src/photos.rs:627-639` (`process_request_photo`), contract `photo.content_types`

**Scenario:** the 415 check reads only the header. `decode` sniffs the bytes and also allows WebP, so a WebP body
labelled `image/jpeg` is stored. This is harmless: it is re-encoded, and the decoder is the one parents already use.
But the route accepts more than its contract says, and widens the decoder surface a token-holder can reach.

**Fix:** in `process_request_photo`, take the format from `with_guessed_format()` and refuse anything but
`Jpeg | Png` with `PhotoError::UnsupportedType`. That is a 422, or map it to 415.

### L3: a picture arriving during the parent's "Legg til" save fails the save with a database error

`server/src/handlers/calls.rs:2197-2210` (`add_contact`'s deferred `begin()`)

**Scenario:**
1. The transaction starts as a reader: `SELECT device_policy`, then `from_request`.
2. The phone's `UPDATE device_contact_requests SET photo_hash…` commits between that read and the
   `INSERT INTO contacts`.
3. In WAL mode the reader's upgrade to a writer fails with `SQLITE_BUSY_SNAPSHOT`. `busy_timeout` does not retry it.
4. The parent gets `db_error`. Saving again works.

The window is milliseconds, right after a request arrives, which is exactly when a parent tapping a push might be
saving. No data is wrong.

**Fix:** `state.db.begin_with("BEGIN IMMEDIATE")` for `add_contact`'s transaction, as `intake` already does. The
upload's `UPDATE` then waits on the 5 s busy timeout and finds the row `added` (409, prune). Optional; a retry also
recovers.

### L4: the save can copy a picture the parent's form never showed

`server/templates/device_calls.html:457-458`, `server/src/handlers/calls.rs` (`add_contact`'s copy)

**Scenario:** the form renders before the picture's upload lands; the save reads the row again and copies it. The
copy is unconditional by design (approved), and it reaches only a contact new to the family, on this phone, whose
kid already sees the same picture locally (design 79 hand-over). So the effect is nil in practice.

**Fix:** none needed. If wanted, post the shown hash as a hidden field and copy only when it equals the row's hash.

### L5: a persistent server-side 500 re-sends the picture every sync for up to 7 days

`launcher/app/src/main/java/com/kidslauncher/mdm/calls/ContactRequests.kt:478-498`

**Scenario:** the disk is full or the photo directory is unwritable. Each sync posts up to 64 KiB and the server
decodes it again, at most 5 requests × 7 days. This is bounded and matches the request's own retry, so it is
acceptable.

**Fix:** none required. Optionally cap the retries (a `photoTries` counter, DONE after N).

### L6: dead size check

`server/src/handlers/contact_requests.rs:334`

`body.len() > PHOTO_MAX_BYTES` can never be true: the route's `DefaultBodyLimit` makes the `Bytes` extractor answer
413 first. It is harmless.

**Fix:** keep it as a belt (it documents the limit), or drop it.

## Notes

- **N1:** `view_photo` serves with `private, max-age=31536000, immutable`
  (`server/src/handlers/calls.rs:2932`, existing). A declined request's picture (often a third person's face) stays
  in the parent's browser cache after the server deletes it. This is the same as every removed contact photo and
  stays on the parent's own device. If it should change, request pictures could be served under `no-store` until
  they become a contact's.
- **N2:** the photo upload runs after the request loop, so a pending request that answers RETRY (`:464`) also holds
  back the pictures of already-accepted requests until the next sync. Harmless; the order is as documented.
