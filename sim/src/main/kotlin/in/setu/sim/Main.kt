package `in`.setu.sim

import kotlin.system.exitProcess

private const val USAGE = """
Setu mesh simulator

  ./gradlew :sim:run --args="[options]"

Options (defaults in brackets):
  --nodes N            phones in the area [300]
  --area M             side of the square area in metres [500]
  --range M            radio range in metres [30]
  --minutes N          simulated duration [60]
  --step S             seconds per step [5]
  --connected F        fraction of phones with internet at any moment [0.02]
  --sos F              fraction of phones raising an SOS at start [0.10]
  --speed MPS          random walk speed, 0 for static [0.5]
  --seed N             random seed [1]
  --sweep KEY=A,B,C    run once per value of KEY (nodes|range|connected|sos)
  --csv                print one CSV row per run instead of a summary
"""

fun main(args: Array<String>) {
    val opts = parse(args) ?: run { println(USAGE); exitProcess(2) }
    val base = opts.toConfig()
    val sweep = opts["sweep"]
    val csv = "csv" in opts
    if (csv) println("nodes,area,range,connected,sos,seed,raised,delivered,acked,ratio,median_s,p90_s,mean_hops,max_hops,exchanges,battery_drop")
    val runs: List<SimConfig> = if (sweep == null) listOf(base) else {
        val (k, vs) = sweep.split("=", limit = 2)
        vs.split(",").map { v ->
            when (k) {
                "nodes" -> base.copy(nodes = v.toInt())
                "range" -> base.copy(radioRangeMetres = v.toDouble())
                "connected" -> base.copy(connectedFraction = v.toDouble())
                "sos" -> base.copy(sosFraction = v.toDouble())
                else -> { println("unknown sweep key $k"); exitProcess(2) }
            }
        }
    }
    for (cfg in runs) {
        val r = Simulator(cfg).run()
        if (csv) {
            println(
                listOf(
                    cfg.nodes, cfg.areaMetres.toInt(), cfg.radioRangeMetres.toInt(), cfg.connectedFraction, cfg.sosFraction, cfg.seed,
                    r.sosRaised, r.sosDelivered, r.sosAcknowledged, "%.3f".format(r.deliveryRatio),
                    r.medianDeliverySeconds(), r.p90DeliverySeconds(), "%.2f".format(r.meanHops()), r.maxHops(),
                    r.totalExchanges, "%.2f".format(r.meanBatteryDrop),
                ).joinToString(","),
            )
        } else {
            println(r.summary())
        }
    }
}

private fun parse(args: Array<String>): Map<String, String>? {
    val out = HashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (!a.startsWith("--")) return null
        val key = a.removePrefix("--")
        if (key == "csv" || key == "help") {
            if (key == "help") return null
            out[key] = "true"
            i++
            continue
        }
        if (i + 1 >= args.size) return null
        out[key] = args[i + 1]
        i += 2
    }
    return out
}

private fun Map<String, String>.toConfig(): SimConfig {
    val d = SimConfig()
    return SimConfig(
        nodes = this["nodes"]?.toInt() ?: d.nodes,
        areaMetres = this["area"]?.toDouble() ?: d.areaMetres,
        radioRangeMetres = this["range"]?.toDouble() ?: d.radioRangeMetres,
        durationSeconds = this["minutes"]?.toLong()?.times(60) ?: d.durationSeconds,
        stepSeconds = this["step"]?.toLong() ?: d.stepSeconds,
        connectedFraction = this["connected"]?.toDouble() ?: d.connectedFraction,
        sosFraction = this["sos"]?.toDouble() ?: d.sosFraction,
        walkSpeed = this["speed"]?.toDouble() ?: d.walkSpeed,
        seed = this["seed"]?.toLong() ?: d.seed,
    )
}
