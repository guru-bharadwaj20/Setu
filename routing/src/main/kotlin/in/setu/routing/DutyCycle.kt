package `in`.setu.routing

/**
 * Battery-aware radio schedule. Discovery runs for [discoverySeconds], then the
 * radio sleeps for [sleepSeconds]. See docs/ARCHITECTURE.md, "Battery policy".
 */
data class DutyCycle(val discoverySeconds: Int, val sleepSeconds: Int) {
    val periodSeconds: Int get() = discoverySeconds + sleepSeconds

    /** Fraction of time the radio is active. */
    val activeRatio: Double get() = discoverySeconds.toDouble() / periodSeconds

    companion object {
        val HIGH = DutyCycle(20, 40)
        val MEDIUM = DutyCycle(15, 90)
        val LOW = DutyCycle(10, 180)
        val CRITICAL = DutyCycle(10, 420)

        /** Below this the app refuses to relay for others unless the user has an active SOS. */
        const val COURIER_MIN_BATTERY = 5

        /**
         * @param batteryPercent 0..100
         * @param activeSos true when this phone has an unacknowledged SOS of its own
         * @param charging true when plugged in; the radio can stay in the top tier
         */
        fun forState(batteryPercent: Int, activeSos: Boolean, charging: Boolean = false): DutyCycle = when {
            activeSos || charging -> HIGH
            batteryPercent > 50 -> HIGH
            batteryPercent > 30 -> MEDIUM
            batteryPercent > 15 -> LOW
            else -> CRITICAL
        }

        fun shouldRelay(batteryPercent: Int, activeSos: Boolean, charging: Boolean = false): Boolean =
            activeSos || charging || batteryPercent >= COURIER_MIN_BATTERY
    }
}

/** How often a phone re-sends its own SOS with a fresh timestamp. */
const val SOS_RESEND_INTERVAL_SECONDS = 600L
