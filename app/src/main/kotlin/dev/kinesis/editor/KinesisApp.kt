package dev.kinesis.editor

import android.app.Application
import android.content.Context
import dev.kinesis.editor.ui.settings.AppSettings

class KinesisApp : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppSettings.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        AppSettings.init(this)
    }
}
