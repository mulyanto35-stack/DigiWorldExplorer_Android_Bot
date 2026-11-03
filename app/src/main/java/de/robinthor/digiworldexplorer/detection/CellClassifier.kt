package de.robinthor.digiworldexplorer.detection

data class Cell(val row: Int, val col: Int)

data class CellScores(
    val player: Double,
    val orange: Double,
    val pink: Double,
    val green: Double,
    val item: Double,
    val pyramid: Double,
    val highlight: Double,
    val text: Double = 0.0,
    val legacyV4Obstacle: Boolean = false,
) {
    fun obstacle(): Boolean = pyramid > .30 && item <= .06 && (legacyV4Obstacle || highlight < .65)
}

/**
 * Deteksi warna & klasifikasi sel papan game.
 * Dioptimalkan untuk Android 9 (API 28) & Layar 720x1600.
 */
object CellClassifier {
    private const val STEP = 2

    fun classify(
        width: Int,
        height: Int,
        argb: IntArray,
        b: GridBounds,
        allSprites: Boolean = true,
        legacyV4Core: Boolean = false
    ): Map<Cell, CellScores> {
        require(argb.size == width * height)
        val out = HashMap<Cell, CellScores>(25)
        val cw = (b.right - b.left) / 5.0
        val ch = (b.bottom - b.top) / 5.0

        for (row in 0..4) {
            for (col in 0..4) {
                val x0 = (b.left + col * cw + cw * .07).toInt().coerceIn(0, width - 1)
                val x1 = (b.left + (col + 1) * cw - cw * .07).toInt().coerceIn(x0 + 1, width)
                val y0 = (b.top + row * ch + ch * .07).toInt().coerceIn(0, height - 1)
                val y1 = (b.top + (row + 1) * ch - ch * (if (row == 4) .42 else .11)).toInt().coerceIn(y0 + 1, height)
                val playerY1 = (b.top + (row + 1) * ch - ch * .11).toInt().coerceIn(y0 + 1, height)

                var n = 0
                var orange = 0
                var pink = 0
                var green = 0
                var red = 0
                var neutral = 0
                var yellow = 0
                var dark = 0
                var body = 0
                var hi = 0
                var pyramid = 0

                var y = y0
                while (y < y1) {
                    val rowOff = y * width
                    var x = x0
                    while (x < x1) {
                        val p = argb[rowOff + x]
                        val r = (p shr 16) and 255
                        val g = (p shr 8) and 255
                        val bl = p and 255
                        n++

                        val o = r > 180 && g > 55 && g < 190 && bl < 100
                        if (o) orange++
                        if (r > 170 && bl > 140 && g < 170 && r > g + 40) pink++
                        if (g > 130 && r < 150 && g > r + 35 && g > bl + 25) green++
                        if (r > 110 && r > g + 45 && r > bl + 25 && !o) red++

                        val max = maxOf(r, g, bl)
                        val min = minOf(r, g, bl)
                        if (min > 165 && max - min < 65) neutral++
                        if (r > 190 && g > 140 && bl < 80) yellow++
                        if (r < 65 && g < 65 && bl < 75) dark++

                        val delta = max - min
                        if (max < 90 && (max == 0 || delta * 255 < max * 120)) body++
                        if (bl > 120 && g > 90 && bl > r + 25) hi++
                        if (bl > 70 && r > 45 && bl > g + 10) pyramid++

                        x += STEP
                    }
                    y += STEP
                }

                val invN = 1.0 / kotlin.math.max(1, n)
                val os = orange * invN
                val ps = pink * invN
                val gs = green * invN
                val rs = red * invN
                val ys = yellow * invN
                val ds = dark * invN
                val his = hi * invN
                val bodys = body * invN

                val shadow = if (ds > .20 && ys > .008 && his > .20 && os < .04) ys * 8 else 0.0
                val genericBody = if (allSprites && bodys >= .05) (.08 + (bodys - .05) * 2.0).coerceAtMost(.60) else 0.0
                val yellowClaw = if (ys > .008 && ds < .08 && his < .25 && os < .06) ys * 7 else 0.0
                val claw = maxOf(rs * 4.0, yellowClaw)
                val item = maxOf(os, ps, gs, claw)

                val extra = if (row == 4) playerScore(width, height, argb, x0, x1, y0, playerY1, allSprites) else 0.0

                out[Cell(row, col)] = CellScores(
                    maxOf(shadow, genericBody, extra),
                    os, ps, gs, item, pyramid * invN, his, neutral * invN, legacyV4Core
                )
            }
        }
        return out
    }

    private fun playerScore(width: Int, height: Int, argb: IntArray, x0: Int, x1: Int, y0: Int, y1: Int, allSprites: Boolean): Double {
        var n = 0
        var yellow = 0
        var dark = 0
        var body = 0
        var hi = 0
        var orange = 0

        var y = y0
        while (y < y1) {
            val rowOff = y * width
            var x = x0
            while (x < x1) {
                val p = argb[rowOff + x]
                val r = (p shr 16) and 255
                val g = (p shr 8) and 255
                val b = p and 255
                n++

                if (r > 180 && g > 55 && g < 190 && b < 100) orange++
                if (r > 190 && g > 140 && b < 80) yellow++
                if (r < 65 && g < 65 && b < 75) dark++

                val max = maxOf(r, g, b)
                val min = minOf(r, g, b)
                val delta = max - min
                if (max < 90 && (max == 0 || delta * 255 < max * 120)) body++
                if (b > 120 && g > 90 && b > r + 25) hi++

                x += STEP
            }
            y += STEP
        }

        val invN = 1.0 / kotlin.math.max(1, n)
        val shadow = if (dark * invN > .20 && yellow * invN > .008 && hi * invN > .20 && orange * invN < .04) (yellow * invN) * 8 else 0.0
        val bodyFraction = body * invN
        val genericBody = if (allSprites && bodyFraction >= .05) (.08 + (bodyFraction - .05) * 2.0).coerceAtMost(.60) else 0.0

        return maxOf(shadow, genericBody)
    }
}
