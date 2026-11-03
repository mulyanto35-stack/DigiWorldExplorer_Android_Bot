package de.robinthor.digiworldexplorer.accessibility

import de.robinthor.digiworldexplorer.R

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.*
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import de.robinthor.digiworldexplorer.automation.DirectorSnapshot
import de.robinthor.digiworldexplorer.automation.ScreenDirector
import de.robinthor.digiworldexplorer.capture.FrameGeometryRegistry
import de.robinthor.digiworldexplorer.capture.GeometrySnapshot
import de.robinthor.digiworldexplorer.capture.PixelRect
import de.robinthor.digiworldexplorer.detection.Cell
import de.robinthor.digiworldexplorer.detection.GridBounds
import de.robinthor.digiworldexplorer.detection.HudCounters
import de.robinthor.digiworldexplorer.diagnostics.PersistentDiagnosticLog
import de.robinthor.digiworldexplorer.input.SafeTapRandomizer
import de.robinthor.digiworldexplorer.strategy.AutomationState

class DigiWorldAccessibilityService : AccessibilityService() {
    @Volatile private var activePackage: String? = null
    private var overlay: GridOverlayView? = null
    private var quickControls: QuickControlOverlay? = null

    companion object {
        private const val NOTIFICATION_ID = 9901
        private const val CHANNEL_ID = "accessibility_service_channel"

        @Volatile var instance: DigiWorldAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        instance = this
        
        // Memastikan Service tidak gampang mati di Android 9+ dengan Notification Channel
        startKeepAliveForeground()

        PersistentDiagnosticLog.initialize(this)
        PersistentDiagnosticLog.record("SERVICE", "accessibility connected (Android 9 Optimized)")
        showOverlay()
        
        val prefs = getSharedPreferences("settings", MODE_PRIVATE)
        if (prefs.getBoolean("quick_overlay_enabled", false)) {
            setQuickControlsEnabled(true)
        }
        setOverlayEnabled(AutomationState.overlayEnabled)
    }

    private fun startKeepAliveForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DigiWorld Service KeepAlive",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Mencegah sistem menutup layanan aksesibilitas"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)

            val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("DigiWorld Explorer Active")
                .setContentText("Aksesibilitas aktif & berjalan lancar.")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setOngoing(true)
                .build()

            // Menjadikan status foreground agar OS Android 9 tidak menutup paksa service
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (e: Exception) {
                PersistentDiagnosticLog.record("SERVICE", "startForeground failed: ${e.message}")
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.packageName?.toString()?.let { activePackage = it }
    }

    override fun onInterrupt() = Unit

    fun isGameForeground(): Boolean {
        val application = windows.firstOrNull { 
            it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive 
        }
        val root = application?.root ?: rootInActiveWindow
        val current = root?.packageName?.toString() ?: activePackage
        @Suppress("DEPRECATION")
        root?.recycle()
        return current == "com.bandainamcoent.dgup_ww"
    }

    override fun onDestroy() {
        quickControls?.destroy()
        quickControls = null
        removeOverlay()
        if (instance === this) instance = null
        super.onDestroy()
    }

    fun gameWindowBounds(): PixelRect? {
        val window = windows.firstOrNull { 
            it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive 
        }
        val root = window?.root ?: rootInActiveWindow ?: return null
        try {
            if (root.packageName?.toString() != "com.bandainamcoent.dgup_ww") return null
            val rect = Rect()
            if (window != null) window.getBoundsInScreen(rect) else root.getBoundsInScreen(rect)
            return if (rect.width() > 0 && rect.height() > 0) PixelRect(rect.left, rect.top, rect.right, rect.bottom) else null
        } finally {
            @Suppress("DEPRECATION") 
            root.recycle() 
        }
    }

    fun controlOverlayBounds() = quickControls?.occupiedBounds() ?: emptyList()

    private fun validOrigin(): GeometrySnapshot? {
        val now = SystemClock.elapsedRealtime()
        val origin = FrameGeometryRegistry.forCaller(now) ?: return null
        if (!FrameGeometryRegistry.calibration.valid(origin, now) || !isGameForeground()) return null
        val display = de.robinthor.digiworldexplorer.capture.PhysicalDisplay.bounds(this, AutomationState.forceLegacyCaptureMetrics)
        if (display != origin.geometry.display.bounds || 
            gameWindowBounds()?.intersect(display) != origin.geometry.display.gameWindow || 
            resources.configuration.densityDpi != origin.geometry.display.densityDpi) return null
        return origin
    }

    fun dispatchValidatedTap(x: Float, y: Float, onComplete: (Boolean) -> Unit) {
        if (de.robinthor.digiworldexplorer.feed.StageFailedFrameAnalyzer.blocksOtherInput) {
            PersistentDiagnosticLog.record("GESTURE", "tap rejected: Growth Guide foreground recovery owns input local=$x,$y")
            onComplete(false)
            return
        }
        val origin = validOrigin()
        val point = origin?.geometry?.map(x.toDouble(), y.toDouble())
        val overlayBounds = controlOverlayBounds()
        if (origin == null || point == null || overlayBounds.any { it.contains(point.x, point.y) }) {
            val reason = when {
                origin == null -> "stale_missing_geometry_or_changed_display"
                point == null -> "outside_game"
                else -> "overlay_covered"
            }
            PersistentDiagnosticLog.record("GESTURE", "tap rejected reason=$reason local=$x,$y display=$point overlays=$overlayBounds generation=${origin?.generation}")
            onComplete(false)
            return
        }
        val sample = PersistentDiagnosticLog.sampleAction("tap", x.toInt(), y.toInt())
        val evidence = "local=$x,$y display=${point.x},${point.y} sample=$sample"
        PersistentDiagnosticLog.record("GESTURE", "tap $evidence generation=${origin.generation}")
        val path = Path().apply { moveTo(point.x.toFloat(), point.y.toFloat()) }
        dispatchMappedGesture(path, 80, origin, onComplete, evidence)
    }

    private fun dispatchMappedGesture(
        path: Path, 
        duration: Long, 
        origin: GeometrySnapshot, 
        onComplete: (Boolean) -> Unit, 
        evidence: String = "swipe"
    ) {
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, duration)).build()
        fun finish(ok: Boolean) = FrameGeometryRegistry.withSnapshot(origin) {
            onComplete(ok && FrameGeometryRegistry.calibration.valid(origin, SystemClock.elapsedRealtime()))
        }
        val accepted = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) {
                PersistentDiagnosticLog.record("GESTURE", "input completed $evidence generation=${origin.generation}; visual confirmation required")
                finish(true)
            }
            override fun onCancelled(d: GestureDescription?) {
                PersistentDiagnosticLog.record("GESTURE", "input cancelled $evidence generation=${origin.generation}")
                finish(false)
            }
        }, null)
        if (!accepted) finish(false)
    }

    fun dispatchValidatedSwipe(x1: Float, y1: Float, x2: Float, y2: Float, onComplete: (Boolean) -> Unit) {
        if (de.robinthor.digiworldexplorer.feed.StageFailedFrameAnalyzer.blocksOtherInput) {
            PersistentDiagnosticLog.record("GESTURE", "swipe rejected: Growth Guide foreground recovery owns input")
            onComplete(false)
            return
        }
        val origin = validOrigin()
        val a = origin?.geometry?.map(x1.toDouble(), y1.toDouble())
        val b = origin?.geometry?.map(x2.toDouble(), y2.toDouble())
        if (origin == null || a == null || b == null || controlOverlayBounds().any { it.intersectsSegment(a, b) }) {
            PersistentDiagnosticLog.record("GESTURE", "swipe rejected: invalid geometry/target or overlay obstruction")
            onComplete(false)
            return
        }
        PersistentDiagnosticLog.record("GESTURE", "swipe local=$x1,$y1->$x2,$y2 display=$a->$b generation=${origin.generation}")
        dispatchMappedGesture(Path().apply { moveTo(a.x.toFloat(), a.y.toFloat()); lineTo(b.x.toFloat(), b.y.toFloat()) }, 420, origin, onComplete)
    }

    fun dispatchBack(onComplete: (Boolean) -> Unit = {}) {
        if (de.robinthor.digiworldexplorer.feed.StageFailedFrameAnalyzer.blocksOtherInput) {
            PersistentDiagnosticLog.record("GESTURE", "back rejected: Growth Guide foreground recovery owns input")
            onComplete(false)
            return
        }
        val ok = performGlobalAction(GLOBAL_ACTION_BACK)
        PersistentDiagnosticLog.record("GESTURE", "back result=$ok")
        onComplete(ok)
    }

    fun dispatchSafeRandomizedTap(x: Float, y: Float, onComplete: (Boolean) -> Unit) {
        val geometry = validOrigin()?.geometry ?: run { onComplete(false); return }
        val radius = (resources.displayMetrics.density.coerceAtLeast(1f) / geometry.scaleToDisplay).toFloat()
        val (jx, jy) = SafeTapRandomizer.point(x, y, radius, radius)
        dispatchValidatedTap(jx, jy, onComplete)
    }

    fun dispatchNormalizedTap(xRatio: Float, yRatio: Float, onComplete: (Boolean) -> Unit) {
        val geometry = validOrigin()?.geometry
        if (geometry == null || !xRatio.isFinite() || !yRatio.isFinite() || xRatio !in 0f..1f || yRatio !in 0f..1f) {
            onComplete(false)
            return
        }
        dispatchSafeRandomizedTap(xRatio * (geometry.analysisSize.width - 1), yRatio * (geometry.analysisSize.height - 1), onComplete)
    }

    fun clearCalibrationOverlay() {
        overlay?.post {
            overlay?.bounds = null
            overlay?.visibility = View.GONE
            overlay?.invalidate()
        }
    }

    fun setOverlayEnabled(enabled: Boolean) {
        overlay?.post { overlay?.visibility = if (enabled) View.VISIBLE else View.GONE }
        quickControls?.refresh()
    }

    fun setQuickControlsEnabled(enabled: Boolean) {
        if (enabled) {
            if (quickControls == null) quickControls = QuickControlOverlay(this).also { it.show() }
        } else {
            quickControls?.destroy()
            quickControls = null
        }
    }

    fun setQuickStatusVisible(visible: Boolean) {
        getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("director_card_visible", visible).apply()
        quickControls?.setDirectorCardVisible(visible)
    }

    fun hideForCapture() {
        overlay?.post {
            overlay?.captureMode = true
            overlay?.invalidate()
        }
    }

    fun updateStatusKeepingGrid(status: String, visible: Boolean = true, sourceScreen: de.robinthor.digiworldexplorer.automation.ObservedScreen? = null) {
        ScreenDirector.noteAction(status, sourceScreen)
        val snapshot = ScreenDirector.snapshot()
        quickControls?.updateDirector(snapshot)
        overlay?.post {
            overlay?.apply {
                this.status = status
                captureMode = false
                visibility = if (visible) View.VISIBLE else View.GONE
                invalidate()
            }
        }
    }

    fun showStatusOnly(status: String, visible: Boolean = true, sourceScreen: de.robinthor.digiworldexplorer.automation.ObservedScreen? = null) {
        PersistentDiagnosticLog.record("STATUS", "source=${sourceScreen?.name ?: "NONE"} visible=$visible text=$status")
        if (status.contains("park", true) || status.contains("fail", true) || status.contains("timeout", true) || 
            status.contains("not confirmed", true) || status.contains("unreadable", true) || 
            status.contains("starting", true) || status.contains("opening", true)) {
            PersistentDiagnosticLog.requestScreenshot("status-$status")
        }
        ScreenDirector.noteAction(status, sourceScreen)
        val snapshot = ScreenDirector.snapshot()
        quickControls?.updateDirector(snapshot)
        overlay?.post {
            overlay?.apply {
                bounds = null
                player = null
                items = emptySet()
                obstacles = emptySet()
                target = null
                this.status = status
                captureMode = false
                visibility = View.GONE
                invalidate()
            }
        }
    }

    fun updateDirector(snapshot: DirectorSnapshot) {
        quickControls?.updateDirector(snapshot)
    }

    fun updateOverlay(
        bounds: GridBounds?, 
        player: Cell?, 
        items: Set<Cell>, 
        obstacles: Set<Cell>, 
        target: Cell?, 
        status: String, 
        visible: Boolean, 
        hud: HudCounters = HudCounters(), 
        dashButton: Pair<Float, Float>? = null
    ) {
        if (bounds != null) {
            ScreenDirector.noteAction(status, de.robinthor.digiworldexplorer.automation.ObservedScreen.WORLD_SEARCH)
            quickControls?.updateDirector(ScreenDirector.snapshot())
        }
        overlay?.post {
            overlay?.apply {
                this.bounds = bounds
                this.player = player
                this.items = items
                this.obstacles = obstacles
                this.target = target
                this.status = status
                this.hud = hud
                this.dashButton = dashButton
                captureMode = false
                visibility = if (visible) View.VISIBLE else View.GONE
                invalidate()
            }
        }
    }

    private fun showOverlay() {
        if (overlay != null) return
        android.util.Log.i("DigiWorldOverlay", "create canDrawOverlays=${Settings.canDrawOverlays(this)}")
        
        val layoutType = if (Settings.canDrawOverlays(this)) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        overlay = GridOverlayView().also {
            getSystemService(WindowManager::class.java).addView(it, lp)
        }
    }

    private fun removeOverlay() {
        overlay?.let {
            runCatching { getSystemService(WindowManager::class.java).removeView(it) }
        }
        overlay = null
    }

    inner class GridOverlayView : View(this@DigiWorldAccessibilityService) {
        var bounds: GridBounds? = null
        var player: Cell? = null
        var items: Set<Cell> = emptySet()
        var obstacles: Set<Cell> = emptySet()
        var target: Cell? = null
        var status = getString(R.string.overlay_bot_ready)
        var hud: HudCounters = HudCounters()
        var dashButton: Pair<Float, Float>? = null
        var captureMode = false
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

        init {
            background = ColorDrawable(Color.TRANSPARENT)
            // Akselerasi Hardware diatur efisien untuk Android 9
            setLayerType(LAYER_TYPE_HARDWARE, null)
        }

        override fun onDraw(c: Canvas) {
            super.onDraw(c)
            if (captureMode) return
            val b = bounds ?: return
            val geometry = FrameGeometryRegistry.current(SystemClock.elapsedRealtime())?.geometry ?: return
            val location = IntArray(2)
            getLocationOnScreen(location)

            val checkpoint = c.save()
            c.translate((geometry.displayOrigin.x - location[0]).toFloat(), (geometry.displayOrigin.y - location[1]).toFloat())
            c.scale(geometry.scaleToDisplay.toFloat(), geometry.scaleToDisplay.toFloat())
            c.clipRect(0, 0, geometry.analysisSize.width, geometry.analysisSize.height)

            val cw = (b.right - b.left) / 5f
            val ch = (b.bottom - b.top) / 5f
            val unit = minOf(cw, ch)

            p.pathEffect = null
            p.style = Paint.Style.STROKE
            p.color = Color.GREEN
            p.strokeWidth = unit * .025f

            for (i in 0..5) {
                c.drawLine(b.left + i * cw, b.top.toFloat(), b.left + i * cw, b.bottom.toFloat(), p)
                c.drawLine(b.left.toFloat(), b.top + i * ch, b.right.toFloat(), b.top + i * ch, p)
            }

            p.pathEffect = DashPathEffect(floatArrayOf(unit * .07f, unit * .05f), 0f)
            c.drawRect(b.right.toFloat(), b.top.toFloat(), b.right + cw, b.bottom.toFloat(), p)
            for (i in 1..4) {
                c.drawLine(b.right.toFloat(), b.top + i * ch, b.right + cw, b.top + i * ch, p)
            }
            p.pathEffect = null

            val inset = unit * .035f
            fun box(cell: Cell, color: Int, w: Float) {
                p.color = color
                p.strokeWidth = w
                val l = b.left + cell.col * cw + inset
                val t = b.top + cell.row * ch + inset
                c.drawRect(l, t, l + cw - 2 * inset, t + ch - 2 * inset, p)
            }

            obstacles.forEach { box(it, Color.RED, unit * .035f) }
            items.forEach { box(it, Color.MAGENTA, unit * .04f) }
            player?.let { box(it, Color.YELLOW, unit * .05f) }
            target?.let { box(it, Color.CYAN, unit * .05f) }

            val dashColor = when {
                hud.dash == 0 -> Color.RED
                hud.dash != null && hud.dash!! > 0 -> Color.GREEN
                else -> Color.GRAY
            }
            p.style = Paint.Style.STROKE
            p.pathEffect = null
            p.color = dashColor
            p.strokeWidth = unit * .035f

            hud.dashBox?.let { d ->
                c.drawRoundRect(d.left - unit * .04f, d.top - unit * .04f, d.right + unit * .04f, d.bottom + unit * .04f, unit * .05f, unit * .05f, p)
            }
            dashButton?.let { (x, y) ->
                c.drawCircle(x, y, unit * .25f, p)
            }

            val ts = ch * .24f
            p.textSize = ts
            val below = b.bottom + ts * 1.15f
            val ty = if (below <= geometry.analysisSize.height - ts * .3f) below else (b.top - ts * .45f).coerceAtLeast(ts)

            p.style = Paint.Style.STROKE
            p.strokeWidth = ts * .20f
            p.color = Color.WHITE
            c.drawText(status, b.left.toFloat(), ty, p)

            p.style = Paint.Style.FILL
            p.color = Color.rgb(12, 20, 36)
            c.drawText(status, b.left.toFloat(), ty, p)

            c.restoreToCount(checkpoint)
        }
    }
}
