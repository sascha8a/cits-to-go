package org.opentrafficmap.citstogo.livemap

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.CornerPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.roundToInt
import org.maplibre.android.maps.Style

/**
 * The marker icons of the live map, drawn for the screen density. Stations are arrows along their heading or dots,
 * coloured by [StationIconGroup], each with a grey variant for stale markers. DENMs are warning signs by
 * [DenmIconGroup]. Road side units from history are a traffic light or a mast with radio waves. The white outlines
 * keep them readable on light and dark map styles.
 */
object MapIcons {
    const val STALE_SUFFIX = "-stale"

    private const val ARROW_SIZE_DP = 30f
    private const val DOT_SIZE_DP = 20f
    private const val SIGN_SIZE_DP = 34f
    private const val ROAD_SIDE_UNIT_SIZE_DP = 26f
    private const val OUTLINE = 0xFFFFFFFF.toInt()
    private const val INK = 0xFF111827.toInt()
    private const val STALE = 0xFF94A3B8.toInt()
    private const val WARNING_RED = 0xFFDC2626.toInt()
    private const val EMERGENCY_BLUE = 0xFF1D4ED8.toInt()
    private const val ROADWORKS_YELLOW = 0xFFFACC15.toInt()
    private const val SLATE = 0xFF334155.toInt()
    private const val SIGNAL_RED = 0xFFEF4444.toInt()
    private const val SIGNAL_AMBER = 0xFFF59E0B.toInt()
    private const val SIGNAL_GREEN = 0xFF22C55E.toInt()

    /** Adds every icon to [style]. A new style starts without them, so this runs after each style change. */
    fun addTo(style: Style, context: Context) {
        val metrics = context.resources.displayMetrics
        val painter = Painter(metrics.density, metrics.densityDpi)
        StationIconGroup.entries.forEach { group ->
            for (arrow in listOf(true, false)) {
                style.addImage(group.icon(arrow), painter.station(arrow, color(group)))
                style.addImage(group.icon(arrow) + STALE_SUFFIX, painter.station(arrow, STALE))
            }
        }
        DenmIconGroup.entries.forEach { style.addImage(it.icon, painter.sign(it)) }
        style.addImage(RoadSideUnitHistory.TRAFFIC_LIGHT_ICON, painter.trafficLight())
        style.addImage(RoadSideUnitHistory.ROAD_SIDE_UNIT_ICON, painter.roadSideUnit())
    }

    // Not the blue of the own position.
    private fun color(group: StationIconGroup): Int = when (group) {
        StationIconGroup.Car -> 0xFF7C3AED
        StationIconGroup.TruckOrBus -> 0xFFB45309
        StationIconGroup.Tram -> 0xFF0F766E
        StationIconGroup.VulnerableRoadUser -> 0xFF16A34A
        StationIconGroup.SpecialVehicle -> 0xFFEA580C
        StationIconGroup.RoadSideUnit -> 0xFF334155
        StationIconGroup.Unknown -> 0xFFDB2777
    }.toInt()

    private class Painter(private val density: Float, private val densityDpi: Int) {
        private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeWidth = 3f * density
            color = OUTLINE
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        /** An arrow pointing north, which the layer rotates to the heading, or a dot. */
        fun station(arrow: Boolean, color: Int): Bitmap {
            fill.color = color
            return if (arrow) {
                draw(ARROW_SIZE_DP) { size ->
                    val path = polygon(size, 0.5f, 0.1f, 0.84f, 0.86f, 0.5f, 0.68f, 0.16f, 0.86f)
                    drawPath(path, outline)
                    drawPath(path, fill)
                }
            } else {
                draw(DOT_SIZE_DP) { size ->
                    val radius = size / 2f - outline.strokeWidth / 2f
                    drawCircle(size / 2f, size / 2f, radius, outline)
                    drawCircle(size / 2f, size / 2f, radius, fill)
                }
            }
        }

        /** A traffic light head: a dark housing with red, amber and green lamps. */
        fun trafficLight(): Bitmap = draw(ROAD_SIDE_UNIT_SIZE_DP) { size ->
            val housing = RectF(0.27f * size, 0.06f * size, 0.73f * size, 0.94f * size)
            val corner = 0.14f * size
            drawRoundRect(housing, corner, corner, outline)
            fill.color = INK
            drawRoundRect(housing, corner, corner, fill)
            listOf(SIGNAL_RED to 0.24f, SIGNAL_AMBER to 0.5f, SIGNAL_GREEN to 0.76f).forEach { (color, y) ->
                fill.color = color
                drawCircle(0.5f * size, y * size, 0.1f * size, fill)
            }
        }

        /** A rounded square with a mast sending radio waves to both sides. */
        fun roadSideUnit(): Bitmap = draw(ROAD_SIDE_UNIT_SIZE_DP) { size ->
            val square = RectF(0.08f * size, 0.08f * size, 0.92f * size, 0.92f * size)
            val corner = 0.18f * size
            drawRoundRect(square, corner, corner, outline)
            fill.color = SLATE
            drawRoundRect(square, corner, corner, fill)
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeWidth = 0.07f * size
                color = OUTLINE
            }
            val top = 0.38f * size
            drawLine(0.5f * size, top, 0.5f * size, 0.78f * size, line)
            drawLine(0.38f * size, 0.78f * size, 0.62f * size, 0.78f * size, line)
            for (radius in listOf(0.13f * size, 0.24f * size)) {
                val waves = RectF(0.5f * size - radius, top - radius, 0.5f * size + radius, top + radius)
                drawArc(waves, -45f, 90f, false, line)
                drawArc(waves, 135f, 90f, false, line)
            }
        }

        /** A warning triangle with a pictogram; emergencies have a blue border, roadworks a yellow face. */
        fun sign(group: DenmIconGroup): Bitmap = draw(SIGN_SIZE_DP) { size ->
            val rounded = CornerPathEffect(2.5f * density)
            val triangle = polygon(size, 0.5f, 0.1f, 0.95f, 0.88f, 0.05f, 0.88f)
            outline.pathEffect = rounded
            fill.pathEffect = rounded
            drawPath(triangle, outline)
            fill.color = if (group == DenmIconGroup.Emergency) EMERGENCY_BLUE else WARNING_RED
            drawPath(triangle, fill)
            fill.color = if (group == DenmIconGroup.Roadworks) ROADWORKS_YELLOW else OUTLINE
            drawPath(polygon(size, 0.5f, 0.3f, 0.78f, 0.78f, 0.22f, 0.78f), fill)
            outline.pathEffect = null
            fill.pathEffect = null
            fill.color = INK
            pictogram(group, size)
        }

        private fun Canvas.pictogram(group: DenmIconGroup, size: Float) {
            fun rect(left: Float, top: Float, right: Float, bottom: Float) =
                RectF(left * size, top * size, right * size, bottom * size)
            val line = Paint(fill).apply {
                style = Paint.Style.STROKE
                strokeCap = Paint.Cap.ROUND
                strokeWidth = 0.06f * size
            }
            val corner = 0.02f * size
            when (group) {
                DenmIconGroup.TrafficJam -> {
                    drawRoundRect(rect(0.43f, 0.45f, 0.57f, 0.56f), corner, corner, fill)
                    drawRoundRect(rect(0.43f, 0.60f, 0.57f, 0.71f), corner, corner, fill)
                }
                DenmIconGroup.Accident -> {
                    drawLine(0.42f * size, 0.5f * size, 0.6f * size, 0.72f * size, line)
                    drawLine(0.58f * size, 0.5f * size, 0.4f * size, 0.72f * size, line)
                }
                DenmIconGroup.Roadworks -> {
                    drawPath(polygon(size, 0.5f, 0.43f, 0.6f, 0.7f, 0.4f, 0.7f), fill)
                    drawRect(rect(0.36f, 0.7f, 0.64f, 0.74f), fill)
                }
                DenmIconGroup.StationaryVehicle -> {
                    drawPath(polygon(size, 0.42f, 0.56f, 0.45f, 0.47f, 0.55f, 0.47f, 0.58f, 0.56f), fill)
                    drawRoundRect(rect(0.37f, 0.55f, 0.63f, 0.68f), corner, corner, fill)
                    drawRect(rect(0.39f, 0.68f, 0.45f, 0.72f), fill)
                    drawRect(rect(0.55f, 0.68f, 0.61f, 0.72f), fill)
                }
                DenmIconGroup.Emergency -> {
                    drawArc(rect(0.41f, 0.55f, 0.59f, 0.73f), 180f, 180f, true, fill)
                    drawRect(rect(0.38f, 0.64f, 0.62f, 0.7f), fill)
                    drawLine(0.5f * size, 0.45f * size, 0.5f * size, 0.5f * size, line)
                }
                DenmIconGroup.Hazard -> {
                    drawRoundRect(rect(0.47f, 0.42f, 0.53f, 0.63f), corner, corner, fill)
                    drawCircle(0.5f * size, 0.7f * size, 0.035f * size, fill)
                }
            }
        }

        private fun draw(sizeDp: Float, block: Canvas.(size: Float) -> Unit): Bitmap {
            val size = (sizeDp * density).roundToInt()
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            // MapLibre takes the pixel ratio of an image from its density.
            bitmap.density = densityDpi
            Canvas(bitmap).block(size.toFloat())
            return bitmap
        }

        /** A closed path through points given as fractions of [size], x before y. */
        private fun polygon(size: Float, vararg points: Float): Path = Path().apply {
            moveTo(points[0] * size, points[1] * size)
            for (i in 2 until points.size step 2) lineTo(points[i] * size, points[i + 1] * size)
            close()
        }
    }
}
