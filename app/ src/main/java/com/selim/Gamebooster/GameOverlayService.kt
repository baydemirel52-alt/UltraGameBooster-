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
import android.view.Choreographer
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
import java.util.Locale
import kotlin.math.abs

class GameOverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var optimizer: SystemOptimizer
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var hudRootView: View? = null
    private var hudLayoutParams: WindowManager.LayoutParams? = null
    private var crosshairView: View? = null
    private var isPanelExpanded = false
    private var isCrosshairActive = false

    private var targetHz = 120

    // Hassas FPS, Frame-Time (ms) ve Stutter/Drop Ölçüm Motoru
    private var frameCount = 0
    private var lastSecondNanos = System.nanoTime()
    private var lastFrameNanos = System.nanoTime()
    private var currentFps = 120
    private var avgFrameTimeMs = 8.3f
    private var stutterDropCount = 0

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frameCount++

            // İki kare arası gerçek gecikme (Frame Pacing)
            val frameDeltaMs = (frameTimeNanos - lastFrameNanos) / 1_000_000f
            lastFrameNanos = frameTimeNanos

            // Eğer tek bir kare 22ms'den uzun sürdüyse (90/120Hz'de mikro-takılma/drop)
            if (frameDeltaMs > 22.0f && frameDeltaMs < 500f) {
                stutterDropCount++
            }

            val elapsedNanos = frameTimeNanos - lastSecondNanos
            if (elapsedNanos >= 1_000_000_000L) {
                currentFps = ((frameCount * 1_000_000_000L) / elapsedNanos).toInt().coerceIn(1, 144)
                avgFrameTimeMs = if (currentFps > 0) 1000f / currentFps else 16.6f
                frameCount = 0
                lastSecondNanos = frameTimeNanos
            }
            if (hudRootView != null) {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        optimizer = SystemOptimizer(this)

        val prefs = getSharedPreferences("game_engine_prefs", Context.MODE_PRIVATE)
        targetHz = prefs.getInt("target_hz", 120)

        startForegroundNotification()
        optimizer.setLowLatencyWifiLock(true)
        optimizer.setCpuSustainedWakeLock(true)
        optimizer.lockSystemRefreshRate(targetHz)

        createSafeFloatingHud()
        Choreographer.getInstance().postFrameCallback(frameCallback)
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
            .setContentTitle("Ultra Game Engine (${targetHz}Hz Kilidi Aktif)")
            .setContentText("SurfaceFlinger yüksek kare hızı ve düşük gecikme devrede.")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_delete,
                    "HUD Kapat",
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

        // KRİTİK: FLAG_HARDWARE_ACCELERATED + preferredRefreshRate ile 90/120Hz kilitlenir!
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
            y = 160
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
            text = "⚡ 120 FPS (8.3ms) | 36°C | -- ms"
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
            text = "Hedef: ${targetHz}Hz | Drop: 0 | RAM: --"
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
        hzSwitchBtn = createMiniButton("🔥 Ekran Kilidi: ${targetHz}Hz (Değiştir)", "#059669") {
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
            hzSwitchBtn?.text = "🔥 Ekran Kilidi: ${targetHz}Hz (Değiştir)"
            stutterDropCount = 0
            Toast.makeText(this@GameOverlayService, "${targetHz} FPS / Hz Donanım Kilidi Aktif!", Toast.LENGTH_SHORT).show()
        }

        val boostBtn = createMiniButton("🚀 Drop Önleyici RAM & Hz Boost", "#2563EB") {
            serviceScope.launch {
                val (_, freed) = optimizer.executeDeepBoost(targetHz)
                stutterDropCount = 0
                Toast.makeText(this@GameOverlayService, "+$freed MB RAM Açıldı & ${targetHz}Hz Yenilendi!", Toast.LENGTH_SHORT).show()
            }
        }

        val crosshairBtn = createMiniButton("🎯 Nişangah (Crosshair)", "#7C3AED") {
            toggleCrosshair()
        }

        val closeBtn = createMiniButton("❌ HUD Kapat", "#DC2626") {
            stopSelf()
        }

        expandedLayout.addView(telemetryDetailText)
        expandedLayout.addView(hzSwitchBtn)
        expandedLayout.addView(boostBtn)
        expandedLayout.addView(crosshairBtn)
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
                val pingStr = if (ping > 0) "${ping}ms" else "--"
                val ftStr = String.format(Locale.US, "%.1fms", avgFrameTimeMs)

                statusTextView.text = "⚡ $currentFps FPS ($ftStr) | ${metrics.batteryTemp}°C | $pingStr"
                telemetryDetailText.text = "Kilit: ${targetHz}Hz | Anlık Drop: $stutterDropCount | RAM: %${metrics.ramPercent}"
                delay(1200)
            }
        }
    }

    private fun toggleCrosshair() {
        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        if (isCrosshairActive) {
            crosshairView?.let {
                try { windowManager.removeView(it) } catch (_: Exception) {}
            }
            crosshairView = null
            isCrosshairActive = false
        } else {
            val chParams = WindowManager.LayoutParams(
                60,
                60,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
                preferredRefreshRate = targetHz.toFloat()
            }

            val chView = TextView(this).apply {
                text = "✚"
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#FF0055"))
            }

            crosshairView = chView
            windowManager.addView(chView, chParams)
            isCrosshairActive = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        optimizer.setLowLatencyWifiLock(false)
        optimizer.setCpuSustainedWakeLock(false)
        hudRootView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        crosshairView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
        }
        hudRootView = null
        crosshairView = null
    }
}

