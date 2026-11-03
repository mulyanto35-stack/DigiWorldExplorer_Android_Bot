package de.robinthor.digiworldexplorer.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import de.robinthor.digiworldexplorer.capture.AnalysisImage as Image
import de.robinthor.digiworldexplorer.detection.CellClassifier
import de.robinthor.digiworldexplorer.detection.CalibrationValidator
import de.robinthor.digiworldexplorer.detection.GridDetector
import de.robinthor.digiworldexplorer.detection.PreviewClassifier
import de.robinthor.digiworldexplorer.strategy.AutoMoveController
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

internal fun compatibleGridBounds(
    a: de.robinthor.digiworldexplorer.detection.GridBounds,
    b: de.robinthor.digiworldexplorer.detection.GridBounds
): Boolean {
    val cell = minOf(a.right - a.left, a.bottom - a.top, b.right - b.left, b.bottom - b.top) / 5.0
    val tolerance = maxOf(4.0, cell * .08)
    return abs(a.left - b.left) <= tolerance && abs(a.top - b.top) <= tolerance &&
            abs(a.right - b.right) <= tolerance && abs(a.bottom - b.bottom) <= tolerance
}

object CaptureFrameAnalyzer {
    private const val CANDIDATE_MIN = .55
    private const val STABLE_FRAMES = 3
    private const val DIAGNOSTIC_INTERVAL = 60_000L

    @Volatile private var lastDiagnostic = 0L
    @Volatile private var calibrated: de.robinthor.digiworldexplorer.detection.GridDetection? = null
    @Volatile private var pending: de.robinthor.digiworldexplorer.detection.GridDetection? = null
    @Volatile private var pendingCount = 0

    private var lastGridVerification = 0L
    private var verifyCleanFrame = false
    private var unverifiableFrames = 0
    private var diagnosticFrameSequence = 0L
    private val gridRecheck = GridRecheckPolicy()

    val isCalibrated: Boolean get() = calibrated != null

    fun resetCalibration() {
        calibrated = null
        pending = null
        pendingCount = 0
        lastGridVerification = 0L
        verifyCleanFrame = false
        unverifiableFrames = 0
        gridRecheck.reset()
        AutoMoveController.reset()
        de.robinthor.digiworldexplorer.accessibility.DigiWorldAccessibilityService.instance?.clearCalibrationOverlay()
    }

    data class Result(val detected: Boolean, val confidence: Double, val output: String)

    private fun stable(candidate: de.robinthor.digiworldexplorer.detection.GridDetection): Boolean {
        val previous = pending
        val same = previous != null && compatibleGridBounds(previous.bounds, candidate.bounds)
        if (same) pendingCount++ else {
            pending = candidate
            pendingCount = 1
        }
        return pendingCount >= STABLE_FRAMES
    }

    fun analyze(context: Context, image: Image, width: Int, height: Int): Result? = runCatching {
        val frameNow = android.os.SystemClock.elapsedRealtime()
        if (calibrated != null && !verifyCleanFrame && frameNow - lastGridVerification >= 5_000L) {
            verifyCleanFrame = true
            AutoMoveController.pauseForGridRecheck()
            de.robinthor.digiworldexplorer.accessibility.DigiWorldAccessibilityService.instance?.hideForCapture()
            return@runCatching Result(false, 0.0, "")
        }

        val plane = image.planes.first()
        val paddedWidth = plane.rowStride / plane.pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer)
        val frame = Bitmap.createBitmap(padded, 0, 0, width, height)
        if (frame !== padded) padded.recycle()

        val pixels = IntArray(width * height)
        frame.getPixels(pixels, 0, width, 0, 0, width, height)
        val frameId = ++diagnosticFrameSequence
        val geometry = FrameGeometryRegistry.forCaller(frameNow)
        val diagnostics = de.robinthor.digiworldexplorer.diagnostics.PersistentDiagnosticLog
        val cleanRecheckFrame = verifyCleanFrame

        fun evidence(state: Map<String, Any?>, hudTop: Int? = null) {
            if (!diagnostics.isEnabled()) return
            diagnostics.recordDwsFrame(
                frame,
                linkedMapOf<String, Any?>(
                    "frameId" to frameId,
                    "analysisSize" to listOf(width, height),
                    "generation" to geometry?.generation,
                    "gameInCapture" to geometry?.geometry?.gameInCapture?.toString(),
                    "captureSize" to geometry?.geometry?.capture?.toString(),
                    "display" to geometry?.geometry?.display?.toString(),
                    "displayOrigin" to geometry?.geometry?.displayOrigin?.toString(),
                    "scaleToDisplay" to geometry?.geometry?.scaleToDisplay,
                    "overlayMayBeVisible" to !cleanRecheckFrame,
                    "rules" to de.robinthor.digiworldexplorer.strategy.AutomationState.dwsNavigationSettings.toString()
                ) + state,
                hudTop
            )
        }

        val dwsSettings = de.robinthor.digiworldexplorer.strategy.AutomationState.dwsNavigationSettings

        if (verifyCleanFrame) {
            verifyCleanFrame = false
            lastGridVerification = frameNow
            val fresh = GridDetector.detect(width, height, pixels)?.takeIf { it.confidence >= CANDIDATE_MIN }
            val previous = calibrated
            val same = fresh != null && previous != null && compatibleGridBounds(previous.bounds, fresh.bounds)
            if (same) unverifiableFrames = 0 else unverifiableFrames++

            val recheck = previous?.let { gridRecheck.observe(it.bounds, fresh?.bounds) } ?: GridRecheck.REVOKE
            if (recheck != GridRecheck.KEEP) {
                evidence(
                    mapOf(
                        "state" to "GRID_RECHECK_BLOCKED",
                        "previousGrid" to previous?.bounds?.toString(),
                        "freshGrid" to fresh?.bounds?.toString(),
                        "freshConfidence" to fresh?.confidence,
                        "misses" to unverifiableFrames,
                        "recheck" to recheck.name
                    )
                )
                AutoMoveController.pauseForGridRecheck()
            }
            if (recheck == GridRecheck.BLOCK) {
                verifyCleanFrame = true
                frame.recycle()
                return@runCatching Result(false, 0.0, "")
            }
            if (recheck == GridRecheck.REVOKE) {
                de.robinthor.digiworldexplorer.diagnostics.PersistentDiagnosticLog.record(
                    "GEOMETRY.GRID",
                    "revoked previous=${previous?.bounds} fresh=${fresh?.bounds} misses=$unverifiableFrames"
                )
                resetCalibration()
                lastGridVerification = frameNow
            }
        }

        var detection = calibrated ?: GridDetector.detect(width, height, pixels)?.takeIf { it.confidence >= CANDIDATE_MIN }
        var cells = detection?.let {
            CellClassifier.classify(width, height, pixels, it.bounds, dwsSettings.trackAllSprites, dwsSettings.legacyV4Core)
        }

        if (calibrated == null && detection != null) {
            val geometryStable = stable(detection)
            if (cells != null && CalibrationValidator.plausible(cells) && geometryStable) {
                val locked = detection.copy(confidence = maxOf(detection.confidence, .90), reason = "Raster ueber $STABLE_FRAMES Frames stabil")
                calibrated = locked
                lastGridVerification = frameNow
                detection = locked
                android.util.Log.i("DigiWorldCapture", "kalibriert bounds=${locked.bounds} rohkonfidenz=${locked.confidence}")
            } else {
                detection = null
                cells = null
            }
        }

        val preview = detection?.let { PreviewClassifier.classify(width, height, pixels, it.bounds) } ?: emptyMap()
        val directory = File(context.getExternalFilesDir(null) ?: context.filesDir, "diagnostics").apply { mkdirs() }
        var gameVisible = false

        if (detection != null && cells != null) {
            val player = cells.maxByOrNull { it.value.player }
            val playerCell = player?.key
            val items = cells.filter { (cell, score) -> cell != playerCell && score.item > .06 }.keys.sortedWith(compareBy({ it.row }, { it.col }))
            val obstacles = cells.filter { (cell, score) -> cell != playerCell && score.obstacle() }.keys.sortedWith(compareBy({ it.row }, { it.col }))
            val detectedDashButton = de.robinthor.digiworldexplorer.detection.DashButtonLocator.locate(width, height, pixels, detection.bounds)
            val dashButton = detectedDashButton
            val hudLayout = de.robinthor.digiworldexplorer.detection.HudCounterReader.detectActionRowLayout(width, height, pixels, detection.bounds)
            val hud = de.robinthor.digiworldexplorer.detection.HudCounterReader.read(width, height, pixels, detection.bounds, hudLayout)

            if (hud.unknown.isNotEmpty()) android.util.Log.i("DigiWorldHud", "unbekannte Ziffer " + hud.unknown)
            val summary = "confidence=" + detection.confidence + "\nplayer=" + player?.key + " score=" + player?.value?.player + "\nitems=" + items + "\nobstacles=" + obstacles + "\npreview=" + preview + "\ndash=" + dashButton + "\nkrallen=" + hud.claws + " dashvorrat=" + hud.dash + " dashMinimumOnly=" + hud.dashMinimumOnly + "\n"

            if (diagnostics.isEnabled() && android.os.SystemClock.elapsedRealtime() - lastDiagnostic >= DIAGNOSTIC_INTERVAL) {
                File(directory, "latest_detection.txt").writeText(summary)
                android.util.Log.i("DigiWorldDetection", summary.replace("\n", "; "))
            }

            gameVisible = CalibrationValidator.plausible(cells)
            val detectedState = mapOf<String, Any?>(
                "grid" to listOf(detection.bounds.left, detection.bounds.top, detection.bounds.right, detection.bounds.bottom),
                "gridConfidence" to detection.confidence,
                "gridReason" to detection.reason,
                "gamePlausible" to gameVisible,
                "hudLayout" to hudLayout.name,
                "dashRead" to hud.dash,
                "dashMinimumOnly" to hud.dashMinimumOnly,
                "clawsRead" to hud.claws,
                "dashBox" to hud.dashBox?.toString(),
                "clawsBox" to hud.clawsBox?.toString(),
                "unknownGlyphs" to hud.unknown,
                "dashButton" to dashButton?.let { listOf(it.first, it.second) },
                "cells" to cells.map { (cell, score) ->
                    mapOf(
                        "row" to cell.row,
                        "col" to cell.col,
                        "player" to score.player,
                        "pyramid" to score.pyramid,
                        "item" to score.item,
                        "orange" to score.orange,
                        "text" to score.text,
                        "highlight" to score.highlight,
                        "obstacle" to score.obstacle()
                    )
                },
                "preview" to preview.map { (cell, score) -> mapOf("row" to cell.row, "col" to cell.col, "scores" to score.toString()) }
            )

            val callback: ((Map<String, Any?>) -> Unit)? = if (diagnostics.isEnabled()) {
                { decision -> evidence(detectedState + decision, detection.bounds.bottom.coerceIn(0, height - 1)) }
            } else null

            if (gameVisible) AutoMoveController.onAnalysis(detection.confidence, detection.bounds, cells, preview, dashButton, hud, callback, frameId)
            else AutoMoveController.onAnalysis(0.0, detection.bounds, emptyMap(), emptyMap(), null, hud, callback, frameId)
        } else {
            evidence(mapOf("state" to "WAIT_GRID", "pendingGrid" to pending?.bounds?.toString(), "stableFrames" to pendingCount))
        }

        val output = File(directory, "dynamic_grid.png")
        val now = android.os.SystemClock.elapsedRealtime()
        if (diagnostics.isEnabled() && now - lastDiagnostic >= DIAGNOSTIC_INTERVAL) {
            lastDiagnostic = now
            val diagnostic = frame.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(diagnostic)
            val paint = Paint().apply {
                color = Color.GREEN
                style = Paint.Style.STROKE
                strokeWidth = (width / 180f).coerceAtLeast(3f)
            }
            detection?.bounds?.let { b ->
                canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(), paint)
                for (i in 1..4) {
                    val x = b.left + (b.right - b.left) * i / 5f
                    val y = b.top + (b.bottom - b.top) * i / 5f
                    canvas.drawLine(x, b.top.toFloat(), x, b.bottom.toFloat(), paint)
                    canvas.drawLine(b.left.toFloat(), y, b.right.toFloat(), y, paint)
                }
            }
            FileOutputStream(output).use { diagnostic.compress(Bitmap.CompressFormat.PNG, 90, it) }
            diagnostic.recycle()
        }

        frame.recycle()
        Result(gameVisible, detection?.confidence ?: 0.0, output.absolutePath)
    }.onFailure { android.util.Log.e("DigiWorldCapture", "analyze failed", it) }.getOrNull()
}
