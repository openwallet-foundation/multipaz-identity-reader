package org.multipaz.identityreader

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.darwin.Darwin
import platform.UIKit.UIDevice
import platform.posix.exit

class IOSPlatformUtils: PlatformUtils {
    override val name: String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion

    // No API to do this on iOS
    override val nfcPollingFramesInsertionSupported = false

    override val httpClientEngineFactory: HttpClientEngineFactory<*> = Darwin

    override fun exitApp() {
        exit(0)
    }
}

actual fun getPlatformUtils(): PlatformUtils = IOSPlatformUtils()
