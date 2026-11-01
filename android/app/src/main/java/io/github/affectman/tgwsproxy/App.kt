package io.github.affectman.tgwsproxy

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.work.Configuration
import io.github.affectman.tgwsproxy.data.SettingsRepository
import io.github.affectman.tgwsproxy.update.UpdateWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application(), Configuration.Provider {

    val settings: SettingsRepository by lazy { SettingsRepository(this) }

    /** For short writes that must outlive the component that started them. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setMinimumLoggingLevel(android.util.Log.WARN).build()

    override fun onCreate() {
        super.onCreate()
        createChannels()
        UpdateWorker.schedule(this)
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROXY, getString(R.string.channel_proxy), NotificationManager.IMPORTANCE_LOW)
                .apply {
                    description = getString(R.string.channel_proxy_desc)
                    setShowBadge(false)
                }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_UPDATES, getString(R.string.channel_updates), NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = getString(R.string.channel_updates_desc) }
        )
    }

    companion object {
        const val CHANNEL_PROXY = "proxy"
        const val CHANNEL_UPDATES = "updates"
    }
}

val Context.app: App get() = applicationContext as App
