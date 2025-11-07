package org.multipaz.identityreader.libbackend

import io.ktor.http.HttpStatusCode
import kotlinx.datetime.DateTimePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.multipaz.asn1.ASN1Integer
import org.multipaz.asn1.OID
import org.multipaz.cbor.annotation.CborSerializable
import org.multipaz.certext.GoogleAccount
import org.multipaz.certext.MultipazExtension
import org.multipaz.certext.toCbor
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.EcPublicKey
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509CertChain
import org.multipaz.crypto.X509Extension
import org.multipaz.device.AndroidKeystoreSecurityLevel
import org.multipaz.device.AssertionNonce
import org.multipaz.device.DeviceAssertion
import org.multipaz.device.DeviceAttestation
import org.multipaz.device.DeviceAttestationAndroid
import org.multipaz.device.DeviceAttestationException
import org.multipaz.device.DeviceAttestationValidationData
import org.multipaz.device.fromCbor
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.storage.StorageTable
import org.multipaz.storage.StorageTableSpec
import org.multipaz.trustmanagement.TrustEntry
import org.multipaz.trustmanagement.toCbor
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

// Note: ReaderIdentity is also defined on the client-side but in another package. Be careful not to mix them up,
//   especially in ReaderBackendTest which includes both this code and the client-side code.
//

data class ReaderIdentity(
    val id: String,
    val displayName: String,
    val displayIcon: ByteString?,
    val privateKey: EcPrivateKey,
    val certChain: X509CertChain
)

/**
 * Data about a signed-in Google User.
 */
@CborSerializable
data class SignedInGoogleUser(
    val id: String,
    val email: String?,
    val displayName: String?,
    val profilePictureUri: String?,
)

/**
 * Data stored for each registered client.
 *
 * The [deviceAttestation] field is obtained at registration time and used at every future interaction to
 * ensure we're talking to the same device.
 *
 * @param deviceAttestation the device attestation we received at registration time.
 * @param deviceIsTrusted set to `true` if the device integrity was successfully validated at registration time.
 * @param signedInGoogleUser set to `null` if user isn't signed in, otherwise [SignedInGoogleUser] with data.
 * @param selectedReaderIdentity the reader identity to get certificates as or `null` to use default reader identity.
 */
@CborSerializable
data class ClientData(
    val deviceAttestation: DeviceAttestation,
    val deviceIsTrusted: Boolean,
    var signedInGoogleUser: SignedInGoogleUser?,
    var selectedReaderIdentity: String?
) {
    companion object {
        const val SCHEMA_VERSION = 1L
    }
}

/**
 * A reference implementation of the reader backend.
 *
 * @param readerRootKeyForUntrustedDevices the private key for the reader root.
 * @param readerRootCertChainForUntrustedDevices the certification for [readerRootKeyForUntrustedDevices].
 * @param readerRootKey the private key for the reader root.
 * @param readerRootCertChain the certification for [readerRootKey].
 * @param readerCertDuration the amount of time the issued certificates will be valid for.
 * @param iosReleaseBuild Whether a release build is required on iOS. When `false`, both debug and release builds
 *   are accepted.
 * @param iosAppIdentifier iOS app identifier that consists of a team id followed by a dot and app bundle name. If
 *   `null`, any app identifier is accepted. It must not be `null` if [iosReleaseBuild] is `true`
 * @param androidGmsAttestation whether to require attestations made for local key on clients is using the Google root.
 * @param androidVerifiedBootGreen whether to require clients are in verified boot state green.
 * @param androidAppSignatureCertificateDigests the allowed list of applications that can use the
 *   service. Each element is the bytes of the SHA-256 of a signing certificate, see the
 *   [Signature](https://developer.android.com/reference/android/content/pm/Signature) class in
 *   the Android SDK for details. If empty, allow any app.
 * @param issuerTrustListVersion the version of the current issuer trust list.
 * @param issuerTrustList the current issuer trust list.
 * @param googleIdTokenVerifier verifies a Google ID token, throws if verification fails. Returns the nonce
 *   extracted from the token and a [SignedInGoogleUser] with information about the user.
 * @param getStorageTable a method to create [StorageTable] from a [StorageTableSpec].
 * @param getCurrentTime a method to get the current time, used only for testing.
 * @param random the [Random] source to use.
 */
open class ReaderBackend(
    private val readerRootKeyForUntrustedDevices: EcPrivateKey,
    private val readerRootCertChainForUntrustedDevices: X509CertChain,
    private val readerRootKey: EcPrivateKey,
    private val readerRootCertChain: X509CertChain,
    private val readerCertDuration: DateTimePeriod,
    private val iosReleaseBuild: Boolean,
    private val iosAppIdentifier: String?,
    private val androidGmsAttestation: Boolean,
    private val androidVerifiedBootGreen: Boolean,
    private val androidAppSignatureCertificateDigests: List<ByteString>,
    private val issuerTrustListVersion: Long,
    private val issuerTrustList: List<TrustEntry>,
    private val googleIdTokenVerifier: suspend (googleIdTokenString: String) -> Pair<String, SignedInGoogleUser>,
    private val getReaderIdentitiesForUser: suspend (user: SignedInGoogleUser) -> List<ReaderIdentity>,
    private val getStorageTable: suspend (spec: StorageTableSpec) -> StorageTable,
    private val getCurrentTime: () -> Instant = { Clock.System.now() },
    private val random: Random = Random.Default
) {
    suspend fun handleGetNonce(
        request: JsonObject,
    ): Pair<HttpStatusCode, JsonObject> {
        val nonceTable = getStorageTable(nonceTableSpec)
        val nonce = random.nextBytes(16)
        val nonceBase64url = nonce.toBase64Url()
        nonceTable.insert(
            key = nonceBase64url,
            data = ByteString(),
            expiration = Clock.System.now() + NONCE_EXPIRATION_TIME
        )
        return Pair(
            HttpStatusCode.OK,
            buildJsonObject {
                put("nonce", nonceBase64url)
            }
        )
    }

    suspend fun handleRegister(
        request: JsonObject,
    ): Pair<HttpStatusCode, JsonObject> {
        val nonceTable = getStorageTable(nonceTableSpec)
        val clientTable = getStorageTable(clientTableSpec)

        val nonceBase64Url = request["nonce"]!!.jsonPrimitive.content
        if (nonceTable.get(key = nonceBase64Url) == null) {
            throw IllegalArgumentException("Unknown nonce")
        }
        val nonce = nonceBase64Url.fromBase64Url()
        val deviceAttestation = DeviceAttestation.fromCbor(
            request["deviceAttestation"]!!.jsonPrimitive.content.fromBase64Url()
        )

        var deviceIsTrusted = false
        try {
            // Check the attestation...
            deviceAttestation.validate(
                DeviceAttestationValidationData(
                    attestationChallenge = ByteString(nonce),
                    iosReleaseBuild = iosReleaseBuild,
                    iosAppIdentifiers = iosAppIdentifier?.let { setOf(it) } ?: emptySet(),
                    androidGmsAttestation = androidGmsAttestation,
                    androidVerifiedBootGreen = androidVerifiedBootGreen,
                    androidRequiredKeyMintSecurityLevel = AndroidKeystoreSecurityLevel.TRUSTED_ENVIRONMENT,
                    androidAppSignatureCertificateDigests = androidAppSignatureCertificateDigests.toSet(),
                    androidAppPackageNames = setOf()
                )
            )
            // For now we only trust Android devices...
            if (deviceAttestation is DeviceAttestationAndroid) {
                deviceIsTrusted = true
            }
        } catch (e: DeviceAttestationException) {
            e.printStackTrace()
            println("Device is untrusted: $e")
        }

        val clientData = ClientData(
            deviceAttestation = deviceAttestation,
            deviceIsTrusted = deviceIsTrusted,
            signedInGoogleUser = null,
            selectedReaderIdentity = null
        )
        val id = clientTable.insert(
            key = null,
            data = ByteString(clientData.toCbor())
        )
        return Pair(
            HttpStatusCode.OK,
            buildJsonObject {
                put("registrationId", id)
            }
        )
    }

    suspend fun handleCertifyKeys(
        request: JsonObject,
    ): Pair<HttpStatusCode, JsonObject> {
        val nonceTable = getStorageTable(nonceTableSpec)
        val clientTable = getStorageTable(clientTableSpec)
        val id = request["registrationId"]!!.jsonPrimitive.content
        val encodedClientData = clientTable.get(key = id)
        if (encodedClientData == null) {
            // Return a 404 here to convey to the client that we don't know this registration ID. This is
            // helpful for the client because they can go ahead and re-register instead of just failing in
            // perpetuity. This is helpful for example if all storage for a server is deleted, that way
            // all clients will just re-register and there will be no loss in service.
            //
            return Pair(
                HttpStatusCode.NotFound,
                buildJsonObject {}
            )
        }
        val clientData = ClientData.fromCbor(encodedClientData.toByteArray())

        val nonceBase64Url = request["nonce"]!!.jsonPrimitive.content
        if (nonceTable.get(key = nonceBase64Url) == null) {
            throw IllegalArgumentException("Unknown nonce")
        }
        val nonce = nonceBase64Url.fromBase64Url()

        val deviceAssertionBase64Url = request["deviceAssertion"]!!.jsonPrimitive.content
        val deviceAssertion = DeviceAssertion.fromCbor(deviceAssertionBase64Url.fromBase64Url())
        check(deviceAssertion.assertion is AssertionNonce)
        check((deviceAssertion.assertion as AssertionNonce).nonce == ByteString(nonce))
        clientData.deviceAttestation.validateAssertion(deviceAssertion)

        val readerIdentityString = request["readerIdentity"]?.jsonPrimitive?.content
        val (readerIdentity, includeGoogleAccountDetails) = if (readerIdentityString != null) {
            // Right now we allow untrusted devices to pick a reader identity. This is because
            // they also proven they're able to sign into a Google account. We could restrict
            // this to only trusted devices...
            //
            if (clientData.signedInGoogleUser == null) {
                return Pair(
                    HttpStatusCode.Forbidden,
                    buildJsonObject {}
                )
            }

            if (readerIdentityString == "") {
                Pair(null, true)
            } else {
                val identitiesForUser = getReaderIdentitiesForUser(clientData.signedInGoogleUser!!)
                val identity = identitiesForUser.find { it.id == readerIdentityString }
                if (identity == null) {
                    return Pair(
                        HttpStatusCode.Forbidden,
                        buildJsonObject {}
                    )
                }
                Pair(identity, false)
            }
        } else {
            Pair(null, false)
        }

        val (chosenReaderRootKey, chosenReaderRootCertChain) = if (readerIdentity != null) {
            Pair(readerIdentity.privateKey, readerIdentity.certChain)
        } else {
            // Pick the right reader root CA, depending on if we trust the client.
            if (clientData.deviceIsTrusted) {
                Pair(readerRootKey, readerRootCertChain)
            } else {
                Pair(readerRootKeyForUntrustedDevices, readerRootCertChainForUntrustedDevices)
            }
        }

        val keysToCertify = request["keys"]!!.jsonArray
        val readerCertifications = mutableListOf<X509CertChain>()
        val now = Instant.fromEpochSeconds(getCurrentTime().epochSeconds)
        for (keyJwk in keysToCertify) {
            // Introduce a bit of jitter so it's not possible for someone to correlate two keys
            val jitterFrom = random.nextInt(12*3600).seconds
            val jitterUntil = random.nextInt(12*3600).seconds
            val validFrom = now - jitterFrom
            val validUntil = now.plus(readerCertDuration, TimeZone.currentSystemDefault()) + jitterUntil
            val key = EcPublicKey.fromJwk(keyJwk.jsonObject)
            val extensions = if (includeGoogleAccountDetails) {
                listOf(X509Extension(
                    oid = OID.X509_EXTENSION_MULTIPAZ_EXTENSION.oid,
                    isCritical = false,
                    data = ByteString(MultipazExtension(
                        googleAccount = GoogleAccount(
                            id = clientData.signedInGoogleUser!!.id,
                            emailAddress = clientData.signedInGoogleUser!!.email,
                            displayName = null,
                            profilePictureUri = clientData.signedInGoogleUser!!.profilePictureUri
                        )
                    ).toCbor())
                ))
            } else {
                emptyList()
            }
            val readerCert = MdocUtil.generateReaderCertificate(
                readerRootKey = AsymmetricKey.X509CertifiedExplicit(
                    certChain = chosenReaderRootCertChain,
                    privateKey = chosenReaderRootKey,
                ),
                readerKey = key,
                subject = X500Name.fromName("CN=Multipaz Identity Reader Single-Use Key"),
                serial = ASN1Integer.fromRandom(numBits = 128, random = random),
                validFrom = validFrom,
                validUntil = validUntil,
                extensions = extensions
            )
            val readerCertChain = X509CertChain(listOf(readerCert) + chosenReaderRootCertChain.certificates)
            readerCertifications.add(readerCertChain)
        }

        return Pair(
            HttpStatusCode.OK,
            buildJsonObject {
                putJsonArray("readerCertifications") {
                    for (readerCertChain in readerCertifications) {
                        add(readerCertChain.toX5c())
                    }
                }
            }
        )
    }

    suspend fun handleGetIssuerList(
        request: JsonObject,
    ): Pair<HttpStatusCode, JsonObject> {
        val nonceTable = getStorageTable(nonceTableSpec)
        val clientTable = getStorageTable(clientTableSpec)
        val id = request["registrationId"]!!.jsonPrimitive.content
        val encodedClientData = clientTable.get(key = id)
        if (encodedClientData == null) {
            // Return a 404 here to convey to the client that we don't know this registration ID. This is
            // helpful for the client because they can go ahead and re-register instead of just failing in
            // perpetuity. This is helpful for example if all storage for a server is deleted, that way
            // all clients will just re-register and there will be no loss in service.
            //
            return Pair(
                HttpStatusCode.NotFound,
                buildJsonObject {}
            )
        }
        val clientData = ClientData.fromCbor(encodedClientData.toByteArray())

        val nonceBase64Url = request["nonce"]!!.jsonPrimitive.content
        if (nonceTable.get(key = nonceBase64Url) == null) {
            throw IllegalArgumentException("Unknown nonce")
        }
        val nonce = nonceBase64Url.fromBase64Url()

        val deviceAssertionBase64Url = request["deviceAssertion"]!!.jsonPrimitive.content
        val deviceAssertion = DeviceAssertion.fromCbor(deviceAssertionBase64Url.fromBase64Url())
        check(deviceAssertion.assertion is AssertionNonce)
        check((deviceAssertion.assertion as AssertionNonce).nonce == ByteString(nonce))
        clientData.deviceAttestation.validateAssertion(deviceAssertion)

        val currentVersion = request["currentVersion"]?.jsonPrimitive?.longOrNull
        return Pair(
            HttpStatusCode.OK,
            buildJsonObject {
                if (currentVersion != issuerTrustListVersion) {
                    put("version", issuerTrustListVersion)
                    putJsonArray("entries") {
                        issuerTrustList.forEach {
                            add(it.toCbor().toBase64Url())
                        }
                    }
                }
            }
        )
    }

    suspend fun handleSignIn(
        request: JsonObject,
    ): Pair<HttpStatusCode, JsonObject> {
        val nonceTable = getStorageTable(nonceTableSpec)
        val clientTable = getStorageTable(clientTableSpec)
        val id = request["registrationId"]!!.jsonPrimitive.content
        val encodedClientData = clientTable.get(key = id)
        if (encodedClientData == null) {
            // Return a 404 here to convey to the client that we don't know this registration ID. This is
            // helpful for the client because they can go ahead and re-register instead of just failing in
            // perpetuity. This is helpful for example if all storage for a server is deleted, that way
            // all clients will just re-register and there will be no loss in service.
            //
            return Pair(
                HttpStatusCode.NotFound,
                buildJsonObject {}
            )
        }
        val clientData = ClientData.fromCbor(encodedClientData.toByteArray())

        val nonceBase64Url = request["nonce"]!!.jsonPrimitive.content
        if (nonceTable.get(key = nonceBase64Url) == null) {
            throw IllegalArgumentException("Unknown nonce")
        }
        val nonce = nonceBase64Url.fromBase64Url()

        val deviceAssertionBase64Url = request["deviceAssertion"]!!.jsonPrimitive.content
        val deviceAssertion = DeviceAssertion.fromCbor(deviceAssertionBase64Url.fromBase64Url())
        check(deviceAssertion.assertion is AssertionNonce)
        check((deviceAssertion.assertion as AssertionNonce).nonce == ByteString(nonce))
        clientData.deviceAttestation.validateAssertion(deviceAssertion)
        
        if (clientData.signedInGoogleUser != null) {
            throw IllegalStateException("User is already signed in")
        }

        val googleIdTokenString = request["googleIdTokenString"]!!.jsonPrimitive.content
        val (extractedNonce, signedInGoogleUser) = googleIdTokenVerifier(googleIdTokenString)
        if (extractedNonce != nonceBase64Url) {
            throw IllegalStateException("Nonce isn't what we expected")
        }

        clientData.signedInGoogleUser = signedInGoogleUser
        clientTable.update(
            key = id,
            data = ByteString(clientData.toCbor())
        )
        return Pair(
            HttpStatusCode.OK,
            buildJsonObject {}
        )
    }

    suspend fun handleSignOut(
        request: JsonObject,
    ): Pair<HttpStatusCode, JsonObject> {
        val nonceTable = getStorageTable(nonceTableSpec)
        val clientTable = getStorageTable(clientTableSpec)
        val id = request["registrationId"]!!.jsonPrimitive.content
        val encodedClientData = clientTable.get(key = id)
        if (encodedClientData == null) {
            // Return a 404 here to convey to the client that we don't know this registration ID. This is
            // helpful for the client because they can go ahead and re-register instead of just failing in
            // perpetuity. This is helpful for example if all storage for a server is deleted, that way
            // all clients will just re-register and there will be no loss in service.
            //
            return Pair(
                HttpStatusCode.NotFound,
                buildJsonObject {}
            )
        }
        val clientData = ClientData.fromCbor(encodedClientData.toByteArray())

        val nonceBase64Url = request["nonce"]!!.jsonPrimitive.content
        if (nonceTable.get(key = nonceBase64Url) == null) {
            throw IllegalArgumentException("Unknown nonce")
        }
        val nonce = nonceBase64Url.fromBase64Url()

        val deviceAssertionBase64Url = request["deviceAssertion"]!!.jsonPrimitive.content
        val deviceAssertion = DeviceAssertion.fromCbor(deviceAssertionBase64Url.fromBase64Url())
        check(deviceAssertion.assertion is AssertionNonce)
        check((deviceAssertion.assertion as AssertionNonce).nonce == ByteString(nonce))
        clientData.deviceAttestation.validateAssertion(deviceAssertion)

        if (clientData.signedInGoogleUser == null) {
            throw IllegalStateException("User isn't signed in")
        }
        clientData.signedInGoogleUser = null
        clientTable.update(
            key = id,
            data = ByteString(clientData.toCbor())
        )
        return Pair(
            HttpStatusCode.OK,
            buildJsonObject {}
        )
    }

    suspend fun handleGetReaderIdentities(
        request: JsonObject,
    ): Pair<HttpStatusCode, JsonObject> {
        val nonceTable = getStorageTable(nonceTableSpec)
        val clientTable = getStorageTable(clientTableSpec)
        val id = request["registrationId"]!!.jsonPrimitive.content
        val encodedClientData = clientTable.get(key = id)
        if (encodedClientData == null) {
            // Return a 404 here to convey to the client that we don't know this registration ID. This is
            // helpful for the client because they can go ahead and re-register instead of just failing in
            // perpetuity. This is helpful for example if all storage for a server is deleted, that way
            // all clients will just re-register and there will be no loss in service.
            //
            return Pair(
                HttpStatusCode.NotFound,
                buildJsonObject {}
            )
        }
        val clientData = ClientData.fromCbor(encodedClientData.toByteArray())

        val nonceBase64Url = request["nonce"]!!.jsonPrimitive.content
        if (nonceTable.get(key = nonceBase64Url) == null) {
            throw IllegalArgumentException("Unknown nonce")
        }
        val nonce = nonceBase64Url.fromBase64Url()

        val deviceAssertionBase64Url = request["deviceAssertion"]!!.jsonPrimitive.content
        val deviceAssertion = DeviceAssertion.fromCbor(deviceAssertionBase64Url.fromBase64Url())
        check(deviceAssertion.assertion is AssertionNonce)
        check((deviceAssertion.assertion as AssertionNonce).nonce == ByteString(nonce))
        clientData.deviceAttestation.validateAssertion(deviceAssertion)
        if (clientData.signedInGoogleUser == null) {
            throw IllegalStateException("User isn't signed in")
        }

        val identities = getReaderIdentitiesForUser(clientData.signedInGoogleUser!!)
        return Pair(
            HttpStatusCode.OK,
            buildJsonObject {
                putJsonArray("entries") {
                    for (identity in identities) {
                        addJsonObject {
                            put("id", identity.id)
                            put("displayName", identity.displayName)
                            identity.displayIcon?.let {
                                put("displayIcon", it.toByteArray().toBase64Url())
                            }
                        }
                    }
                }
            }
        )
    }

    companion object {
        private val nonceTableSpec = StorageTableSpec(
            name = "ReaderBackendNonces",
            supportPartitions = false,
            supportExpiration = true
        )

        private val NONCE_EXPIRATION_TIME = 5.minutes

        private val clientTableSpec = StorageTableSpec(
            name = "ReaderBackendClients",
            supportPartitions = false,
            supportExpiration = false,   // TODO: maybe consider using expiration
            schemaVersion = ClientData.SCHEMA_VERSION
        )
    }

}