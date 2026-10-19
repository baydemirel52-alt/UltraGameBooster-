package com.selim.ultragameengine

import android.annotation.SuppressLint
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.roundToInt

class GameOverlayService : Service(), Choreographer.FrameCallback {

    private var windowManager: WindowManager? = null
    private var hudLayout: LinearLayout? = null
    private var telemetryText: TextView? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Choreographer Ekran Hz Sayacı (Standart Mod İçin)
    private var lastFrameTimeNanos = 0L
    private var frameCount = 0
    private var currentScreenHz = 60

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundWithEmergencyAction()
        createDraggableTelemetryHud()
        Choreographer.getInstance().postFrameCallback(this)
        startTelemetryLoop()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (lastFrameTimeNanos == 0L) {
            lastFrameTimeNanos = frameTimeNanos
        }
        frameCount++
        val diff = frameTimeNanos - lastFrameTimeNanos
        if (diff >= 1_000_000_000L) {
            currentScreenHz = frameCount
            frameCount = 0
            lastFrameTimeNanos = frameTimeNanos
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun startForegroundWithEmergencyAction() {
        val channelId = "ultra_engine_hud_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Ultra Game Engine Telemetri",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        // Bildirimdeki "Ekranı Orijinale Döndür (Acil Reset)" Butonu
        val resetIntent = Intent(this, EmergencyRestoreReceiver::class.java).apply {
            action = "com.selim.ultragameengine.ACTION_EMERGENCY_RESET"
        }
        val resetPendingIntent = PendingIntent.getBroadcast(
            this, 101, resetIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
            .setContentTitle("Ultra Game Engine Aktif")
            .setContentText("Canlı telemetri çalışıyor. Çıkışta ekranı sıfırlayabilirsiniz.")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_revert,
                    "EKRANI SIFIRLA & KAPAT",
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            layoutFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 100
        }

        hudLayout = LinearLayout(this).apply {
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

        hudLayout?.addView(telemetryText)

        // Sürükle-Bırak Desteği (Oyun Tuşlarını Kapatmaması İçin İstediğin Köşeye Çekebilirsin)
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        hudLayout?.setOnTouchListener { _, event ->
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
                    windowManager?.updateViewLayout(hudLayout, params)
                    true
                }
                else -> false
            }
        }

        windowManager?.addView(hudLayout, params)
    }

    private fun startTelemetryLoop() {
        serviceScope.launch {
            while (isActive) {
                val pingMs = measureRealPing()
                val tempC = getBatteryTemp()
                val freeRamMb = getAvailableRamMb()
                val hwFps = withContext(Dispatchers.IO) { EngineManager.getHardwareRealFps() }

                val fpsLabel = if (hwFps > 0) {
                    "FPS: $hwFps"
                } else {
                    "Hz: $currentScreenHz"
                }

                val thermalWarn = if (tempC >= 43.0f) " ⚠️SICAK!" else ""
                telemetryText?.text = "$fpsLabel  |  ${pingMs}ms  |  ${tempC}°C$thermalWarn  |  RAM: ${freeRamMb}MB"

                // Sıcaklık 43 dereceyi geçerse rengi turuncu/kırmızıya çevirerek uyar
                if (tempC >= 43.0f) {
                    telemetryText?.setTextColor(Color.parseColor("#FF5252"))
                } else {
                    telemetryText?.setTextColor(Color.parseColor("#00E5FF"))
                }

                delay(1500)
            }
        }
    }

    private suspend fun measureRealPing(): Int = withContext(Dispatchers.IO) {
        return@withContext try {
            val start = System.currentTimeMillis()
            val socket = Socket()
            socket.connect(InetSocketAddress("8.8.8.8", 53), 1000)
            socket.close()
            (System.currentTimeMillis() - start).toInt()
        } catch (_: Exception) {
            999
        }
    }

    private fun getBatteryTemp(): Float {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        return (temp / 10f)
    }

    private fun getAvailableRamMb(): Long {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        return memInfo.availMem / (1024 * 1024)
    }

    override fun onDestroy() {
        super.onDestroy()
        Choreographer.getInstance().removeFrameCallback(this)
        serviceScope.cancel()
        if (hudLayout != null) {
            windowManager?.removeView(hudLayout)
            hudLayout = null
        }
    }
}
