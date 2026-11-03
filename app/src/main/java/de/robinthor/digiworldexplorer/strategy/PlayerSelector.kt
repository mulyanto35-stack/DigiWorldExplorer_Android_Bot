package de.robinthor.digiworldexplorer.strategy

import de.robinthor.digiworldexplorer.detection.Cell
import de.robinthor.digiworldexplorer.detection.CellScores
import kotlin.math.abs

/**
 * Pemilih sel posisi pemain.
 * Dioptimalkan untuk Android 9 (API 28) - Pemrosesan memori efisien.
 */
object PlayerSelector {
    fun select(
        cells: Map<Cell, CellScores>,
        previous: Cell?,
        expected: Cell?,
        recentItems: Set<Cell>,
        minScore: Double = .08,
        legacyV4Core: Boolean = false
    ): Map.Entry<Cell, CellScores>? {
        if (cells.isEmpty()) return null

        expected?.let { e ->
            val match = cells.entries.firstOrNull { it.key == e && it.value.player >= minScore }
            if (match != null) return match
        }

        var bestEntry: Map.Entry<Cell, CellScores>? = null
        var maxCalculatedScore = -1.0

        for (entry in cells.entries) {
            val score = entry.value.player
            if (score < minScore) continue

            val cell = entry.key
            if (cell in recentItems && cell != previous) continue
            if (previous != null && distance(cell, previous) > 1) continue

            if (legacyV4Core) {
                val effectiveScore = score + (if (cell == previous) .04 else 0.0)
                if (effectiveScore > maxCalculatedScore) {
                    maxCalculatedScore = effectiveScore
                    bestEntry = entry
                }
            } else {
                if (score > maxCalculatedScore) {
                    maxCalculatedScore = score
                    bestEntry = entry
                }
            }
        }

        if (!legacyV4Core && previous != null) {
            val oldEntry = cells.entries.firstOrNull { it.key == previous && it.value.player >= minScore }
            if (oldEntry != null && maxCalculatedScore > 0 && oldEntry.value.player >= maxCalculatedScore * .68) {
                return oldEntry
            }
        }

        return bestEntry
    }

    private fun distance(a: Cell, b: Cell) = abs(a.row - b.row) + abs(a.col - b.col)
}
