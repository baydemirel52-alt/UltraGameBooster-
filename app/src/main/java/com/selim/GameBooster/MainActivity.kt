package com.selim.gamebooster

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var optimizer: SystemOptimizer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        optimizer = SystemOptimizer(this)

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
}

@Composable
fun GameBoosterDashboard(
    optimizer: SystemOptimizer,
    onApplyWindowHz: (Int, Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("game_engine_prefs", Context.MODE_PRIVATE) }

    var selectedTab by remember { mutableIntStateOf(0) }
    var targetHz by remember { mutableIntStateOf(prefs.getInt("target_hz", 120)) }

    var autoDnd by remember { mutableStateOf(prefs.getBoolean("auto_dnd", true)) }
    var wifiLowLatency by remember { mutableStateOf(prefs.getBoolean("wifi_lock", true)) }
    var showFloatingHud by remember { mutableStateOf(prefs.getBoolean("show_hud", true)) }
    var keepAwake by remember { mutableStateOf(prefs.getBoolean("keep_awake", true)) }

    var metrics by remember {
        mutableStateOf(HardwareMetrics(0, 8192, 0, 35.0f, 80, 4000, 20, 120))
    }
    var installedApps by remember { mutableStateOf<List<GameAppInfo>>(emptyList()) }
    var boostStatusText by remember { mutableStateOf("Hedef: $targetHz FPS • Kare Hızı Kilidi Hazır") }

    LaunchedEffect(Unit) {
        onApplyWindowHz(targetHz, keepAwake)
        installedApps = optimizer.getInstalledUserApps()
        while (true) {
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
                    label = { Text("Motor Ayarları") }
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
                Column {
                    Text(
                        text = "ULTRA GAME ENGINE",
                        color = Color(0xFF00E676),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = boostStatusText,
                        color = Color(0xFF94A3B8),
                        fontSize = 12.sp
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
                            Toast.makeText(context, "${targetHz}Hz FPS & Frame-Time HUD Başlatıldı!", Toast.LENGTH_SHORT).show()
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

            Spacer(Modifier.height(14.dp))

            when (selectedTab) {
                0 -> CockpitTab(
                    metrics = metrics,
                    targetHz = targetHz,
                    onSelectHz = { hz ->
                        targetHz = hz
                        prefs.edit().putInt("target_hz", hz).apply()
                        onApplyWindowHz(hz, keepAwake)
                        val locked = optimizer.lockSystemRefreshRate(hz)
                        boostStatusText = if (locked) {
                            "Sistem Tablosu ${hz}Hz / FPS Olarak Kilitlendi!"
                        } else {
                            "Hedef ${hz}Hz Seçildi (Tam Kilit İçin Ayarlardan İzin Ver)"
                        }
                    },
                    onTurboBoost = {
                        scope.launch {
                            boostStatusText = "${targetHz}Hz sabitleniyor ve arka plan temizleniyor..."
                            val (killed, freedMb) = optimizer.executeDeepBoost(targetHz)
                            optimizer.setLowLatencyWifiLock(wifiLowLatency)
                            optimizer.setCpuSustainedWakeLock(true)
                            if (autoDnd) optimizer.setDoNotDisturb(true)
                            onApplyWindowHz(targetHz, keepAwake)
                            val ping = optimizer.measureRealPing()
                            metrics = optimizer.getHardwareMetrics(ping)
                            boostStatusText = "${targetHz}Hz Kilitlendi • $killed işlem kapandı (+$freedMb MB RAM)"
                        }
                    }
                )
                1 -> GamesLibraryTab(
                    apps = installedApps,
                    targetHz = targetHz,
                    onLaunchGame = { app ->
                        scope.launch {
                            val (_, freedMb) = optimizer.executeDeepBoost(targetHz)
                            optimizer.setLowLatencyWifiLock(wifiLowLatency)
                            optimizer.setCpuSustainedWakeLock(true)
                            if (autoDnd) optimizer.setDoNotDisturb(true)

                            if (showFloatingHud && Settings.canDrawOverlays(context)) {
                                val hudIntent = Intent(context, GameOverlayService::class.java)
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    context.startForegroundService(hudIntent)
                                } else {
                                    context.startService(hudIntent)
                                }
                            }

                            Toast.makeText(
                                context,
                                "${app.name} -> ${targetHz}Hz Kilidi + $freedMb MB Boş RAM ile açılıyor!",
                                Toast.LENGTH_SHORT
                            ).show()

                            val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)
                            if (launchIntent != null) {
                                context.startActivity(launchIntent)
                            }
                        }
                    }
                )
                2 -> EngineSettingsTab(
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
    onSelectHz: (Int) -> Unit,
    onTurboBoost: () -> Unit
) {
    val frameBudgetStr = if (targetHz == 120) "8.3 ms Kare Bütçesi" else if (targetHz == 90) "11.1 ms Kare Bütçesi" else "16.6 ms Kare Bütçesi"

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricCard(
                modifier = Modifier.weight(1f),
                title = "EKRAN & KARE HIZI",
                value = "${metrics.currentDisplayHz} Hz",
                subValue = frameBudgetStr,
                accentColor = Color(0xFF00E676)
            )
            MetricCard(
                modifier = Modifier.weight(1f),
                title = "BELLEK (RAM)",
                value = "%${metrics.ramPercent}",
                subValue = "${metrics.usedRamMb} / ${metrics.totalRamMb} MB",
                accentColor = Color(0xFF38BDF8)
            )
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricCard(
                modifier = Modifier.weight(1f),
                title = "TERMAL ISI",
                value = "${metrics.batteryTemp}°C",
                subValue = if (metrics.batteryTemp >= 42f) "Isı Yüksek! Hz Koruması Aktif" else "Optimal Sıcaklık",
                accentColor = if (metrics.batteryTemp >= 42f) Color(0xFFEF4444) else Color(0xFFF59E0B)
            )
            MetricCard(
                modifier = Modifier.weight(1f),
                title = "CANLI PİNG",
                value = if (metrics.pingMs > 0) "${metrics.pingMs} ms" else "Ölçülüyor",
                subValue = "Low-Latency Soket",
                accentColor = Color(0xFFA855F7)
            )
        }

        Spacer(Modifier.height(18.dp))

        Text(
            text = "HEDEF KARE HIZI VE LTPO EKRAN KİLİDİ (FPS SABİTLEYİCİ)",
            color = Color(0xFF94A3B8),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                Triple(60, "60 FPS", "Standart"),
                Triple(90, "90 FPS", "Sabit Akıcı"),
                Triple(120, "120 FPS", "Maksimum Hz")
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
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = title,
                        color = if (isSelected) Color(0xFF00E676) else Color.White,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = sub,
                        color = Color(0xFF94A3B8),
                        fontSize = 10.sp
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(16.dp))
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
                    text = "${targetHz} FPS KİLİTLE & SİSTEMİ BOOSTLA",
                    color = Color.Black,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 15.sp
                )
            }
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
            .padding(16.dp)
    ) {
        Text(text = title, color = Color(0xFF94A3B8), fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(text = value, color = accentColor, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(4.dp))
        Text(text = subValue, color = Color(0xFFCBD5E1), fontSize = 11.sp)
    }
}

@Composable
fun GamesLibraryTab(
    apps: List<GameAppInfo>,
    targetHz: Int,
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
                                    text = "${targetHz}Hz HAZIR",
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
                        Text("${targetHz}Hz Başlat", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun EngineSettingsTab(
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

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            SettingToggleRow(
                title = "Düşük Gecikme Wi-Fi Kilidi (Ping Sabitleyici)",
                subtitle = "Wi-Fi çipinin uyku moduna geçmesini engeller, paket kaybını önler.",
                checked = wifiLowLatency,
                onCheckedChange = onWifiLatencyChange
            )
        }
        item {
            SettingToggleRow(
                title = "Oyun İçi 90/120Hz FPS & Frame-Time HUD",
                subtitle = "SurfaceFlinger üzerinden ekranı yüksek Hz'e zorlar ve anlık ms/drop sayar.",
                checked = showFloatingHud,
                onCheckedChange = onShowHudChange
            )
        }
        item {
            SettingToggleRow(
                title = "Otomatik Rahatsız Etmeyin (DND) Modu",
                subtitle = "Oyun sırasında üstten bildirim düşüp FPS düşürmesini engeller.",
                checked = autoDnd,
                onCheckedChange = onAutoDndChange
            )
        }
        item {
            SettingToggleRow(
                title = "CPU & Ekran Uyanık Kalma Kilidi",
                subtitle = "İşlemci çekirdeklerinin frekans kısmasını ve ekranın kararmasını önler.",
                checked = keepAwake,
                onCheckedChange = onKeepAwakeChange
            )
        }

        item {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "90 / 120 FPS DONANIM İZİNLERİ (TEK SEFERLİK)",
                color = Color(0xFF00E676),
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
                    if (canWriteSettings) "1. Sistem 90/120Hz Sabitleme İzni (Verildi ✅)"
                    else "1. 90/120Hz Sistem Kilidi İçin Ayar Değiştirme İznini Aç",
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
                Text("2. Oyun İçi HUD İçin Üstte Gösterim İznini Aç", color = Color.White)
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

        item {
            Button(
                onClick = {
                    context.stopService(Intent(context, GameOverlayService::class.java))
                    Toast.makeText(context, "Oyun İçi HUD Kapatıldı.", Toast.LENGTH_SHORT).show()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
            ) {
                Text("Açık Olan Oyun İçi HUD Panelini Kapat", color = Color.White, fontWeight = FontWeight.Bold)
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

