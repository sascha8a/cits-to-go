package org.opentrafficmap.citstogo.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.abs

enum class DragConfirmDirection { LeftToRight, RightToLeft }

@Composable
fun DragConfirmSlider(
    position: Float,
    onPositionChange: (Float) -> Unit,
    enabled: Boolean,
    direction: DragConfirmDirection,
    trackColor: Color,
    fillColor: Color,
    modifier: Modifier = Modifier,
    onDragStateChange: (Boolean) -> Unit = {},
    onDragFinished: () -> Unit = {},
    drawThumbContent: DrawScope.(Offset, Float) -> Unit,
) {
    var sliderSize by remember { mutableStateOf(IntSize.Zero) }
    val currentPosition by rememberUpdatedState(position.coerceIn(0f, 1f))
    val currentOnPositionChange by rememberUpdatedState(onPositionChange)
    val currentOnDragFinished by rememberUpdatedState(onDragFinished)
    val currentOnDragStateChange by rememberUpdatedState(onDragStateChange)
    val constrainedPosition = position.coerceIn(0f, 1f)

    Canvas(
        modifier = modifier
            .onSizeChanged { sliderSize = it }
            .pointerInput(enabled, sliderSize.width, direction) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val width = sliderSize.width.toFloat()
                    if (!enabled || width <= 0f) return@awaitEachGesture
                    val thumbRadius = 30.dp.toPx()
                    val startX = thumbRadius
                    val endX = width - thumbRadius
                    if (endX <= startX) return@awaitEachGesture
                    val initialPosition = currentPosition
                    val thumbX = when (direction) {
                        DragConfirmDirection.LeftToRight -> startX + (endX - startX) * initialPosition
                        DragConfirmDirection.RightToLeft -> endX - (endX - startX) * initialPosition
                    }
                    val hitSlop = 18.dp.toPx()
                    if (abs(down.position.x - thumbX) > thumbRadius + hitSlop) return@awaitEachGesture
                    down.consume()
                    currentOnDragStateChange(true)
                    try {
                        val downX = down.position.x
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            event.changes.forEach { it.consume() }
                            val active = event.changes.firstOrNull { it.pressed } ?: break
                            val deltaFraction = when (direction) {
                                DragConfirmDirection.LeftToRight -> (active.position.x - downX) / (endX - startX)
                                DragConfirmDirection.RightToLeft -> (downX - active.position.x) / (endX - startX)
                            }
                            currentOnPositionChange((initialPosition + deltaFraction).coerceIn(0f, 1f))
                        }
                        currentOnDragFinished()
                    } finally {
                        currentOnDragStateChange(false)
                    }
                }
            },
    ) {
        val trackHeight = 66.dp.toPx()
        val thumbRadius = 30.dp.toPx()
        val centerY = size.height / 2f
        val startX = thumbRadius
        val endX = size.width - thumbRadius
        val thumbX = when (direction) {
            DragConfirmDirection.LeftToRight -> startX + (endX - startX) * constrainedPosition
            DragConfirmDirection.RightToLeft -> endX - (endX - startX) * constrainedPosition
        }
        drawLine(trackColor, Offset(startX, centerY), Offset(endX, centerY), trackHeight, StrokeCap.Round)
        val fillStart = if (direction == DragConfirmDirection.LeftToRight) Offset(startX, centerY) else Offset(thumbX, centerY)
        val fillEnd = if (direction == DragConfirmDirection.LeftToRight) Offset(thumbX, centerY) else Offset(endX, centerY)
        drawLine(fillColor, fillStart, fillEnd, trackHeight, StrokeCap.Round)
        drawCircle(Color.White, thumbRadius + 3.dp.toPx(), Offset(thumbX, centerY))
        drawCircle(fillColor, thumbRadius, Offset(thumbX, centerY))
        drawThumbContent(Offset(thumbX, centerY), thumbRadius)
    }
}
