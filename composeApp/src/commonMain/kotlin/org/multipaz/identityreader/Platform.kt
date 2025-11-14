package org.multipaz.identityreader

import io.ktor.client.engine.HttpClientEngineFactory

interface Platform {
    val name: String

    // Workaround for now until b/460804407 is resolved and used in Multipaz
    val nfcPollingFramesInsertionSupported: Boolean

    fun exitApp()
}

expect fun getPlatform(): Platform

expect fun platformHttpClientEngineFactory(): HttpClientEngineFactory<*>
