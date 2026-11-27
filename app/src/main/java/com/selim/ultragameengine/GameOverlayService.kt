package com.selim.ultragameengine

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.DisplayMetrics
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.roundToInt

class GameOverlayService : Service() {

    companion object {
        const val EXTRA_GAME_PACKAGE = "extra_game_package"

        // Oyun ön planda görülmeden bu süre geçerse (açılamadı) ayarlar geri alınır.
        private const val GAME_NEVER_STARTED_TIMEOUT_MS = 40_000L

        // Oyun bu kadar ölçüm (x1.5 sn) üst üste ön planda değilse oyun kapandı sayılır.
        private const val AWAY_POLLS_BEFORE_RESET = 8

        private const val PREFS_NAME = "ultra_engine_prefs"
        private const val KEY_EXPANDED = "hud_expanded"

        private val COLOR_OK = Color.parseColor("#00E5FF")
        private val COLOR_HOT = Color.parseColor("#FF5252")
        private val COLOR_MOVE = Color.parseColor("#FFFFFF")
    }

    private class Telemetry(
        val pingMs: Int,
        val cpuC: Float,
        val batC: Float,
        val thermal: Int
    )

    private var windowManager: WindowManager? = null
    private var hudView: TextView? = null
    private var hudParams: WindowManager.LayoutParams? = null
    private var telemetryJob: Job? = null
    private var gamePackage: String = ""
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var expanded = false
    private var movingNow = false
    private var lastTelemetry: Telemetry? = null
    private var lastRenderKey = ""

    // Sürükleme sırasında pencere güncellemelerini kare başına en fazla bire indirmek için.
    private var moveScheduled = false
    private var targetX = 0
    private var targetY = 0

    private val prefs by lazy { getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundWithEmergencyAction()
        createHud()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (hudView == null) {
            // Üstte gösterme izni yoksa HUD eklenemedi; servisi sessizce kapat.
            stopSelf()
            return START_NOT_STICKY
        }
        gamePackage = intent?.getStringExtra(EXTRA_GAME_PACKAGE).orEmpty()
        telemetryJob?.cancel()
        telemetryJob = serviceScope.launch { telemetryLoop() }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Ekran döndüyse o yönün kayıtlı konumunu yükle ve ekran içinde tut.
        val params = hudParams ?: return
        loadPosition(params)
        hudView?.post { applyPosition(params.x, params.y) }
    }

    private fun startForegroundWithEmergencyAction() {
        val channelId = "ultra_engine_hud_channel"
        val channel = NotificationChannel(
            channelId,
            "Ultra Game Engine Telemetri",
            NotificationManager.IMPORTANCE_LOW
        )
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)

        val resetIntent = Intent(this, EmergencyRestoreReceiver::class.java).apply {
            action = EmergencyRestoreReceiver.ACTION_EMERGENCY_RESET
        }
        val resetPendingIntent = PendingIntent.getBroadcast(
            this,
            101,
            resetIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Ultra Game Engine Aktif")
            .setContentText("Canlı telemetri çalışıyor. Çıkışta ayarları sıfırlayabilirsiniz.")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_revert,
                    "AYARLARI SIFIRLA & KAPAT",
                    resetPendingIntent
                ).build()
            )
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notification)
        }
    }

    // ---------------------------------------------------------------------------------------
    // HUD: dokun = büyüt/küçült, basılı tut = sürükle, bırak = konumu sabitle
    // ---------------------------------------------------------------------------------------

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun pillBackground(accent: Int): GradientDrawable = GradientDrawable().apply {
        setColor(Color.parseColor("#E6080B12"))
        cornerRadius = dp(12).toFloat()
        setStroke(dp(1), accent)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun createHud() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        windowManager = wm

        // FLAG_HARDWARE_ACCELERATED: servisten eklenen pencereler bu bayrak olmadan yazılımla çizilir ve sürüklerken takılır.
        // FLAG_NOT_TOUCH_MODAL: HUD'un dışındaki dokunuşlar doğrudan oyuna gider.
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        loadPosition(params)
        expanded = prefs.getBoolean(KEY_EXPANDED, false)

        val view = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = pillBackground(COLOR_OK)
            alpha = 0.9f
            text = buildHudText(null)
        }

        var startX = 0
        var startY = 0
        var downRawX = 0f
        var downRawY = 0f
        var lastRawX = 0f
        var lastRawY = 0f

        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                toggleExpanded()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                movingNow = true
                startX = params.x
                startY = params.y
                downRawX = lastRawX
                downRawY = lastRawY
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                view.background = pillBackground(COLOR_MOVE)
                view.alpha = 1f
            }
        })

        view.setOnTouchListener { _, event ->
            lastRawX = event.rawX
            lastRawY = event.rawY
            detector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    if (movingNow) {
                        requestMove(
                            startX + (event.rawX - downRawX).roundToInt(),
                            startY + (event.rawY - downRawY).roundToInt()
                        )
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (movingNow) {
                        movingNow = false
                        savePosition()
                        view.alpha = 0.9f
                        renderHud(force = true)
                    }
                }
            }
            true
        }

        try {
            wm.addView(view, params)
            hudView = view
            hudParams = params
        } catch (_: Exception) {
            // İzin geri alınmış olabilir; hudView null kalır ve onStartCommand servisi kapatır.
            hudView = null
        }
    }

    private fun toggleExpanded() {
        expanded = !expanded
        prefs.edit().putBoolean(KEY_EXPANDED, expanded).apply()
        renderHud(force = true, reclamp = true)
    }

    private fun buildHudText(t: Telemetry?): String {
        if (t == null) return if (expanded) "ULTRA HUD\nölçülüyor..." else "● ..."
        val ping = if (t.pingMs >= 0) "${t.pingMs}ms" else "--"
        val cpu = if (t.cpuC > 0f) "%.1f°".format(t.cpuC) else "--"
        val bat = "%.1f°".format(t.batC)
        val thermal = SystemOptimizer.thermalLabelFor(t.thermal)
        return if (expanded) {
            "PING   " + ping.padStart(7) + "\n" +
                "CPU    " + cpu.padStart(7) + "\n" +
                "PİL    " + bat.padStart(7) + "\n" +
                "TERMAL " + thermal.padStart(7)
        } else {
            val hottest = if (t.cpuC > 0f) maxOf(t.cpuC, t.batC) else t.batC
            val hottestLabel = "%.0f°".format(hottest)
            "● " + ping.padStart(5) + " " + hottestLabel.padStart(4)
        }
    }

    // Metni yalnızca değiştiyse günceller; sabit genişlikli yazı sayesinde pencere her ölçümde yeniden boyutlanmaz.
    private fun renderHud(force: Boolean = false, reclamp: Boolean = false) {
        val view = hudView ?: return
        val t = lastTelemetry
        val hot = t != null && (t.batC >= 43f || t.cpuC >= 85f || t.thermal >= 2)
        val accent = if (hot) COLOR_HOT else COLOR_OK
        val plain = buildHudText(t)
        val key = "$expanded|$hot|$plain"
        if (!force && key == lastRenderKey) return
        lastRenderKey = key

        val text = SpannableString(plain)
        if (!expanded) {
            text.setSpan(ForegroundColorSpan(accent), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        view.text = text
        if (!movingNow) view.background = pillBackground(accent)
        if (reclamp) {
            view.post { hudParams?.let { applyPosition(it.x, it.y) } }
        }
    }

    // Parmak hareketi kare hızından hızlı gelir; her harekette değil, her karede bir kez konumu güncelle.
    private fun requestMove(x: Int, y: Int) {
        targetX = x
        targetY = y
        if (moveScheduled) return
        moveScheduled = true
        hudView?.postOnAnimation {
            moveScheduled = false
            applyPosition(targetX, targetY)
        }
    }

    private fun applyPosition(x: Int, y: Int) {
        val params = hudParams ?: return
        val view = hudView ?: return
        val (screenW, screenH) = screenSize()
        val maxX = (screenW - view.width).coerceAtLeast(0)
        val maxY = (screenH - view.height).coerceAtLeast(0)
        params.x = x.coerceIn(0, maxX)
        params.y = y.coerceIn(0, maxY)
        try {
            windowManager?.updateViewLayout(view, params)
        } catch (_: Exception) {
        }
    }

    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Int, Int> {
        val wm = windowManager ?: return Pair(Int.MAX_VALUE, Int.MAX_VALUE)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            Pair(bounds.width(), bounds.height())
        } else {
            val dm = DisplayMetrics()
            wm.defaultDisplay.getRealMetrics(dm)
            Pair(dm.widthPixels, dm.heightPixels)
        }
    }

    // Oyunlar çoğunlukla yatay oynanır; konumu dikey ve yatay için ayrı sakla.
    private fun orientationSuffix(): String =
        if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) "l" else "p"

    private fun loadPosition(params: WindowManager.LayoutParams) {
        val suffix = orientationSuffix()
        params.x = prefs.getInt("hud_x_$suffix", dp(24))
        params.y = prefs.getInt("hud_y_$suffix", dp(48))
    }

    private fun savePosition() {
        val params = hudParams ?: return
        val suffix = orientationSuffix()
        prefs.edit()
            .putInt("hud_x_$suffix", params.x)
            .putInt("hud_y_$suffix", params.y)
            .apply()
    }

    // ---------------------------------------------------------------------------------------
    // Telemetri + oyun kapanınca otomatik sıfırlama
    // ---------------------------------------------------------------------------------------

    // Tüm okumalar arka plan thread'inde yapılır; ana thread yalnızca metni günceller (sürükleme akıcı kalır).
    private suspend fun readTelemetry(): Telemetry = withContext(Dispatchers.IO) {
        val ping = measurePing()
        val cpu = SystemOptimizer.getCpuTemperature()
        val bat = SystemOptimizer.getBatteryTemperature(this@GameOverlayService)
        val thermal = SystemOptimizer.getThermalStatus(this@GameOverlayService)
        Telemetry(ping, cpu, bat, thermal)
    }

    private suspend fun telemetryLoop() {
        val startedAt = System.currentTimeMillis()
        var lastQuery = startedAt - 5_000L
        var currentForeground: String? = null
        var sawGame = false
        var awayPolls = 0

        while (serviceScope.isActive) {
            val now = System.currentTimeMillis()

            lastTelemetry = readTelemetry()
            renderHud()

            // --- Oyun kapanınca otomatik sıfırla (Kullanım erişimi izni gerekir) ---
            val canTrack = gamePackage.isNotEmpty() &&
                withContext(Dispatchers.IO) { SystemOptimizer.hasUsageAccess(this@GameOverlayService) }
            if (canTrack) {
                val foreground = withContext(Dispatchers.IO) {
                    queryLatestForegroundPackage(lastQuery - 1_000L, now)
                }
                lastQuery = now
                if (foreground != null) currentForeground = foreground

                if (currentForeground == gamePackage) {
                    sawGame = true
                    awayPolls = 0
                } else if (sawGame) {
                    awayPolls++
                }

                val gameNeverStarted = !sawGame && now - startedAt > GAME_NEVER_STARTED_TIMEOUT_MS
                if (gameNeverStarted || awayPolls >= AWAY_POLLS_BEFORE_RESET) {
                    withContext(Dispatchers.IO) {
                        SystemOptimizer.restoreOriginalSystemSettings(this@GameOverlayService)
                    }
                    Toast.makeText(
                        this@GameOverlayService,
                        "Oyun kapandı. Ayarlar eski haline döndü.",
                        Toast.LENGTH_LONG
                    ).show()
                    stopSelf()
                    return
                }
            }

            delay(1500)
        }
    }

    // [begin, end] aralığında ön plana gelen son uygulamanın paket adı.
    @Suppress("DEPRECATION")
    private fun queryLatestForegroundPackage(begin: Long, end: Long): String? {
        return try {
            val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val events = usm.queryEvents(begin, end) ?: return null
            val event = UsageEvents.Event()
            var latest: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                // MOVE_TO_FOREGROUND (=1), Android 10+ için ACTIVITY_RESUMED ile aynı değerdir.
                if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                    latest = event.packageName
                }
            }
            latest
        } catch (_: Exception) {
            null
        }
    }

    // Google DNS'e TCP bağlantı süresi; ulaşılamazsa -1. Yalnızca arka plan thread'inden çağrılır.
    private fun measurePing(): Int {
        return try {
            val start = System.currentTimeMillis()
            Socket().use { socket ->
                socket.connect(InetSocketAddress("8.8.8.8", 53), 1000)
            }
            (System.currentTimeMillis() - start).toInt()
        } catch (_: Exception) {
            -1
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        try {
            hudView?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
        }
        hudView = null
    }
}
