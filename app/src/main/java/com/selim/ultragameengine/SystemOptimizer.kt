package com.selim.ultragameengine

import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
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
import kotlinx.coroutines.delay
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
    // Ekran çözünürlüğü yüzdesi (100 = dokunma). Yalnızca Shizuku ile uygulanır.
    val resolutionPercent: Int = 100,
    // 0 = Otomatik (yenileme hızına dokunma), aksi halde kilitlenecek Hz değeri.
    val targetHz: Int = 0,
    val enableDnd: Boolean = true,
    val lowLatencyWifi: Boolean = true,
    val cleanRam: Boolean = true
)

data class ShellResult(val exitCode: Int, val output: String) {
    val ok: Boolean get() = exitCode == 0
}

object SystemOptimizer {
    private const val PREFS_NAME = "ultra_engine_prefs"

    // Standart değişiklikler (DND, Wi-Fi kilidi) yapıldı mı?
    private const val KEY_BOOST_ACTIVE = "boost_active"
    // Shizuku ile yapılan değişiklikler (çözünürlük, Hz, Game Mode) geri alınmayı bekliyor mu?
    private const val KEY_SHIZUKU_DIRTY = "shizuku_dirty"
    private const val KEY_ORIG_DND = "orig_dnd_filter"
    private const val KEY_ORIG_PEAK = "orig_peak_refresh"
    private const val KEY_ORIG_MIN = "orig_min_refresh"
    private const val KEY_GAME_MODE_PKG = "game_mode_pkg"
    private const val OP_GET_USAGE_STATS = "android:get_usage_stats"

    // Çözünürlük seçenekleri (yüzde). Slider bu listenin indeksini kullanır.
    val RESOLUTION_STEPS: List<Int> = listOf(100, 90, 85, 80, 75, 70)

    private val PKG_REGEX = Regex("^[A-Za-z0-9_.]+\$")
    private val NUMBER_REGEX = Regex("^[0-9]+(\\.[0-9]+)?\$")

    // Yalnızca belirli paket öneklerini oyun sayıyoruz ("tencent" gibi geniş eşleşmeler WeChat'i de yakalıyordu).
    private val GAME_PACKAGE_PREFIXES = listOf(
        "com.tencent.ig", "com.tencent.tmgp", "com.pubg", "com.activision.callofduty",
        "com.dts.freefire", "com.garena", "com.riotgames", "com.mihoyo", "com.hoyoverse",
        "com.mobile.legends", "com.supercell", "com.roblox", "com.netease", "com.proximabeta",
        "com.levelinfinite", "com.krafton", "com.epicgames", "com.ea.game", "com.gameloft",
        "com.king.", "com.kiloo", "com.mojang", "com.ubisoft", "com.innersloth"
    )

    private var wifiLock: WifiManager.WifiLock? = null
    private var cachedCpuZonePaths: List<String>? = null
    private var crashHandlerInstalled = false

    // Shizuku 13.1.5 içindeki private newProcess metoduna Reflection erişimi
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

    // ---------------------------------------------------------------------------------------
    // Shizuku
    // ---------------------------------------------------------------------------------------

    fun isShizukuReady(): Boolean {
        return try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    // Komutu çalıştırır; çıkış kodunu ve (stderr dahil) çıktıyı döndürür.
    fun runShizuku(cmd: String, mergeStderr: Boolean = true): ShellResult {
        if (!isShizukuReady()) return ShellResult(-1, "")
        return try {
            val method = shizukuNewProcessMethod ?: return ShellResult(-1, "Shizuku API erişilemedi")
            val full = if (mergeStderr) "{ $cmd; } 2>&1" else cmd
            val process = method.invoke(null, arrayOf("sh", "-c", full), null, null) as? Process
                ?: return ShellResult(-1, "")
            val out = process.inputStream.bufferedReader().use { it.readText() }
            val code = process.waitFor()
            ShellResult(code, out.trim())
        } catch (e: Exception) {
            ShellResult(-1, e.message ?: "")
        }
    }

    fun runShizukuCommand(cmd: String): String = runShizuku(cmd).output

    private fun looksLikeError(output: String): Boolean {
        val o = output.lowercase()
        return o.contains("exception") || o.contains("error") || o.contains("unknown command") ||
            o.contains("failed") || o.contains("not found") || o.contains("permission denied")
    }

    // Fiziksel (override edilmemiş) ekran boyutu ve yoğunluğu: "wm size" / "wm density" çıktısından.
    private fun readPhysicalDisplay(): Triple<Int, Int, Int>? {
        val sizeOut = runShizuku("wm size").output
        val densityOut = runShizuku("wm density").output
        val s = Regex("Physical size:\\s*(\\d+)x(\\d+)").find(sizeOut) ?: return null
        val d = Regex("Physical density:\\s*(\\d+)").find(densityOut) ?: return null
        return Triple(s.groupValues[1].toInt(), s.groupValues[2].toInt(), d.groupValues[1].toInt())
    }

    // ---------------------------------------------------------------------------------------
    // Güvenlik ağı: çökme olursa ayarları geri al
    // ---------------------------------------------------------------------------------------

    fun installCrashSafetyRestore(context: Context) {
        if (crashHandlerInstalled) return
        crashHandlerInstalled = true
        val appContext = context.applicationContext
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                restoreOriginalSystemSettings(appContext)
            } catch (_: Exception) {
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun isShizukuDirty(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHIZUKU_DIRTY, false)
    }

    // ---------------------------------------------------------------------------------------
    // Optimizasyonu uygula
    // ---------------------------------------------------------------------------------------

    suspend fun applyOptimizationProfile(context: Context, profile: GameProfile): String = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val report = mutableListOf<String>()
        val shizuku = isShizukuReady()
        val pkg = profile.packageName
        val validPkg = pkg.isNotEmpty() && PKG_REGEX.matches(pkg)

        // 0. Orijinal değerleri yalnızca "boost'lu değilken" kaydet; böylece ikinci kez basınca üzerine yazılmaz.
        val alreadyBoosted = prefs.getBoolean(KEY_BOOST_ACTIVE, false) || prefs.getBoolean(KEY_SHIZUKU_DIRTY, false)
        if (!alreadyBoosted) snapshotOriginals(appContext, prefs)
        prefs.edit().putBoolean(KEY_BOOST_ACTIVE, true).apply()

        // 1. Arka plan temizliği (ölçülen RAM farkıyla)
        if (profile.cleanRam) {
            val before = getAvailableRamMb(appContext)
            val attempted = cleanBackgroundApps(appContext, pkg)
            if (shizuku) runShizuku("am kill-all")
            delay(600)
            val after = getAvailableRamMb(appContext)
            val diff = after - before
            if (diff > 0) {
                report.add("✓ RAM: $attempted uygulamanın arka planı kapatıldı. Boş RAM $before MB → $after MB (+$diff MB).")
            } else {
                report.add("⚠ RAM: $attempted uygulama denendi ama belirgin kazanç yok (Android arka planı zaten yönetiyor). Boş RAM: $after MB.")
            }
        }

        // 2. Düşük gecikmeli Wi-Fi kilidi
        if (profile.lowLatencyWifi) {
            try {
                val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
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
                report.add("✓ Wi-Fi: düşük gecikme kilidi alındı (yalnızca Wi-Fi bağlıyken; etkisi cihaza göre değişir).")
            } catch (e: Exception) {
                report.add("✗ Wi-Fi kilidi alınamadı: ${e.message}")
            }
        }

        // 3. Rahatsız Etmeyin
        if (profile.enableDnd) {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.isNotificationPolicyAccessGranted) {
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                report.add("✓ Rahatsız Etmeyin: oyun sırasında bildirimler susturuldu.")
            } else {
                report.add("✗ Rahatsız Etmeyin: izin verilmemiş (Ayarlar → Rahatsız Etmeyin erişimi).")
            }
        }

        // 4. Shizuku gerektiren adımlar
        if (!shizuku) {
            if (profile.resolutionPercent < 100 || profile.targetHz > 0) {
                report.add("ℹ Çözünürlük ve Hz kilidi atlandı: Shizuku bağlı değil.")
            }
        } else {
            // 4a. Android Game Mode (oyun desteklerse etkili)
            if (validPkg && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val r = runShizuku("cmd game mode performance $pkg")
                if (r.ok && !looksLikeError(r.output)) {
                    prefs.edit()
                        .putString(KEY_GAME_MODE_PKG, pkg)
                        .putBoolean(KEY_SHIZUKU_DIRTY, true)
                        .apply()
                    report.add("✓ Android Game Mode: Performans (oyun desteklemiyorsa etkisi olmaz).")
                } else {
                    report.add("✗ Game Mode ayarlanamadı: ${r.output.take(80)}")
                }
            }

            // 4b. Çözünürlük (wm size + wm density)
            if (profile.resolutionPercent < 100) {
                val physical = readPhysicalDisplay()
                if (physical == null) {
                    report.add("✗ Çözünürlük: fiziksel ekran boyutu okunamadı, değiştirilmedi.")
                } else {
                    val (w, h, dpi) = physical
                    val scale = profile.resolutionPercent / 100f
                    val targetW = ((w * scale).roundToInt() / 8) * 8
                    val targetH = ((targetW.toFloat() * h / w).roundToInt() / 2) * 2
                    val targetDpi = (dpi.toFloat() * targetW / w).roundToInt()
                    prefs.edit().putBoolean(KEY_SHIZUKU_DIRTY, true).apply()
                    val sizeResult = runShizuku("wm size ${targetW}x${targetH}")
                    val densityResult = runShizuku("wm density $targetDpi")
                    if (sizeResult.ok && densityResult.ok) {
                        report.add("✓ Çözünürlük: ${targetW}x${targetH} (%${profile.resolutionPercent}) — GPU yükü düştü.")
                    } else {
                        report.add("✗ Çözünürlük ayarı hata verdi: ${(sizeResult.output + " " + densityResult.output).trim().take(80)}")
                    }
                }
            }

            // 4c. Yenileme hızı kilidi
            if (profile.targetHz > 0) {
                val hz = "${profile.targetHz}.0"
                prefs.edit().putBoolean(KEY_SHIZUKU_DIRTY, true).apply()
                val peak = runShizuku("settings put system peak_refresh_rate $hz")
                val min = runShizuku("settings put system min_refresh_rate $hz")
                if (peak.ok && min.ok) {
                    report.add("✓ Ekran: ${profile.targetHz} Hz istendi (bazı cihazlar bu ayarı yok sayar).")
                } else {
                    report.add("✗ Hz ayarı uygulanamadı: ${(peak.output + " " + min.output).trim().take(80)}")
                }
            }
        }

        // 5. Dürüst uyarılar: performansı gerçekten düşüren durumlar
        val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isPowerSaveMode) {
            report.add("⚠ Pil tasarrufu AÇIK: işlemciyi kısıtlar. Oyundan önce kapatman FPS'e en çok yardım eden adımdır.")
        }
        val thermal = getThermalStatus(appContext)
        if (thermal >= PowerManager.THERMAL_STATUS_MODERATE) {
            report.add("⚠ Cihaz ısınmış (${thermalLabelFor(thermal)}): sistem performansı zaten kısıyor. Soğumasını bekle.")
        }

        report.joinToString("\n")
    }

    private fun snapshotOriginals(context: Context, prefs: SharedPreferences) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val cr = context.contentResolver
        prefs.edit()
            .putInt(KEY_ORIG_DND, nm.currentInterruptionFilter)
            .putString(KEY_ORIG_PEAK, Settings.System.getString(cr, "peak_refresh_rate") ?: "")
            .putString(KEY_ORIG_MIN, Settings.System.getString(cr, "min_refresh_rate") ?: "")
            .apply()
    }

    // Kullanıcının kendi uygulamalarının arka plan işlemlerini sonlandırır (sistem, launcher, klavye ve oyun hariç).
    private fun cleanBackgroundApps(context: Context, gamePackage: String): Int {
        val pm = context.packageManager
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val protectedPackages = mutableSetOf(context.packageName, gamePackage, "moe.shizuku.privileged.api")
        try {
            val home = pm.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY
            )?.activityInfo?.packageName
            if (home != null) protectedPackages.add(home)
            val ime = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            if (!ime.isNullOrEmpty()) protectedPackages.add(ime.substringBefore('/'))
        } catch (_: Exception) {
        }

        var count = 0
        for (app in pm.getInstalledApplications(0)) {
            val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (isSystem || app.packageName in protectedPackages) continue
            try {
                am.killBackgroundProcesses(app.packageName)
                count++
            } catch (_: Exception) {
            }
        }
        return count
    }

    // ---------------------------------------------------------------------------------------
    // Geri alma
    // ---------------------------------------------------------------------------------------

    fun restoreOriginalSystemSettings(context: Context, force: Boolean = false): String {
        val appContext = context.applicationContext
        val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lines = mutableListOf<String>()

        try {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (_: Exception) {
        }

        // Standart ayarlar: DND'yi kullanıcının önceki haline döndür (körlemesine "hepsine izin ver" demeyiz).
        if (prefs.getBoolean(KEY_BOOST_ACTIVE, false)) {
            try {
                val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (nm.isNotificationPolicyAccessGranted) {
                    val orig = prefs.getInt(KEY_ORIG_DND, NotificationManager.INTERRUPTION_FILTER_ALL)
                    val filter = if (orig == NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
                        NotificationManager.INTERRUPTION_FILTER_ALL
                    } else {
                        orig
                    }
                    nm.setInterruptionFilter(filter)
                }
            } catch (_: Exception) {
            }
            prefs.edit().putBoolean(KEY_BOOST_ACTIVE, false).apply()
            lines.add("✓ Bildirim ve ağ ayarları eski haline döndü.")
        }

        // Shizuku ile yapılan değişiklikler
        val dirty = prefs.getBoolean(KEY_SHIZUKU_DIRTY, false)
        if (dirty || force) {
            if (isShizukuReady()) {
                runShizuku("wm size reset")
                runShizuku("wm density reset")
                if (dirty) {
                    restoreRefreshKey("peak_refresh_rate", prefs.getString(KEY_ORIG_PEAK, "") ?: "")
                    restoreRefreshKey("min_refresh_rate", prefs.getString(KEY_ORIG_MIN, "") ?: "")
                    val gamePkg = prefs.getString(KEY_GAME_MODE_PKG, "") ?: ""
                    if (gamePkg.isNotEmpty() && PKG_REGEX.matches(gamePkg) &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ) {
                        runShizuku("cmd game mode standard $gamePkg")
                    }
                }
                prefs.edit().putBoolean(KEY_SHIZUKU_DIRTY, false).apply()
                lines.add("✓ Ekran çözünürlüğü, Hz ve Game Mode eski haline döndü.")
            } else if (dirty) {
                lines.add("⚠ Ekran ayarları geri alınamadı: Shizuku'yu başlatıp ACİL SIFIRLA'ya tekrar bas.")
            }
        }

        if (lines.isEmpty()) lines.add("Geri alınacak bir değişiklik yoktu.")
        return lines.joinToString("\n")
    }

    private fun restoreRefreshKey(key: String, original: String) {
        if (original.isNotEmpty() && NUMBER_REGEX.matches(original)) {
            runShizuku("settings put system $key $original")
        } else {
            runShizuku("settings delete system $key")
        }
    }

    // ---------------------------------------------------------------------------------------
    // Cihaz durumu
    // ---------------------------------------------------------------------------------------

    fun getAvailableRamMb(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem / (1024L * 1024L)
    }

    fun getBatteryTemperature(context: Context): Float {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        return temp / 10f
    }

    // Android'in kendi termal durumu (0=Normal ... 3=Ciddi ... ). Desteklenmiyorsa -1.
    fun getThermalStatus(context: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.currentThermalStatus
        } catch (_: Exception) {
            -1
        }
    }

    fun thermalLabelFor(status: Int): String = when {
        status < 0 -> "—"
        status == 0 -> "Normal"
        status == 1 -> "Hafif"
        status == 2 -> "Orta"
        status == 3 -> "Ciddi"
        else -> "Kritik"
    }

    fun getThermalLabel(context: Context): String = thermalLabelFor(getThermalStatus(context))

    fun isPowerSaveMode(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isPowerSaveMode
    }

    // CPU çekirdek sensörlerinin en yükseği; okunamazsa -1 (Shizuku gerekir)
    fun getCpuTemperature(): Float {
        if (!isShizukuReady()) return -1f
        return try {
            val paths = cachedCpuZonePaths ?: resolveCpuThermalZonePaths()
            if (paths.isEmpty()) return -1f
            val maxC = runShizuku("cat " + paths.joinToString(" ") + " 2>/dev/null", mergeStderr = false)
                .output
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
        val zones = runShizuku(
            "for z in /sys/class/thermal/thermal_zone*; do echo \"\$z \$(cat \$z/type 2>/dev/null)\"; done",
            mergeStderr = false
        ).output
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

    // Cihazın gerçekten desteklediği yenileme hızları (60 Hz altı elenir).
    fun getSupportedRefreshRates(context: Context): List<Int> {
        return try {
            val dm = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
            display.supportedModes
                .map { it.refreshRate.roundToInt() }
                .filter { it >= 60 }
                .distinct()
                .sorted()
        } catch (_: Exception) {
            emptyList()
        }
    }

    // Kullanım erişimi: oyun kapanınca otomatik sıfırlama için gerekir.
    fun hasUsageAccess(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val uid = android.os.Process.myUid()
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(OP_GET_USAGE_STATS, uid, context.packageName)
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(OP_GET_USAGE_STATS, uid, context.packageName)
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    // ---------------------------------------------------------------------------------------
    // Uygulama / oyun listesi
    // ---------------------------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun launchablePackages(context: Context): Set<String> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0).map { it.activityInfo.packageName }.toSet()
    }

    fun getInstalledGames(context: Context): List<GameAppInfo> {
        val pm = context.packageManager
        val launchable = launchablePackages(context)
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { app ->
                if (app.packageName == context.packageName) return@filter false
                if (app.packageName !in launchable) return@filter false
                val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isGameCategory = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    app.category == ApplicationInfo.CATEGORY_GAME
                @Suppress("DEPRECATION")
                val isLegacyGame = (app.flags and ApplicationInfo.FLAG_IS_GAME) != 0
                val lower = app.packageName.lowercase()
                val matchesKnown = GAME_PACKAGE_PREFIXES.any { lower.startsWith(it) }
                (!isSystem && (isGameCategory || isLegacyGame)) || matchesKnown
            }
            .map { app ->
                GameAppInfo(
                    name = pm.getApplicationLabel(app).toString(),
                    packageName = app.packageName
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    // Oyun otomatik bulunamazsa kullanıcı listeden kendi seçsin.
    @Suppress("DEPRECATION")
    fun getAllLaunchableApps(context: Context): List<GameAppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { GameAppInfo(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .sortedBy { it.name.lowercase() }
    }

    // ---------------------------------------------------------------------------------------
    // Profiller
    // ---------------------------------------------------------------------------------------

    fun saveProfile(context: Context, profile: GameProfile) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("${profile.packageName}_res", profile.resolutionPercent)
            .putInt("${profile.packageName}_hz_v2", profile.targetHz)
            .putBoolean("${profile.packageName}_dnd", profile.enableDnd)
            .putBoolean("${profile.packageName}_wifi", profile.lowLatencyWifi)
            .putBoolean("${profile.packageName}_ram", profile.cleanRam)
            .apply()
    }

    fun loadProfile(context: Context, packageName: String): GameProfile {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val savedRes = prefs.getInt("${packageName}_res", 100)
        return GameProfile(
            packageName = packageName,
            resolutionPercent = if (savedRes in RESOLUTION_STEPS) savedRes else 100,
            targetHz = prefs.getInt("${packageName}_hz_v2", 0),
            enableDnd = prefs.getBoolean("${packageName}_dnd", true),
            lowLatencyWifi = prefs.getBoolean("${packageName}_wifi", true),
            cleanRam = prefs.getBoolean("${packageName}_ram", true)
        )
    }
}
