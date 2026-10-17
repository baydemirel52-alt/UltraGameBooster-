package com.selim.gamebooster

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

class GameOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var optimizer: SystemOptimizer
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var hudRootView: View? = null
    private var hudLayoutParams: WindowManager.LayoutParams? = null
    private var isPanelExpanded = false
    private var targetHz = 120

    companion object {
        const val ACTION_STOP_OVERLAY = "ACTION_STOP_OVERLAY"
        const val ACTION_UPDATE_CONFIG = "ACTION_UPDATE_CONFIG"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "ultra_game_engine_hud"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        optimizer = SystemOptimizer.getInstance(this)

        val prefs = getSharedPreferences(SystemOptimizer.PREFS_NAME, Context.MODE_PRIVATE)
        targetHz = prefs.getInt("target_hz", 120)

        startForegroundNotification()
        applyUserPreferencesAndLocks()

        if (Settings.canDrawOverlays(this)) {
            createSafeFloatingHud()
        } else {
            Toast.makeText(this, "Üstte gösterim izni bulunamadı, servis kapatılıyor.", Toast.LENGTH_SHORT).show()
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_OVERLAY -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_CONFIG -> {
                applyUserPreferencesAndLocks()
                updateOverlayFlagsFromPrefs()
            }
        }
        return START_STICKY
    }

    /**
     * Kullanıcının SharedPreferences'ta seçtiği kilitleri (Wi-Fi, DND, WakeLock, Hz)
     * zorlama yapmadan birebir uygular.
     */
    private fun applyUserPreferencesAndLocks() {
        val prefs = getSharedPreferences(SystemOptimizer.PREFS_NAME, Context.MODE_PRIVATE)
        targetHz = prefs.getInt("target_hz", 120)
        val wifiLowLatency = prefs.getBoolean("wifi_lock", true)
        val autoDnd = prefs.getBoolean("auto_dnd", true)
        val keepAwake = prefs.getBoolean("keep_awake", true)

        serviceScope.launch {
            optimizer.backupOriginalSystemState()
            optimizer.setLowLatencyWifiLock(wifiLowLatency)
            optimizer.setCpuSustainedWakeLock(keepAwake)
            if (autoDnd) {
                optimizer.setDoNotDisturb(true)
            }
            optimizer.lockSystemRefreshRate(targetHz)
            updateForegroundNotificationText()
        }
    }

    private fun updateOverlayFlagsFromPrefs() {
        val root = hudRootView ?: return
        val lp = hudLayoutParams ?: return
        val prefs = getSharedPreferences(SystemOptimizer.PREFS_NAME, Context.MODE_PRIVATE)
        val keepAwake = prefs.getBoolean("keep_awake", true)

        lp.flags = buildOverlayFlags(keepAwake)
        try {
            windowManager.updateViewLayout(root, lp)
        } catch (_: Exception) {}
    }

    private fun buildOverlayFlags(keepScreenOn: Boolean): Int {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        if (keepScreenOn) {
            // HUD penceresi oyun üzerinde çizildiği için FLAG_KEEP_SCREEN_ON oyun sırasında
            // ekranın kararmasını %100 gerçek olarak engeller.
            flags = flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        }
        return flags
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, GameOverlayService::class.java).apply {
            action = ACTION_STOP_OVERLAY
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Ultra Game Engine Aktif (${targetHz}Hz)")
                .setContentText("HUD çalışıyor • Kapatıldığında tüm ayarlar orijinale döner.")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .addAction(
                    Notification.Action.Builder(
                        android.R.drawable.ic_delete,
                        "Kapat & Normale Dön",
                        stopPending
                    ).build()
                )
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Ultra Game Engine Aktif (${targetHz}Hz)")
                .setContentText("HUD çalışıyor • Kapatıldığında tüm ayarlar orijinale döner.")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setOngoing(true)
                .build()
        }
    }

    private fun startForegroundNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Oyun Motoru Aktif",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun updateForegroundNotificationText() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {}
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun createSafeFloatingHud() {
        val prefs = getSharedPreferences(SystemOptimizer.PREFS_NAME, Context.MODE_PRIVATE)
        val keepAwake = prefs.getBoolean("keep_awake", true)

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            buildOverlayFlags(keepAwake),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 140
        }
        hudLayoutParams = params

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E6090D16"))
                cornerRadius = 30f
                setStroke(3, Color.parseColor("#00E676"))
            }
            setPadding(24, 14, 24, 14)
        }

        val statusTextView = TextView(this).apply {
            text = "⚡ Ölçülüyor..."
            setTextColor(Color.parseColor("#00E676"))
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
        }

        val expandedLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, 14, 0, 0)
        }

        val telemetryDetailText = TextView(this).apply {
            text = "Mod: Kontrol ediliyor..."
            setTextColor(Color.parseColor("#CBD5E1"))
            textSize = 10.5f
            setPadding(0, 0, 0, 10)
        }

        fun createMiniButton(label: String, bgColor: String, onClick: () -> Unit): Button {
            return Button(this).apply {
                text = label
                textSize = 11f
                isAllCaps = false
                setTextColor(Color.WHITE)
                background = GradientDrawable().apply {
                    setColor(Color.parseColor(bgColor))
                    cornerRadius = 18f
                }
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    80
                ).apply { topMargin = 8 }
                layoutParams = lp
                setOnClickListener { onClick() }
            }
        }

        var hzSwitchBtn: Button? = null
        hzSwitchBtn = createMiniButton("🔥 Hedef Hz: ${targetHz}Hz (60/90/120 Döngüsü)", "#059669") {
            targetHz = when (targetHz) {
                60 -> 90
                90 -> 120
                else -> 60
            }
            getSharedPreferences(SystemOptimizer.PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putInt("target_hz", targetHz).apply()

            hzSwitchBtn?.text = "🔥 Hedef Hz: ${targetHz}Hz (60/90/120 Döngüsü)"
            serviceScope.launch {
                val locked = optimizer.lockSystemRefreshRate(targetHz)
                updateForegroundNotificationText()
                val msg = if (locked) {
                    "${targetHz}Hz Global Ekran Kilidi Uygulandı!"
                } else {
                    "Hedef ${targetHz}Hz seçildi (Kilit için Shizuku veya Ayar İzni gerekli)"
                }
                Toast.makeText(this@GameOverlayService, msg, Toast.LENGTH_SHORT).show()
            }
        }

        val boostBtn = createMiniButton("🚀 Arka Planı Temizle", "#2563EB") {
            serviceScope.launch {
                val res = optimizer.executeDeepBoost(targetHz)
                val detail = if (res.shizukuDeepClean) {
                    "ADB Deep Clean + ${res.targetedAppCount} uygulama • Açılan RAM: ${res.freedRamMb} MB"
                } else {
                    "${res.targetedAppCount} kullanıcı uygulaması • Açılan RAM: ${res.freedRamMb} MB"
                }
                Toast.makeText(this@GameOverlayService, detail, Toast.LENGTH_SHORT).show()
            }
        }

        val closeBtn = createMiniButton("❌ Kapat & Ayarları Normale Döndür", "#DC2626") {
            stopSelf()
        }

        expandedLayout.addView(telemetryDetailText)
        expandedLayout.addView(hzSwitchBtn)
        expandedLayout.addView(boostBtn)
        expandedLayout.addView(closeBtn)

        rootLayout.addView(statusTextView)
        rootLayout.addView(expandedLayout)

        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        statusTextView.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    try {
                        windowManager.updateViewLayout(rootLayout, params)
                    } catch (_: Exception) {}
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val movedX = abs(event.rawX - initialTouchX)
                    val movedY = abs(event.rawY - initialTouchY)
                    if (movedX < 15 && movedY < 15) {
                        isPanelExpanded = !isPanelExpanded
                        expandedLayout.visibility = if (isPanelExpanded) View.VISIBLE else View.GONE
                    }
                    true
                }
                else -> false
            }
        }

        hudRootView = rootLayout
        windowManager.addView(rootLayout, params)

        serviceScope.launch {
            while (isActive) {
                val ping = optimizer.measureRealPing()
                // Tek bir Shizuku kabuk çağrısıyla hem gerçek FPS hem CPU sıcaklığı okunur
                val (realFps, cpuTemp) = optimizer.readShizukuTelemetry(includeFps = true)
                val metrics = optimizer.getHardwareMetrics(ping, preReadCpuTemp = cpuTemp)
                val pingStr = if (ping > 0) "${ping}ms" else "--"

                val perfLabel = if (realFps != null) {
                    "⚡ $realFps FPS (${metrics.currentDisplayHz}Hz)"
                } else {
                    "⚡ Ekran: ${metrics.currentDisplayHz} Hz"
                }

                val tempLabel = if (metrics.cpuTemp != null) {
                    "CPU:${metrics.cpuTemp}°C"
                } else {
                    "Pil:${metrics.batteryTemp}°C"
                }

                statusTextView.text = "$perfLabel | $tempLabel | $pingStr"
                val engineMode = if (metrics.shizukuActive) "Shizuku ADB Aktif ✅" else "Standart Mod"
                telemetryDetailText.text = "$engineMode | RAM: %${metrics.ramPercent} (${metrics.usedRamMb}MB)"
                delay(1500)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()

        hudRootView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
        }
        hudRootView = null

        // ANR yaratan runBlocking kaldırıldı!
        // Singleton optimizer kendi IO CoroutineScope'u üzerinde tüm sistem ve oyun ayarlarını güvenle sıfırlar.
        optimizer.restoreOriginalSystemStateAsync()
        Toast.makeText(applicationContext, "Oyun modu kapandı, tüm sistem ayarları normale döndürülüyor.", Toast.LENGTH_SHORT).show()
    }
}
