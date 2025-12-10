package org.multipaz.identityreader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.Simple
import org.multipaz.compose.prompt.PromptDialogs
import org.multipaz.documenttype.DocumentTypeRepository
import org.multipaz.documenttype.knowntypes.DrivingLicense
import org.multipaz.documenttype.knowntypes.PhotoID
import org.multipaz.mdoc.transport.MdocTransportOptions
import org.multipaz.trustmanagement.CompositeTrustManager
import org.multipaz.trustmanagement.TrustEntryVical
import org.multipaz.trustmanagement.TrustEntryX509Cert
import org.multipaz.trustmanagement.TrustManager
import org.multipaz.trustmanagement.TrustManagerLocal
import org.multipaz.util.Logger
import org.multipaz.util.Platform
import org.multipaz.util.fromBase64Url
import org.multipaz.util.toBase64Url
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

data class UrlLaunchData(
    val url: String,
    val finish: () -> Unit,
)

/**
 * App instance.
 *
 * @param urlLaunchData if launched from an intent for mdoc://<base64-of-device-engagement>, this
 *   contains the URL and the app will start at [SelectRequestDestination]. This is useful for when
 *   being launched from the camera.
 */
class App(
    private val urlLaunchData: UrlLaunchData?
) {
    companion object {
        private const val TAG = "App"
    }

    private val promptModel = Platform.promptModel
    private var startDestination: Destination? = null
    private val readerModel = ReaderModel()
    private lateinit var documentTypeRepository: DocumentTypeRepository
    private lateinit var builtInTrustManager: TrustManagerLocal
    private lateinit var userTrustManager: TrustManagerLocal
    private lateinit var compositeTrustManager: TrustManager
    private lateinit var settingsModel: SettingsModel
    private lateinit var readerBackendClient: ReaderBackendClient

    private fun getMdocTransportOptionsForNfcEngagement() =
        MdocTransportOptions(
            bleUseL2CAP = settingsModel.bleL2capEnabled.value,
            bleUseL2CAPInEngagement = settingsModel.bleL2capInEngagementEnabled.value
        )

    private fun getMdocTransportOptionsForQrEngagement() =
        MdocTransportOptions(
            bleUseL2CAP = settingsModel.bleL2capEnabled.value,
            bleUseL2CAPInEngagement = settingsModel.bleL2capInEngagementEnabled.value
        )

    private val initLock = Mutex()
    private var initialized = false

    suspend fun initialize() {
        initLock.withLock {
            if (initialized) {
                return
            }

            settingsModel = SettingsModel.create(Platform.storage)

            startDestination = if (urlLaunchData != null) {
                val encodedDeviceEngagement =
                    ByteString(urlLaunchData.url.substringAfter("mdoc:").fromBase64Url())
                readerModel.reset()
                readerModel.setMdocTransportOptions(getMdocTransportOptionsForQrEngagement())
                readerModel.setConnectionEndpoint(
                    encodedDeviceEngagement = encodedDeviceEngagement,
                    handover = Simple.NULL,
                    existingTransport = null
                )
                SelectRequestDestination
            } else {
                null
            }

            documentTypeRepository = DocumentTypeRepository()
            documentTypeRepository.addDocumentType(DrivingLicense.getDocumentType())
            documentTypeRepository.addDocumentType(PhotoID.getDocumentType())
            // Note: builtInTrustManager will be populated at app startup, see updateBuiltInIssuers()
            //   and its call-sites
            builtInTrustManager = TrustManagerLocal(
                storage = Platform.storage,
                identifier = "builtInTrustManager",
            )
            userTrustManager = TrustManagerLocal(
                storage = Platform.storage,
                identifier = "userTrustManager",
            )
            compositeTrustManager = CompositeTrustManager(listOf(builtInTrustManager, userTrustManager))

            readerBackendClient = ReaderBackendClient(
                // Use the deployed backend by default.. replace with http://127.0.0.1:8020 or similar
                // if you are running your own backend using `./gradlew backend:run`
                //
                readerBackendUrl = BuildConfig.IDENTITY_READER_BACKEND_URL,
                //readerBackendUrl = "http://127.0.0.1:8020",
                storage = Platform.nonBackedUpStorage,
                httpClientEngineFactory = getPlatformUtils().httpClientEngineFactory,
                secureArea = Platform.getSecureArea(),
                numKeys = 10,
            )

            initialized = true
        }
    }

    private suspend fun ensureReaderKeys() {
        when (settingsModel.readerAuthMethod.value) {
            ReaderAuthMethod.CUSTOM_KEY,
            ReaderAuthMethod.NO_READER_AUTH -> {
                Logger.i(TAG, "Not using backend-signed auth so not ensuring keys")
            }
            ReaderAuthMethod.STANDARD_READER_AUTH -> {
                try {
                    readerBackendClient.getKey()
                    Logger.i(TAG, "Success ensuring reader keys")
                } catch (e: Throwable) {
                    Logger.i(TAG, "Error when ensuring reader keys: $e")
                }
            }
            ReaderAuthMethod.STANDARD_READER_AUTH_WITH_GOOGLE_ACCOUNT_DETAILS -> {
                try {
                    readerBackendClient.getKey("")
                    Logger.i(TAG, "Success ensuring reader keys w/ Google account details")
                } catch (e: Throwable) {
                    Logger.i(TAG, "Error when ensuring reader keys: $e")
                }
            }
            ReaderAuthMethod.IDENTITY_FROM_GOOGLE_ACCOUNT -> {
                val identity = settingsModel.readerAuthMethodGoogleIdentity.value
                try {
                    readerBackendClient.getKey(readerIdentityId = identity!!.id)
                    Logger.i(TAG, "Success ensuring reader keys for id ${identity.id}")
                } catch (e: Throwable) {
                    Logger.i(TAG, "Error when ensuring reader keys for id ${identity?.id}: $e")
                }
            }
        }
    }

    private suspend fun updateBuiltInIssuers() {
        try {
            val currentVersion = settingsModel.builtInIssuersVersion.value
            val getTrustedIssuerResult = readerBackendClient.getTrustedIssuers(
                currentVersion = currentVersion
            )
            if (getTrustedIssuerResult != null) {
                val version = getTrustedIssuerResult.first
                val entries = getTrustedIssuerResult.second
                builtInTrustManager.getEntries().forEach {
                    builtInTrustManager.deleteEntry(it)
                }
                entries.forEach {
                    when (it) {
                        is TrustEntryX509Cert -> {
                            builtInTrustManager.addX509Cert(
                                certificate = it.certificate,
                                metadata = it.metadata
                            )
                        }
                        is TrustEntryVical -> {
                            builtInTrustManager.addVical(
                                encodedSignedVical = it.encodedSignedVical,
                                metadata = it.metadata
                            )
                        }
                    }
                }
                settingsModel.builtInIssuersVersion.value = version
                settingsModel.builtInIssuersUpdatedAt.value = Clock.System.now()
                Logger.i(TAG, "Updated built-in issuer list from $currentVersion to version $version")
            } else {
                Logger.i(TAG, "No update to built-in issuer list at version $currentVersion")
            }
        } catch (e: Throwable) {
            Logger.i(TAG, "Error when checking for updated issuer trust list: $e")
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun Content() {
        var isInitialized = remember { mutableStateOf<Boolean>(initialized) }
        if (!isInitialized.value) {
            CoroutineScope(Dispatchers.Main).launch {
                initialize()
                isInitialized.value = true
            }
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(text = "Initializing...")
            }
            return
        }

        val coroutineScope = rememberCoroutineScope()

        // At application-startup, update trusted issuers and ensure reader keys.
        //
        // Also do this every 4 hours to make sure we pick up updates even if the app keeps running.
        //
        LaunchedEffect(Unit) {
            coroutineScope.launch {
                while (true) {
                    ensureReaderKeys()
                    updateBuiltInIssuers()
                    delay(4.hours)
                }
            }
        }

        val navController = rememberNavController()
        AppTheme {
            PromptDialogs(Platform.promptModel)

            if (BuildConfig.IDENTITY_READER_REQUIRE_TOS_ACCEPTANCE) {
                val tosAgreedTo = settingsModel.tosAgreedTo.collectAsState()
                if (!tosAgreedTo.value) {
                    TosScreen(settingsModel = settingsModel)
                    return@AppTheme
                }
            }

            NavHost(
                navController = navController,
                startDestination = startDestination ?: StartDestination,
                modifier = Modifier.fillMaxSize()
            ) {
                composable<StartDestination> { backStackEntry ->
                    StartScreen(
                        settingsModel = settingsModel,
                        readerBackendClient = readerBackendClient,
                        promptModel = promptModel,
                        mdocTransportOptionsForNfcEngagement = getMdocTransportOptionsForNfcEngagement(),
                        onScanQrClicked = {
                            navController.navigate(route = ScanQrDestination)
                        },
                        onNfcHandover = { scanResult ->
                            readerModel.reset()
                            readerModel.setConnectionEndpoint(
                                encodedDeviceEngagement = scanResult.encodedDeviceEngagement,
                                handover = scanResult.handover,
                                existingTransport = scanResult.transport
                            )
                            val readerQuery = ReaderQuery.valueOf(settingsModel.selectedQueryName.value)
                            readerModel.setDeviceRequest(
                                readerQuery.generateDeviceRequest(
                                    settingsModel = settingsModel,
                                    encodedSessionTranscript = readerModel.encodedSessionTranscript,
                                    readerBackendClient = readerBackendClient
                                )
                            )
                            navController.navigate(route = TransferDestination)
                        },
                        onReaderIdentityClicked = { navController.navigate(route = ReaderIdentityDestination) },
                        onTrustedIssuersClicked = { navController.navigate(route = TrustedIssuersDestination) },
                        onDeveloperSettingsClicked = { navController.navigate(route = DeveloperSettingsDestination) },
                        onAboutClicked = { navController.navigate(route = AboutDestination) },
                    )
                }
                composable<ScanQrDestination> { backStackEntry ->
                    ScanQrScreen(
                        onBackPressed = { navController.navigateUp() },
                        onMdocQrCodeScanned = { mdocUri ->
                            coroutineScope.launch {
                                val encodedDeviceEngagement =
                                    ByteString(mdocUri.substringAfter("mdoc:").fromBase64Url())
                                readerModel.reset()
                                readerModel.setMdocTransportOptions(getMdocTransportOptionsForQrEngagement())
                                readerModel.setConnectionEndpoint(
                                    encodedDeviceEngagement = encodedDeviceEngagement,
                                    handover = Simple.NULL,
                                    existingTransport = null
                                )
                                val readerQuery = ReaderQuery.valueOf(settingsModel.selectedQueryName.value)
                                readerModel.setDeviceRequest(
                                    readerQuery.generateDeviceRequest(
                                        settingsModel = settingsModel,
                                        encodedSessionTranscript = readerModel.encodedSessionTranscript,
                                        readerBackendClient = readerBackendClient
                                    )
                                )
                                navController.popBackStack()
                                navController.navigate(route = TransferDestination)
                            }
                        }
                    )
                }
                composable<SelectRequestDestination> { backStackEntry ->
                    SelectRequestScreen(
                        readerModel = readerModel,
                        settingsModel = settingsModel,
                        readerBackendClient = readerBackendClient,
                        onBackPressed = { urlLaunchData?.finish() ?: navController.navigateUp() },
                        onContinueClicked = {
                            navController.popBackStack()
                            navController.navigate(route = TransferDestination)
                        },
                        onReaderIdentitiesClicked = {
                            navController.navigate(route = ReaderIdentityDestination)
                        }
                    )
                }
                composable<TransferDestination> { backStackEntry ->
                    TransferScreen(
                        readerModel = readerModel,
                        onBackPressed = { urlLaunchData?.finish() ?: navController.navigateUp() },
                        onTransferComplete = {
                            navController.popBackStack()
                            navController.navigate(route = ShowResultsDestination)
                        }
                    )
                }
                composable<ShowResultsDestination> {
                    ShowResultsScreen(
                        readerQuery = ReaderQuery.valueOf(settingsModel.selectedQueryName.value),
                        readerModel = readerModel,
                        documentTypeRepository = documentTypeRepository,
                        issuerTrustManager = compositeTrustManager,
                        onBackPressed = { urlLaunchData?.finish() ?: navController.navigateUp() },
                        onShowDetailedResults = if (settingsModel.devMode.value) {
                            { navController.navigate(route = ShowDetailedResultsDestination) }
                        } else {
                            null
                        }
                    )
                }
                composable<ShowDetailedResultsDestination> { backStackEntry ->
                    ShowDetailedResultsScreen(
                        readerQuery = ReaderQuery.valueOf(settingsModel.selectedQueryName.value),
                        readerModel = readerModel,
                        documentTypeRepository = documentTypeRepository,
                        issuerTrustManager = compositeTrustManager,
                        onBackPressed = { urlLaunchData?.finish() ?: navController.navigateUp() },
                        onShowCertificateChain = { certificateChain ->
                            val certificateDataBase64 = Cbor.encode(certificateChain.toDataItem()).toBase64Url()
                            navController.navigate(
                                route = CertificateViewerDestination(certificateDataBase64)
                            )
                        },
                    )
                }
                composable<DeveloperSettingsDestination> { backStackEntry ->
                    DeveloperSettingsScreen(
                        settingsModel = settingsModel,
                        onBackPressed = { navController.navigateUp() },
                    )
                }
                composable<ReaderIdentityDestination> { backStackEntry ->
                    ReaderIdentityScreen(
                        promptModel = promptModel,
                        readerBackendClient = readerBackendClient,
                        settingsModel = settingsModel,
                        onBackPressed = { navController.navigateUp() },
                        onShowCertificateChain = { certificateChain ->
                            val certificateDataBase64 = Cbor.encode(certificateChain.toDataItem()).toBase64Url()
                            navController.navigate(
                                route = CertificateViewerDestination(certificateDataBase64)
                            )
                        },
                    )
                }
                composable<TrustedIssuersDestination> { backStackEntry ->
                    TrustedIssuersScreen(
                        builtInTrustManager = builtInTrustManager,
                        userTrustManager = userTrustManager,
                        settingsModel = settingsModel,
                        onBackPressed = { navController.navigateUp() },
                        onTrustEntryClicked = { trustManagerId, entryIndex, justImported ->
                            navController.navigate(
                                route = TrustEntryViewerDestination(
                                    trustManagerId = trustManagerId,
                                    entryIndex = entryIndex,
                                    justImported = justImported
                                )
                            )
                        }
                    )
                }
                composable<AboutDestination> { backStackEntry ->
                    AboutScreen(
                        onBackPressed = { navController.navigateUp() },
                    )
                }
                composable<CertificateViewerDestination> { backStackEntry ->
                    val destination = backStackEntry.toRoute<CertificateViewerDestination>()
                    CertificateViewerScreen(
                        certificateDataBase64 = destination.certificateDataBase64,
                        onBackPressed = { navController.navigateUp() },
                    )
                }
                composable<TrustEntryViewerDestination> { backStackEntry ->
                    val destination = backStackEntry.toRoute<TrustEntryViewerDestination>()
                    TrustEntryViewerScreen(
                        builtInTrustManager = builtInTrustManager,
                        userTrustManager = userTrustManager,
                        trustManagerId = destination.trustManagerId,
                        entryIndex = destination.entryIndex,
                        justImported = destination.justImported,
                        onBackPressed = { navController.navigateUp() },
                        onEditPressed = { entryIndex ->
                            navController.navigate(
                                route = TrustEntryEditorDestination(entryIndex)
                            )
                        },
                        onShowVicalEntry = { trustManagerId, entryIndex, vicalCertNum ->
                            navController.navigate(
                                route = VicalEntryViewerDestination(
                                    trustManagerId = trustManagerId,
                                    entryIndex = entryIndex,
                                    certificateIndex = vicalCertNum
                                )
                            )
                        },
                        onShowCertificate = { certificate ->
                            val certificateDataBase64 = Cbor.encode(certificate.toDataItem()).toBase64Url()
                            navController.navigate(
                                route = CertificateViewerDestination(certificateDataBase64)
                            )
                        },
                        onShowCertificateChain = { certificateChain ->
                            val certificateDataBase64 = Cbor.encode(certificateChain.toDataItem()).toBase64Url()
                            navController.navigate(
                                route = CertificateViewerDestination(certificateDataBase64)
                            )
                        },
                    )
                }
                composable<TrustEntryEditorDestination> { backStackEntry ->
                    val destination = backStackEntry.toRoute<TrustEntryEditorDestination>()
                    TrustEntryEditorScreen(
                        userTrustManager = userTrustManager,
                        entryIndex = destination.entryIndex,
                        onBackPressed = { navController.navigateUp() },
                    )
                }
                composable<VicalEntryViewerDestination> { backStackEntry ->
                    val destination = backStackEntry.toRoute<VicalEntryViewerDestination>()
                    VicalEntryViewerScreen(
                        builtInTrustManager = builtInTrustManager,
                        userTrustManager = userTrustManager,
                        trustManagerId = destination.trustManagerId,
                        entryIndex = destination.entryIndex,
                        certificateIndex = destination.certificateIndex,
                        onBackPressed = { navController.navigateUp() },
                    )
                }
            }
        }
    }
}

