package org.multipaz.identityreader

import android.os.Build
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.android.Android
import org.multipaz.context.applicationContext
import org.multipaz.context.getActivity
import org.multipaz.util.Logger

private const val TAG = "Platform"

class AndroidPlatformUtils : PlatformUtils {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"

    override val nfcPollingFramesInsertionSupported by lazy {
        // Use an allow-list until b/460804407 is resolved and used in Multipaz
        if (Build.MANUFACTURER == "Google" && (
                    Build.MODEL.startsWith("Pixel 8") ||
                    Build.MODEL.startsWith("Pixel 9") ||
                    Build.MODEL.startsWith("Pixel 10") ||
                    Build.MODEL.startsWith("Pixel 11")
            )
        ) {
            Logger.i(TAG, "Device is on allow-list for nfcPollingFramesInsertionSupported")
            true
        } else {
            Logger.w(TAG, "Device is not allow-list for nfcPollingFramesInsertionSupported")
            false
        }
    }

    override val httpClientEngineFactory: HttpClientEngineFactory<*> = Android

    override fun exitApp() {
        System.exit(0)
    }
}

actual fun getPlatformUtils(): PlatformUtils = AndroidPlatformUtils()
