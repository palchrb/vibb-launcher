package com.kidslauncher.mdm.apps

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * This interface is implemented by [AppInfo].
 */
@Serializable
sealed interface AbstractAppInfo {
    fun serialize(): String {
        return Json.encodeToString(this)
    }

    companion object {
        const val INVALID_USER = -1

        fun deserialize(serialized: String): AbstractAppInfo {
            return Json.decodeFromString(serialized)
        }
    }
}