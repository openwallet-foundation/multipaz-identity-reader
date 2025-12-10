package org.multipaz.identityreader

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.DiagnosticOption
import org.multipaz.compose.datetime.formattedDateTime
import org.multipaz.compose.decodeImage
import org.multipaz.crypto.Algorithm
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.X509CertChain
import org.multipaz.documenttype.DocumentAttributeType
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.documenttype.MdocDocumentType
import org.multipaz.mdoc.devicesigned.DeviceAuth
import org.multipaz.mdoc.response.DeviceResponse
import org.multipaz.trustmanagement.TrustManager
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

private sealed class Value

private data class ValueText(
    val text: String
): Value()

private data class ValueImage(
    val text: String?,
    val image: ImageBitmap
): Value()

private data class ValueDateTime(
    val dateTime: Instant
): Value()

private data class ValueDuration(
    val duration: Duration
): Value()

private data class ValueCertChain(
    val certChain: X509CertChain
): Value()

private data class Line(
    val header: String,
    val value: Value,
    val onClick: (() -> Unit)? = null
)

private data class Section(
    val header: String,
    val lines: List<Line>
)

private data class VerificationResult(
    val sections: List<Section>
)

@Composable
fun ShowDetailedResultsScreen(
    readerQuery: ReaderQuery,
    readerModel: ReaderModel,
    documentTypeRepository: DocumentTypeRepository,
    issuerTrustManager: TrustManager,
    onBackPressed: () -> Unit,
    onShowCertificateChain: (certChain: X509CertChain) -> Unit,
) {
    val coroutineScope = rememberCoroutineScope()
    val verificationError = remember { mutableStateOf<Throwable?>(null) }
    val verficationResult = remember { mutableStateOf<VerificationResult?>(null) }
    val scrollState = rememberScrollState()

    LaunchedEffect(Unit) {
        if (readerModel.error == null) {
            coroutineScope.launch {
                val now = Clock.System.now()
                try {
                    verficationResult.value = parseResponse(
                        now = now,
                        readerModel = readerModel,
                        documentTypeRepository = documentTypeRepository,
                        issuerTrustManager = issuerTrustManager,
                        onShowCertificateChain = onShowCertificateChain
                    )
                } catch (e: Throwable) {
                    verificationError.value = e
                }
            }
        } else {
            verificationError.value = readerModel.error
        }
    }

    Scaffold(
        topBar = {
            AppBar(
                title = AnnotatedString(text = "Detailed Results"),
                onBackPressed = onBackPressed,
            )
        },
    ) { innerPadding ->
        Surface(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth().padding(8.dp)
                    .verticalScroll(scrollState)
            ) {
                if (verificationError.value != null) {
                    Text(text = verificationError.value!!.message ?: "Failed")
                } else if (verficationResult.value != null) {
                    for (section in verficationResult.value!!.sections) {
                        val entries = mutableListOf<@Composable () -> Unit>()
                        for (line in section.lines) {
                            entries.add {
                                Column(
                                    Modifier.fillMaxWidth().padding(8.dp)
                                        .clickable(
                                            enabled = line.onClick != null
                                        ) {
                                            line.onClick!!.invoke()
                                        }
                                ) {
                                    Text(
                                        text = line.header,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    when (line.value) {
                                        is ValueDateTime -> {
                                            Text(
                                                text = formattedDateTime(instant = line.value.dateTime)
                                            )
                                        }
                                        is ValueDuration -> {
                                            Text(
                                                text = "${line.value.duration.inWholeMilliseconds} msec"
                                            )
                                        }
                                        is ValueImage -> {
                                            line.value.text?.let {
                                                Text(text = line.value.text)
                                                Spacer(modifier = Modifier.height(8.dp))
                                            }
                                            Image(
                                                bitmap = line.value.image,
                                                modifier = Modifier.size(200.dp),
                                                contentDescription = null
                                            )
                                        }
                                        is ValueCertChain -> {
                                            Text(text = "Click to view")
                                        }

                                        is ValueText -> {
                                            Text(text = line.value.text)
                                        }
                                    }
                                }
                            }
                        }
                        EntryList(
                            title = section.header,
                            entries = entries
                        )
                    }
                }
            }
        }
    }
}

private suspend fun parseResponse(
    now: Instant,
    readerModel: ReaderModel,
    documentTypeRepository: DocumentTypeRepository,
    issuerTrustManager: TrustManager,
    onShowCertificateChain: (certChain: X509CertChain) -> Unit
): VerificationResult {
    val sections = mutableListOf<Section>()
    var lines: MutableList<Line>
    val result = readerModel.result!!

    lines = mutableListOf()
    lines.add(Line("Engagement mechanism", ValueText(result.engagementType.description)))
    lines.add(Line("Time from engagement received to request sent",
        ValueDuration(result.durationEngagementReceivedToRequestSent)))
    lines.add(Line("Time from request sent to response received",
        ValueDuration(result.durationRequestSentToResponseReceived)))
    lines.add(Line("Time spent scanning",
        result.durationScanningTime?.let {
            ValueDuration(it)
        } ?: ValueText("N/A")
    ))
    lines.add(Line("Total duration",
        ValueDuration(result.durationEngagementReceivedToRequestSent + result.durationRequestSentToResponseReceived)))
    lines.add(Line("Connection method",
        ValueText(result.connectionMethod.toString())))
    lines.add(Line("Session encryption curve", ValueText(result.eReaderKey.curve.name)))
    result.status?.let {
        lines.add(Line("Status", ValueText("$it")))
    } ?: {
        lines.add(Line("Status", ValueText("Not set")))
    }
    result.encodedDeviceResponse?.let {
        lines.add(Line("Device Response", ValueText("${it.size} bytes")))
    } ?: {
        lines.add(Line("Device Response", ValueText("Not set")))
    }
    sections.add(Section(
        header = "Response",
        lines = lines
    ))

    if (result.encodedDeviceResponse != null) {
        val deviceResponse = DeviceResponse.fromDataItem(Cbor.decode(result.encodedDeviceResponse!!.toByteArray()))
        deviceResponse.verify(
            sessionTranscript = Cbor.decode(result.encodedSessionTranscript.toByteArray()),
            eReaderKey = AsymmetricKey.AnonymousExplicit(
                privateKey = result.eReaderKey,
            )
        )

        deviceResponse.documents.forEachIndexed { documentIndex, document ->
            val mdocDocumentType =
                documentTypeRepository.getDocumentTypeForMdoc(document.docType)?.mdocDocumentType
            lines = mutableListOf()
            lines.add(Line("DocType", ValueText(document.docType)))
            lines.add(Line("DeviceKey curve", ValueText(document.mso.deviceKey.curve.name)))
            val deviceSignedResult =
                when (document.deviceAuth) {
                    is DeviceAuth.Ecdsa -> "Authenticated (ECDSA signature)"
                    is DeviceAuth.Mac -> "Authenticated (MAC)"
                }
            lines.add(Line("Device Signed", ValueText(deviceSignedResult)))
            lines.add(Line("Issuer DS curve", ValueText(document.issuerCertChain.certificates.first().ecPublicKey.curve.name)))
            lines.add(Line("Issuer Signed", ValueText("Authenticated")))
            val trustResult =
                issuerTrustManager.verify(document.issuerCertChain.certificates, now)
            if (trustResult.isTrusted) {
                val tpName =
                    trustResult.trustPoints.first().metadata?.displayName?.let { " ($it)" } ?: ""
                lines.add(Line("Issuer Trusted", ValueText("Yes$tpName")))
            } else {
                lines.add(Line("Issuer Trusted", ValueText("No")))
            }
            lines.add(
                Line(
                    "Issuer Certificate Chain",
                    ValueCertChain(document.issuerCertChain),
                    { onShowCertificateChain(document.issuerCertChain) }
                )
            )
            lines.add(Line("Valid from", ValueDateTime(document.mso.validFrom)))
            lines.add(Line("Valid until", ValueDateTime(document.mso.validUntil)))
            lines.add(Line("Signed at", ValueDateTime(document.mso.signedAt)))
            document.mso.expectedUpdate?.let {
                lines.add(Line("Expected update", ValueDateTime(it)))
            } ?: {
                lines.add(Line("Expected update", ValueText("Not set")))
            }

            for ((namespace, items) in document.issuerNamespaces.data) {
                lines.add(Line("Namespace", ValueText(namespace)))
                for ((dataElement, item) in items) {
                    lines.add(
                        lineForDataElement(
                            mdocDocumentType = mdocDocumentType,
                            namespace = namespace,
                            dataElement = dataElement,
                            dataElementValue = item.dataElementValue
                        )
                    )
                }
            }

            for ((namespace, items) in document.deviceNamespaces.data) {
                lines.add(Line("Namespace (Device Signed)", ValueText(namespace)))
                for ((dataElement, dataElementValue) in items) {
                    lines.add(
                        lineForDataElement(
                            mdocDocumentType = mdocDocumentType,
                            namespace = namespace,
                            dataElement = dataElement,
                            dataElementValue = dataElementValue
                        )
                    )
                }
            }

            sections.add(
                Section(
                    header = "Document ${documentIndex + 1} of ${deviceResponse.documents.size}",
                    lines = lines
                )
            )
        }
    }
    return VerificationResult(sections)
}

private fun lineForDataElement(
    mdocDocumentType: MdocDocumentType?,
    namespace: String,
    dataElement: String,
    dataElementValue: DataItem
): Line {
    val mdocDataElement = mdocDocumentType?.namespaces?.get(namespace)?.dataElements?.get(dataElement)
    if (mdocDataElement != null) {
        if (mdocDataElement.attribute.type == DocumentAttributeType.Picture) {
            val text = mdocDataElement.renderValue(dataElementValue)
            val image = decodeImage(dataElementValue.asBstr)
            return Line(mdocDataElement.attribute.displayName, ValueImage(text, image))
        } else {
            val text = mdocDataElement.renderValue(dataElementValue)
            return Line(mdocDataElement.attribute.displayName, ValueText(text))
        }
    } else {
        val text = Cbor.toDiagnostics(dataElementValue, setOf(DiagnosticOption.BSTR_PRINT_LENGTH))
        return Line(dataElement, ValueText(text))
    }
}