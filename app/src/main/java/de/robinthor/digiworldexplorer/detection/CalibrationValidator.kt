package de.robinthor.digiworldexplorer.detection

object CalibrationValidator {
    fun plausible(cells: Map<Cell, CellScores>): Boolean {
        if (cells.isEmpty()) return false
        var textCovered = 0
        var maxPlayer = 0.0
        var highPlayerCount = 0

        for (score in cells.values) {
            if (score.text > .08) textCovered++
            val p = score.player
            if (p > maxPlayer) maxPlayer = p
            if (p > .50) highPlayerCount++
        }

        return textCovered < 3 && maxPlayer >= .08 && highPlayerCount <= 2
    }
}
