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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.lang.reflect.Method
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
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

data class BoostResult(
    val targetedAppCount: Int,
    val freedRamMb: Long,
    val shizukuDeepClean: Boolean,
    val hzLocked: Boolean
)

class SystemOptimizer private constructor(context: Context) {

    private val appContext: Context = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateMutex = Mutex()

    private var wifiLock: WifiManager.WifiLock? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null

    private var lastSurfaceFlipCount: Long = -1L
    private var lastSurfaceFlipTimeMs: Long = 0L

    @Volatile
    private var cachedCpuThermalPath: String? = null
    @Volatile
    private var thermalZoneSearched: Boolean = false

    private val shizukuNewProcessMethod: Method? by lazy {
        try {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }
        } catch (_: Throwable) {
            null
        }
    }

    companion object {
        const val PREFS_NAME = "game_engine_prefs"
        private const val NULL_SENTINEL = "__UNSET_NULL__"
        private const val KEY_OPTIMIZED_PACKAGES = "optimized_game_packages"

        @Volatile
        private var INSTANCE: SystemOptimizer? = null

        fun getInstance(context: Context): SystemOptimizer {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SystemOptimizer(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private fun isValidPackageName(pkg: String): Boolean {
        return pkg.isNotEmpty() && pkg.all { it.isLetterOrDigit() || it == '.' || it == '_' }
    }

    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    fun hasShizukuPermission(): Boolean {
        if (!isShizukuAvailable()) return false
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    suspend fun runShizukuShell(command: String, timeoutMs: Long = 2000L): String? = withContext(Dispatchers.IO) {
        if (!hasShizukuPermission()) return@withContext null
        val method = shizukuNewProcessMethod ?: return@withContext null

        withTimeoutOrNull(timeoutMs) {
            var process: Process? = null
            try {
                process = method.invoke(null, arrayOf("sh", "-c", command), null, null) as? Process
                    ?: return@withTimeoutOrNull null

                val output = process.inputStream.bufferedReader().use { it.readText() }
                process.errorStream.bufferedReader().use { it.readText() }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                        process.destroyForcibly()
                        return@withTimeoutOrNull null
                    }
                } else {
                    process.waitFor()
                }
                output.trim()
            } catch (_: Throwable) {
                null
            } finally {
                try {
                    process?.destroy()
                } catch (_: Throwable) {}
            }
        }
    }

    private suspend fun resolveCpuThermalZonePath(): String? {
        if (thermalZoneSearched) return cachedCpuThermalPath
        if (!hasShizukuPermission()) return null

        // Tırnak veya $ kaçış hatası yaratmayan temiz grep komutu
        val probeCmd = "grep -H '' /sys/class/thermal/thermal_zone*/type 2>/dev/null"
        val out = runShizukuShell(probeCmd, 1500L)
        if (!out.isNullOrBlank()) {
            val cpuKeywords = listOf("cpu", "soc", "tsens_tz_sensor", "mtktscpu", "exynos", "ap_temp", "cluster")
            val lines = out.lines()
            val matchedLine = lines.firstOrNull { line ->
                val typePart = line.substringAfter(":", "").lowercase()
                cpuKeywords.any { kw -> typePart.contains(kw) }
            } ?: lines.firstOrNull { it.startsWith("/sys/class/thermal/thermal_zone") }

            val typeFilePath = matchedLine?.substringBefore(":")?.trim()
            if (!typeFilePath.isNullOrEmpty() && typeFilePath.endsWith("/type")) {
                cachedCpuThermalPath = typeFilePath.removeSuffix("/type") + "/temp"
            }
        }
        thermalZoneSearched = true
        return cachedCpuThermalPath
    }

    suspend fun readShizukuTelemetry(includeFps: Boolean): Pair<Int?, Float?> = withContext(Dispatchers.IO) {
        if (!hasShizukuPermission()) return@withContext Pair(null, null)

        val thermalPath = resolveCpuThermalZonePath()
        val cmdBuilder = StringBuilder()
        if (includeFps) {
            cmdBuilder.append("service call SurfaceFlinger 1013; ")
        }
        cmdBuilder.append("echo '__SEP__'; ")
        if (thermalPath != null) {
            cmdBuilder.append("cat ").append(thermalPath).append(" 2>/dev/null")
        }

        val rawOut = runShizukuShell(cmdBuilder.toString(), 1500L) ?: return@withContext Pair(null, null)
        val parts = rawOut.split("__SEP__")
        val sfPart = parts.getOrNull(0).orEmpty()
        val tempPart = parts.getOrNull(1)?.trim().orEmpty()

        var calculatedFps: Int? = null
        if (includeFps && sfPart.isNotBlank()) {
            val regex = Regex("Parcel\\(\\s*[0-9a-fA-F]{8}\\s+([0-9a-fA-F]{8})")
            val match = regex.find(sfPart)
            if (match != null) {
                val currentFlips = match.groupValues[1].toLongOrNull(16)
                val nowMs = System.currentTimeMillis()
                if (currentFlips != null) {
                    if (lastSurfaceFlipCount > 0L && nowMs > lastSurfaceFlipTimeMs) {
                        val deltaFlips = currentFlips - lastSurfaceFlipCount
                        val deltaMs = nowMs - lastSurfaceFlipTimeMs
                        if (deltaFlips >= 0 && deltaMs >= 200) {
                            val maxHz = getMaxSupportedDisplayHz().coerceAtLeast(120)
                            calculatedFps = ((deltaFlips * 1000L) / deltaMs).toInt().coerceIn(1, maxHz)
                        }
                    }
                    lastSurfaceFlipCount = currentFlips
                    lastSurfaceFlipTimeMs = nowMs
                }
            }
        }

        var cpuTempC: Float? = null
        val rawTempVal = tempPart.lines().firstOrNull()?.trim()?.toFloatOrNull()
        if (rawTempVal != null && rawTempVal > 0f) {
            cpuTempC = when {
                rawTempVal >= 1000f -> ((rawTempVal / 100f).roundToInt()) / 10f
                rawTempVal >= 200f -> ((rawTempVal / 10f).roundToInt()) / 1f
                else -> (rawTempVal * 10f).roundToInt() / 10f
            }
            if (cpuTempC !in 15f..115f) {
                cpuTempC = null
            }
        }

        Pair(calculatedFps, cpuTempC)
    }

    suspend fun getHardwareMetrics(currentPing: Int, preReadCpuTemp: Float? = null): HardwareMetrics = withContext(Dispatchers.IO) {
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val totalMb = (memInfo.totalMem / (1024 * 1024)).coerceAtLeast(1L)
        val availMb = memInfo.availMem / (1024 * 1024)
        val usedMb = (totalMb - availMb).coerceAtLeast(0L)
        val percent = ((usedMb.toDouble() / totalMb.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)

        val batteryIntent = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tempTenths = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val batTempC = tempTenths / 10f
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0

        val dm = appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val currentHz = display?.refreshRate?.roundToInt() ?: 60

        val realCpuTemp = preReadCpuTemp ?: if (hasShizukuPermission()) {
            readShizukuTelemetry(includeFps = false).second
        } else {
            null
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

    fun getMaxSupportedDisplayHz(): Int {
        return try {
            val dm = appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return 120
            display.supportedModes.maxOfOrNull { it.refreshRate.roundToInt() } ?: 120
        } catch (_: Exception) {
            120
        }
    }

    suspend fun backupOriginalSystemState() = stateMutex.withLock {
        if (prefs.getBoolean("state_backed_up", false)) return@withLock
        try {
            val resolver = appContext.contentResolver
            val origMinHz = Settings.System.getString(resolver, "min_refresh_rate") ?: NULL_SENTINEL
            val origPeakHz = Settings.System.getString(resolver, "peak_refresh_rate") ?: NULL_SENTINEL
            val origMode = Settings.System.getString(resolver, "refresh_rate_mode") ?: NULL_SENTINEL

            var origGosTempControl = NULL_SENTINEL
            if (hasShizukuPermission()) {
                val gosOut = runShizukuShell("settings get secure game_auto_temperature_control", 1000L)
                if (!gosOut.isNullOrBlank() && gosOut != "null") {
                    origGosTempControl = gosOut.trim()
                }
            }

            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val origDnd = nm.currentInterruptionFilter

            prefs.edit()
                .putString("orig_min_hz_str", origMinHz)
                .putString("orig_peak_hz_str", origPeakHz)
                .putString("orig_refresh_mode_str", origMode)
                .putString("orig_gos_temp_str", origGosTempControl)
                .putInt("orig_dnd_filter", origDnd)
                .putBoolean("state_backed_up", true)
                .apply()
        } catch (_: Exception) {}
    }

    fun restoreOriginalSystemStateAsync(onComplete: (() -> Unit)? = null) {
        backgroundScope.launch {
            restoreOriginalSystemState()
            if (onComplete != null) {
                withContext(Dispatchers.Main) { onComplete() }
            }
        }
    }

    suspend fun restoreOriginalSystemState() = stateMutex.withLock {
        withContext(Dispatchers.IO) {
            setLowLatencyWifiLock(false)
            setCpuSustainedWakeLock(false)

            val optimizedPkgs = prefs.getStringSet(KEY_OPTIMIZED_PACKAGES, emptySet())?.toSet() ?: emptySet()
            val wasBackedUp = prefs.getBoolean("state_backed_up", false)

            val origMinHz = prefs.getString("orig_min_hz_str", NULL_SENTINEL) ?: NULL_SENTINEL
            val origPeakHz = prefs.getString("orig_peak_hz_str", NULL_SENTINEL) ?: NULL_SENTINEL
            val origMode = prefs.getString("orig_refresh_mode_str", NULL_SENTINEL) ?: NULL_SENTINEL
            val origGos = prefs.getString("orig_gos_temp_str", NULL_SENTINEL) ?: NULL_SENTINEL
            val origDnd = prefs.getInt("orig_dnd_filter", NotificationManager.INTERRUPTION_FILTER_ALL)

            if (hasShizukuPermission()) {
                val cmds = mutableListOf<String>()
                cmds.add("cmd power set-fixed-performance-mode-enabled false")

                for (pkg in optimizedPkgs) {
                    if (isValidPackageName(pkg)) {
                        cmds.add("cmd game reset $pkg")
                        cmds.add("cmd game mode standard $pkg")
                    }
                }

                if (wasBackedUp) {
                    cmds.add(
                        if (origMinHz == NULL_SENTINEL) "settings delete system min_refresh_rate"
                        else "settings put system min_refresh_rate $origMinHz"
                    )
                    cmds.add(
                        if (origPeakHz == NULL_SENTINEL) "settings delete system peak_refresh_rate"
                        else "settings put system peak_refresh_rate $origPeakHz"
                    )
                    cmds.add(
                        if (origMode == NULL_SENTINEL) "settings delete system refresh_rate_mode"
                        else "settings put system refresh_rate_mode $origMode"
                    )
                    if (origGos != NULL_SENTINEL) {
                        cmds.add("settings put secure game_auto_temperature_control $origGos")
                    } else {
                        cmds.add("settings delete secure game_auto_temperature_control")
                    }
                }

                runShizukuShell(cmds.joinToString("; "), 3000L)
            } else if (wasBackedUp && Settings.System.canWrite(appContext)) {
                try {
                    val resolver = appContext.contentResolver
                    val minVal = origMinHz.toFloatOrNull() ?: 60f
                    val peakVal = origPeakHz.toFloatOrNull() ?: 120f
                    val modeVal = origMode.toIntOrNull() ?: 1
                    Settings.System.putFloat(resolver, "min_refresh_rate", minVal)
                    Settings.System.putFloat(resolver, "peak_refresh_rate", peakVal)
                    Settings.System.putInt(resolver, "refresh_rate_mode", modeVal)
                } catch (_: Exception) {}
            }

            if (wasBackedUp) {
                try {
                    val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    if (nm.isNotificationPolicyAccessGranted) {
                        nm.setInterruptionFilter(origDnd)
                    }
                } catch (_: Exception) {}
            }

            prefs.edit()
                .putBoolean("state_backed_up", false)
                .remove(KEY_OPTIMIZED_PACKAGES)
                .apply()
        }
    }

    suspend fun lockSystemRefreshRate(targetHz: Int): Boolean = withContext(Dispatchers.IO) {
        backupOriginalSystemState()
        val rate = targetHz.toFloat()
        val mode = if (targetHz >= 90) 1 else 0

        if (hasShizukuPermission()) {
            val cmd = "settings put system min_refresh_rate $rate; " +
                    "settings put system peak_refresh_rate $rate; " +
                    "settings put system refresh_rate_mode $mode"
            runShizukuShell(cmd, 2000L)
            return@withContext true
        }

        if (!Settings.System.canWrite(appContext)) return@withContext false
        return@withContext try {
            val resolver = appContext.contentResolver
            Settings.System.putFloat(resolver, "min_refresh_rate", rate)
            Settings.System.putFloat(resolver, "peak_refresh_rate", rate)
            Settings.System.putInt(resolver, "refresh_rate_mode", mode)
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun applyShizukuGameOptimization(packageName: String, downscaleRatio: String): Boolean = withContext(Dispatchers.IO) {
        if (!hasShizukuPermission()) return@withContext false
        if (!isValidPackageName(packageName)) return@withContext false

        backupOriginalSystemState()

        try {
            val updatedSet = prefs.getStringSet(KEY_OPTIMIZED_PACKAGES, emptySet())?.toMutableSet() ?: mutableSetOf()
            updatedSet.add(packageName)
            prefs.edit().putStringSet(KEY_OPTIMIZED_PACKAGES, updatedSet).apply()

            val gameCmd = if (downscaleRatio == "disable") {
                "cmd game reset $packageName; cmd game mode performance $packageName"
            } else {
                "cmd game mode performance $packageName; cmd game set --mode 2 --downscale $downscaleRatio $packageName"
            }

            val fullCmd = "$gameCmd; " +
                    "cmd power set-fixed-performance-mode-enabled true; " +
                    "settings put secure game_auto_temperature_control 0"

            runShizukuShell(fullCmd, 2500L)
            true
        } catch (_: Throwable) {
            false
        }
    }

    suspend fun updateActiveGamesDownscale(downscaleRatio: String): Int = withContext(Dispatchers.IO) {
        if (!hasShizukuPermission()) return@withContext 0
        val activePkgs = prefs.getStringSet(KEY_OPTIMIZED_PACKAGES, emptySet())?.toSet() ?: emptySet()
        if (activePkgs.isEmpty()) return@withContext 0

        val cmds = activePkgs.mapNotNull { pkg ->
            if (!isValidPackageName(pkg)) null
            else if (downscaleRatio == "disable") "cmd game reset $pkg; cmd game mode performance $pkg"
            else "cmd game set --mode 2 --downscale $downscaleRatio $pkg"
        }
        if (cmds.isNotEmpty()) {
            runShizukuShell(cmds.joinToString("; "), 2500L)
        }
        cmds.size
    }

    fun setCpuSustainedWakeLock(enable: Boolean) {
        try {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (enable) {
                if (cpuWakeLock == null) {
                    cpuWakeLock = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        "UltraGameBooster:SustainedCpuLock"
                    ).apply {
                        setReferenceCounted(false)
                    }
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

    suspend fun measureRealPing(): Int = withContext(Dispatchers.IO) {
        try {
            var time = 0L
            Socket().use { socket ->
                time = measureTimeMillis {
                    socket.connect(InetSocketAddress("ec2.eu-central-1.amazonaws.com", 443), 1500)
                }
            }
            time.toInt()
        } catch (_: Exception) {
            -1
        }
    }

    suspend fun executeDeepBoost(targetHz: Int): BoostResult = withContext(Dispatchers.IO) {
        backupOriginalSystemState()
        val am = appContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val pm = appContext.packageManager

        val beforeMem = ActivityManager.MemoryInfo()
        am.getMemoryInfo(beforeMem)

        val shizukuActive = hasShizukuPermission()
        if (shizukuActive) {
            runShizukuShell(
                "cmd activity kill-all; cmd power set-fixed-performance-mode-enabled true",
                2500L
            )
        }

        val launcherIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(launcherIntent, 0)
        val targetPackages = mutableSetOf<String>()

        for (info in resolveInfos) {
            val pkg = info.activityInfo.packageName
            val appInfo = info.activityInfo.applicationInfo
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (!isSystem && pkg != appContext.packageName && !pkg.contains("shizuku", ignoreCase = true)) {
                targetPackages.add(pkg)
            }
        }

        for (pkg in targetPackages) {
            try {
                am.killBackgroundProcesses(pkg)
            } catch (_: Exception) {}
        }

        val hzLocked = lockSystemRefreshRate(targetHz)
        delay(250)

        val afterMem = ActivityManager.MemoryInfo()
        am.getMemoryInfo(afterMem)
        val realFreedMb = ((afterMem.availMem - beforeMem.availMem) / (1024 * 1024)).coerceAtLeast(0L)

        BoostResult(
            targetedAppCount = targetPackages.size,
            freedRamMb = realFreedMb,
            shizukuDeepClean = shizukuActive,
            hzLocked = hzLocked
        )
    }

    fun setLowLatencyWifiLock(enable: Boolean) {
        try {
            val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (enable) {
                if (wifiLock == null) {
                    val lockType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    } else {
                        @Suppress("DEPRECATION")
                        WifiManager.WIFI_MODE_FULL_HIGH_PERF
                    }
                    wifiLock = wifiManager.createWifiLock(lockType, "UltraGameBooster:LowLatencyLock").apply {
                        setReferenceCounted(false)
                    }
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

    suspend fun setDoNotDisturb(enable: Boolean): Boolean = withContext(Dispatchers.IO) {
        val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.isNotificationPolicyAccessGranted) return@withContext false
        return@withContext try {
            if (enable) {
                backupOriginalSystemState()
                nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            } else {
                val origDnd = prefs.getInt("orig_dnd_filter", NotificationManager.INTERRUPTION_FILTER_ALL)
                nm.setInterruptionFilter(origDnd)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    suspend fun getInstalledUserApps(): List<GameAppInfo> = withContext(Dispatchers.IO) {
        val pm = appContext.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        val resolveInfos = pm.queryIntentActivities(intent, 0)
        val list = mutableListOf<GameAppInfo>()
        val seen = mutableSetOf<String>()

        for (info in resolveInfos) {
            val pkg = info.activityInfo.packageName
            if (pkg == appContext.packageName || seen.contains(pkg)) continue
            seen.add(pkg)

            val appInfo = info.activityInfo.applicationInfo
            val label = appInfo.loadLabel(pm).toString()
            val isGame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appInfo.category == ApplicationInfo.CATEGORY_GAME
            } else {
                false
            } || pkg.contains("pubg", true) || pkg.contains("tencent", true) ||
                    pkg.contains("game", true) || pkg.contains("krafton", true) ||
                    pkg.contains("activision", true) || pkg.contains("riotgames", true) ||
                    pkg.contains("supercell", true) || pkg.contains("genshin", true) ||
                    pkg.contains("mobile.legends", true) || pkg.contains("roblox", true)

            list.add(GameAppInfo(name = label, packageName = pkg, isGameCategory = isGame))
        }
        list.sortedWith(compareByDescending<GameAppInfo> { it.isGameCategory }.thenBy { it.name })
    }
}
