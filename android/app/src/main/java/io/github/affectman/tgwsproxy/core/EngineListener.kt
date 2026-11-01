package io.github.affectman.tgwsproxy.core

import androidx.annotation.Keep

/**
 * Receives state events from Python (`listener.onEvent(json)` in engine.py).
 *
 * Called on the proxy's own Python thread, so it only hands the event over.
 * Kept by name in proguard-rules.pro: R8 can't see calls made from Python.
 */
@Keep
class EngineListener(private val handler: (String) -> Unit) {
    @Keep
    fun onEvent(json: String) = handler(json)
}
