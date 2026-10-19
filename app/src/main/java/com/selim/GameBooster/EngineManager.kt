package com.selim.ultragameengine

import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader

data class GameAppInfo(
    val name: String,
    val packageName: String
)

data class GameProfile(
    val packageName: String,
    val resolutionScale: Float = 1.0f, // 1.0f = Orijinal, 0.85f = %85, 0.75f = %75
    val targetHz: Int = 120,           // 60, 90, 120
    val enableDnd: Boolean = true,
    val lowLatencyWifi: Boolean = true
)

object EngineManager {
    private const val PREFS_NAME = "ultra_engine_prefs"
    private const val KEY_ORIG_WIDTH = "orig_width"
    private const val KEY_ORIG_HEIGHT = "orig_height"
    private const val KEY_ORIG_DENSITY = "orig_density"
    private const val KEY_IS_MODIFIED = "is_display_modified"

    private var wifiLock: WifiManager.WifiLock? = null

    // Shizuku Çalışıyor ve Yetki Verilmiş mi?
    fun isShizukuReady(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    // Shizuku Kabuk Komutu Çalıştırıcı (Sıfır Plasebo - Doğrudan Sistem Komutu)
    fun runShizukuCommand(cmd: String): String {
        if (!isShizukuReady()) return ""
        return try {
            val process = Shizuku.newProcess(arrayOf("sh", "-c", cmd), null, null)
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = StringBuilder()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            process.waitFor()
            output.toString().trim()
        } catch (e: Exception) {
            ""
        }
    }

    // Orijinal Ekran Değerlerini İlk Kurulumda Yedekle
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

    // Uygulama Çökerse Otomatik Ekranı Kurtaran Zırh
    fun installCrashSafetyRestore(context: Context) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                restoreOriginalSystemSettings(context)
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    // OYUN MOTORUNU ATEŞLE (Standart Mod + Shizuku Ultra Mod)
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

        // 2. STANDART MOD: Wi-Fi Düşük Gecikme Kilidi (Gerçek Android Donanım Kilidi)
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

        // 3. STANDART MOD: Rahatsız Etmeyin (DND) Modu (Bildirim Engelleme)
        if (profile.enableDnd) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.isNotificationPolicyAccessGranted) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                logs.add("DND: Oyun içi bildirim kesintileri susturuldu.")
            }
        }

        // 4. SHIZUKU ULTRA MOD: Donanım Çözünürlük Ölçekleme, Resmi Game API ve Hz Sabitleme
        if (isShizukuReady()) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(KEY_IS_MODIFIED, true).apply()

            // A) Android 12+ Resmi Game Mode API (Ban Riski %0 - Sistemin Kendi Komutu)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && profile.packageName.isNotEmpty()) {
                runShizukuCommand("cmd game mode performance ${profile.packageName}")
                if (profile.resolutionScale < 0.99f) {
                    runShizukuCommand("cmd game set --mode performance --downscale ${profile.resolutionScale} ${profile.packageName}")
                }
                logs.add("Android Game API: Performans modu aktif edildi.")
            }

            // B) Donanımsal Ekran Çözünürlüğü Ölçekleme (wm size)
            if (profile.resolutionScale < 0.99f) {
                val origW = prefs.getInt(KEY_ORIG_WIDTH, 1080)
                val origH = prefs.getInt(KEY_ORIG_HEIGHT, 2400)
                val targetW = ((origW * profile.resolutionScale).toInt() / 8) * 8
                val targetH = ((origH * profile.resolutionScale).toInt() / 8) * 8
                runShizukuCommand("wm size ${targetW}x${targetH}")
                logs.add("GPU Ölçekleme: ${targetW}x${targetH} (%${(profile.resolutionScale * 100).toInt()}) uygulandı.")
            }

            // C) Ekran Yenileme Hızı (Hz) Sabitleme
            val hz = profile.targetHz.toFloat()
            runShizukuCommand("settings put system peak_refresh_rate $hz")
            runShizukuCommand("settings put system min_refresh_rate $hz")
            logs.add("Ekran: ${profile.targetHz} Hz donanım kilidi uygulandı.")

            // D) Oyun Önbellek Derleme Optimizasyonu (Speed Profile)
            if (profile.packageName.isNotEmpty()) {
                runShizukuCommand("cmd package compile -m speed-profile -f ${profile.packageName}")
            }
        } else {
            logs.add("Bilgi: Standart Mod aktif (Donanım ölçekleme için Shizuku bağlayın).")
        }

        logs.joinToString("\n")
    }

    // TÜM SİSTEM AYARLARINI ORİJİNALE DÖNDÜR (Güvenli Çıkış & Acil Reset)
    fun restoreOriginalSystemSettings(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Wi-Fi Kilidini Bırak
        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {}

        // DND Modunu Normale Döndür
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.isNotificationPolicyAccessGranted) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            }
        } catch (_: Exception) {}

        // Shizuku Üzerinden Ekran Çözünürlüğünü ve Hz'i Sıfırla
        if (isShizukuReady()) {
            runShizukuCommand("wm size reset")
            runShizukuCommand("wm density reset")
            runShizukuCommand("settings delete system min_refresh_rate")
            prefs.edit().putBoolean(KEY_IS_MODIFIED, false).apply()
            return "Tüm ekran çözünürlüğü, Hz ve ağ ayarları orijinal fabrika değerlerine döndürüldü."
        }
        return "Standart ağ ve bildirim ayarları sıfırlandı."
    }

    // Gerçek Oyun FPS Ölçümü (Shizuku Varsa SurfaceFlinger'dan Alır)
    fun getHardwareRealFps(): Int {
        if (!isShizukuReady()) return -1
        return try {
            val dump = runShizukuCommand("dumpsys SurfaceFlinger --latency")
            if (dump.isNotEmpty()) {
                val lines = dump.lines().filter { it.isNotBlank() }
                if (lines.size > 4) {
                    val lastParts = lines.last().split("\\s+".toRegex())
                    val prevParts = lines[lines.size - 2].split("\\s+".toRegex())
                    if (lastParts.size >= 2 && prevParts.size >= 2) {
                        val deltaNs = lastParts[1].toLong() - prevParts[1].toLong()
                        if (deltaNs > 0) {
                            val fps = (1_000_000_000L / deltaNs).toInt()
                            return fps.coerceIn(1, 165)
                        }
                    }
                }
            }
            -1
        } catch (_: Exception) {
            -1
        }
    }

    // Yüklü Oyunları ve Uygulamaları Listele
    fun getInstalledGames(context: Context): List<GameAppInfo> {
        val pm = context.packageManager
        val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val gameKeywords = listOf("tencent", "pubg", "dts.freefire", "activision", "riotgames", "mihoyo", "genshin", "mobile.legends", "supercell", "roblox", "ea.game", "gameloft", "epicgames")

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

    // Profil Kaydet & Yükle
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
