package org.multipaz.identityreader

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.annotation.CborSerializable

@CborSerializable
data class SignInWithGoogleUserData(
    val id: String,
    val givenName: String?,
    val familyName: String?,
    val displayName: String?,
    val profilePicture: ByteString?
) {
    companion object
}