package com.selim.ultragameengine

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.lang.reflect.Method
import kotlin.math.roundToInt

data class GameAppInfo(
    val name: String,
    val packageName: String
)

data class GameProfile(
    val packageName: String,
    val resolutionScale: Float = 1.0f,
    val targetHz: Int = 120,
    val enableDnd: Boolean = true,
    val lowLatencyWifi: Boolean = true
)

object SystemOptimizer {
    private const val PREFS_NAME = "ultra_engine_prefs"
    private const val KEY_ORIG_WIDTH = "orig_width"
    private const val KEY_ORIG_HEIGHT = "orig_height"
    private const val KEY_ORIG_DENSITY = "orig_density"
    private const val KEY_IS_MODIFIED = "is_display_modified"

    private var wifiLock: WifiManager.WifiLock? = null
    private var cachedCpuZonePaths: List<String>? = null

    // Shizuku 13.1.5 içindeki private newProcess metoduna güvenli Reflection erişimi
    private val shizukuNewProcessMethod: Method? by lazy {
        try {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply {
                isAccessible = true
            }
        } catch (e: Exception) {
            null
        }
    }

    fun isShizukuReady(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    fun runShizukuCommand(cmd: String): String {
        if (!isShizukuReady()) return ""
        return try {
            val method = shizukuNewProcessMethod ?: return ""
            val process = method.invoke(null, arrayOf("sh", "-c", cmd), null, null) as? Process ?: return ""
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
            output.trim()
        } catch (e: Exception) {
            ""
        }
    }

    fun backupOriginalDisplay(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getInt(KEY_ORIG_WIDTH, 0) == 0) {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            prefs.edit()
                .putInt(KEY_ORIG_WIDTH, metrics.widthPixels)
                .putInt(KEY_ORIG_HEIGHT, metrics.heightPixels)
                .putInt(KEY_ORIG_DENSITY, metrics.densityDpi)
                .apply()
        }
    }

    fun installCrashSafetyRestore(context: Context) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                restoreOriginalSystemSettings(context)
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    suspend fun applyOptimizationProfile(context: Context, profile: GameProfile): String = withContext(Dispatchers.IO) {
        backupOriginalDisplay(context)
        val logs = mutableListOf<String>()

        // 1. STANDART MOD: Arka Plan Süreçlerini Temizle
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runningApps = am.runningAppProcesses ?: emptyList()
        var freedCount = 0
        for (proc in runningApps) {
            if (proc.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE &&
                !proc.processName.contains(context.packageName) &&
                !proc.processName.contains("shizuku")
            ) {
                am.killBackgroundProcesses(proc.processName)
                freedCount++
            }
        }
        logs.add("RAM: $freedCount arka plan işlemi temizlendi.")

        // 2. STANDART MOD: Düşük Gecikmeli Wi-Fi Donanım Kilidi
        if (profile.lowLatencyWifi) {
            try {
                val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
                if (wifiLock == null) {
                    wifiLock = wifiManager.createWifiLock(lockMode, "UltraGameEngine:LatencyLock")
                }
                if (wifiLock?.isHeld == false) {
                    wifiLock?.acquire()
                }
                logs.add("Ağ: Düşük gecikme (Low-Latency) Wi-Fi kilidi aktif.")
            } catch (_: Exception) {}
        }

        // 3. STANDART MOD: Rahatsız Etmeyin (DND) Modu
        if (profile.enableDnd) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.isNotificationPolicyAccessGranted) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                logs.add("DND: Oyun içi bildirim kesintileri susturuldu.")
            }
        }

        // 4. SHIZUKU ULTRA MOD: Resmi Android Game API, Çözünürlük Ölçekleme ve Hz Kilidi
        if (isShizukuReady()) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_IS_MODIFIED, true).apply()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && profile.packageName.isNotEmpty()) {
                runShizukuCommand("cmd game mode performance ${profile.packageName}")
                if (profile.resolutionScale < 0.99f) {
                    runShizukuCommand("cmd game set --mode performance --downscale ${profile.resolutionScale} ${profile.packageName}")
                }
                logs.add("Android Game API: Performans modu aktif edildi.")
            }

            if (profile.resolutionScale < 0.99f) {
                val origW = prefs.getInt(KEY_ORIG_WIDTH, 1080)
                val origH = prefs.getInt(KEY_ORIG_HEIGHT, 2400)
                val scaledW = (origW / (1.0f / profile.resolutionScale)).roundToInt()
                val targetW = (scaledW / 8) * 8
                // Yükseklik, orijinal en-boy oranı bozulmayacak şekilde genişlikten türetilir
                val targetH = ((targetW.toFloat() * origH / origW).roundToInt() / 2) * 2
                runShizukuCommand("wm size ${targetW}x${targetH}")
                // Yoğunluğu (DPI) da aynı oranda düşür; yoksa arayüz ekrana göre büyük görünür
                val origDensity = prefs.getInt(KEY_ORIG_DENSITY, 0)
                if (origDensity > 0 && origW > 0) {
                    val targetDensity = (origDensity.toFloat() * targetW / origW).roundToInt()
                    runShizukuCommand("wm density $targetDensity")
                }
                val percent = (profile.resolutionScale * 100f).roundToInt()
                logs.add("GPU Ölçekleme: ${targetW}x${targetH} (%$percent) uygulandı.")
            }

            val hz = profile.targetHz.toFloat()
            runShizukuCommand("settings put system peak_refresh_rate $hz")
            runShizukuCommand("settings put system min_refresh_rate $hz")
            logs.add("Ekran: ${profile.targetHz} Hz donanım kilidi uygulandı.")

            if (profile.packageName.isNotEmpty()) {
                runShizukuCommand("cmd package compile -m speed-profile -f ${profile.packageName}")
            }
        } else {
            logs.add("Bilgi: Standart Mod aktif (Donanım ölçekleme için Shizuku bağlayın).")
        }

        logs.joinToString("\n")
    }

    fun restoreOriginalSystemSettings(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {}

        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.isNotificationPolicyAccessGranted) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            }
        } catch (_: Exception) {}

        if (isShizukuReady()) {
            runShizukuCommand("wm size reset")
            runShizukuCommand("wm density reset")
            runShizukuCommand("settings delete system min_refresh_rate")
            prefs.edit().putBoolean(KEY_IS_MODIFIED, false).apply()
            return "Tüm ekran çözünürlüğü, Hz ve ağ ayarları orijinal fabrika değerlerine döndürüldü."
        }
        return "Standart ağ ve bildirim ayarları sıfırlandı."
    }

    // --- Sıcaklık: pil ve CPU ayrı okunur ---
    fun getBatteryTemperature(context: Context): Float {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        return temp / 10f
    }

    // CPU çekirdek sensörlerinin en yükseği; okunamazsa -1 (Shizuku gerekir)
    fun getCpuTemperature(): Float {
        if (!isShizukuReady()) return -1f
        return try {
            val paths = cachedCpuZonePaths ?: resolveCpuThermalZonePaths()
            if (paths.isEmpty()) return -1f
            val maxC = runShizukuCommand("cat " + paths.joinToString(" ") + " 2>/dev/null")
                .lines()
                .mapNotNull { it.trim().toFloatOrNull() }
                .map { raw -> if (raw > 1000f) raw / 1000f else if (raw > 100f) raw / 10f else raw }
                .filter { it in 20f..120f }
                .maxOrNull() ?: return -1f
            (maxC * 10f).roundToInt() / 10f
        } catch (_: Exception) {
            -1f
        }
    }

    private fun resolveCpuThermalZonePaths(): List<String> {
        val zones = runShizukuCommand("for z in /sys/class/thermal/thermal_zone*; do echo \"\$z \$(cat \$z/type 2>/dev/null)\"; done")
            .lines()
            .mapNotNull { line ->
                val parts = line.trim().split(" ", limit = 2)
                if (parts.size == 2) parts[0] to parts[1].lowercase() else null
            }
        var cpuZones = zones.filter { (_, type) ->
            type.contains("cpu") || type.contains("soc") || type.contains("big") ||
                type.contains("little") || type == "mid"
        }
        if (cpuZones.isEmpty()) cpuZones = zones.filter { (_, type) -> type.contains("tsens") }.take(8)
        val paths = cpuZones.map { "${it.first}/temp" }
        cachedCpuZonePaths = paths
        return paths
    }

    fun getInstalledGames(context: Context): List<GameAppInfo> {
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val gameKeywords = listOf(
            "tencent", "pubg", "dts.freefire", "activision", "riotgames",
            "mihoyo", "genshin", "mobile.legends", "supercell", "roblox",
            "ea.game", "gameloft", "epicgames"
        )

        return packages.filter { app ->
            val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val isGameCategory = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.category == ApplicationInfo.CATEGORY_GAME
            } else false
            @Suppress("DEPRECATION")
            val isLegacyGame = (app.flags and ApplicationInfo.FLAG_IS_GAME) != 0
            val matchesKeyword = gameKeywords.any { app.packageName.lowercase().contains(it) }

            (!isSystem && (isGameCategory || isLegacyGame || matchesKeyword)) || matchesKeyword
        }.map { app ->
            GameAppInfo(
                name = pm.getApplicationLabel(app).toString(),
                packageName = app.packageName
            )
        }.sortedBy { it.name }
    }

    fun saveProfile(context: Context, profile: GameProfile) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putFloat("${profile.packageName}_scale", profile.resolutionScale)
            .putInt("${profile.packageName}_hz", profile.targetHz)
            .putBoolean("${profile.packageName}_dnd", profile.enableDnd)
            .putBoolean("${profile.packageName}_wifi", profile.lowLatencyWifi)
            .apply()
    }

    fun loadProfile(context: Context, packageName: String): GameProfile {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return GameProfile(
            packageName = packageName,
            resolutionScale = prefs.getFloat("${packageName}_scale", 1.0f),
            targetHz = prefs.getInt("${packageName}_hz", 120),
            enableDnd = prefs.getBoolean("${packageName}_dnd", true),
            lowLatencyWifi = prefs.getBoolean("${packageName}_wifi", true)
        )
    }
}
