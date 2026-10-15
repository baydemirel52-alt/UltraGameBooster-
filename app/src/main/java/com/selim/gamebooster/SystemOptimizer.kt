package com.selim.gamebooster

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.view.Display
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.roundToInt
import kotlin.system.measureTimeMillis

data class HardwareMetrics(
    val usedRamMb: Long,
    val totalRamMb: Long,
    val ramPercent: Int,
    val batteryTemp: Float,
    val cpuTemp: Float?,
    val batteryLevel: Int,
    val pingMs: Int,
    val currentDisplayHz: Int,
    val shizukuActive: Boolean
)

data class GameAppInfo(
    val name: String,
    val packageName: String,
    val isGameCategory: Boolean
)

class SystemOptimizer(private val context: Context) {

    private val prefs = context.getSharedPreferences("game_engine_prefs", Context.MODE_PRIVATE)
    private var wifiLock: WifiManager.WifiLock? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null

    // SurfaceFlinger gerçek kare hesabı için değişkenler
    private var lastSurfaceFlipCount: Long = -1L
    private var lastSurfaceFlipTimeMs: Long = 0L

    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            false
        }
    }

    fun hasShizukuPermission(): Boolean {
        if (!isShizukuAvailable()) return false
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * Shizuku üzerinden gerçek ADB Shell komutu çalıştırır (Root gerekmez).
     */
    fun runShizukuShell(command: String): String? {
        if (!hasShizukuPermission()) return null
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as Process
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            output.trim()
        } catch (e: Throwable) {
            null
        }
    }

    /**
     * Shizuku aktifse SurfaceFlinger donanım katmanından gerçek çizilen FPS'i okur.
     * Aktif değilse null döner (Arayüz dürüstçe sadece Ekran Hz gösterir).
     */
    suspend fun readRealSurfaceFlingerFps(): Int? = withContext(Dispatchers.IO) {
        if (!hasShizukuPermission()) return@withContext null
        try {
            val out = runShizukuShell("service call SurfaceFlinger 1013") ?: return@withContext null
            val regex = Regex("""Parcel\(\s*[0-9a-fA-F]{8}\s+([0-9a-fA-F]{8})""")
            val match = regex.find(out) ?: return@withContext null
            val currentFlips = match.groupValues[1].toLong(16)
            val nowMs = System.currentTimeMillis()

            var fps: Int? = null
            if (lastSurfaceFlipCount > 0L && nowMs > lastSurfaceFlipTimeMs) {
                val deltaFlips = currentFlips - lastSurfaceFlipCount
                val deltaMs = nowMs - lastSurfaceFlipTimeMs
                if (deltaFlips >= 0 && deltaMs > 200) {
                    fps = ((deltaFlips * 1000L) / deltaMs).toInt().coerceIn(1, 144)
                }
            }
            lastSurfaceFlipCount = currentFlips
            lastSurfaceFlipTimeMs = nowMs
            fps
        } catch (e: Throwable) {
            null
        }
    }

    suspend fun getHardwareMetrics(currentPing: Int): HardwareMetrics = withContext(Dispatchers.IO) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val totalMb = memInfo.totalMem / (1024 * 1024)
        val availMb = memInfo.availMem / (1024 * 1024)
        val usedMb = totalMb - availMb
        val percent = ((usedMb.toDouble() / totalMb.toDouble()) * 100).toInt()

        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tempTenths = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val batTempC = tempTenths / 10f
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0

        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val currentHz = display?.refreshRate?.roundToInt() ?: 60

        // Shizuku aktifse gerçek işlemci/termal sensör sıcaklığını oku
        var realCpuTemp: Float? = null
        if (hasShizukuPermission()) {
            val rawThermal = runShizukuShell("cat /sys/class/thermal/thermal_zone0/temp")
            val milliC = rawThermal?.toIntOrNull()
            if (milliC != null && milliC > 1000) {
                realCpuTemp = (milliC / 100) / 10f
            }
        }

        HardwareMetrics(
            usedRamMb = usedMb,
            totalRamMb = totalMb,
            ramPercent = percent,
            batteryTemp = batTempC,
            cpuTemp = realCpuTemp,
            batteryLevel = level,
            pingMs = currentPing,
            currentDisplayHz = currentHz,
            shizukuActive = hasShizukuPermission()
        )
    }

    /**
     * Oyun başlamadan önce kullanıcının orijinal sistem ayarlarını yedekler.
     */
    fun backupOriginalSystemState() {
        if (prefs.getBoolean("state_backed_up", false)) return
        try {
            val resolver = context.contentResolver
            val origMinHz = Settings.System.getFloat(resolver, "min_refresh_rate", 60f)
            val origPeakHz = Settings.System.getFloat(resolver, "peak_refresh_rate", 120f)
            val origMode = Settings.System.getInt(resolver, "refresh_rate_mode", 1)

            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val origDnd = nm.currentInterruptionFilter

            prefs.edit()
                .putFloat("orig_min_hz", origMinHz)
                .putFloat("orig_peak_hz", origPeakHz)
                .putInt("orig_refresh_mode", origMode)
                .putInt("orig_dnd_filter", origDnd)
                .putBoolean("state_backed_up", true)
                .apply()
        } catch (_: Exception) {}
    }

    /**
     * Oyun bittiğinde veya HUD kapatıldığında TÜM ayarları (Hz, DND, WakeLock, Power Mode)
     * telefonun orijinal haline geri döndürür. Asla takılı bırakmaz.
     */
    suspend fun restoreOriginalSystemState() = withContext(Dispatchers.IO) {
        setLowLatencyWifiLock(false)
        setCpuSustainedWakeLock(false)

        if (hasShizukuPermission()) {
            runShizukuShell("cmd power set-fixed-performance-mode-enabled false")
        }

        if (!prefs.getBoolean("state_backed_up", false)) return@withContext

        val origMinHz = prefs.getFloat("orig_min_hz", 60f)
        val origPeakHz = prefs.getFloat("orig_peak_hz", 120f)
        val origMode = prefs.getInt("orig_refresh_mode", 1)
        val origDnd = prefs.getInt("orig_dnd_filter", NotificationManager.INTERRUPTION_FILTER_ALL)

        if (hasShizukuPermission()) {
            runShizukuShell("settings put system min_refresh_rate $origMinHz")
            runShizukuShell("settings put system peak_refresh_rate $origPeakHz")
            runShizukuShell("settings put system refresh_rate_mode $origMode")
        } else if (Settings.System.canWrite(context)) {
            try {
                val resolver = context.contentResolver
                Settings.System.putFloat(resolver, "min_refresh_rate", origMinHz)
                Settings.System.putFloat(resolver, "peak_refresh_rate", origPeakHz)
                Settings.System.putInt(resolver, "refresh_rate_mode", origMode)
            } catch (_: Exception) {}
        }

        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.isNotificationPolicyAccessGranted) {
                nm.setInterruptionFilter(origDnd)
            }
        } catch (_: Exception) {}

        prefs.edit().putBoolean("state_backed_up", false).apply()
    }

    fun lockSystemRefreshRate(targetHz: Int): Boolean {
        backupOriginalSystemState()
        val rate = targetHz.toFloat()
        val mode = if (targetHz >= 90) 1 else 0

        if (hasShizukuPermission()) {
            runShizukuShell("settings put system min_refresh_rate $rate")
            runShizukuShell("settings put system peak_refresh_rate $rate")
            runShizukuShell("settings put system refresh_rate_mode $mode")
            return true
        }

        if (!Settings.System.canWrite(context)) return false
        return try {
            val resolver = context.contentResolver
            Settings.System.putFloat(resolver, "min_refresh_rate", rate)
            Settings.System.putFloat(resolver, "peak_refresh_rate", rate)
            Settings.System.putInt(resolver, "refresh_rate_mode", mode)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Shizuku ile seçilen oyuna gerçek Android Game Mode ve GPU Downscale (Çözünürlük Ölçekleme) uygular.
     * @param downscaleRatio "disable" (100% Orijinal), "0.85" (%85 Keskin/Akıcı), "0.75" (%75 Maksimum FPS)
     */
    suspend fun applyShizukuGameOptimization(packageName: String, downscaleRatio: String): Boolean = withContext(Dispatchers.IO) {
        if (!hasShizukuPermission()) return@withContext false
        try {
            // 1. Oyunu Android'in resmi Performans moduna geçir (Mod 2 = Performance)
            runShizukuShell("cmd game mode performance $packageName")
            // 2. GPU yükünü azaltıp 90/120 FPS'i sabitlemek için donanım ölçeklemesi uygula
            runShizukuShell("cmd game set --mode 2 --downscale $downscaleRatio $packageName")
            // 3. İşlemciyi sabit performans moduna kilitle
            runShizukuShell("cmd power set-fixed-performance-mode-enabled true")
            // 4. Samsung GOS otomatik sıcaklık kısmasını devre dışı bırak
            runShizukuShell("settings put secure game_auto_temperature_control 0")
            true
        } catch (e: Throwable) {
            false
        }
    }

    fun findBestDisplayModeId(targetHz: Int): Int {
        return try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return 0
            val matchedMode = display.supportedModes.minByOrNull { kotlin.math.abs(it.refreshRate - targetHz.toFloat()) }
            matchedMode?.modeId ?: 0
        } catch (e: Exception) {
            0
        }
    }

    fun setCpuSustainedWakeLock(enable: Boolean) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (enable) {
                if (cpuWakeLock == null) {
                    cpuWakeLock = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "UltraGameBooster:SustainedCpuLock"
                    )
                    cpuWakeLock?.setReferenceCounted(false)
                }
                if (cpuWakeLock?.isHeld == false) {
                    cpuWakeLock?.acquire(2 * 60 * 60 * 1000L)
                }
            } else {
                if (cpuWakeLock?.isHeld == true) {
                    cpuWakeLock?.release()
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Google DNS yerine PUBG Mobile Avrupa (AWS Frankfurt) sunucu merkezine gerçek TCP ping atar.
     */
    suspend fun measureRealPing(): Int = withContext(Dispatchers.IO) {
        try {
            var time = 0L
            Socket().use { socket ->
                time = measureTimeMillis {
                    socket.connect(InetSocketAddress("ec2.eu-central-1.amazonaws.com", 443), 1500)
                }
            }
            time.toInt()
        } catch (e: Exception) {
            -1
        }
    }

    /**
     * Sahte +15MB olmadan tamamen gerçek RAM farkını ölçer. Shizuku varsa ADB kill-all çalıştırır.
     */
    suspend fun executeDeepBoost(targetHz: Int): Pair<Int, Long> = withContext(Dispatchers.IO) {
        backupOriginalSystemState()
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val pm = context.packageManager

        val beforeMem = ActivityManager.MemoryInfo()
        am.getMemoryInfo(beforeMem)

        var killedCount = 0

        if (hasShizukuPermission()) {
            runShizukuShell("cmd activity kill-all")
            runShizukuShell("cmd power set-fixed-performance-mode-enabled true")
        }

        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        for (app in packages) {
            val isSystemCritical = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (app.packageName.startsWith("com.android.systemui") ||
                     app.packageName.startsWith("android") ||
                     app.packageName.contains("ims") ||
                     app.packageName.contains("telephony") ||
                     app.packageName.contains("shizuku"))

            if (!isSystemCritical && app.packageName != context.packageName) {
                try {
                    am.killBackgroundProcesses(app.packageName)
                    killedCount++
                } catch (_: Exception) {}
            }
        }

        lockSystemRefreshRate(targetHz)

        val afterMem = ActivityManager.MemoryInfo()
        am.getMemoryInfo(afterMem)
        // Sahte 15 MB kaldırıldı: Gerçekte ne kadar açıldıysa (0 ise 0) onu döner
        val realFreedMb = ((afterMem.availMem - beforeMem.availMem) / (1024 * 1024)).coerceAtLeast(0L)

        Pair(killedCount, realFreedMb)
    }

    fun setLowLatencyWifiLock(enable: Boolean) {
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (enable) {
                if (wifiLock == null) {
                    val lockType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    } else {
                        @Suppress("DEPRECATION")
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF
                    }
                    wifiLock = wifiManager.createWifiLock(lockType, "UltraGameBooster:LowLatencyLock")
                    wifiLock?.setReferenceCounted(false)
                }
                if (wifiLock?.isHeld == false) {
                    wifiLock?.acquire()
                }
            } else {
                if (wifiLock?.isHeld == true) {
                    wifiLock?.release()
                }
            }
        } catch (_: Exception) {}
    }

    fun setDoNotDisturb(enable: Boolean): Boolean {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) return false
        return try {
            if (enable) {
                backupOriginalSystemState()
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            } else {
                val origDnd = prefs.getInt("orig_dnd_filter", NotificationManager.INTERRUPTION_FILTER_ALL)
                nm.setInterruptionFilter(origDnd)
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    suspend fun getInstalledUserApps(): List<GameAppInfo> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(intent, 0)
        val list = mutableListOf<GameAppInfo>()
        val seen = mutableSetOf<String>()

        for (info in resolveInfos) {
            val pkg = info.activityInfo.packageName
            if (pkg == context.packageName || seen.contains(pkg)) continue
            seen.add(pkg)

            val appInfo = info.activityInfo.applicationInfo
            val label = appInfo.loadLabel(pm).toString()
            val isGame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appInfo.category == ApplicationInfo.CATEGORY_GAME
            } else {
                false
            } || pkg.contains("pubg", true) || pkg.contains("tencent", true) ||
                 pkg.contains("game", true) || pkg.contains("krafton", true)

            list.add(GameAppInfo(name = label, packageName = pkg, isGameCategory = isGame))
        }
        list.sortedWith(compareByDescending<GameAppInfo> { it.isGameCategory }.thenBy { it.name })
    }
}
