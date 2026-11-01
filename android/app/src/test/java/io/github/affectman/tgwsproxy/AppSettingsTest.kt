package io.github.affectman.tgwsproxy

import io.github.affectman.tgwsproxy.data.AppSettings
import io.github.affectman.tgwsproxy.data.BatteryProfile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppSettingsTest {
    private val secret = "0123456789abcdef0123456789abcdef"

    @Test
    fun coreConfigUsesManagedFieldsOverOverrides() {
        val settings = AppSettings(
            secret = secret,
            port = 1500,
            profile = BatteryProfile.SAVER,
            overrides = mapOf("port" to 9999, "pool_size" to 8, "fallback_cfproxy" to false),
        )
        val o = JSONObject(settings.coreConfigJson())
        assertEquals(secret, o.getString("secret"))
        assertEquals(1500, o.getInt("port"))
        assertEquals(0, o.getInt("pool_size"))
        assertFalse(o.getBoolean("fallback_cfproxy"))
    }

    @Test
    fun profilePoolSizes() {
        assertEquals(4, BatteryProfile.SPEED.poolSize)
        assertEquals(2, BatteryProfile.BALANCED.poolSize)
        assertEquals(0, BatteryProfile.SAVER.poolSize)
    }

    @Test
    fun overridesSurviveJsonRoundTrip() {
        val map = mapOf(
            "dc_redirects" to listOf("2:1.2.3.4", "4:5.6.7.8"),
            "force_test_dc" to true,
            "buffer_size" to 65536,
            "fake_tls_domain" to "example.com",
        )
        assertEquals(map, AppSettings.overridesFromJson(AppSettings.overridesToJson(map)))
    }

    @Test
    fun nullOverrideRemovesKey() {
        val s = AppSettings(secret = secret).withOverride("host", "0.0.0.0").withOverride("host", null)
        assertFalse(s.overrides.containsKey("host"))
    }

    @Test
    fun brokenOverridesJsonIsIgnored() {
        assertEquals(emptyMap<String, Any?>(), AppSettings.overridesFromJson("{not json"))
    }
}
