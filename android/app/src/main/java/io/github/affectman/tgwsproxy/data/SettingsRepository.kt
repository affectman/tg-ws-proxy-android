package io.github.affectman.tgwsproxy.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.affectman.tgwsproxy.service.BootReceiver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.SecureRandom

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Settings and small bits of app state, stored with DataStore. */
class SettingsRepository(context: Context) {
    private val appContext = context.applicationContext
    private val store = appContext.dataStore

    val settings: Flow<AppSettings> = store.data.map { it.toSettings() }

    /** The release offered by the last update check, or null. */
    val pendingUpdate: Flow<UpdateInfo?> = store.data.map { prefs ->
        UpdateInfo.fromJson(prefs[Keys.PENDING_UPDATE])
    }

    val lastUpdateCheck: Flow<Long> = store.data.map { it[Keys.LAST_UPDATE_CHECK] ?: 0L }

    /** Current settings, generating the proxy secret on first use. */
    suspend fun current(): AppSettings {
        val loaded = settings.first()
        if (loaded.secret.isNotEmpty()) return loaded
        val secret = newSecret()
        store.edit { if (it[Keys.SECRET].isNullOrEmpty()) it[Keys.SECRET] = secret }
        return settings.first()
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        val prefs = store.edit { prefs ->
            val next = transform(prefs.toSettings())
            prefs[Keys.SECRET] = next.secret
            prefs[Keys.PORT] = next.port
            prefs[Keys.PROFILE] = next.profile.name
            prefs[Keys.IDLE_SCREEN_OFF] = next.idleWhenScreenOff
            prefs[Keys.START_ON_BOOT] = next.startOnBoot
            prefs[Keys.VERBOSE] = next.verboseLogs
            prefs[Keys.LOG_FILE] = next.logToFile
            prefs[Keys.CHECK_UPDATES] = next.checkUpdates
            prefs[Keys.OVERRIDES] = AppSettings.overridesToJson(next.overrides)
        }
        syncBootReceiver(prefs)
    }

    suspend fun regenerateSecret() = update { it.copy(secret = newSecret()) }

    suspend fun setPendingUpdate(info: UpdateInfo?) {
        store.edit {
            if (info == null) it.remove(Keys.PENDING_UPDATE) else it[Keys.PENDING_UPDATE] = info.toJson()
        }
    }

    suspend fun markUpdateChecked(at: Long) {
        store.edit { it[Keys.LAST_UPDATE_CHECK] = at }
    }

    /** True while the user wants the proxy on; lets it come back after an app update. */
    suspend fun setWantRunning(want: Boolean) {
        syncBootReceiver(store.edit { it[Keys.WANT_RUNNING] = want })
    }

    suspend fun wantRunning(): Boolean = store.data.first()[Keys.WANT_RUNNING] ?: false

    private fun syncBootReceiver(prefs: Preferences) {
        BootReceiver.sync(appContext, prefs[Keys.START_ON_BOOT] ?: false, prefs[Keys.WANT_RUNNING] ?: false)
    }

    private fun Preferences.toSettings() = AppSettings(
        secret = this[Keys.SECRET].orEmpty(),
        port = this[Keys.PORT] ?: AppSettings.DEFAULT_PORT,
        profile = this[Keys.PROFILE]?.let { name -> BatteryProfile.entries.firstOrNull { it.name == name } }
            ?: BatteryProfile.BALANCED,
        idleWhenScreenOff = this[Keys.IDLE_SCREEN_OFF] ?: true,
        startOnBoot = this[Keys.START_ON_BOOT] ?: false,
        verboseLogs = this[Keys.VERBOSE] ?: false,
        logToFile = this[Keys.LOG_FILE] ?: false,
        checkUpdates = this[Keys.CHECK_UPDATES] ?: true,
        overrides = AppSettings.overridesFromJson(this[Keys.OVERRIDES]),
    )

    private object Keys {
        val SECRET = stringPreferencesKey("secret")
        val PORT = intPreferencesKey("port")
        val PROFILE = stringPreferencesKey("battery_profile")
        val IDLE_SCREEN_OFF = booleanPreferencesKey("idle_when_screen_off")
        val START_ON_BOOT = booleanPreferencesKey("start_on_boot")
        val VERBOSE = booleanPreferencesKey("verbose_logs")
        val LOG_FILE = booleanPreferencesKey("log_to_file")
        val CHECK_UPDATES = booleanPreferencesKey("check_updates")
        val OVERRIDES = stringPreferencesKey("core_overrides")
        val PENDING_UPDATE = stringPreferencesKey("pending_update")
        val LAST_UPDATE_CHECK = longPreferencesKey("last_update_check")
        val WANT_RUNNING = booleanPreferencesKey("want_running")
    }

    companion object {
        fun newSecret(): String {
            val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
