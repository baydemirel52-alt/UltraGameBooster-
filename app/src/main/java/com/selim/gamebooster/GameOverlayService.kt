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
import kotlinx.coroutines.runBlocking
import kotlin.math.abs

class GameOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var optimizer: SystemOptimizer
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var hudRootView: View? = null
    private var hudLayoutParams: WindowManager.LayoutParams? = null
    private var isPanelExpanded = false
    private var targetHz = 120

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        optimizer = SystemOptimizer(this)

        val prefs = getSharedPreferences("game_engine_prefs", Context.MODE_PRIVATE)
        targetHz = prefs.getInt("target_hz", 120)

        optimizer.backupOriginalSystemState()
        startForegroundNotification()
        optimizer.setLowLatencyWifiLock(true)
        optimizer.setCpuSustainedWakeLock(true)
        optimizer.lockSystemRefreshRate(targetHz)

        createSafeFloatingHud()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_STOP_OVERLAY") {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "ultra_game_engine_hud"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Oyun Motoru Aktif",
                NotificationManager.IMPORTANCE_LOW
            )
            nm.createNotificationChannel(channel)
        }

        val stopIntent = Intent(this, GameOverlayService::class.java).apply {
            action = "ACTION_STOP_OVERLAY"
        }
        val stopPending = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Ultra Game Engine Aktif (${targetHz}Hz)")
            .setContentText("Kapatıldığında tüm sistem ayarları orijinale döner.")
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

        startForeground(1001, notification)
    }

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    private fun createSafeFloatingHud() {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val bestModeId = optimizer.findBestDisplayModeId(targetHz)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 140
            preferredRefreshRate = targetHz.toFloat()
            if (bestModeId != 0) {
                preferredDisplayModeId = bestModeId
            }
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
        hzSwitchBtn = createMiniButton("🔥 Hedef Hz: ${targetHz}Hz (Değiştir)", "#059669") {
            targetHz = if (targetHz == 120) 90 else 120
            getSharedPreferences("game_engine_prefs", Context.MODE_PRIVATE)
                .edit().putInt("target_hz", targetHz).apply()

            optimizer.lockSystemRefreshRate(targetHz)
            hudLayoutParams?.let { lp ->
                lp.preferredRefreshRate = targetHz.toFloat()
                val modeId = optimizer.findBestDisplayModeId(targetHz)
                if (modeId != 0) lp.preferredDisplayModeId = modeId
                try { windowManager.updateViewLayout(rootLayout, lp) } catch (_: Exception) {}
            }
            hzSwitchBtn?.text = "🔥 Hedef Hz: ${targetHz}Hz (Değiştir)"
            Toast.makeText(this@GameOverlayService, "${targetHz}Hz Ekran Kilidi Uygulandı!", Toast.LENGTH_SHORT).show()
        }

        val boostBtn = createMiniButton("🚀 Arka Planı Temizle", "#2563EB") {
            serviceScope.launch {
                val (killed, freed) = optimizer.executeDeepBoost(targetHz)
                Toast.makeText(
                    this@GameOverlayService,
                    "$killed paket uyarıldı (Gerçek Açılan RAM: $freed MB)",
                    Toast.LENGTH_SHORT
                ).show()
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
                val metrics = optimizer.getHardwareMetrics(ping)
                val realFps = optimizer.readRealSurfaceFlingerFps()
                val pingStr = if (ping > 0) "${ping}ms" else "--"

                // DÜRÜST GÖSTERGE: Shizuku varsa gerçek FPS, yoksa Ekran Hz yazar!
                val perfLabel = if (realFps != null) {
                    "⚡ $realFps Gerçek FPS (${metrics.currentDisplayHz}Hz)"
                } else {
                    "⚡ Ekran: ${metrics.currentDisplayHz} Hz"
                }

                val tempLabel = if (metrics.cpuTemp != null) {
                    "CPU:${metrics.cpuTemp}°C"
                } else {
                    "Pil:${metrics.batteryTemp}°C"
                }

                statusTextView.text = "$perfLabel | $tempLabel | $pingStr"
                val engineMode = if (metrics.shizukuActive) "Shizuku ADB Aktif ✅" else "Standart Mod (Shizuku Kapalı)"
                telemetryDetailText.text = "$engineMode | RAM: %${metrics.ramPercent}"
                delay(1200)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        // HUD kapanırken tüm sistem ayarlarını (Hz, DND, WakeLock, PowerMode) orijinale döndür
        runBlocking {
            optimizer.restoreOriginalSystemState()
        }
        hudRootView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        hudRootView = null
        Toast.makeText(this, "Oyun modu kapandı, tüm sistem ayarları normale döndü.", Toast.LENGTH_SHORT).show()
    }
}

