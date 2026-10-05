package com.kidslauncher.mdm.server

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

/** Shared JSON config for both Retrofit and the cached-policy preference blob. The server's JSON
 * is snake_case (Rust's serde default, no per-field renames); this maps it to/from idiomatic
 * camelCase Kotlin properties without needing an `@SerialName` on every field. Its own file
 * (not in `MdmApi.kt`) so JVM unit tests can use it without loading the Retrofit wiring.
 *
 * No `coerceInputValues`: a `null` for a non-nullable field (e.g. `"kiosk_desired": null`) fails
 * the whole decode. The server's key-snapshot test guarantees it never sends one. */
@OptIn(ExperimentalSerializationApi::class)
val ServerJson: Json = Json {
    ignoreUnknownKeys = true
    namingStrategy = JsonNamingStrategy.SnakeCase
}
