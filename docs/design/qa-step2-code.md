# QA/security code review: step 2 (calls, phone book, message buttons)

Reviewer: QA/security, 2026-10-04. Scope: `kid-phone-server` `a5f170e^..handy`, `kids-launcher-mdm` `fc94a3b^..handy`,
against 02-calls.md (Decisions + Implementation status) and qa-01-02.md (02 criteria). Tests run: server 57/57 pass,
launcher 117/117 JVM tests pass; the two `phone_vectors.json` copies are byte-identical. L = launcher
`app/src/main/java/com/kidslauncher/mdm/`, S = server. **[inferred]** = from reading the code and platform behaviour,
not reproduced on a device. The open items the implementers already list (task 15 direct boot; MMI/USSD from the system
keypad, QA #6) still apply as release gates and are not repeated here.

**Blockers: 0.** Should-fix: 7. Notes: 6.

## Findings

1. **should-fix. Emergency contacts disappear when calls are off or fail closed.** L `calls/PhoneBookActivity.kt:74`
   shows only "calls off" when `rules == null || !rules.callsEnabled`, and `ui/minimalist/ContactsHomeAdapter.kt:56`
   hides every Home row. The decision is "no built-in emergency buttons; the parent adds 112/110/113 as contacts",
   so with calls off, in fail-closed, or with an unreadable cache, the launcher has no way left to dial 112. The system
   dialer is hidden from Home and the drawer (`apps/AppFilter.kt`), so the only path is the lock screen's Emergency
   button, and with no secure lock (None or Swipe) there isn't one [inferred]. Fix: in every state, keep listing
   contacts whose number is an emergency number (`Emergency.isEmergencyOutgoing`), or always show one "Emergency" row
   that opens the platform emergency dialer. Add a test. Device-check kiosk with screen lock None.
2. **should-fix. QA #10 is only half done.** L `Application.kt:111-122`: `BootClock.init` and
   `LauncherPreferences.init` still run before the `try`, and the global handler calls `exitProcess(1)` on **any**
   uncaught exception on any thread. A throw there causes a crash loop: screening never answers (5 s, then allow), the
   InCallService bind fails (preloaded dialer), and C rings and can be answered. Fix: wrap both in the guard (or make
   them no-throw). Have the handler not kill the process when the throw comes from a call-service path. Device test 13
   should inject the throw into `BootClock.init` too, not only `initRest`.
3. **should-fix. The default phone app can be switched away while the redirection role is missing.** L
   `calls/DialerRole.kt:36` sets `DISALLOW_CONFIG_DEFAULT_APPS` only when the dialer **and** the redirection role are
   both held. The redirection role needs adb, or a Home prompt the kid can simply decline
   (`ui/HomeActivity.kt` `promptForCallRoleIfNeeded`). Until then, Settings → Default apps → Phone, or another
   allowlisted app's `createRequestRoleIntent(ROLE_DIALER)`, takes the role away from us. Incoming calls are then
   unscreened until the next `AppEnforcer.apply` (no role-change listener) [inferred]. Fix: lock once the dialer role
   is held, and lift the lock only briefly around a parent-initiated redirection prompt. Consider also holding
   `ROLE_CALL_SCREENING`, so screening survives a lost dialer role.
4. **should-fix [inferred]. The callback window trusts the wall clock.** L `calls/CallRules.kt:281-293` compares
   `System.currentTimeMillis()` with call-log dates and with `last_connected_emergency_end_ms`, a pref that is never
   cleared. `lockDateTime = appsManaged` (`server/EnforcementPlan.kt:118`), so while apps are unmanaged (or under
   override) with calls managed, the clock can be changed. Setting it back to just after any past connected emergency
   call reopens "anyone may call" for an hour. Fix: `lockDateTime` also when `callState.managed`. Anchor the recorded
   end with `elapsedRealtime`/`BootClock`, so the window can't reopen after a reboot or a clock change. Add a test.
5. **should-fix [inferred]. Calls with `DIRECTION_UNKNOWN` skip both backstops.** L `calls/KidInCallService.kt:48,57`
   check only `OUTGOING` and `INCOMING`. Anything else is added and shown, and can be answered. Telecom reports
   unknown direction for "unknown" calls added by telephony (e.g. after SRVCC/handover), conference parents and some
   ConnectionServices. Fix: while managed, allow an unknown-direction call only if it is an emergency call or its
   number is in inbound ∪ outbound. Otherwise disconnect it. Add a unit test of the pure rule.
6. **should-fix [inferred]. The number that is checked is not the number that is dialled.** The rules drop one "trunk"
   `0` for every country code, Norway included (Norway has no trunk prefix). So `0`+`<allowed 8 digits>` matches
   contact B, but `KidCallRedirectionService.kt:38` (`placeCallUnmodified`) and `PhoneBookActivity.kt:145`
   (`placeCall(this, raw)`) send the raw string, and the network routes `091234567` by its own rules. The same applies
   to any future gap between our separator stripping and telephony's. Fix: when the stripped raw differs from the
   canonical form, the redirection service calls `redirectCall(tel:<stored E.164>, account, false)`. The phone book
   dials the contact's stored number. Optionally, skip the trunk drop for country codes without one (47, 45, ...).
7. **should-fix. When the call log can't be read, the callback window silently never opens.**
   L `calls/CallSystem.kt:92-95` turns any failure into `emptyList()`. `READ_CALL_LOG` is hard-restricted (the
   implementation status's device item 9). Our InCallService normally doesn't see emergency calls, because the
   preloaded dialer shows them. So if the log is unreadable, a PSAP callback is rejected. Fix: report
   `call_log_readable` in `CallState`, warn on the server's calls page, and make it part of device test 8.
8. **note. Without the redirection role, outgoing calls are only stopped after they start.**
   L `server/EnforcementPlan.kt:99` lifts `DISALLOW_OUTGOING_CALLS` when only the dialer role is held. Without
   redirection, a call to C is placed and then disconnected by the backstop, so C may ring or log a missed call, and
   QA #5/T10 cannot pass. The server warns about this. Make it an explicit provisioning gate: the redirection role is
   required before handover.
9. **note. Matrix ID validation is loose.** S `src/handlers/calls.rs:429` and L `calls/MessageButtons.kt:26` accept
   `/ ? # % &` etc. in the ID, which is put into `https://matrix.to/#/<id>`. Impact is limited: the address is
   parent-entered, the intent is explicit (`setPackage`), and the ID must start with `@`, so only Element X opens it.
   SMS and Signal use the server-normalised number, and I found no path that starts another app. Fix: tighten both
   checks to the Matrix grammar (`@[a-z0-9._=/+-]+:[A-Za-z0-9.:\[\]-]+`).
10. **note. "SMS on" means SMS to and from any number.** There is no SMS allowlist, and a contact's SMS button opens
    the full Messages app. S `templates/device_calls.html` should say so. Telecom's missed-call notification
    ("Call back" / "Message") goes through redirection and the SMS switch respectively. That is fine.
11. **note. The system dialer can be pinned in kiosk when calls are unmanaged and the parent allowlisted it.**
    `EnforcementPlan.kt` removes it from the pinned set only while calls are managed. QA T8 says "never". This looks
    like a deliberate exception (an unmanaged phone with an allowlisted dialer already calls freely). Write it down,
    or drop the condition.
12. **note. Tests.** `CallRules.inbound`/`outbound` are `@Transient` fields derived in the class body, so they are not
    part of `equals`. `CallPolicyStateTest` compares decoded `last_call_rules` only with `equals`. Add an assertion
    that a decoded rules object has a non-empty `inbound`/`outbound`; that object is the fallback for blocker 3.
    Also missing: a pure test for the `DIRECTION_UNKNOWN` rule (#5) and one for the clock rollback (#4).
13. **note. Self-update gap (QA #10).** While the APK is being replaced, Telecom can't bind our services, so a call
    in that window is unscreened. Prefer installing updates at night or while the phone is idle, and document it.

## Checked and fine
- Auth: all six new routes are in `admin_routes` (session + 2FA layer), with a test (`calls_forms_need_an_admin_session`).
  Flag and remove are scoped to `device_id AND contact_id` (404 otherwise). Every write is one transaction and
  answers 500 on error. Missing checkbox = false. Every write nudges the device. Template output is Askama-escaped,
  including the launcher-reported `last_error`. Status JSON is capped at 4 KiB.
- Migration 0022: existing devices default to `calls_managed = 0`, with an explicit `managed: false` sent. The
  country-code CHECK is fixed (QA #18). `call_policy` is always present, and `build_call_policy` runs before the
  command pop.
- Normalisation: identical rules and ASCII-only digits on both sides. `*`, `#`, `,`, `;`, fullwidth and Unicode
  digits are rejected. Handles are percent-decoded, `sip:` user parts are taken and `voicemail:` gives null. The
  static emergency list is exact-match (112, 911 + national), never opens the window, and allows 112 if the platform
  throws.
- Fail-closed: the store starts as `UnknownFailClosed`. `judgeFresh` rejects a missing `call_policy` after managed
  calls. The last managed rules are written in the same `commit()`. Corrupt cache → last rules or fail-closed.
  Override and pause never lift call rules, `DISALLOW_SMS`, SMS-app suspension or the CALL_PHONE/ANSWER_PHONE_CALLS
  denials (new packages included). Role loss → `DISALLOW_OUTGOING_CALLS`. Withheld or verification-failed callers →
  BLOCK. MMI/voicemail → BLOCK in redirection and the backstop.
