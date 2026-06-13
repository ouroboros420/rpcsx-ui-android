package net.rpcsx.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceView

/**
 * Cosmetic frame drawn over the running game: optional rounded corners (a mask
 * filled with a chosen colour outside a rounded rect) and an optional coloured
 * border.
 *
 * It is a SurfaceView, NOT a plain View, on purpose. The game is rendered into
 * its own SurfaceView, which punches a transparent hole in the window and clears
 * any plain sibling View's pixels in that rect - so a plain-View overlay never
 * shows over the game (verified broken on Adreno). PadOverlay works precisely
 * because it is a SurfaceView that draws through the view path; this mirrors that
 * proven pattern exactly (no holder use, no background, no z-order calls). It
 * never consumes input, so the game surface and controls are unaffected.
 *
 * Logs under tag "RPCSX-GameFrame" so a logcat tells us definitively whether
 * configure ran, the view got a size, and draw fired.
 */
class GameFrameOverlay(context: Context?, attrs: AttributeSet?) : SurfaceView(context, attrs) {

    private var radiusPx = 0f
    private var drawCornerMask = false
    private var drawBorder = false
    private var drawLogs = 0

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val maskPath = Path()
    private val roundPath = Path()
    private val rect = RectF()

    init {
        isClickable = false
        isFocusable = false
    }

    private fun dp(v: Int) = v * resources.displayMetrics.density

    fun configure(
        roundedCorners: Boolean,
        cornerRadiusDp: Int,
        cornerColor: Int,
        border: Boolean,
        borderWidthDp: Int,
        borderColor: Int,
    ) {
        radiusPx = dp(cornerRadiusDp)
        drawCornerMask = roundedCorners && radiusPx > 0f
        maskPaint.color = cornerColor
        drawBorder = border && borderWidthDp > 0 && Color.alpha(borderColor) > 0
        borderPaint.color = borderColor
        borderPaint.strokeWidth = dp(borderWidthDp)
        visibility = if (drawCornerMask || drawBorder) VISIBLE else GONE
        drawLogs = 0
        Log.i(TAG, "configure rounded=$roundedCorners radiusDp=$cornerRadiusDp cornerColor=${hex(cornerColor)} " +
                "border=$border borderDp=$borderWidthDp borderColor=${hex(borderColor)} " +
                "-> mask=$drawCornerMask drawBorder=$drawBorder visibility=${if (visibility == VISIBLE) "VISIBLE" else "GONE"}")
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Log.i(TAG, "attached (visibility=${if (visibility == VISIBLE) "VISIBLE" else "GONE"})")
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        Log.i(TAG, "size ${w}x$h")
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (drawLogs < 3) {
            Log.i(TAG, "draw ${w.toInt()}x${h.toInt()} mask=$drawCornerMask border=$drawBorder")
            drawLogs++
        }
        if (w <= 0f || h <= 0f) return

        if (drawCornerMask) {
            // Fill the area between the full rect and the rounded rect with the
            // corner colour, so the square surface reads as rounded.
            maskPath.reset()
            maskPath.addRect(0f, 0f, w, h, Path.Direction.CW)
            roundPath.reset()
            roundPath.addRoundRect(0f, 0f, w, h, radiusPx, radiusPx, Path.Direction.CW)
            maskPath.op(roundPath, Path.Op.DIFFERENCE)
            canvas.drawPath(maskPath, maskPaint)
        }

        if (drawBorder) {
            val inset = borderPaint.strokeWidth / 2f
            rect.set(inset, inset, w - inset, h - inset)
            canvas.drawRoundRect(rect, radiusPx, radiusPx, borderPaint)
        }
    }

    private fun hex(c: Int) = String.format("#%08X", c)

    private companion object {
        const val TAG = "RPCSX-GameFrame"
    }
}
