package de.robinthor.digiworldexplorer.vision

import kotlin.math.roundToInt

/** 
 * Small sampled text silhouette from our game screenshots.
 * Dioptimalkan untuk Android 9 (API 28) & Skala Layar 720x1600.
 */
class ScreenTextPattern(private val left: Int, private val top: Int, private val rows: List<String>) {
    fun matches(frame: PixelFrame, viewport: GameViewport = GameViewport.detect(frame)): Boolean {
        return score(frame, viewport) >= .80
    }

    fun score(frame: PixelFrame, viewport: GameViewport = GameViewport.detect(frame)): Double {
        var best = 0.0
        val vLeft = viewport.left
        val vTop = viewport.top
        val vWidth = viewport.width.toDouble()
        val vHeight = viewport.height.toDouble()

        // Skala koordinat dihitung di luar loop internal untuk menghemat instruksi CPU
        for (dy in -1..1) {
            for (dx in -1..1) {
                var intersection = 0
                var union = 0
                for (y in rows.indices) {
                    val rowStr = rows[y]
                    val py = vTop + ((top + y * 3 + dy) * vHeight / 1600.0).roundToInt()
                    
                    for (x in rowStr.indices) {
                        val px = vLeft + ((left + x * 3 + dx) * vWidth / 720.0).roundToInt()
                        val p = frame.rgbAt(px, py)
                        
                        val actual = p.red > 165 && p.green > 190 && p.blue > 190
                        val expected = rowStr[x] == '#'
                        
                        if (actual && expected) intersection++
                        if (actual || expected) union++
                    }
                }
                if (union > 30) {
                    val currentScore = intersection.toDouble() / union
                    if (currentScore > best) best = currentScore
                }
            }
        }
        return best
    }
}
