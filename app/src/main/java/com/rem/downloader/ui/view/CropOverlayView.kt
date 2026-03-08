package com.rem.downloader.ui.view

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.rem.downloader.model.CropRatio

class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val overlayPaint = Paint().apply {
        color = Color.argb(160, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }

    private val cropRect = RectF()
    private val minCropSize = 80f
    private val handleRadius = 12f
    private val touchSlop = 40f

    private var activeHandle = Handle.NONE
    private var lastTouchX = 0f
    private var lastTouchY = 0f

    var cropRatio: CropRatio = CropRatio.FREE
        set(value) {
            field = value
            resetCropRect()
            invalidate()
        }

    var accentColor: Int = Color.WHITE
        set(value) {
            field = value
            borderPaint.color = value
            handlePaint.color = value
            invalidate()
        }

    // Video dimensions (for mapping crop rect back to actual video coordinates)
    var videoWidth: Int = 0
        set(value) {
            field = value
            resetCropRect()
            invalidate()
        }
    var videoHeight: Int = 0
        set(value) {
            field = value
            resetCropRect()
            invalidate()
        }

    var onCropChanged: ((RectF) -> Unit)? = null

    private enum class Handle {
        NONE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, CENTER
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetCropRect()
    }

    /**
     * Computes the actual video content rectangle within the view,
     * accounting for fitCenter scaling (pillarbox for landscape views with portrait video,
     * letterbox for portrait views with landscape video).
     */
    private fun getContentBounds(): RectF {
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (vw <= 0 || vh <= 0 || videoWidth <= 0 || videoHeight <= 0) {
            return RectF(0f, 0f, vw, vh)
        }

        val viewAspect = vw / vh
        val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()

        val contentW: Float
        val contentH: Float
        if (videoAspect > viewAspect) {
            // Video is wider than view → letterbox (bars top/bottom)
            contentW = vw
            contentH = vw / videoAspect
        } else {
            // Video is taller than view → pillarbox (bars left/right)
            contentH = vh
            contentW = vh * videoAspect
        }

        val offsetX = (vw - contentW) / 2f
        val offsetY = (vh - contentH) / 2f
        return RectF(offsetX, offsetY, offsetX + contentW, offsetY + contentH)
    }

    private fun resetCropRect() {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val bounds = getContentBounds()
        val padding = bounds.width() * 0.1f

        if (cropRatio.isFixed) {
            val aspect = cropRatio.aspect
            val availW = bounds.width() - padding * 2
            val availH = bounds.height() - padding * 2
            val rectW: Float
            val rectH: Float
            if (availW / availH > aspect) {
                rectH = availH
                rectW = rectH * aspect
            } else {
                rectW = availW
                rectH = rectW / aspect
            }
            val cx = bounds.centerX()
            val cy = bounds.centerY()
            cropRect.set(cx - rectW / 2, cy - rectH / 2, cx + rectW / 2, cy + rectH / 2)
        } else {
            cropRect.set(
                bounds.left + padding,
                bounds.top + padding,
                bounds.right - padding,
                bounds.bottom - padding
            )
        }
        onCropChanged?.invoke(cropRect)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        // Draw overlay (darkened area outside crop) using Path to avoid deprecated Region.Op
        val path = Path().apply {
            addRect(0f, 0f, w, h, Path.Direction.CW)
            addRect(cropRect, Path.Direction.CCW)
        }
        canvas.drawPath(path, overlayPaint)

        // Border
        canvas.drawRect(cropRect, borderPaint)

        // Grid lines (rule of thirds)
        val thirdW = cropRect.width() / 3
        val thirdH = cropRect.height() / 3
        for (i in 1..2) {
            canvas.drawLine(cropRect.left + thirdW * i, cropRect.top, cropRect.left + thirdW * i, cropRect.bottom, gridPaint)
            canvas.drawLine(cropRect.left, cropRect.top + thirdH * i, cropRect.right, cropRect.top + thirdH * i, gridPaint)
        }

        // Corner handles
        drawHandle(canvas, cropRect.left, cropRect.top)
        drawHandle(canvas, cropRect.right, cropRect.top)
        drawHandle(canvas, cropRect.left, cropRect.bottom)
        drawHandle(canvas, cropRect.right, cropRect.bottom)
    }

    private fun drawHandle(canvas: Canvas, x: Float, y: Float) {
        canvas.drawCircle(x, y, handleRadius, handlePaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                activeHandle = getHandle(x, y)
                lastTouchX = x
                lastTouchY = y
                if (activeHandle != Handle.NONE) {
                    parent.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeHandle == Handle.NONE) return false
                val dx = x - lastTouchX
                val dy = y - lastTouchY
                moveHandle(dx, dy)
                lastTouchX = x
                lastTouchY = y
                invalidate()
                onCropChanged?.invoke(cropRect)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                activeHandle = Handle.NONE
                parent.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return false
    }

    private fun getHandle(x: Float, y: Float): Handle {
        val t = touchSlop
        if (dist(x, y, cropRect.left, cropRect.top) < t) return Handle.TOP_LEFT
        if (dist(x, y, cropRect.right, cropRect.top) < t) return Handle.TOP_RIGHT
        if (dist(x, y, cropRect.left, cropRect.bottom) < t) return Handle.BOTTOM_LEFT
        if (dist(x, y, cropRect.right, cropRect.bottom) < t) return Handle.BOTTOM_RIGHT
        if (cropRect.contains(x, y)) return Handle.CENTER
        return Handle.NONE
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
    }

    private fun moveHandle(dx: Float, dy: Float) {
        val bounds = getContentBounds()

        when (activeHandle) {
            Handle.CENTER -> {
                val newLeft = (cropRect.left + dx).coerceIn(bounds.left, bounds.right - cropRect.width())
                val newTop = (cropRect.top + dy).coerceIn(bounds.top, bounds.bottom - cropRect.height())
                cropRect.offsetTo(newLeft, newTop)
            }
            Handle.TOP_LEFT -> {
                if (cropRatio.isFixed) {
                    resizeFromCornerFixed(dx, dy, isLeft = true, isTop = true)
                } else {
                    cropRect.left = (cropRect.left + dx).coerceIn(bounds.left, cropRect.right - minCropSize)
                    cropRect.top = (cropRect.top + dy).coerceIn(bounds.top, cropRect.bottom - minCropSize)
                }
            }
            Handle.TOP_RIGHT -> {
                if (cropRatio.isFixed) {
                    resizeFromCornerFixed(dx, dy, isLeft = false, isTop = true)
                } else {
                    cropRect.right = (cropRect.right + dx).coerceIn(cropRect.left + minCropSize, bounds.right)
                    cropRect.top = (cropRect.top + dy).coerceIn(bounds.top, cropRect.bottom - minCropSize)
                }
            }
            Handle.BOTTOM_LEFT -> {
                if (cropRatio.isFixed) {
                    resizeFromCornerFixed(dx, dy, isLeft = true, isTop = false)
                } else {
                    cropRect.left = (cropRect.left + dx).coerceIn(bounds.left, cropRect.right - minCropSize)
                    cropRect.bottom = (cropRect.bottom + dy).coerceIn(cropRect.top + minCropSize, bounds.bottom)
                }
            }
            Handle.BOTTOM_RIGHT -> {
                if (cropRatio.isFixed) {
                    resizeFromCornerFixed(dx, dy, isLeft = false, isTop = false)
                } else {
                    cropRect.right = (cropRect.right + dx).coerceIn(cropRect.left + minCropSize, bounds.right)
                    cropRect.bottom = (cropRect.bottom + dy).coerceIn(cropRect.top + minCropSize, bounds.bottom)
                }
            }
            Handle.NONE -> {}
        }
    }

    private fun resizeFromCornerFixed(dx: Float, dy: Float, isLeft: Boolean, isTop: Boolean) {
        val aspect = cropRatio.aspect
        val bounds = getContentBounds()

        // "Growth" delta: positive = growing the rect.
        // Left handle: dragging left (dx<0) grows the rect → growthX = -dx
        // Right handle: dragging right (dx>0) grows the rect → growthX = +dx
        // Top handle: dragging up (dy<0) grows the rect → growthY = -dy
        // Bottom handle: dragging down (dy>0) grows the rect → growthY = +dy
        val growthX = if (isLeft) -dx else dx
        val growthY = if (isTop) -dy else dy

        // Pick the dominant axis (compare in width-equivalent units)
        val growthDelta = if (Math.abs(growthX) >= Math.abs(growthY * aspect)) growthX else growthY * aspect

        val newWidth = (cropRect.width() + growthDelta).coerceIn(minCropSize, bounds.width())
        val newHeight = newWidth / aspect
        if (newHeight < minCropSize || newHeight > bounds.height()) return

        // The opposite edge stays fixed; the active edge moves
        if (isLeft) cropRect.left = cropRect.right - newWidth else cropRect.right = cropRect.left + newWidth
        if (isTop) cropRect.top = cropRect.bottom - newHeight else cropRect.bottom = cropRect.top + newHeight

        // Clamp to content bounds
        if (cropRect.left < bounds.left) cropRect.offsetTo(bounds.left, cropRect.top)
        if (cropRect.top < bounds.top) cropRect.offsetTo(cropRect.left, bounds.top)
        if (cropRect.right > bounds.right) cropRect.offsetTo(bounds.right - cropRect.width(), cropRect.top)
        if (cropRect.bottom > bounds.bottom) cropRect.offsetTo(cropRect.left, bounds.bottom - cropRect.height())
    }

    /**
     * Returns crop coordinates mapped to actual video dimensions.
     * Maps from content-relative coordinates (accounting for fitCenter scaling).
     */
    fun getCropInVideoCoords(): Rect {
        if (videoWidth <= 0 || videoHeight <= 0 || width <= 0 || height <= 0) {
            return Rect(0, 0, videoWidth, videoHeight)
        }
        val bounds = getContentBounds()
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            return Rect(0, 0, videoWidth, videoHeight)
        }
        val scaleX = videoWidth.toFloat() / bounds.width()
        val scaleY = videoHeight.toFloat() / bounds.height()
        return Rect(
            ((cropRect.left - bounds.left) * scaleX).toInt().coerceAtLeast(0),
            ((cropRect.top - bounds.top) * scaleY).toInt().coerceAtLeast(0),
            ((cropRect.right - bounds.left) * scaleX).toInt().coerceAtMost(videoWidth),
            ((cropRect.bottom - bounds.top) * scaleY).toInt().coerceAtMost(videoHeight)
        )
    }
}
