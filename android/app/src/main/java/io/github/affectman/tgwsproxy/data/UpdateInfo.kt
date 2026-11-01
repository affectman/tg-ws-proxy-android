package io.github.affectman.tgwsproxy.data

import org.json.JSONObject

/** A newer release found on GitHub. */
data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val pageUrl: String,
    val notes: String,
) {
    fun toJson(): String = JSONObject()
        .put("version_code", versionCode)
        .put("version_name", versionName)
        .put("apk_url", apkUrl)
        .put("page_url", pageUrl)
        .put("notes", notes)
        .toString()

    companion object {
        fun fromJson(json: String?): UpdateInfo? {
            if (json.isNullOrBlank()) return null
            return runCatching {
                val o = JSONObject(json)
                UpdateInfo(
                    versionCode = o.getInt("version_code"),
                    versionName = o.getString("version_name"),
                    apkUrl = o.getString("apk_url"),
                    pageUrl = o.getString("page_url"),
                    notes = o.optString("notes"),
                )
            }.getOrNull()
        }
    }
}
