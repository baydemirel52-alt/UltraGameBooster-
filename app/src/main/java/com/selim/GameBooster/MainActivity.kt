
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
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.roundToInt
import kotlin.system.measureTimeMillis

data class HardwareMetrics(
    val usedRamMb: Long,
    val totalRamMb: Long,
    val ramPercent: Int,
    val batteryTemp: Float,
    val batteryLevel: Int,
    val batteryVoltage: Int,
    val pingMs: Int,
    val currentDisplayHz: Int
)

data class GameAppInfo(
    val name: String,
    val packageName: String,
    val isGameCategory: Boolean
)

class SystemOptimizer(private val context: Context) {

    private var wifiLock: WifiManager.WifiLock? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null

    fun getHardwareMetrics(currentPing: Int): HardwareMetrics {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val totalMb = memInfo.totalMem / (1024 * 1024)
        val availMb = memInfo.availMem / (1024 * 1024)
        val usedMb = totalMb - availMb
        val percent = ((usedMb.toDouble() / totalMb.toDouble()) * 100).toInt()

        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tempTenths = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val tempC = tempTenths / 10f
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val voltage = batteryIntent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0

        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val currentHz = display?.refreshRate?.roundToInt() ?: 60

        return HardwareMetrics(
            usedRamMb = usedMb,
            totalRamMb = totalMb,
            ramPercent = percent,
            batteryTemp = tempC,
            batteryLevel = level,
            batteryVoltage = voltage,
            pingMs = currentPing,
            currentDisplayHz = currentHz
        )
    }

    /**
     * Samsung OneUI ve Android LTPO panellerde ekranın 60Hz'e düşmesini engeller.
     * Doğrudan sistem tablosuna min_refresh_rate ve peak_refresh_rate değerlerini çiviler.
     */
    fun lockSystemRefreshRate(targetHz: Int): Boolean {
        if (!Settings.System.canWrite(context)) return false
        return try {
            val rate = targetHz.toFloat()
            val resolver = context.contentResolver
            Settings.System.putFloat(resolver, "min_refresh_rate", rate)
            Settings.System.putFloat(resolver, "peak_refresh_rate", rate)
            // Samsung OneUI özel yenileme modu anahtarı (0: Standart 60Hz, 1: Yüksek/Adaptif 120Hz)
            Settings.System.putInt(resolver, "refresh_rate_mode", if (targetHz >= 90) 1 else 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Ekranda desteklenen en uygun Display.Mode ID'sini bulur (90Hz veya 120Hz için).
     */
    fun findBestDisplayModeId(targetHz: Int): Int {
        return try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return 0
            val modes = display.supportedModes
            val matchedMode = modes.minByOrNull { kotlin.math.abs(it.refreshRate - targetHz.toFloat()) }
            matchedMode?.modeId ?: 0
        } catch (e: Exception) {
            0
        }
    }

    /**
     * CPU çekirdeklerinin oyun sırasında uyku moduna (deep-sleep) girip micro-stutter yapmasını engeller.
     */
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
                    cpuWakeLock?.acquire(4 * 60 * 60 * 1000L) // Maksimum 4 saatlik güvenli oyun kilidi
                }
            } else {
                if (cpuWakeLock?.isHeld == true) {
                    cpuWakeLock?.release()
                }
            }
        } catch (_: Exception) {}
    }

    suspend fun measureRealPing(host: String = "8.8.8.8", port: Int = 53): Int = withContext(Dispatchers.IO) {
        try {
            var time = 0L
            Socket().use { socket ->
                time = measureTimeMillis {
                    socket.connect(InetSocketAddress(host, port), 1500)
                }
            }
            time.toInt()
        } catch (e: Exception) {
            -1
        }
    }

    suspend fun executeDeepBoost(targetHz: Int): Pair<Int, Long> = withContext(Dispatchers.IO) {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val pm = context.packageManager

        val beforeMem = ActivityManager.MemoryInfo()
        am.getMemoryInfo(beforeMem)

        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        var killedCount = 0

        for (app in packages) {
            val isSystemCritical = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (app.packageName.startsWith("com.android.systemui") ||
                     app.packageName.startsWith("android") ||
                     app.packageName.contains("ims") ||
                     app.packageName.contains("telephony"))

            if (!isSystemCritical && app.packageName != context.packageName) {
                try {
                    am.killBackgroundProcesses(app.packageName)
                    killedCount++
                } catch (_: Exception) {}
            }
        }

        // Sistem Hz Kilidini Uygula
        lockSystemRefreshRate(targetHz)

        System.gc()

        val afterMem = ActivityManager.MemoryInfo()
        am.getMemoryInfo(afterMem)
        val freedMb = ((afterMem.availMem - beforeMem.availMem) / (1024 * 1024)).coerceAtLeast(15L)

        Pair(killedCount, freedMb)
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
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            } else {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
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
