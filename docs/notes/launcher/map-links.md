# Map links (design 110)

Google Maps and Apple Maps links open in Organic Maps (or CoMaps) as a pin with the place's name and address. The
server resolves short links and places (`server/src/integrations/map_links/`, `docs/notes/server/integrations.md`);
the phone never talks to Google or Apple.

- **`map/`** (guarded by `KidScreensEscapeTest`): `MapLinkForms` is the one file that knows Google's and Apple's links
  (hosts, forms, the short-link holder `com.google.android.gms`) and reads a link into a neutral `MapParse` (pin,
  resolve, lookup, search, none, list); everything else - `MapLinks` (the `geo:` forms, the label, the fallback's query
  and S2 centre, a message's web links), `MapLinkPlan` (`mapLinkPlan`, `mapLinkAnswer`, `mapLinkAfter`),
  `MapLinkActivity`, `MapLinksRuntime` (the switch, the target and its state, the report), `MapLinkRows` - speaks only
  of links the server resolves.
- **The handler** `MapLinkActivity`: two intent filters (every path on the map hosts, `/maps…` on the general ones),
  installed disabled, enabled only by a policy carrying the parent's choice (`map_links.on`); its persistent preferred
  activity is set on every apply and in `HomePin`'s re-seat (the sixth, `PlayInvariantsTest`). Over a time rule or the
  keyguard it shows nothing; a server call is cancelled in `onStop` and nothing starts after it; the target starts in
  its own task. In a pause the note offers "Åpne i nettleseren" to a named browser, with the original link.
- **The target's start** (`MapLinks.clearsTask`, `startIdentity`; the user's bug of 2026-11-19: Organic Maps
  running in the background came to the front without moving). Organic Maps and CoMaps take `geo:` (and their own
  `om://`/`cm://`, `omaps.app` forms) in `SplashActivity` (standard launch mode, the app's own task), which hands it on
  to the `singleTask` `MwmActivity` (`onNewIntent` -> `processIntent`). Two things lost a link:
  - Android only brings the task to the front, starting nothing, when a `NEW_TASK` start `filterEquals` the root
    intent of the app's task (AOSP `ActivityStarter.complyActivityFlags`: the task's `realActivity` and
    `isSameIntentFilter`; am's "Activity not started, its current task has been brought to the front"). So a link
    that started the task, opened again later, was dropped - with other links between, and for an `omaps.app` link
    as much as a `geo:` one (it only seemed better because it differed from the task's `geo:` root).
  - On Android 16 (both apps, any start, our flags or none) a new pin moves the map but its place card doesn't open
    while the previous card is still up.
  The start therefore carries `FLAG_ACTIVITY_CLEAR_TASK`: a fresh `MwmActivity` processes the link in `onSafeCreate`
  (the engine already running, OM issue 6712's path) and always opens the pin with its card, and the kid's old state
  in the app (an open search or card) goes. Not when the target itself sent the link (`Activity.getReferrer()` is the
  target): it is on screen then, and recreating it there left its map black for good on Android 14 (its old activity's
  GL `DetachSurface ... destroySurface = 1` came after the new attach). Every start also gets a fresh
  `Intent.setIdentifier` (part of `filterEquals`), which is what keeps that one start from being dropped. In the
  background the old surface is already gone, so the clear is safe. Checked 2026-11-19 with Organic Maps 2026.11.11-39
  and CoMaps 2026.11.07-16 (F-Droid): compat34 through the handler itself (a Device Owner debug build, cold, in the
  background with the same and another link and with the last card left open, two in a row - every card right; with
  the app on screen and the link sent by itself, no clear: the map moves, no black map, the card can stay shut as above), jelly36 with the handler's intent
  from the shell (the handler can't be enabled there without a policy). Lock task wasn't on in either (no enrolment);
  by AOSP's `LockTaskController.activityBlockedFromFinish` only the root of the last locked task is kept from
  finishing, and the map app's task never is that.
- **The two one-time steps** (Q2) are design 114's setup steps `map_play_links` and `map_vibb_links` (114 §2.6): screens
  of the setup flow (`setup/SetupFlowActivity`, "Oppsett" in the PIN-gated Settings), only while the switch is on and
  Organic Maps or CoMaps is installed. Each opens Android's "Open by default" page (only in a pause or the setup hold):
  Google Play services' "Open supported links" off, then Vibb One's on with every link ticked; the flow runs
  `MapLinkStep.begin` before step 2's page and `end` at its return (finding 1). Their readings (`playLinksDone`,
  `vibbLinksDone` in `map/MapLinkRows.kt`) are the report's `play_services_links` and `vibb_hosts`, as the server reads
  them (`setup_steps.json`'s `map_readings`), and a pause-time report keeps its own `vibb_hosts` (only `reach` is the
  last unpaused one), so row 16 ticks off on both sides at once. "Åpne" again while step 2's page opens or is open
  does nothing, and the holders are given back whenever no page opened (a recreation during `begin` included). From
  Android 16 the screen's line names the page's two choices (`setupHowRes`, finding 3). The PIN-gated
  Settings' "Kartlenker" keeps one row: with the switch off and Play services' links still off, it gives them back (Q4,
  `mapLinkBackShown`).
- **Meldinger** (Q1): every web link in a bubble is underlined and tappable (`ui/messages/MessageLinks.kt`); a map
  link goes straight to the handler while the switch is on; any other link goes to the one app Android names that the parent allows (`messageLinkTarget`: the allowlist, the login browser, Vibb One; never Play services; nothing during a time rule), else the calm note. The bubble's ink plus an underline is the user's choice (2026-11-18).
- **Dagen**: a "Kart" row per map link (at most two) in an event's location or description, for an allowed or suspended target.
- Vibb's legacy PIN lock isn't checked (phase D removes it; lock task blocks the start under it).
- **The report** `map_links`: the target, its state, Play services' link handling (`DomainVerificationManager`, API 31),
  Vibb One's hosts `{selected, selectable, declared}` and `reach` per class of link (what Android resolves a probe to).
- Contracts: `map_links/contract.json` (shared, `MapLinksContractTest`), `map_link_rules.json` (the launcher's,
  `MapLinksTest`).
- Android 14-17: everything used is API 31-34 (`DomainVerificationManager`, `ACTION_APP_OPEN_BY_DEFAULT_SETTINGS`,
  `PackageManager.*Flags`), no gate needed at minSdk 34; Android 15's intent-filter matching enforcement is met (the
  `geo:` start names the target package and matches its `VIEW geo` filter). Checked by source only until the gate.
