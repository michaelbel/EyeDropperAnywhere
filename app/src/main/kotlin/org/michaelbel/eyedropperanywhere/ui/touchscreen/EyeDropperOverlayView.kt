/*
 * Copyright (C) 2025 The Android Open Source Project
 * Copyright (C) 2026 EyeDropperAnywhere contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.michaelbel.eyedropperanywhere.ui.touchscreen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Region
import android.os.Build
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.AbstractComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import androidx.compose.ui.unit.roundToIntSize
import org.michaelbel.eyedropperanywhere.R
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Direct public-API port of packages/apps/EyeDropper's Android 17 TouchscreenReticle.
 * Only its privileged screenshot/window plumbing is replaced by MediaProjection and an app overlay.
 */
internal class EyeDropperOverlayView(
    context: Context,
    private val screenshot: Bitmap,
    private val onColorPicked: (Int) -> Unit,
    private val onCancel: () -> Unit,
) : AbstractComposeView(context) {
    private val touchRegion = Region()
    val overlayWidth = screenshot.width
    val overlayHeight = screenshot.height
    private var pointer by mutableStateOf(Offset(screenshot.width / 2f, screenshot.height / 2f))
    private val windowOrigin = Offset.Zero
    private var dragging by mutableStateOf(false)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f

    val initialWindowX: Int
        get() = windowOrigin.x.roundToInt()
    val initialWindowY: Int
        get() = windowOrigin.y.roundToInt()

    @Composable
    override fun Content() {
        val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (isSystemInDarkTheme()) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            MaterialTheme.colorScheme
        }
        MaterialTheme(colorScheme = colorScheme) {
            TouchscreenReticle(
                screenshot = screenshot,
                globalPointer = pointer,
                windowOrigin = windowOrigin,
                screenSize = IntSize(screenshot.width, screenshot.height),
                dragging = dragging,
                onMovePointer = ::movePointer,
                onApply = onColorPicked,
                onCancel = onCancel,
                onTouchableBoundsChanged = ::setTouchableBounds,
            )
        }
    }

    private fun setTouchableBounds(bounds: Rect) {
        if (!isAttachedToWindow || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        touchRegion.set(
            bounds.left.toInt().coerceIn(0, width),
            bounds.top.toInt().coerceIn(0, height),
            bounds.right.toInt().coerceIn(0, width),
            bounds.bottom.toInt().coerceIn(0, height),
        )
        rootSurfaceControl?.setTouchableRegion(touchRegion)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                dragging = false
                super.dispatchTouchEvent(event)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && hypot(event.rawX - downRawX, event.rawY - downRawY) > touchSlop) {
                    dragging = true
                    MotionEvent.obtain(event).also { cancelEvent ->
                        cancelEvent.action = MotionEvent.ACTION_CANCEL
                        super.dispatchTouchEvent(cancelEvent)
                        cancelEvent.recycle()
                    }
                }
                if (dragging) {
                    moveFromRawEvent(event)
                    return true
                }
                super.dispatchTouchEvent(event)
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) super.dispatchTouchEvent(event)
                dragging = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (!dragging) super.dispatchTouchEvent(event)
                dragging = false
                return true
            }
        }
        return super.dispatchTouchEvent(event)
    }

    private fun moveFromRawEvent(event: MotionEvent) {
        movePointer(Offset(event.rawX - lastRawX, event.rawY - lastRawY))
        lastRawX = event.rawX
        lastRawY = event.rawY
    }

    private fun movePointer(delta: Offset) {
        pointer = Offset(
            (pointer.x + delta.x).coerceIn(0f, (screenshot.width - 1).toFloat()),
            (pointer.y + delta.y).coerceIn(0f, (screenshot.height - 1).toFloat()),
        )
    }
}

private enum class ReticleDirection(val angle: Float) {
    TOP(-90f), TOP_LEFT(-135f), TOP_RIGHT(-45f), LEFT(-180f), RIGHT(0f),
    BOTTOM(90f), BOTTOM_LEFT(135f), BOTTOM_RIGHT(45f),
}

private enum class DirectionButton { UP, DOWN, LEFT, RIGHT }

private data class ReticleDimensions(
    val backgroundWidth: Float,
    val largeCornerRadius: Float,
    val smallCornerRadius: Float,
    val innerCircleSize: Float,
    val innerCircleOutlineWidth: Float,
    val pixelBorderWidth: Float,
    val centerHighlightCornerRadius: Float,
    val centerHighlightStrokeWidth: Float,
    val ringWidth: Float,
    val handleSize: Float,
    val handleStrokeWidth: Float,
    val handleInnerOutlineWidth: Float,
    val handleMargin: Float,
    val shadowRadius: Float,
    val shadowDy: Float,
    val controlsGap: Float,
    val controlsHeight: Float,
    val additionalOutlineWidth: Float,
)

private data class ReticleColors(
    val container: Color,
    val outline: Color,
    val additionalOutline: Color,
    val background: Color,
    val shadow: Color,
)

@Composable
private fun TouchscreenReticle(
    screenshot: Bitmap,
    globalPointer: Offset,
    windowOrigin: Offset,
    screenSize: IntSize,
    dragging: Boolean,
    onMovePointer: (Offset) -> Unit,
    onApply: (Int) -> Unit,
    onCancel: () -> Unit,
    onTouchableBoundsChanged: (Rect) -> Unit,
) {
    val density = LocalDensity.current
    val imageBitmap = remember(screenshot) { screenshot.asImageBitmap() }
    val dimensions = reticleDimensions(density)
    val colors = ReticleColors(
        container = MaterialTheme.colorScheme.surfaceVariant,
        outline = MaterialTheme.colorScheme.onSurface,
        additionalOutline = MaterialTheme.colorScheme.outlineVariant,
        background = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadow = Color(0x40000000),
    )
    var controlsSize by remember { mutableStateOf(IntSize.Zero) }
    val pointer = globalPointer - windowOrigin
    val direction = calculateDirection(globalPointer, screenSize, dimensions)
    val animatedAngle = remember { Animatable(direction.angle) }
    LaunchedEffect(direction) {
        val difference = (direction.angle - animatedAngle.value + 180f).mod(360f) - 180f
        animatedAngle.animateTo(
            animatedAngle.value + difference,
            tween(300, easing = FastOutSlowInEasing),
        )
    }
    val innerCenter = getInnerCircleCenter(pointer, dimensions, animatedAngle.value)
    val controlsOffset = calculateControlsOffset(direction, innerCenter, controlsSize, dimensions)
    val totalBounds = calculateTouchableBounds(pointer, innerCenter, controlsOffset, controlsSize, dimensions)
    SideEffect {
        if (controlsSize != IntSize.Zero) {
            onTouchableBoundsChanged(totalBounds)
        }
    }

    val shadowPaint = remember(dimensions, colors.shadow) {
        Paint().asFrameworkPaint().apply {
            color = android.graphics.Color.BLACK
            setShadowLayer(dimensions.shadowRadius, 0f, dimensions.shadowDy, colors.shadow.toArgb())
        }
    }

    Box(Modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize()) {
            drawReticleBackground(innerCenter, animatedAngle.value, dimensions, colors, shadowPaint)
            drawMagnifiedContent(imageBitmap, globalPointer, innerCenter, dimensions, colors)
            drawDraggableHandle(pointer, dimensions, colors, shadowPaint)
        }

        ArrowButtonsOverlay(
                center = innerCenter,
                arcRadius = ((dimensions.innerCircleSize + dimensions.ringWidth) / 2).roundToInt(),
                tint = colors.outline,
                onMove = { button, multiplier ->
                    val delta = when (button) {
                        DirectionButton.UP -> Offset(0f, -multiplier.toFloat())
                        DirectionButton.DOWN -> Offset(0f, multiplier.toFloat())
                        DirectionButton.LEFT -> Offset(-multiplier.toFloat(), 0f)
                        DirectionButton.RIGHT -> Offset(multiplier.toFloat(), 0f)
                    }
                    onMovePointer(delta)
                },
            )
            Controls(
                showButtons = !dragging,
                color = Color(screenshot.getPixel(globalPointer.x.roundToInt(), globalPointer.y.roundToInt())),
                onApply = {
                    onApply(screenshot.getPixel(globalPointer.x.roundToInt(), globalPointer.y.roundToInt()))
                },
                onCancel = onCancel,
                outlineColor = colors.additionalOutline,
                modifier = Modifier
                    .align(AbsoluteAlignment.TopLeft)
                    .onSizeChanged { controlsSize = it }
                    .absoluteOffset { controlsOffset },
            )
    }
}

@Composable
private fun ArrowButtonsOverlay(
    center: Offset,
    arcRadius: Int,
    tint: Color,
    onMove: (DirectionButton, Int) -> Unit,
) {
    val density = LocalDensity.current
    val itemRadius = with(density) { 16.dp.toPx().roundToInt() }
    val arrows = DirectionButton.entries
    Box(Modifier.fillMaxSize()) {
        arrows.forEach { direction ->
            val interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .absoluteOffset { arrowOffset(center, direction, arcRadius, itemRadius) }
                    .clip(CircleShape)
                    .combinedClickable(
                        interactionSource = interactionSource,
                        indication = LocalIndication.current,
                        onClick = { onMove(direction, 1) },
                        onLongClick = { onMove(direction, 10) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_arrow_up),
                    contentDescription = null,
                    modifier = Modifier
                        .size(24.dp)
                        .rotate(
                            when (direction) {
                                DirectionButton.UP -> 0F
                                DirectionButton.DOWN -> 180F
                                DirectionButton.LEFT -> 270F
                                DirectionButton.RIGHT -> 90F
                            }
                        ),
                    tint = tint,
                )
            }
        }
    }
}

private fun arrowOffset(center: Offset, direction: DirectionButton, radius: Int, itemRadius: Int): IntOffset {
    val position = when (direction) {
        DirectionButton.UP -> center.copy(y = center.y - radius)
        DirectionButton.DOWN -> center.copy(y = center.y + radius)
        DirectionButton.LEFT -> center.copy(x = center.x - radius)
        DirectionButton.RIGHT -> center.copy(x = center.x + radius)
    }
    return IntOffset((position.x - itemRadius).roundToInt(), (position.y - itemRadius).roundToInt())
}

@Composable
private fun Controls(
    showButtons: Boolean,
    color: Color,
    onApply: () -> Unit,
    onCancel: () -> Unit,
    outlineColor: Color,
    modifier: Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(100.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shadowElevation = 8.dp,
        border = BorderStroke(1.dp, outlineColor),
    ) {
        val endPadding = if (showButtons) 4.dp else 16.dp
        Row(
            modifier = Modifier.padding(start = 16.dp, end = endPadding, top = 4.dp, bottom = 4.dp)
                .heightIn(min = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val hex = "%06X".format(java.util.Locale.ROOT, color.toArgb() and 0xFFFFFF)
            Text(
                text = "#$hex",
                style = MaterialTheme.typography.titleSmall.copy(fontFamily = FontFamily.Monospace),
            )
            AnimatedVisibility(showButtons) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledIconButton(
                        onClick = onCancel,
                        modifier = Modifier.padding(4.dp).size(40.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    FilledIconButton(
                        onClick = onApply,
                        modifier = Modifier.padding(4.dp).size(40.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawReticleBackground(
    center: Offset,
    angle: Float,
    dimensions: ReticleDimensions,
    colors: ReticleColors,
    shadowPaint: android.graphics.Paint,
) {
    rotate(angle + 135f, center) {
        val bounds = Rect(center, dimensions.backgroundWidth / 2f)
        val path = Path().apply {
            addRoundRect(
                RoundRect(
                    rect = bounds,
                    topLeft = CornerRadius(dimensions.largeCornerRadius),
                    topRight = CornerRadius(dimensions.largeCornerRadius),
                    bottomRight = CornerRadius(dimensions.smallCornerRadius),
                    bottomLeft = CornerRadius(dimensions.largeCornerRadius),
                )
            )
        }
        drawIntoCanvas { canvas ->
            canvas.drawPath(path, Paint().apply { asFrameworkPaint().set(shadowPaint) })
        }
        drawPath(path, colors.background)
        drawPath(path, colors.additionalOutline, style = Stroke(dimensions.additionalOutlineWidth))
    }
}

private fun DrawScope.drawMagnifiedContent(
    screenshot: ImageBitmap,
    pointer: Offset,
    center: Offset,
    dimensions: ReticleDimensions,
    colors: ReticleColors,
) {
    val pixelSize = dimensions.innerCircleSize / 7
    val topLeft = Offset(center.x - dimensions.innerCircleSize / 2, center.y - dimensions.innerCircleSize / 2)
    val destinationSize = Size(dimensions.innerCircleSize, dimensions.innerCircleSize).roundToIntSize()
    val circle = Path().apply { addOval(Rect(center, dimensions.innerCircleSize / 2)) }
    clipPath(circle) {
        drawCircle(colors.container, dimensions.innerCircleSize / 2, center)
        val sourceX = (pointer.x.roundToInt() - 3).coerceIn(0, max(0, screenshot.width - 7))
        val sourceY = (pointer.y.roundToInt() - 3).coerceIn(0, max(0, screenshot.height - 7))
        drawImage(
            image = screenshot,
            srcOffset = IntOffset(sourceX, sourceY),
            srcSize = IntSize(7, 7),
            dstOffset = topLeft.round(),
            dstSize = destinationSize,
            filterQuality = FilterQuality.None,
        )
        drawPath(circle, colors.outline, style = Stroke(dimensions.innerCircleOutlineWidth))
        for (index in 1 until 7) {
            val offset = index * pixelSize
            drawLine(colors.outline, Offset(topLeft.x + offset, topLeft.y), Offset(topLeft.x + offset, topLeft.y + dimensions.innerCircleSize), dimensions.pixelBorderWidth)
            drawLine(colors.outline, Offset(topLeft.x, topLeft.y + offset), Offset(topLeft.x + dimensions.innerCircleSize, topLeft.y + offset), dimensions.pixelBorderWidth)
        }
        drawRoundRect(
            color = colors.outline,
            topLeft = Offset(center.x - pixelSize / 2, center.y - pixelSize / 2),
            size = Size(pixelSize, pixelSize),
            cornerRadius = CornerRadius(dimensions.centerHighlightCornerRadius),
            style = Stroke(dimensions.centerHighlightStrokeWidth),
        )
    }
}

private fun DrawScope.drawDraggableHandle(
    center: Offset,
    dimensions: ReticleDimensions,
    colors: ReticleColors,
    shadowPaint: android.graphics.Paint,
) {
    val radius = dimensions.handleSize / 2
    val innerOffset = dimensions.handleStrokeWidth / 2 + dimensions.handleInnerOutlineWidth / 2
    val outerOffset = dimensions.handleStrokeWidth / 2 + dimensions.additionalOutlineWidth / 2
    drawIntoCanvas { canvas ->
        canvas.drawCircle(center, radius, Paint().apply {
            asFrameworkPaint().set(shadowPaint)
            style = PaintingStyle.Stroke
            strokeWidth = dimensions.handleStrokeWidth
        })
    }
    drawCircle(colors.outline, radius - innerOffset, center, style = Stroke(dimensions.handleInnerOutlineWidth))
    drawCircle(colors.background, radius, center, style = Stroke(dimensions.handleStrokeWidth))
    drawCircle(colors.additionalOutline, radius + outerOffset, center, style = Stroke(dimensions.additionalOutlineWidth))
}

@Composable
private fun reticleDimensions(density: Density): ReticleDimensions = with(density) {
    ReticleDimensions(
        backgroundWidth = dimensionResource(R.dimen.reticle_magnify_view_background_width).toPx(),
        largeCornerRadius = dimensionResource(R.dimen.reticle_background_large_corner_radius).toPx(),
        smallCornerRadius = dimensionResource(R.dimen.reticle_background_small_corner_radius).toPx(),
        innerCircleSize = dimensionResource(R.dimen.reticle_magnify_view_inner_circle_size).toPx(),
        innerCircleOutlineWidth = dimensionResource(R.dimen.reticle_inner_circle_outline_width).toPx(),
        pixelBorderWidth = dimensionResource(R.dimen.reticle_pixel_border_width).toPx(),
        centerHighlightCornerRadius = dimensionResource(R.dimen.reticle_center_pixel_highlight_corner_radius).toPx(),
        centerHighlightStrokeWidth = dimensionResource(R.dimen.reticle_center_pixel_highlight_stroke_width).toPx(),
        ringWidth = dimensionResource(R.dimen.reticle_magnify_view_outer_ring_width).toPx(),
        handleSize = dimensionResource(R.dimen.reticle_handle_size).toPx(),
        handleStrokeWidth = dimensionResource(R.dimen.reticle_handle_stroke_width).toPx(),
        handleInnerOutlineWidth = dimensionResource(R.dimen.reticle_handle_inner_outline_width).toPx(),
        handleMargin = dimensionResource(R.dimen.reticle_handle_view_margin_top).toPx(),
        shadowRadius = dimensionResource(R.dimen.reticle_shadow_radius).toPx(),
        shadowDy = dimensionResource(R.dimen.reticle_shadow_dy).toPx(),
        controlsGap = dimensionResource(R.dimen.control_panel_vertical_offset).toPx(),
        controlsHeight = dimensionResource(R.dimen.controls_height).toPx(),
        additionalOutlineWidth = dimensionResource(R.dimen.additional_outline_width).toPx(),
    )
}

private fun getInnerCircleCenter(handle: Offset, dimensions: ReticleDimensions, angle: Float): Offset {
    val distance = dimensions.backgroundWidth * sqrt(2f) / 2f + dimensions.handleMargin + dimensions.handleSize / 2
    val radians = Math.toRadians(angle.toDouble())
    return Offset(
        handle.x + distance * cos(radians).toFloat(),
        handle.y + distance * sin(radians).toFloat(),
    )
}

private fun calculateDirection(
    handle: Offset,
    screen: IntSize,
    dimensions: ReticleDimensions,
): ReticleDirection {
    // Same representative bounds calculation as AOSP getReticleTotalBounds().
    val triggerExtent = dimensions.handleSize + dimensions.handleMargin + dimensions.backgroundWidth
    val topTrigger = triggerExtent + dimensions.controlsHeight + dimensions.controlsGap
    val isLeft = handle.x < triggerExtent
    val isRight = handle.x > screen.width - triggerExtent
    val isTop = handle.y < topTrigger
    return when {
        isTop && isLeft -> ReticleDirection.BOTTOM_RIGHT
        isTop && isRight -> ReticleDirection.BOTTOM_LEFT
        isLeft -> ReticleDirection.TOP_RIGHT
        isRight -> ReticleDirection.TOP_LEFT
        isTop -> ReticleDirection.BOTTOM_LEFT
        else -> ReticleDirection.TOP
    }
}

private fun calculateControlsOffset(
    direction: ReticleDirection,
    center: Offset,
    controls: IntSize,
    dimensions: ReticleDimensions,
): IntOffset {
    val below = direction == ReticleDirection.BOTTOM_LEFT || direction == ReticleDirection.BOTTOM_RIGHT
    val y = if (below) {
        center.y + dimensions.backgroundWidth / 2 + dimensions.controlsGap
    } else {
        center.y - dimensions.backgroundWidth / 2 - controls.height - dimensions.controlsGap
    }
    val centeredX = center.x - controls.width / 2
    val halfBackground = dimensions.backgroundWidth / 2
    val x = when (direction) {
        ReticleDirection.TOP_LEFT, ReticleDirection.BOTTOM_LEFT -> {
            min(centeredX, center.x + halfBackground - controls.width)
        }
        ReticleDirection.TOP_RIGHT, ReticleDirection.BOTTOM_RIGHT -> {
            max(centeredX, center.x - halfBackground)
        }
        else -> centeredX
    }
    return IntOffset(x.roundToInt(), y.roundToInt())
}

private fun calculateTouchableBounds(
    handle: Offset,
    innerCenter: Offset,
    controlsOffset: IntOffset,
    controlsSize: IntSize,
    dimensions: ReticleDimensions,
): Rect {
    if (handle == Offset.Unspecified) return Rect.Zero
    val backgroundRadius = dimensions.backgroundWidth * sqrt(2f) / 2f + dimensions.shadowRadius
    val handleRadius = dimensions.handleSize / 2 + dimensions.shadowRadius
    var left = min(handle.x - handleRadius, innerCenter.x - backgroundRadius)
    var top = min(handle.y - handleRadius, innerCenter.y - backgroundRadius)
    var right = max(handle.x + handleRadius, innerCenter.x + backgroundRadius)
    var bottom = max(handle.y + handleRadius, innerCenter.y + backgroundRadius)
    if (controlsSize != IntSize.Zero) {
        left = min(left, controlsOffset.x.toFloat())
        top = min(top, controlsOffset.y.toFloat())
        right = max(right, controlsOffset.x + controlsSize.width.toFloat())
        bottom = max(bottom, controlsOffset.y + controlsSize.height.toFloat())
    }
    return Rect(left, top, right, bottom)
}
