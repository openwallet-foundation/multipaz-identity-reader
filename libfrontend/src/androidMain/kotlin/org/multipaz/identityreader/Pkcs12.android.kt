package org.multipaz.identityreader

import kotlinx.io.bytestring.ByteString
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.crypto.javaPublicKey
import org.multipaz.crypto.toEcPrivateKey
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.PrivateKey

actual fun parsePkcs12(pkcs12: ByteString, passphrase: String): Pair<EcPrivateKey, X509CertChain> {
    val keyStore = KeyStore.getInstance("PKCS12")
    val bis = ByteArrayInputStream(pkcs12.toByteArray())
    try {
        keyStore.load(bis, passphrase.toCharArray())
    } catch (e: Throwable) {
        if (e.message == "stream does not represent a PKCS12 key store") {
            throw IllegalArgumentException("File does not look like a valid PKCS#12 file", e)
        } else {
            throw WrongPassphraseException("Wrong passphrase supplied", e)
        }
    }
    for (alias in keyStore.aliases()) {
        println("alias $alias")
        if (keyStore.isKeyEntry(alias)) {
            val javaCertChain = keyStore.getCertificateChain(alias)
            val certChain = X509CertChain(certificates = javaCertChain.map {
                X509Cert(encoded = ByteString(it.encoded))
            })
            val javaPrivateKey = keyStore.getKey(alias, passphrase.toCharArray()) as PrivateKey
            val privateKey = javaPrivateKey.toEcPrivateKey(
                publicKey = certChain.certificates[0].ecPublicKey.javaPublicKey,
                curve = certChain.certificates[0].ecPublicKey.curve
            )
            return Pair(privateKey, certChain)
        }
    }
    throw IllegalArgumentException("No private key found")
}
