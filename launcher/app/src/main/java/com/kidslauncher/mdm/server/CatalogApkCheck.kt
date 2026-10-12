package com.kidslauncher.mdm.server

import kotlinx.serialization.Serializable

/*
 * The phone's check of a catalog APK before it installs it (security review M10, the phone side) -
 * pure, tested in CatalogApkCheckTest; AppDownloads reads the APK and acts. The server pins each
 * catalog app's Android package and the SHA-256 of its signing certificate from the first release
 * (`TrackedAppUpdate.packageName`/`signerSha256`), but it only *reads* the signing block, it doesn't
 * verify it: the first certificate of the first signer of the v3 block, else the v2 block. So the
 * phone checks the APK as Android verified it (`getPackageArchiveInfo(GET_SIGNING_CERTIFICATES)`):
 * - the manifest's package is the pinned one (and the install session is held to it with
 *   `setAppPackageName`);
 * - the pinned certificate is in the APK's signing lineage - `signingCertificateHistory`, which
 *   holds the rotated-from certificates too: Android 13+ verifies a v3.1 block (key rotation for SDK
 *   33+) that the server ignores, so after a rotation the pin is the older key, which is still in
 *   the lineage; with several signers (no lineage) the pin must be one of `apkContentsSigners` (the
 *   server pins the first signer only). This is what `PackageManager.hasSigningCertificate` checks
 *   for an installed package; an APK file has no such call.
 * - an APK whose manifest or signers Android can't read is refused: it fails closed.
 */

/** Why a catalog APK isn't installed; [wire] is the status report's `app_downloads` state. */
enum class CatalogApkVerdict(val wire: String) {
    OK(""),

    /** The server has no package or signing key for the app (an older server, or a release whose
     * APK it couldn't read): nothing to check against, so nothing is installed. */
    NOT_PINNED("refused_unpinned"),

    /** Android can't read the APK's manifest or signers. */
    UNREADABLE("refused_unreadable"),

    /** The APK is another Android package than the catalog's. */
    WRONG_PACKAGE("refused_package"),

    /** The catalog's signing certificate isn't in the APK's signers or their lineage. */
    WRONG_SIGNER("refused_signer"),
}

/** The server's pin as the phone compares it: lower-case hex without colons, `null` if it isn't a SHA-256. */
fun normalizedSigner(signerSha256: String?): String? =
    signerSha256?.trim()?.lowercase()?.replace(":", "")?.takeIf(::isSignerHex)

/** Whether the server sent both pins for a catalog app (else it isn't downloaded at all). */
fun catalogPinned(packageName: String?, signerSha256: String?): Boolean =
    !packageName.isNullOrBlank() && normalizedSigner(signerSha256) != null

/**
 * [expectedPackage]/[expectedSigner] the catalog's pins; [archivePackage] the APK's package as
 * PackageManager parsed it (`null` = unreadable); [archiveSigners] SHA-256 hex of every certificate
 * in its signers or their lineage ([SelfUpdate.signers]; `null` or empty = unreadable).
 */
fun catalogApkCheck(
    expectedPackage: String?,
    expectedSigner: String?,
    archivePackage: String?,
    archiveSigners: Set<String>?,
): CatalogApkVerdict {
    val signer = normalizedSigner(expectedSigner)
    return when {
        expectedPackage.isNullOrBlank() || signer == null -> CatalogApkVerdict.NOT_PINNED
        archivePackage.isNullOrBlank() || archiveSigners.isNullOrEmpty() -> CatalogApkVerdict.UNREADABLE
        archivePackage != expectedPackage -> CatalogApkVerdict.WRONG_PACKAGE
        archiveSigners.none { it.lowercase() == signer } -> CatalogApkVerdict.WRONG_SIGNER
        else -> CatalogApkVerdict.OK
    }
}

/** A refused catalog release, reported in `app_downloads` (state = [reason], a [CatalogApkVerdict.wire])
 * until the server lists another release of the app. */
@Serializable
data class CatalogRefusal(
    val appId: Long,
    val tag: String,
    val reason: String,
    val atMs: Long,
)

/**
 * The refusals after a successful list fetch: an APK's refusal stays while the list still has that
 * release ([listed]: app id -> tag); "unpinned" is recomputed - exactly the apps in [unpinned]
 * (app id -> tag), keeping the time it was first seen.
 */
fun reconcileRefusals(
    current: List<CatalogRefusal>,
    listed: Map<Long, String>,
    unpinned: Map<Long, String>,
    nowMs: Long,
): List<CatalogRefusal> {
    val unpinnedReason = CatalogApkVerdict.NOT_PINNED.wire
    val kept = current.filter { it.reason != unpinnedReason && listed[it.appId] == it.tag && it.appId !in unpinned }
    val fresh = unpinned.map { (appId, tag) ->
        current.firstOrNull { it.appId == appId && it.tag == tag && it.reason == unpinnedReason }
            ?: CatalogRefusal(appId, tag, unpinnedReason, nowMs)
    }
    return (kept + fresh).sortedBy { it.appId }
}

/** [current] with [refusal] (it replaces the app's earlier one). */
fun withRefusal(current: List<CatalogRefusal>, refusal: CatalogRefusal): List<CatalogRefusal> =
    (current.filterNot { it.appId == refusal.appId } + refusal).sortedBy { it.appId }
