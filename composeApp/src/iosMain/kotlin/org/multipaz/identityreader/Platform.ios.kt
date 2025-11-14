package org.multipaz.identityreader

import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.darwin.Darwin
import platform.UIKit.UIDevice
import platform.posix.exit

class IOSPlatform: Platform {
    override val name: String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion

    // No API to do this on iOS
    override val nfcPollingFramesInsertionSupported = false

    override fun exitApp() {
        exit(0)
    }
}

private val platform by lazy { IOSPlatform() }

actual fun getPlatform(): Platform = platform

actual fun platformHttpClientEngineFactory(): HttpClientEngineFactory<*> = Darwin
