package org.multipaz.identityreader

import androidx.navigation3.runtime.NavKey

sealed interface Destination : NavKey

data object StartDestination: Destination

data object ScanQrDestination: Destination

data object SelectRequestDestination: Destination

data object TransferDestination: Destination

data object ShowResultsDestination: Destination

data object ShowDetailedResultsDestination: Destination

data object AboutDestination: Destination

data class CertificateViewerDestination(
    val certificateDataBase64: String
): Destination

data class TrustEntryViewerDestination(
    val trustManagerId: String,
    val entryIndex: Int,
    val justImported: Boolean,
): Destination

data class TrustEntryEditorDestination(
    val entryIndex: Int,
): Destination

data class VicalEntryViewerDestination(
    val trustManagerId: String,
    val entryIndex: Int,
    val certificateIndex: Int,
): Destination

data object TrustedIssuersDestination: Destination

data object DeveloperSettingsDestination: Destination

data object ReaderIdentityDestination: Destination

const val TRUST_MANAGER_ID_BUILT_IN = "built-in"
const val TRUST_MANAGER_ID_USER = "user"
