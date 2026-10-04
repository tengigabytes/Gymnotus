package io.github.tengigabytes.gymnotus.power

/**
 * A monitor name split into its parts. Pixel reports MEASUREMENT rails as `[S2M_VDD_CPU2]:CPU(BIG)`.
 *
 * @property rail text inside the brackets, or the whole name when it does not follow that pattern
 * @property subsystem label after the colon without its parenthesised part (`CPU`), null when absent
 * @property variant parenthesised part of the label (`BIG`), null when absent
 */
data class RailName(val rail: String, val subsystem: String?, val variant: String?)

private val LABELLED = Regex("""^\[(.+)]:(.+)$""")
private val VARIANT = Regex("""^(.+?)\((.+)\)$""")

fun parseRailName(name: String): RailName {
    val labelled = LABELLED.matchEntire(name) ?: return RailName(name, null, null)
    val (rail, label) = labelled.destructured
    val variant = VARIANT.matchEntire(label) ?: return RailName(rail, label, null)
    return RailName(rail, variant.groupValues[1], variant.groupValues[2])
}

enum class TreeGrouping {
    /** By the subsystem label the device puts in the monitor name. */
    SUBSYSTEM,

    /** By rail-name prefix: `S<n>M`, `S<n>S`, `VSYS_PWR`. What these sources are electrically is not verified. */
    SOURCE,
}

data class TreeInput(val index: Int, val name: String, val powerMw: Double?)

data class TreeLeaf(val index: Int, val rail: String, val detail: String?, val powerMw: Double?)

/**
 * @property powerMw sum over the leaves that have data; null when none has
 * @property missing leaves without data, i.e. left out of [powerMw]
 */
data class TreeGroup(val name: String, val powerMw: Double?, val missing: Int, val leaves: List<TreeLeaf>)

/**
 * @property hasBatteryRail whether the device reports [PowerTreeBuilder.BATTERY_RAIL] at all
 * @property batteryRailIndex monitor index of that rail, null when the device has none
 * @property batteryRailMw power of that rail; it is the root only when [batteryIsRoot]
 * @property railsSumMw sum of every other rail that has data
 * @property unmeasuredMw battery rail minus the other rails, only when [batteryIsRoot]; negative values are
 * shown as they are (the 20 s window may straddle a plug event)
 * @property missing rails (other than the battery rail) without data
 */
data class PowerTree(
    val hasBatteryRail: Boolean,
    val batteryRailIndex: Int?,
    val batteryRailMw: Double?,
    val batteryIsRoot: Boolean,
    val railsSumMw: Double?,
    val unmeasuredMw: Double?,
    val missing: Int,
    val groups: List<TreeGroup>,
) {
    /** Denominator for the share of each node. */
    val baseMw: Double? get() = if (batteryIsRoot) batteryRailMw else railsSumMw
}

/**
 * Two-level grouping of MEASUREMENT rails. The API gives no topology, so the groups are by name only.
 *
 * The one piece of topology used: on battery power, [BATTERY_RAIL] is the root and all other rails are below it.
 * Observed on Pixel 10 Pro (blazer) only: unplugged, that rail exceeded the sum of the others in all four 20 s
 * windows measured and tracked BatteryManager current x voltage (about 200 mW above it). While charging at a
 * steady 1.4 W it read 12-14 mW, so it counts discharge only and is not the device total on external power.
 */
object PowerTreeBuilder {
    const val BATTERY_RAIL = "VSYS_PWR_VBATT"
    const val UNLABELLED = "(unlabelled)"
    const val OTHER_SOURCE = "(other)"

    private val MAIN = Regex("""^S\d+M(_.*)?$""")
    private val SUB = Regex("""^S\d+S(_.*)?$""")

    /**
     * @param onExternalPower null when the power source is unknown, which is treated like external power
     * @param map device facts, if a map exists for this device; without one, sources are guessed from rail names
     * and the battery rail is assumed to be [BATTERY_RAIL]
     */
    fun build(rails: List<TreeInput>, grouping: TreeGrouping, onExternalPower: Boolean?, map: DeviceMap? = null): PowerTree {
        val batteryRail = map?.batteryRail ?: BATTERY_RAIL
        val (batteryRails, loads) = rails.partition { parseRailName(it.name).rail == batteryRail }
        val batteryMw = batteryRails.firstOrNull()?.powerMw
        val groups = loads
            .groupBy(
                keySelector = { groupOf(parseRailName(it.name), grouping, map) },
                valueTransform = { input ->
                    val name = parseRailName(input.name)
                    val detail = when (grouping) {
                        TreeGrouping.SUBSYSTEM -> name.variant
                        TreeGrouping.SOURCE -> name.subsystem?.let { s -> name.variant?.let { "$s($it)" } ?: s }
                    }
                    TreeLeaf(input.index, name.rail, detail, input.powerMw)
                },
            )
            .map { (name, leaves) ->
                TreeGroup(name, sumOrNull(leaves.map { it.powerMw }), leaves.count { it.powerMw == null }, leaves.sortedWith(byPower { it.powerMw }))
            }
            .sortedWith(byPower { it.powerMw })
        val railsSumMw = sumOrNull(loads.map { it.powerMw })
        val batteryIsRoot = onExternalPower == false && batteryMw != null
        return PowerTree(
            hasBatteryRail = batteryRails.isNotEmpty(),
            batteryRailIndex = batteryRails.firstOrNull()?.index,
            batteryRailMw = batteryMw,
            batteryIsRoot = batteryIsRoot,
            railsSumMw = railsSumMw,
            unmeasuredMw = if (batteryIsRoot && railsSumMw != null) batteryMw!! - railsSumMw else null,
            missing = loads.count { it.powerMw == null },
            groups = groups,
        )
    }

    /** Name of the group a rail belongs to under [grouping]; the same names the tree's groups carry. */
    fun groupOf(name: RailName, grouping: TreeGrouping, map: DeviceMap?) = when (grouping) {
        TreeGrouping.SUBSYSTEM -> name.subsystem ?: UNLABELLED
        TreeGrouping.SOURCE -> map?.sourceOf(name.rail)?.name ?: when {
            MAIN.matches(name.rail) -> "S<n>M"
            SUB.matches(name.rail) -> "S<n>S"
            name.rail.startsWith("VSYS_PWR") -> "VSYS_PWR"
            else -> OTHER_SOURCE
        }
    }

    // No data is not 0 W: a sum exists only if at least one term does.
    private fun sumOrNull(values: List<Double?>): Double? = values.filterNotNull().takeIf { it.isNotEmpty() }?.sum()

    /** Largest first, entries without data last. */
    private fun <T> byPower(power: (T) -> Double?) = compareByDescending<T> { power(it) ?: Double.NEGATIVE_INFINITY }
}
