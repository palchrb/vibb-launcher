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
- **The two one-time steps** (Q2) are rows in the PIN-gated Settings' "Kartlenker" group, each opening Android's
  "Open by default" page (only in a pause): Google Play services' "Open supported links" off, then Vibb One's on with
  every link ticked. With the switch off and Play services' links still off, one row gives them back (Q4).
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
