package io.github.affectman.tgwsproxy

import io.github.affectman.tgwsproxy.core.ConfigField
import io.github.affectman.tgwsproxy.core.EngineState
import io.github.affectman.tgwsproxy.core.FieldKind
import io.github.affectman.tgwsproxy.core.ProxyState
import io.github.affectman.tgwsproxy.core.ProxyStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The JSON shapes here are the ones tgws_android/engine.py produces. */
class ModelsTest {
    @Test
    fun engineEventWithError() {
        val s = EngineState.fromJson(
            """{"state": "error", "port": 1443,
                "error": {"code": "port_in_use", "field": null, "detail": "1443", "suggested_port": 1444}}"""
        )
        assertEquals(ProxyState.ERROR, s.state)
        val error = s.error!!
        assertEquals("port_in_use", error.code)
        assertEquals(1444, error.suggestedPort)
        assertNull(error.field)
    }

    @Test
    fun unknownStateMeansStopped() {
        assertEquals(ProxyState.STOPPED, EngineState.fromJson("""{"state": "weird", "error": null, "port": 1}""").state)
    }

    @Test
    fun statsParse() {
        val st = ProxyStats.fromJson(
            """{"state": "running", "core_version": "1.11.3", "uptime_s": 65, "power_mode": "idle",
                "pool_size": 0, "log_seq": 9,
                "stats": {"connections_active": 3, "connections_total": 10, "bytes_up": 100, "bytes_down": 2048}}"""
        )
        assertEquals(ProxyState.RUNNING, st.state)
        assertTrue(st.idle)
        assertEquals(3, st.active)
        assertEquals(2048L, st.bytesDown)
    }

    @Test
    fun schemaKinds() {
        val fields = ConfigField.listFromJson(
            """[{"name": "port", "kind": "int", "default": 1443},
                {"name": "dc_redirects", "kind": "dc_map", "default": ["2:1.1.1.1"]},
                {"name": "future", "kind": "tuple_of_things", "default": null}]"""
        )
        assertEquals(FieldKind.INT, fields[0].kind)
        assertEquals(FieldKind.DC_MAP, fields[1].kind)
        assertEquals(FieldKind.UNKNOWN, fields[2].kind)
        assertNull(fields[2].default)
    }
}
