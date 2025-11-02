package org.multipaz.identityreader

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.alexzhirkevich.compottie.LottieCompositionSpec
import io.github.alexzhirkevich.compottie.animateLottieCompositionAsState
import io.github.alexzhirkevich.compottie.rememberLottieComposition
import io.github.alexzhirkevich.compottie.rememberLottiePainter
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.io.bytestring.ByteString
import multipazidentityreader.composeapp.generated.resources.Res
import org.multipaz.cbor.Cbor
import org.multipaz.claim.MdocClaim
import org.multipaz.compose.camera.Camera
import org.multipaz.compose.camera.CameraCaptureResolution
import org.multipaz.compose.camera.CameraFrame
import org.multipaz.compose.camera.CameraSelection
import org.multipaz.compose.cropRotateScaleImage
import org.multipaz.compose.decodeImage
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.facedetection.DetectedFace
import org.multipaz.facedetection.FaceLandmarkType
import org.multipaz.facedetection.detectFaces
import org.multipaz.facematch.FaceMatchLiteRtModel
import org.multipaz.facematch.getFaceEmbeddings
import org.multipaz.mdoc.response.DeviceResponseParser
import org.multipaz.trustmanagement.TrustManager
import org.multipaz.trustmanagement.TrustPoint
import org.multipaz.util.Logger
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.time.Clock
import kotlin.time.Instant

@Composable
fun ShowResultsScreen(
    readerQuery: ReaderQuery,
    readerModel: ReaderModel,
    documentTypeRepository: DocumentTypeRepository,
    issuerTrustManager: TrustManager,
    onBackPressed: () -> Unit,
    onShowDetailedResults: (() -> Unit)?
) {
    val coroutineScope = rememberCoroutineScope()
    val documents = remember { mutableStateOf<List<MdocDocument>?>(null) }
    val verificationError = remember { mutableStateOf<Throwable?>(null) }
    print("onShowDetailedResults: $onShowDetailedResults foo")

    LaunchedEffect(Unit) {
        if (readerModel.error == null) {
            coroutineScope.launch {
                val now = Clock.System.now()
                try {
                    documents.value =
                        parseResponse(now, readerModel, documentTypeRepository, issuerTrustManager)
                } catch (e: Throwable) {
                    verificationError.value = e
                }
            }
        }
    }

    Scaffold(
        topBar = {
            AppBar(
                onBackPressed = onBackPressed,
            )
        },
    ) { innerPadding ->
        Surface(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            color = MaterialTheme.colorScheme.background
        ) {
            if (readerModel.error != null) {
                ShowResultsScreenFailed(
                    message = "Something went wrong",
                    secondaryMessage = null,
                    onShowDetailedResults = onShowDetailedResults
                )
            } else {
                if (documents.value == null && verificationError.value == null) {
                    ShowResultsScreenValidating()
                } else if (verificationError.value != null) {
                    ShowResultsScreenFailed(
                        message = "Document verification failed",
                        secondaryMessage = "The returned document is from an unknown issuer",
                        onShowDetailedResults = onShowDetailedResults
                    )
                } else {
                    if (documents.value!!.size == 0) {
                        ShowResultsScreenFailed(
                            message = "No documents returned",
                            secondaryMessage = null,
                            onShowDetailedResults = onShowDetailedResults
                        )
                    } else {
                        ShowResultsScreenSuccess(
                            readerQuery = readerQuery,
                            documents = documents.value!!,
                            onShowDetailedResults = onShowDetailedResults
                        )
                    }
                }
            }
        }
    }
}

private data class MdocDocument(
    val docType: String,
    val msoValidFrom: Instant,
    val msoValidUntil: Instant,
    val msoSigned: Instant,
    val msoExpectedUpdate: Instant?,
    val namespaces: List<MdocNamespace>,
    val trustPoint: TrustPoint
)

private data class MdocNamespace(
    val name: String,
    val dataElements: Map<String, MdocClaim>
)

// Throws IllegalArgumentException if validity checks fail
//
//
private suspend fun parseResponse(
    now: Instant,
    readerModel: ReaderModel,
    documentTypeRepository: DocumentTypeRepository,
    issuerTrustManager: TrustManager
): List<MdocDocument> {
    val parser = DeviceResponseParser(
        encodedDeviceResponse = readerModel.result!!.encodedDeviceResponse!!.toByteArray(),
        encodedSessionTranscript = readerModel.result!!.encodedSessionTranscript.toByteArray()
    )
    parser.setEphemeralReaderKey(readerModel.result!!.eReaderKey)
    val deviceResponse = parser.parse()

    val readerDocuments = mutableListOf<MdocDocument>()
    for (document in deviceResponse.documents) {
        // TODO: validity checks and throw if not met
        require(document.deviceSignedAuthenticated) { "Device Authentication failure" }
        require(document.issuerSignedAuthenticated) { "Issuer Authentication failure" }
        require(now >= document.validityInfoValidFrom && now <= document.validityInfoValidUntil) {
            "Document is not valid at this point"
        }
        val trustResult =
            issuerTrustManager.verify(document.issuerCertificateChain.certificates, now)
        require(trustResult.isTrusted) { "Document issuer isn't trusted" }

        val mdocType =
            documentTypeRepository.getDocumentTypeForMdoc(document.docType)?.mdocDocumentType
        val resultNs = mutableListOf<MdocNamespace>()
        for (namespace in document.issuerNamespaces) {
            val resultDataElements = mutableMapOf<String, MdocClaim>()

            val mdocNamespace = if (mdocType != null) {
                mdocType.namespaces.get(namespace)
            } else {
                // Some DocTypes not known by [documentTypeRepository] - could be they are
                // private or was just never added - may use namespaces from existing
                // DocTypes... support that as well.
                //
                documentTypeRepository.getDocumentTypeForMdocNamespace(namespace)
                    ?.mdocDocumentType?.namespaces?.get(namespace)
            }

            for (dataElement in document.getIssuerEntryNames(namespace)) {
                val value = document.getIssuerEntryData(namespace, dataElement)
                val mdocDataElement = mdocNamespace?.dataElements?.get(dataElement)
                resultDataElements[dataElement] = MdocClaim(
                    displayName = mdocDataElement?.attribute?.displayName ?: dataElement,
                    attribute = mdocDataElement?.attribute,
                    namespaceName = namespace,
                    dataElementName = dataElement,
                    value = Cbor.decode(value)
                )
            }
            resultNs.add(MdocNamespace(namespace, resultDataElements))
        }
        readerDocuments.add(
            MdocDocument(
                docType = document.docType,
                msoValidFrom = document.validityInfoValidFrom,
                msoValidUntil = document.validityInfoValidUntil,
                msoSigned = document.validityInfoSigned,
                msoExpectedUpdate = document.validityInfoExpectedUpdate,
                namespaces = resultNs,
                trustPoint = trustResult.trustPoints[0]
            )
        )
    }
    return readerDocuments
}

@Composable
private fun ShowResultsScreenValidating() {
    val coroutineScope = rememberCoroutineScope()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Validating documents",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

        }
    }
}

@Composable
private fun ShowResultsScreenFailed(
    message: String,
    secondaryMessage: String?,
    onShowDetailedResults: (() -> Unit)?
) {
    val errorComposition by rememberLottieComposition {
        LottieCompositionSpec.JsonString(
            Res.readBytes("files/error_animation.json").decodeToString()
        )
    }
    val errorProgress by animateLottieCompositionAsState(
        composition = errorComposition,
    )

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = rememberLottiePainter(
                    composition = errorComposition,
                    progress = { errorProgress },
                ),
                contentDescription = null,
                modifier = Modifier.size(200.dp)
                    .let {
                        if (onShowDetailedResults != null) {
                            it.combinedClickable(
                                onClick = {},
                                onDoubleClick = { onShowDetailedResults() }
                            )
                        } else it
                    },
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = message,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            if (secondaryMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = secondaryMessage,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

        }
    }
}

@Composable
private fun ShowResultsScreenSuccess(
    readerQuery: ReaderQuery,
    documents: List<MdocDocument>,
    onShowDetailedResults: (() -> Unit)?
) {
    val successComposition by rememberLottieComposition {
        LottieCompositionSpec.JsonString(
            Res.readBytes("files/success_animation.json").decodeToString()
        )
    }
    val successProgress by animateLottieCompositionAsState(
        composition = successComposition,
    )

    // For now we only consider the first document...
    val document = documents[0]

    val scrollState = rememberScrollState()
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (document.trustPoint.metadata.testOnly) {
                Text(
                    text = "TEST DATA\nDO NOT USE",
                    textAlign = TextAlign.Center,
                    lineHeight = 1.25.em,
                    color = Color(red = 255, green = 128, blue = 128, alpha = 192),
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    style = TextStyle(
                        fontSize = 30.sp,
                        shadow = Shadow(
                            color = Color.Black,
                            offset = Offset(0f, 0f),
                            blurRadius = 2f
                        ),
                    ),
                )
            }

            when (readerQuery) {
                ReaderQuery.AGE_OVER_18 -> {
                    ShowAgeOver(
                        age = 18,
                        document = document,
                        onShowDetailedResults = onShowDetailedResults
                    )
                }

                ReaderQuery.AGE_OVER_21 -> {
                    ShowAgeOver(
                        age = 21,
                        document = document,
                        onShowDetailedResults = onShowDetailedResults
                    )
                }

                ReaderQuery.IDENTIFICATION -> {
                    ShowIdentification(
                        document = document,
                        onShowDetailedResults = onShowDetailedResults
                    )
                }
            }
        }
    }
}

@Composable
private fun ShowAgeOver(
    age: Int,
    document: MdocDocument,
    onShowDetailedResults: (() -> Unit)?
) {
    var faceMatchLiteRtModel: FaceMatchLiteRtModel? = null

    runBlocking {
        val modelData = ByteString(Res.readBytes("files/facenet_512.tflite"))
        faceMatchLiteRtModel =
            FaceMatchLiteRtModel(modelData, imageSquareSize = 160, embeddingsArraySize = 512)
    }

    val portraitBitmap = remember { getPortraitBitmap(document) }
    val mdlNameSpace = document.namespaces.find { it.name == DrivingLicense.MDL_NAMESPACE }
    val ageOver = mdlNameSpace?.dataElements?.get("age_over_${age}")?.value?.asBoolean
    val (message, animationFile) = if (ageOver != null && ageOver == true) {
        Pair("This person is $age or older", "files/success_animation.json")
    } else if (ageOver != null) {
        Pair("This person is NOT $age or older", "files/error_animation.json")
    } else {
        Pair("Unable to determine if this person is $age or older", "files/error_animation.json")
    }

    var showFaceVerification by remember { mutableStateOf(false) }
    var similarity by remember { mutableStateOf(0f) }

    val composition by rememberLottieComposition {
        LottieCompositionSpec.JsonString(
            Res.readBytes(animationFile).decodeToString()
        )
    }
    val progress by animateLottieCompositionAsState(
        composition = composition,
    )

    Image(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp).padding(16.dp)
            .clickable {
                showFaceVerification = true
            }
            .let {
                println("onShowDetailedResults: $onShowDetailedResults")
                if (onShowDetailedResults != null) {
                    println("foo")
                    it.combinedClickable(
                        onClick = {},
                        onDoubleClick = { onShowDetailedResults() }
                    )
                } else it
            },
        bitmap = portraitBitmap!!,
        contentDescription = null
    )

    if (showFaceVerification) {
        Text("Similarity: ${(similarity * 100).roundToInt()}%")

        Camera(
            modifier = Modifier
                .fillMaxSize(0.5f)
                .padding(64.dp),
            cameraSelection = CameraSelection.DEFAULT_FRONT_CAMERA,
            captureResolution = CameraCaptureResolution.MEDIUM,
            showCameraPreview = true,
        ) { incomingVideoFrame: CameraFrame ->

            val faces: List<DetectedFace>? = detectFaces(incomingVideoFrame)

            if (faces == null || faces.isEmpty()) {
                similarity = 0f;
            } else {
                val faceImage =
                    extractFaceBitmap(
                        incomingVideoFrame,
                        faces[0], // assuming only one face exists for simplicity
                        faceMatchLiteRtModel!!.imageSquareSize
                    )

                val faceInsetsForDetectedFace =
                    getFaceEmbeddings(faceImage, faceMatchLiteRtModel)
                val portriatEmbedding = getFaceEmbeddings(portraitBitmap, faceMatchLiteRtModel)

                if (faceInsetsForDetectedFace != null && portriatEmbedding != null) {
                    similarity = faceInsetsForDetectedFace.calculateSimilarity(
                        portriatEmbedding
                    )

                    similarity = max(similarity, 0f)
                }
            }
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = rememberLottiePainter(
                composition = composition,
                progress = { progress },
            ),
            contentDescription = null,
            modifier = Modifier.size(50.dp)
        )
        Text(
            text = message,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun ShowIdentification(
    document: MdocDocument,
    onShowDetailedResults: (() -> Unit)?
) {
    val portraitBitmap = remember { getPortraitBitmap(document) }
    val mdlNameSpace = document.namespaces.find { it.name == DrivingLicense.MDL_NAMESPACE }

    val composition by rememberLottieComposition {
        LottieCompositionSpec.JsonString(
            Res.readBytes("files/success_animation.json").decodeToString()
        )
    }
    val progress by animateLottieCompositionAsState(
        composition = composition,
    )

    Image(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp)
            .padding(16.dp)
            .let {
                if (onShowDetailedResults != null) {
                    it.combinedClickable(
                        onClick = {},
                        onDoubleClick = { onShowDetailedResults() }
                    )
                } else it
            },
        bitmap = portraitBitmap!!,
        contentDescription = null
    )
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(
            painter = rememberLottiePainter(
                composition = composition,
                progress = { progress },
            ),
            contentDescription = null,
            modifier = Modifier.size(50.dp)
        )
        Text(
            text = "Identity data verified",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape = RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        for (namespace in document.namespaces) {
            for ((dataElementName, dataElement) in namespace.dataElements) {
                val key = if (dataElement.attribute != null) {
                    dataElement.attribute!!.displayName
                } else {
                    dataElementName
                }
                val value = dataElement.render(TimeZone.currentSystemDefault())

                if (namespace.name == DrivingLicense.MDL_NAMESPACE && dataElementName == "portrait") {
                    continue
                }

                KeyValuePairText(key, value)
            }
        }
    }
}

@Composable
private fun KeyValuePairText(
    keyText: String,
    valueText: String
) {
    Column(
        Modifier
            .padding(8.dp)
            .fillMaxWidth()
    ) {
        Text(
            text = keyText,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = valueText,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private fun getPortraitBitmap(document: MdocDocument): ImageBitmap? {
    if (document.docType != DrivingLicense.MDL_DOCTYPE) {
        return null
    }
    val mdlNamespace = document.namespaces.find { it.name == DrivingLicense.MDL_NAMESPACE }
    if (mdlNamespace == null) {
        return null
    }
    val portraitClaim = mdlNamespace.dataElements["portrait"]
    if (portraitClaim == null) {
        return null
    }
    return decodeImage(portraitClaim.value.asBstr)
}

@Composable
private fun ShowResultDocument(
    document: MdocDocument,
) {
    val portraitBitmap = remember { getPortraitBitmap(document) }

    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .verticalScroll(scrollState)
            .padding(8.dp),
    ) {
        if (portraitBitmap != null) {
            Image(
                modifier = Modifier.fillMaxWidth().height(300.dp).padding(16.dp),
                bitmap = portraitBitmap,
                contentDescription = null
            )
        }

        for (namespace in document.namespaces) {
            for ((dataElementName, dataElement) in namespace.dataElements) {
                val key = if (dataElement.attribute != null) {
                    dataElement.attribute!!.displayName
                } else {
                    dataElementName
                }
                val value = dataElement.render(TimeZone.currentSystemDefault())

                if (portraitBitmap != null && namespace.name == DrivingLicense.MDL_NAMESPACE && dataElementName == "portrait") {
                    continue
                }

                KeyValuePairText(key, value)
            }
        }
    }
}

/** Cut out the face square, rotate it to level eyes line, scale to the smaller size for face matching tasks. */
private fun extractFaceBitmap(
    frameData: CameraFrame,
    face: DetectedFace,
    targetSize: Int
): ImageBitmap {
    val leftEye = face.landmarks.find { it.type == FaceLandmarkType.LEFT_EYE }
    val rightEye = face.landmarks.find { it.type == FaceLandmarkType.RIGHT_EYE }
    val mouthPosition = face.landmarks.find { it.type == FaceLandmarkType.MOUTH_BOTTOM }

    if (leftEye == null || rightEye == null || mouthPosition == null) {
        Logger.w("vishnu", "No face features for bitmap extraction.") // todo
        return frameData.cameraImage.toImageBitmap()
    }

    // Heuristic multiplier to fit the face normalized to the eyes pupilar distance.
    val faceCropFactor = 4f

    // Heuristic multiplier to offset vertically so the face is better centered within the rectangular crop.
    val faceVerticalOffsetFactor = 0.25f

    var faceCenterX = (leftEye.position.x + rightEye.position.x) / 2
    var faceCenterY = (leftEye.position.y + rightEye.position.y) / 2
    val eyeOffsetX = leftEye.position.x - rightEye.position.x
    val eyeOffsetY = leftEye.position.y - rightEye.position.y
    val eyeDistance = sqrt(eyeOffsetX * eyeOffsetX + eyeOffsetY * eyeOffsetY)
    val faceWidth = eyeDistance * faceCropFactor
    val faceVerticalOffset = eyeDistance * faceVerticalOffsetFactor
    if (frameData.isLandscape) {
        /** Required for iOS capable of upside-down face detection. */
        faceCenterY += faceVerticalOffset * (if (leftEye.position.y < mouthPosition.position.y) 1 else -1)
    } else {
        /** Required for iOS capable of upside-down face detection. */
        faceCenterX -= faceVerticalOffset * (if (leftEye.position.x < mouthPosition.position.x) -1 else 1)
    }
    val eyesAngleRad = atan2(eyeOffsetY, eyeOffsetX)
    val eyesAngleDeg = eyesAngleRad * 180.0 / PI // Convert radians to degrees
    val totalRotationDegrees = 180 - eyesAngleDeg

    // Call platform dependent bitmap transformation.
    return cropRotateScaleImage(
        frameData = frameData, // Platform-specific image data.
        cx = faceCenterX.toDouble(), // Point between eyes
        cy = faceCenterY.toDouble(), // Point between eyes
        angleDegrees = totalRotationDegrees, //includes the camera rotation and eyes rotation.
        outputWidthPx = faceWidth.toInt(), // Expected face width for cropping *before* final scaling.
        outputHeightPx = faceWidth.toInt(),// Expected face height for cropping *before* final scaling.
        targetWidthPx = targetSize, // Final square image size (for database saving and face matching tasks).
    )
}
