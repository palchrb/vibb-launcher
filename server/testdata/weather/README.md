# Weather fixtures (design 75)

Recorded once on 2026-11-03 at 14:00 UTC for the cell `lat=59.94&lon=10.76` (Oslo, Bjølsen). The tests never ask
the network.

- `met_complete.json` - MET Norway's Locationforecast 2.0 `complete`, its `timeseries` trimmed to the entries before
  2026-11-05T00:00Z (35 hourly entries). Sent with `Date: Tue, 03 Nov 2026 14:00:22 GMT`, `Expires: Tue, 03 Nov 2026
  14:29:48 GMT`, `Last-Modified: Tue, 03 Nov 2026 13:58:32 GMT`.
- `met_nowcast.json` - MET Norway's Nowcast 2.0 `complete` at the same time and cell, as sent (`radar_coverage` `ok`,
  dry).

Kartverket's place-name pages that were here went on 2026-11-04 with the place lookup (the user: "Vi dropper navnet").

MET Norway's data is licensed under CC BY 4.0 (https://api.met.no/doc/License). Changes: trimmed as said above, minified.

`../weather.json` is the contract shared with the launcher (byte-identical in `launcher/app/src/test/resources/`);
`WEATHER_CONTRACT_WRITE=1 cargo test weather::tests::the_contract` rewrites its computed parts.
