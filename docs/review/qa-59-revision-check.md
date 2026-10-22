# QA 59 - revision check (`docs/design/59-android-keyguard-design.md`, `70c93e38`)

Checked 2026-10-22 against `qa-59-design.md` (Q59-1..14, N1-4), the prototype QA (P59-1..8) and the code on
`proto-59-run` (server `src/`, launcher `mdm/`). 0 High, 2 Medium, 4 Low, 2 Nit new. Not committed.

## Findings

- Q59-1 resolved. `verifyCommandPin(kidLock, pin)` on the fresh policy, no backoff, no failure, still the only
  `PinHash.verify(` caller (`ParentPinCallersTest:32`, now `PinLockRuntime.kt:1569`); test names the differing-hash case.
  Implementer: `dispatchPendingCommand` (`MdmSyncWorker.kt:353`) takes only `pending` today - it needs the fresh `kidLock`.
- Q59-2 resolved in the body (first CE start of a boot UNLOCKED, later LOCKED, crash guard `lockNow`, test). Gap: N-A.
- Q59-3 resolved. `seenLocked` gone; the table leaves only on the pad's success or a `USER_PRESENT` since the edge;
  RELOCK after the 1 s wait; §4 and the mutation list match.
- Q59-4 partly. Rollback only on fresh/`Ok`, write-ahead flag, token kept running: all in §3.4/3.8/7. But the two
  mechanisms are not yet buildable as written: M-1 (the flag) and L-1 (provenance).
- Q59-5 resolved. `saveConfig` commits `pin_rev` with the hash; each path commits the rev of the hash it checked.
- Q59-6 partly. `COALESCE` + `kid_pin_prev_rev` + "shown only while the phone's rev = prev_rev" is right, but the SQL
  has a hole for a legacy PIN: M-2.
- Q59-7 resolved (waiting fragment = the pop's predicate, intake heal, capability on delivery, `null` = open question 4).
  I checked the pop (`handlers/device_api.rs:347-397`): oldest-first, so a redelivery-eligible old row pops before the
  new one for one sync, runs nothing (`CommandAnswers.begin`), then the new one goes: the claim holds. Two smaller
  points: L-2, N-B. Capability gate: step 4 in §1 and §6, consistent (Q59-13 resolved).
- Q59-8 resolved. `guarded=` verdict dropped (§3 intro), §3.9 says the keyguard holds Recents, §8 step 1 is the
  baseline on today's lock and step 5 counts only if it leaks there, `kg` in each Send, the square tap expected.
- Q59-9 resolved (P59-1/3/4/5/6/7/8 named in §3 and step 3; 24b skip = "android requested", §3.7; P59-2 is §8 step 1).
- Q59-10 resolved as a stated trade: default kept, open question 3 with the QA's options.
- Q59-11 resolved. Reveal POSTs to the Lock page's own path, renders (no redirect), `no_store_path` gets
  `["devices", _, "lock"]`, one `reauth` helper for the reveal, `change_password` (`handlers/auth.rs:391`) and
  `reset_totp` (`:772`). `/devices/{id}/lock` is GET-only today (`main.rs:863`): a new POST route, not named as new.
- Q59-12 resolved (§3.6: none -> set; inactive + no credential -> set; inactive + credential -> wait; `Unreadable` = none).
  Ownership: L-3.
- Q59-13 resolved. Q59-14 resolved (quick off/on and fingerprint-within-1 s in steps 4/11, `am crash` in 13, the three tags).
- N1-N4 resolved (`keys::load_or_create`, both key pins named, capability tells an older launcher, one-shot "ours" flag;
  clearing the PIN sets own). Path nit: `tests/step10.rs` is `server/src/tests/step10.rs`.

## New problems

- **M-1 (Medium) the stored flag means two things.** §3.1: `saveConfig` commits `keyguard_android` "with the hash"
  on every apply, i.e. what the policy requests. §3.4/3.5/3.8: the same name is written ahead of a reset and cleared
  only when no credential is left, and §3.8's edge is "stored `keyguard_android` true and the policy says own". If
  `saveConfig` writes the policy's value, the android->own apply erases the edge before the rollback reads it (and a
  Fallback/`Apply(null)` apply would erase it too, undoing Q59-4). Fix: two names - `keyguard_requested` (policy,
  `saveConfig`, what the process start reads) and `keyguard_android` (we own a credential: set ahead of a reset,
  cleared only after a verified clear); the rollback edge = owns && !requested.
- **M-2 (Medium) `prev_sealed`/`prev_rev` COALESCE out of step for a legacy PIN.** Legacy: `sealed` NULL, rev 0. Save B:
  `prev_sealed = COALESCE(NULL,NULL)` stays NULL, `prev_rev = COALESCE(NULL,0) = 0`. Save C: `prev_sealed` becomes B
  (it was NULL), `prev_rev` stays 0 - the page then shows B as "the phone's PIN" for a phone reporting rev 0, which holds
  the legacy PIN. Same when the key was off at an earlier save. Fix: key both on `kid_pin_prev_rev IS NULL`
  (`CASE WHEN kid_pin_prev_rev IS NULL THEN ... END`); prev_rev set with prev_sealed NULL = "an earlier PIN". Add the
  legacy case (legacy, B, C) to the §7 save test.
- **L-1 (Low) rollback's "fresh or `Ok`" has no carrier.** `configure` reads `policy?.kidLock` (`PinLockRuntime.kt:1176`)
  from `AppEnforcer.apply(context, decision.policy)`, and `onPasswordChanged` passes `currentPolicyDecision().policy`:
  the `Fallback`/`Apply` type is dropped, and a Fallback's null `kidLock` equals a fresh "no PIN". Step 3's brief must say
  the decision (or a `carriesParentChoice` bool) travels to `configure`; the test "never on a Fallback" then runs through it.
- **L-2 (Low) intake can queue a duplicate.** A command just delivered is not "waiting"; a status posted in that window
  (before the phone ran it) with the old `pin_rev` queues a second `set_kid_pin`. Harmless (same PIN) but noisy; treat "delivered
  within the redelivery window, no answer yet" as in flight in the intake check only. Also: a phone updated after the
  switch was set reports `keyguard: own` with a current rev, so intake never queues for it - it waits for the keypad
  migration though a sealed PIN exists; queue on `own` reported + server `android` + capability too (or accept in writing).
- **L-3 (Low) §3.6 sits in step 4 but step 3 needs it.** §3.4 (migration) and §3.8 ("`ensureResetToken` keeps running so
  escrow finishes the rollback") are step 3. Move `ensureResetToken` (and `reset_token` in the report) to step 3, or say
  step 3 keeps 24b's `resetTokenNeeded` (fine for the normal path) and step 4 swaps the rule.
- **L-4 (Low) `android_boot_count` write is unspecified (N-A).** The first-CE-start test needs the value written at
  every CE process start in every mode (or `PinLockStore`'s existing `MARK_BOOT`, `:101`, can't be shared - it is consumed
  by the boot mark). Otherwise the first android-mode start after a mid-boot migration reads "first" and starts UNLOCKED
  (the Q59-2 (b) case). One line in §3.1.
- N-B (Nit) §1 says an old launcher's `set_kid_pin` "is queued"; §2.1's queue rule inserts only with the capability in
  the last report, so none is. Reword. Clearing the Child PIN (sets own) should also cancel a waiting row like the switch does.
- N-C (Nit) a rollback pending with no token reports `inactive: android_credential` (§3.8) while §1 says android mode
  reports `inactive: null`; §2.3's "Switching back..." needs `keyguard: android` in the same report. Say which wins.

Spot-checks that held: `WindowRelockPlan.kt:127-128`, `PinLockRuntime.kt:1176`, `ParentPinCallersTest:32`,
`device_api.rs:385-397` (now the retire block), `kid_lock.rs:95` (`#[serde(default)]`), `Inactive::AndroidCredential`
(`kid_lock.rs:205`), `admin_auth.rs:473` `no_store_path` and its test, migration 0085 free, `keys::load_or_create`,
`MusicKey::from_bytes`/`seal_with`/`open_with`.
