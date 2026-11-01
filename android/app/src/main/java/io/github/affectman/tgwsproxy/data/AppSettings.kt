package io.github.affectman.tgwsproxy.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * How many spare WebSocket connections the core keeps warm per DC.
 *
 * Every warm connection is re-created every 120 s by the core, so this is the
 * main battery knob; live Telegram connections don't depend on it.
 */
enum class BatteryProfile(val poolSize: Int) {
    SPEED(4),
    BALANCED(2),
    SAVER(0),
}

/**
 * Everything the user can change.
 *
 * [overrides] holds upstream ProxyConfig fields by their own names (see
 * tgws_android/settings.py): the curated screens and the generated
 * "Advanced" section both write there, so new upstream options need no code.
 */
data class AppSettings(
    val secret: String = "",
    val port: Int = DEFAULT_PORT,
    val profile: BatteryProfile = BatteryProfile.BALANCED,
    val idleWhenScreenOff: Boolean = true,
    val startOnBoot: Boolean = false,
    val verboseLogs: Boolean = false,
    val logToFile: Boolean = false,
    val checkUpdates: Boolean = true,
    val overrides: Map<String, Any?> = emptyMap(),
) {
    /** The JSON engine.start() expects: ProxyConfig field names only. */
    fun coreConfigJson(): String {
        val o = JSONObject()
        for ((key, value) in overrides) {
            if (key in MANAGED_FIELDS) continue
            o.put(key, value.toJsonValue())
        }
        o.put("secret", secret)
        o.put("port", port)
        o.put("pool_size", profile.poolSize)
        return o.toString()
    }

    fun override(name: String): Any? = overrides[name]

    fun withOverride(name: String, value: Any?): AppSettings =
        copy(overrides = if (value == null) overrides - name else overrides + (name to value))

    companion object {
        const val DEFAULT_PORT = 1443

        /** Fields with their own controls; never written through [overrides]. */
        val MANAGED_FIELDS = setOf("secret", "port", "pool_size")

        fun overridesToJson(map: Map<String, Any?>): String {
            val o = JSONObject()
            for ((k, v) in map) o.put(k, v.toJsonValue())
            return o.toString()
        }

        fun overridesFromJson(json: String?): Map<String, Any?> {
            if (json.isNullOrBlank()) return emptyMap()
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
            return o.keys().asSequence().associateWith { key -> o.get(key).fromJsonValue() }
        }
    }
}

private fun Any?.toJsonValue(): Any = when (this) {
    null -> JSONObject.NULL
    is List<*> -> JSONArray(this.map { it.toJsonValue() })
    else -> this
}

private fun Any?.fromJsonValue(): Any? = when (this) {
    JSONObject.NULL -> null
    is JSONArray -> (0 until length()).map { get(it).fromJsonValue() }
    else -> this
}
