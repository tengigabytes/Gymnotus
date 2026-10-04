package io.github.tengigabytes.gymnotus.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.tengigabytes.gymnotus.R
import io.github.tengigabytes.gymnotus.power.SankeyLayouter
import io.github.tengigabytes.gymnotus.power.SankeyModel
import io.github.tengigabytes.gymnotus.power.SankeyNode
import io.github.tengigabytes.gymnotus.power.SankeySpan
import io.github.tengigabytes.gymnotus.power.TreeGrouping
import java.util.Locale

private val NODE_WIDTH = 8.dp
private val SUBSYSTEM_LABEL_WIDTH = 96.dp
private val SOURCE_LABEL_WIDTH = 132.dp
private val MIN_SLOT = 16.dp
private val NODE_GAP = 3.dp

/**
 * Power flow at a glance: the root on the left, the sources in the middle, the subsystems on the right, and one
 * ribbon per rail whose width is its power. Nodes keep their order, so only the widths move as readings change.
 *
 * Tapping a source or a subsystem adds it to (or removes it from) the chart; a charted node, and the ribbons
 * that pass through it, take that series' colour. Everything else is neutral.
 */
@Composable
fun SankeyDiagram(model: SankeyModel, selection: ChartSelection, plotHeight: Dp) {
    val colors = chartColors()
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    var widthPx by remember { mutableIntStateOf(0) }
    val unmeasuredLabel = stringResource(R.string.bar_unmeasured)
    val otherLabel = model.subsystems.lastOrNull()?.takeIf { it.merged > 0 }?.let { stringResource(R.string.sankey_other, it.merged) }
    val rootLabel = stringResource(if (model.rootIsBattery) R.string.tree_root_battery else R.string.tree_root_sum)

    val heightPx = with(density) { plotHeight.toPx() }
    val layout = remember(model, heightPx) {
        SankeyLayouter.layout(model, heightPx, with(density) { MIN_SLOT.toPx() }, with(density) { NODE_GAP.toPx() })
    }
    val nodeWidth = with(density) { NODE_WIDTH.toPx() }
    val subsystemX = widthPx - with(density) { SUBSYSTEM_LABEL_WIDTH.toPx() } - nodeWidth
    val sourceX = (subsystemX * 0.42f).coerceAtLeast(nodeWidth * 3)
    val sourceLabelWidth = with(density) { SOURCE_LABEL_WIDTH.toPx() }

    // The gesture handler outlives recompositions, so it must read the latest model and layout.
    val currentModel by rememberUpdatedState(model)
    val currentLayout by rememberUpdatedState(layout)
    val currentSourceX by rememberUpdatedState(sourceX)
    val currentSubsystemX by rememberUpdatedState(subsystemX)
    val currentOtherLabel by rememberUpdatedState(otherLabel)

    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .padding(12.dp),
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(plotHeight)
                .onSizeChanged { widthPx = it.width }
                .pointerInput(Unit) {
                    detectTapGestures { tap ->
                        // Hit areas are the node plus its label, and as tall as the slot, so small nodes can be hit.
                        if (tap.x >= currentSubsystemX - nodeWidth) {
                            val i = currentLayout.subsystems.indexOfFirst { tap.y in it.slot.top..it.slot.bottom }
                            if (i >= 0) toggle(selection, TreeGrouping.SUBSYSTEM, currentModel.subsystems[i], currentOtherLabel)
                        } else if (tap.x in (currentSourceX - nodeWidth)..(currentSourceX + nodeWidth + sourceLabelWidth)) {
                            val i = currentLayout.sources.indexOfFirst { tap.y in it.slot.top..it.slot.bottom }
                            if (i >= 0) toggle(selection, TreeGrouping.SOURCE, currentModel.sources[i], null)
                        }
                    }
                },
        ) {
            if (widthPx == 0) return@Canvas
            val neutralRibbon = colors.muted.copy(alpha = 0.4f)
            val labelStyle = TextStyle(fontSize = 11.sp, color = colors.primary)
            val valueStyle = TextStyle(fontSize = 11.sp, color = colors.secondary)

            fun slotOfSource(i: Int) = selection.slotOf(groupKey(TreeGrouping.SOURCE, model.sources[i].name))
            fun slotOfSubsystem(i: Int) = selection.slotOf(groupKey(TreeGrouping.SUBSYSTEM, model.subsystems[i].name))

            // Root to sources.
            layout.rootRibbons.forEachIndexed { i, ribbon ->
                val color = slotOfSource(i)?.let { colors.series[it].copy(alpha = 0.55f) } ?: neutralRibbon
                ribbon(nodeWidth, ribbon.from, sourceX, ribbon.to, color)
            }
            layout.unmeasuredRibbon?.let { ribbon(nodeWidth, it.from, sourceX, it.to, colors.muted.copy(alpha = 0.18f)) }

            // Sources to subsystems: one ribbon per rail. A charted rail outranks its subsystem, which outranks its source.
            model.links.forEachIndexed { i, link ->
                val slot = selection.slotOf(railKey(link.railIndex))
                val color = when {
                    slot != null -> colors.series[slot].copy(alpha = 0.85f)
                    slotOfSubsystem(link.subsystem) != null -> colors.series[slotOfSubsystem(link.subsystem)!!].copy(alpha = 0.55f)
                    slotOfSource(link.source) != null -> colors.series[slotOfSource(link.source)!!].copy(alpha = 0.55f)
                    else -> neutralRibbon
                }
                ribbon(sourceX + nodeWidth, layout.railRibbons[i].from, subsystemX, layout.railRibbons[i].to, color)
            }

            // Nodes on top of the ribbons, then their labels in text ink.
            node(0f, layout.root, nodeWidth, colors.secondary)
            label(measurer, SankeyNode(rootLabel, emptyList(), model.rootMw), layout.root, nodeWidth + 4.dp.toPx(), sourceX - nodeWidth - 8.dp.toPx(), labelStyle, valueStyle, twoLines = true)
            layout.sources.forEachIndexed { i, node ->
                node(sourceX, node.bar, nodeWidth, slotOfSource(i)?.let { colors.series[it] } ?: colors.secondary)
                label(measurer, model.sources[i], node.slot, sourceX + nodeWidth + 4.dp.toPx(), sourceLabelWidth, labelStyle, valueStyle, twoLines = true)
            }
            layout.unmeasured?.let { node ->
                // Outlined, not filled: it is what is left over, not something that was measured.
                drawRoundRect(
                    colors.muted,
                    Offset(sourceX, node.bar.top),
                    Size(nodeWidth, node.bar.height.coerceAtLeast(1f)),
                    CornerRadius(2.dp.toPx()),
                    style = Stroke(1.dp.toPx()),
                )
                label(
                    measurer,
                    SankeyNode(unmeasuredLabel, emptyList(), model.unmeasuredMw ?: 0.0),
                    node.slot,
                    sourceX + nodeWidth + 4.dp.toPx(),
                    sourceLabelWidth,
                    labelStyle,
                    valueStyle,
                    twoLines = true,
                )
            }
            layout.subsystems.forEachIndexed { i, node ->
                node(subsystemX, node.bar, nodeWidth, slotOfSubsystem(i)?.let { colors.series[it] } ?: colors.secondary)
                val shown = if (model.subsystems[i].merged > 0) model.subsystems[i].copy(name = otherLabel ?: model.subsystems[i].name) else model.subsystems[i]
                label(measurer, shown, node.slot, subsystemX + nodeWidth + 4.dp.toPx(), SUBSYSTEM_LABEL_WIDTH.toPx() - 4.dp.toPx(), labelStyle, valueStyle, twoLines = false)
            }
        }
        Text(stringResource(R.string.sankey_hint), color = colors.secondary, fontSize = 12.sp)
    }
}

/** @param label shown in the chart legend instead of the node's own name, for the folded "other" node */
private fun toggle(selection: ChartSelection, grouping: TreeGrouping, node: SankeyNode, label: String?) =
    selection.toggle(groupKey(grouping, node.name), label ?: node.name, node.indices)

/** A band from ([x0], [from]) to ([x1], [to]) that leaves and arrives horizontally. */
private fun DrawScope.ribbon(x0: Float, from: SankeySpan, x1: Float, to: SankeySpan, color: Color) {
    // Even a rail drawing almost nothing stays visible as a hairline.
    val fromBottom = maxOf(from.bottom, from.top + 1f)
    val toBottom = maxOf(to.bottom, to.top + 1f)
    val mid = (x0 + x1) / 2
    val path = Path().apply {
        moveTo(x0, from.top)
        cubicTo(mid, from.top, mid, to.top, x1, to.top)
        lineTo(x1, toBottom)
        cubicTo(mid, toBottom, mid, fromBottom, x0, fromBottom)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.node(x: Float, bar: SankeySpan, width: Float, color: Color) {
    drawRoundRect(color, Offset(x, bar.top), Size(width, bar.height.coerceAtLeast(1f)), CornerRadius(2.dp.toPx()))
}

/** Name and value beside a node, centred on its slot; on one line, or on two when the slot is tall enough. */
private fun DrawScope.label(
    measurer: TextMeasurer,
    node: SankeyNode,
    slot: SankeySpan,
    x: Float,
    maxWidth: Float,
    nameStyle: TextStyle,
    valueStyle: TextStyle,
    twoLines: Boolean,
) {
    val value = String.format(Locale.ROOT, "%,.0f", node.valueMw)
    val constraints = Constraints(maxWidth = maxWidth.toInt().coerceAtLeast(1))
    val centre = (slot.top + slot.bottom) / 2
    if (twoLines) {
        val name = measurer.measure(node.name, nameStyle, overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = constraints)
        val number = measurer.measure("$value mW", valueStyle, maxLines = 1, constraints = constraints)
        if (slot.height >= name.size.height + number.size.height) {
            val top = centre - (name.size.height + number.size.height) / 2f
            drawText(name, topLeft = Offset(x, top))
            drawText(number, topLeft = Offset(x, top + name.size.height))
            return
        }
    }
    val number = measurer.measure(value, valueStyle, maxLines = 1)
    val nameWidth = (maxWidth - number.size.width - 4.dp.toPx()).toInt().coerceAtLeast(1)
    val name = measurer.measure(node.name, nameStyle, overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = nameWidth))
    drawText(name, topLeft = Offset(x, centre - name.size.height / 2f))
    drawText(number, topLeft = Offset(x + maxWidth - number.size.width, centre - number.size.height / 2f))
}
