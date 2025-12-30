package org.multipaz.identityreader

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Cbor
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.documenttype.knowntypes.Loyalty
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
    WHOLESALE_MEMBERSHIP(
        displayName = "Utopia Wholesale Membership"
    )

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
    val loyaltyDoctype = Loyalty.LOYALTY_DOCTYPE
    val loyaltyIdItemsToRequest = mutableMapOf<String, MutableMap<String, Boolean>>()

    when (query) {
        ReaderQuery.WHOLESALE_MEMBERSHIP -> {
            val loyaltyIdNs =
                loyaltyIdItemsToRequest.getOrPut(Loyalty.LOYALTY_NAMESPACE) { mutableMapOf() }

            loyaltyIdNs.put("family_name", intentToRetain)
            loyaltyIdNs.put("given_name", intentToRetain)
            loyaltyIdNs.put("portrait", intentToRetain)
            loyaltyIdNs.put("membership_number", intentToRetain)
            loyaltyIdNs.put("tier", intentToRetain)
            loyaltyIdNs.put("issue_date", intentToRetain)
            loyaltyIdNs.put("expiry_date", intentToRetain)
        }
    }
    val deviceRequestInfo =
        if (deviceEngagement.capabilities.get(Capability.EXTENDED_REQUEST_SUPPORT)?.asBoolean == true) {
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
        } else {
            null
        }

    val deviceRequest = buildDeviceRequestSuspend(
        sessionTranscript = Cbor.decode(encodedSessionTranscript),
        deviceRequestInfo = deviceRequestInfo
    ) {
        addDocRequest(
            docType = loyaltyDoctype,
            nameSpaces = loyaltyIdItemsToRequest,
            docRequestInfo = null
        )
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