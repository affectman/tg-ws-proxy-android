package io.github.affectman.tgwsproxy.ui

import android.content.Context
import io.github.affectman.tgwsproxy.R
import io.github.affectman.tgwsproxy.core.CoreError
import java.util.Locale

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return String.format(Locale.getDefault(), if (value < 10) "%.1f %s" else "%.0f %s", value, units[unit])
}

fun formatUptime(context: Context, seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        h > 0 -> context.getString(R.string.uptime_hm, h, m)
        m > 0 -> context.getString(R.string.uptime_ms, m, s)
        else -> context.getString(R.string.uptime_s, s)
    }
}

/** A user-facing message for an adapter error code. */
fun describeError(context: Context, e: CoreError): String = when (e.code) {
    "bad_secret" -> context.getString(R.string.err_bad_secret)
    "bad_port" -> context.getString(R.string.err_bad_port, e.detail ?: "")
    "bad_host" -> context.getString(R.string.err_bad_host, e.detail ?: "")
    "bad_dc_ip" -> context.getString(R.string.err_bad_dc_ip, e.detail ?: "")
    "bad_fake_tls" -> context.getString(R.string.err_bad_fake_tls, e.detail ?: "")
    "bad_value" -> context.getString(R.string.err_bad_value, e.field ?: "", e.detail ?: "")
    "port_in_use" -> context.getString(R.string.err_port_in_use, e.detail ?: "")
    "permission" -> context.getString(R.string.err_permission, e.detail ?: "")
    "address" -> context.getString(R.string.err_address, e.detail ?: "")
    else -> context.getString(R.string.err_unknown, e.detail ?: e.code)
}
