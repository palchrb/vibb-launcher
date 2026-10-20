# Design QA - launcher round 7 (r7 small items: §1 ring over the lock, §2 force-stop protection, §3 wake for a notification)

Reviewed on `main` at d53edc81 (2026-10-20): `docs/design/r7-launcher-small-items.md` against PLAN's three round 7
entries, designs 17/17b, 19b, 25, 32 (§7, step 1), 51, `launcher/CLAUDE.md`'s standing rule and the launcher and
server code. Paths below are under `launcher/app/src/main/java/com/kidslauncher/mdm/` unless they start with `server/`,
`docs/` or `PLAN`. No build run; reading only.

Confirmed as the design assumes: `NotificationRuleRuntime.policy` is a `@Volatile NagPolicy?`, `null` until the first
apply in the process, with `allowed` = allowlist + the contacts' messaging apps (default SMS included) + the time
lock's usable apps (`badges/NotificationRuleRuntime.kt:24-63`). `GlanceNotice.postTimeMs` (= `sbn.postTime`) and
`conversationKey` exist per notification (`badges/GlanceReader.kt:129-141`). `VoipCalls.fsiDenied` and
`LockStateReport` (built only in `PinLockRuntime.kt:1456`) are as described. `MAX_FSI_DENIED` and
`valid_package_name` exist (`server/src/kid_lock.rs:113-131`). Migration 0083 is the latest, so 0084 is free.
`cap_installed_apps` rebuilds each entry with `..app`, so a new field is kept without code
(`server/src/handlers/device_api.rs:678-700`). minSdk is 34, so `setUserControlDisabledPackages` (API 30) always
exists. The "no policy yet" fallback in §1.1.1 fails closed: today's sets, never wider.

## §1 - any allowed app may ring over the lock

**r7-1 (High) - the accepted risk in §1.1.2 is incomplete, and one sentence in it is wrong.** What an allowed app can
do with a fake ring, from the code:
- *No real time bound.* The 2-minute limit is per ring, and the 3 h cap is per record. An app that cancels its ring
  and posts it again gets a fresh `ringStartedElapsedMs` after the 15 s grace (`lock/VoipCallPlan.kt:278-284`, and
  `continueCall` :297-313). Once a record ends, the next ring starts a new record with a new 3 h cap (:288-290). An
  app also controls the in-call phase itself: it can post a call-category foreground notification and set
  `MODE_IN_COMMUNICATION` (any app may set it). So an app built or set up for this keeps the lock away for as long
  as it likes.
- *"The kiosk keeps every other app locked (the exemption pins that one package)" is true only with the kiosk off.*
  The re-front loop yields `"voip"` to whatever is in front and never checks the package (`lock/RefrontPolicy.kt:86`;
  the launcher has no front-package read at all).
  - Kiosk on: the lock-task list is the whole kiosk list, untouched while LOCKED (`server/LockTaskHelpers.kt:188-190`).
    Home and Recents are off, but the ringing app can open any allowlisted app (a link, a share, a "settings" screen),
    and that app then stays in front over the lock.
  - Kiosk off: the pin plus the app-block bit hold that one package (:191-202), but only while lock task runs. A ring
    that began unlocked gets no lock task when the phone locks (`lockTaskEntry`, `lock/LockTaskRoot.kt:50`).
    `setStatusBarDisabled` doesn't block Recents, so the other unsuspended apps are probably one swipe away. This
    needs a device check.
- *Any caller, not just the contacts.* `launcher/CLAUDE.md`'s "who may call is set on vibb.me" holds for Element and
  Signal under the contact rules. A WhatsApp, Teams or school-app call from anyone who has the kid's number now rings
  over the lock, and Answer opens the call.
- *Noise and light.* Every ring wakes the screen (`VoipWakeActivity`) and plays the default ringtone at any hour
  (`ringPlan` follows only the ringer mode and DND).
- What stays bounded: Home sits below the lock (an app that finishes lands on the lock or on Home's locked resume).
  The permission controller is pinned with the kiosk off, but it grants that app nothing it couldn't ask for while
  unlocked. Another call, the emergency flow and an alarm still win.

Change: rewrite §1.1.2 with these points. Drop "the kiosk keeps every other app locked" and say what holds in each
kiosk mode. Put the decision to the user (question 1). If they want a bound, the cheapest is a per-package limit in
`voipExemption`, e.g. at most N ring records per package per hour, after which its rings are ignored and reported. A
stricter option is a parent tick per app, "may ring over the lock".

**r7-2 (Medium) - the exclusion set is too narrow, and its source isn't named.** "The SMS app" is ambiguous. The
plan's own set is `smsPackages(defaultSms)` = `KNOWN_SMS_PACKAGES` + the default SMS role (`server/EnforcementPlan.kt:267`).
With the default-role package only, Google Messages on the allowlist rings over the lock whenever the role is held by
something else, or by nothing. The system dialer is also missing. When our dialer role isn't held (calls unmanaged),
the system dialer posts a CATEGORY_CALL notification with an FSI for every cellular ring. It is never suspended and is
often allowlisted. The VoIP path would then take a cellular ring as a VoIP ring: a record, `voip_rang` showing "Calls
from Phone rang over the lock", and with the kiosk off a pin. `otherCall` keeps the ringer quiet, but that is luck,
not design. The same goes for Telecom and the emergency dialer. A package the allowlist names twice is no bypass:
it is a set difference. Change: `exclude = smsPackages(defaultSms) + NagPolicy.essential + ownPackageFamily(own)`.
`essential` is already resolved in `apply()` (`server/AppEnforcer.kt:513-516`, :529-567: system and default dialer,
Telecom, emergency dialer, `com.android.phone`, the clock, the keyboards, SystemUI). Add a `VoipCallPlanTest` row: a
duplicate or excluded package stays out.

**r7-3 (Medium) - `NagPolicy` can't express the §1.1.1 fallback.** It has `allowed` and `managed` (= allowlist != null
&& no override, `badges/NotificationRuleRuntime.kt:40-47`). Nothing says "allowlist == null": with no allowlist,
`allowed` is just the messaging apps + usable apps, which is neither of "today's sets". `allowed` uses the raw
`policy?.allowlist`, not `effectiveAllowlist(..., WindowRelock.blockedPackages)` (`AppEnforcer.kt:314`). `refresh`
runs last in `apply()` (:510-519), so a throw earlier in the pass keeps the previous value. And
`CallSystem.defaultSmsPackage` (a RoleManager binder call) would run on the main thread in `VoipCalls.eligible`
(`lock/VoipCalls.kt:220`). Change: have `refresh` compute one more volatile, `voipAllowed: Set<String>?` (the
effective allowlist + messaging apps + usable apps − r7-2's `exclude`; `null` when the allowlist is `null`). Give the
signature as `voipCandidates(effective: CallPolicyState, voipAllowed: Set<String>?, policyKnown: Boolean)`, so the
pure table in §1.2 is the whole rule, and `eligible` does no binder work beyond `isPackageSuspended`.

**r7-4 (Medium) - one malformed `voip_rang` entry would drop the whole `lock_state`.** `LockState` is a plain serde
struct (`server/src/kid_lock.rs:94-111`), and `sanitize_lock_state` does `serde_json::from_value(...).ok()?` (:122). A
`Vec<RangEntry { package, last_at_ms: i64 }>` with one string time or missing key fails the whole parse: no lock
state at all, the lock-off warnings included. Change: declare `voip_rang: Vec<serde_json::Value>` (or a lenient
`deserialize_with`), then filter each entry to a valid package and an integer time, dedupe by package, newest
first, `take(MAX_FSI_DENIED)`. "A future time dropped" needs a `now_ms` the function doesn't take today. Pass it in
(its two callers), or drop future times when rendering. The card's "<label> · <when>" needs a label source and a
zone that `lock_card(policy, state, capable, reported, now_ms)` doesn't have. Name them: the catalog/app-display name
map, and the device's reported `time_state.zone` like the other device-page times. Add to §1.2: a malformed entry
leaves the rest of `lock_state` intact (in `step10.rs::lock_state_is_stored_sanitized_and_capped`).

**r7-5 (Low) - "rang over the lock" is recorded for rings while unlocked.** `VoipCalls.evaluate` builds records
whatever the lock mode is (`lock/VoipCalls.kt:165-217`), so "phase RINGING began" also fires for a call taken on an
unlocked phone. Change: record when RINGING begins while `PinLockRuntime` is LOCKED, or when the phone locks during
a ring. Add a test.

## §2 - catalog apps protected from force-stop and hibernation

**r7-6 (High) - the exception to the standing rule isn't the user's decision, and its reason doesn't hold.** The rule
(`launcher/CLAUDE.md:921`) allows an exception only when the user explicitly says so "for that specific case". The
user's words in §2 are about the battery flow, not about this. "It blocks nothing the kid does" misses the point of
the rule: the pause is the parent's "something's wrong" tool. `setUserControlDisabledPackages` greys out **Force stop
and Clear storage** for the catalog apps, the two first fixes for a stuck or broken app (a messenger stuck after an
update, a clock with a corrupt database). Holding the protection through a pause buys almost nothing, because an OEM
battery manager seldom acts in a pause's minutes. Change: follow the rule. The set is empty while
`overrideActive`, which needs no exception and no new entry in the exception list. §2.2's "kept through the override
and the pause" becomes "cleared by them", and §2.3's "during a pause Force stop greyed out" moves out of the pause
(e.g. `adb shell am force-stop` refused, or the check runs before the pause). If the orchestrator still wants it kept
through the pause, it goes to the user (question 2).

**r7-7 (Medium) - the record source can't give the set.** `TrackedAppUpdateState` is keyed by the catalog id and holds
no package name (`server/TrackedAppUpdateState.kt:11-38`, :50-55). `apply()` never sees the catalog list: it comes
from `GET api/devices/apps` in the sync (`server/MdmSyncWorker.kt:582-640`, `MdmApi.kt:57`). Records are never removed
when the parent unchecks an app, so "uncheck the app - the protection is gone" (§2.3) wouldn't happen. `phonePackage`
is a private helper in `MdmSyncWorker.kt:946`. Change: build the set from what `apply()` can read. Take the stored
catalog packages (`WindowRelock.rememberCatalogPins`, `server/WindowRelock.kt:362`: package → signer for the current
checked list), keep the ones that are installed for this user (FLAG_INSTALLED) and whose
`InstallSourceInfo.installingPackageName` is in `ownPackageFamily` (the same test as the server's
`installed_by_launcher`, `server/src/handlers/device_api.rs:815`). Then remove `plan.hide` and our own family.
`userControlDisabledPackages(catalog, installed, installer, hidden, own, overrideActive)` in `UserControlPlan.kt`
then covers "never a Play twin" (installer = Play) and "unchecked" (left the pins) without the records. Use the same
criterion for which rows carry `battery_exempt` (r7-9).

**r7-8 (Medium) - a throw here would skip the PIN lock and the hardening.** "Right after the suspension pass" is
`AppEnforcer.kt:376-433`. That is before `PinLockRuntime.configure` (:453), `LockTaskChrome.applyPlan` (:463), the
VPN (:501) and `applyHardening` (:509), with no catch around that stretch. An OEM `SecurityException` or a refused
package would fail the phone open. Change: give the call its own try/catch, logged, so it never throws out of
`apply()`. Better, place it after `applyHardening`, next to `NotificationRuleRuntime.refresh` (:510). Drop "From
Android 12" (minSdk 34). The hibernation and permission-reset exemption isn't documented for this API as far as I
know (it is for App Standby buckets on newer releases). Make it a device check (`dumpsys app_hibernation`, or the
app's "Pause app activity if unused" row in a pause) rather than a claim.

**r7-9 (Low) - the battery wording.** `isIgnoringBatteryOptimizations == false` is Android's "Optimized" (or
"Restricted"), usually not restricted. "Background: restricted" would alarm parents for the default state. Change:
"Background: optimized by Android - to exempt it, during a pause: Settings › Apps › <app> › Battery ›
Unrestricted", and "Background: unrestricted". `battery_exempt` is `Option<bool>` with `serde(default,
skip_serializing_if)` on `models.rs::InstalledApp` (:841), sent only for r7-7's set, and left out when unreadable (as
19b does).

## §3 - wake the screen for a notification

**r7-10 (High) - the start mechanics are wrong about the VoIP wake, and the `darkLockUp` gate never opens.**
- The VoIP wake isn't "started with the lock already the top task (lockOnTop)". It is `show(wake = true)`: it starts
  `PinLockActivity` with REORDER_TO_FRONT, then `VoipWakeActivity`, and clears `lockOnTop` (`lock/PinLockRuntime.kt:1094-1125`).
- `darkLockUp` is set by every HOME_THEN_LOCK lock-up (:648) and cleared only by an interactive resume (:256, :262).
  It stays true for the **whole** sleep after the common case (the kid was in an app when the screen went off). So
  "never while darkLockUp" means no wake on most sleeps, and "the lock's next pause/resume" doesn't happen while dark.
- If `LockWakeActivity` is started on its own while the pinlock task isn't there or isn't on top (`lockOnTop` is best
  effort), its translucent window lights the screen over whatever task is on top: a live app on a LOCKED phone. That
  breaks design 32 §7's "lock or black".
- A twin that copies `VoipWakeActivity.onPause` would call `PinLockRuntime.voipWakePaused` and corrupt 17b's
  try-detection (`lock/VoipWakeActivity.kt:27-33`).

Change: one main-thread step, as VoIP does. Generalise `show(wake)` to `show(context, wake = Wake.VOIP | Wake.NOTICE)`
so the lock's start and the wake's start always go together. Accept that it clears `lockOnTop`: the next screen-off
runs a normal lock-up. Replace the `darkLockUp` gate with "no lock-up in the last 2 s" (a `lockUpAtElapsed` stamp in
`lockUp`, :645) plus `bootMark == Over`. `LockWakeActivity` only finishes itself and calls no VoIP hook.
`LockWakeTest` covers the new gate. Add a device check: the first frame after a notice wake is the lock or black
(design 32 §7), with and without the step 2 cover.

**r7-11 (Medium) - "a new post" can't be told from an update the way §3.1.3 says.** `GlanceStore.put` gets no
post/update flag (`badges/GlanceStore.kt:60-64`). `sbn.postTime` is renewed on every `notify()` of the same key,
silent updates included, so "postTimeMs newer than the conversation's last" doesn't exclude updates. Notifications of
a **suspended** app still arrive, marked suspended in the ranking (`GlanceReader.kt:139`), so §3.1.5's "a time rule
that suspends the app means no post arrives" is wrong. Without a check the phone would wake for an app it hides.
`glanceCandidate` lets LOW-importance channels through (`badges/GlanceRows.kt:115-119`), which never alert on a stock
phone. Change: trigger only from `GlanceReader.posted` (a separate callback, not `put`/`changed`), when all of these
hold:
- the notice is kept and not suspended;
- `ranking.importance >= IMPORTANCE_DEFAULT`;
- `ranking.matchesInterruptionFilter()` (DND);
- not `FLAG_ONLY_ALERT_ONCE` on a key that already existed;
- the visible rows (`glanceRows`, after the opened marks) gain a row or a newer `timeMs`.

Put each condition in `LockWakeTest`/`GlanceRowsTest`.

**r7-12 (Medium) - the missed-call trigger has no hook, and it is the real thread hand-off.** Our post runs on
`MissedCallNotifier`'s executor (`calls/MissedCallNotifier.kt:202-283`). "Alerting" is `step.alert`, and the
listeners fire only when `current` changes (:57-63), not on an alerting post. The notification listener's callbacks
are already on the main thread (`GlanceReader.kt:17-19`), so "never from the listener thread directly" names the
wrong thread. Change: after a successful `post(..., alert = true)` in `recomputeNow`, post `PinLockRuntime.noticeCame()`
to `main`. `relearn`/boot re-sends and `afterCall` updates have `alert = false`, so they never wake. Fix the wording
in §3.1.4.

**r7-13 (Medium) - pocket and on-time.** A notice wake keeps the screen on for the parent's timeout (up to 10 min,
`ScreenTimeout`). That is up to 20 times an hour, and PLAN's entry says "a short timeout". In a pocket, touches on
the lock's PIN pad add wrong-PIN backoff for the kid. Change: no wake while a one-shot `TYPE_PROXIMITY` read says
"near" (no permission; skip the check when there's no sensor). Decide the on-time: either a short one (e.g.
`lockNow()` as device owner when the lock has had no touch 10 s after a notice wake - the lock is LOCKED already),
or say plainly that it's the phone's timeout and fix PLAN. Question 3 if the orchestrator won't pick.

**r7-14 (Low) - claims and small gaps.**
- `PinLockManifestTest` doesn't pin the turn-screen-on set today; it only checks the lock (`test/.../PinLockManifestTest.kt:37-44`).
  §3.1.2 should say "a new assertion".
- The gap and the hourly counters are in memory (a new process starts over; the 2-minute start busy covers it). Say so.
- The wake's own pause of the lock is interactive and clears `lockOnTop` (`lockOnTopAfter`, `lock/LockUpPlan.kt:85-91`). That's harmless; say so.
- Name where the runtime reads the switch. `LauncherUi` isn't read by the lock today. A volatile set in
  `PinLockRuntime.configure` (which gets the enforced policy, `AppEnforcer.kt:453`) keeps the cached/fresh rule in one place.

**r7-15 (Low) - the no-jump rule.** Every form on the page returns to its own cell (`#lock-wallpaper`,
`#lock-screen-app-<pkg>`; `server/templates/device_lock.html:130-133`, :159, :197), not to the section. Change: a cell
`id="lock-wake"` with `data-autosave` and a `<noscript>` Save, posting to `/devices/{id}/lock-wake#lock-wake`, in
`check_forms`. Put it in the Lock screen group under Background. Add to the group's info text: "What the lock shows
then (text included, if on below) shows to anyone near the phone."

## General

**r7-16 (Low) - PLAN disagrees with the design.** It says:
- §1: `lock_state.voip_rings` with a count, against the design's `voip_rang` with `last_at_ms`;
- §2: "at install/allow time, removed when the app leaves the catalog", "from Android 12", "restricted";
- §3: "`PowerManager`/`turnScreenOn` on the lock activity, a short timeout". The lock activity part contradicts
  design 32 §7.1.

Change: update PLAN's three entries in the same commit as the design revision.

Scope: nothing beyond what was asked or what PLAN already proposed (the `voip_rang` note and the Apps-page battery
line come from PLAN's entries). The undecided points the implementer would otherwise settle are in r7-3, r7-4,
r7-7, r7-10 and r7-13.

## Questions for the user

1. **§1, the full risk** (r7-1): a call-capable allowed app can keep the lock away indefinitely by ringing again. With
   the kiosk on, it can lead to any other allowed app while the lock steps aside. Through apps whose contacts Vibb
   doesn't manage (WhatsApp, Teams), anyone with the number can ring over the lock. Accept as is, or add a bound: a
   per-app ring limit per hour, or a parent tick per app, "may ring over the lock"?
2. **§2, the standing rule** (r7-6): should the force-stop/clear-data protection be lifted by the override and the
   pause like every other restriction (recommended), or kept through them as a new exception?
3. **§3, only if the orchestrator won't decide** (r7-13): after a notice wakes the screen, should it go off again
   after about 10 s untouched, or stay on for the phone's normal timeout?

## Verdict

**Approved with revisions.** §1 and §2 need questions 1 and 2 answered, and r7-1 and r7-6 written in. §3 needs
r7-10's start path and r7-11's trigger definition before implementation. The rest are text changes.
