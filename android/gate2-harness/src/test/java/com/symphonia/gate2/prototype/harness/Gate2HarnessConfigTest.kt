package com.symphonia.gate2.prototype.harness

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Gate2HarnessConfigTest {

    private class InMemorySharedPreferences : SharedPreferences {
        private val map = linkedMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = map.toMutableMap()
        override fun getString(key: String, defValue: String?): String? = (map[key] as? String) ?: defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String): Boolean = map.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = linkedMapOf<String, Any?>()
            private var clear = false

            override fun putString(key: String, value: String?) = apply { pending[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
            override fun putInt(key: String, value: Int) = apply { pending[key] = value }
            override fun putLong(key: String, value: Long) = apply { pending[key] = value }
            override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
            override fun remove(key: String) = apply { pending[key] = null }
            override fun clear() = apply { clear = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clear) map.clear()
                for ((k, v) in pending) {
                    if (v == null) map.remove(k) else map[k] = v
                }
            }
        }
    }

    @Test
    fun persistsSignalingUrlAndDemoModeAcrossInstances() {
        val prefs = InMemorySharedPreferences()
        val initial = Gate2HarnessConfig(prefs)
        assertEquals(Gate2HarnessConfig.DEFAULT_SIGNALING_URL, initial.load().signalingUrl)
        assertFalse(initial.load().demoMode)

        initial.saveSignalingUrl("  ws://10.77.0.1:8080/gate2  ")
        initial.saveDemoMode(true)

        val restored = Gate2HarnessConfig(prefs).load()
        assertEquals("ws://10.77.0.1:8080/gate2", restored.signalingUrl)
        assertTrue(restored.demoMode)
    }

    @Test
    fun appliesAdbValuesAndIgnoresBlankUrls() {
        val prefs = InMemorySharedPreferences()
        val config = Gate2HarnessConfig(prefs)

        val updated = config.applyValues(
            signalingUrl = "  ws://10.77.0.1:8080/gate2  ",
            demoModeRaw = "true"
        )
        assertEquals("ws://10.77.0.1:8080/gate2", updated.signalingUrl)
        assertTrue(updated.demoMode)

        val second = config.applyValues(
            signalingUrl = "   ",
            demoModeRaw = false
        )
        assertEquals("ws://10.77.0.1:8080/gate2", second.signalingUrl)
        assertFalse(second.demoMode)
    }
}
