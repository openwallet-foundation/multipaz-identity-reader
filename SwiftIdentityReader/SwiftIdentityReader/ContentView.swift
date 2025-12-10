import SwiftUI
import IdentityReader
internal import Combine

class AppState {
    var storage: Storage!
    var settingsModel: SettingsModel!
    var readerModel: ReaderModel!
    var readerBackendClient: ReaderBackendClient!
    
    func initialize() async {
        storage = Platform.shared.nonBackedUpStorage
        settingsModel = try! await SettingsModel.companion.create(storage: storage, readOnly: false)
        readerModel = ReaderModel()
        
        readerBackendClient = await ReaderBackendClient.init(
            readerBackendUrl: "https://identityreader.multipaz.org/",
            //readerBackendUrl: "http://127.0.0.1:8020/",
            storage: storage,
            httpClientEngineFactory: getPlatformUtils().httpClientEngineFactory,
            secureArea: try! Platform.shared.getSecureArea(),
            numKeys: 10
        )
    }
}

enum AppDestination: Hashable {
    case transfer
    case result
}

class NavigationManager: ObservableObject {
    @Published var path = NavigationPath()
    
    func navigate(to destination: AppDestination) {
        path.append(destination)
    }
    
    func pop() {
        if !path.isEmpty {
            path.removeLast()
        }
    }
    
    func reset() {
        path = NavigationPath()
    }
}

struct ContentView: View {
    @StateObject private var navigationManager = NavigationManager()
    
    let appState = AppState()

    var body: some View {
        NavigationStack(path: $navigationManager.path) {
            VStack {
                Image(systemName: "globe")
                    .imageScale(.large)
                    .foregroundStyle(.tint)
                Text("Hello KMP")
                
                Button("Scan with NFC") {
                    Task {
                        let connectionMethods = [
                            MdocConnectionMethodBle(
                                supportsPeripheralServerMode: false,
                                supportsCentralClientMode: true,
                                peripheralServerModeUuid: nil,
                                centralClientModeUuid: UUID.companion.randomUUID(),
                                peripheralServerModePsm: nil,
                                peripheralServerModeMacAddress: nil)
                        ]
                        // See NfcUtils.kt in libfrontend for this
                        let builtInTagReader = NfcTagReaderCompanion.shared.getReaders().first!
                        let scanResult = try! await builtInTagReader.scanNfcMdocReaderMod1(
                            message: "Hold to wallet",
                            options: MdocTransportOptions(
                                bleUseL2CAP: false,
                                bleUseL2CAPInEngagement: true
                            ),
                            transportFactory: MdocTransportFactoryDefault(),
                            negotiatedHandoverConnectionMethods: connectionMethods,
                            nfcScanOptions: NfcScanOptions(pollingFrameData: nil),
                            context: Dispatchers.shared.IO
                        )
                        if (scanResult != nil) {
                            appState.readerModel.reset()
                            appState.readerModel.setConnectionEndpoint(
                                encodedDeviceEngagement: scanResult!.encodedDeviceEngagement,
                                handover: scanResult!.handover,
                                existingTransport: scanResult!.transport
                            )
                            let readerQuery = ReaderQuery.ageOver18
                            let encodedDeviceRequest = try! await readerQuery.generateDeviceRequest(
                                settingsModel: appState.settingsModel,
                                encodedSessionTranscript: appState.readerModel.encodedSessionTranscript,
                                readerBackendClient: appState.readerBackendClient
                            )
                            appState.readerModel.setDeviceRequest(
                                encodedDeviceRequest: encodedDeviceRequest
                            )
                            navigationManager.navigate(to: AppDestination.transfer)
                        }
                    }
                }
                //List {
                //    NavigationLink("Go to transfer", value: AppDestination.transfer)
                //    NavigationLink("Go to results", value: AppDestination.result)
                //}
            }
            .padding()
            .navigationDestination(for: AppDestination.self) { destination in
                switch (destination) {
                case .transfer:
                    TransferView()
                case .result:
                    ResultView()
                }
            }
        }
        .environmentObject(navigationManager)
        .onAppear {
            Task {
                await appState.initialize()
                try! await appState.readerBackendClient.getKey(
                    readerIdentityId: nil, atTime: KotlinClockCompanion.shared.getSystem().now()
                )
                appState.readerModel.reset()
                for await state in appState.readerModel.state {
                    print("state: \(state)")
                    switch (state) {
                    case .idle:
                        break
                    case .waitingForDeviceRequest:
                        break
                    case .waitingForStart:
                        appState.readerModel.start(scope: CoroutineScope(context: Dispatchers.shared.Main))
                        break
                    case .connecting:
                        break
                    case .completed:
                        navigationManager.reset()
                        navigationManager.navigate(to: .result)
                        break
                    }
                }
            }
        }
    }
}

struct TransferView: View {
    @EnvironmentObject var navigationManager: NavigationManager
    
    var body: some View {
        Text("Transfering data")
            .font(.largeTitle)
            .navigationTitle("Transfer")
        
        Button("On to results!") {
            navigationManager.pop()
            navigationManager.navigate(to: .result)
            print("foobar")
        }.buttonStyle(.bordered)
    }
}

struct ResultView: View {
    @EnvironmentObject var navigationManager: NavigationManager
    
    var body: some View {
        Text("Results are here")
            .font(.largeTitle)
            .navigationTitle("Result")
    }
}


#Preview {
    ContentView()
}
