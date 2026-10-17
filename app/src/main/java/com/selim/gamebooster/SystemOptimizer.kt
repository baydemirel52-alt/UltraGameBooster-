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

        val out = runShizukuShell("grep -H . /sys/class/thermal/thermal_zone*/type 2>/dev/null", 1500L)
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
            val regex = Regex("""Parcel\(\s*[0-9a-fA-F]{8}\s+([0-9a-fA-F]{8})""")
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
                    if (pkg.matches(Regex("^[a-zA-Z0-9._]+$"))) {
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
