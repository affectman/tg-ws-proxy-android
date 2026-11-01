package io.github.affectman.tgwsproxy.core

import android.content.Context
import android.util.Log
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import io.github.affectman.tgwsproxy.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * Kotlin side of tgws_android/engine.py.
 *
 * Every Python call runs on one thread, so lifecycle calls never overlap.
 * [state] is pushed by Python on each transition; nothing here polls.
 */
object ProxyEngine {
    private const val TAG = "ProxyEngine"

    private val dispatcher = Executors.newSingleThreadExecutor { Thread(it, "python") }
        .asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    private val listener = EngineListener { json ->
        runCatching { EngineState.fromJson(json) }
            .onSuccess { _state.value = it }
            .onFailure { Log.w(TAG, "Bad event from core: $json", it) }
    }

    @Volatile
    private var module: PyObject? = null
    private var cachedSchema: List<ConfigField>? = null

    /** Screen-off power mode; remembered so a start while idle begins idle. */
    @Volatile
    private var idle = false

    /** Core config of the running session, to tell when settings need a restart. */
    private val _appliedConfig = MutableStateFlow<String?>(null)
    val appliedConfig: StateFlow<String?> = _appliedConfig.asStateFlow()

    /** Starts the Python runtime once; the first call takes about a second. */
    private fun engine(context: Context): PyObject {
        module?.let { return it }
        if (!Python.isStarted()) Python.start(AndroidPlatform(context.applicationContext))
        return Python.getInstance().getModule("tgws_android.engine").also { module = it }
    }

    /** Returns the config error, or null once the proxy is starting. */
    suspend fun start(context: Context, configJson: String, options: EngineOptions): CoreError? =
        withContext(dispatcher) {
            val py = engine(context)
            py.callAttr("set_power_mode", if (idle) "idle" else "active")
            val result = py.callAttr("start", configJson, options.toJson(), listener)
            val error = result?.toString()?.let { CoreError.fromJson(org.json.JSONObject(it)) }
            _appliedConfig.value = if (error == null) configJson else null
            error
        }

    suspend fun stop() = withContext(dispatcher) {
        module?.callAttr("stop")
        _appliedConfig.value = null
    }

    /** [stop] that outlives the caller, e.g. a service being destroyed. */
    fun stopInBackground() {
        scope.launch { stop() }
    }

    fun setPowerMode(idle: Boolean) {
        this.idle = idle
        scope.launch { module?.callAttr("set_power_mode", if (idle) "idle" else "active") }
    }

    fun onNetworkChanged() {
        scope.launch { module?.callAttr("on_network_changed") }
    }

    suspend fun stats(context: Context): ProxyStats = withContext(dispatcher) {
        ProxyStats.fromJson(engine(context).callAttr("status").toString())
    }

    suspend fun logs(context: Context, maxLines: Int = 400): String = withContext(dispatcher) {
        engine(context).callAttr("logs", maxLines).toString()
    }

    suspend fun clearLogs() = withContext(dispatcher) {
        module?.callAttr("clear_logs")
        Unit
    }

    suspend fun schema(context: Context): List<ConfigField> = withContext(dispatcher) {
        cachedSchema ?: ConfigField.listFromJson(engine(context).callAttr("schema").toString())
            .also { cachedSchema = it }
    }

    suspend fun link(context: Context, configJson: String): String? = withContext(dispatcher) {
        engine(context).callAttr("proxy_link", configJson)?.toString()
    }

    /** Warms the runtime up off the main thread, e.g. when the app opens. */
    fun preload(context: Context) {
        scope.launch { runCatching { engine(context) }.onFailure { Log.e(TAG, "Python start failed", it) } }
    }

    fun logDir(context: Context): File = File(context.filesDir, "logs")
}

/** Options for one proxy session that aren't ProxyConfig fields. */
data class EngineOptions(val verbose: Boolean, val logDir: String?, val eagerRewarm: Boolean) {
    fun toJson(): String = org.json.JSONObject()
        .put("verbose", verbose)
        .put("log_dir", logDir ?: org.json.JSONObject.NULL)
        .put("logcat", BuildConfig.DEBUG)
        .put("eager_rewarm", eagerRewarm)
        .toString()
}
