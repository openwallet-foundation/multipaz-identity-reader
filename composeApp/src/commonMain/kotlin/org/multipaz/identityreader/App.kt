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
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
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

        val startRoute = startDestination ?: StartDestination
        val navigationState = rememberNavigationState(
            startRoute = startRoute,
            topLevelRoutes = setOf(StartDestination, SelectRequestDestination)
        )
        val navigator = remember { Navigator(navigationState) }

        val selectedQueryNameState = settingsModel.selectedQueryName.collectAsState()
        val devModeState = settingsModel.devMode.collectAsState()

        val entryProvider = entryProvider<NavKey> {
            entry<StartDestination> {
                StartScreen(
                    settingsModel = settingsModel,
                    readerBackendClient = readerBackendClient,
                    promptModel = promptModel,
                    mdocTransportOptionsForNfcEngagement = getMdocTransportOptionsForNfcEngagement(),
                    onScanQrClicked = {
                        navigator.navigate(ScanQrDestination)
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
                        navigator.navigate(TransferDestination)
                    },
                    onReaderIdentityClicked = { navigator.navigate(ReaderIdentityDestination) },
                    onTrustedIssuersClicked = { navigator.navigate(TrustedIssuersDestination) },
                    onDeveloperSettingsClicked = { navigator.navigate(DeveloperSettingsDestination) },
                    onAboutClicked = { navigator.navigate(AboutDestination) },
                )
            }
            entry<ScanQrDestination> {
                ScanQrScreen(
                    onBackPressed = { navigator.goBack() },
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
                            navigator.popBackStack()
                            navigator.navigate(TransferDestination)
                        }
                    }
                )
            }
            entry<SelectRequestDestination> {
                SelectRequestScreen(
                    readerModel = readerModel,
                    settingsModel = settingsModel,
                    readerBackendClient = readerBackendClient,
                    onBackPressed = { urlLaunchData?.finish() ?: navigator.goBack() },
                    onContinueClicked = {
                        navigator.popBackStack()
                        navigator.navigate(TransferDestination)
                    },
                    onReaderIdentitiesClicked = {
                        navigator.navigate(ReaderIdentityDestination)
                    }
                )
            }
            entry<TransferDestination> {
                TransferScreen(
                    readerModel = readerModel,
                    onBackPressed = { urlLaunchData?.finish() ?: navigator.goBack() },
                    onTransferComplete = {
                        navigator.popBackStack()
                        navigator.navigate(ShowResultsDestination)
                    }
                )
            }
            entry<ShowResultsDestination> {
                ShowResultsScreen(
                    readerQuery = ReaderQuery.valueOf(selectedQueryNameState.value),
                    readerModel = readerModel,
                    documentTypeRepository = documentTypeRepository,
                    issuerTrustManager = compositeTrustManager,
                    onBackPressed = { urlLaunchData?.finish() ?: navigator.goBack() },
                    onShowDetailedResults = if (devModeState.value) {
                        { navigator.navigate(ShowDetailedResultsDestination) }
                    } else {
                        null
                    }
                )
            }
            entry<ShowDetailedResultsDestination> {
                ShowDetailedResultsScreen(
                    readerQuery = ReaderQuery.valueOf(selectedQueryNameState.value),
                    readerModel = readerModel,
                    documentTypeRepository = documentTypeRepository,
                    issuerTrustManager = compositeTrustManager,
                    onBackPressed = { urlLaunchData?.finish() ?: navigator.goBack() },
                    onShowCertificateChain = { certificateChain ->
                        val certificateDataBase64 = Cbor.encode(certificateChain.toDataItem()).toBase64Url()
                        navigator.navigate(
                            CertificateViewerDestination(certificateDataBase64)
                        )
                    },
                )
            }
            entry<DeveloperSettingsDestination> {
                DeveloperSettingsScreen(
                    settingsModel = settingsModel,
                    onBackPressed = { navigator.goBack() },
                )
            }
            entry<ReaderIdentityDestination> {
                ReaderIdentityScreen(
                    promptModel = promptModel,
                    readerBackendClient = readerBackendClient,
                    settingsModel = settingsModel,
                    onBackPressed = { navigator.goBack() },
                    onShowCertificateChain = { certificateChain ->
                        val certificateDataBase64 = Cbor.encode(certificateChain.toDataItem()).toBase64Url()
                        navigator.navigate(
                            CertificateViewerDestination(certificateDataBase64)
                        )
                    },
                )
            }
            entry<TrustedIssuersDestination> {
                TrustedIssuersScreen(
                    builtInTrustManager = builtInTrustManager,
                    userTrustManager = userTrustManager,
                    settingsModel = settingsModel,
                    onBackPressed = { navigator.goBack() },
                    onTrustEntryClicked = { trustManagerId, entryIndex, justImported ->
                        navigator.navigate(
                            TrustEntryViewerDestination(
                                trustManagerId = trustManagerId,
                                entryIndex = entryIndex,
                                justImported = justImported
                            )
                        )
                    }
                )
            }
            entry<AboutDestination> {
                AboutScreen(
                    onBackPressed = { navigator.goBack() },
                )
            }
            entry<CertificateViewerDestination> { key ->
                CertificateViewerScreen(
                    certificateDataBase64 = key.certificateDataBase64,
                    onBackPressed = { navigator.goBack() },
                )
            }
            entry<TrustEntryViewerDestination> { key ->
                TrustEntryViewerScreen(
                    builtInTrustManager = builtInTrustManager,
                    userTrustManager = userTrustManager,
                    trustManagerId = key.trustManagerId,
                    entryIndex = key.entryIndex,
                    justImported = key.justImported,
                    onBackPressed = { navigator.goBack() },
                    onEditPressed = { entryIndex ->
                        navigator.navigate(
                            TrustEntryEditorDestination(entryIndex)
                        )
                    },
                    onShowVicalEntry = { trustManagerId, entryIndex, vicalCertNum ->
                        navigator.navigate(
                            VicalEntryViewerDestination(
                                trustManagerId = trustManagerId,
                                entryIndex = entryIndex,
                                certificateIndex = vicalCertNum
                            )
                        )
                    },
                    onShowCertificate = { certificate ->
                        val certificateDataBase64 = Cbor.encode(certificate.toDataItem()).toBase64Url()
                        navigator.navigate(
                            CertificateViewerDestination(certificateDataBase64)
                        )
                    },
                    onShowCertificateChain = { certificateChain ->
                        val certificateDataBase64 = Cbor.encode(certificateChain.toDataItem()).toBase64Url()
                        navigator.navigate(
                            CertificateViewerDestination(certificateDataBase64)
                        )
                    },
                )
            }
            entry<TrustEntryEditorDestination> { key ->
                TrustEntryEditorScreen(
                    userTrustManager = userTrustManager,
                    entryIndex = key.entryIndex,
                    onBackPressed = { navigator.goBack() },
                )
            }
            entry<VicalEntryViewerDestination> { key ->
                VicalEntryViewerScreen(
                    builtInTrustManager = builtInTrustManager,
                    userTrustManager = userTrustManager,
                    trustManagerId = key.trustManagerId,
                    entryIndex = key.entryIndex,
                    certificateIndex = key.certificateIndex,
                    onBackPressed = { navigator.goBack() },
                )
            }
        }

        AppTheme {
            PromptDialogs(Platform.promptModel)

            if (BuildConfig.IDENTITY_READER_REQUIRE_TOS_ACCEPTANCE) {
                val tosAgreedTo = settingsModel.tosAgreedTo.collectAsState()
                if (!tosAgreedTo.value) {
                    TosScreen(settingsModel = settingsModel)
                    return@AppTheme
                }
            }

            NavDisplay(
                entries = navigationState.toEntries(entryProvider),
                onBack = { navigator.goBack() },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}
