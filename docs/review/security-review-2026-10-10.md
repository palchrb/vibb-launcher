<!-- Imported 2026-10-10 from the user's Claude Docs page "Sikkerhetsreview av vibb-launcher" (review against 9cfb600). Status of each finding: see the security-wave notes in docs/design. -->

# Sikkerhetsreview av vibb-launcher

2026-10-10 · Pål Breyholtz

Dette er et oppdrag til en kodeagent: rett sikkerhetsfunnene under i `palchrb/vibb-launcher`, i rekkefølgen i oversikten. Reviewen er gjort mot commit `9cfb600` uten kodeendringer, så sjekk hvert funn mot gjeldende kode før du endrer det.

## Instruksjoner til agenten

- Les `CLAUDE.md`, `server/CLAUDE.md` og `launcher/CLAUDE.md` først. Jobb i `server/` og `launcher/`, aldri i de gamle klonene `kids-launcher-mdm/` eller `kid-phone-server/`.
- Ta ett funn, eller en tett gruppe, per commit, med funnets ID i meldingen (f.eks. `security H1: validate check_time`).
- Før hver push: `cargo test`, `cargo fmt --check` og `cargo clippy --all-targets` i `server/`, og `./gradlew assembleDebug assembleRelease testDebugUnitTest` i `launcher/`.
- En endring i API-et eller policy-JSON går inn i både `server/` og `launcher/` i samme commit. Oppdater `policy_json_keys_snapshot` og `PolicyResponseCompatTest`, og hold de to `phone_vectors.json` like.
- Skriv en test som feiler før fiksen der det går: Rust i `server/src/tests/`, JVM i `launcher/app/src/test/`. Ikke svekk, hopp over eller slett eksisterende tester.
- Funn merket «utledet» er ikke bevist i koden. Bekreft dem først (emulator, `docs/testing/emulator.md`), og si fra hvis de ikke holder.
- Funn under «Trenger beslutning» endres ikke uten svar fra eieren. Aksepterte risikoer skal ikke «fikses».
- Designvalg dokumenteres som før i `docs/design/`. All ny kode er GPL-3.0, og ingen ny kode går i MIT-katalogene `ui/list/` og `apps/`.
- Linjenumrene gjelder commit `9cfb600` og kan ha flyttet seg. Søk på funksjonsnavnet hvis linjen ikke stemmer.

## Oversikt

Gjør bølgene i rekkefølge. Bølge 1 bryter kjeden fra en telefons enhetsnøkkel via foreldrenes admin til root på Pi-en.

| Bølge | ID | Alvor | Del | Funn |
|---|---|---|---|---|
| 1 | H1 | Høy | Server, root-skript | `check_time` blir en kommando som kjøres som root |
| 1 | H2 | Høy | Server, web | Lagret XSS fra telefonen via `captured_at` på Finn enhet-siden |
| 2 | H3 | Høy | Launcher | Ingen sperre på foreldrekoden i leggetidsdialogen |
| 2 | M8 | Middels | Launcher | Fjernsletting bruker `wipeData`, som trolig feiler på targetSdk 36 |
| 2 | M1 | Middels | Server, innlogging | Foreldrekontoen kan låses ute for godt |
| 3 | H4 | Høy | Launcher + server | Safe mode tillatt som standard (trenger beslutning) |
| 3 | M9 | Middels | Launcher | Foreldrekoden på leggetidsskjermen åpner hele telefonen (trenger beslutning) |
| 4 | M2 | Middels | Server, backup | Backup og `data/` inneholder aktive innlogginger og hemmeligheter |
| 4 | M3 | Middels | Server, innlogging | Ingen tilbaketrekking av innlogginger, ingen ny bekreftelse for farlige handlinger |
| 4 | M4 | Middels | Server, root-skript | Tjenestebrukeren kan bli root via `data/` (restore) |
| 5 | M10 | Middels | Server + launcher | Katalog-APK-er installeres uten låst pakkenavn og signatur |
| 5 | M11 | Middels | Launcher | Ukryptert HTTP tillatt og fallback utenom Tailscale |
| 5 | M12 | Middels | Server + launcher | Samme gjenbrukbare Tailscale-nøkkel i alle QR-koder; stille ny registrering |
| 6 | M5 | Middels | Server, oppdatering | Oppdateringer sjekkes for feil, men er ikke signert |
| 6 | M6 | Middels | Server, DNS | DNS-blokklisten svikter åpent |
| 6 | M7 | Middels | Server | Ingen grenser på inndata fra telefon og `/login` |
| 6 | L1–L13 | Lav | Begge | Se «Lav og info» |

## Høy

### H1. `check_time` blir en kommando som kjøres som root

- Bevis (verifisert): `save_schedule` i `server/src/handlers/system_maintenance.rs:335-360` godtar `check_time` med bare `trim()`. `to_file_contents` (`:150-161`) skriver `CHECK_TIME=` til `data/schedule.conf`. Root-skriptet `scheduler.sh`, som `server/deploy/install.sh:537-580` skriver, kjører `source "$CONFIG_FILE"` hvert 10. minutt, før noen tidssjekk.
- Angrep: `POST /system/schedule` med `check_time=03:00$(…)` eller et linjeskift fulgt av en kommando. Alt som gir admin-innlogging (H2, M2, M3) gir dermed root på Pi-en.
- Fiks:
- Godta bare `^([01]\d|2[0-3]):[0-5]\d$` i `save_schedule`, og svar 400 ellers. Gjør det samme i `save_app_update_schedule` (`server/src/handlers/system_update.rs:~316`) og for `BACKUP_TIME` i `server/src/handlers/backups.rs`.
- I `scheduler.sh`: fjern `source`. Les hver nøkkel med `grep`/`cut` slik `backup_sync.sh` gjør, og sjekk hver verdi med regex i bash før bruk. Ugyldig verdi gir standardverdien og en loggføring.
- `update.sh` skriver ikke root-skriptene på nytt, så eksisterende Pi-er beskyttes bare av steg 1 til `install.sh` kjøres igjen. Vurder at `update.sh` også oppdaterer `scheduler.sh`.

- Ferdig når: en Rust-test viser at `03:00$(x)` og `03:00\nx` avvises, og ingen root-skript gjør `source` på noe i `data/`.

### H2. Lagret XSS fra telefonen via `captured_at`

- Bevis (verifisert): statusrapporten lagrer `location.captured_at` urørt (`server/src/handlers/device_api.rs:829-843`, feltet er en fri `String` i `server/src/models.rs:727`). `locations_json` (`server/src/handlers/locate.rs:188-201`) sorterer på den. `server/templates/device_locate.html:164` gjør `bindPopup('Last seen ' + last.captured_at)`, og Leaflet setter strenger med `innerHTML`. Det finnes ingen CSP.
- Angrep: en enhetsnøkkel poster `"captured_at":"z"`. Verdien sorteres sist, åpnes alltid og slettes aldri av `retention.rs`. Når forelderen åpner Finn enhet, kjører scriptet med full admin.
- Fiks:
- Telefonen sender `Instant.toString()` (`launcher/.../server/MdmSyncWorker.kt:373`), altså RFC 3339. Parse med `chrono::DateTime::parse_from_rfc3339` ved inntak. Er tidspunktet ugyldig, droppes posisjonen (resten av rapporten lagres) og det logges. Begrens breddegrad til ±90, lengdegrad til ±180 og nøyaktighet til ≥ 0.
- I malen: lag boblen som en DOM-node med `textContent`, aldri som HTML-streng.
- `var shown = "{{ last_fix_at }}"` (`device_locate.html:173`): JSON-kod verdien i stedet for HTML-escaping.
- Rydd opp i en migrasjon: slett rader i `device_locations` der `captured_at` ikke er et gyldig tidspunkt.
- CSP kommer i L4, ikke nødvendig her.

- Ferdig når: en test viser at en statusrapport med HTML i `captured_at` ikke lagrer posisjonen, og malen setter ikke lenger sammen strenger til `bindPopup`.

### H3. Ingen sperre på foreldrekoden i leggetidsdialogen

- Bevis (verifisert): `launcher/app/src/main/java/com/kidslauncher/mdm/ui/LockActivity.kt:87` sjekker `isLockedOut()` bare når dialogen åpnes, og OK-knappen (`:104-121`) holder dialogen åpen etter feil kode. `OfflineOverride.verifyPin` (`launcher/.../server/OfflineOverride.kt:77-97`) sjekker aldri sperren. Ved 5. feil settes sperretid og telleren nullstilles, men riktig kode godtas fortsatt.
- Angrep: barnet holder dialogen åpen og prøver i det uendelige, for hånd eller med et USB-tastatur via OTG. Foreldrekoden gir 2 timer uten begrensninger, innstillingene, pause og Play-installasjon.
- Fiks (sentralt i `verifyPin`, så alle kallere dekkes):
- Returner et resultat med tre utfall (riktig, feil, sperret). Ingen hashing mens sperren er aktiv.
- Tell forsøket før PBKDF2 og lagre med `commit()`, så krasj eller tvungen stopp midt i ikke nullstiller.
- Mål sperretiden med elapsed realtime og boot count, som `launcher/.../lock/PinBackoff.kt` (`beginAttempt`, `backoffWindow`), ikke veggklokke.
- Gi økende ventetid (f.eks. 15 min, 1 t, 4 t), og rapporter mislykkede forsøk i statusrapporten.
- `LockActivity` lukker dialogen og viser sperremeldingen når sperren slår inn. Sjekk `PinLockActivity`, `SettingsActivity` og `SettingsFragmentLauncher` mot det nye resultatet.

- Ferdig når: en JVM-test viser at riktig kode etter 5 feil gir «sperret», og at sperren overlever at veggklokka endres.

### H4. Safe mode tillatt som standard (trenger beslutning)

- Bevis (verifisert): `SAFE_BOOT(false)` i `launcher/.../server/Hardening.kt:27`, og kolonnen har `DEFAULT 0` (`server/migrations/0023_hardening.sql:16`). Serveren slår den bare på med første barne-PIN (`server/src/handlers/devices.rs:1835-1837`). `docs/design/04-hardening.md` holder den av «til enhetssjekk 6 er bestått på Jelly Star».
- Konsekvens: i safe mode kjører ikke launcheren. Da forsvinner filteret på anrop, DNS-filteret og tidsreglene, og systemets telefonapp ringer og tar imot fra hvem som helst. `EnforcementPlan.kt:165` løfter `DISALLOW_OUTGOING_CALLS` når launcheren har telefonrollen, og `:150` sperrer aldri systemets telefonapp.
- Fiks (etter ja fra eieren og enhetssjekk 6):
- Standard på i launcheren og serveren for telefoner som styrer apper eller anrop, med en migrasjon for eksisterende enheter.
- Rapporter oppstarter launcheren ikke så (hopp i `Settings.Global.BOOT_COUNT`), og vis en advarsel på enhetssiden.

- Ferdig når: tester viser at standardpolicyen gir `DISALLOW_SAFE_BOOT`, og at et hopp i boot count gir et varsel.

## Middels, server

### M1. Foreldrekontoen kan låses ute for godt

- Bevis (verifisert): `record_failed_login` (`server/src/security.rs:172-200`) teller opp, og bare `reset_failed_login` (`:202`), som kjøres ved vellykket innlogging, nullstiller. Når 15-minutterssperren går ut, står telleren fortsatt på 5, så ett feilforsøk låser igjen. Mens kontoen er låst, avvises også riktig passord (`server/src/handlers/auth.rs:73-93`).
- Angrep: ett feilforsøk hvert 14. minutt fra en hvilken som helst maskin på tailnettet. Brukernavnet er `admin` som standard.
- Fiks: nullstill telleren når sperren går ut, eller tell i et glidende vindu. Helst sperre per (konto, IP), så forelderens egen IP ikke rammes av andres forsøk.
- Ferdig når: en test viser at riktig passord godtas etter at sperren har gått ut, selv om noen har prøvd feil igjen.

### M2. Backup og `data/` inneholder aktive innlogginger og hemmeligheter

- Bevis (verifisert): sesjonslageret bruker samme database (`SqliteStore::new(db.clone())`, `server/src/main.rs:99`). `VACUUM INTO` kopierer alt inn i backup-zip og USB-speil (`server/src/handlers/backups.rs:253` og `:581`), også `tower_sessions` (cookie-verdiene), TOTP-hemmeligheten i klartekst og Tailscale-nøkkelen. Tjenesten har ingen `UMask`, og `data/` får ingen `chmod` (`server/deploy/install.sh:207`, unit-filen rundt `:257-270`).
- Angrep: den som får tak i en backup eller USB-disken, setter cookien `id=` og er admin i opptil 30 dager uten passord eller TOTP. En gjenopprettet backup bringer også tilbake utloggede innlogginger.
- Fiks:
- Flytt sesjonene til en egen databasefil, eller slett `tower_sessions` i kopien før den pakkes.
- `UMask=0077` i unit-filen og `chmod 700` på `data/` og backup-katalogene. Eksisterende installasjoner må få rettighetene rettet ved oppdatering.
- Vurder å kryptere den eksterne kopien.

- Ferdig når: en test viser at en ny backup ikke har rader i `tower_sessions`.

### M3. Ingen tilbaketrekking av innlogginger, ingen ny bekreftelse for farlige handlinger

- Bevis (verifisert): `reset_totp` (`server/src/handlers/auth.rs:537-551`) krever verken passord eller TOTP. Bytte av passord eller 2FA logger ikke ut andre innlogginger. Sesjonen varer 30 dager etter siste bruk uten øvre grense (`server/src/main.rs:122`). Sletting av telefonen, sletting av enhet, PIN, hardening, backup og gjenoppretting, APK-opplasting og systemoppdatering krever bare en innlogging. `PLAN.md` (rundt `:377-380`) planlegger dette, men det er ikke gjort.
- Angrep: en stjålen innlogging nullstiller 2FA, registrerer sin egen autentiseringsapp og stenger forelderen ute. Passordbytte kaster ikke angriperen ut.
- Fiks:
- En `session_epoch` per admin som sjekkes i `require_full_auth` og økes ved bytte av passord eller 2FA.
- Krev passord og TOTP for 2FA-reset.
- Krev fersk TOTP (under ca. 5 min) for de farlige handlingene over.
- Absolutt levetid på sesjoner, og en «logg ut alle»-knapp.

- Ferdig når: tester viser at en gammel sesjon avvises etter passordbytte, og at 2FA-reset og sletting uten fersk TOTP avvises.

### M4. Tjenestebrukeren kan bli root via `data/`

- Bevis (verifisert): gjenoppretting i root-skriptet `actions.sh` kjører `unzip -q -o` på hele zip-filen (`server/deploy/install.sh:462`) og deretter `cp "$restore_tmp/kidphone.db" "$DATA_DIR/kidphone.db"` (`:485`). `cp` skriver gjennom en symlenke på målet, og `data/` eies av `kidphone`. Dette bryter grensen beskrevet i `install.sh:286-290`.
- Angrep: med kodekjøring som `kidphone` peker man `data/kidphone.db` mot `/opt/kid-phone-server-updater/actions.sh` og ber om en gjenoppretting av en zip der `kidphone.db` er et skript. En zip-bombe eller symlenker i zip-filen er andre veier.
- Fiks:
- Root skal aldri lese inn, kjøre eller skrive gjennom noe i `data/` uten sjekk. Bruk `cp --remove-destination` eller `install -m 600`, og avvis hvis målet er en symlenke.
- Pakk bare ut `kidphone.db` med `unzip -p "$backup_path" kidphone.db > fil`, med en grense for størrelse, og avvis symlenker.

- Ferdig når: `shellcheck` er ren, og en manuell test med en symlenke i `data/` gjør at gjenopprettingen avbrytes.

### M5. Serveroppdateringer er ikke signert

- Bevis (verifisert): tarball og `.sha256` hentes fra samme release (`server/deploy/install.sh:163-178`, tilsvarende i `update.sh`). Root henter `update.sh` fra en tag som kan flyttes (`install.sh:340-345`). `.github/workflows/server-release.yml` publiserer uten et beskyttet miljø. Automatisk installasjon finnes (`server/src/handlers/system_update.rs:355`).
- Angrep: den som kan skrive til repoet eller bytte en release-fil, kjører kode som root på hver Pi innen et døgn.
- Fiks: signer tarball og `update.sh` med minisign eller cosign, og lås den offentlige nøkkelen i root-eide skript. Slå på immutable releases, og publiser fra et GitHub-miljø med godkjenning.
- Ferdig når: `update.sh` og `actions.sh` avviser en tarball uten gyldig signatur.

### M6. DNS-blokklisten svikter åpent

- Bevis (verifisert): i `compile_blocklist` (`server/src/dns_engine.rs:110-146`) blir en kilde som feiler, til en tom liste. HTTP-status sjekkes ikke, så en feilside leses som domener. Vernet «behold forrige liste» gjelder bare når alle kilder er tomme. Ved oppstart uten internett sendes en tom liste. `dns_blocklist` i `server/src/handlers/device_api.rs:~430-454` gjør databasefeil til en redusert liste med `unwrap_or_default()`. Telefonen bytter ut hele lista (`DnsFilterEngine.kt`).
- Angrep: barnet restarter ruteren og Pi-en samtidig, og filteret er borte til neste vellykkede henting.
- Fiks: behold forrige domener per kilde ved feil, bruk `error_for_status()` og en grense for størrelse, svar 503 til første vellykkede innlasting, og 5xx ved databasefeil.
- Ferdig når: tester viser at én kilde som feiler, beholder sine forrige domener, og at blokklisten svarer 503 før første innlasting.

### M7. Ingen grenser på inndata fra telefonen og `/login`

- Bevis: ingen begrensning på antall forespørsler, og Axums standardgrense på 2 MB. Statusrapporten lagrer `installed_apps_json` uten tak, og DNS-hendelser, krasjrapporter og SSE-tilkoblinger kan vokse fritt (`server/src/handlers/device_api.rs`). `security_events` ryddes aldri, og `/security` viser bare de siste 200 (`server/src/handlers/admin.rs:22-28`), så rundt ti forespørsler kan skjule innloggingsforsøk. `/login` logger brukernavnet urørt, opptil 2 MB, uten innlogging (`server/src/handlers/auth.rs:~105-112`).
- Fiks: begrens forespørsler per enhet og globalt på `/login`. Sett tak på antall apper og lengden på feltene, og kutt brukernavnet til ca. 64 tegn. Rydd og del sikkerhetsloggen i sider, og tillat maks ca. 2 SSE-tilkoblinger per enhet.
- Ferdig når: tester viser at for lange felt kuttes eller avvises, og at sikkerhetsloggen ryddes.

## Middels, launcher og app-katalog

`L/` står for `launcher/app/src/main/java/com/kidslauncher/mdm/`.

### M8. Fjernsletting virker trolig ikke (utledet)

- Bevis: `LocateCommands.wipe` (`L/server/LocateCommands.kt:391-397`) kaller `dpm.wipeData(0)`, og appen har `targetSdk = 36` (`launcher/app/build.gradle.kts:97`). Fra Android 14 kaster `wipeData()` på hovedbrukeren `IllegalStateException` for apper som targeter nyere SDK, og `wipeDevice()` er erstatningen. Feilen blir bare logget, og serveren regner kommandoen som levert når den hentes (`server/src/handlers/device_api.rs:~200-206`).
- Konsekvens: forelderen tror en mistet telefon er slettet, men den beholder alle data.
- Fiks: bekreft først på emulator (API 34+, device owner) at `wipeData(0)` kaster. Bytt så til `dpm.wipeDevice(0)`, og vurder `WIPE_RESET_PROTECTION_DATA` opp mot `docs/design/qa-backup.md`. Rapporter mottatt sletting før kallet, og en feil hvis kallet kaster.
- Ferdig når: sletting er testet på emulator og lagt inn i sjekklisten i `docs/testing/emulator.md`.

### M9. Foreldrekoden på leggetidsskjermen åpner hele telefonen (trenger beslutning)

- Bevis (verifisert): under override er `appsManaged = false` (`L/server/EnforcementPlan.kt:152`), så ingenting er sperret og kiosk er av. VPN-filteret slås av (`L/server/AppEnforcer.kt:416`), og installasjon utenfra tillates (`:421`). `DISALLOW_CONFIG_WIFI`, `_CONFIG_CREDENTIALS` og `_CONFIG_TETHERING` settes ikke, og verken `setPermittedAccessibilityServices` eller `setPermittedInputMethods` brukes. Overriden avsluttes bare av en vellykket synk, og pausen kan ikke avsluttes fra serveren. `AppEnforcer.kt:~997` kaller dette en bevisst regel.
- Angrep: forelderen gir «30 minutter til» med koden. Barnet slår på flymodus, og setter i Innstillinger en Wi-Fi-proxy, et eget CA-sertifikat, en tilgjengelighetstjeneste eller hotspot. Alt blir værende etter at tiden er ute.
- Forslag (etter ja fra eieren):
- Koden på leggetidsskjermen opphever bare tidslåsen. Tillatt-lista, kiosk og VPN beholdes.
- Full åpning, med Innstillinger, krever en egen vedlikeholdsmodus.
- Legg restriksjonene over og lister over tillatte tilgjengelighetstjenester og tastaturer i hardeningen som aldri løftes.
- En serverkommando som avslutter en pause eller override.

- Ferdig når: tester i `EnforcementPlanTest` viser hva koden opphever, og at Innstillinger forblir sperret.

### M10. Katalog-APK-er installeres uten låst pakkenavn og signatur

- Bevis (verifisert): serveren sjekker bare zip-header og størrelse (`check_apk_download`, `server/src/handlers/tracked_apps.rs:52-62`), og SHA-256 er av serverens egen nedlasting. Launcheren installerer andre apper enn seg selv uten forventet pakkenavn eller sertifikat (`L/server/AppDownloads.kt:~665-697`, `L/server/AppInstaller.kt:~66-75` uten `setAppPackageName`). Når pakkenavnet til en katalog-app mangler, tar serveren den ene nye pakken telefonen rapporterer, lagrer den for hele katalogen og tillater den (`server/src/handlers/device_api.rs:~770-822`). Første rapport fra en ny telefon tillater alt som er installert (`:~729-753`, bare når `allowlist_json IS NULL`).
- Angrep: en kompromittert eller falsk release-kilde får en vilkårlig APK installert stille av device owner. En falsk telefon kan rapportere `com.android.settings` som ny og få Innstillinger tillatt, også på søskens telefon senere.
- Fiks:
- Lås pakkenavn og SHA-256 av signeringssertifikatet per katalog-app (lært ved første installasjon eller lagt inn av admin), send dem i policyen, og sjekk på telefonen med `getPackageArchiveInfo` og `setAppPackageName` før installasjon. Gjenbruk `signerMatches` fra selvoppdateringen. Dette er en API-endring: begge kataloger i samme commit.
- Krev at launcheren selv installerte pakken (`installer`) før pakkenavnet gjettes.
- Start en ny telefon med en trygg standardliste som aldri inneholder Innstillinger, nettlesere eller filbehandlere, og vis den til forelderen.

- Ferdig når: tester på begge sider viser at en APK med feil pakkenavn eller signatur avvises, og at gjetting uten `installer == launcher` ikke skjer.

### M11. Ukryptert HTTP tillatt og fallback utenom Tailscale

- Bevis (verifisert): `android:usesCleartextTraffic="true"` (`launcher/app/src/main/AndroidManifest.xml:96`), og ingen network security config. `createMdmApi` (`L/server/MdmApi.kt:~99-116`) og SSE-klienten (`L/server/CommandListenerService.kt:100-104`) bruker vanlig nett når Tailscale-proxyen ikke er oppe, for eksempel etter krasjvakten i `L/server/TsnetClient.kt:169-179` eller ved oppstart. Verken telefonen (`L/server/Provisioning.kt:28`) eller serveren (`server/src/handlers/settings.rs:~66-89`) sjekker at adressen er https.
- Angrep: med en `http://`-adresse kan noen på samme nett lese enhetsnøkkelen og sende en policy som låser opp alt og installerer en APK (se M10). Med den dokumenterte `https://…ts.net` svikter det trygt.
- Fiks: krev https i serverens innstillinger og ved provisjonering, eller tillat http bare til 100.64.0.0/10 gjennom proxyen. Sett `usesCleartextTraffic=false` med en network security config. Når en Tailscale-nøkkel er satt opp, send aldri uten proxyen: svikt og prøv igjen senere.
- Ferdig når: tester viser at `http://`-adresser avvises, og at klienten ikke bygges uten proxy når Tailscale er konfigurert.

### M12. Samme gjenbrukbare Tailscale-nøkkel i alle QR-koder, og stille ny registrering

- Bevis (verifisert): innstillingssiden ber om en gjenbrukbar nøkkel som legges i hver QR-kode (`server/templates/provisioning_settings.html:22`, `server/src/handlers/provisioning.rs:~155`). Telefonen lagrer den for godt (`L/server/Provisioning.kt:29-31`). Admin og enhets-API deler port. `enroll` (`server/src/handlers/device_api.rs:22-62`) sjekker ikke om telefonen alt er registrert, overskriver nøkkelen uten logg, og oppdateringen er ikke betinget på koden. `GET /devices/{id}/provision` lager en ny kode ved hver visning (`provisioning.rs:116-130`).
- Angrep: et bilde av QR-koden gir tilgang til tailnettet (admin-innloggingen, M1) og en kode som tar over telefonens identitet, som igjen åpner for H2.
- Fiks:
- Dokumenter og anbefal merkede, forhåndsgodkjente engangsnøkler med en ACL som bare gir `tag:kid` tilgang til enhets-API-et (i `server/DEPLOY.md`), og kjør enhets-API-et på egen port.
- Slett nøkkelen på telefonen etter første oppkobling. `connectFromPreferences` (`TsnetClient.kt:178-179`) må da koble til fra lagret tilstand uten nøkkel.
- Gjør registrering til én betinget `UPDATE … WHERE enrollment_code = ? AND expires > now RETURNING id`. Krev en eksplisitt «registrer på nytt» for en telefon som er registrert, logg det i sikkerhetsloggen, og lag ikke koder på GET.

- Ferdig når: tester viser at en registrert telefon ikke kan overtas uten «registrer på nytt», og at registreringen logges.

## Lav og info

Ta disse etter bølge 5, eller sammen med et større funn som berører samme fil.

| ID | Funn | Hvor | Fiks |
|---|---|---|---|
| L1 | X-Forwarded-For stoles på (første verdi), og «Unban»-knappen bygger en sti av IP-en. Bak `tailscale serve` kan headeren ikke forfalskes, men direkte tilgang gir omgåelse av IP-sperren og en knapp som poster til f.eks. `/devices/1/delete`. | `server/src/security.rs:77-85`, `server/templates/security.html:39` | Bruk peer-adressen, og XFF bare fra 127.0.0.1. Parse til `IpAddr` før lagring, og send IP-en i et skjult felt. |
| L2 | TOTP-kode kan brukes igjen i 90 s, og det finnes ingen gjenopprettingskoder. Feiltelleren nullstilles av riktig passord før TOTP-steget. | `server/src/handlers/auth.rs:~127`, `:~242` | Lagre siste godtatte tidssteg og avvis eldre. Nullstill telleren først etter godkjent TOTP. |
| L3 | Ingen ny sesjons-ID ved innlogging. Vanskelig å utnytte. | `server/src/handlers/auth.rs:~133`, `:~277` | `session.cycle_id()` etter passord og etter TOTP. |
| L4 | Ingen CSP eller andre sikkerhetsheadere. CSRF hviler på SameSite=Strict, men alle `*.ts.net`-verter regnes som samme nettsted, og cookien heter bare `id`. | `server/src/main.rs` (session layer) | CSP `script-src 'self'` (flytt inline-script og `onchange` til statiske filer), `frame-ancestors 'none'`, `nosniff`, `Referrer-Policy`, og et eget cookie-navn. |
| L5 | Regelnavn settes inn i JavaScript i et `onsubmit`-attributt. Bare admin eller en gjenopprettet backup kan sette navnet. | `server/templates/schedules.html:53` | Legg navnet i et `data-`-attributt og les det fra JS. |
| L6 | PIN-hashene for foreldre og barn sendes i policyen og lagres i klartekst. En 6-sifret kode knekkes på minutter hvis hashen kommer på avveie. | `L/server/dto/PolicyResponse.kt`, `L/server/MdmSyncWorker.kt:~130`, `L/lock/PinLockStore.kt` | Lagre en Keystore-HMAC av hashen i stedet. Vurder lengre foreldrekode. |
| L7 | Innkommende anrop filtreres ikke hvis launcheren ikke har telefonrollen. | `L/server/EnforcementPlan.kt:165` | Hold også `ROLE_CALL_SCREENING`, og varsle på serveren når anrop styres uten rolle. |
| L8 | `Authorization`-headeren legges på alle forespørsler, også absolutte URL-er. | `L/server/MdmApi.kt:~110-119` | Legg den bare på når verten er serverens. |
| L9 | Byggekjeden: ingen sjekksum for Gradle-wrapperen og ingen verifisering av avhengigheter. Rust (`stable`) og `cross` er ikke låst. | `launcher/gradle/wrapper/gradle-wrapper.properties`, `.github/workflows/server-release.yml` | `distributionSha256Sum`, wrapper-validering, `verification-metadata.xml`, låste versjoner. |
| L10 | Installasjonsrådet peker på `master` (repoet har bare `main`) og bruker `curl \| sudo bash`. DEPLOY.md nevner `http://:3100`. `.env.example` har et standardpassord. `*.jks`/`*.keystore` er kommentert ut i `.gitignore`, og `*.p12` mangler. | `server/src/security.rs:~331`, `server/DEPLOY.md`, `launcher/.gitignore` | Rett til `main` og en låst tag. Fjern http-rådet. Legg nøkkelfilene i `.gitignore`. |
| L11 | Argon2 kjører på async-trådene, så mange innlogginger kan stoppe Pi-en. Ukjent brukernavn hopper over Argon2 (tidsforskjell). | `server/src/security.rs:30-45`, `server/src/handlers/auth.rs:~94-97` | `spawn_blocking`, og verifiser mot en dummy-hash for ukjente brukere. |
| L12 | Kommandoer regnes som levert når de hentes, så lås eller sletting kan gå tapt hvis svaret ikke kommer fram. Kommentaren i koden godtar dette. | `server/src/handlers/device_api.rs:~197-206`, `L/server/MdmSyncWorker.kt:~97-115` | Eksplisitt kvittering fra telefonen. Minst: vis «levert, ikke bekreftet» på enhetssiden. |
| L13 | En lokalt bygget release uten `tsnet.aar` får stubben og bruker vanlig nett. CI har `-PrequireTsnet=true`. | `launcher/app/build.gradle.kts:~56-62` | La release-bygg feile uten `tsnet.aar`, uansett flagg. |

## Trenger beslutning eller test på enhet

Spør eieren før disse endres:

- H4: safe mode på som standard. Venter på enhetssjekk 6 på Jelly Star (`docs/design/04-hardening.md`).
- M9: hva foreldrekoden på leggetidsskjermen skal oppheve.
- M12 steg 1: engangsnøkler og ACL settes i Tailscale-konsollen, som bare eieren har tilgang til.

Test på emulator eller telefon:

- [ ] M8: sletting på API 34+ med launcheren som device owner.
- [ ] H4: safe mode på Jelly Star (sjekk 6).
- [ ] H3: USB-tastatur via OTG mot PIN-dialogene.
- [ ] Spesialkoder fra systemets telefonapp i kiosk (`*#06#`, `*#*#4636#*#*`, `*21*…#`), sjekkliste 12 i `docs/design/09-sms-and-fixes.md`.
- [ ] I kiosk: hurtiginnstillinger, assistenten (langt trykk på av/på), varselet om innlogging på Wi-Fi (captive portal), volumknapp-snarveien for tilgjengelighet og tannhjulet i Gboard.

### Aksepterte risikoer: ikke endre uten beslutning

- Spesialkoder (MMI/USSD) fra systemets telefonapp går utenom Telecom (`docs/design/09-sms-and-fixes.md:227`).
- Anrop i tillatte meldingsapper (Element X og andre self-managed) filtreres ikke (`launcher/CLAUDE.md:57`, design 17).
- SMS på betyr SMS fra alle (design 09, del A er utsatt).
- I 60 minutter etter et nødanrop kan hvem som helst ringe inn.
- Bare `VERIFICATION_FAILED` avvises, så forfalsket nummervisning av en godkjent kontakt slipper gjennom.
- DNS-filteret ser bare IPv4 UDP/53 og har ikke lockdown, så apper med egen DoH går utenom.
- Recents-leverandøren sperres aldri (`docs/design/16d-recents.md`).
- Play Store kan ikke sperres på telefoner med Google-tjenester. Kiosk og app-blokkering holder den inne.

## Allerede rettet: ikke gjør på nytt

Disse punktene fra `docs/review/qa-security.md` (2026-10-04) er løst i `9cfb600`:

- Hardening: sperre for fabrikkreset, nye brukere, debugging, USB-filoverføring, VPN-oppsett og kontoer er på som standard. Dato og tid låses med automatisk tid. Override løfter ikke hardening eller anrop.
- Svikter trygt: tom tillatt-liste, ødelagt cache og databasefeil på serveren gir ingen tilgang. PIN-hashen overskrives bare av en godkjent policy.
- Leggetid: håndheves med sperring av apper, og kiosk låser telefonen til launcheren.
- Launcher-innstillingene: krever alltid PIN. Pausen varer maks 2 timer, måles med to klokker og rapporteres.
- Release og provisjonering: release-APK kan ikke debugges, QR-koden peker på egen release med sertifikatsjekk, og backup er slått av.
- Forsyningskjeden: tsnet og gomobile er låst og verifisert. GitHub Actions er låst til commit med minimale rettigheter, og signeringen skjer i et eget miljø.
- Selvoppdatering: launcheren sjekker pakkenavn, signatur, versjon og hash. Serveroppdatering henter fra eget repo og nekter nedgradering.
- Server: ingen telefon kan nå en annen telefons data, all SQL bruker parametere, og ingen hemmeligheter er committet.
- Anrop: numre matches nøyaktig, skjulte numre blokkeres, og spesialkoder matcher aldri en kontakt.

## Status and decisions (handy, 2026-10-10)

The review was made against 9cfb600 (2026-10-07). On main since then:

| ID | Status |
|---|---|
| H3 | Fixed by design 24 (`docs/design/24-parent-pin-hardening.md`, branch `pin-24`): one gate with an escalating lockout for all five entries; the OTG keyboard check is in its §8. |
| M1, M3 (part), L1, L2, L3, L4 (phase 1), L11 | Design 22 S2-S4 (in progress). The step-up was declined by the user; `session_epoch` and "sign out everywhere" are in. |
| M7 | Design 22 S2 (limits, SSE cap) and S4 (security-log pruning). Check the field caps on `installed_apps_json` etc. |
| M11 | Design 22 launcher steps L1/L3 (planned). |
| M12 | Design 22 S1 (separate device port) and S3 (hashed, atomic enrollment, revoke). The Tailscale-key part is open. |
| L10 | `master` -> `main` fixed (72240eb3); the rest is open. |

User decisions:
- **H4 (safe mode):** yes. `DISALLOW_SAFE_BOOT` on by default after device check 6 on the Jelly Star (design 04).
- **M9 (the parent code on the time-lock screen):** keep the full opening for a limited window. But:
  - the phone must lock down again by itself when the window ends, even without a sync;
  - the parent gets a clear "Lock again" control to end the window early, instead of leaving the phone open for the
    whole period;
  - the PWA can end a pause or override too.
  - What the kid could change during the window (proxy, CA certificate, accessibility service, input method, hotspot)
    must not survive the relock: reset or report it. Design 24b.
- **M12 step 1 (one-time tagged Tailscale keys + ACL):** the user does this in the Tailscale console. We document it,
  and the launcher deletes the key after the first connect.

Plan:
- **Server security wave:** by design 22's implementer after S4 + D1. H1 and H2 first, then M2, M4, M5, M6, M10
  (server part), M7 leftovers, L5, L9 (server), L10 and L12 (server part).
- **Launcher:** M8, M10 (phone part), L7, L8, L12 (phone part) and L13 after `pin-24` and music step 3 merge.
- **Design 24b:** M9 and L6, by the design 24 implementer.

## Server security wave status (2026-10-11)

Done on `main` by design 22's implementer, each finding checked against the code of the day first, with the tests the
review asks for. Server 0.24.0 (not tagged).

| ID | Commit | What was done |
|---|---|---|
| H1 | `06cd48d7` | `scheduler.sh` reads `schedule.conf` key by key with checked values (never `source`); every schedule form takes `HH:MM` only; `update.sh` rewrites all four root scripts on every update. |
| H2 | `7e9fe55e` | A location is stored only when its time is RFC 3339 and its numbers are on Earth (normalised); the locate page uses `textContent` and `js_string`; migration 0054 removed bad rows. |
| M4 | `f952f67b` | The root restore reads only the zip's `kidphone.db` (`unzip -p`, 1 GiB, SQLite header), keeps the old one in root-owned `/var/backups/...-prerestore/`, and renames into place; the watcher and backup sync ignore symlinks in `data/`. |
| M2 | `cd5e8216` | Every copy of the database (backup zip, live mirror, pre-migrate) has no `tower_sessions` rows (`secure_delete`); a restore signs everyone out; migration 0061 ended the sessions in older backups. umask 077 in the process, `UMask=0077` in the unit (a drop-in on update), `data/` 700 and its files 600 (fixed on update, as the service user), the drive's copies root-only. The 2FA secret stays unsealed (design 22 §3.2.8). The external copy is not encrypted: DEPLOY says to keep the drive at home or use an encrypted one. |
| M6 | `2481de32` | Per feed: `error_for_status`, a 32 MB cap and "no domains" all keep the previous domains; 503 to a phone while a feed it uses hasn't loaded since the start; 500 on DB errors; the DNS page shows each feed's state; retry after 5 min. |
| M10 (server) | `9325eadf` | Package and signing-cert SHA-256 read from the APK (`src/apk.rs`), pinned from the first release (migration 0062), a different release refused, sent in `TrackedAppUpdate` (launcher DTO and compat test in the same commit). No package guess unless `installer` is the launcher. A new phone starts with only a clock, calculator, camera and Vibb Music. |
| M5 | `5cbf69c7`, `0475158d` | minisign: `install.sh`, `update.sh` and `actions.sh` pin the public key and refuse a tarball or `update.sh` without a good signature whose trusted comment is "file tag"; `actions.sh` takes `update.sh` from the signed release assets; the release job signs with the `SERVER_MINISIGN_KEY` secret and refuses to publish without it. |
| M7 (rest) | `e4f942f6` | Status reports: at most 1500 apps, real package names, labels 100 chars, `lock_reason`/`app_version` clipped. `/security` pages back (`?before=`). (The `/login` username cap and body limit: QA 22 Low 1.) |
| L5 | `952b4ed1` | The rule name goes into `data-name`; the confirm reads `this.dataset.name`. |
| L9 (server) | `ff6692bd` | Rust 1.99.0 and cross 0.2.5 pinned in `server-ci.yml` and `server-release.yml`, kept by a test. |
| L10 (rest) | `41849523` | `main` instead of `master` in the re-install hint and README; no curl piped into `sudo bash` anywhere (download, read, run); keystores, `*.p12`, `*.pk8` and `*.key` in the root `.gitignore`. |
| L12 (server) | `908757f0` | A fetched command reads "not confirmed yet", after 10 minutes "never confirmed - send it again"; a wipe can't confirm. |

Needs the repo owner (M5), before tagging server 0.24.0:
- Create the minisign key pair, add the `SERVER_MINISIGN_KEY` secret and pin the public key in `server/deploy/install.sh`
  and `update.sh` (server/DEPLOY.md, "Release signing", has the exact steps). Until then the scripts refuse every
  release and the release job refuses to publish.
- Optional: immutable releases, and the secret in an environment with required reviewers.

Left for the launcher wave: M10's phone side (`getPackageArchiveInfo` against `package_name` and `signer_sha256`,
`setAppPackageName`, `signerMatches`), L9's Gradle side, L12's explicit receipt; and deleting the Tailscale key after
the first connect (M12).
