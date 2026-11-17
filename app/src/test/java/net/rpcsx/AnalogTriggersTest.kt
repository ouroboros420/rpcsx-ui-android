package net.rpcsx

import net.rpcsx.utils.AnalogTriggerConfig
import net.rpcsx.utils.TriggerArbiter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalogTriggersTest {
    @Test
    fun linearMapping() {
        val c = AnalogTriggerConfig(enabled = true, deadzonePercent = 0, curvePercent = 100)
        assertEquals(0, c.axisToPressure(0f))
        assertEquals(128, c.axisToPressure(0.5f))
        assertEquals(255, c.axisToPressure(1f))
        assertEquals(255, c.axisToPressure(1.5f))
        assertEquals(0, c.axisToPressure(Float.NaN))
    }

    @Test
    fun deadzoneAndCurve() {
        val dz = AnalogTriggerConfig(enabled = true, deadzonePercent = 10, curvePercent = 100)
        assertEquals(0, dz.axisToPressure(0.1f))
        assertTrue(dz.axisToPressure(0.1001f) >= 1)
        assertEquals(255, dz.axisToPressure(1f))

        val curve = AnalogTriggerConfig(enabled = true, deadzonePercent = 0, curvePercent = 200)
        assertEquals(64, curve.axisToPressure(0.5f))
        assertEquals(255, curve.axisToPressure(1f))
    }

    @Test
    fun disabledIsLegacyDigital() {
        val off = AnalogTriggerConfig(enabled = false)
        assertEquals(0, off.axisToPressure(0.05f))
        assertEquals(255, off.axisToPressure(0.2f))
    }

    @Test
    fun digitalModeKeyStillWorks() {
        val a = TriggerArbiter()
        a.onAxis(0, 0)
        a.onKey(true, 1000)
        assertEquals(255, a.pressure)
        a.onKey(true, 1050) // repeat
        assertEquals(255, a.pressure)
        a.onKey(false, 1100)
        assertEquals(0, a.pressure)
    }

    @Test
    fun keyDoesNotSnapAnalogTriggerToFull() {
        val a = TriggerArbiter()
        a.onAxis(40, 1000)
        a.onKey(true, 1005)
        assertEquals(40, a.pressure)
        a.onKey(true, 1100) // repeat
        a.onAxis(90, 1100)
        assertEquals(90, a.pressure)
        a.onAxis(0, 1200) // released before the key-up arrives
        assertEquals(0, a.pressure)
        a.onKey(false, 1210)
        assertEquals(0, a.pressure)
    }

    @Test
    fun axisClaimsPressWhenKeyArrivesFirst() {
        val a = TriggerArbiter()
        a.onKey(true, 2000)
        assertEquals(255, a.pressure)
        a.onAxis(30, 2004)
        assertEquals(30, a.pressure)
        a.onAxis(0, 2100)
        assertEquals(0, a.pressure)
    }

    @Test
    fun graceWindowAfterAxisActivity() {
        val a = TriggerArbiter()
        a.onAxis(20, 3000)
        a.onAxis(0, 3010)
        a.onKey(true, 3050)
        assertEquals(0, a.pressure)
        a.onKey(false, 3060)
        a.onKey(true, 4000)
        assertEquals(255, a.pressure)
    }
}
