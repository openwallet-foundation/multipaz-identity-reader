package org.multipaz.identityreader

import android.content.pm.PackageManager
import android.os.Build
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import kotlinx.io.bytestring.ByteString
import org.multipaz.context.applicationContext
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.Crypto
import org.multipaz.util.Logger
import org.multipaz.util.toHex

private const val TAG = "SignInWithGoogle"

actual suspend fun signInWithGoogle(
    explicitSignIn: Boolean,
    serverClientId: String,
    nonce: String,
    httpClientEngineFactory: HttpClientEngineFactory<*>
): Pair<String, SignInWithGoogleUserData> {
    val credentialManager = CredentialManager.create(applicationContext)

    if (Build.VERSION.SDK_INT >= 28) {
        val pkg = applicationContext.packageManager
            .getPackageInfo(applicationContext.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        println("Num content signers: ${pkg.signingInfo!!.apkContentsSigners.size}")
        pkg.signingInfo!!.apkContentsSigners.forEach { signatureInfo ->
            println(
                "digest: ${Crypto.digest(Algorithm.INSECURE_SHA1, signatureInfo.toByteArray()).toHex(byteDivider = ":")}"
            )
        }
    }

    val signInOption = if (explicitSignIn) {
        GetSignInWithGoogleOption.Builder(
            serverClientId = serverClientId
        ).setNonce(nonce)
            .build()
    } else {
        GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(serverClientId)
            .setAutoSelectEnabled(false)
            .setNonce(nonce)
            .build()
    }

    val request: GetCredentialRequest = GetCredentialRequest.Builder()
        .addCredentialOption(signInOption)
        .build()

    try {
        val result = credentialManager.getCredential(
            request = request,
            context = applicationContext,
        )
        Logger.i(TAG, "result: $result")
        return handleSignIn(result, httpClientEngineFactory)
    } catch (e: GetCredentialCancellationException) {
        throw SignInWithGoogleDismissedException("User dismissed dialog", e)
    } catch (e: GetCredentialException) {
        Logger.e(TAG, "Error signing in", e)
        throw IllegalStateException("Error signing in", e)
    }
}

private suspend fun handleSignIn(
    result: GetCredentialResponse,
    httpClientEngineFactory: HttpClientEngineFactory<*>,
): Pair<String, SignInWithGoogleUserData> {
    // Handle the successfully returned credential.
    when (val credential = result.credential) {
        // GoogleIdToken credential
        is CustomCredential -> {
            if (credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                try {
                    // Use googleIdTokenCredential and extract the ID to validate and
                    // authenticate on your server.
                    val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                    val profilePicture = googleIdTokenCredential.profilePictureUri?.let {
                        val httpClient = HttpClient(httpClientEngineFactory) {
                            install(HttpTimeout)
                        }
                        val response = httpClient.get(it.toString())
                        ByteString(response.body<ByteArray>())
                    }
                    return Pair(
                        googleIdTokenCredential.idToken,
                        SignInWithGoogleUserData(
                            id = googleIdTokenCredential.id, // TODO: val id: String' is deprecated. Use uniqueId for the stable user identifier, or email for the user's email address.
                            givenName = googleIdTokenCredential.givenName,
                            familyName = googleIdTokenCredential.familyName,
                            displayName = googleIdTokenCredential.displayName,
                            profilePicture = profilePicture
                        )
                    )
                } catch (e: GoogleIdTokenParsingException) {
                    Logger.e(TAG, "Received an invalid google id token response", e)
                    throw IllegalStateException("Received an invalid google id token response", e)
                }
            } else {
                Logger.e(TAG, "Unexpected type of credential")
                throw IllegalStateException("Unexpected type of credential returned")
            }
        }
        else -> {
            Logger.e(TAG, "Unexpected type of credential")
            throw IllegalStateException("Unexpected type of credential returned")
        }
    }
}

actual suspend fun signInWithGoogleSignedOut() {
    val credentialManager = CredentialManager.create(applicationContext)
    credentialManager.clearCredentialState(
        ClearCredentialStateRequest(
            requestType = ClearCredentialStateRequest.TYPE_CLEAR_CREDENTIAL_STATE
        )
    )
}
