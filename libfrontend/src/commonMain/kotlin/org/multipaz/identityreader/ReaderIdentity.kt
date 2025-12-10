package org.multipaz.identityreader

import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.annotation.CborSerializable

@CborSerializable
data class ReaderIdentity(
    val id: String,
    val displayName: String,
    val displayIcon: ByteString?,
) {
    companion object
}