package de.robinthor.digiworldexplorer.strategy

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import de.robinthor.digiworldexplorer.R
import de.robinthor.digiworldexplorer.accessibility.DigiWorldAccessibilityService
import de.robinthor.digiworldexplorer.capture.ScreenCaptureService
import de.robinthor.digiworldexplorer.detection.Cell
import de.robinthor.digiworldexplorer.detection.CellScores
import de.robinthor.digiworldexplorer.detection.GridBounds
import de.robinthor.digiworldexplorer.detection.HudCounters
import de.robinthor.digiworldexplorer.input.SafeTapRandomizer

internal fun safeDashCharges(read: Int?): Int = read?.coerceAtLeast(0) ?: 0

/**
 * Controller pergerakan otomatis dihitung per-frame.
 * Dioptimalkan untuk Android 9 (API 28) & Layar 720x1600.
 */
object AutoMoveController {
    private const val MIN_GRID = .82
    private const val MIN_PLAYER = .08
    private const val TAP_DELAY = 800L
    private const val STUCK_FRAMES = 10
    private const val UNKNOWN_CLAW_FALLBACK = 4
    private const val NO_PROGRESS_LIMIT = 25
    private const val MAX_FAILED_ACTIONS = 5
    private const val LOST_LIMIT = 15
    private const val EXPECTED_TTL = 4
    private const val SETTLE_TOLERANCE = .02
    private const val SETTLE_LIMIT = 12
    private const val PENDING_TIMEOUT = 2500L
    private const val BURST_ITEM = 2
    private const val BURST_RIGHT_V4 = 3
    private const val BURST_RIGHT_PHONE_SAFE = 2
    private const val BURST_DELAY_V4 = 800L
    private const val BURST_DELAY_PHONE_SAFE = 1_100L
    private const val DIALOG_TEXT = .08
    private const val DIALOG_CELLS = 3
    private const val BLIND_STAGE_INTERVAL = 10_000L
    private const val DWS_CONTEXT_TTL = 8_000L

    private val main = Handler(Looper.getMainLooper())
    private val history = ArrayDeque<Cell>()
    private val recentItems = mutableMapOf<Cell, Int>()

    private var candidate: Cell? = null
    private var stable = 0
    private var lastTap = 0L
    private var nextTapDelay = TAP_DELAY
    private var pending = false
    private var previous: Cell? = null
    private var expected: Cell? = null

    @Volatile private var dialogActive = false

    private val forbiddenObstacles = mutableSetOf<Cell>()
    private var lastAttackTarget: Cell? = null
    private var lastAttackPlayer: Cell? = null
    private var unchangedAttackFrames = 0
    private var sameCellFrames = 0
    private var furthestCol = -1
    private var trackingConfirmed = false
    private var lostFrames = 0
    private var noProgressFrames = 0
    private var dashFailures = 0
    private var actionsWithoutProgress = 0

    private var lastResourceKind: ActionKind? = null
    private var resourcePlayer: Cell? = null
    private var resourceUnchangedFrames = 0
    private var attackUnavailable = false
    private var dashUnavailable = false
    private var dashBlockedUntil = 0L
    private var lastDwsGridSeen = 0L
    private var lastBlindStageTap = 0L

    private var lastSignature: List<Double> = emptyList()
    private var lastSettledSignature: List<Double> = emptyList()
    private var expectedAge = 0
    private var unsettledFrames = 0

    private var rightScrolls: Boolean? = null
    private var probeFrom: Cell? = null
    private var expectedRight: Cell? = null

    @Volatile private var burstEpoch = 0L
    private var lastScheduledActionFrame: Long? = null

    fun onAnalysis(
        confidence: Double,
        bounds: GridBounds,
        cells: Map<Cell, CellScores>,
        preview: Map<Cell, CellScores>,
        dashButton: Pair<Float, Float>? = null,
        hud: HudCounters = HudCounters(),
        diagnostic: ((Map<String, Any?>) -> Unit)? = null,
        sourceFrameId: Long = 0
    ) {
        fun evidence(state: String, extra: Map<String, Any?> = emptyMap()) {
            diagnostic?.invoke(
                linkedMapOf<String, Any?>(
                    "state" to state,
                    "previous" to previous?.toString(),
                    "expected" to expected?.toString(),
                    "pendingInput" to pending,
                    "unsettledFrames" to unsettledFrames,
                    "trackingConfirmed" to trackingConfirmed,
                    "rightScrolls" to rightScrolls,
                    "lastScheduledActionFrame" to lastScheduledActionFrame,
                    "actionsWithoutProgress" to actionsWithoutProgress,
                    "dashUnavailable" to dashUnavailable,
                    "dashFailures" to dashFailures,
                    "attackUnavailable" to attackUnavailable
                ) + extra
            )
        }

        val dwsSettings = AutomationState.dwsNavigationSettings
        val self = setOfNotNull(previous, expected)
        recentItems.replaceAll { _, ttl -> ttl - 1 }
        recentItems.entries.removeIf { it.value <= 0 || it.key in self }

        cells.filter { (_, score) -> score.item > .06 && (dwsSettings.legacyV4Core || score.player < MIN_PLAYER) && (!dwsSettings.collectOnlyEnergy || score.orange > .06) }
            .filterKeys { it !in self }.keys.forEach { recentItems[it] = 6 }

        val service = DigiWorldAccessibilityService.instance
        if (pending && SystemClock.elapsedRealtime() - lastTap > PENDING_TIMEOUT) {
            Log.w("DigiWorldAuto", "Tap-Rueckmeldung ausgeblieben - Sperre aufgehoben")
            pending = false
            expected = null
            trackingConfirmed = false
        }

        val entry = PlayerSelector.select(cells, previous, expected, recentItems.keys, MIN_PLAYER, dwsSettings.legacyV4Core)
        val player = entry?.key
        val valid = confidence >= MIN_GRID && entry != null

        if (valid) lastDwsGridSeen = SystemClock.elapsedRealtime()

        val obstacles = cells.filter { (c, s) -> c != player && s.obstacle() }.keys + preview.filter { it.value.pyramid > .17 && it.value.item <= .06 }.keys
        val onlyEnergy = AutomationState.dwsNavigationSettings.collectOnlyEnergy
        val energyVisible = cells.any { (c, s) -> c != player && s.orange > .06 }
        val items = cells.filter { (c, s) -> c != player && s.item > .06 && (!onlyEnergy || s.orange > .06) }.keys +
                preview.filter { it.value.item > .06 && (!onlyEnergy || it.value.orange > .06) }.keys

        val texty = cells.count { it.value.text > DIALOG_TEXT }
        if (texty >= DIALOG_CELLS) {
            evidence("WAIT_DIALOG", mapOf("textCells" to texty))
            val now = SystemClock.elapsedRealtime()
            if (AutomationState.dwsNavigationSettings.blindStageFailedTap && now - lastDwsGridSeen <= DWS_CONTEXT_TTL && now - lastBlindStageTap >= BLIND_STAGE_INTERVAL && service != null) {
                lastBlindStageTap = now
                dispatchBlindStageDismiss(service, bounds)
            }
            Log.i("DigiWorldAuto", "Meldung im Bild ($texty Zellen mit Schrift) - Automatik wartet")
            service?.updateOverlay(bounds, null, emptySet(), emptySet(), null, service.getString(R.string.overlay_wait_dialog), AutomationState.overlayEnabled, hud, dashButton)
            dialogActive = true
            candidate = null
            stable = 0
            expected = null
            expectedAge = 0
            trackingConfirmed = false
            probeFrom = null
            return
        }
        dialogActive = false

        if (!valid) {
            lostFrames++
            if (lostFrames >= LOST_LIMIT) {
                Log.w("DigiWorldAuto", "Verfolgung nach $lostFrames Analysen ohne Spieler zurueckgesetzt")
                recentItems.clear()
                expected = null
                previous = null
                trackingConfirmed = false
                lostFrames = 0
            }
            val reason = when {
                cells.isEmpty() -> "kein Spielbild"
                confidence < MIN_GRID -> "Raster unsicher %.2f".format(confidence)
                else -> "Spieler unsicher (%d Kandidaten, best %.3f)".format(cells.count { it.value.player >= MIN_PLAYER }, cells.values.maxOf { it.player })
            }
            evidence("WAIT_RECOGNITION", mapOf("reason" to reason, "selectedPlayer" to player?.toString()))
            val overlayStatus = when {
                cells.isEmpty() -> service?.getString(R.string.overlay_no_game) ?: reason
                confidence < MIN_GRID -> service?.getString(R.string.overlay_grid_uncertain) ?: reason
                else -> service?.getString(R.string.overlay_player_uncertain) ?: reason
            }
            if (cells.isEmpty() || confidence < MIN_GRID) service?.updateStatusKeepingGrid(overlayStatus, AutomationState.overlayEnabled)
            else service?.updateOverlay(bounds, player, items, obstacles, null, overlayStatus, AutomationState.overlayEnabled, hud, dashButton)
            candidate = null
            stable = 0
            return
        }

        lostFrames = 0
        val signature = (0..4).flatMap { r -> (0..4).map { c -> cells[Cell(r, c)]?.pyramid ?: 0.0 } }
        val motionDelta = if (lastSignature.size == signature.size) signature.indices.maxOf { kotlin.math.abs(signature[it] - lastSignature[it]) } else null
        val quiet = motionDelta != null && motionDelta <= SETTLE_TOLERANCE
        lastSignature = signature
        if (quiet) unsettledFrames = 0 else unsettledFrames++
        val settled = quiet || unsettledFrames >= SETTLE_LIMIT

        if (!settled) {
            evidence("WAIT_MOTION", mapOf("motionMaxDelta" to motionDelta, "motionTolerance" to SETTLE_TOLERANCE, "selectedPlayer" to player.toString()))
            service?.updateOverlay(bounds, player, items, obstacles, null, service.getString(R.string.overlay_wait_motion), AutomationState.overlayEnabled, hud, dashButton)
            candidate = null
            stable = 0
            return
        }

        unsettledFrames = 0
        val advanced = lastSettledSignature.isNotEmpty() && signature.indices.any { kotlin.math.abs(signature[it] - lastSettledSignature[it]) > SETTLE_TOLERANCE * 2 }
        lastSettledSignature = signature
        forbiddenObstacles.removeIf { cells[it]?.obstacle() != true }

        lastAttackTarget?.let { target ->
            if (player == lastAttackPlayer && cells[target]?.obstacle() == true) {
                unchangedAttackFrames++
                if (unchangedAttackFrames >= 2) forbiddenObstacles += target
            } else {
                lastAttackTarget = null
                lastAttackPlayer = null
                unchangedAttackFrames = 0
            }
        }

        if (expected == player) {
            expected = null
            expectedAge = 0
            trackingConfirmed = true
        } else if (expected != null) {
            expectedAge++
            if (expectedAge >= EXPECTED_TTL) {
                expected = null
                expectedAge = 0
            }
        }

        probeFrom?.let { from ->
            if (player == expectedRight) {
                rightScrolls = false
                probeFrom = null
            } else if (advanced && player == from) {
                rightScrolls = true
                probeFrom = null
            }
        }

        previous = player
        if (history.lastOrNull() != player) {
            history.addLast(player!!)
            while (history.size > 8) history.removeFirst()
            sameCellFrames = 0
        } else sameCellFrames++

        if (advanced) sameCellFrames = 0

        val newRightmost = player!!.col > furthestCol
        val progressed = advanced || newRightmost
        if (advanced) furthestCol = player.col else if (newRightmost) furthestCol = player.col

        if (progressed) {
            if (lastResourceKind == ActionKind.ATTACK) dashBlockedUntil = SystemClock.elapsedRealtime() + 2_500L
            noProgressFrames = 0
            dashFailures = 0
            actionsWithoutProgress = 0
            attackUnavailable = false
            dashUnavailable = false
        } else noProgressFrames++

        lastResourceKind?.let { kind ->
            if (progressed || player != resourcePlayer) {
                lastResourceKind = null
                resourcePlayer = null
                resourceUnchangedFrames = 0
            } else {
                resourceUnchangedFrames++
                if (resourceUnchangedFrames >= 2) {
                    if (kind == ActionKind.ATTACK) attackUnavailable = true else if (kind == ActionKind.DASH) dashUnavailable = true
                    lastResourceKind = null
                    resourcePlayer = null
                    resourceUnchangedFrames = 0
                    if (attackUnavailable && dashUnavailable) {
                        if (de.robinthor.digiworldexplorer.automation.DwsExcursionRequest.running()) {
                            de.robinthor.digiworldexplorer.automation.DwsExcursionRequest.requestReturn("DWS resources unavailable")
                            reset()
                            return
                        }
                        AutomationState.enabled = false
                        AutomationState.overlayEnabled = false
                        service?.setOverlayEnabled(false)
                        if (service != null) main.post { ScreenCaptureService.stopForStuck(service) }
                        return
                    }
                }
            }
        }

        val looping = history.size >= 4 && history.takeLast(4).let { it[0] == it[2] && it[1] == it[3] }
        val stuck = AutomationState.enabled && (looping || actionsWithoutProgress >= 3 || sameCellFrames >= STUCK_FRAMES || noProgressFrames >= NO_PROGRESS_LIMIT)

        if (AutomationState.enabled && actionsWithoutProgress >= MAX_FAILED_ACTIONS) {
            if (de.robinthor.digiworldexplorer.automation.DwsExcursionRequest.running()) {
                de.robinthor.digiworldexplorer.automation.DwsExcursionRequest.requestReturn("DWS has no safe progress")
                reset()
                return
            }
            AutomationState.enabled = false
            AutomationState.overlayEnabled = false
            service?.setOverlayEnabled(false)
            if (service != null) main.post { ScreenCaptureService.stopForStuck(service) }
            return
        }

        val burst = if (!trackingConfirmed || rightScrolls == null || (AutomationState.dwsNavigationSettings.betterEnergyCollect && energyVisible)) 1
        else if (cells.any { (c, s) -> c != player && s.item > .06 && (!onlyEnergy || s.orange > .06) }) BURST_ITEM
        else if (AutomationState.dwsNavigationSettings.phoneSafeMovement) BURST_RIGHT_PHONE_SAFE else BURST_RIGHT_V4

        val dashCharges = safeDashCharges(hud.dash)
        val plan = MovementPlanner.plan(
            player!!, cells, history.toList(),
            dashAvailable = dashButton != null && dashCharges > 0 && dashFailures < 3 && !dashUnavailable && SystemClock.elapsedRealtime() >= dashBlockedUntil,
            preview = preview, forbiddenObstacles = forbiddenObstacles,
            dashCharges = if (dashUnavailable) 0 else dashCharges,
            stuck = stuck,
            claws = if (attackUnavailable) 0 else hud.claws ?: UNKNOWN_CLAW_FALLBACK,
            maxSteps = burst,
            settings = dwsSettings
        )

        val action = plan.firstOrNull()
        val actionLabel = when (action?.kind) {
            ActionKind.MOVE -> service?.getString(R.string.overlay_action_move)
            ActionKind.ATTACK -> service?.getString(R.string.overlay_action_attack)
            ActionKind.DASH -> service?.getString(R.string.overlay_action_dash)
            null -> service?.getString(R.string.overlay_action_stop)
        }
        val status = if (AutomationState.enabled) service?.getString(R.string.overlay_auto_action, actionLabel ?: "") + (if (plan.size > 1) " x${plan.size}" else "") else service?.getString(R.string.overlay_paused).orEmpty()
        service?.updateOverlay(bounds, player, items, obstacles, action?.target, status, AutomationState.overlayEnabled, hud, dashButton)

        if (!AutomationState.enabled || pending || action == null) return

        val needed = if (trackingConfirmed) 1 else 2
        if (candidate == player) stable++ else {
            candidate = player
            stable = 1
        }

        if (stable < needed || SystemClock.elapsedRealtime() - lastTap < nextTapDelay) return
        if (service == null) return

        val taps = mutableListOf<Pair<Float, Float>>()
        if (action.kind == ActionKind.DASH) {
            val button = dashButton ?: return
            taps += button
        } else {
            var pr = player.row
            var pc = player.col
            var shift = 0
            for (step in plan) {
                val tr = step.target.row
                val tc = step.target.col - shift
                if (tc < 0 || tc > 4) break
                taps += cellCenter(bounds, tr, tc)
                if (step.direction == Direction.RIGHT && rightScrolls == true) shift++ else {
                    pr = tr
                    pc = tc
                }
            }
            if (taps.isEmpty()) return
            expected = if (taps.size > 1) Cell(pr, pc) else action.target
            expectedAge = 0
        }

        pending = true
        stable = 0
        lastTap = SystemClock.elapsedRealtime()
        actionsWithoutProgress += taps.size

        when (action.kind) {
            ActionKind.MOVE -> {
                if (taps.size == 1 && action.direction == Direction.RIGHT && rightScrolls == null) {
                    probeFrom = player
                    expectedRight = action.target
                }
            }
            ActionKind.ATTACK -> {
                expected = null
                lastAttackTarget = action.target
                lastAttackPlayer = player
                unchangedAttackFrames = 0
                lastResourceKind = ActionKind.ATTACK
                resourcePlayer = player
                resourceUnchangedFrames = 0
            }
            ActionKind.DASH -> {
                expected = null
                expectedAge = 0
                sameCellFrames = 0
                dashFailures++
                lastResourceKind = ActionKind.DASH
                resourcePlayer = player
                resourceUnchangedFrames = 0
            }
        }

        val info = "frame=$sourceFrameId kind=${action.kind} player=$player target=${action.target} direction=${action.direction} reason=${action.reason}"
        lastScheduledActionFrame = sourceFrameId
        val epoch = burstEpoch
        main.post(de.robinthor.digiworldexplorer.capture.FrameGeometryRegistry.bind(SystemClock.elapsedRealtime()) {
            dispatchBurst(service, taps, 0, info, epoch)
        })
    }

    private fun dispatchBlindStageDismiss(service: DigiWorldAccessibilityService, bounds: GridBounds) {
        val gridWidth = (bounds.right - bounds.left).toFloat()
        val gridHeight = (bounds.bottom - bounds.top).toFloat()
        val radius = minOf(gridWidth * .08f, service.resources.displayMetrics.density * 38f)
        val baseX = bounds.left + gridWidth * .50f
        val baseY = bounds.bottom + gridHeight * .33f
        val taps = if (kotlin.random.Random.nextBoolean()) 2 else 1

        fun tap(index: Int) {
            val (x, y) = SafeTapRandomizer.point(baseX, baseY, radius, radius * .45f)
            service.dispatchValidatedTap(x, y) { ok ->
                if (ok && index + 1 < taps) main.postDelayed(de.robinthor.digiworldexplorer.capture.FrameGeometryRegistry.bind(SystemClock.elapsedRealtime()) { tap(index + 1) }, SafeTapRandomizer.delay(240L, 80L))
            }
        }
        main.post(de.robinthor.digiworldexplorer.capture.FrameGeometryRegistry.bind(SystemClock.elapsedRealtime()) { tap(0) })
    }

    private fun cellCenter(bounds: GridBounds, row: Int, col: Int): Pair<Float, Float> {
        val cw = (bounds.right - bounds.left) / 5f
        val ch = (bounds.bottom - bounds.top) / 5f
        return SafeTapRandomizer.point(bounds.left + (col + .5f) * cw, bounds.top + (row + .5f) * ch, cw * .08f, ch * .08f)
    }

    private fun dispatchBurst(service: DigiWorldAccessibilityService, taps: List<Pair<Float, Float>>, index: Int, info: String, epoch: Long) {
        if (epoch != burstEpoch || !AutomationState.enabled || dialogActive) {
            de.robinthor.digiworldexplorer.diagnostics.PersistentDiagnosticLog.record("DWS.INPUT_ABORTED", "$info step=${index + 1} calibration/dialog/automation changed")
            return
        }
        val (x, y) = taps[index]
        service.dispatchValidatedTap(x, y) { ok ->
            if (epoch != burstEpoch) return@dispatchValidatedTap
            if (!ok || index + 1 >= taps.size) {
                lastTap = SystemClock.elapsedRealtime()
                nextTapDelay = SafeTapRandomizer.delay(TAP_DELAY, 60L)
                pending = false
                if (!ok) {
                    expected = null
                    trackingConfirmed = false
                    probeFrom = null
                }
            } else main.postDelayed(de.robinthor.digiworldexplorer.capture.FrameGeometryRegistry.bind(SystemClock.elapsedRealtime()) {
                if (dialogActive) {
                    lastTap = SystemClock.elapsedRealtime()
                    nextTapDelay = SafeTapRandomizer.delay(TAP_DELAY, 60L)
                    pending = false
                } else dispatchBurst(service, taps, index + 1, info, epoch)
            }, SafeTapRandomizer.delay(
                if (AutomationState.dwsNavigationSettings.phoneSafeMovement) BURST_DELAY_PHONE_SAFE else BURST_DELAY_V4, 60L
            ))
        }
    }

    fun pauseForPurchaseScreen() {
        burstEpoch++
        dialogActive = true
        pending = false
        expected = null
        expectedAge = 0
        candidate = null
        stable = 0
        probeFrom = null
    }

    fun pauseForGridRecheck() {
        burstEpoch++
        dialogActive = true
        pending = false
        candidate = null
        stable = 0
    }

    fun reset() {
        burstEpoch++
        lastScheduledActionFrame = null
        candidate = null
        stable = 0
        pending = false
        nextTapDelay = TAP_DELAY
        history.clear()
        recentItems.clear()
        forbiddenObstacles.clear()
        lastAttackTarget = null
        lastAttackPlayer = null
        unchangedAttackFrames = 0
        previous = null
        expected = null
        sameCellFrames = 0
        furthestCol = -1
        trackingConfirmed = false
        lostFrames = 0
        lastSignature = emptyList()
        lastSettledSignature = emptyList()
        expectedAge = 0
        unsettledFrames = 0
        probeFrom = null
        expectedRight = null
        noProgressFrames = 0
        dashFailures = 0
        actionsWithoutProgress = 0
        lastResourceKind = null
        resourcePlayer = null
        resourceUnchangedFrames = 0
        attackUnavailable = false
        dashUnavailable = false
        dashBlockedUntil = 0L
        lastDwsGridSeen = 0L
        lastBlindStageTap = 0L
    }
}
