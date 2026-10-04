package io.github.tengigabytes.gymnotus.power

/**
 * A source or a subsystem in the flow diagram.
 *
 * @property indices monitor indices of the rails that pass through this node
 * @property valueMw sum of those rails that have data
 * @property merged how many subsystems were folded into this node; 0 for an ordinary node
 */
data class SankeyNode(val name: String, val indices: List<Int>, val valueMw: Double, val merged: Int = 0)

/** One rail: power flowing from its source (a PMIC, or the system rail) to the subsystem it supplies. */
data class SankeyLink(val railIndex: Int, val rail: String, val source: Int, val subsystem: Int, val valueMw: Double)

/**
 * Power flow in three columns: the root, the sources, the subsystems. Every rail is a link from its source to
 * its subsystem, so both groupings of the tree are visible at once.
 *
 * @property rootIsBattery true when [rootMw] is the battery rail (device total); false when it is only the sum
 * of the measured rails, because the device is on external power
 * @property unmeasuredMw battery output that no rail accounts for; null when unknown or not positive
 */
data class SankeyModel(
    val rootIsBattery: Boolean,
    val rootMw: Double,
    val sources: List<SankeyNode>,
    val subsystems: List<SankeyNode>,
    val links: List<SankeyLink>,
    val unmeasuredMw: Double?,
)

object SankeyBuilder {
    /** Name of the node that small subsystems are folded into. */
    const val OTHER = "(other)"

    /**
     * Node order depends only on which rails exist, never on their readings: sources in device-map order,
     * subsystems by the first source that feeds them. Nodes therefore keep their place while values change.
     *
     * A phone screen has room for only a handful of labelled subsystems. The [maxSubsystems] largest keep a node
     * of their own and the rest share one [OTHER] node; "largest" is judged by [rankMw], which should be a
     * steady figure such as a recent average, so that subsystems do not hop in and out of [OTHER].
     *
     * @return null when there is nothing to draw (no rail has data)
     */
    fun build(
        rails: List<TreeInput>,
        onExternalPower: Boolean?,
        map: DeviceMap?,
        maxSubsystems: Int = Int.MAX_VALUE,
        rankMw: (TreeInput) -> Double = { it.powerMw ?: 0.0 },
    ): SankeyModel? {
        val tree = PowerTreeBuilder.build(rails, TreeGrouping.SOURCE, onExternalPower, map)
        val rootMw = tree.baseMw?.takeIf { it > 0 } ?: return null
        val loads = rails.filter { it.index != tree.batteryRailIndex }.map { it to parseRailName(it.name) }

        val mapOrder = map?.sources?.map { it.name }.orEmpty()
        val sourceNames = loads.map { (_, name) -> PowerTreeBuilder.groupOf(name, TreeGrouping.SOURCE, map) }.distinct()
            .sortedWith(compareBy({ mapOrder.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it }))
        val sourceOf = loads.associate { (rail, name) -> rail.index to sourceNames.indexOf(PowerTreeBuilder.groupOf(name, TreeGrouping.SOURCE, map)) }
        val ownSubsystem = loads.associate { (rail, name) -> rail.index to (name.subsystem ?: PowerTreeBuilder.UNLABELLED) }
        val structuralOrder = loads
            .sortedWith(compareBy({ sourceOf.getValue(it.first.index) }, { it.first.index }))
            .map { ownSubsystem.getValue(it.first.index) }
            .distinct()
        val rank = structuralOrder.associateWith { name -> loads.filter { ownSubsystem.getValue(it.first.index) == name }.sumOf { rankMw(it.first) } }
        // sortedByDescending is stable, so equal ranks keep their structural order.
        val kept = if (structuralOrder.size > maxSubsystems) structuralOrder.sortedByDescending { rank.getValue(it) }.take(maxSubsystems - 1).toSet() else structuralOrder.toSet()
        val folded = structuralOrder.size - kept.size
        val subsystemOfRail = ownSubsystem.mapValues { (_, name) -> if (name in kept) name else OTHER }
        val subsystemNames = structuralOrder.filter { it in kept } + if (folded > 0) listOf(OTHER) else emptyList()

        fun node(name: String, members: List<TreeInput>) =
            SankeyNode(name, members.map { it.index }, members.sumOf { it.powerMw ?: 0.0 })

        return SankeyModel(
            rootIsBattery = tree.batteryIsRoot,
            rootMw = rootMw,
            sources = sourceNames.mapIndexed { i, name -> node(name, loads.map { it.first }.filter { sourceOf.getValue(it.index) == i }) },
            subsystems = subsystemNames.map { name ->
                node(name, loads.map { it.first }.filter { subsystemOfRail.getValue(it.index) == name }).copy(merged = if (name == OTHER) folded else 0)
            },
            links = loads.mapNotNull { (rail, name) ->
                val power = rail.powerMw?.takeIf { it > 0 } ?: return@mapNotNull null
                SankeyLink(rail.index, name.rail, sourceOf.getValue(rail.index), subsystemNames.indexOf(subsystemOfRail.getValue(rail.index)), power)
            },
            unmeasuredMw = tree.unmeasuredMw?.takeIf { it > 0 },
        )
    }
}

/** A vertical extent in the diagram, in pixels from its top. */
data class SankeySpan(val top: Float, val bottom: Float) {
    val height: Float get() = bottom - top
}

/** @property slot the room reserved for the node and its label; [bar] is the node itself, centred in it */
data class SankeyNodeLayout(val slot: SankeySpan, val bar: SankeySpan)

/** A ribbon: where it leaves its left node and where it enters its right node. */
data class SankeyRibbon(val from: SankeySpan, val to: SankeySpan)

data class SankeyLayout(
    val pxPerMw: Float,
    val root: SankeySpan,
    val sources: List<SankeyNodeLayout>,
    val unmeasured: SankeyNodeLayout?,
    val subsystems: List<SankeyNodeLayout>,
    val rootRibbons: List<SankeyRibbon>,
    val unmeasuredRibbon: SankeyRibbon?,
    val railRibbons: List<SankeyRibbon>,
)

object SankeyLayouter {
    /**
     * Stacks the nodes of each column top to bottom and attaches the ribbons.
     *
     * Every node gets a slot at least [minSlot] high so its label has room; a node smaller than that is centred
     * in its slot. One scale (pixels per mW) serves all columns and is the largest for which the tallest column
     * still fits in [height].
     *
     * @return ribbons in the order of [SankeyModel.sources] (root ribbons) and [SankeyModel.links] (rail ribbons)
     */
    fun layout(model: SankeyModel, height: Float, minSlot: Float, gap: Float): SankeyLayout {
        val middle = model.sources.map { it.valueMw } + listOfNotNull(model.unmeasuredMw)
        val right = model.subsystems.map { it.valueMw }
        // With too many nodes the label slots alone would fill the height and leave nothing to scale; the slots
        // then give way, so that at least half the height always shows power.
        val nodes = maxOf(middle.size, right.size)
        val minSlot = minOf(minSlot, ((height / 2 - gap * (nodes - 1)) / nodes).coerceAtLeast(0f))
        fun columnHeight(values: List<Double>, scale: Float) =
            values.sumOf { maxOf(it.toFloat() * scale, minSlot).toDouble() }.toFloat() + gap * (values.size - 1).coerceAtLeast(0)

        // The column height grows with the scale, so the largest scale that fits is found by bisection.
        var low = 0f
        var high = height / model.rootMw.toFloat()
        repeat(BISECTION_STEPS) {
            val scale = (low + high) / 2
            if (maxOf(columnHeight(middle, scale), columnHeight(right, scale)) <= height) low = scale else high = scale
        }
        val scale = low

        fun stack(values: List<Double>): List<SankeyNodeLayout> {
            var y = 0f
            return values.map { value ->
                val bar = value.toFloat() * scale
                val slot = maxOf(bar, minSlot)
                val top = y + (slot - bar) / 2
                SankeyNodeLayout(SankeySpan(y, y + slot), SankeySpan(top, top + bar)).also { y += slot + gap }
            }
        }
        val middleLayout = stack(middle)
        val sources = middleLayout.take(model.sources.size)
        val unmeasured = middleLayout.getOrNull(model.sources.size)
        val subsystems = stack(right)

        // Ribbons leave the root in column order, so none of them cross.
        var rootY = 0f
        fun leaveRoot(value: Double) = SankeySpan(rootY, rootY + value.toFloat() * scale).also { rootY = it.bottom }
        val rootRibbons = model.sources.mapIndexed { i, node -> SankeyRibbon(leaveRoot(node.valueMw), sources[i].bar) }
        val unmeasuredRibbon = unmeasured?.let { SankeyRibbon(leaveRoot(model.unmeasuredMw!!), it.bar) }

        // At a source the ribbons are ordered by target, at a subsystem by origin: that keeps crossings low.
        val outgoing = FloatArray(model.sources.size) { sources[it].bar.top }
        val incoming = FloatArray(model.subsystems.size) { subsystems[it].bar.top }
        val railRibbons = arrayOfNulls<SankeyRibbon>(model.links.size)
        val fromEnds = arrayOfNulls<SankeySpan>(model.links.size)
        for (i in model.links.indices.sortedWith(compareBy({ model.links[it].source }, { model.links[it].subsystem }, { it }))) {
            val link = model.links[i]
            val width = link.valueMw.toFloat() * scale
            fromEnds[i] = SankeySpan(outgoing[link.source], outgoing[link.source] + width)
            outgoing[link.source] += width
        }
        for (i in model.links.indices.sortedWith(compareBy({ model.links[it].subsystem }, { model.links[it].source }, { it }))) {
            val link = model.links[i]
            val width = link.valueMw.toFloat() * scale
            railRibbons[i] = SankeyRibbon(fromEnds[i]!!, SankeySpan(incoming[link.subsystem], incoming[link.subsystem] + width))
            incoming[link.subsystem] += width
        }
        return SankeyLayout(scale, SankeySpan(0f, model.rootMw.toFloat() * scale), sources, unmeasured, subsystems, rootRibbons, unmeasuredRibbon, railRibbons.map { it!! })
    }

    private const val BISECTION_STEPS = 30
}
