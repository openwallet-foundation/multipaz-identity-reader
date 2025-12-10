package org.multipaz.identityreader

import kotlinx.io.bytestring.ByteString
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.X509CertChain

actual fun parsePkcs12(pkcs12: ByteString, passphrase: String): Pair<EcPrivateKey, X509CertChain> = TODO()
