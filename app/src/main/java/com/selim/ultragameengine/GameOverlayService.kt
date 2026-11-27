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
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
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
    }

    private var windowManager: WindowManager? = null
    private var hudLayout: LinearLayout? = null
    private var telemetryText: TextView? = null
    private var gamePackage: String = ""
    private var telemetryJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundWithEmergencyAction()
        createDraggableTelemetryHud()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (hudLayout == null) {
            // Üstte gösterme izni yoksa HUD eklenemedi; servisi sessizce kapat.
            stopSelf()
            return START_NOT_STICKY
        }
        gamePackage = intent?.getStringExtra(EXTRA_GAME_PACKAGE).orEmpty()
        telemetryJob?.cancel()
        telemetryJob = serviceScope.launch { telemetryLoop() }
        return START_NOT_STICKY
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

    @SuppressLint("ClickableViewAccessibility")
    private fun createDraggableTelemetryHud() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val layoutFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            layoutFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 100
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(28, 14, 28, 14)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC0A0E17"))
                cornerRadius = 24f
                setStroke(2, Color.parseColor("#00E5FF"))
            }
        }

        telemetryText = TextView(this).apply {
            text = "ULTRA HUD | Başlatılıyor..."
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 12f
        }
        layout.addView(telemetryText)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        layout.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).roundToInt()
                    params.y = initialY + (event.rawY - initialTouchY).roundToInt()
                    try {
                        windowManager?.updateViewLayout(layout, params)
                    } catch (_: Exception) {
                    }
                    true
                }
                else -> false
            }
        }

        try {
            windowManager?.addView(layout, params)
            hudLayout = layout
        } catch (_: Exception) {
            // İzin geri alınmış olabilir; hudLayout null kalır ve onStartCommand servisi kapatır.
            hudLayout = null
        }
    }

    private suspend fun telemetryLoop() {
        val startedAt = System.currentTimeMillis()
        var lastQuery = startedAt - 5_000L
        var currentForeground: String? = null
        var sawGame = false
        var awayPolls = 0

        while (serviceScope.isActive) {
            val now = System.currentTimeMillis()

            // --- Telemetri ---
            val pingMs = measureRealPing()
            val cpuC = withContext(Dispatchers.IO) { SystemOptimizer.getCpuTemperature() }
            val batC = SystemOptimizer.getBatteryTemperature(this@GameOverlayService)
            val thermal = SystemOptimizer.getThermalStatus(this@GameOverlayService)

            val pingLabel = if (pingMs >= 0) "${pingMs}ms" else "ping yok"
            val cpuLabel = if (cpuC > 0f) "CPU ${cpuC}° | " else ""
            val thermalLabel = if (thermal >= 0) " | ${SystemOptimizer.thermalLabelFor(thermal)}" else ""
            val isHot = batC >= 43.0f || cpuC >= 85.0f || thermal >= 2

            telemetryText?.text = "$pingLabel | ${cpuLabel}PİL ${batC}°$thermalLabel${if (isHot) " SICAK!" else ""}"
            telemetryText?.setTextColor(Color.parseColor(if (isHot) "#FF5252" else "#00E5FF"))

            // --- Oyun kapanınca otomatik sıfırla (Kullanım erişimi izni gerekir) ---
            if (gamePackage.isNotEmpty() && SystemOptimizer.hasUsageAccess(this@GameOverlayService)) {
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

    // Google DNS'e TCP bağlantı süresi; ulaşılamazsa -1.
    private suspend fun measureRealPing(): Int = withContext(Dispatchers.IO) {
        try {
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
            hudLayout?.let { windowManager?.removeView(it) }
        } catch (_: Exception) {
        }
        hudLayout = null
    }
}
