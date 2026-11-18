# A parent's choice is never a warning - audit (2026-11-18)

The user (2026-11-18): "foreldre pin som ikke er satt - det er galt. men å velge å ikke bruke funksjon x eller y - som
vi gir valg om, det er ikke galt". A missing parent PIN is wrong; not using a feature the PWA offers is not. What follows
from such a choice is at most a quiet note at the choice, behind its (i). Attention rows and orange/red lines are for
what is wrong, broken or a step left undone.

Classes: **WRONG** (broken, unsafe or a real failure), **UNDONE** (a step left: an update, a grant, an approval),
**CHOICE** (what follows from a choice the PWA offers), **BORDERLINE** (unclear; not changed). Branch
`copy-round-113`; server only. No stored value, contract or migration changed.

## Attention rows (`server/src/attention.rs`)

| Row | nb title | Class | Done |
|---|---|---|---|
| RestrictionsPaused | Begrensninger satt på pause | WRONG (the phone is open; a PIN action on the phone, no PWA setting) | kept |
| ForeignAccess | Ukjent app kan lese foreldre-PIN-en | WRONG | kept |
| DoNotDisturb | Ikke forstyrr er på | WRONG (a silent rule's own is already left out) | kept |
| GoogleBackup | Sikkerhetskopi til Google er på | WRONG (the phone turns it off itself) | kept |
| NoParentPin | Ingen foreldre-PIN er satt | WRONG (the user's own example) | kept |
| EasyParentPin | Foreldre-PIN-en er lett å gjette | WRONG | kept |
| AccessRevoked | Tilgangen er fjernet | BORDERLINE | - |
| PolicyProblem | Telefonen får ikke brukt innstillingene | WRONG | kept |
| UnseenRestarts | Telefonen startet uten Vibb One | WRONG | kept |
| IncomingCallsNotBlocked | Innkommende anrop blokkeres ikke | CHOICE (calls not managed) | **removed**, also from Device › Time; a note behind the calls switch's (i) |
| ChildLockOff | Låsen er av på telefonen | WRONG, except its "telefonen styres ikke" case: BORDERLINE | kept |
| ChildPinChanged | Telefonens PIN er ikke barne-PIN-en | WRONG | kept |
| BlockedApps | N blokkerte apper venter | UNDONE | kept |
| RestartOnce | Start telefonen på nytt én gang | UNDONE | kept |
| PlayInstallMode | Play Butikk er åpen (installering) | BORDERLINE | - |
| PlayStoreOpenable | Play Butikk kan åpnes | BORDERLINE | - |
| NotHomeApp | Vibb One er ikke Hjem-appen | WRONG | kept |
| BootCoverNotShown | Oppstartsskjermen ble ikke vist | WRONG | kept |
| NoNotificationAccess | Varseltilgang er av | UNDONE (a grant; no PWA choice) | kept |
| NotificationsOff | Varsler slått av: ... | WRONG (switched off on the phone) | kept |
| MobileDataOff | Mobildata er av på telefonen | WRONG (Android's switch; design 114 folds it) | kept |
| AppPush* | Appvarsler kommer ikke fram ... | WRONG | kept |
| Calls: EmergencyCall, CallbackWindow, CallScreenFailed, Unconfirmed, NotEnforced, FailClosed, NotPhoneApp, AnyoneCanCall, NoRedirection, CallLogUnreadable, RulesAfterRestart, Reported, NeedsUpdate, SmsNotOurs, SmsHoldFull, OwnNumberMismatch | (device.calls.problem.*) | WRONG / UNDONE | kept |
| Calls: DialerNotAllowed | Bare nødanrop virker | BORDERLINE | - |
| Calls: MessagesHidden | Meldingsappen er skjult | BORDERLINE | - |
| Calls: SmsAppAllowed | Meldingsappen er tillatt med SMS av | CHOICE (SMS off; the app is ticked by default and hidden anyway) | **no row**; a quiet row on Calls & Contacts |
| LedgerUnreadable, LauncherWithoutRules | Skjermtidsloggen kan ikke leses; Vibb One er for gammel for tidsregler | WRONG / UNDONE | kept |
| Music: CantDeliver, Unreachable, StorytelRefused, OverLimit, OlderPhone, Failing, Spotify, SpotifyLogin, SpotifyEnding, SpotifyNoApp | (device.attention.music_*) | WRONG / UNDONE | kept |
| Music: SpotifyShared | To telefoner deler en Spotify-konto | BORDERLINE | - |

## Lines on the pages

| Where | nb text | Class | Done |
|---|---|---|---|
| Time rule / Bedtime, calls switch | (new, behind the (i)) "Anrop styres ikke på telefonen, så bare utgående anrop stoppes." / everyone's rules: "Der anrop ikke styres, stoppes bare utgående anrop." | CHOICE | **added**, only while calls aren't managed |
| Lock › Barne-PIN | Sikker modus er tillatt | CHOICE (Blokker sikker modus off; on by itself with the first Child PIN) | **removed**; the switch's (i) on Advanced already says safe mode skips the lock |
| Lock › Varsler | Ingen lås uten barne-PIN | CHOICE | **quiet note** (grey, `data-note`) |
| Lock › other notes (krasj, Androids skjermlås, får ikke lest barne-PIN-en, anrop kan ikke ringe over låsen, oppdater) | | WRONG / UNDONE | kept |
| Finn (Locate) | Posisjon er av | CHOICE (Posisjon: Av) | **quiet line**, the consequence behind its (i) |
| Soner | Posisjon er ikke låst | CHOICE (Hold posisjon på off) | **plain row** (no orange) |
| Soner | Soner trenger Jevnlig; Telefonen kan ikke følge med på sonene | BORDERLINE | - |
| Innlogging | Chrome er tillatt som vanlig app | CHOICE (Chrome ticked under Apps) | **plain row** (no orange) |
| Anrop og kontakter | Meldingsappen er tillatt med SMS av | CHOICE | **quiet row** |
| Anrop og kontakter › Nødnumre | Ikke et nødnummer - følger anropsreglene; Avansert: N nummer følger anropsreglene | BORDERLINE | - |
| Musikk | N Storytel-innslag sendes ikke (Storytel off); De N Spotify-innslagene sendes ikke (Spiller som: Av) | BORDERLINE | - |
| Avansert › App-skjermer | Play kan ikke settes på pause på denne telefonen (also while the block is on); Play Butikk er åpen til ... | BORDERLINE | - |
| Nettfilter | {browser} kan gå utenom filteret | BORDERLINE | - |
| Om telefonen | Endre systeminnstillinger: Ikke tillatt; Digital assistent: Ikke valgt; Nettverk: Utenfor tailnettet | BORDERLINE | - |
| Lås › Vær | Ingen fersk posisjon og ingen sone - låseskjermen viser ikke vær nå. | BORDERLINE | - |
| Enhetslisten, status line | a rule's lock ("Skole til 14:00") in orange | BORDERLINE | - |
| Kalender › CalDAV | Ren http på hjemmenettet: trygt bare hvis veien dit er kryptert | WRONG-ish (a security caveat) | kept |
| Hurtigvalg (quick actions) | the rule's pre-action notes ("står på pause så lenge", ...) | not a state: what a tap will do | kept |
| Mobilnett | the switch, lingering, still-on and ship lines | WRONG (design 113 already applied the rule) | kept |
| Everything else orange/red (needs-update hints, failing calendars/CalDAV/music/app push, holds waiting, wrong 2FA codes, push not reaching, server clock, plain-http QR, "replaces" on a new code, corrupt rules, wipe not ready, Wi-Fi sign-in) | | WRONG / UNDONE | kept |

## Borderline - for the user

- **Tilgangen er fjernet** (red Attention): the parent's own "Remove access", but the phone runs on old rules until set
  up again. Recommend: a quiet line on Advanced, no Attention row.
- **Låsen er av: telefonen styres ikke** (Child PIN set, phone not managed): two choices that conflict. Recommend: keep
  orange - the parent set a Child PIN and has no lock.
- **Play Butikk er åpen / kan åpnes** (Attention, qa-31-s3 M4/L4, the user's earlier call): install mode and "Blokker
  andre appers skjermer" off are choices. Recommend: install mode a quiet line with its end time; "kan åpnes" a note
  behind the block switch's (i); the "Play kan ikke settes på pause" note while the block is on shows nothing.
- **Bare nødanrop virker / Meldingsappen er skjult** (phone or SMS app unticked under Apps): can be a choice or an
  older allowlist's accident. Recommend: keep - the kid can't call or text, which is rarely meant.
- **To telefoner deler en Spotify-konto**: the PWA allows it; Spotify plays on one at a time. Recommend: a quiet line.
- **Soner trenger Jevnlig / kan ikke følge med** (design 113's own decision): zones the parent made don't work under
  the location choice. Recommend: keep.
- **Nødnummer som ikke er et nødnummer**: the parent's entry, but likely a misunderstanding. Recommend: keep.
- **Storytel / Spotify-innslag sendes ikke** (switch off / Spiller som: Av): a choice or a step left. Recommend: orange
  only while the switch is on (Storytel: no login saved); quiet under Av.
- **{browser} kan gå utenom filteret**: allowing the app is a choice, the hole isn't. Recommend: keep.
- **Om telefonen: Endre systeminnstillinger / Digital assistent / Utenfor tailnettet**: orange whether or not a chosen
  feature needs the grant. Recommend: orange only while touch sounds or a clock format / Flymodus is on; tailnet a quiet
  value.
- **Vær uten posisjon**: lock weather on with location Off. Recommend: a quiet line.
- **The status line's orange lock**: a rule in force is as it should be. Recommend: the plain tone.

## Launcher (`launcher/`, parent Settings) - listed, not changed

The parent Settings summaries carry no colour; by the same rule:

| Row | nb text | Class |
|---|---|---|
| Serveradresse (tailnet http) | Vanlig http, bare gjennom tailnettet. https://<navn>.ts.net er bedre. | CHOICE: drop the advice sentence |
| Serveradresse (refused) | Brukes ikke: adressen er ikke tillatt, ... | WRONG |
| Serversertifikat | Sjekkes ikke - serveren slår på sjekken | quiet value, fine |
| Bakgrunnstilkobling | Batterioptimalisering er på - trykk for å tillate | UNDONE |
| Foreldrekode | Ingen foreldrekode er satt for denne telefonen. | WRONG |
| Varsler | Av: ...; Alle av | phone state, quiet |
| Appvarsler | Av; Ingen app bruker det ennå | quiet values, fine |
