package de.robinthor.digiworldexplorer.vision

import kotlin.math.max
import kotlin.math.min

data class ColorComponent(val left: Int, val top: Int, val right: Int, val bottom: Int, val pixels: Int) {
    val width get() = right - left + 1
    val height get() = bottom - top + 1
}

/** 
 * Eight-connected flood fill; dioptimalkan untuk performa Android 9 (Bebas GC thrashing).
 */
object ColorComponents {
    fun find(mask: BooleanArray, width: Int, height: Int): List<ColorComponent> {
        val totalSize = width * height
        require(width > 0 && height > 0 && totalSize == mask.size)
        if (totalSize == 0) return emptyList()

        val visited = BooleanArray(totalSize)
        val queue = IntArray(totalSize)
        val result = mutableListOf<ColorComponent>()

        for (start in 0 until totalSize) {
            if (!mask[start] || visited[start]) continue

            var head = 0
            var tail = 1
            queue[0] = start
            visited[start] = true

            var left = start % width
            var right = left
            var top = start / width
            var bottom = top

            while (head < tail) {
                val index = queue[head++]
                val x = index % width
                val y = index / width

                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y

                val minY = max(0, y - 1)
                val maxY = min(height - 1, y + 1)
                val minX = max(0, x - 1)
                val maxX = min(width - 1, x + 1)

                for (ny in minY..maxY) {
                    val rowOffset = ny * width
                    for (nx in minX..maxX) {
                        val adjacent = rowOffset + nx
                        if (mask[adjacent] && !visited[adjacent]) {
                            visited[adjacent] = true
                            queue[tail++] = adjacent
                        }
                    }
                }
            }
            result.add(ColorComponent(left, top, right, bottom, tail))
        }
        return result
    }
}
