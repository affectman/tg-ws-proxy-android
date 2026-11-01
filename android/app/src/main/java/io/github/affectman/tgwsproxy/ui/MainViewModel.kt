package io.github.affectman.tgwsproxy.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.affectman.tgwsproxy.BuildConfig
import io.github.affectman.tgwsproxy.app
import io.github.affectman.tgwsproxy.core.ConfigField
import io.github.affectman.tgwsproxy.core.EngineState
import io.github.affectman.tgwsproxy.core.ProxyEngine
import io.github.affectman.tgwsproxy.core.ProxyStats
import io.github.affectman.tgwsproxy.data.AppSettings
import io.github.affectman.tgwsproxy.data.UpdateInfo
import io.github.affectman.tgwsproxy.service.ProxyService
import io.github.affectman.tgwsproxy.update.UpdateChecker
import io.github.affectman.tgwsproxy.update.UpdateInstaller
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One-off messages for the snackbar. */
sealed interface UiMessage {
    data object UpToDate : UiMessage
    data class UpdateFailed(val reason: String) : UiMessage
    data object Copied : UiMessage
    data object NoTelegram : UiMessage
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()
    private val repo = application.app.settings

    val settings: StateFlow<AppSettings?> =
        repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val engine: StateFlow<EngineState> = ProxyEngine.state

    /** Counters, read once a second only while a screen collects them and the proxy runs. */
    val stats: StateFlow<ProxyStats> = ProxyEngine.state
        .map { it.state.isActive }
        .distinctUntilChanged()
        .flatMapLatest { active ->
            if (!active) flowOf(ProxyStats()) else flow {
                while (true) {
                    emit(ProxyEngine.stats(context))
                    delay(STATS_PERIOD_MS)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), ProxyStats())

    val link: StateFlow<String?> = settings.filterNotNull()
        .map { if (it.secret.isEmpty()) null else it.coreConfigJson() }
        .distinctUntilChanged()
        .mapLatest { config -> config?.let { ProxyEngine.link(context, it) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val schema: StateFlow<List<ConfigField>> = flow { emit(ProxyEngine.schema(context)) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** True when saved settings differ from what the running proxy was started with. */
    val needsRestart: StateFlow<Boolean> = combine(
        settings.filterNotNull(), ProxyEngine.appliedConfig, ProxyEngine.state,
    ) { s, applied, st -> st.state.isActive && applied != null && applied != s.coreConfigJson() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    val pendingUpdate: StateFlow<UpdateInfo?> =
        repo.pendingUpdate.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val lastUpdateCheck: StateFlow<Long> =
        repo.lastUpdateCheck.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    private val _checkingUpdate = MutableStateFlow(false)
    val checkingUpdate: StateFlow<Boolean> = _checkingUpdate.asStateFlow()

    /** Download progress 0..1 while an update downloads, else null. */
    private val _downloadProgress = MutableStateFlow<Float?>(null)
    val downloadProgress: StateFlow<Float?> = _downloadProgress.asStateFlow()

    private val _messages = Channel<UiMessage>(Channel.BUFFERED)
    val messages: Flow<UiMessage> = _messages.receiveAsFlow()

    /** The log tail, refreshed only while the log screen is open and only when it changed. */
    val logs: StateFlow<String> = flow {
        var seen = -1L
        while (true) {
            val seq = ProxyEngine.stats(context).logSeq
            if (seq != seen) {
                emit(ProxyEngine.logs(context))
                seen = seq
            }
            delay(LOG_PERIOD_MS)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), "")

    init {
        viewModelScope.launch { repo.current() }  // creates the secret on first launch
        ProxyEngine.preload(context)
        maybeCheckUpdatesOnOpen()
    }

    fun toggle() {
        if (engine.value.state.isActive) ProxyService.stop(context) else ProxyService.start(context)
    }

    fun restart() = ProxyService.start(context, restart = true)

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { repo.update(transform) }
    }

    fun regenerateSecret() {
        viewModelScope.launch { repo.regenerateSecret() }
    }

    /** Switches to [port] after a "port in use" error and starts again. */
    fun usePort(port: Int) {
        viewModelScope.launch {
            repo.update { it.copy(port = port) }
            ProxyService.start(context, restart = true)
        }
    }

    fun message(m: UiMessage) {
        _messages.trySend(m)
    }

    fun clearLogs() {
        viewModelScope.launch { ProxyEngine.clearLogs() }
    }

    fun checkUpdatesNow() {
        if (_checkingUpdate.value) return
        viewModelScope.launch {
            _checkingUpdate.value = true
            try {
                val info = UpdateChecker.fetchNewer()
                repo.setPendingUpdate(info)
                repo.markUpdateChecked(System.currentTimeMillis())
                if (info == null) message(UiMessage.UpToDate)
            } catch (e: Exception) {
                message(UiMessage.UpdateFailed(e.message ?: e.javaClass.simpleName))
            } finally {
                _checkingUpdate.value = false
            }
        }
    }

    fun installUpdate(info: UpdateInfo) {
        if (_downloadProgress.value != null) return
        viewModelScope.launch {
            _downloadProgress.value = 0f
            val result = try {
                UpdateInstaller.downloadAndInstall(context, info) { _downloadProgress.value = it }
            } finally {
                _downloadProgress.value = null
            }
            when (result) {
                UpdateInstaller.Result.NeedsPermission ->
                    context.startActivity(UpdateInstaller.permissionIntent(context))
                is UpdateInstaller.Result.Failed -> message(UiMessage.UpdateFailed(result.reason))
                UpdateInstaller.Result.Launched -> Unit
            }
        }
    }

    /** Opening the app counts as a chance to check, at most once a day. */
    private fun maybeCheckUpdatesOnOpen() {
        if (BuildConfig.DEBUG) return
        viewModelScope.launch {
            if (!repo.current().checkUpdates) return@launch
            if (System.currentTimeMillis() - repo.lastUpdateCheck.first() < DAY_MS) return@launch
            runCatching {
                repo.setPendingUpdate(UpdateChecker.fetchNewer())
                repo.markUpdateChecked(System.currentTimeMillis())
            }
        }
    }

    private companion object {
        const val STATS_PERIOD_MS = 1_000L
        const val LOG_PERIOD_MS = 1_000L
        const val STOP_TIMEOUT_MS = 3_000L
        const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
