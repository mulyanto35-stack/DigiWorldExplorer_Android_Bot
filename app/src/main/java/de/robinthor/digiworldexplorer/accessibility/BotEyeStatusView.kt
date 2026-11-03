package de.robinthor.digiworldexplorer.accessibility

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.view.View
import de.robinthor.digiworldexplorer.automation.BotEyeState
import de.robinthor.digiworldexplorer.automation.BotEyeStateResolver
import de.robinthor.digiworldexplorer.automation.DirectorSnapshot
import kotlin.math.sin

/** Mascot animatif: OFF, ACTIVE (hijau), COPILOT (ungu), SEARCHING (kuning), UNKNOWN (abu-abu), ERROR (merah). */
class BotEyeStatusView(context: Context) : View(context) {

    // Dioptimalkan: Alokasi tunggal untuk Paint guna menghindari alokasi memori di onDraw
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private val handler = Handler(Looper.getMainLooper())
    private var eyeState = BotEyeState.UNKNOWN
    private var frame = 0

    private val animate = object : Runnable {
        override fun run() {
            if (!isAttachedToWindow || windowVisibility != VISIBLE || !eyeState.animated) return
            frame = (frame + 1) % 24
            invalidate()
            handler.postDelayed(this, eyeState.frameDelay)
        }
    }

    init {
        // Android 9: Gunakan Hardware Layer untuk rendering bentuk geometris yang cepat & lancar
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun update(value: DirectorSnapshot) {
        val next = BotEyeStateResolver.resolve(value)
        if (next != eyeState) {
            frame = 0
            eyeState = next
            // Matikan akselerasi jika state diam untuk menghemat konsumsi daya pada Android 9
            if (eyeState.animated) {
                setLayerType(LAYER_TYPE_HARDWARE, null)
            } else {
                setLayerType(LAYER_TYPE_NONE, null)
            }
        }
        handler.removeCallbacks(animate)
        if (eyeState.animated && isAttachedToWindow && windowVisibility == VISIBLE) {
            handler.postDelayed(animate, eyeState.frameDelay)
        }
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (eyeState.animated && windowVisibility == VISIBLE) {
            handler.removeCallbacks(animate)
            handler.postDelayed(animate, eyeState.frameDelay)
        }
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(animate)
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE && eyeState.animated) {
            handler.removeCallbacks(animate)
            handler.postDelayed(animate, eyeState.frameDelay)
        } else {
            handler.removeCallbacks(animate)
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = resources.displayMetrics.density
        val cy = 24f * d
        val left = 20f * d
        val right = 32f * d

        if (eyeState == BotEyeState.OFF) {
            fillPaint.color = Color.argb(245, 7, 16, 25)
            canvas.drawOval(left - 6f * d, cy - 4.4f * d, left + 6f * d, cy + 4.4f * d, fillPaint)
            canvas.drawOval(right - 6f * d, cy - 4.4f * d, right + 6f * d, cy + 4.4f * d, fillPaint)

            strokePaint.color = Color.rgb(135, 145, 158)
            strokePaint.strokeWidth = 3.2f * d
            strokePaint.strokeCap = Paint.Cap.ROUND
            canvas.drawLine(left - 3f * d, cy, left + 3f * d, cy, strokePaint)
            canvas.drawLine(right - 3f * d, cy, right + 3f * d, cy, strokePaint)
            return
        }

        val blink = eyeState in setOf(BotEyeState.ACTIVE, BotEyeState.COPILOT) && frame in 20..21
        val radiusY = when {
            blink -> .55f * d
            eyeState == BotEyeState.UNKNOWN -> 2.7f * d
            else -> 4.7f * d
        }

        val color = when (eyeState) {
            BotEyeState.ACTIVE -> Color.rgb(55, 224, 143)
            BotEyeState.COPILOT -> Color.rgb(191, 105, 255)
            BotEyeState.SEARCHING -> Color.rgb(250, 190, 48)
            BotEyeState.UNKNOWN -> Color.rgb(145, 153, 164)
            BotEyeState.ERROR -> Color.rgb(239, 74, 82)
            BotEyeState.OFF -> Color.TRANSPARENT
        }

        // Gambar soket luar mata
        fillPaint.color = Color.argb(250, 5, 12, 22)
        canvas.drawOval(left - 6f * d, cy - radiusY, left + 6f * d, cy + radiusY, fillPaint)
        canvas.drawOval(right - 6f * d, cy - radiusY, right + 6f * d, cy + radiusY, fillPaint)

        // Gambar outline mata
        strokePaint.strokeWidth = 1.2f * d
        strokePaint.color = Color.argb(220, Color.red(color), Color.green(color), Color.blue(color))
        canvas.drawOval(left - 6f * d, cy - radiusY, left + 6f * d, cy + radiusY, strokePaint)
        canvas.drawOval(right - 6f * d, cy - radiusY, right + 6f * d, cy + radiusY, strokePaint)

        if (blink) return

        // Animasi posisi pupil & ukuran lingkaran mata
        val xOffset = if (eyeState == BotEyeState.SEARCHING) ((frame % 3) - 1) * 3.5f * d else 0f
        val radius = when (eyeState) {
            BotEyeState.ERROR -> (2.25f + .55f * ((sin(frame * .8) + 1.0) / 2.0)).toFloat()
            BotEyeState.ACTIVE, BotEyeState.COPILOT -> 3.25f
            BotEyeState.UNKNOWN -> 2.7f
            BotEyeState.SEARCHING -> 3.25f
            BotEyeState.OFF -> 0f
        } * d

        fillPaint.color = color
        canvas.drawCircle(left + xOffset, cy, radius, fillPaint)
        canvas.drawCircle(right + xOffset, cy, radius, fillPaint)

        // Indikator garis alis marah saat ERROR
        if (eyeState == BotEyeState.ERROR) {
            strokePaint.strokeWidth = 1.5f * d
            strokePaint.strokeCap = Paint.Cap.ROUND
            strokePaint.color = color
            canvas.drawLine(left - 4f * d, cy - 6f * d, left + 3f * d, cy - 4.5f * d, strokePaint)
            canvas.drawLine(right - 3f * d, cy - 4.5f * d, right + 4f * d, cy - 6f * d, strokePaint)
        }
    }

    private val BotEyeState.animated get() = this == BotEyeState.ACTIVE || this == BotEyeState.COPILOT || this == BotEyeState.SEARCHING || this == BotEyeState.ERROR
    private val BotEyeState.frameDelay get() = when (this) {
        BotEyeState.SEARCHING -> 340L
        BotEyeState.ERROR -> 180L
        BotEyeState.ACTIVE, BotEyeState.COPILOT -> 140L
        else -> 600L
    }
}
