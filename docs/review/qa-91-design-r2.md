# Design QA: Find My Device before the first unlock, revision 2 (`docs/design/91-find-before-unlock.md`)

Checked against `design-91` (1a17c2cf8, = `main` 60ee2a86b + revision 2), and the Android 14-17 rule added on `main` in
c51a248ad. Read: the root `CLAUDE.md`, `launcher/CLAUDE.md` (the standing rule on restrictions), the notes
`calls.md` (direct boot, `DirectBootComponents`), `dns-and-vpn.md` (the sink's lockdown), design 59d §5 (the boot
cover over the BFU keyguard); the launcher's `calls/BootCallReceiver.kt`, `lock/BootCover.kt`,
`lock/LockTaskChrome.kt` (`writeStatusBar`), `lock/StatusBarHealPlan.kt`, `server/EnforcementPlan.kt`
(`restrictOutgoingCalls`, `restrictSms`), `server/AppEnforcer.kt` (`applyHardening`), `server/Hardening.kt`,
`assistant/AirplaneRoute.kt` (`turnOff`), `server/MobileNetwork.kt`, `server/MobileNetworkRuntime.kt`,
`server/FindMyDeviceRing.kt`, `server/LocateCommands.kt` (`lock`, `wipe`, the stop intent), `timerules/ZenRuleRuntime.kt`
(`holdForRing`, `silencesAlarms`), `Notifications.kt`, the manifest's direct-boot-aware components; the server's
`models.rs` (`EXPIRING_COMMANDS`, `past_expiry`, `redeliverable`), `handlers/device_api.rs` (the pop, "older ones never
again", `end_expiring_at_start`), `security.rs` (`require_device_token`, `TokenIndex`, the `limits` buckets) and
`server/testdata/status_heartbeat.json`. No emulator, no build. Marks as in the note: **[aosp]** read or recalled from
AOSP, **[docs]**, **[device]**; a claim of this review that rests on memory of AOSP says so.

**Verdict: the lean default and lost mode are the right answer to the user's cost concern, and the narrow token,
`:locked` and the filtered pop still hold. But two of lost mode's headline parts don't reach the people they are for
on a typical managed phone: the SMS fallback and "Ring Mamma" run into the launcher's own `DISALLOW_SMS` and
`DISALLOW_OUTGOING_CALLS`, and the finder line is drawn on a boot cover that asks for the PIN pad at once. The Ring's
timing contradicts itself after 12 hours, a silent time rule can mute the BFU ring, and the DE file has three
processes writing to one SharedPreferences file. Lost mode also adds a third writer to two DPM states that already have
owners. Nothing in the note was checked against Android 17.**

Counts: **2 High, 9 Medium, 15 Low.**

## High

### Q91-H1 The SMS fallback and "Ring Mamma" are blocked by the launcher's own restrictions (§16, §17.1, D15, D17)

`EnforcementPlan.restrictSms` sets `DISALLOW_SMS` when SMS is "Av", or when it is "Styrt av Vibb" without our SMS
role. `restrictOutgoingCalls` sets `DISALLOW_OUTGOING_CALLS` when calls are off, while a time rule blocks calls
(`callsBlockedByRule`), when calls fail closed, or while our dialer isn't in place. Both are user restrictions, so they
hold BFU (§3's own row). Both are deliberately never lifted by the override (`launcher/CLAUDE.md`, "Standing rule").

- `DISALLOW_SMS` is enforced through AppOps (`OP_SEND_SMS` maps to `DISALLOW_SMS`, with no device-owner exemption)
  [aosp, from memory; device]. `SmsManager.sendTextMessage` from `:locked` then fails silently or with
  `RESULT_ERROR_GENERIC_FAILURE`. A young kid with SMS "Av" is exactly the phone this would happen on, so lost mode's
  "SMS when it has no data" and "a new SIM sends you one SMS" (§14.6's sheet text) would be false for it.
- `DISALLOW_OUTGOING_CALLS` blocks "Ring Mamma" for every phone whose calls are off or rule-blocked at that moment.
  Even with calls on, the call must pass `KidCallRedirectionService` (direct-boot aware, `CallPolicyStore` from DE), so
  Mamma must be an allowed outgoing number in the DE call policy.

The note's §15.6 item 5 and item 9 test only the plain path, so the Jelly Star check would pass on a phone with SMS
and calls open and miss this.

**Fix.** Put a decision to the user (it changes a recorded exception): either (a) the beacon lifts `DISALLOW_SMS`
(and `DISALLOW_OUTGOING_CALLS` for "Ring Mamma") only for the one send or call, from `:locked`, BFU only, restoring it
in a `finally` and on the next start through a DE "I lifted it" mark (as §4.7 does for the location switch). BFU no
app of the kid's can use the gap. Or (b) the PWA's sheet says "SMS fallback: off, because SMS is Av on this phone",
the feature list shrinks to match, and the phone reports `sms_blocked` in the check. Recommend (a) for the SMS and
(b) for "Ring Mamma" (see Q91-M9). Either way, add both restrictions to §3's table, to §15.6 items 5 and 9 ("with SMS
Av, and with a call-blocking rule in force"), and a `sms` contract row for "blocked".

### Q91-H2 The finder line is drawn where a finder never sees it (§16, D15; design 59d §5)

In android mode BFU, design 59d §5 rule 2 has the boot cover call `requestDismissKeyguard` as soon as it is resumed,
interactive and has no ask open. A finder who presses power gets the cover, then at once the BFU bouncer ("PIN is
required after device restarts"). 59d §5 also records that SystemUI draws the owner-info line in the face's indication
area, **not on the bouncer**. So the finder line on the cover and `setDeviceOwnerLockScreenInfo` are both hidden behind
the pad. The finder sees the line only after pressing Back, and "Ring Mamma" sits on that same hidden cover. The note
says the cover "hides that face" and draws the line itself, but doesn't address the automatic ask.

**Fix.** While the DE flag says Mistet, or the finder mode is "Alltid", the cover doesn't ask for the pad on its own.
It shows the mark, the finder line and "Trykk for å låse opp" / "Tap to unlock", and asks only on a tap. 59d §5's
"Cancelled" state already has this layout. This changes the user's step-F decision for that one case, so list it as a
decision (D15b). Also:
- The beacon calls `setDeviceOwnerLockScreenInfo` itself when a check's answer turns lost mode on BFU. Today only the
  unlocked `AndroidLock` writes it.
- The cover reads the line through a fresh read of a DE file that only the beacon writes (Q91-M3), not a cached
  SharedPreferences.
- Add to §15.6 item 6: "what a finder sees on the first power press, BFU, in Mistet".

## Medium

### Q91-M1 The Ring's timing contradicts itself after 12 hours, and a late Ring is mostly useless (§5.2, §14.1, §14.3)

- §5.2 promises "a Ring never goes off hours later". But the quiet phase checks every 2 hours after the first 12, and
  the expiry is "15 minutes after the next expected check". A Ring pressed just after a check therefore plays about
  2 hours later.
- Even in the first 12 hours, a Ring that arrives up to 30 minutes late plays to an empty room unless the parent
  waits by it. The PWA's "Reaches the phone at its next check-in, by 14:34" asks the parent to come back at 14:34 and
  press nothing.
- `end_expiring_at_start` cancels every waiting ring, locate and stop at server start (`cancel_reason: restart`).
  Watchtower's updates make that routine, and a Ring waiting for a sparse check is cancelled silently.
- §14.1's "No network" row says a Ring waits under §5.2's locked expiry. But that rule applies only "while
  `locked_since` is set", and nothing sets it before the first check of this boot. Before then the one-hour clock
  applies.

**Fix.** When a check finds a waiting `ring`, deliver the hold instead of the ring by default, and push the parent who
asked for it: "Telefonen er tilkoblet nå - trykk Ring" / "The phone is connected now - tap Ring". The Ring then plays
in seconds over the stream while they are listening. A tick in the PWA, "Ring straight away when it checks in", keeps
today's behaviour for a parent who wants it. Cap the locked expiry at 60 minutes from the request, whatever the
cadence, and say so. Exclude rows waiting on a locked phone from `end_expiring_at_start`, or re-time them. Correct
§14.1's "No network" row. This push answers the parent's own request, so it is consistent with D7.

### Q91-M2 A silent time rule can mute the BFU ring, in the note's main case (§3 "Ring", §4.4)

`FindMyDeviceRing` relies on the alarm stream playing through DND. Our own zen rule can silence alarms
(`ZenRuleRuntime.silencesAlarms`), and `holdForRing` lifts it only through `apply()`, which reads CE. An automatic zen
rule and its active condition are kept by NotificationManagerService across a reboot [aosp, from memory; device].
Nothing of ours updates the rule BFU, so a "Skole" or "Leggetid" rule that was on at the restart stays on, even past
its end. The case the note puts first is "lost at home, school or a friend's, restarted". During school hours that
Ring is silent, and the note only lists this as a device check (§9).

**Fix.** Mirror our rule's id and its `silencesAlarms` bit into the DE config file. BFU, the beacon's ring sets our
own rule's condition to false (`setAutomaticZenRuleState`, which only the owner may call for its own rule; the
notification-policy access is DE state) and sets it back when the ring ends or at the handover. Add a contract or
plan row and a [device] check: "Ring BFU with Skole in force".

### Q91-M3 One DE SharedPreferences file is written by three processes (§4.2)

`locked_beacon` is written by the unlocked app (the mirror) and by `:locked` (`lost`, `ran`, `sms_count`,
`next_check_at_ms`). It is read by `:bootcover` (the finder line, H2) and by the main process at
`LOCKED_BOOT_COMPLETED` (`beaconStartPlan` runs in `BootCallReceiver`, in the main process). SharedPreferences caches
the file per process and rewrites the whole map on every commit. The main process loads the file BFU and then lives
on through the unlock (it is the call-screening process). Its first write after the unlock therefore writes back its
stale copy, and the beacon's `ran` keys and `lost` flag can be lost. That breaks §4.4 step 4's "never run twice" and
§14.4's "cleared only by the parent". `:bootcover` would never see a `lost` the beacon wrote after the cover started.

**Fix.** Use one file per writer:
- `locked_beacon` (config) is written only by the unlocked app.
- `locked_beacon_state` (`lost` from an answer, `ran`, the bookkeeping) is written only by `:locked`.
- Every read across processes is a fresh file read: a small JSON file written by atomic rename, not SharedPreferences.

The main process merges `ran` only after `:locked` has stopped (it is stopped at `ACTION_USER_UNLOCKED`), before the
first sync. Add this to §9's source scan (each key in exactly one file) and add a unit test of the merge order.

### Q91-M4 Lost mode adds a third writer to `DISALLOW_AIRPLANE_MODE` and to the status bar (§14.4, D18)

- `DISALLOW_AIRPLANE_MODE` already has two writers. `AppEnforcer.applyHardening`/`clearHardening` clears it when
  the parent's switch is off. `AirplaneRoute.turnOff` adds it and clears it in a `finally`. If lost mode sets it from
  the policy "on the side", the first unlocked `apply()` clears it again.
- `setStatusBarDisabled` has one writer today, `LockTaskChrome.writeStatusBar`. It caches what it last wrote
  (`statusBar.toWrite`) and also serves the update fence's shade block. A write from lost mode, or from `:locked`, is
  invisible to that cache, so the two would undo each other.
- §14.4 doesn't say whether the status bar stays disabled once the phone is unlocked while lost. D8 lists it among
  what lost mode turns on. If it stays disabled AFU, the kid loses the shade, Snarveier (design 99) and the Vibb
  notification (design 100) for as long as the mark lasts.

**Fix.**
- The server folds lost mode into the served `hardening.disallow_airplane_mode`: it is on while the parent's switch
  is on or the phone is lost. That leaves one writer on the phone, and the server owns the rule. The beacon BFU only
  ever adds the restriction (from an answer), never clears it.
- The status bar is disabled only BFU, by `:locked` from its DE flag, and released at `ACTION_USER_UNLOCKED` before
  `LockTaskChrome` starts. AFU, lost mode never touches it. `LockTaskChrome` reads the DPM state once at its start
  instead of trusting its cache.
- Or cut the status bar from step 2 (Q91-M9): with the airplane block in place, it adds little.

### Q91-M5 Lost mode's restrictions have no offline lift (standing rule) (§14.4, D9, D18)

Under the standing rule, every restriction the launcher adds must be liftable by the parent's offline code, unless
the user explicitly says otherwise. The hardening switches are the recorded exception (only the server lifts them).
Lost mode adds the airplane block, the status bar, location forced on over the parent's own Off (D9) and a 10-minute
interval. Picture a found phone abroad with no data (roaming blocked by design 95, no Wi-Fi). It keeps all of this
until it reaches the server again, and the parent standing next to it can't end it. The note doesn't ask the user.

**Fix.** Add a decision, D20. Recommended: the parent's offline code (the pause or override) ends lost mode's
phone-side parts AFU until the next sync: the airplane block, the status bar and the location override. The beacon and
the server's mark stay. The sync then shows "Savnet-modus er fortsatt på" with the End button. If the user prefers
server-only, record it as an explicit exception next to the hardening switches in `launcher/CLAUDE.md`.

### Q91-M6 Lost mode AFU is invisible to the kid while it overrides location Off (§14.4 "Quiet", D9, §11)

In lost mode the phone sends its location every 10 minutes, even where the parent set location Off. AFU nothing on
the phone says so ("Quiet: no notice"). §11's mitigation, "its banner stays on the phone's summary", is the PWA's
summary, not the phone. A mark set by one parent, or left on after the phone was found, becomes a covert tracking mode
for a kid who has the phone in hand. That conflicts with the note's own "calm, never sneaky phone" (§18) and with the
logged, visible-to-all intent.

**Fix.**
- Keep it quiet BFU (a thief).
- Once unlocked while lost, the kid sees one calm, persistent line on Home or the lock glance, nb and en: "Telefonen er
  meldt savnet. Posisjonen deles med mamma og pappa." / "This phone is reported lost. Its location is shared with your
  parents."
- The D16 push "a lost phone was unlocked" goes to every parent, not only the one who marked it.
- Consider ending the location override (D9) at the first unlock unless the parent ticks "keep".

### Q91-M7 The outgoing SMS can be forged, and in Stjålet it tells the thief (§17.1, D17)

The parent receives "Vibb: The phone reported lost is on ... Position 59.91,10.75" from an unknown number, and §17.1
invites pasting it into the PWA. Anyone who knows the parent's number can send the same text: a false trail, or a lure
to a place. The note looks at spoofing of incoming SMS (§17.2) but not of the outgoing one. Also, in Stjålet the note
hides everything from the thief ("nothing tells a thief the phone is being watched"). Yet an SMS sent from the
thief's own SIM shows up in their operator's app and bill, and gives them the parent's number.

**Fix.**
- Add a short authentication tag to every SMS: 8 base32 characters, an HMAC over the text under a per-phone
  `sms_key`. The phone makes the key AFU and registers it with the token. The server keeps it sealed, as it keeps the
  kid PIN key. In DE it is sealed under `locked_v1`.
- The PWA gets a "Sjekk en SMS" / "Check an SMS" box in Finn telefonen, and the sheet says that an SMS that fails the
  check isn't from the phone.
- The tag fits the 160 characters once Q91-L8's "±" is gone.
- In Stjålet, make the new-SIM SMS a choice in the sheet ("Send SMS fra tyvens SIM" / "Text from the thief's SIM",
  default on), with that trade-off stated.

### Q91-M8 Nothing is checked against Android 17, and the gates run on Android 16 only (§3, §9, §15.6)

The root `CLAUDE.md` now requires 14-17, a note that names the versions it was checked on, and version-dependent
emulator gates on the lowest and highest. The note is written for 14-16, and its emulator pass and Jelly Star checks
are on API 36. Items to check against Android 17's behaviour changes (all-apps and target-37) before the build:
- **Background audio** [verify]: as recalled, 17 restricts audio playback, audio focus and volume changes from apps in
  the background unless a foreground service that can claim while-in-use runs. The BFU ring raises `STREAM_ALARM` and
  plays from a `specialUse` service started by an alarm or the stream.
- **Restricted SMS that look like OTPs**: §17.2 attributes this to Android 16; as recalled it is an Android 17
  change [verify]. It matters only for the deferred command SMS, but the note should name the right version.
- **Target-37 defaults** (certificate transparency, local-network access) against design 76's pinner and
  `toServer`; local-network access is out of reach for an https-only beacon, but say so.
- **FGS starts, exact alarms and `PendingIntent` network callbacks** for any 17 tightening.

Then add an API 34 AVD and an API 37 AVD to the emulator pass (§9) for the boot, the alarm, the ring and the SMS.
Keep the Jelly Star (16) for the radios.

### Q91-M9 The four-round estimate is short, and a few parts can go to keep it lean (§10, §12)

Step 2 is "~1 round". It holds the PWA's sheet, banner, events and police card, three pushes, the security log, the
DE flag, the lost cadence and battery budget, the scan, cells and joined network, the SIM, the airplane block, the
status bar, and the charging and shutdown checks, plus H1, M3, M4 and M5's fixes. That is closer to two rounds. Step 4
touches the boot cover, the area of both past BFU lockouts, which needs its own BFU device run. Realistically it is
5-6 rounds for steps 1-4.

**Cut, or move behind a later step:**
- **"Ring Mamma"** (H1's call restriction, the cover's call path from `:bootcover`, an outgoing call from an
  occluding activity BFU, Telecom unverified). The finder line with the number does most of the work.
- **`setStatusBarDisabled`** (M4's conflicts, unverified over a secure keyguard). The airplane block is the one that
  matters.
- **Learning the 12 scan BSSIDs from every AFU fix (D11).** Keep the joined BSSID and the IP. Scan learning adds
  report bytes (Q91-L11) and a map of the neighbourhood for a small gain.
- **The two-tier quiet cadence** (12 hours at 30 minutes, then 2 hours) together with the `schedule` contract table.
  A single served `next_check_s`, decided by the server alone, with a fixed phone fallback is enough until real data
  says otherwise.

## Low

- **Q91-L1 The grace and "no network" wait run in a long-lived foreground service.** Step 1 has the service wait for a
  validated network and then the grace. A phone in a drawer with no network keeps a foreground service up for days.
  Instead, at the locked boot, register the `PendingIntent` network callback and stop. A validated network sets an
  exact-while-idle alarm for the grace's end, and only that alarm starts the service for the check. This matches §14.1's
  "the service for one request, then stop".
- **Q91-L2 Mutable `PendingIntent`s on Android 14+.** ConnectivityService fills the network callback's `PendingIntent`
  with the `Network`, so it must be `FLAG_MUTABLE`. Since targetSdk 34, a mutable `PendingIntent` needs an explicit
  intent. Name the receiver's component, and pin it in a test.
- **Q91-L3 `booted_at_ms` comes from the wall clock.** After a flat battery, the clock BFU may be wrong before network
  time arrives. Send `elapsed_ms` (`elapsedRealtime`) instead, and let the server compute the boot as "server now minus
  elapsed". The server already distrusts the clock after a power cut (`end_expiring_at_start`).
- **Q91-L4 The phone's `"mode": "lost"` must not be trusted.** D9's exception (a fix kept while location is Off) must
  depend on the server's own `devices.lost_kind`, not on the body. Otherwise a stale DE flag, or a token holder, keeps
  fixes the parent has said no to. The answer's `lost` stays the only source of truth.
- **Q91-L5 Rate limits.** Say that `AuthedLockedDevice` reuses `require_device_token`'s `limits`: the failure bucket
  per client, the device bucket, and an in-flight class for the locked stream. Give it a second `TokenIndex` for the
  locked hashes. The 20-second rule would refuse the last-gasp check (shutdown) or the plug-in check right after
  another check, so merge or queue event checks instead of answering 429. A token holder can post "another SIM" every
  20 seconds, so debounce the D16 SIM push to one per SIM state per boot. Cap BeaconDB queries per phone (for example,
  6 an hour) so a token holder can't use the server against a third party.
- **Q91-L6 Salted ICCID hashes can be brute-forced.** An ICCID has about 10^10-10^12 likely values once the issuer
  prefix is known, and the salt sits next to the hash. Store an HMAC under a plain AndroidKeyStore HMAC key, or the
  hash sealed under `locked_v1`, so a DE file read gains nothing.
- **Q91-L7 §3's VPN row and §8's design-95 paragraph are out of date.**
  - The sink shape (Bare Vibb, or the roaming sink) sets always-on **with lockdown** (`dns-and-vpn.md`). That
    persists BFU while `KidVpnService` can't start, so every other app is cut BFU. Our own UID is exempt as the
    always-on package [aosp, from memory], so the beacon goes through: add "the beacon's check BFU with the sink's
    lockdown recorded" to §15.6.
  - "The parent's 'Mobildata av' (`mobile_data`) means Wi-Fi only BFU" is wrong: `mobile_data` is a report, and the
    parent's choice is Bare Vibb, under which Vibb, and so the beacon, still uses mobile data, including abroad under
    "vibb". Only "Ingen data" (`DISALLOW_DATA_ROAMING`) makes it Wi-Fi only abroad.
- **Q91-L8 The SMS text.**
  - "±" is not in the GSM-7 basic set or its extension. One "±" switches the SMS to UCS-2, which allows 70 characters
    a segment, so §17.1's example becomes two or three billed parts. Use "+/-" or "ca.", and test it in the `sms`
    rows (GSM-7 only).
  - Write the text in the parent's language (have the policy carry it), not the kid's phone language.
  - Count the SMS per SIM (by its hash) rather than per boot: a thief who restarts the phone gets five more each time.
  - On a dual-SIM phone, use the subscription's own `SmsManager`.
- **Q91-L9 Safe mode.** A thief can restart into safe mode from the power menu, where third-party apps (ours too)
  don't run [aosp, from memory; device]. `DISALLOW_SAFE_BOOT` is off by default (`Hardening.SAFE_BOOT(false)`). Lost
  mode should serve it on, as with the airplane block, or §18 should name safe mode as a hole.
- **Q91-L10 Contract hygiene.**
  - Test tokens "read `test-...-not-real`", but the token is 64 hex digits, and a validator that checks the shape would
    refuse them. Use a value that is valid and plainly fake, such as `7e57` repeated 16 times, and say so.
  - The IMEI report (§15.4) and the AFU fix's `network` are new status-report fields. They need rows in the contract,
    `intake_limits`, and a decision on whether `network` joins `heartbeat_fields`, which changes `fields_tag` in
    `status_heartbeat.json` (Q91-L11).
- **Q91-L11 The AFU `network` costs bytes on every report.** `location` is a heartbeat field (`status_heartbeat.json`),
  so 12 BSSIDs, the SSID and the cell ride on every heartbeat with a fix, though the server keeps them only inside a
  zone. Have the server's report answer carry `want_network: true` when the last fix lay inside a zone. The phone then
  sends `network` once, on the next report. The server still judges the zone.
- **Q91-L12 Matching the IP can give false hits.** A dynamic IPv4 address goes to another customer within days, so
  "Samme internettlinje som Hjemme" from a 90-day-old row can be a stranger. Match the IP only against rows seen in the
  last 7 days. Word it as weaker than a BSSID match ("trolig" / "probably").
- **Q91-L13 When a locate's fix is sent.** §4.4 step 3 says "sent with the next check". In the quiet phase that is
  30 minutes away. The fix should go in an immediate check of its own, and with location Off the answer should be
  "location off", not "no fix".
- **Q91-L14 Components and the stop receiver.**
  - Every new direct-boot-aware component (the service, the alarm and network receivers, the beacon's stop receiver)
    goes into `DirectBootComponents` (`calls.md`; `DirectBootComponentsTest`).
  - "`StopRingReceiver` made DBA, in `:locked`" would move the AFU ring's Stop (`LocateCommands.kt:582`) into a
    process that can't stop a main-process ring. Give the beacon its own stop receiver class in `:locked`, and leave
    `StopRingReceiver` where it is.
- **Q91-L15 Small text issues.**
  - §4.3 says "the four `locked/*` routes", but §5.1 has three plus the registration route.
  - "Restarted 14:02 · not unlocked yet" stays true for a phone switched off BFU in normal mode, which sends no last
    gasp. Show the last check's time next to it.
  - The PWA's save rule (no jump to the top) is stated for the sheet only. Say it for the Posisjonsinnstillinger
    switch, the finder choice, the OpenCellID token and the BeaconDB switch too.

## What holds

- **The narrow token** (§4.3, §6) and the two route groups as structure, not per-handler checks.
- **`:locked` as its own process.**
- **The filtered pop**, with "older ones never again" kept within the Find set. It fits `device_api.rs`'s pop and
  receipts as they are.
- **The lean default's logic.** The grace on a validated network, the server-owned `next_check_s` with a clamped
  fallback, and lost mode as the parent's explicit mark answer the user's "1 av 1000" concern.
- **The battery numbers are the right order of magnitude.**
  - 0.3 mAh for an LTE wake is the low end. A cold start of `:locked`, DNS and a full TLS handshake on LTE are more
    likely 0.4-0.6 mAh, which is still under 1 % a day in normal mode.
  - Lost mode's ~7 mAh an hour is two-thirds keepalives (15 an hour at ~0.3 mAh). The ~8 % a day is plausible, and
    the phone's own idle drain dominates, as §14.2 says.
  - The Jelly Star measurement (§15.6 item 13) is the right gate.
- **D14** (the raw ICCID and IMSI never leave the phone), **D13** (BeaconDB opt-in, server-side, lost only, a generic
  User-Agent) and **D12** (OpenCellID with the parent's own token, kept as a secret in the database, with credit shown).
  These are the right privacy defaults. "Not legal advice" is the honest framing for the SMS from another person's SIM.
  Q91-M7 adds the thief's view.
- **`DISALLOW_AIRPLANE_MODE`**: when set, it ends an airplane mode already on (`AirplaneRoute.turnOff` relies on
  this). So lost mode marked AFU also brings a phone out of the kid's Flymodus, which the note could say.
- **Names**: no real name of the kid. The parent is "Mamma" throughout, and the nb and en lines are given.
