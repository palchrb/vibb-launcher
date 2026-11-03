package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/**
 * `GET /api/devices/weather/now`'s answer (design 75 §3.5, D9), asked when the card opens: [status] `ok` with the
 * card's top [line] or `null` (dry, no radar coverage, outside the Nordic area, MET failing), or `none` (off, no
 * position). Pinned by `weather.json`'s `now_answers` (`WeatherContractTest`).
 */
@Serializable
data class WeatherNow(val v: Int = 0, val status: String = "", val line: WeatherNowLine? = null)

/** `rain_in`, `snow_in` and `dry_in` with [minutes] ("Regn om ca. 25 min"), `rain_all` and `snow_all` without. */
@Serializable
data class WeatherNowLine(val key: String = "", val minutes: Int? = null)
