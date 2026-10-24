package com.selim.ultragameengine

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    private var windowManager: WindowManager? = null
    private var hudLayout: LinearLayout? = null
    private var telemetryText: TextView? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    @Volatile private var gamePackage = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra("game_pkg")?.let { gamePackage = it }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundWithEmergencyAction()
        createDraggableTelemetryHud()
        startTelemetryLoop()
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

        val resetIntent = Intent(this, EmergencyRestoreReceiver::class.java).apply {
            action = "com.selim.ultragameengine.ACTION_EMERGENCY_RESET"
        }
        val resetPendingIntent = PendingIntent.getBroadcast(
            this,
            101,
            resetIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notification = builder
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
                val cpuC = withContext(Dispatchers.IO) { SystemOptimizer.getCpuTemperature() }
                val batC = SystemOptimizer.getBatteryTemperature(this@GameOverlayService)
                val freeRamMb = getAvailableRamMb()
                val pkg = gamePackage
                val hwFps = withContext(Dispatchers.IO) { SystemOptimizer.getHardwareRealFps(pkg) }

                val fpsLabel = if (hwFps > 0) "FPS: $hwFps" else "FPS: -- (${SystemOptimizer.fpsStatus})"
                val cpuLabel = if (cpuC > 0f) "CPU ${cpuC}°  |  " else ""
                val isHot = batC >= 43.0f || cpuC >= 85.0f
                val thermalWarn = if (isHot) " SICAK!" else ""

                telemetryText?.text = "$fpsLabel  |  ${pingMs}ms  |  ${cpuLabel}PİL ${batC}°$thermalWarn  |  RAM: ${freeRamMb}MB"

                if (isHot) {
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

    private fun getAvailableRamMb(): Long {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        return memInfo.availMem / 1048576L
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        if (hudLayout != null) {
            windowManager?.removeView(hudLayout)
            hudLayout = null
        }
    }
}
