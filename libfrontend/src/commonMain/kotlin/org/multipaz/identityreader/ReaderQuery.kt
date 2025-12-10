package org.multipaz.identityreader

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Cbor
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.documenttype.knowntypes.PhotoID
import org.multipaz.mdoc.engagement.Capability
import org.multipaz.mdoc.engagement.DeviceEngagement
import org.multipaz.mdoc.request.DeviceRequestInfo
import org.multipaz.mdoc.request.DocumentSet
import org.multipaz.mdoc.request.UseCase
import org.multipaz.mdoc.request.buildDeviceRequestSuspend
import org.multipaz.util.Logger

private const val TAG = "ReaderQuery"

enum class ReaderQuery(
    val displayName: String,
) {
    AGE_OVER_18(
        displayName = "Age Over 18",
    ),
    AGE_OVER_21(
        displayName = "Age Over 21",
    ),
    IDENTIFICATION(
        displayName = "Identification",
    ),

    ;

    suspend fun generateDeviceRequest(
        settingsModel: SettingsModel,
        encodedSessionTranscript: ByteString,
        readerBackendClient: ReaderBackendClient
    ): ByteString {
        val readerIdentityId = when (settingsModel.readerAuthMethod.value) {
            ReaderAuthMethod.NO_READER_AUTH,
            ReaderAuthMethod.CUSTOM_KEY,
            ReaderAuthMethod.STANDARD_READER_AUTH -> null
            ReaderAuthMethod.STANDARD_READER_AUTH_WITH_GOOGLE_ACCOUNT_DETAILS -> ""
            ReaderAuthMethod.IDENTITY_FROM_GOOGLE_ACCOUNT ->  {
                settingsModel.readerAuthMethodGoogleIdentity.value!!.id
            }
        }
        val sessionTranscript = Cbor.decode(encodedSessionTranscript.toByteArray())
        val deviceEngagement = DeviceEngagement.fromDataItem(sessionTranscript.asArray[0].asTaggedEncodedCbor)
        val deviceRequest = when (settingsModel.readerAuthMethod.value) {
            ReaderAuthMethod.NO_READER_AUTH -> {
                generateEncodedDeviceRequest(
                    query = this,
                    deviceEngagement = deviceEngagement,
                    intentToRetain = settingsModel.logTransactions.value,
                    encodedSessionTranscript = encodedSessionTranscript.toByteArray(),
                    readerKey = null,
                )
            }
            ReaderAuthMethod.IDENTITY_FROM_GOOGLE_ACCOUNT,
            ReaderAuthMethod.STANDARD_READER_AUTH,
            ReaderAuthMethod.STANDARD_READER_AUTH_WITH_GOOGLE_ACCOUNT_DETAILS -> {
                val (readerKey, keyInfo) = try {
                    val (keyInfo, keyCertification) = readerBackendClient.getKey(readerIdentityId)
                    Pair(
                        AsymmetricKey.X509CertifiedSecureAreaBased(
                            certChain = keyCertification,
                            alias = keyInfo.alias,
                            secureArea = keyInfo.let { readerBackendClient.secureArea },
                            keyInfo = readerBackendClient.secureArea.getKeyInfo(keyInfo.alias)
                        ),
                        keyInfo
                    )
                } catch (e: ReaderIdentityNotAvailableException) {
                    try {
                        Logger.w(TAG, "The reader identity we're configured for is no longer working", e)
                        Logger.i(TAG, "Resetting configuration to standard reader auth")
                        settingsModel.readerAuthMethod.value = ReaderAuthMethod.STANDARD_READER_AUTH
                        settingsModel.readerAuthMethodGoogleIdentity.value = null
                        val (keyInfo, keyCertification) = readerBackendClient.getKey(null)
                        Pair(
                            AsymmetricKey.X509CertifiedSecureAreaBased(
                                certChain = keyCertification,
                                alias = keyInfo.alias,
                                secureArea = keyInfo.let { readerBackendClient.secureArea },
                                keyInfo = readerBackendClient.secureArea.getKeyInfo(keyInfo.alias)
                            ),
                            keyInfo
                        )
                    } catch (e: Throwable) {
                        Logger.e(TAG, "Error getting certified reader key, proceeding without reader authentication", e)
                        Pair(null, null)
                    }
                } catch (e: Throwable) {
                    Logger.e(TAG, "Error getting certified reader key, proceeding without reader authentication", e)
                    Pair(null, null)
                }
                generateEncodedDeviceRequest(
                    query = this,
                    deviceEngagement = deviceEngagement,
                    intentToRetain = settingsModel.logTransactions.value,
                    encodedSessionTranscript = encodedSessionTranscript.toByteArray(),
                    readerKey = readerKey,
                ).also {
                    keyInfo?.let { readerBackendClient.markKeyAsUsed(it) }
                }
            }
            ReaderAuthMethod.CUSTOM_KEY -> {
                generateEncodedDeviceRequest(
                    query = this,
                    deviceEngagement = deviceEngagement,
                    intentToRetain = settingsModel.logTransactions.value,
                    encodedSessionTranscript = encodedSessionTranscript.toByteArray(),
                    readerKey = AsymmetricKey.X509CertifiedExplicit(
                        certChain = settingsModel.customReaderAuthCertChain.value!!,
                        privateKey = settingsModel.customReaderAuthKey.value!!,
                    )
                )
            }
        }
        return ByteString(deviceRequest)
    }
}

suspend fun generateEncodedDeviceRequest(
    query: ReaderQuery,
    deviceEngagement: DeviceEngagement,
    intentToRetain: Boolean,
    encodedSessionTranscript: ByteArray,
    readerKey: AsymmetricKey.X509Compatible?,
): ByteArray {
    val mdlItemsToRequest = mutableMapOf<String, MutableMap<String, Boolean>>()
    val mdlNs = mdlItemsToRequest.getOrPut(DrivingLicense.MDL_NAMESPACE) { mutableMapOf() }
    when (query) {
        ReaderQuery.AGE_OVER_18 -> {
            mdlNs.put("age_over_18", intentToRetain)
            mdlNs.put("portrait", intentToRetain)
        }
        ReaderQuery.AGE_OVER_21 -> {
            mdlNs.put("age_over_21", intentToRetain)
            mdlNs.put("portrait", intentToRetain)
        }
        ReaderQuery.IDENTIFICATION -> {
            mdlNs.put("given_name", intentToRetain)
            mdlNs.put("family_name", intentToRetain)
            mdlNs.put("birth_date", intentToRetain)
            mdlNs.put("birth_place", intentToRetain)
            mdlNs.put("sex", intentToRetain)
            mdlNs.put("portrait", intentToRetain)
            mdlNs.put("resident_address", intentToRetain)
            mdlNs.put("resident_city", intentToRetain)
            mdlNs.put("resident_state", intentToRetain)
            mdlNs.put("resident_postal_code", intentToRetain)
            mdlNs.put("resident_country", intentToRetain)
            mdlNs.put("issuing_authority", intentToRetain)
            mdlNs.put("document_number", intentToRetain)
            mdlNs.put("issue_date", intentToRetain)
            mdlNs.put("expiry_date", intentToRetain)
        }
    }
    val mdlDocType = DrivingLicense.MDL_DOCTYPE

    val photoIdItemsToRequest = mutableMapOf<String, MutableMap<String, Boolean>>()
    val iso23220Ns = photoIdItemsToRequest.getOrPut(PhotoID.ISO_23220_2_NAMESPACE) { mutableMapOf() }
    when (query) {
        ReaderQuery.AGE_OVER_18 -> {
            iso23220Ns.put("age_over_18", intentToRetain)
            iso23220Ns.put("portrait", intentToRetain)
        }
        ReaderQuery.AGE_OVER_21 -> {
            iso23220Ns.put("age_over_21", intentToRetain)
            iso23220Ns.put("portrait", intentToRetain)
        }
        ReaderQuery.IDENTIFICATION -> {
            iso23220Ns.put("given_name", intentToRetain)
            iso23220Ns.put("family_name", intentToRetain)
            iso23220Ns.put("birth_date", intentToRetain)
            iso23220Ns.put("birthplace", intentToRetain)
            iso23220Ns.put("sex", intentToRetain)
            iso23220Ns.put("portrait", intentToRetain)
            iso23220Ns.put("resident_address", intentToRetain)
            iso23220Ns.put("resident_city", intentToRetain)
            iso23220Ns.put("resident_state", intentToRetain)
            iso23220Ns.put("resident_postal_code", intentToRetain)
            iso23220Ns.put("resident_country", intentToRetain)
            iso23220Ns.put("issuing_authority_unicode", intentToRetain)
            iso23220Ns.put("document_number", intentToRetain)
            iso23220Ns.put("issue_date", intentToRetain)
            iso23220Ns.put("expiry_date", intentToRetain)
        }
    }
    val photoIdDocType = PhotoID.PHOTO_ID_DOCTYPE

    val deviceRequestInfo = if (deviceEngagement.capabilities.get(Capability.EXTENDED_REQUEST_SUPPORT)?.asBoolean == true) {
        DeviceRequestInfo(
            useCases = listOf(
                UseCase(
                    mandatory = true,
                    documentSets = listOf(
                        DocumentSet(listOf(0)),
                        DocumentSet(listOf(1)),
                    ),
                    purposeHints = mapOf()
                )
            )
        )
    } else { null }

    val deviceRequest = buildDeviceRequestSuspend(
        sessionTranscript = Cbor.decode(encodedSessionTranscript),
        deviceRequestInfo = deviceRequestInfo
    ) {
        if (readerKey != null) {
            addDocRequest(
                docType = mdlDocType,
                nameSpaces = mdlItemsToRequest,
                docRequestInfo = null,
                readerKey = readerKey
            )
            if (deviceEngagement.capabilities.get(Capability.EXTENDED_REQUEST_SUPPORT)?.asBoolean == true) {
                addDocRequest(
                    docType = photoIdDocType,
                    nameSpaces = photoIdItemsToRequest,
                    docRequestInfo = null,
                    readerKey = readerKey
                )
            }
        } else {
            addDocRequest(
                docType = mdlDocType,
                nameSpaces = mdlItemsToRequest,
                docRequestInfo = null
            )
            if (deviceEngagement.capabilities.get(Capability.EXTENDED_REQUEST_SUPPORT)?.asBoolean == true) {
                addDocRequest(
                    docType = photoIdDocType,
                    nameSpaces = photoIdItemsToRequest,
                    docRequestInfo = null,
                )
            }
        }
    }
    Logger.iCbor(TAG, "deviceRequest", deviceRequest.toDataItem())
    return Cbor.encode(deviceRequest.toDataItem())
}


fun List<ReaderQuery>.findIndexForId(id: String): Int? {
    this.forEachIndexed { idx, readerQuery ->
        if (readerQuery.name == id) {
            return idx
        }
    }
    return null
}