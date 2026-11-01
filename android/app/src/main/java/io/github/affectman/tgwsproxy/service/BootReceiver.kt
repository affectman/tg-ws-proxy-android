package io.github.affectman.tgwsproxy.service

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import io.github.affectman.tgwsproxy.app
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Starts the proxy after boot ("Start on boot") and brings it back after the
 * app updates itself while it was running.
 *
 * Disabled in the manifest; [sync] enables it only when one of those cases
 * can apply, so a phone that never uses them never wakes the app at boot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = context.app.settings
                val start = when (intent.action) {
                    Intent.ACTION_BOOT_COMPLETED -> repo.current().startOnBoot
                    Intent.ACTION_MY_PACKAGE_REPLACED -> repo.wantRunning()
                    else -> false
                }
                if (start) ProxyService.start(context)
            } catch (e: Exception) {
                Log.w("BootReceiver", "Cannot start proxy", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** Enables the receiver while start-on-boot is on or the proxy should be running. */
        fun sync(context: Context, startOnBoot: Boolean, wantRunning: Boolean) {
            val state = if (startOnBoot || wantRunning) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            context.packageManager.setComponentEnabledSetting(
                ComponentName(context, BootReceiver::class.java), state, PackageManager.DONT_KILL_APP,
            )
        }
    }
}
