package org.multipaz.identityreader

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.io.bytestring.ByteString
import org.multipaz.cbor.Bstr
import org.multipaz.cbor.Cbor
import org.multipaz.cbor.DataItem
import org.multipaz.cbor.Simple
import org.multipaz.cbor.Tagged
import org.multipaz.cbor.buildCborArray
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.mdoc.connectionmethod.MdocConnectionMethod
import org.multipaz.mdoc.engagement.DeviceEngagement
import org.multipaz.mdoc.role.MdocRole
import org.multipaz.mdoc.sessionencryption.SessionEncryption
import org.multipaz.mdoc.transport.MdocTransport
import org.multipaz.mdoc.transport.MdocTransportFactory
import org.multipaz.mdoc.transport.MdocTransportOptions
import org.multipaz.util.Constants
import org.multipaz.util.Logger
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

enum class EngagementType(val description: String) {
    QR("QR code"),
    NFC_STATIC_HANDOVER("NFC Static Handover"),
    NFC_NEGOTIATED_HANDOVER("NFC Negotiated Handover"),
}

data class ReaderModelResult(
    val status: Long?,
    val encodedDeviceResponse: ByteString?,
    val encodedSessionTranscript: ByteString,
    val eReaderKey: EcPrivateKey,

    val engagementType: EngagementType,
    val durationEngagementReceivedToRequestSent: Duration,
    val durationRequestSentToResponseReceived: Duration,
    val durationScanningTime: Duration?,
    val connectionMethod: MdocConnectionMethod
)

class ReaderModel {
    companion object {
        private const val TAG = "ReaderModel"
    }

    enum class State {
        IDLE,
        WAITING_FOR_DEVICE_REQUEST,
        WAITING_FOR_START,
        CONNECTING,
        COMPLETED,
    }

    private val _state = MutableStateFlow<State>(State.IDLE)

    /**
     * The current state.
     */
    val state = _state.asStateFlow()

    private var _scope: CoroutineScope? = null

    /**
     * A [CoroutineScope] for the verification process.
     *
     * Any coroutine launched in this scope will be automatically canceled when verification completes.
     *
     * This should only be read in states which aren't [State.IDLE] and [State.COMPLETED]. It will throw
     * [IllegalStateException] if this is not the case.
     */
    val scope: CoroutineScope
        get() {
            check(_scope != null)
            check(_state.value != State.IDLE && _state.value != State.COMPLETED)
            return _scope!!
        }

    private var _error: Throwable? = null

    /**
     * If reading the credentials fails, this will be set with a [Throwable] with more information about the failure.
     */
    val error: Throwable?
        get() = _error

    private var _result: ReaderModelResult? = null
    val result: ReaderModelResult?
        get() = _result

    private var _encodedSessionTranscript: ByteString? = null
    val encodedSessionTranscript: ByteString
        get() {
            check(_state.value != State.IDLE && _state.value != State.COMPLETED)
            return _encodedSessionTranscript!!
        }

    private var deviceEngagement: DeviceEngagement? = null
    private var _eReaderKey: EcPrivateKey? = null
    val eReaderKey: EcPrivateKey
        get() {
            check(_state.value != State.IDLE && _state.value != State.COMPLETED)
            return _eReaderKey!!
        }

    fun reset() {
        _scope?.cancel(CancellationException("ReaderModel reset"))
        _scope = null
        _result = null
        _error = null
        _encodedSessionTranscript = null
        deviceEngagement = null
        _eReaderKey = null
        mdocTransportOptions = null
        encodedDeviceRequest = null
        encodedDeviceEngagement = null
        handover = null
        existingTransport = null
        _state.value = State.IDLE
    }

    private var mdocTransportOptions: MdocTransportOptions? = null
    private var encodedDeviceRequest: ByteString? = null
    private var encodedDeviceEngagement: ByteString? = null
    private var handover: DataItem? = null
    private var existingTransport: MdocTransport? = null

    fun setMdocTransportOptions(options: MdocTransportOptions) {
        mdocTransportOptions = options
    }

    fun setConnectionEndpoint(
        encodedDeviceEngagement: ByteString,
        handover: DataItem,
        existingTransport: MdocTransport? = null
    ) {
        check(_state.value == State.IDLE)
        this.encodedDeviceEngagement = encodedDeviceEngagement
        this.handover = handover
        this.existingTransport = existingTransport
        _state.value = State.WAITING_FOR_DEVICE_REQUEST

        deviceEngagement = DeviceEngagement.fromDataItem(
            Cbor.decode(encodedDeviceEngagement.toByteArray())
        )
        _eReaderKey = Crypto.createEcPrivateKey(deviceEngagement!!.eDeviceKey.curve)
        val encodedEReaderKey = Cbor.encode(_eReaderKey!!.publicKey.toCoseKey().toDataItem())
        _encodedSessionTranscript = ByteString(
            Cbor.encode(
                buildCborArray {
                    add(Tagged(24, Bstr(encodedDeviceEngagement.toByteArray())))
                    add(Tagged(24, Bstr(encodedEReaderKey)))
                    add(handover)
                }
            )
        )
        Logger.iCbor(TAG, "sessionTranscript", _encodedSessionTranscript!!.toByteArray())
    }

    fun setDeviceRequest(encodedDeviceRequest: ByteString) {
        check(_state.value == State.WAITING_FOR_DEVICE_REQUEST)
        _state.value = State.WAITING_FOR_START
        this.encodedDeviceRequest = encodedDeviceRequest
    }

    /**
     * Sets the model to [State.CONNECTING].
     */
    fun start(
        scope: CoroutineScope,
    ) {
        check(_state.value == State.WAITING_FOR_START)
        _scope = scope
        _state.value = State.CONNECTING
        println("Starting...")
        _scope!!.launch {
            val (result, error) = try {
                Pair(
                    doReaderFlow(
                        encodedDeviceEngagement!!,
                        handover!!,
                        existingTransport
                    ),
                    null
                )
            } catch (e: Throwable) {
                Logger.w(TAG, "Error doing reader flow", e)
                Pair(null, e)
            }
            println("Setting state to COMPLETED")
            _result = result
            _error = error
            deviceEngagement = null
            _eReaderKey = null
            encodedDeviceRequest = null
            encodedDeviceEngagement = null
            handover = null
            existingTransport = null
            _state.value = State.COMPLETED

            // TODO: Hack to ensure that [state] collectors gets called for State.COMPLETED
            _scope?.launch {
                delay(1.seconds)
                _scope?.cancel(CancellationException("PresentationModel completed"))
                _scope = null
            }
        }
    }

    // Returns the message/status on success, throws otherwise
    private suspend fun doReaderFlow(
        encodedDeviceEngagement: ByteString,
        handover: DataItem,
        existingTransport: MdocTransport?
    ): ReaderModelResult {
        println("In doReaderFlow()")

        val timeOfEngagementReceived = Clock.System.now()

        val transport = if (existingTransport != null) {
            existingTransport
        } else {
            val connectionMethods = MdocConnectionMethod.disambiguate(
                deviceEngagement!!.connectionMethods,
                MdocRole.MDOC_READER
            )
            val connectionMethod = if (connectionMethods.size == 1) {
                connectionMethods[0]
            } else {
                // TODO: maybe selectConnectionMethod(connectionMethods)
                connectionMethods[0]
            }
            val transport = MdocTransportFactory.Default.createTransport(
                connectionMethod = connectionMethod,
                role = MdocRole.MDOC_READER,
                options = mdocTransportOptions ?: MdocTransportOptions()
            )
            // TODO: maybe if (transport is NfcTransportMdocReader) {
            transport
        }

        Logger.iCbor(TAG, "handover", Cbor.encode(handover))
        val engagementType = if (handover == Simple.NULL) {
            EngagementType.QR
        } else {
            if (handover.asArray[1] == Simple.NULL) {
                EngagementType.NFC_STATIC_HANDOVER
            } else {
                EngagementType.NFC_NEGOTIATED_HANDOVER
            }
        }

        val sessionEncryption = SessionEncryption(
            MdocRole.MDOC_READER,
            eReaderKey,
            deviceEngagement!!.eDeviceKey,
            encodedSessionTranscript.toByteArray(),
        )

        println("OK, with transport: $transport")
        val connectionMethod = transport.connectionMethod
        try {
            transport.open(deviceEngagement!!.eDeviceKey)
            transport.sendMessage(
                sessionEncryption.encryptMessage(
                    messagePlaintext = encodedDeviceRequest!!.toByteArray(),
                    statusCode = null
                )
            )
            val timeOfFirstMessageSent = Clock.System.now()

            val sessionData = transport.waitForMessage()
            val timeOfFirstResponseReceived = Clock.System.now()
            if (sessionData.isEmpty()) {
                // TODO: showToast("Received transport-specific session termination message from holder")
                transport.close()
                return ReaderModelResult(
                    status = null,
                    encodedDeviceResponse = null,
                    encodedSessionTranscript = encodedSessionTranscript,
                    eReaderKey = eReaderKey,
                    engagementType = engagementType,
                    durationEngagementReceivedToRequestSent = timeOfFirstMessageSent - timeOfEngagementReceived,
                    durationRequestSentToResponseReceived = timeOfFirstResponseReceived - timeOfFirstMessageSent,
                    durationScanningTime = transport.scanningTime,
                    connectionMethod = connectionMethod
                )
            }

            val (message, status) = sessionEncryption.decryptMessage(sessionData)
            Logger.i(TAG, "Holder sent ${message?.size} bytes status $status")
            if (status == Constants.SESSION_DATA_STATUS_SESSION_TERMINATION) {
                //showToast("Received session termination message from holder")
                Logger.i(
                    TAG, "Holder indicated they closed the connection. " +
                            "Closing and ending reader loop"
                )
                transport.close()
            } else {
                Logger.i(
                    TAG, "Holder did not indicate they are closing the connection. " +
                            "Auto-close is enabled, so sending termination message, closing, and " +
                            "ending reader loop"
                )
                transport.sendMessage(SessionEncryption.encodeStatus(Constants.SESSION_DATA_STATUS_SESSION_TERMINATION))
                transport.close()
            }
            return ReaderModelResult(
                status = status,
                encodedDeviceResponse = message?.let { ByteString(it) },
                encodedSessionTranscript = encodedSessionTranscript,
                eReaderKey = eReaderKey,
                engagementType = engagementType,
                durationEngagementReceivedToRequestSent = timeOfFirstMessageSent - timeOfEngagementReceived,
                durationRequestSentToResponseReceived = timeOfFirstResponseReceived - timeOfFirstMessageSent,
                durationScanningTime = transport.scanningTime,
                connectionMethod = connectionMethod
            )
        } finally {
            /*
            if (updateNfcDialogMessage != null) {
                updateNfcDialogMessage("Transfer complete")
            }
             */
            transport.close()
        }
    }
}
