package de.robinthor.digiworldexplorer.vision

import kotlin.math.max
import kotlin.math.min

data class NormalizedRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width get() = right - left
    val height get() = bottom - top
    val center get() = NormalizedPoint((left + right) / 2.0, (top + bottom) / 2.0)
}

/** 
 * Finds connected visible regions inside a bounded viewport area.
 * Dioptimalkan untuk Android 9 & Layar 720x1600.
 */
object ColorRegionLocator {
    fun find(
        frame: PixelFrame,
        viewport: GameViewport,
        area: NormalizedRect,
        sampleStep: Int = 2,
        predicate: (Rgb) -> Boolean,
    ): List<NormalizedRect> {
        val step = max(1, sampleStep)
        val vpLeft = viewport.left
        val vpTop = viewport.top
        val vpWidth = viewport.width
        val vpHeight = viewport.height

        val x0 = (vpLeft + vpWidth * area.left).toInt().coerceIn(vpLeft, vpLeft + vpWidth - 1)
        val y0 = (vpTop + vpHeight * area.top).toInt().coerceIn(vpTop, vpTop + vpHeight - 1)
        val x1 = (vpLeft + vpWidth * area.right).toInt().coerceIn(x0 + 1, vpLeft + vpWidth)
        val y1 = (vpTop + vpHeight * area.bottom).toInt().coerceIn(y0 + 1, vpTop + vpHeight)

        val maskWidth = ((x1 - x0) + step - 1) / step
        val maskHeight = ((y1 - y0) + step - 1) / step
        val totalSize = maskWidth * maskHeight
        if (totalSize <= 0) return emptyList()

        val mask = BooleanArray(totalSize)
        var idx = 0
        var my = y0
        while (my < y1) {
            val actualY = min(my, y1 - 1)
            var mx = x0
            while (mx < x1) {
                val actualX = min(mx, x1 - 1)
                mask[idx++] = predicate(frame.rgbAt(actualX, actualY))
                mx += step
            }
            my += step
        }

        val invVpWidth = 1.0 / vpWidth
        val invVpHeight = 1.0 / vpHeight

        return ColorComponents.find(mask, maskWidth, maskHeight).map { component ->
            NormalizedRect(
                ((x0 + component.left * step) - vpLeft) * invVpWidth,
                ((y0 + component.top * step) - vpTop) * invVpHeight,
                ((x0 + (component.right + 1) * step) - vpLeft) * invVpWidth,
                ((y0 + (component.bottom + 1) * step) - vpTop) * invVpHeight,
            )
        }
    }
}
