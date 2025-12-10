package org.multipaz.identityreader

import io.ktor.client.engine.HttpClientEngineFactory

interface PlatformUtils {
    val name: String

    // Workaround for now until b/460804407 is resolved and used in Multipaz
    val nfcPollingFramesInsertionSupported: Boolean

    val httpClientEngineFactory: HttpClientEngineFactory<*>

    fun exitApp()
}

expect fun getPlatformUtils(): PlatformUtils
