# Design 114b: the Child PIN as a setup step, and the call filter offered up front

Status: **built**, 2026-11-20 (branch `setup-pin-roles`), server and launcher in one commit. An addendum to design 114
(`docs/design/114-setup-status.md`), with the same table, rule, contract and flow. Two approved items of the user's
(2026-11-20):

- **A.** The Child PIN ("Barne-PIN") is a required setup step. The user: "vi krever pin uansett"; and "foreldre pin som
  ikke er satt - det er galt" holds for the Child PIN too. Design 114 §1 listed the Child PIN as a non-goal. This
  addendum lifts that.
- **B.** The setup asks for the call roles up front. The user: "hva om noen ikke vil bruke det? kanskje den ivhertfall
  skal spørre om du vil gi vibb den rettigheten som en del av oppsettet?" This came after turning on calls management
  in the PWA had sent them back into the phone's setup.

Both were checked on `main` at 4a7838494. Android 14-17: no new API. `KeyguardManager.isDeviceSecure` is API 23, and the
role requests are design 114's own. The flow's buttons and lines are the same views as 114's.

## A. `child_pin`

| field | value |
|---|---|
| key | `child_pin`, the third row of the table (after `device_owner`), so the two PINs sit together |
| need | `required` |
| read_by | `phone` |
| when | `always` |
| summary / level | `count` / `action`: in the summary's "Oppsett: N steg igjen", orange, never red |
| features | none (it stops no single choice: the whole phone is open without it) |
| phone_page | `null`: no Android page. The PIN is set from the PWA (`set_kid_pin`), so the server knows it |
| names | Barne-PIN / Child PIN |

**The reading** is a new field in the report's `setup`, `child_pin`, since no other field carries it. It is `true` when
the phone has a PIN:

- **Android's lock** (`kid_lock.keyguard` `android`, the lock every phone runs, memory "Android lock only"): reads
  `KeyguardManager.isDeviceSecure`. A PIN set by Android's setup wizard counts as done. The phone is locked, and Lock
  Screen & PINs' existing note ("Telefonens PIN er ikke barne-PIN-en", design 59) already says that this PIN isn't the
  Child PIN.
- **Vibb One's own lock** (legacy; phase D removes it): the cached policy carries a `kid_lock`. That lock steps aside
  for an Android credential and the relock removes one set in a window, so `isDeviceSecure` would read "no PIN" forever
  there. This costs one line.
- If the read fails, the reading is `null`: never counted.

**Why `required` and `count`, not `own_row`/`alarm` like `parent_pin`.** The Parent PIN has its own red Attention row
(`NoParentPin`), and the QR waits for it. The Child PIN has no row of its own. As a counted orange step it shows in the
three places 114 already has: the summary's N, Device › Oppsett and the phone's Settings row. That is the user's "kort
oransje linje".

**The PWA:**
- **Device › Oppsett.** The row is an orange link at the top of "På telefonen", like `parent_pin`'s red one. It leads
  to `/devices/{id}/lock#child-pin` and has no ⓘ inside the link (design 113 QA M7). It never makes the page offer
  "Åpne for oppsett": the phone can't do this step.
- **Lås › Barne-PIN** (`#child-pin`). While the step is missing and no Child PIN is stored, there is one orange note
  above the group: "Telefonen har ingen PIN". How to fix it is behind its ⓘ. Once a Child PIN is stored, the card's
  own notes take over (waiting, sending), so it is said once.
- The lock glance is not shown without a PIN. This is unchanged (decided 2026-11-20: the face sits over Android's PIN
  lock).

**The phone's flow.** A screen with no "Åpne". Its line says where to set the PIN, and its only button is "Senere".
The flow reads it again after every apply. The set_kid_pin command's reset, and any password change
(`onPasswordChanged`), ends in an apply, and the change now asks for a sync too. So the screen moves on by itself once
the PWA's PIN lands. It is `always`, so it can show before the first policy, as 114's other `always` rows do.

## B. `call_filter_early`: the call filter, offered before calls are managed

**What turning calls management on needs from a person today.** The Device Owner takes the dialer role
(`setDefaultDialerApplication`) and the SMS role (`setDefaultSmsApplication`) by itself, without a prompt, the moment
calls or SMS are managed. SMS needs nothing more on the phone. Calls also need Android's **call screening** and **call
redirection** roles, which no device-owner API grants. That is design 114's required `call_filter` (row 13). Once Vibb
holds the dialer, `DISALLOW_CONFIG_DEFAULT_APPS` greys their requests, so they need design 98's pick window inside a
pause. That is the trip back into the phone's setup the user found "litt tungvint". Android's dialogs say "default app
for caller ID & spam" and "for call redirecting", which reads like "the default phone app".

**The shape: one optional step, `call_filter_early`**, named as row 13 ("Samtalefilter" / "Call filter"):

| field | value |
|---|---|
| key | `call_filter_early`, right after `call_filter` |
| need | `optional`: listed quietly, never counted, never a warning (CLAUDE.md "Words in the UI": not using calls management is the parent's choice) |
| read_by | `phone`, the same reading as `call_filter`: `call_state.screening_role_held && redirection_role_held` |
| when | `calls_unmanaged` (new): `calls_mode` is `unmanaged`, the exact opposite of `calls_managed` |
| summary / level | `count` / `action` (optional, so never counted) |
| features | none |
| phone_page | `["android.app.role.action.REQUEST_ROLE"]`, the same requests in the same pick window as row 13 |
| names | Samtalefilter / Call filter |

Why this shape:
- **Not the dialer or SMS role.** The Device Owner takes them silently when management goes on, so asking up front
  saves nothing. Granted by hand while unmanaged, they would also change an unmanaged phone. Vibb's in-call screen
  would carry every call, and Vibb One would hold every SMS (Google Messages couldn't send). And a role the parent gave
  by hand is never handed back (`takenByUs` false). The screening and redirection roles are harmless while unmanaged:
  both services answer ALLOW for every call then (`decideIncoming`/`decideOutgoing`, `CallPolicyState.Unmanaged`).
- **One step, not two.** Both roles are one grant from the parent's view: two dialogs in a row, "Velg Vibb One begge
  gangene". That is row 13's own shape, and its two-request queue is reused.
- **No duplicate with row 13.** The two `when`s are exact opposites, so at most one of the two rows applies. Both read
  the same roles, so a grant made early leaves `call_filter` done the moment calls are managed. If the parent skipped
  it and turns calls on later, row 13 is required and takes over as today, with its warning line on Anrop.
- **"Calls Off" is `calls_managed`** (114 QA L5), so there the required row applies, not this one.

The cost the parent sees: a phone that never manages calls keeps a quiet "Samtalefilter" row under Device › Oppsett's
"Valgfritt", as an unenrolled fingerprint does. So "Alt er gjort" waits for it. No orange, no count.

## The contract (`setup_steps.json`, byte-identical in both dirs)

- `steps`: two new rows, `child_pin` (third) and `call_filter_early` (after `call_filter`).
- `report` and `report_no_hold`: gain `"child_pin": true` after `device_owner` (the phone's key order).
- `clean`: a row whose `child_pin` is kept and one whose non-boolean is dropped.
- `needed`, four new rows:
  - no PIN on the phone, `child_pin` counted;
  - calls unmanaged, the roles not held: only the optional row;
  - calls managed, the roles not held: `call_filter` counted, the early row gone;
  - the roles granted early: nothing left when calls are turned on.
- `when` gains `calls_unmanaged`; the inputs are unchanged (it is `!calls_managed`).

**Order of shipping.** The server ships first. An older launcher's report has no `setup.child_pin`, so the reading is
absent and the step never shows. Its `call_state` already carries the two roles, so the PWA lists the optional row
while such a launcher's flow can't show it. That is quiet, optional and harmless. A new launcher against an older
server: the server's `clean` drops `child_pin` and its table lacks both rows, so the PWA shows neither. The phone's flow
and Settings row count `child_pin` from its own table, which is the right answer there. No policy key changes, and no
migration.

## Copy (checked against "Words in the UI")

The PWA (`src/i18n/*.json`):

| key | nb | en |
|---|---|---|
| `setup.step.child_pin` | Barne-PIN | Child PIN |
| `setup.how.child_pin` | Låseskjerm og PIN-koder › Barne-PIN. | Lock Screen & PINs › Child PIN. |
| `setup.step.call_filter_early` | Samtalefilter | Call filter |
| `setup.how.call_filter_early` | Velg Vibb One begge gangene. | Choose Vibb One both times. |
| `setup.why.call_filter_early` | Trengs først når Vibb styrer samtaler. | Only needed once Vibb manages calls. |
| `device.lock.no_pin` | Telefonen har ingen PIN | The phone has no PIN |
| `device.lock.no_pin_fix` | Velg en barne-PIN under. | Choose a Child PIN below. |

The phone (`strings.xml`):

| key | nb | en |
|---|---|---|
| `setup_step_child_pin` | Barne-PIN | Child PIN |
| `setup_how_child_pin` | Sett den i foreldreappen: Låseskjerm og PIN-koder › Barne-PIN. | Set it in the parent app: Lock Screen & PINs › Child PIN. |
| `setup_step_call_filter_early` | Samtalefilter | Call filter |
| `setup_how_call_filter_early` | Trengs hvis Vibb skal styre samtaler. Velg Vibb One begge gangene. | Needed if Vibb is to manage calls. Choose Vibb One both times. |

## Left for a device

- The Child PIN's screen moving on by itself when the PWA's PIN lands, in the hold (set_kid_pin → apply → refresh), on
  Android 14 and 16.
- The two role dialogs from the early step on an unmanaged phone. While SMS is managed or the link gate is on, they
  should open inside the pick window. Afterwards, turning calls management on should leave no `call_filter` step.
- That nothing changes for calls on an unmanaged phone holding the two roles: incoming calls ring, outgoing calls go
  through, the system dialer stays the phone app.
