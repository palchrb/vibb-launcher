# Design 25: the lock's glance page (to be designed)

Status: request and user decisions only; the architect starts once music step 4 (fix round) and design 24b are
merged into `main`, because both change `PinLockActivity`.

## The request (user, 2026-10-11)

The two-stage lock from music step 4 (design 20 "PIN lock while media is actively playing", mockup `Lock.dc.html`)
becomes permanent. The power button always shows the clean first page first; **"Lås opp"** leads to the keypad.
The first page shows:
- the clock and the date;
- the music tile while something plays (as step 4);
- a short list of notifications, mainly missed calls and messages (e.g. Element X);
- "Lås opp" and the emergency call button.

## User decisions (2026-10-11)

- **Message content:** the parent chooses in the PWA. The default is the sender only (e.g. "Element X: Pappa (2)"); a
  switch shows the text too.
- **Tapping a notification:** the keypad comes up, and after the right code the conversation or the call log opens
  directly.
- **Sources:** missed calls always. Plus apps the parent marks "vis på låseskjerm" in the PWA's app list; Element X is
  on by default.

## Known starting points

- The launcher is the default dialer (`calls/KidInCallService`), holds `READ_CALL_LOG`, and has
  `calls/MissedCallReceiver`.
- `badges/BadgeListenerService` already listens to notifications for the badges. It reads no extras today; the glance
  page needs the sender (and, if the parent allows, the text).
- Step 4's media view, its emergency button and its "Lås opp" stage are the base. Design 24b's own keypad and its
  relock rules apply unchanged.
