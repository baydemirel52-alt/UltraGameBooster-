package com.selim.gamebooster

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    private lateinit var optimizer: SystemOptimizer
    private var shizukuGrantedState = mutableStateOf(false)

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        shizukuGrantedState.value = (grantResult == PackageManager.PERMISSION_GRANTED)
        if (shizukuGrantedState.value) {
            Toast.makeText(this, "Shizuku ADB Yetkisi Bağlandı! Gerçek FPS & Downscale Aktif.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        optimizer = SystemOptimizer(this)
        shizukuGrantedState.value = optimizer.hasShizukuPermission()

        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
        } catch (_: Throwable) {}

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF00E676),
                    secondary = Color(0xFF3B82F6),
                    background = Color(0xFF090D16),
                    surface = Color(0xFF131B2E)
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    GameBoosterDashboard(
                        optimizer = optimizer,
                        shizukuGranted = shizukuGrantedState.value,
                        onRefreshShizuku = {
                            shizukuGrantedState.value = optimizer.hasShizukuPermission()
                        },
                        onApplyWindowHz = { targetHz, keepScreenOn ->
                            val lp = window.attributes
                            lp.preferredRefreshRate = targetHz.toFloat()
                            val modeId = optimizer.findBestDisplayModeId(targetHz)
                            if (modeId != 0) lp.preferredDisplayModeId = modeId
                            window.attributes = lp

                            if (keepScreenOn) {
                                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            } else {
                                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        } catch (_: Throwable) {}
    }
}

@Composable
fun GameBoosterDashboard(
    optimizer: SystemOptimizer,
    shizukuGranted: Boolean,
    onRefreshShizuku: () -> Unit,
    onApplyWindowHz: (Int, Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("game_engine_prefs", Context.MODE_PRIVATE) }

    var selectedTab by remember { mutableIntStateOf(0) }
    var targetHz by remember { mutableIntStateOf(prefs.getInt("target_hz", 120)) }
    var downscaleRatio by remember { mutableStateOf(prefs.getString("downscale", "0.85") ?: "0.85") }

    var autoDnd by remember { mutableStateOf(prefs.getBoolean("auto_dnd", true)) }
    var wifiLowLatency by remember { mutableStateOf(prefs.getBoolean("wifi_lock", true)) }
    var showFloatingHud by remember { mutableStateOf(prefs.getBoolean("show_hud", true)) }
    var keepAwake by remember { mutableStateOf(prefs.getBoolean("keep_awake", true)) }

    var metrics by remember {
        mutableStateOf(HardwareMetrics(0, 8192, 0, 35.0f, null, 80, 20, 120, false))
    }
    var installedApps by remember { mutableStateOf<List<GameAppInfo>>(emptyList()) }
    var boostStatusText by remember {
        mutableStateOf(if (shizukuGranted) "Shizuku ADB Motoru Aktif ✅" else "Standart Mod • Shizuku Bekleniyor")
    }

    // Android 13+ (API 33+) Bildirim İzni İsteyici
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Toast.makeText(context, "Bildirim izni verildi!", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        onApplyWindowHz(targetHz, keepAwake)
        installedApps = optimizer.getInstalledUserApps()
        while (true) {
            onRefreshShizuku()
            val ping = optimizer.measureRealPing()
            metrics = optimizer.getHardwareMetrics(ping)
            delay(1800)
        }
    }

    Scaffold(
        containerColor = Color(0xFF090D16),
        bottomBar = {
            NavigationBar(containerColor = Color(0xFF111827)) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Speed, contentDescription = null) },
                    label = { Text("Kokpit") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.SportsEsports, contentDescription = null) },
                    label = { Text("Oyunlar") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Default.Tune, contentDescription = null) },
                    label = { Text("Shizuku & İzin") }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "ULTRA GAME ENGINE v2",
                        color = Color(0xFF00E676),
                        fontSize = 19.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = boostStatusText,
                        color = Color(0xFF94A3B8),
                        fontSize = 11.5.sp
                    )
                }

                Button(
                    onClick = {
                        if (!Settings.canDrawOverlays(context)) {
                            Toast.makeText(context, "Önce Üstte Gösterim iznini ver kanka!", Toast.LENGTH_LONG).show()
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            )
                            context.startActivity(intent)
                        } else {
                            val serviceIntent = Intent(context, GameOverlayService::class.java)
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                context.startForegroundService(serviceIntent)
                            } else {
                                context.startService(serviceIntent)
                            }
                            Toast.makeText(context, "Oyun İçi HUD Başlatıldı!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.Visibility, contentDescription = null, tint = Color(0xFF00E676))
                    Spacer(Modifier.width(6.dp))
                    Text("HUD Aç", color = Color.White, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(12.dp))

            when (selectedTab) {
                0 -> CockpitTab(
                    metrics = metrics,
                    targetHz = targetHz,
                    downscaleRatio = downscaleRatio,
                    onSelectHz = { hz ->
                        targetHz = hz
                        prefs.edit().putInt("target_hz", hz).apply()
                        onApplyWindowHz(hz, keepAwake)
                        val locked = optimizer.lockSystemRefreshRate(hz)
                        boostStatusText = if (locked) {
                            "Ekran ${hz}Hz Olarak Kilitlendi (Kapatınca Normale Döner)"
                        } else {
                            "Hedef ${hz}Hz Seçildi (Tam Kilit İçin Shizuku veya Ayar İzni Ver)"
                        }
                    },
                    onSelectDownscale = { ratio ->
                        downscaleRatio = ratio
                        prefs.edit().putString("downscale", ratio).apply()
                    },
                    onTurboBoost = {
                        scope.launch {
                            boostStatusText = "Sistem optimize ediliyor..."
                            val (killed, freedMb) = optimizer.executeDeepBoost(targetHz)
                            optimizer.setLowLatencyWifiLock(wifiLowLatency)
                            if (autoDnd) optimizer.setDoNotDisturb(true)
                            onApplyWindowHz(targetHz, keepAwake)
                            val ping = optimizer.measureRealPing()
                            metrics = optimizer.getHardwareMetrics(ping)
                            boostStatusText = "${targetHz}Hz Kilitlendi • $killed işlem • Gerçek Açılan RAM: $freedMb MB"
                        }
                    },
                    onRestoreNormal = {
                        scope.launch {
                            context.stopService(Intent(context, GameOverlayService::class.java))
                            optimizer.restoreOriginalSystemState()
                            val ping = optimizer.measureRealPing()
                            metrics = optimizer.getHardwareMetrics(ping)
                            boostStatusText = "Tüm sistem ayarları (Hz, DND, Güç) orijinale döndürüldü."
                            Toast.makeText(context, "Sistem orijinal ayarlarına döndü!", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
                1 -> GamesLibraryTab(
                    apps = installedApps,
                    targetHz = targetHz,
                    downscaleRatio = downscaleRatio,
                    onLaunchGame = { app ->
                        scope.launch {
                            val (_, freedMb) = optimizer.executeDeepBoost(targetHz)
                            val shizukuApplied = optimizer.applyShizukuGameOptimization(app.packageName, downscaleRatio)
                            optimizer.setLowLatencyWifiLock(wifiLowLatency)
                            if (autoDnd) optimizer.setDoNotDisturb(true)

                            if (showFloatingHud && Settings.canDrawOverlays(context)) {
                                val hudIntent = Intent(context, GameOverlayService::class.java)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    context.startForegroundService(hudIntent)
                                } else {
                                    context.startService(hudIntent)
                                }
                            }

                            val modeMsg = if (shizukuApplied) {
                                "Shizuku Performans + Downscale ($downscaleRatio) + ${targetHz}Hz"
                            } else {
                                "Standart ${targetHz}Hz ($freedMb MB Boş RAM)"
                            }

                            Toast.makeText(context, "${app.name}: $modeMsg ile açılıyor!", Toast.LENGTH_LONG).show()

                            val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)
                            if (launchIntent != null) {
                                context.startActivity(launchIntent)
                            }
                        }
                    }
                )
                2 -> EngineSettingsTab(
                    optimizer = optimizer,
                    shizukuGranted = shizukuGranted,
                    autoDnd = autoDnd,
                    onAutoDndChange = {
                        autoDnd = it
                        prefs.edit().putBoolean("auto_dnd", it).apply()
                    },
                    wifiLowLatency = wifiLowLatency,
                    onWifiLatencyChange = {
                        wifiLowLatency = it
                        prefs.edit().putBoolean("wifi_lock", it).apply()
                        optimizer.setLowLatencyWifiLock(it)
                    },
                    showFloatingHud = showFloatingHud,
                    onShowHudChange = {
                        showFloatingHud = it
                        prefs.edit().putBoolean("show_hud", it).apply()
                    },
                    keepAwake = keepAwake,
                    onKeepAwakeChange = {
                        keepAwake = it
                        prefs.edit().putBoolean("keep_awake", it).apply()
                        onApplyWindowHz(targetHz, it)
                    }
                )
            }
        }
    }
}

@Composable
fun CockpitTab(
    metrics: HardwareMetrics,
    targetHz: Int,
    downscaleRatio: String,
    onSelectHz: (Int) -> Unit,
    onSelectDownscale: (String) -> Unit,
    onTurboBoost: () -> Unit,
    onRestoreNormal: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MetricCard(
                modifier = Modifier.weight(1f),
                title = "GÜNCEL EKRAN HZ",
                value = "${metrics.currentDisplayHz} Hz",
                subValue = if (metrics.shizukuActive) "Shizuku Gerçek FPS Hazır" else "Standart Ekran Yenileme",
                accentColor = Color(0xFF00E676)
            )
            MetricCard(
                modifier = Modifier.weight(1f),
                title = "BELLEK (GERÇEK RAM)",
                value = "%${metrics.ramPercent}",
                subValue = "${metrics.usedRamMb} / ${metrics.totalRamMb} MB",
                accentColor = Color(0xFF38BDF8)
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            MetricCard(
                modifier = Modifier.weight(1f),
                title = if (metrics.cpuTemp != null) "CPU & BATARYA ISISI" else "BATARYA ISISI",
                value = if (metrics.cpuTemp != null) "${metrics.cpuTemp}°C" else "${metrics.batteryTemp}°C",
                subValue = "Pil Sensörü: ${metrics.batteryTemp}°C",
                accentColor = if (metrics.batteryTemp >= 42f) Color(0xFFEF4444) else Color(0xFFF59E0B)
            )
            MetricCard(
                modifier = Modifier.weight(1f),
                title = "AB (FRANKFURT) PİNG",
                value = if (metrics.pingMs > 0) "${metrics.pingMs} ms" else "Ölçülüyor",
                subValue = "AWS EU-Central Sunucu",
                accentColor = Color(0xFFA855F7)
            )
        }

        Spacer(Modifier.height(14.dp))

        Text(
            text = "1. EKRAN YENİLEME HIZI KİLİDİ (LTPO SABİTLEYİCİ)",
            color = Color(0xFF94A3B8),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                Triple(60, "60 Hz", "Pil Tasarrufu"),
                Triple(90, "90 Hz", "Sabit Akıcı"),
                Triple(120, "120 Hz", "Maksimum Hz")
            ).forEach { (hz, title, sub) ->
                val isSelected = targetHz == hz
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) Color(0xFF00E676).copy(alpha = 0.18f) else Color(0xFF131B2E))
                        .border(
                            width = 1.5.dp,
                            color = if (isSelected) Color(0xFF00E676) else Color(0xFF1E293B),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable { onSelectHz(hz) }
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = title,
                        color = if (isSelected) Color(0xFF00E676) else Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.sp
                    )
                    Text(text = sub, color = Color(0xFF94A3B8), fontSize = 10.sp)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text = "2. SHIZUKU DONANIM ÇÖZÜNÜRLÜK ÖLÇEKLEME (GERÇEK FPS ARTIRICI)",
            color = Color(0xFF94A3B8),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(6.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                Triple("disable", "%100 Orijinal", "Tam Çözünürlük"),
                Triple("0.85", "%85 Dengeli", "Önerilen +FPS"),
                Triple("0.75", "%75 Rekabetçi", "Maksimum +FPS")
            ).forEach { (ratio, title, sub) ->
                val isSelected = downscaleRatio == ratio
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) Color(0xFF38BDF8).copy(alpha = 0.18f) else Color(0xFF131B2E))
                        .border(
                            width = 1.5.dp,
                            color = if (isSelected) Color(0xFF38BDF8) else Color(0xFF1E293B),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .clickable { onSelectDownscale(ratio) }
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = title,
                        color = if (isSelected) Color(0xFF38BDF8) else Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 12.sp
                    )
                    Text(text = sub, color = Color(0xFF94A3B8), fontSize = 10.sp)
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.horizontalGradient(
                        listOf(Color(0xFF00E676), Color(0xFF10B981))
                    )
                )
                .clickable { onTurboBoost() },
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Bolt, contentDescription = null, tint = Color.Black)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${targetHz} HZ UYGULA & SİSTEMİ HAZIRLA",
                    color = Color.Black,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 14.sp
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = { onRestoreNormal() },
            modifier = Modifier.fillMaxWidth().height(46.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = "Oyun Bitti: Tüm Sistem Ayarlarını Orijinale Döndür",
                color = Color(0xFFF87171),
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
fun MetricCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    subValue: String,
    accentColor: Color
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF131B2E))
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Text(text = title, color = Color(0xFF94A3B8), fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(text = value, color = accentColor, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(4.dp))
        Text(text = subValue, color = Color(0xFFCBD5E1), fontSize = 10.5.sp)
    }
}

@Composable
fun GamesLibraryTab(
    apps: List<GameAppInfo>,
    targetHz: Int,
    downscaleRatio: String,
    onLaunchGame: (GameAppInfo) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    val filtered = remember(apps, searchQuery) {
        apps.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Oyun veya uygulama ara...", color = Color.Gray) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Color.Gray) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(filtered, key = { it.packageName }) { app ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF131B2E))
                        .border(
                            1.dp,
                            if (app.isGameCategory) Color(0xFF00E676).copy(alpha = 0.5f) else Color(0xFF1E293B),
                            RoundedCornerShape(14.dp)
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = app.name,
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp
                            )
                            if (app.isGameCategory) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = "${targetHz}Hz | Ölçek: $downscaleRatio",
                                    color = Color(0xFF00E676),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                        }
                        Text(
                            text = app.packageName,
                            color = Color(0xFF64748B),
                            fontSize = 11.sp
                        )
                    }

                    Button(
                        onClick = { onLaunchGame(app) },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Başlat", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun EngineSettingsTab(
    optimizer: SystemOptimizer,
    shizukuGranted: Boolean,
    autoDnd: Boolean,
    onAutoDndChange: (Boolean) -> Unit,
    wifiLowLatency: Boolean,
    onWifiLatencyChange: (Boolean) -> Unit,
    showFloatingHud: Boolean,
    onShowHudChange: (Boolean) -> Unit,
    keepAwake: Boolean,
    onKeepAwakeChange: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val nm = remember { context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager }
    val canWriteSettings = Settings.System.canWrite(context)
    val shizukuRunning = optimizer.isShizukuAvailable()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF131B2E))
                    .border(
                        1.5.dp,
                        if (shizukuGranted) Color(0xFF00E676) else Color(0xFFF59E0B),
                        RoundedCornerShape(14.dp)
                    )
                    .padding(16.dp)
            ) {
                Text(
                    text = if (shizukuGranted) "SHIZUKU ADB MOTORU: AKTİF ✅"
                    else if (shizukuRunning) "SHIZUKU ÇALIŞIYOR (YETKİ VERİLMEDİ)"
                    else "SHIZUKU KAPALI VEYA YÜKLÜ DEĞİL",
                    color = if (shizukuGranted) Color(0xFF00E676) else Color(0xFFF59E0B),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Gerçek oyun FPS'ini (SurfaceFlinger) okumak, oyunun çözünürlüğünü donanımsal ölçeklemek (Downscale) ve Samsung GOS hız kesmesini durdurmak için Shizuku yetkisi gerekir.",
                    color = Color(0xFFCBD5E1),
                    fontSize = 11.5.sp
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        if (!optimizer.isShizukuAvailable()) {
                            Toast.makeText(
                                context,
                                "Önce Shizuku uygulamasını açıp Kablosuz Hata Ayıklama ile başlat kanka!",
                                Toast.LENGTH_LONG
                            ).show()
                        } else if (!optimizer.hasShizukuPermission()) {
                            try {
                                Shizuku.requestPermission(1001)
                            } catch (_: Throwable) {}
                        } else {
                            Toast.makeText(context, "Shizuku zaten bağlı ve aktif!", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (shizukuGranted) Color(0xFF059669) else Color(0xFF2563EB)
                    )
                ) {
                    Text(
                        if (shizukuGranted) "Shizuku ADB Bağlantısı Tamam ✅"
                        else "Shizuku ADB Yetkisini Şimdi Bağla",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        item {
            SettingToggleRow(
                title = "Oyun İçi HUD Paneli",
                subtitle = "Shizuku aktifse gerçek oyun FPS'ini, kapalıysa gerçek ekran Hz'ini gösterir.",
                checked = showFloatingHud,
                onCheckedChange = onShowHudChange
            )
        }
        item {
            SettingToggleRow(
                title = "Otomatik Rahatsız Etmeyin (DND) Modu",
                subtitle = "Oyun sırasında bildirimleri susturur, HUD kapanınca otomatik eski haline döner.",
                checked = autoDnd,
                onCheckedChange = onAutoDndChange
            )
        }
        item {
            SettingToggleRow(
                title = "Düşük Gecikme Wi-Fi Kilidi",
                subtitle = "Oyun boyunca Wi-Fi çipini düşük gecikme modunda tutar, çıkınca bırakır.",
                checked = wifiLowLatency,
                onCheckedChange = onWifiLatencyChange
            )
        }
        item {
            SettingToggleRow(
                title = "Ekran Uyanık Kalma Kilidi",
                subtitle = "Oyun sırasında ekranın kararmasını önler.",
                checked = keepAwake,
                onCheckedChange = onKeepAwakeChange
            )
        }

        item {
            Text(
                text = "TEMEL ANDROID İZİNLERİ",
                color = Color(0xFF94A3B8),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }

        item {
            Button(
                onClick = {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_WRITE_SETTINGS,
                        Uri.parse("package:${context.packageName}")
                    )
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))
            ) {
                Text(
                    if (canWriteSettings) "1. Sistem Ayar İzni (Verildi ✅)"
                    else "1. Sistem Ayarlarını Değiştirme İznini Aç",
                    color = Color.White
                )
            }
        }

        item {
            Button(
                onClick = {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))
            ) {
                Text("2. Üstte Gösterim (HUD) İznini Aç", color = Color.White)
            }
        }

        item {
            Button(
                onClick = {
                    val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                    context.startActivity(intent)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E293B))
            ) {
                val granted = nm.isNotificationPolicyAccessGranted
                Text(
                    if (granted) "3. DND Bildirim İzni (Verildi ✅)" else "3. Rahatsız Etmeyin (DND) İznini Aç",
                    color = Color.White
                )
            }
        }
    }
}

@Composable
fun SettingToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF131B2E))
            .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            Text(text = subtitle, color = Color(0xFF94A3B8), fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = Color(0xFF00E676))
        )
    }
}
