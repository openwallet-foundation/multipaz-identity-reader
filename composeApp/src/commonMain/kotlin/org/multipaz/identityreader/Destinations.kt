package org.multipaz.identityreader

import kotlinx.serialization.Serializable

@Serializable
sealed class Destination

@Serializable
data object StartDestination: Destination()

@Serializable
data object ScanQrDestination: Destination()

@Serializable
data object SelectRequestDestination: Destination()

@Serializable
data object TransferDestination: Destination()

@Serializable
data object ShowResultsDestination: Destination()

@Serializable
data object ShowDetailedResultsDestination: Destination()

@Serializable
data object AboutDestination: Destination()

@Serializable
data class CertificateViewerDestination(
    val certificateDataBase64: String
): Destination()

@Serializable
data class TrustEntryViewerDestination(
    val trustManagerId: String,
    val entryIndex: Int,
    val justImported: Boolean,
): Destination()

@Serializable
data class TrustEntryEditorDestination(
    val entryIndex: Int,
): Destination()

@Serializable
data class VicalEntryViewerDestination(
    val trustManagerId: String,
    val entryIndex: Int,
    val certificateIndex: Int,
): Destination()

@Serializable
data object TrustedIssuersDestination: Destination()

@Serializable
data object DeveloperSettingsDestination: Destination()

@Serializable
data object ReaderIdentityDestination: Destination()

const val TRUST_MANAGER_ID_BUILT_IN = "built-in"
const val TRUST_MANAGER_ID_USER = "user"