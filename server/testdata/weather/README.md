# Weather fixtures (design 75)

Recorded once on 2026-11-03 at 14:00 UTC for the cell `lat=59.94&lon=10.76` (Oslo, Bjølsen). The tests never ask
the network.

- `met_complete.json` - MET Norway's Locationforecast 2.0 `complete`, its `timeseries` trimmed to the entries before
  2026-11-05T00:00Z (35 hourly entries). Sent with `Date: Tue, 03 Nov 2026 14:00:22 GMT`, `Expires: Tue, 03 Nov 2026
  14:29:48 GMT`, `Last-Modified: Tue, 03 Nov 2026 13:58:32 GMT`.
- `met_nowcast.json` - MET Norway's Nowcast 2.0 `complete` at the same time and cell, as sent (`radar_coverage` `ok`,
  dry).

- `met_complete_mountain_view.json` - the same product for `lat=37.42&lon=-122.08` (Mountain View, the emulator's
  default position), fetched on 2026-11-04 at 21:18 UTC (its model run is `updated_at` 19:18Z), whole and as sent (minified by MET): 90 entries, 63 hourly,
  ECMWF's shape outside MEPS (no `wind_speed_of_gust`, no `probability_of_precipitation`, the last five hourly entries
  without `next_6_hours`). Sent with `Date: Wed, 04 Nov 2026 21:18:08 GMT`, `Expires: Wed, 04 Nov 2026 21:48:16 GMT`.
  Read by `weather::tests::the_mountain_view_forecast_reads` only, not by the contract.

Kartverket's place-name pages that were here went on 2026-11-04 with the place lookup (the user: "Vi dropper navnet").

MET Norway's data is licensed under CC BY 4.0 (https://api.met.no/doc/License). Changes: `met_complete.json` trimmed as
said above and minified; the others as sent.

`../weather.json` is the contract shared with the launcher (byte-identical in `launcher/app/src/test/resources/`);
`WEATHER_CONTRACT_WRITE=1 cargo test weather::tests::the_contract` rewrites its computed parts.
