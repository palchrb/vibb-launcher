# QA 17b code review (commit a4b1d826, 2026-10-07)

In a worktree of a4b1d826, `./gradlew --no-daemon testDebugUnitTest assembleDebug -PwarningsAsErrors=true` is green: 684 tests, 0 failures (`VoipCallPlanTest` 21, `VoipCallGuardTest` 5). **No High or Medium findings.** These hold: every send (FSI, answer, decline, reopen) goes through `send` behind `voipCallOrEmergency`, and the card and FSI also give way to an alarm, so the precedence and the `RefrontPolicy` order are unchanged. The FSI goes once per `ringId`, only from a resumed lock with the screen on; the 300 ms settle is cancelled in `onPause`, so the wake activity's pause (queued right after the ring's own pass) never counts as "came up", and a false "left" only brings the card. `pickAnswerIntent` can't return the decline; javap of androidx core 1.17 confirms `key_action_priority` and the answer/decline extras. The ring stops on Svar, Avvis, power, unlock, ring gone, the limit, the call FGS, another call and process death. The pin and lifetime are unchanged: FSI-denied rings pin like rings, and `updateFsiDenied` keys on rings that have an FSI. The reader reads intents and one boolean only.

1. **Low - Svar can silence a ring that nothing answered** (`PinLockActivity.kt:224,100-102`, `VoipCalls.kt:302-306`). Without an answer intent, `answer()` sends the FSI, so Element's ring screen comes up silent. A refused start (`voipAnswerCheck`) also leaves the call ringing silently. Fix: have `answer()` say which intent it sent and silence only after the answer action; `voipAnswerCheck` un-silences (`silencedRing = null`, then `syncVoipRinger`).
2. **Low - the action fallback picks an ongoing CallStyle's hang-up** (`VoipCallPlan.kt:86-87,105-109`, `VoipCallReader.kt:53-58`). In compat, when the decline is null the negative action is the hang-up, and it is the only marked action. A CALL notice with an FSI (or FSI-denied) built that way now rings, and Svar on it hangs up. Fix: pass `EXTRA_CALL_TYPE == CALL_TYPE_INCOMING` (already read) and use the action fallback and FSI-denied ringing only when it is true. Test: `pickAnswerIntent(null, null, [cs(hangUp)])` gives null.
3. **Low - the auto-send ignores an open lock dialog** (`PinLockActivity.kt:186-189,90-96`). If a ring arrives while the Nødsamtale confirm or the Foreldrekode dialog is open, the wake activity's pause and resume puts Element's ring screen over the dialog; in 17 the dialog stayed on top. Fix: don't schedule or send while a lock dialog is showing; re-render when it is dismissed.
4. **Low - the calls gate isn't checked at the send** (`VoipCalls.kt:169,295-313`). `eligible()` runs only per evaluate (the 2 s poll; a sync evaluates only through the notification rule's sweep). So a Svar or FSI up to 2 s after calls are switched off, or after a no-calls rule begins, still goes out. Fix: for ring intents, require `ringingPackage in eligible(app)` in `send`.
5. **Low - only our own send counts as a try** (`PinLockActivity.kt:256-263`, `PinLockRuntime.kt:476-479`). Any other pause in the settle, such as SystemUI launching the FSI itself (the open 17 check), leaves no try. Back from that screen then brings Element's screen once more before the card shows. Fix: settle the device check first; if SystemUI does launch it, count such a pause (not the wake activity's) as the try.
6. **Note - the per-ring try, silence and dismiss are kept only in memory** (`PinLockRuntime.kt:407-429`). If the process restarts mid-ring, the lock wakes and rings again after Svar, Avvis or power, and resends the FSI, as in 17. Accept this, or store them with the `voip_call` record.
7. **Tests**: the pure decisions are covered. Add the hang-up case (#2), and move the Svar-silence rule (#1) and the pause bookkeeping (`voipLockLeft`) into pure helpers so they can be tested.

## Resolution (2026-10-07)
1. **Fixed.** `VoipCalls.answer` returns what it sent (`VoipAnswerSent`); only the answer action silences the ring
   (`answerSilences`). A refused start (`voipAnswerCheck`) puts the silence back as it was before Svar
   (`silenceAfterRefusedAnswer`, so a power-button silence stays).
2. **Fixed.** `pickAnswerIntent(..., incoming)` uses the action fallback only for an incoming CallStyle
   (`EXTRA_CALL_TYPE`) with a decline intent. An FSI-denied ring rings only when it is incoming (`voipRings`).
   Tests cover the hang-up case.
3. **Fixed.** The lock tracks its Nødsamtale and Foreldrekode dialogs (`EmergencyCall.confirm` returns its dialog). While one
   is open, a pending send is cancelled and none is scheduled. Closing it re-renders.
4. **Fixed.** The ring screen and Svar go through `VoipCalls.sendRing`, which checks `eligible()` again. A refusal
   re-evaluates at once. Decline isn't gated, because it only ends the ring.
5. **Fixed.** A pause of the settling lock that isn't our wake activity's counts as a try that came up by itself (wake =
   asked for less than 2 s ago and not gone). So does the wake activity being covered before it finishes itself.
   Either way, this ring never gets our send and shows the card on the next resume (`pauseIsTry`,
   `voipTryAfterPause`). The 17 device check on SystemUI's own launch stays open.
6. **Accepted** as in 17: after a process restart mid-ring, the try, the silence and Avvis are forgotten.
7. **Fixed.** `VoipCallPlanTest` now has the hang-up and non-incoming cases, the Svar silence rule and the pause
   bookkeeping. `VoipCallGuardTest` checks that the ring screen and Svar pass the calls gate in `sendRing`.
