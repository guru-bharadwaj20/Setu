package `in`.setu.sim

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SimulatorTest {
    @Test
    fun `dense camp delivers nearly every sos within the hour`() {
        val r = Simulator(SimConfig(nodes = 200, areaMetres = 300.0, durationSeconds = 3600, seed = 7)).run()
        assertEquals(20, r.sosRaised)
        assertTrue(r.deliveryRatio >= 0.95, r.summary())
        assertTrue(r.ackRatio >= 0.5, r.summary())
        assertTrue(r.medianDeliverySeconds() in 0..1800, r.summary())
        assertTrue(r.maxHops() in 1..31, r.summary())
    }

    @Test
    fun `no connected phone means nothing is delivered`() {
        val r = Simulator(SimConfig(nodes = 50, connectedFraction = 0.0, linkFlipProbability = 0.0, durationSeconds = 600)).run()
        assertEquals(0, r.sosDelivered)
        assertTrue(r.totalExchanges > 0)
    }

    @Test
    fun `isolated phones never exchange`() {
        val r = Simulator(SimConfig(nodes = 20, areaMetres = 5000.0, radioRangeMetres = 1.0, walkSpeed = 0.0, durationSeconds = 600, connectedFraction = 0.0, linkFlipProbability = 0.0)).run()
        assertEquals(0, r.totalExchanges)
    }

    @Test
    fun `runs are reproducible for a seed`() {
        val cfg = SimConfig(nodes = 80, durationSeconds = 900, seed = 3)
        val a = Simulator(cfg).run()
        val b = Simulator(cfg).run()
        assertEquals(a.sosDelivered, b.sosDelivered)
        assertEquals(a.totalExchanges, b.totalExchanges)
        assertEquals(a.deliverySeconds.sorted(), b.deliverySeconds.sorted())
    }

    @Test
    fun `battery drops stay modest over an hour`() {
        val r = Simulator(SimConfig(nodes = 100, durationSeconds = 3600, seed = 11)).run()
        assertTrue(r.meanBatteryDrop < 8.0, "drop ${r.meanBatteryDrop}")
        assertTrue(r.meanBatteryDrop > 0.0)
    }
}
