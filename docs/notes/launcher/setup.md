# The phone's setup: the steps, the hold and the flow (design 114)

`docs/design/114-setup-status.md` is the spec; this is where the code is.

## The steps and the one rule

- `setup/SetupSteps.kt` (pure): `SetupStep`, the table of design 114 §2.2 in its order (key, need, `read_by`, `when`,
  summary, level, features, the Android actions the flow's "Åpne" tries, the name and the screen's one line);
  `SetupInputs` (the policy's mask, clock format, calls managed, Android's lock, design 110's map links);
  `setupMissing` / `setupCount` / `setupAlarm` / `setupRow`, the rule the server's `setup_status` runs too;
  `flowScreens`, `flowButtons`, `flowProgress` for the flow. `write_settings_used` reads bit 16, bit 8 (Sound: the
  kid's ringtone picks write `Settings.System`) or a clock format in use, as the server's `write_settings_needed`.
- The shared contract is `contracts/core/setup_steps.json`: `SetupStepsTest` checks the
  table, the names in nb and en, every `needed` row, the report's exact encoding, the policy, the tick, `map_readings`
  (rows 15-16 read from a `map_links` report, as the server's `map_links::setup_part`) and the copies. `map_links`'s map
  app is read from `controllablePackages`, the set the report's `installed_apps` lists (a hidden app counts).
- `setup/SetupReadings.kt`: the live readings (off the main thread) - the Parent PIN from the vault
  (`ParentPin.status().configured`: the cached policy carries no hash), Device Owner, fine location, notification
  access, the user's own mobile-data switch (`isDataEnabledForReason(USER)`, absent without a default data SIM; the
  manifest's `READ_BASIC_PHONE_STATE`), Data Saver (`restrictBackgroundStatus`), the battery exemption, design 107's
  orange rule (`channelsOrange`, the server's About rule, checked against 107's `pages` rows), full-screen intents,
  Modify system settings, the assistant role, Wi-Fi in airplane mode, the call filter's two roles, the moved default
  apps (read live, `AssistantSetup.defaultAppsChanged`), the fingerprint (absent without a sensor), Google's location
  accuracy (`location_private`, below). `report()` is the
  status report's `setup` (only the readings no other field carries, and the hold's `hold_until_ms`/`hold_left_ms`).
  `watch()` registers `ACTION_RESTRICT_BACKGROUND_CHANGED`; the listener's connect/disconnect asks for a sync too.
- The moved default apps (§2.2.1): the pick window that moved a role keeps its snapshot (`assistant_moved_from`) while
  a role is listed; `stillMoved` drops an entry once its role is back (call screening and redirection are row 13's);
  the flow's "Standardapper" opens the pick window with `keepSnapshot = true`.

- Design 114b (`docs/design/114b-setup-pin-and-roles.md`): `child_pin` (required, counted) reads
  `KeyguardManager.isDeviceSecure`. Design 132: a stored Child PIN counts only with Android's credential (the legacy own lock's row went); before that, with Vibb One's legacy own lock, a `kid_lock` in the cached policy counted instead
  (`childPinDone`). It goes in the report's `setup`. In the flow it is a screen done in the PWA (`SetupStep.inPwa`): its
  line, "Senere", no "Åpne". It moves on by itself after the apply that follows the PIN's arrival, and
  `MdmDeviceAdminReceiver.onPasswordChanged` asks for a sync. `call_filter_early` (optional, `calls_unmanaged`) is row
  13's two role requests in the same pick window, offered while calls aren't managed. Its `when` is the exact opposite
  of row 13's, and it reads the same roles.
- Google's four switches (design 126 B1-B4: `NUMBER_VERIFICATION`, `AD_ID`, `DIAGNOSTICS`, `PLAY_PROTECT_UPLOAD`):
  optional parent steps the phone can't read (Play services' own state), so "Gjort"/"Hopp over" as every parent step.
  "Åpne" tries each `phone_page` action in order through `systemPageIntent` - Play services' own settings action first
  (`com.google.android.gms.settings.GOOGLE_SETTINGS`, `.ADS_PRIVACY`, `.VERIFY_APPS_SETTINGS`), then Android's public
  page. The Play-services actions are **not yet verified** on a Play image (design 126 §5.2's "[verify]"): the emulator
  gate keeps only those that resolve to an exported activity of `com.google.android.gms`, and the paths in
  `setup_how_*` follow what the gate and the Jelly Star show.
- `location_private` (2026-11-22, design 125: "Posisjon uten Google"): optional, `when` `location_interval` (the cached
  policy's `location_policy.mode` is `interval`, `SetupInputs.locationInterval`). Done when Android's `network` provider
  is off while Location is on (`LocationManager.isProviderEnabled(NETWORK_PROVIDER)` false - Google Location Accuracy
  off, or no network provider at all); `null` with Location off. It goes in the report's `setup`. "Åpne" opens
  `ACTION_LOCATION_SOURCE_SETTINGS` (Location), from where the parent goes to Location services › Google Location
  Accuracy - a setting of Play services that no Device Owner API sets; no Play-services intent is tried (none is a
  public, stable action on 14-17). `DISALLOW_CONFIG_LOCATION` (Advanced › Restrictions › "Hold posisjon på", on by
  default, not lifted by the hold or the pause) greys Google's "Forbedre posisjonsnøyaktigheten" (Improve Location
  Accuracy), so the how-text (2026-11-22, the PWA's `setup.how.location_private` and the launcher's string alike) says:
  "Hold posisjon på" off in the PWA first, the switch off, Wi-Fi scanning left on (Bluetooth scanning may go off),
  "Hold posisjon på" on again. Why it is offered: `docs/setup/google-account.md` §2.

## The setup hold

- `Enrollment.enroll` decides "fresh" (`setupHoldStarts`: device owner, `policy_ever_applied` false, no cached policy)
  before `DeviceToken.set`, and starts `RestrictionsPause.startHold` in the success path. Only the enrolment calls it
  (`ParentPinCallersTest`); a re-enrolment of a managed phone never starts one.
- The hold is the restrictions pause with `restrictions_paused_hold` set: `durationMs()` is `SETUP_HOLD_DURATION_MS`
  (4 h, `restrictions_pause.json`'s `setup_hold_max_ms`) while it is, else 2 h; `start()` writes it false and
  `clear()` clears it. `startHold` grants POST_NOTIFICATIONS first, opens the window with the screen lock unknown
  (`WindowRelock.opened(.., secureKnown = false)`: the relock never removes a PIN set in the setup wizard), then the
  flag and the pause in the same lift.
- While it lasts: a `start_pause` command answers "in setup" and changes nothing; screen time doesn't count
  (`screenTimeCounts(setupHold)`); the window notification's tap opens the flow; Home's resume starts the flow in place
  of its content. It ends like every window: "Ferdig" (`lockAgain(LOCK_AGAIN)`, synchronous), the PWA's "Lås igjen",
  a restart, the ceiling.
- The PWA keeps a held phone "open" up to the hold's end (`paused_at`'s exception, `device_status.setup_hold_until_ms`).

## The flow

- `setup/SetupFlowActivity.kt`: its own task (`taskAffinity` `.setup`, `singleTask`, not exported, out of Recents),
  so a Home press from Android's page brings it back at the same screen; Back does nothing in the hold (an
  `OnBackPressedCallback`: predictive back on 16). The run's skips live in `SetupRun` (the process). Screens: Not open
  (outside a window: "Sett på pause i 2 t", the parent PIN through `ParentPin`), Wait (before the first policy with
  nothing runnable; "Prøv igjen" and "Ferdig" after 30 s), one step a screen, the end ("Klar" / "N steg igjen" and
  "Ferdig").
- `setup/SetupPages.kt` builds each step's pages through `systemPageIntent` (an unsuspended system page - inside a
  window); the assistant, the call filter and the default apps open in design 98's pick window, Wi-Fi in airplane mode
  through its Wi-Fi step.
- The gate of 2026-11-19 (`docs/testing/2026-11-18-114-emulator-check.md`): row 5 opens the Internet panel first from
  API 36 (`mobileDataActions`: Android 16's SIM page has no Mobile data switch), and the flow reads its step again on
  window focus (the panel is a SystemUI dialog there); "Sett på pause i 2 t" from the flow marks the pause
  (`SetupRun.markPausedFromFlow`) and the flow comes back once - from Home's resume (14 restarts Home) or its own
  `onDestroy` (16 leaves Settings in front) - since leaving lock task clears every locked task.
- "Gjort" on a parent step: `SetupTicks` (a pending set, posted to `POST /api/devices/setup/ticks` at the start of
  every sync until done or carried by the policy's `setup.ticked`).
- Parent Settings has one row, `settings_setup` ("N steg igjen" in orange, or "Fullført"), which opens the flow; the
  rows that sat there (background connection, Modify system settings, Vibb's notifications, the assistant, Wi-Fi in
  airplane mode, the call filter, fingerprint, media on the lock screen) are flow screens now.
- The setup notification (`AssistantSetup.refreshNotice`) counts `SetupReadings.count` and opens the flow.
- The flow shows only missing steps (§6.3): a step done can't be redone from it - re-picking the assistant, Wi-Fi in
  airplane mode once stored (`wifi_kept` has no on-phone way back) or another fingerprint is Android's own Settings in
  a pause (code QA L9).
- The hold fails closed on a wall clock set back past the time elapsed (an NTP or NITZ correction right after the
  enrolment): `timedWindowActive` ends it; the PWA's "Åpne for oppsett" recovers (code QA L9; the gate's owed checks).

## Koble til (2026-11-20)

- A Device Owner phone that isn't enrolled - a QR whose extras never arrived, any failed QR - gets "Koble til" from
  Home's resume (`setup/ConnectActivity.kt`, past the gate and the lock, before the hold's flow): the server address
  (the stored one filled in), "Oppsettkode", one line where the parent finds the code (the phone's page in the parent
  app › Avansert › Ny oppsettkode) and the button. Pure `connectScreenDue` (`ConnectScreen.kt`): Device Owner, neither
  `enrolled` nor a stored token nor an adopted hardware key, and `settingsAccess` OPEN (no policy ever applied) - never
  wider than the Settings gate (the lost preferences below are the one exception); checked again at every start, so an
  enrolment by any path closes it.
- The button runs S-M1's `serverUrlUsable` first (`server_url_refused`, the whole reason), then `QrExtras.enrolTyped`
  on the process's scope: the QR's one `applyProvisioningExtras` under `QrExtras`' mutex (a kept QR retry never runs
  beside it; its tailnet key is used, the kept extras dropped once enrolled), and on an enrolled phone nothing is sent
  (a second tap, a second instance, a QR retry that got there first). A refused code (`CODE_REFUSED_STATUSES`) says
  `re_enrol_failed`, anything else - 408, 429, a 5xx, no network - `re_enrol_unreachable`. Enrolled, it finishes and
  Home's resume leads to the setup flow (the hold started at the enrolment).
- The busy state and the failure line are the process's, not the instance's (Home's `singleTask` and a recreation make
  new ones): `typing` (one tap, one enrolment) and `QrExtras.running` (any apply in flight, a QR delivery or retry
  too) show "Kobler til …"; when neither runs, the rule is read again and an enrolled phone's screen finishes.
- No tailnet key field: the kept QR extras' key is used, and for a tailnet-only (`*.ts.net`) server without a QR the
  parent sets the key in Settings (open before the first policy: Back, the drawer's Settings, "Tailscale-nøkkel" / "Tailscale auth key"),
  then comes back. Lighter than an expander on a screen meant for two fields, and the case is rare: the server's QR
  carries the key.
- "Nødsamtale" / "Emergency call" under the button, always there: `EmergencyCall.confirm`, as on every locked screen.
- The preferences lost or cut short on a phone that was managed: the enrolment's witness (`server/EnrolmentWitness.kt`,
  `enrolment_witness` in device-protected storage, written atomically, never removed by the app) keeps two facts and
  the server address: `enrolled` (every enrolment) and `policy_applied` (only `storeAcceptedPolicy`). `policy_applied`
  counts as "a policy was applied" wherever that decides what to enforce (`everManaged`: the sync, `cachedPolicy`,
  `currentPolicyDecision`, `TimeRulesStore`, the setup hold's start, Settings' gate) - so the sync enforces the last
  plan or nothing allowed, never no policy, and Settings stays behind the parent code (with no PIN stored it doesn't
  open). An enrolment alone is not enough: a new phone without its first policy still sets up (no policy, Settings
  open). Either fact keeps the first-launch reset away and makes Koble til due although a policy was applied
  (`connectScreenDue`'s `witnessEnrolled`), the witness's address filled in; it takes only an address on that host
  (`connectUrlAllowed`, refused with `server_url_refused`): a new code from the parent for the same server is the way
  back. A witness that is there but doesn't read counts as both facts, with no address: Koble til then takes any
  server the address rule takes - accepted (code QA L3 of `koble-til-key`), since corrupting the witness needs root or
  our own uid, and refusing every address would leave that phone stuck until a factory reset, as §6bp row 6 did. The
  enrolment commits `enrolled` synchronously before it writes the witness, so a process death between the two never
  looks like lost preferences. A phone with an adopted hardware key (design 129/129b: every enrolled phone since 129b)
  gets Koble til the same way (the gate of 2026-11-25, §6bp row 6): the key
  book lives in its own files (`device_key`, the device-protected mirror `device_key_adopted`) and outlives the cut, but
  the server address and `enrolled` went with the main file, so the key can't reach its server. `connectScreenDue` takes
  the token and the key apart: a stored token (`DeviceCredentials.tokenStored`, in the main file) still holds the
  screen back; an adopted key (`KeyBook.adoptedStored`) holds it back only without the witness (a phone the witness
  never saw enrolled, today's rule). The key is not dropped by the rule: it stays until a new enrolment succeeds, which
  attests a fresh key for the new code (`enrolKeyAlias` never hands the adopted key to an enrolment, so an old code
  typed again and refused can't delete it), then drops every other key, the old one included (`enrolmentDrops`), and
  every enrolment challenge (`newEnrolmentBook`), as every new enrolment does (design 129 §5.1). A healthy enrolled phone (`enrolled` set) never gets the screen.
  `PolicyGateTest`, `ConnectScreenTest`, `FirstLaunchResetTest`, `DeviceKeyContractTest`.
- Back closes it for that one resume of Home; it comes back the next time Home comes to the front. Not exported, out
  of Recents, `FLAG_SECURE`. `ConnectScreenTest`.

## Android 14-17

Every API used is in 34 (`canUseFullScreenIntent`, `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`) or below
(`isDataEnabledForReason`, `ACTION_APP_OPEN_BY_DEFAULT_SETTINGS`, `READ_BASIC_PHONE_STATE`): no version gate. The flow
starts only from Home's resume or a notification tap (no background start); insets by `fitsSystemWindows`
(edge-to-edge on 15+). The OEM pages' names and the restricted-setting greying are the emulator gate's and the Jelly
Star's to check (design 114 §11).
