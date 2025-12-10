package org.multipaz.identityreader

import kotlinx.io.bytestring.ByteString
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.X509CertChain

class WrongPassphraseException(message: String, cause: Throwable): Exception(message, cause)

/**
 * Extracts a private key and certificate chain from a PKCS#12 file.
 *
 * @param pkcs12 the contents of the PKCS#12 file.
 * @param passphrase the passphrase used to protect the file.
 * @throws WrongPassphraseException if the wrong passphrase was supplied.
 * @throws IllegalArgumentException if the given contents does not appear to be from a PKCS#12 file.
 */
expect fun parsePkcs12(pkcs12: ByteString, passphrase: String): Pair<EcPrivateKey, X509CertChain>