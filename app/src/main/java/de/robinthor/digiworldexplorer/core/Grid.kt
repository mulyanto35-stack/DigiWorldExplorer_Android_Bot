package de.robinthor.digiworldexplorer.core

import kotlin.math.max
import kotlin.math.min

/** Ukuran grid standar permainan (5x5). */
const val GRID_SIZE = 5

data class Cell(val row: Int, val col: Int)
data class ScreenPoint(val x: Int, val y: Int)

/**
 * Representasi batas papan permainan dan kalkulator koordinat sentuhan.
 * Dioptimalkan untuk Android 9 (API 28) & Layar 720x1600 (20:9).
 */
data class BoardBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    init {
        require(right > left && bottom > top) { "Board bounds must have a positive size" }
    }

    val width: Int get() = right - left
    val height: Int get() = bottom - top

    /**
     * Menghitung titik tengah piksel dari sebuah sel (5x5).
     * Dioptimalkan tanpa alokasi memori tambahan.
     */
    fun cellCenter(cell: Cell): ScreenPoint {
        require(cell.row in 0 until GRID_SIZE && cell.col in 0 until GRID_SIZE) {
            "Cell coordinates out of grid bounds: $cell"
        }

        // Kalkulasi berbasis rasio presisi tinggi untuk resolusi 720x1600
        val cellWidth = width.toDouble() / GRID_SIZE
        val cellHeight = height.toDouble() / GRID_SIZE

        val x = (left + (cell.col + 0.5) * cellWidth).toInt().coerceIn(left, right - 1)
        val y = (top + (cell.row + 0.5) * cellHeight).toInt().coerceIn(top, bottom - 1)

        return ScreenPoint(x, y)
    }

    /**
     * Mengembalikan koordinat Float (x, y) untuk kebutuhan eksekusi tap langsung pada AccessibilityService.
     */
    fun cellCenterFloat(cell: Cell): Pair<Float, Float> {
        val pt = cellCenter(cell)
        return pt.x.toFloat() to pt.y.toFloat()
    }
}
