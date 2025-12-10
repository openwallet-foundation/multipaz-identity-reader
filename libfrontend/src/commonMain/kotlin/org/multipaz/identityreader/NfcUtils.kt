package org.multipaz.identityreader

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.nfc.ScanMdocReaderResult
import org.multipaz.mdoc.nfc.scanMdocReader
import org.multipaz.mdoc.transport.MdocTransportFactory
import org.multipaz.mdoc.transport.MdocTransportOptions
import org.multipaz.nfc.NfcScanOptions
import org.multipaz.nfc.NfcTagReader
import kotlin.coroutines.CoroutineContext

// Have to do this b/c SKIE doesn't handle parameters with suspend lambdas
suspend fun NfcTagReader.scanNfcMdocReaderMod1(
    message: String?,
    options: MdocTransportOptions,
    transportFactory: MdocTransportFactory = MdocTransportFactory.Default,
    negotiatedHandoverConnectionMethods: List<MdocConnectionMethod>,
    nfcScanOptions: NfcScanOptions = NfcScanOptions(),
    context: CoroutineContext = Dispatchers.IO
): ScanMdocReaderResult? {
    return scanMdocReader(
        message = message,
        options = options,
        transportFactory = transportFactory,
        selectConnectionMethod = { connectionMethods -> connectionMethods.first() },
        negotiatedHandoverConnectionMethods = negotiatedHandoverConnectionMethods,
        nfcScanOptions = nfcScanOptions,
        context = context
    )
}