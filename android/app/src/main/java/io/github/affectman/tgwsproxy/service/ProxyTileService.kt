package io.github.affectman.tgwsproxy.service

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import io.github.affectman.tgwsproxy.R
import io.github.affectman.tgwsproxy.core.ProxyEngine
import io.github.affectman.tgwsproxy.core.ProxyState
import io.github.affectman.tgwsproxy.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** Quick Settings tile; it observes state only while the shade is open. */
class ProxyTileService : TileService() {
    private var watcher: Job? = null

    override fun onStartListening() {
        watcher = CoroutineScope(Dispatchers.Main.immediate).launch {
            ProxyEngine.state.collectLatest { render(it.state) }
        }
    }

    override fun onStopListening() {
        watcher?.cancel()
        watcher = null
    }

    override fun onClick() {
        if (ProxyEngine.state.value.state.isActive) {
            ProxyService.stop(this)
            return
        }
        try {
            ProxyService.start(this)
        } catch (e: Exception) {
            // Some Android versions refuse a foreground service start from a tile;
            // the app's screen can start it instead.
            Log.w("ProxyTile", "Cannot start from tile", e)
            openApp()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun render(state: ProxyState) {
        val tile = qsTile ?: return
        tile.state = when (state) {
            ProxyState.RUNNING, ProxyState.STARTING -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.label = getString(R.string.tile_label)
        if (Build.VERSION.SDK_INT >= 29) {
            tile.subtitle = getString(
                when (state) {
                    ProxyState.RUNNING -> R.string.state_running
                    ProxyState.STARTING -> R.string.state_starting
                    ProxyState.ERROR -> R.string.state_error
                    ProxyState.STOPPED -> R.string.state_stopped
                }
            )
        }
        tile.updateTile()
    }
}
