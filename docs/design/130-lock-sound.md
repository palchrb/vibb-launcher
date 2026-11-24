# Design 130: A sound for a notification on the locked phone (Av / Vibrer / Lyd)

Status: built, 2026-11-24 (branch `lock-sound`). Server and launcher. PLAN's queue item (4h), approved 2026-11-23
("Ja"): the user asked for an ordinary message sound for chat and SMS on the locked phone, beside the Lock Screen
page's "Vekk skjermen" and "Vibrer ved varsler".

Builds on design 82 W3 (`docs/design/82-meldinger-as-an-app.md` §9 and §14: the notice buzz) and launcher round 7 §3
(the notice wake). Notes: `docs/notes/launcher/lock-and-glance.md` ("The notice buzz"), `docs/notes/server/pwa.md`
(Lock Screen & PINs).

## 1. Why the phone is silent today

A locked phone mutes every notification: the lock's task features leave out notifications
(`LockTaskHelpers.featuresWhileLocked`), which mutes their sound and vibration (design 82 §9). W3's buzz is the
launcher's own vibration, deliberately "no sound". Lifting the lock task's notification feature would bring back every
app's own sounds, also for rows the parent set to Off - not offered.

## 2. The change

- The switch "Vibrer ved varsler" becomes one choice, **Av / Vibrer / Lyd**, in the same cell under "Vekk skjermen ved
  varsler". Off by default.
- **Lyd** plays Android's default notification sound once (`Settings.System.DEFAULT_NOTIFICATION_URI` through
  `RingtoneManager.getRingtone`, `AudioAttributes` `USAGE_NOTIFICATION` / `CONTENT_TYPE_SONIFICATION`), so the
  notification volume and Do Not Disturb apply.
- Exactly the buzz's rules decide **whether** (`noticeBuzzAllowed`'s gate, unchanged): locked only, nothing busy (a
  call, a ring, the emergency flow, an alarm, the boot mark, the process's first minutes), the interruption filter
  "all", no time rule's silence, the 10 s gap, and only the notices the buzz already gets (a row set to Off never
  reaches it). One gap for both: a sound and a buzz never follow each other within 10 s.
- **What** follows the ringer (the new pure `noticeAlertAction`):

  | Choice | Ringer Normal | Ringer Vibrate | Ringer Silent / unreadable |
  |---|---|---|---|
  | Av | nothing | nothing | nothing |
  | Vibrer | buzz (40 ms) | buzz | nothing |
  | Lyd | the sound | buzz | nothing |

  Lyd on a normal ringer is the sound alone (no buzz beside it): what was asked for, and one signal per notice.
- No sound file of our own, no channel, no notification: nothing new on the phone's notification list.

## 3. The policy and the database

- `launcher_ui.lock_notice_alert`: `"off"`, `"buzz"` or `"sound"`, always sent. It **replaces**
  `launcher_ui.lock_buzz_on_notification`, which is dropped (no backwards compatibility on the phones yet). The
  launcher reads a missing or unknown value as off.
- Migration **0173** `lock_notice_alert.sql`: `device_policy.lock_notice_alert TEXT NOT NULL DEFAULT 'off'` with a
  CHECK of the three values; a stored `lock_buzz_on_notification` 1 becomes `buzz`, 0 `off`; the old column is
  dropped.
- The form: `POST /devices/{id}/lock-alert`, field `alert` (`off`/`buzz`/`sound`; anything else is a 400 with nothing
  written), back to `#lock-alert`, the phone nudged, a `lock_alert_changed` security-log line when the value changed.
  The old route `/lock-buzz` is gone. Old `lock_buzz_changed` rows keep their words (the keys stay in the catalogs).
- Pinned by `policy_json_keys_snapshot` and the launcher's `PolicyResponseCompatTest`. No contract file carries the
  key.

## 4. Copy (checked against "Words in the UI")

A heading of one word, three one-word choices, no sub-line; the explanation behind the group's (i), which already
spoke of the buzz and is rewritten, not added to. Nothing orange: every choice is the parent's.

| Where | nb | en |
|---|---|---|
| Row label | Varsling | Alert |
| Choices | Av · Vibrer · Lyd | Off · Buzz · Sound |
| The group's (i) | For appene på låsen og tapte anrop: skjermen våkner, og telefonen vibrerer eller spiller Androids varsellyd. Lyd følger telefonens lydmodus og volum. Det låsen viser, kan alle i nærheten se. Et varsel kan sveipes bort der, som på Androids egen låseskjerm; ingenting kan besvares. | For the apps on the lock and missed calls: the screen wakes, and the phone buzzes or plays Android's notification sound. Sound follows the phone's ringer mode and volume. What the lock shows, anyone near the phone can see. A notification can be swiped away there, as on Android's own lock screen; nothing can be answered. |
| Security-log event | Varsling på låseskjermen endret | Lock screen alert changed |
| Security-log line | telefon {device}: varsling: av / vibrer / lyd | phone {device}: alert: off / buzz / sound |

## 5. Android 14-17

Checked against the SDK's `api-versions.xml` (API 37) and the platform's behaviour changes for 14, 15, 16 and 17:

- `RingtoneManager.getRingtone(Context, Uri)` (API 1), `Ringtone.setAudioAttributes` (21), `play()`/`stop()` (1):
  none deprecated through API 37; only `setStreamType`/`getStreamType` are (not used). No `SDK_INT` gate needed;
  lint's NewApi stays on.
- Playing audio needs no foreground state or permission on 14-17 (the background start limits are for activities
  and foreground services, not audio); the launcher is also the Device Owner and the lock's task. The attributes are
  set before `play()` (on 14+ a late `setAudioAttributes` rebuilds the player).
- When the local player can't open the chosen sound (a custom file on storage the launcher can't read), `Ringtone`
  falls back to the system's remote player (SystemUI's `RingtonePlayer`) with the same attributes, on 14-17 alike. A
  default sound of "None" gives no ringtone: nothing plays.
- Do Not Disturb restricts `USAGE_NOTIFICATION` audio for apps on 14-17 (zen mode's app-op restrictions); the gate
  already refuses any filter but "all", so it never comes to that.
- On 14+ the ring and notification volumes may be split; a notification volume of 0 under a Normal ringer plays
  nothing - Android's own behaviour, kept.
- The sound is set up and played on its own thread (`notice-sound`, a `HandlerThread`): `getRingtone` reads settings and
  storage and the player talks to the media server, never on the main thread. The gap is stamped on the main thread
  before it is posted. It asks a transient focus that lets music duck (`AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`) and
  gives it back when the sound is released: 5 s after it started (`NOTICE_SOUND_RELEASE_MS`; a notification sound is
  shorter), or when the next one starts. `Ringtone.stop()` frees its player. `AudioFocusRequest` is API 26: no gate.
- Not checked on a device: whether the Jelly Star (16) plays it from the lock as on the emulator; the emulator row
  (`docs/testing/emulator.md` §6bi) is written but not run.

## 6. Release note

Ship the server first, as always: a phone on the old launcher reads the policy's old buzz key, which the new server no
longer sends, so the lock screen's buzz stops until that phone's launcher updates.

## 7. Tests

Server: `lock_glance::the_alert_choice_saves_logs_nudges_and_reaches_the_policy` (the cell, the three values, a
bad value's 400, the log, the 404), `policy_json_keys_snapshot`, `launcher_ui`'s key test, the Lock Screen page's
redirect table, and a migration test (a stored buzz on becomes `buzz`, off `off`). Launcher: `LockWakeTest`
(`noticeAlertAction`'s table and the gate by source), `PolicyResponseCompatTest` (the three values, missing and
unknown are off).
