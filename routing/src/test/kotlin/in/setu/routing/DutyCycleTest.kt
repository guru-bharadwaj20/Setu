package `in`.setu.routing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DutyCycleTest {
    @Test
    fun `tiers follow the battery table`() {
        assertEquals(DutyCycle.HIGH, DutyCycle.forState(100, false))
        assertEquals(DutyCycle.HIGH, DutyCycle.forState(51, false))
        assertEquals(DutyCycle.MEDIUM, DutyCycle.forState(50, false))
        assertEquals(DutyCycle.MEDIUM, DutyCycle.forState(31, false))
        assertEquals(DutyCycle.LOW, DutyCycle.forState(30, false))
        assertEquals(DutyCycle.LOW, DutyCycle.forState(16, false))
        assertEquals(DutyCycle.CRITICAL, DutyCycle.forState(15, false))
        assertEquals(DutyCycle.CRITICAL, DutyCycle.forState(0, false))
    }

    @Test
    fun `active sos or charging forces the top tier`() {
        assertEquals(DutyCycle.HIGH, DutyCycle.forState(3, activeSos = true))
        assertEquals(DutyCycle.HIGH, DutyCycle.forState(3, activeSos = false, charging = true))
    }

    @Test
    fun `active ratios decrease with battery`() {
        val ratios = listOf(DutyCycle.HIGH, DutyCycle.MEDIUM, DutyCycle.LOW, DutyCycle.CRITICAL).map { it.activeRatio }
        assertEquals(ratios.sortedDescending(), ratios)
        assertTrue(DutyCycle.CRITICAL.activeRatio < 0.03)
    }

    @Test
    fun `courier mode stops at the floor`() {
        assertTrue(DutyCycle.shouldRelay(5, false))
        assertFalse(DutyCycle.shouldRelay(4, false))
        assertTrue(DutyCycle.shouldRelay(1, true))
        assertTrue(DutyCycle.shouldRelay(1, false, charging = true))
    }
}
