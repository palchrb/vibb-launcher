# QA L2 (code): READ_PHONE_STATE for allowlisted apps (design 57 (d))

Commit 045b63d9 on main 199c9429, branch `launcher-enforce`. Reviewer: code QA, 2026-10-26.

## Verdict

- **Merge.** 0 High, 0 Medium, 3 Low, 3 Nits. None blocks; Low 2 needs the user's nod, Lows 1 and 3 are small follow-ups.
- The grant's scope is right and minimal: non-system, not ours, requests CALL_PHONE/ANSWER_PHONE_CALLS *and* READ_PHONE_STATE, in the effective allowlist (all of them when apps are unmanaged). The override and the pause widen nothing.
- The state machine is sound: GRANTED only while `denyCallPermissions`, DEFAULT otherwise, read-before-write, exceptions logged per write.
- The release (DEFAULT alone) is safer than the sketch's (DENIED then DEFAULT), but it differs from the agreed sketch and the docs don't say so yet (Low 2).
- "Q6 not buildable" holds: on API 34+ a device owner can't end another app's process without hiding it or wiping its data.

## Scope (verified)

- Effective allowlist: `AppEnforcer.kt:369` `effectiveAllowlist(policy?.allowlist, WindowRelock.blockedPackages)`, passed to `applyCallPermissions` at `:414`. A relock-blocked app goes back to DEFAULT. The override/pause (`overrideActive`, `:322`) only reaches `computeEnforcementPlan`. A paused phone grants nothing extra, and the pause's end changes nothing. That is right, because call rules are never lifted.
- The sketch also lists "a contacts' messaging app". It adds no case: lock-usable apps only count "if the allowlist allows them" (`EnforcementPlan.kt:112-114`).
- `null` allowlist: every target is granted (`EnforcementPlan.kt:299`). This matches "every app is allowed while apps are unmanaged".
- Requesters: `:711-716` lists only non-`FLAG_SYSTEM` packages, hidden ones too (`MATCH_UNINSTALLED_PACKAGES`). The SOS app (system) is excluded. The music app requests neither permission (`music/.../AndroidManifest.xml`). A suspended-but-allowlisted app keeps the grant, which is right: suspension is temporary.
- Our own package: excluded from the grant set (`callPermissionTargets(..., ownPackage)`) and from the DEFAULT loop (`AppEnforcer.kt:730`).
- No other writer touches a third party's READ_PHONE_STATE (grep `setPermissionGrantState`: QuickControls (own), MusicSync (POST_NOTIFICATIONS/BLUETOOTH_CONNECT), WindowRelock (own location)). The DEFAULT loop therefore can't undo anyone else's policy.

## State machine and ordering

- `setGrantState` (`AppEnforcer.kt:741-750`) compares `getPermissionGrantState` first and catches everything. The denials (`:721-725`) run before the grants (`:728-736`), sequentially inside `apply` (synchronized, `:287`). They write different permissions, so they can't race each other.
- `enforceOnNewPackage` (`:1262-1268`) writes only the CALL_PERMISSIONS DENIED that `apply` would write too, so no conflict.
- New WhatsApp install:
  - Not allowlisted: denied at once, hidden, and `SyncRunner.request("package_added")` runs (`Application.kt:87`). The parent's allowlisting then pushes a policy, and the grant arrives with that apply. Fine.
  - Already allowlisted: `blocked` is false and no sync is requested, so the grant waits for the backstop. That can be 30 min (`push/SyncSchedule.kt:17`), or 15 min with SSE down. See Low 3.

## Privacy / security

- READ_PHONE_STATE on API 34 gives call state, service/network state and subscription info. The IMEI and ICCID need READ_PRIVILEGED_PHONE_STATE since Q, and the phone number needs READ_PHONE_NUMBERS. That is exactly what the kid could grant from the dialog on an unmanaged phone. Nothing is logged except the package and permission names in a failure.
- Revocability: while GRANTED+POLICY_FIXED the kid can't revoke it. The parent's levers are taking the app off the allowlist or unmanaging calls. Either one gives DEFAULT: the fix is cleared, the grant stays, and it can be revoked in Settings (the platform's documented DEFAULT semantics, as `OwnPermissions.kt:48-53` relies on). That is enough.

## Findings

**Low 1 - the DEFAULT loop's exclusions are untested** (`AppEnforcer.kt:729-736`). Which apps go back to DEFAULT, and that our own package never does, is decided outside the pure function. If the `packageName == context.packageName` guard were lost, our self-fixed READ_PHONE_STATE would go DEFAULT on every apply and be re-fixed by `fixOwnPermissions` (`:768`). That is a grant/un-fix loop that re-notifies (the CLAUDE.md gotcha). Fix: have the pure function return the wanted state per requester (a `Map<String, Int>` over every third-party READ_PHONE_STATE requester, own excluded), and add the own/DEFAULT rows to the existing test.

**Low 2 - the release differs from the agreed sketch, and the design/PLAN still describe the sketch.** `docs/design/57-voip-call-screen-over-lock.md:534-553` specifies `phoneStateWrites` with `[DENIED, DEFAULT]` (revoke, so the app "asks again as on a fresh phone", Q2 accepted). `PLAN.md:704` says "back to the phone's own choice". The code writes DEFAULT alone, so the grant outlives management. I agree with the change: it adds no reach the kid couldn't grant once the denial is gone, and a revoke kills a live WhatsApp call. But it reverses a decision the user accepted. Fix: add a "built" note under design 57 (d) (DEFAULT alone, why, and the grant staying) and say it to the user when the round is reported.

**Low 3 - an allowlisted install waits up to 30 min, and the comment's reason is wrong** (`AppEnforcer.kt:1265`). "Waits for the next apply, which knows the allowlist": `enforceOnNewPackage` has `decision` and `relockBlocked` in hand (`:1238-1241`), so it knows the allowlist too. Fix: when calls are managed and not `suspend`, write GRANTED from `phoneStateGrants(mapOf(pkg to perms), own, effectiveAllowlist(decision.policy?.allowlist, relockBlocked), true)`. Or request an apply/sync when that set is non-empty.

**Nit 1** - "Written only when it differs" isn't pinned by any test. Mutation 5 survived the full suite. This is the existing pattern (the old `setCallPermissions`, `MusicSync.kt:69-71`). A pure `grantWrite(current, wanted): Int?` would pin it; optional.
**Nit 2** - the PLAN queue entry (`PLAN.md:700`) gets its state in the merge commit (CLAUDE.md rule). Item 1 (the pause-end kill) needs its "not buildable, see launcher/CLAUDE.md gotcha" status there too.
**Nit 3** - pre-existing, not this round: an *update* that adds CALL_PHONE (`onPackageChanged`, `Application.kt:95-115`) isn't denied until the next apply. A READ_PHONE_STATE added by an update waits the same way.

## The three implementer choices

1. **Targets = the greyed apps only**: agreed. It is the sketch's definition and least privilege. An app that requests READ_PHONE_STATE without a call permission has no policy-fixed permission in its Phone group, so the kid can grant it normally. PLAN's wording ("every allowlisted app that requests it") is broader than needed.
2. **Release = DEFAULT alone**: agreed on substance (no kill mid-call, no extra reach), but it's a deviation from an accepted design point: Low 2.
3. **New install granted at the next apply**: acceptable for a not-yet-allowlisted app (the sync is requested anyway). For a pre-allowlisted one it is a half-hour gap that is cheap to close: Low 3.

## Q6 ("the pause-end kill is not buildable")

- Holds. The Android 14 behavior-changes page ("Apps can kill only their own background processes": another package has no effect and logs `Invalid packageName`) lists no exemption.
- AOSP 5b7edbf2ba07 (`ActivityManagerService.killBackgroundProcesses`): `hasKillAllPermission = FORCE_STOP_PACKAGES || UserHandle.isCore(callingUid) || proc.info.isSystemApp()`, otherwise `appId != callingAppId` is rejected. The check doesn't depend on target SDK.
- `FORCE_STOP_PACKAGES` is signature|privileged, so a Play-installed or sideloaded device owner can't hold it. `ActivityManager.forceStopPackage` is a hidden @SystemApi behind that same permission.
- The DPM has no force-stop. Its only process-ending paths are `setApplicationHidden(true)` (`killApplication` "hiding pkg") and `clearApplicationUserData` (wipes the app's data, which is unusable here).
- The `launcher/CLAUDE.md:1383` gotcha and emulator.md §6 item 11 (`:376-381`) are accurate and cite the right commit.

## Runs

- Full run: `assembleDebug testDebugUnitTest --no-daemon --max-workers=2 -PwarningsAsErrors=true` - **1805 tests, 0 failures, 0 errors, 0 skipped, exit 0** (EnforcementPlanTest 40, the new case among them).
- Mutations, each reverted (`git status` clean after):

| # | Mutation | Run | Result |
|---|---|---|---|
| 1 | drop `(allowlist == null \|\| pkg in allowlist)` | EnforcementPlanTest | killed (exit 1) |
| 2 | drop `READ_PHONE_STATE in requested[pkg]` | EnforcementPlanTest | killed |
| 3 | `callPermissionTargets(requested, "")` (own included) | EnforcementPlanTest | killed |
| 4 | drop `if (!denyCalls) return emptySet()` | EnforcementPlanTest | killed |
| 5 | `setGrantState` writes without comparing (`if (true)`) | full unit suite | **survived** (Nit 1) |

- Tests: the new case (`EnforcementPlanTest.kt:275`) covers allowlist, request, call-permission, own, `null` allowlist and both `denyCalls=false` rows. No redundant matrix, and the `callPermissionTargets` test is unchanged.
