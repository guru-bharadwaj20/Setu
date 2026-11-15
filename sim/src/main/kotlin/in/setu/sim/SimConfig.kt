package `in`.setu.sim

/**
 * Parameters of one simulation run. Distances are metres, times are seconds.
 * Defaults model a dense relief camp: 300 phones in a 500 m square with 30 m
 * Bluetooth range, 2 % of phones briefly seeing one bar of signal.
 */
data class SimConfig(
    val nodes: Int = 300,
    val areaMetres: Double = 500.0,
    val radioRangeMetres: Double = 30.0,
    val durationSeconds: Long = 3600,
    val stepSeconds: Long = 5,
    /** Fraction of nodes that are connected to the internet at any moment. */
    val connectedFraction: Double = 0.02,
    /** Fraction of nodes that raise an SOS at the start. */
    val sosFraction: Double = 0.10,
    /** Metres per second of random-walk movement. 0 = static. */
    val walkSpeed: Double = 0.5,
    /** Probability per step that a connected node loses its link, and vice versa. */
    val linkFlipProbability: Double = 0.05,
    /** Initial battery, uniformly drawn from this range. */
    val batteryMin: Int = 20,
    val batteryMax: Int = 100,
    /** Approximate battery drain per hour of fully active radio, in percent. */
    val drainPerActiveHour: Double = 8.0,
    val seed: Long = 1,
) {
    init {
        require(nodes > 0 && areaMetres > 0 && radioRangeMetres > 0)
        require(durationSeconds > 0 && stepSeconds > 0 && stepSeconds <= durationSeconds)
        require(connectedFraction in 0.0..1.0 && sosFraction in 0.0..1.0)
        require(batteryMin in 0..100 && batteryMax in batteryMin..100)
    }

    val steps: Int get() = (durationSeconds / stepSeconds).toInt()
}
