# Code QA: the phone book as a task of its own (branch `phonebook-task`, 01c4aee7b)

Reviewed: `git diff main...phonebook-task` (manifest, `PhoneBookActivity`, `PhoneBookEntryActivity`, `LightPages.kt`,
`PageKind`, `PageLook`, `LauncherLocale`, `ScreenTime`, `AppEnforcer`, the tests and the notes), against main's
`PhoneBookActivity`, `MessagesEntryActivity`, `DayActivity`/`MessagesScreens` (the pattern copied), `LockActivity`,
`GlanceOpener`, `MissedCallNotifier`, `ContactSheet`, `KidPhotoActivity` and AOSP's task code (`ActivityStarter.complyActivityFlags`,
`RecentTasks.onPackagesSuspendedChanged`, `ComputerEngine.findPersistentPreferredActivity`). Nothing was built or run.

Counts: **1 High, 2 Medium, 5 Low.**

## What holds

- **The exported entry is no wider than main's exported page, and in places narrower.** `forward` builds a fresh intent:
  only the action, data, type and `EXTRA_MISSED_CONTACT` cross, never the caller's flags, other extras, ClipData or
  grants, and a malformed extras bundle is caught. A `CALL` sent explicitly to the entry is treated like `DIAL`, as on
  main: `handleNumber` runs `decideOutgoing` and always asks "Ring X?" first. `EXTRA_MISSED_CONTACT` opens only the sheet
  of a contact already in the view, as on main. `passOnCallLog` was moved over word for word. Under a rule that blocks
  calls, the asked number is dropped and the resume redirects. On main an outside `tel:` or the missed-call tap
  (`NEW_TASK`, Home's affinity) could put the phone book above the time-rule screen, so this branch is stricter.
- **Kiosk and keyguard.** Lock task is allowed per package, and our package is always lock-task permitted, so the
  `.phonebook` task needs no new entry, as with Kalender's and Meldinger's. Neither the page nor the entry is
  `showWhenLocked`, and nothing new is `directBootAware`.
- **Callers.** Home's tile and the time-rule screen's row use `intent()`. The glance and the notification name the entry
  through `missedCallsIntent`. `InCallActivity`'s own `.call` task falls back to the phone book's task when it finishes.
  The boot cover and HomePin name no phone-book class. Any old call-log PPA left on `PhoneBookActivity` does no harm:
  PackageManager skips a PPA whose component is not among the resolved candidates, and the page no longer has filters.
- **Look and language.** `closesTask`/`languageClosesTask` key on the base intent's component, which is always a page
  class (the entry's affinity is `""`, so it never roots `.phonebook`). `closeIfOtherLook` covers a stale card.

## High

### H1. A suspended app opened from the phone book survives a time rule inside the phone book's task
`calls/ContactSheet.kt:175` (`openMessage`: no `FLAG_ACTIVITY_NEW_TASK`), `calls/PhoneBookActivity.kt:618` (`intent()`),
`calls/PhoneBookActivity.kt:229` (the redirect runs only when the page itself resumes).

A row's or card's Melding starts Signal, Molly or Element X with `startActivity` and no `NEW_TASK`. Any target activity
with a standard or singleTop launch mode, such as an `smsto:` trampoline, therefore joins the caller's task. On main that
was Home's task: `clearTaskOnLaunch` cleared it on Home, and `LockActivity.start`'s `CLEAR_TOP` cleared it under a rule.
Now it is the `.phonebook` task, whose root is our package. Android's suspension removes only tasks whose
**root** (`realActivity`) is of a suspended package (`RecentTasks.onPackagesSuspendedChanged`), so Signal's activity
stays in our task when a rule suspends it. Lock task authorises a task by its root too.

Scenario: outside a rule, the kid taps Melding on Mamma, and Signal's conversation opens in the phone book's task. The
kid presses Home. Leggetid begins and Signal is suspended. Then either:

- the rule allows calls, and the time-rule screen's "Telefonbok" row runs `intent()`. With `NEW_TASK`, an equal filter
  and a reused task, Android brings the task forward as it was, with Signal on top. Or:
- the rule blocks calls, and Recents (available in the kiosk, §6au) brings back the "Telefonbok" card with Signal on top.

Either way the page never resumes, so `redirectIfLocked` never runs, and a suspended app is usable under the rule.
`KidPhotoActivity`'s camera or picker, started for a result inside the task, is the same case on a smaller scale. Not
seen on the emulator, because §6au never used Melding to an outside app.

Fix:
- `openMessage` adds `FLAG_ACTIVITY_NEW_TASK`, so the app gets its own task. That task's root is the suspended
  package, so the kiosk and suspension rules hold, and Home's `ContactSheet` behaves as before.
- The time-rule screen's row starts the page with `CLEAR_TOP` (as `forward` does), so it always lands on the page and
  the gate runs.
- Add an emulator row to §6au: Melding to an outside app, Home, a rule on, then the row and Recents.

## Medium

### M1. The task's base intent changes with a forward, so Home's tile can stack a second phone book
`calls/PhoneBookActivity.kt:626-627` (`forward` sets the page intent's action, data and type) against `:618`.

`forward` delivers through `CLEAR_TOP`, and when the page is the root, Android also sets the **task's base intent** to
the forwarded intent: `VIEW` with the call-log type, or `DIAL tel:…`. A later `intent()` from Home's tile or the
time-rule row has no action. In `complyActivityFlags`, the same root component with a top that is not the page (for
example `KidPhotoActivity` from the card's "change picture", or H1's outside app) and `!isSameIntentFilter` gives
`mAddingToTask = true`, so a **second** `PhoneBookActivity` lands on top. Scenario: the kid opens the phone book from a
missed-call notification, opens Mamma's card, starts the picture flow, presses Home and taps Telefonbok. A second phone
book appears over the framing screen, and Back reveals the framing screen. This contradicts calls.md:654 ("comes back as
it was"). Meldinger and Kalender don't hit it, because their builders never set an action or data.

Fix: keep the page's intent filter-equal to `intent()`. Carry the asked action, data and type as private extras on a
component-only intent (`EXTRA_ASKED_ACTION`/`_DATA`/`_TYPE`, read by `handleNumber` and `isCallLogView`), so the base
intent never differs from the tile's.

### M2. The tests pin the source text, not the behaviour; the security-relevant stripping in `forward` is untested
`test/.../calls/PhoneBookTaskTest.kt` (the second test), `PageManifestTest`, `PageLookScanTest`.

Most of the new assertions are `contains("<exact line of code>")` checks: the onCreate guard, `takeAsked`'s first line,
the `onResume` order, `forward`'s signature. They fail on any reformatting and prove only that the line exists. What
`forward` copies and drops (no outside extras, no flags, the contact read safely) is the entry's security contract, and
no test exercises it. The third test, `phoneBookUsableUnder` against `messagesUsableUnder` and `timeLockRows`, is the real
one.

Fix: move the copy rule into a pure function, for example
`forwardedAsk(action, dataString, type, contact): Ask?`, and test it with rows (a `CALL`, a `voicemail:`, a call-log
`content://` URI, a hostile extra). Drop the line-pinning asserts that a behavioural test or the manifest test already
covers.

## Low

### L1. Under a rule that allows calls, with calls unmanaged, the phone book is reachable where the screen offers no row
`calls/PhoneBookList.kt:23`, `docs/notes/launcher/calls.md:672`. `phoneBookUsableUnder` ignores `callsManaged`, while
`timeLockRows` offers the row only while calls are managed. So Recents can show an unmanaged phone book ("calls off")
under such a rule. This is harmless, since nothing can be called that the system dialer couldn't call, but the note's
"exactly where it was before" is wrong. Fix: change the note's wording, or let the guard also require managed calls.

### L2. A forward closes the kid's picture screen without a word
`calls/PhoneBookActivity.kt:626`. A `tel:` link or a missed-call tap while `KidPhotoActivity` (or the camera or picker
above it) is in the task is cleared by `CLEAR_TOP`. The framing is lost and a draft is deleted by the cancel path. This
is intended ("never a second phone book over the kid's picture screen"), but the notes should say that the picture flow
is dropped.

### L3. Stale core doc
`launcher/CLAUDE.md:25` still says `LockActivity.start`'s `CLEAR_TOP` is "closing what the screen opened above it there
(the phone book)". The phone book is no longer in Home's task, and its own resume gate does that job now.
`docs/notes/launcher/calls.md:30` says that the notification's tap is "sent explicitly to it" (the phone book). It now
names the entry.

### L4. The PPA note overstates what was seen
`docs/notes/launcher/calls.md:664` says that "Android's device policy engine (14+) keys a PPA by its filter". This was
seen on API 36 only, and the API 34 run was not enrolled, so no pin was set. The outcome is safe on any version: a
dangling or unexported PPA component is skipped. Fix: word it as "seen on 16; on any version the old pin on the page
no longer matches anything".

### L5. Android 14 covered only in part
`docs/testing/emulator.md:4765`. On API 34 the rule redirect, the kiosk Recents card, the missed-call forward and the
update path (an old pin and the role's preferred DIAL) were not run. The root rule says a version-dependent gate runs on
the lowest and the highest version. Fix: rerun §6au's rule, kiosk and pin rows on `compat34` (with `-wipe-data` and a
working route) before the release.
