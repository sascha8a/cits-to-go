package org.opentrafficmap.citstogo

import android.app.Application
import android.os.Build
import org.opentrafficmap.citstogo.logging.AppLogging
import java.util.logging.Logger

class CitsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogging.install()
        Logger.getLogger("org.opentrafficmap.citstogo.Application").info(
            "App ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}); " +
                "Android ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}; ${Build.MANUFACTURER} ${Build.MODEL}",
        )
    }
}
