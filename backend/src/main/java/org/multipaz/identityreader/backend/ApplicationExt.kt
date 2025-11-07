package org.multipaz.identityreader.backend


import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.util.pipeline.PipelineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.datetime.DateTimePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.io.bytestring.ByteString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.multipaz.asn1.ASN1Integer
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.identityreader.BuildConfig
import org.multipaz.identityreader.libbackend.ReaderBackend
import org.multipaz.identityreader.libbackend.ReaderIdentity
import org.multipaz.identityreader.libbackend.SignedInGoogleUser
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.rpc.backend.BackendEnvironment
import org.multipaz.rpc.backend.Configuration
import org.multipaz.rpc.backend.getTable
import org.multipaz.rpc.handler.InvalidRequestException
import org.multipaz.server.ServerConfiguration
import org.multipaz.server.ServerEnvironment
import org.multipaz.server.getServerIdentity
import org.multipaz.trustmanagement.TrustEntry
import org.multipaz.trustmanagement.TrustEntryX509Cert
import org.multipaz.trustmanagement.TrustMetadata
import org.multipaz.util.Logger
import org.multipaz.util.fromBase64Url
import kotlin.time.Clock
import kotlin.time.Instant


private const val TAG = "ApplicationExt"

private typealias RequestWrapper =
        suspend PipelineContext<*,ApplicationCall>.(
            suspend PipelineContext<*,ApplicationCall>.() -> Unit) -> Unit

/**
 * Defines server endpoints for HTTP GET and POST.
 */
fun Application.configureRouting(configuration: ServerConfiguration) {
    // TODO: when https://youtrack.jetbrains.com/issue/KTOR-8088 is resolved, there
    //  may be a better way to inject our custom wrapper for all request handlers
    //  (instead of doing it for every request like we do today).
    val env = ServerEnvironment.create(configuration)
    val server = createServer(env)
    val runRequest: RequestWrapper = { body ->
        val self = this
        withContext(env.await()) {
            try {
                body.invoke(self)
            } catch (err: CancellationException) {
                throw err
            } catch (err: Throwable) {
                Logger.e(TAG, "Error", err)
                err.printStackTrace()
                call.respondText(
                    status = HttpStatusCode.InternalServerError,
                    text = err::class.simpleName + ": " + err.message,
                    contentType = ContentType.Text.Plain
                )
            }
        }
    }
    routing {
        get("/") { runRequest { fetchResource(call, "index.html") } }
        get("/readerRootCert") {
            runRequest {
                handleGetReaderRootCert(backendEnvironmentDeferred = env, call = call, forTrustedDevices = true)
            }
        }
        get("/readerRootCertUntrustedDevices") {
            runRequest {
                handleGetReaderRootCert(backendEnvironmentDeferred = env, call = call, forTrustedDevices = false)
            }
        }
        get("/{path...}") {
            runRequest { fetchResource(call, call.parameters["path"]!!) }
        }
        post("/{command}") {
            runRequest {
                val command = call.parameters["command"]!!
                val requestData = call.receive<ByteArray>()
                val requestObj = Json.decodeFromString<JsonObject>(requestData.decodeToString())
                val (responseStatusCode, responseObj) = when (command) {
                    "getNonce" -> server.await().handleGetNonce(requestObj)
                    "register" -> server.await().handleRegister(requestObj)
                    "certifyKeys" -> server.await().handleCertifyKeys(requestObj)
                    "getIssuerList" -> server.await().handleGetIssuerList(requestObj)
                    "signIn" -> server.await().handleSignIn(requestObj)
                    "signOut" -> server.await().handleSignOut(requestObj)
                    "getReaderIdentities" -> server.await().handleGetReaderIdentities(requestObj)
                    else -> throw InvalidRequestException("Unknown command: $command")
                }
                call.respondText(
                    status = responseStatusCode,
                    contentType = ContentType.Application.Json,
                    text = Json.encodeToString(responseObj)
                )
            }
        }
    }
}

private suspend fun handleGetReaderRootCert(
    backendEnvironmentDeferred: Deferred<BackendEnvironment>,
    call: ApplicationCall,
    forTrustedDevices: Boolean
) {
    val backendEnvironment = backendEnvironmentDeferred.await()
    withContext(currentCoroutineContext() + backendEnvironment) {
        val identity = getReaderRootIdentity(forTrustedDevices = forTrustedDevices)
        call.respondText(
            contentType = ContentType.Text.Plain,
            text = identity.certChain.certificates.joinToString { it.toPem() }
        )
    }
}

private suspend fun googleIdTokenVerifier(
    googleIdTokenString: String,
): Pair<String, SignedInGoogleUser> {
    val transport = GoogleNetHttpTransport.newTrustedTransport()
    val jsonFactory = GsonFactory.getDefaultInstance()
    val verifier = GoogleIdTokenVerifier.Builder(
        transport,
        jsonFactory
    )
        .setAudience(listOf(BuildConfig.IDENTITY_READER_BACKEND_CLIENT_ID))
        .build()

    val idToken = verifier.verify(googleIdTokenString)
    if (idToken == null) {
        throw IllegalStateException("Error validating ID token")
    }
    val payload = idToken.payload
    return Pair(
        payload.nonce,
        SignedInGoogleUser(
            id = payload.subject,
            email = payload.email,
            displayName = payload.get("name") as String?,
            profilePictureUri = payload.get("picture") as String?,
        )
    )
}

private suspend fun getReaderIdentitiesForUser(
    user: SignedInGoogleUser
): List<ReaderIdentity> {
    val configuration = BackendEnvironment.getInterface(Configuration::class)!!

    val readerIdentitiesValue = configuration.getValue("reader_identities")
    if (readerIdentitiesValue == null) {
        return emptyList()
    }
    val idToIdentity = try {
        Json.parseToJsonElement(readerIdentitiesValue).jsonArray.associate {
            it as JsonObject
            val id = it["id"]!!.jsonPrimitive.content
            val privateKey = EcPrivateKey.fromJwk(it["identity"]!!.jsonObject["jwk"]!!.jsonObject)
            val certChain = X509CertChain.fromX5c(it["identity"]!!.jsonObject["x5c"]!!)
            Pair(
                id,
                ReaderIdentity(
                    id = id,
                    displayName = it["display_name"]!!.jsonPrimitive.content,
                    displayIcon = it["display_icon"]?.jsonPrimitive?.content?.fromBase64Url()?.let {
                        ByteString(it) },
                    privateKey = privateKey,
                    certChain = certChain
                )
            )
        }
    } catch (e: Throwable) {
        e.printStackTrace()
        Logger.e(TAG, "Error parsing reader identity", e)
        return emptyList()
    }

    val id = user.email ?: user.id
    val key = "reader_identities_$id"
    val valueForUser = configuration.getValue(key)
    Logger.i(TAG, "Looking up $key")
    val value = if (valueForUser != null) {
        valueForUser
    } else {
        val valueFallback = configuration.getValue("reader_identities_fallback")
        if (valueFallback == null) {
            return emptyList()
        }
        valueFallback
    }
    val identityIds = Json.parseToJsonElement(value).jsonArray.map {
        it.jsonPrimitive.content
    }

    return identityIds.mapNotNull { idToIdentity[it] }
}

private fun createServer(
    backendEnvironmentDeferred: Deferred<BackendEnvironment>,
): Deferred<ReaderBackend> = CoroutineScope(Dispatchers.Default).async {
    val backendEnvironment = backendEnvironmentDeferred.await()
    withContext(coroutineContext + backendEnvironment) {
        val settings = Settings(BackendEnvironment.getInterface(Configuration::class)!!)

        val readerRoot = getReaderRootIdentity(forTrustedDevices = true)
        val readerRootForUntrustedDevices = getReaderRootIdentity(forTrustedDevices = false)
        val (issuerTrustListVersion, issuerTrustList) = getTrustedIssuers()
        ReaderBackend(
            readerRootKeyForUntrustedDevices = readerRootForUntrustedDevices.privateKey,
            readerRootCertChainForUntrustedDevices = readerRootForUntrustedDevices.certChain,
            readerRootKey = readerRoot.privateKey,
            readerRootCertChain = readerRoot.certChain,
            readerCertDuration = DateTimePeriod(days = settings.readerCertValidityDays),
            iosReleaseBuild = settings.iosReleaseBuild,
            iosAppIdentifier = settings.iosAppIdentifier,
            androidGmsAttestation = settings.androidRequireGmsAttestation,
            androidVerifiedBootGreen = settings.androidRequireVerifiedBootGreen,
            androidAppSignatureCertificateDigests = settings.androidRequireAppSignatureCertificateDigests,
            googleIdTokenVerifier = ::googleIdTokenVerifier,
            getReaderIdentitiesForUser = ::getReaderIdentitiesForUser,
            issuerTrustListVersion = issuerTrustListVersion,
            issuerTrustList = issuerTrustList,
            getStorageTable = { spec -> BackendEnvironment.getTable(spec) },
        )
    }
}

private suspend fun getTrustedIssuers(): Pair<Long, List<TrustEntry>> {
    val configuration = BackendEnvironment.getInterface(Configuration::class)!!
    val trustedIssuersString = configuration.getValue("trusted_issuers")
    if (trustedIssuersString == null) {
        return Pair(Long.MIN_VALUE, emptyList())
    }
    val trustedIssuersObject = Json.parseToJsonElement(trustedIssuersString).jsonObject
    val version = trustedIssuersObject["version"]!!.jsonPrimitive.long
    val entries = mutableListOf<TrustEntry>()
    for (entry in trustedIssuersObject["entries"]!!.jsonArray) {
        entry as JsonObject
        val type = entry["type"]!!.jsonPrimitive.content
        when (type) {
            "iaca_certificate" -> {
                entries.add(TrustEntryX509Cert(
                    // TODO: maybe support displayIcon too
                    metadata = TrustMetadata(
                        displayName = entry["display_name"]?.jsonPrimitive?.content,
                        privacyPolicyUrl = entry["privacy_policy_url"]?.jsonPrimitive?.content,
                        testOnly = entry["test_only"]?.jsonPrimitive?.booleanOrNull ?: false
                    ),
                    certificate = X509Cert(
                        encoded = ByteString(entry["certificate"]!!.jsonPrimitive.content.fromBase64Url())
                    )
                ))
            }
            else -> {
                throw IllegalArgumentException("Unexpected entry type `$type`")
            }
        }
    }
    return Pair(version, entries)
}

private suspend fun getReaderRootIdentity(forTrustedDevices: Boolean): AsymmetricKey.X509CertifiedExplicit {
    val keyName = if (forTrustedDevices) {
        "reader_root_identity"
    } else {
        "reader_root_identity_untrusted_devices"
    }
    return BackendEnvironment.getServerIdentity(keyName) {
        val subjectAndIssuer = X500Name.fromName(
            if (forTrustedDevices) {
                "CN=Multipaz Identity Verifier Reader CA"
            } else {
                "CN=Multipaz Identity Verifier Reader CA (Untrusted Devices)"
            }
        )

        val validFrom = Instant.fromEpochSeconds(Clock.System.now().epochSeconds)
        val validUntil = validFrom.plus(DateTimePeriod(years = 5), TimeZone.currentSystemDefault())
        val serial = ASN1Integer.fromRandom(128)

        val readerRootKey = Crypto.createEcPrivateKey(EcCurve.P384)
        val readerRootCertificate =
            MdocUtil.generateReaderRootCertificate(
                readerRootKey = AsymmetricKey.AnonymousExplicit(readerRootKey),
                subject = subjectAndIssuer,
                serial = serial,
                validFrom = validFrom,
                validUntil = validUntil,
                crlUrl = "https://github.com/openwallet-foundation-labs/identity-credential/crl"
            )
        AsymmetricKey.X509CertifiedExplicit(
            certChain = X509CertChain(listOf(readerRootCertificate)),
            privateKey = readerRootKey,
        )
    } as AsymmetricKey.X509CertifiedExplicit
}