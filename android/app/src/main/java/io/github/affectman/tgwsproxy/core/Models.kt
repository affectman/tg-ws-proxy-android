package io.github.affectman.tgwsproxy.core

import org.json.JSONArray
import org.json.JSONObject

/** Proxy lifecycle as reported by tgws_android/engine.py. */
enum class ProxyState {
    STOPPED, STARTING, RUNNING, ERROR;

    val isActive: Boolean get() = this == STARTING || this == RUNNING

    companion object {
        fun parse(value: String): ProxyState = when (value) {
            "starting" -> STARTING
            "running" -> RUNNING
            "error" -> ERROR
            else -> STOPPED
        }
    }
}

/** An error from the adapter; [code] is a stable id the UI translates. */
data class CoreError(
    val code: String,
    val field: String? = null,
    val detail: String? = null,
    val suggestedPort: Int? = null,
) {
    companion object {
        fun fromJson(o: JSONObject?): CoreError? = o?.let {
            CoreError(
                code = it.getString("code"),
                field = it.optStringOrNull("field"),
                detail = it.optStringOrNull("detail"),
                suggestedPort = if (it.isNull("suggested_port")) null else it.optInt("suggested_port"),
            )
        }
    }
}

data class EngineState(
    val state: ProxyState = ProxyState.STOPPED,
    val error: CoreError? = null,
    val port: Int = 0,
) {
    companion object {
        fun fromJson(json: String): EngineState {
            val o = JSONObject(json)
            return EngineState(
                state = ProxyState.parse(o.getString("state")),
                error = CoreError.fromJson(o.optJSONObject("error")),
                port = o.optInt("port"),
            )
        }
    }
}

/** Counters of the running core; read only while a screen shows them. */
data class ProxyStats(
    val state: ProxyState = ProxyState.STOPPED,
    val coreVersion: String = "",
    val uptimeSeconds: Long = 0,
    val poolSize: Int = 0,
    val idle: Boolean = false,
    val active: Int = 0,
    val total: Int = 0,
    val bytesUp: Long = 0,
    val bytesDown: Long = 0,
    val logSeq: Long = 0,
) {
    companion object {
        fun fromJson(json: String): ProxyStats {
            val o = JSONObject(json)
            val s = o.optJSONObject("stats") ?: JSONObject()
            return ProxyStats(
                state = ProxyState.parse(o.getString("state")),
                coreVersion = o.optString("core_version"),
                uptimeSeconds = o.optLong("uptime_s"),
                poolSize = o.optInt("pool_size"),
                idle = o.optString("power_mode") == "idle",
                active = s.optInt("connections_active"),
                total = s.optInt("connections_total"),
                bytesUp = s.optLong("bytes_up"),
                bytesDown = s.optLong("bytes_down"),
                logSeq = o.optLong("log_seq"),
            )
        }
    }
}

/** Kinds of upstream ProxyConfig fields, see tgws_android/settings.py. */
enum class FieldKind { BOOL, INT, FLOAT, STR, STR_LIST, DC_MAP, UNKNOWN }

/** One ProxyConfig field as described by the adapter's schema(). */
data class ConfigField(val name: String, val kind: FieldKind, val default: Any?) {
    companion object {
        fun listFromJson(json: String): List<ConfigField> {
            val array = JSONArray(json)
            return (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                val kind = when (o.getString("kind")) {
                    "bool" -> FieldKind.BOOL
                    "int" -> FieldKind.INT
                    "float" -> FieldKind.FLOAT
                    "str" -> FieldKind.STR
                    "str_list" -> FieldKind.STR_LIST
                    "dc_map" -> FieldKind.DC_MAP
                    else -> FieldKind.UNKNOWN
                }
                ConfigField(o.getString("name"), kind, if (o.isNull("default")) null else o.get("default"))
            }
        }
    }
}

internal fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name)) null else optString(name)
