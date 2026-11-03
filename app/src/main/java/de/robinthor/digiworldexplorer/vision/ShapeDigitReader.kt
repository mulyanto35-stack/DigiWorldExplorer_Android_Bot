package de.robinthor.digiworldexplorer.vision

import kotlin.math.max
import kotlin.math.min

/**
 * Shape rules used by the game's compact white counters, normalized to a 12x16 grid.
 * Dioptimalkan untuk Android 9 (API 28) & Resolusi Layar 720x1600 (Zero-allocation bitmask).
 */
object ShapeDigitReader {
    private const val COLS = 12
    private const val ROWS = 16

    fun classify(source: BooleanArray, width: Int, component: ColorComponent): Int? {
        if (width <= 0 || source.size % width != 0 || component.width <= 0 || component.height <= 0) return null
        val height = source.size / width
        if (component.left < 0 || component.top < 0 || component.right >= width || component.bottom >= height) return null

        // Optimalisasi Android 9: Menggunakan Bitmask 192-bit (3x Long) untuk menghindari alokasi BooleanArray berulang di memori
        var bit0 = 0L // Bit 0..63
        var bit1 = 0L // Bit 64..127
        var bit2 = 0L // Bit 128..191

        val compWidth = component.width
        val compHeight = component.height

        for (row in 0 until ROWS) {
            val y0 = component.top + row * compHeight / ROWS
            val y1 = min(component.bottom + 1, component.top + max(row * compHeight / ROWS + 1, (row + 1) * compHeight / ROWS))
            val rowOffset = row * COLS

            for (col in 0 until COLS) {
                val x0 = component.left + col * compWidth / COLS
                val x1 = min(component.right + 1, component.left + max(col * compWidth / COLS + 1, (col + 1) * compWidth / COLS))

                var on = 0
                var total = 0
                for (y in y0 until y1) {
                    val yIndex = y * width
                    for (x in x0 until x1) {
                        total++
                        if (source[yIndex + x]) on++
                    }
                }

                if (total > 0 && on * 2 >= total) {
                    val bitIndex = rowOffset + col
                    when (bitIndex / 64) {
                        0 -> bit0 = bit0 or (1L shl (bitIndex % 64))
                        1 -> bit1 = bit1 or (1L shl (bitIndex % 64))
                        2 -> bit2 = bit2 or (1L shl (bitIndex % 64))
                    }
                }
            }
        }

        val holes = holesBitmask(bit0, bit1, bit2)
        val bottom = meanBitmask(bit0, bit1, bit2, ROWS - 2, ROWS, 0, COLS)
        val right = meanBitmask(bit0, bit1, bit2, 0, ROWS, COLS - 4, COLS)

        if (holes.size >= 2) return 8
        if (holes.size == 1) {
            if (bottom < .35) return 4
            if (holes[0] < .4) return 9
            if (holes[0] > .55) return 6
            return 0
        }
        if (bottom < .35) return 7
        if (right >= .25 && meanBitmask(bit0, bit1, bit2, 2, 13, COLS - 3, COLS) >= .15) {
            if (bottom >= .85 && meanBitmask(bit0, bit1, bit2, 9, 13, 0, 4) > meanBitmask(bit0, bit1, bit2, 9, 13, COLS - 4, COLS)) return 2
            return if (meanBitmask(bit0, bit1, bit2, 3, 7, 0, 4) > meanBitmask(bit0, bit1, bit2, 3, 7, COLS - 4, COLS)) 5 else 3
        }
        return 1
    }

    private fun isBitSet(b0: Long, b1: Long, b2: Long, row: Int, col: Int): Boolean {
        val idx = row * COLS + col
        return when (idx / 64) {
            0 -> (b0 and (1L shl (idx % 64))) != 0L
            1 -> (b1 and (1L shl (idx % 64))) != 0L
            2 -> (b2 and (1L shl (idx % 64))) != 0L
            else -> false
        }
    }

    private fun meanBitmask(b0: Long, b1: Long, b2: Long, r0: Int, r1: Int, c0: Int, c1: Int): Double {
        var count = 0
        for (r in r0 until r1) {
            for (c in c0 until c1) {
                if (isBitSet(b0, b1, b2, r, c)) count++
            }
        }
        return count.toDouble() / max(1, (r1 - r0) * (c1 - c0))
    }

    private fun holesBitmask(b0: Long, b1: Long, b2: Long): List<Double> {
        val paddedWidth = COLS + 2
        val paddedHeight = ROWS + 2
        val background = BooleanArray(paddedWidth * paddedHeight) { true }

        for (r in 0 until ROWS) {
            for (c in 0 until COLS) {
                background[(r + 1) * paddedWidth + (c + 1)] = !isBitSet(b0, b1, b2, r, c)
            }
        }

        return ColorComponents.find(background, paddedWidth, paddedHeight).mapNotNull { component ->
            if (component.left == 0 || component.top == 0 || component.right == paddedWidth - 1 || component.bottom == paddedHeight - 1) null
            else {
                var rows = 0L
                var pixels = 0
                for (r in component.top..component.bottom) {
                    for (c in component.left..component.right) {
                        if (background[r * paddedWidth + c]) {
                            rows += r
                            pixels++
                        }
                    }
                }
                if (pixels == 0) null else rows.toDouble() / pixels / paddedHeight
            }
        }
    }
}
