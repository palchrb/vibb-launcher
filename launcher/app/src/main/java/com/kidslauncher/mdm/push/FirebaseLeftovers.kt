package com.kidslauncher.mdm.push

/*
 * What the FCM era left in our app data (design 19): our own `push_state` preferences (they held
 * the Firebase installation ID - a credential for sending to this phone) and the Firebase SDK's
 * files, preferences, database and scheduled jobs. Nothing reads them any more; FirebaseCleanup
 * deletes them once, at the first start after the unlock. Pure, unit-tested in
 * FirebaseLeftoversTest - it must never pick one of our own files.
 */

/** Our own FCM-era preferences file (`PushState` until design 19). */
const val LEGACY_PUSH_PREFS = "push_state"

/** SharedPreferences files of firebase-common, -installations and -messaging (names without
 * `.xml`): `com.google.firebase.common.prefs:<key>`, `com.google.firebase.messaging`,
 * `com.google.android.gms.appid`, `FirebaseHeartBeat<key>`, `FirebaseAppHeartBeat`. */
private val FIREBASE_PREFS_PREFIXES = listOf(
    "com.google.firebase.",
    "com.google.android.gms.appid",
    "FirebaseHeartBeat",
    "FirebaseAppHeartBeat",
)

/** Files in `filesDir` / `noBackupFilesDir`: Firebase Installations' `PersistedInstallation.<key>.json`
 * and its `generatefid.lock`, firebase-messaging's `com.google.android.gms.appid-no-backup`. */
private val FIREBASE_FILE_PREFIXES = listOf("PersistedInstallation.", "com.google.android.gms.appid")
private const val FIREBASE_FID_LOCK = "generatefid.lock"

/** Google's data transport (FCM delivery metrics) kept its events in a database. */
private const val DATATRANSPORT_PREFIX = "com.google.android.datatransport"

private val DB_AUX_SUFFIXES = listOf("-journal", "-wal", "-shm")

data class FirebaseLeftovers(
    /** Preferences files to delete (names without `.xml`, for `deleteSharedPreferences`). */
    val prefs: List<String>,
    /** File names to delete, in the directory they were listed from. */
    val files: List<String>,
    /** Database names for `deleteDatabase` (which takes the journal files with it). */
    val databases: List<String>,
) {
    val isEmpty: Boolean get() = prefs.isEmpty() && files.isEmpty() && databases.isEmpty()
}

/**
 * Picks the leftovers from directory listings: [prefsFiles] = the file names in `shared_prefs`
 * (with or without `.xml`), [files] = the names in `filesDir` or `noBackupFilesDir`, [databases]
 * = `databaseList()`.
 */
fun firebaseLeftovers(prefsFiles: Collection<String>, files: Collection<String>, databases: Collection<String>): FirebaseLeftovers {
    val prefs = prefsFiles.map { it.removeSuffix(".bak").removeSuffix(".xml") }
        .filter { name -> name == LEGACY_PUSH_PREFS || FIREBASE_PREFS_PREFIXES.any { name.startsWith(it) } }
        .distinct().sorted()
    val leftoverFiles = files
        .filter { name -> name == FIREBASE_FID_LOCK || FIREBASE_FILE_PREFIXES.any { name.startsWith(it) } }
        .distinct().sorted()
    val dbs = databases.filter { it.startsWith(DATATRANSPORT_PREFIX) }
        .map { name -> DB_AUX_SUFFIXES.firstOrNull { name.endsWith(it) }?.let { name.removeSuffix(it) } ?: name }
        .distinct().sorted()
    return FirebaseLeftovers(prefs, leftoverFiles, dbs)
}

/** A job scheduled by Google's data transport (its JobInfoSchedulerService), whose class is gone. */
fun isFirebaseJobService(className: String?): Boolean = className?.startsWith(DATATRANSPORT_PREFIX) == true
