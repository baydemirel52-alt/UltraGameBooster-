package com.selim.ultragameengine

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import kotlin.math.roundToInt

private val Cyan = Color(0xFF00E5FF)
private val Violet = Color(0xFF7C4DFF)
private val Bg = Color(0xFF080B12)
private val Surface = Color(0xFF121824)
private val Outline = Color(0xFF1E293B)
private val Muted = Color(0xFF94A3B8)
private val Green = Color(0xFF00E676)
private val Amber = Color(0xFFFFB300)
private val Red = Color(0xFFFF5252)

class MainActivity : ComponentActivity() {

    private lateinit var monetizationManager: MonetizationManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SystemOptimizer.installCrashSafetyRestore(this)
        monetizationManager = MonetizationManager(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Cyan,
                    secondary = Violet,
                    background = Bg,
                    surface = Surface
                )
            ) {
                UltraEngineMainScreen()
            }
        }
    }

    @Composable
    fun UltraEngineMainScreen() {
        val ctx = this@MainActivity
        val scope = rememberCoroutineScope()
        val isProUnlocked by monetizationManager.isProUnlocked.collectAsState()
        val isDevMode by monetizationManager.isDevMode.collectAsState()
        var showDevDialog by remember { mutableStateOf(false) }
        var devCodeInput by remember { mutableStateOf("") }

        // Canlı cihaz durumu (2 sn'de bir yenilenir)
        var shizukuReady by remember { mutableStateOf(SystemOptimizer.isShizukuReady()) }
        var shizukuDirty by remember { mutableStateOf(SystemOptimizer.isShizukuDirty(ctx)) }
        var usageAccess by remember { mutableStateOf(SystemOptimizer.hasUsageAccess(ctx)) }
        var freeRamMb by remember { mutableLongStateOf(SystemOptimizer.getAvailableRamMb(ctx)) }
        var batteryTemp by remember { mutableFloatStateOf(SystemOptimizer.getBatteryTemperature(ctx)) }
        var thermalLabel by remember { mutableStateOf(SystemOptimizer.getThermalLabel(ctx)) }
        var powerSave by remember { mutableStateOf(SystemOptimizer.isPowerSaveMode(ctx)) }

        var installedGames by remember { mutableStateOf(emptyList<GameAppInfo>()) }
        var allApps by remember { mutableStateOf(emptyList<GameAppInfo>()) }
        var showAllApps by remember { mutableStateOf(false) }
        var searchQuery by remember { mutableStateOf("") }
        var selectedGame by remember { mutableStateOf<GameAppInfo?>(null) }
        var hzOptions by remember { mutableStateOf(emptyList<Int>()) }

        var resolutionPercent by remember { mutableIntStateOf(100) }
        var targetHz by remember { mutableIntStateOf(0) }
        var enableDnd by remember { mutableStateOf(true) }
        var lowLatencyWifi by remember { mutableStateOf(true) }
        var cleanRam by remember { mutableStateOf(true) }
        var statusLog by remember { mutableStateOf("Motor hazır. Oyununu seç, ayarları belirle ve başlat.") }
        var showShizukuWizard by remember { mutableStateOf(false) }

        fun selectGame(game: GameAppInfo) {
            selectedGame = game
            val prof = SystemOptimizer.loadProfile(ctx, game.packageName)
            resolutionPercent = prof.resolutionPercent
            targetHz = prof.targetHz
            enableDnd = prof.enableDnd
            lowLatencyWifi = prof.lowLatencyWifi
            cleanRam = prof.cleanRam
        }

        fun applyPreset(resolution: Int, useMaxHz: Boolean) {
            resolutionPercent = if (shizukuReady) resolution else 100
            val maxHz = hzOptions.maxOrNull() ?: 0
            targetHz = if (shizukuReady && useMaxHz && hzOptions.size > 1) maxHz else 0
            enableDnd = true
            lowLatencyWifi = true
            cleanRam = true
        }

        LaunchedEffect(Unit) {
            val games = withContext(Dispatchers.IO) { SystemOptimizer.getInstalledGames(ctx) }
            installedGames = games
            hzOptions = SystemOptimizer.getSupportedRefreshRates(ctx)
            games.firstOrNull()?.let { selectGame(it) }
        }

        LaunchedEffect(showAllApps) {
            if (showAllApps && allApps.isEmpty()) {
                allApps = withContext(Dispatchers.IO) { SystemOptimizer.getAllLaunchableApps(ctx) }
            }
        }

        LaunchedEffect(Unit) {
            while (true) {
                shizukuReady = SystemOptimizer.isShizukuReady()
                shizukuDirty = SystemOptimizer.isShizukuDirty(ctx)
                usageAccess = SystemOptimizer.hasUsageAccess(ctx)
                freeRamMb = SystemOptimizer.getAvailableRamMb(ctx)
                batteryTemp = SystemOptimizer.getBatteryTemperature(ctx)
                thermalLabel = SystemOptimizer.getThermalLabel(ctx)
                powerSave = SystemOptimizer.isPowerSaveMode(ctx)
                delay(2000)
            }
        }

        val visibleGames = remember(installedGames, allApps, showAllApps, searchQuery) {
            val base = if (showAllApps) allApps else installedGames
            if (searchQuery.isBlank()) {
                base
            } else {
                base.filter {
                    it.name.contains(searchQuery, ignoreCase = true) ||
                        it.packageName.contains(searchQuery, ignoreCase = true)
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Bg)
                .padding(16.dp)
        ) {
            // ---------------- Başlık ----------------
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "ULTRA GAME ENGINE",
                        color = Cyan,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = if (shizukuReady) "MOD: ULTRA DONANIM (SHIZUKU AKTİF)" else "MOD: STANDART OPTİMİZASYON",
                        color = if (shizukuReady) Green else Amber,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = {
                        scope.launch {
                            val msg = withContext(Dispatchers.IO) {
                                SystemOptimizer.restoreOriginalSystemSettings(ctx, force = true)
                            }
                            stopService(Intent(ctx, GameOverlayService::class.java))
                            statusLog = msg
                            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("ACİL SIFIRLA", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Çözünürlük değişmiş ama Shizuku kapalıysa kullanıcı sıkışmasın.
                if (shizukuDirty && !shizukuReady) {
                    item {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF3B1212)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, Red, RoundedCornerShape(12.dp))
                        ) {
                            Text(
                                text = "Ekran çözünürlüğün/Hz ayarın değiştirilmiş kalmış olabilir. " +
                                    "Shizuku'yu başlat, sonra ACİL SIFIRLA'ya bas.",
                                color = Color.White,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }

                // ---------------- Cihaz durumu ----------------
                item {
                    SectionCard(title = "Cihaz Durumu") {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            StatBox(
                                label = "Boş RAM",
                                value = "$freeRamMb MB",
                                valueColor = Cyan,
                                modifier = Modifier.weight(1f)
                            )
                            StatBox(
                                label = "Pil sıcaklığı",
                                value = "${batteryTemp}°C",
                                valueColor = if (batteryTemp >= 43f) Red else Green,
                                modifier = Modifier.weight(1f)
                            )
                            StatBox(
                                label = "Termal durum",
                                value = thermalLabel,
                                valueColor = if (thermalLabel == "Orta" || thermalLabel == "Ciddi" || thermalLabel == "Kritik") Red else Green,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (powerSave) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "⚠ Pil tasarrufu açık: işlemciyi kısıtlar ve FPS'i düşürür. Oyundan önce kapat.",
                                color = Amber,
                                fontSize = 12.sp
                            )
                            TextButton(onClick = {
                                try {
                                    startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
                                } catch (_: Exception) {
                                    startActivity(Intent(Settings.ACTION_SETTINGS))
                                }
                            }) {
                                Text("Pil tasarrufu ayarlarını aç", color = Cyan, fontSize = 12.sp)
                            }
                        }
                    }
                }

                // ---------------- Shizuku ----------------
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Surface),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Outline, RoundedCornerShape(12.dp))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (shizukuReady) "Shizuku Donanım Yetkisi Bağlı" else "Donanım Kilidini Aç (Shizuku Rehberi)",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = {
                                    shizukuReady = SystemOptimizer.isShizukuReady()
                                    showShizukuWizard = !showShizukuWizard
                                }) {
                                    Text(if (showShizukuWizard) "Gizle" else "Kurulumu Gör", color = Cyan)
                                }
                            }

                            if (showShizukuWizard) {
                                Text(
                                    text = "Çözünürlük ölçekleme, Hz kilidi ve CPU sıcaklığı için Rootsuz ADB yetkisi gerekir:\n" +
                                        "1. Shizuku uygulamasını indirip açın.\n" +
                                        "2. Geliştirici Seçenekleri -> Kablosuz Hata Ayıklama ile Shizuku'yu başlatın.\n" +
                                        "3. 'Yetki Bağla' butonuna basın.",
                                    color = Muted,
                                    fontSize = 12.sp
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            startActivity(
                                                Intent(
                                                    Intent.ACTION_VIEW,
                                                    Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api")
                                                )
                                            )
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("1. Shizuku", fontSize = 11.sp)
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            try {
                                                startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
                                            } catch (_: Exception) {
                                                startActivity(Intent(Settings.ACTION_SETTINGS))
                                            }
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("2. Geliştirici", fontSize = 11.sp)
                                    }
                                    Button(
                                        onClick = {
                                            try {
                                                if (Shizuku.pingBinder()) {
                                                    Shizuku.requestPermission(1001)
                                                } else {
                                                    Toast.makeText(ctx, "Önce Shizuku uygulamasını başlatın.", Toast.LENGTH_SHORT).show()
                                                }
                                            } catch (_: Exception) {
                                            }
                                            shizukuReady = SystemOptimizer.isShizukuReady()
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("3. Yetki Bağla", fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                // ---------------- Pro / reklam ----------------
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A142E)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, Violet, RoundedCornerShape(12.dp))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .pointerInput(Unit) {
                                        detectTapGestures(onLongPress = { showDevDialog = true })
                                    }
                            ) {
                                Text(
                                    text = if (isDevMode) {
                                        "ULTRA PRO AKTİF (GELİŞTİRİCİ)"
                                    } else if (isProUnlocked) {
                                        "ULTRA PRO AKTİF (${monetizationManager.getRemainingRewardMinutes()} dk)"
                                    } else {
                                        "ÇÖZÜNÜRLÜK ÖLÇEKLEME KİLİTLİ"
                                    },
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp
                                )
                                Text(
                                    text = "Reklam izleyerek 3 saat ücretsiz açın veya Ömür Boyu Pro'ya geçin.",
                                    color = Color(0xFFCBD5E1),
                                    fontSize = 11.sp
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(
                                    onClick = { monetizationManager.showRewardedAdFor3Hours(ctx) {} },
                                    colors = ButtonDefaults.buttonColors(containerColor = Cyan),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text("Reklam (3s)", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                Button(
                                    onClick = { monetizationManager.launchProPurchaseFlow(ctx) },
                                    colors = ButtonDefaults.buttonColors(containerColor = Violet),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text("PRO Al", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // ---------------- Oyun seçimi ----------------
                item {
                    Text("1. Oyununu Seç", color = Cyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                item {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        singleLine = true,
                        label = { Text("Ara") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    SwitchRow(
                        label = "Tüm uygulamaları göster (oyunun listede yoksa)",
                        checked = showAllApps,
                        onChange = { showAllApps = it }
                    )
                }
                if (visibleGames.isEmpty()) {
                    item {
                        Text(
                            text = if (showAllApps) "Uygulama bulunamadı." else "Oyun otomatik bulunamadı. Yukarıdan 'Tüm uygulamaları göster'i aç.",
                            color = Muted,
                            fontSize = 12.sp
                        )
                    }
                }
                items(visibleGames, key = { it.packageName }) { game ->
                    val isSelected = selectedGame?.packageName == game.packageName
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(if (isSelected) Outline else Surface, RoundedCornerShape(8.dp))
                            .border(1.dp, if (isSelected) Cyan else Color.Transparent, RoundedCornerShape(8.dp))
                            .clickable { selectGame(game) }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AppIcon(packageName = game.packageName, modifier = Modifier.size(36.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(game.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(game.packageName, color = Color(0xFF64748B), fontSize = 11.sp)
                        }
                        if (isSelected) {
                            Text("SEÇİLİ", color = Cyan, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // ---------------- Hazır profiller ----------------
                item {
                    SectionCard(title = "2. Hazır Profil") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = { applyPreset(resolution = 100, useMaxHz = false) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) { Text("Dengeli", fontSize = 12.sp) }
                            OutlinedButton(
                                onClick = { applyPreset(resolution = 85, useMaxHz = true) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) { Text("Performans", fontSize = 12.sp) }
                            OutlinedButton(
                                onClick = { applyPreset(resolution = 70, useMaxHz = true) },
                                modifier = Modifier.weight(1f),
                                contentPadding = PaddingValues(horizontal = 4.dp)
                            ) { Text("Maksimum", fontSize = 12.sp) }
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (shizukuReady) {
                                "Dengeli: sadece temizlik. Performans: %85 çözünürlük + en yüksek Hz. Maksimum: %70 çözünürlük + en yüksek Hz."
                            } else {
                                "Shizuku bağlı değil: profiller sadece RAM temizliği, DND ve Wi-Fi kilidini ayarlar."
                            },
                            color = Muted,
                            fontSize = 11.sp
                        )
                    }
                }

                // ---------------- Standart optimizasyonlar ----------------
                item {
                    SectionCard(title = "3. Standart Optimizasyonlar (Shizuku gerekmez)") {
                        SwitchRow(
                            label = "Arka plan uygulamalarını kapat (RAM temizliği)",
                            checked = cleanRam,
                            onChange = { cleanRam = it }
                        )
                        SwitchRow(
                            label = "Oyun içi Rahatsız Etmeyin (bildirimleri sustur)",
                            checked = enableDnd,
                            onChange = {
                                val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                                if (it && !nm.isNotificationPolicyAccessGranted) {
                                    startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                                }
                                enableDnd = it
                            }
                        )
                        SwitchRow(
                            label = "Düşük gecikmeli Wi-Fi kilidi",
                            checked = lowLatencyWifi,
                            onChange = { lowLatencyWifi = it }
                        )
                    }
                }

                // ---------------- Ultra donanım ----------------
                item {
                    SectionCard(title = "4. Ultra Donanım Motoru (Shizuku)") {
                        val steps = SystemOptimizer.RESOLUTION_STEPS
                        val stepIndex = steps.indexOf(resolutionPercent).let { if (it < 0) 0 else it }
                        Text(
                            text = "Çözünürlük: %$resolutionPercent" +
                                if (resolutionPercent < 100) " (GPU yükünü düşürür, ekran biraz yumuşar)" else " (değişmez)",
                            color = if (shizukuReady) Color.White else Color(0xFF64748B),
                            fontSize = 12.sp
                        )
                        Slider(
                            value = stepIndex.toFloat(),
                            onValueChange = {
                                resolutionPercent = steps[it.roundToInt().coerceIn(0, steps.lastIndex)]
                            },
                            valueRange = 0f..(steps.size - 1).toFloat(),
                            steps = steps.size - 2,
                            enabled = shizukuReady
                        )

                        Text(
                            text = "Ekran yenileme hızı:",
                            color = if (shizukuReady) Color.White else Color(0xFF64748B),
                            fontSize = 12.sp
                        )
                        if (hzOptions.size > 1) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = targetHz == 0,
                                    onClick = { targetHz = 0 },
                                    enabled = shizukuReady,
                                    label = { Text("Otomatik") }
                                )
                                hzOptions.forEach { hz ->
                                    FilterChip(
                                        selected = targetHz == hz,
                                        onClick = { targetHz = hz },
                                        enabled = shizukuReady,
                                        label = { Text("$hz Hz") }
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = "Cihazın tek yenileme hızı var; kilitlemeye gerek yok.",
                                color = Muted,
                                fontSize = 11.sp
                            )
                        }
                        if (!shizukuReady) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Bu bölüm için yukarıdan Shizuku'yu bağla.",
                                color = Amber,
                                fontSize = 11.sp
                            )
                        }
                    }
                }

                // ---------------- Otomatik sıfırlama ----------------
                item {
                    SectionCard(title = "Oyun kapanınca otomatik sıfırla") {
                        if (usageAccess) {
                            Text(
                                text = "✓ Açık. Oyundan çıkınca çözünürlük, Hz ve bildirim ayarları kendiliğinden eski haline döner.",
                                color = Green,
                                fontSize = 12.sp
                            )
                        } else {
                            Text(
                                text = "Kapalı. Oyundan çıkınca ayarların kendiliğinden eski haline dönmesi için " +
                                    "'Kullanım erişimi' izni ver. İzin vermezsen sıfırlamayı elle yapman gerekir.",
                                color = Muted,
                                fontSize = 12.sp
                            )
                            TextButton(onClick = {
                                try {
                                    startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                                } catch (_: Exception) {
                                    startActivity(Intent(Settings.ACTION_SETTINGS))
                                }
                            }) {
                                Text("İzin ver", color = Cyan)
                            }
                        }
                    }
                }

                // ---------------- Sonuç raporu ----------------
                item {
                    SectionCard(title = "Son İşlem Raporu") {
                        statusLog.lines().forEach { line ->
                            Text(text = line, color = lineColor(line), fontSize = 12.sp)
                        }
                        TextButton(onClick = {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://baydemirel52-alt.github.io/")))
                        }) {
                            Text("Gizlilik Politikası", color = Color(0xFF64748B), fontSize = 11.sp)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ---------------- Başlat ----------------
            Button(
                onClick = {
                    if (!Settings.canDrawOverlays(ctx)) {
                        Toast.makeText(ctx, "HUD göstergesi için 'Üstte Göster' izni verin.", Toast.LENGTH_LONG).show()
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                        return@Button
                    }

                    if (shizukuReady && resolutionPercent < 100 && !monetizationManager.checkAccessStatus()) {
                        Toast.makeText(
                            ctx,
                            "Çözünürlük ölçekleme için yukarıdan 1 kısa reklam izleyin veya Pro'ya geçin!",
                            Toast.LENGTH_LONG
                        ).show()
                        return@Button
                    }

                    val pkg = selectedGame?.packageName ?: ""
                    val profile = GameProfile(
                        packageName = pkg,
                        resolutionPercent = resolutionPercent,
                        targetHz = targetHz,
                        enableDnd = enableDnd,
                        lowLatencyWifi = lowLatencyWifi,
                        cleanRam = cleanRam
                    )
                    if (pkg.isNotEmpty()) {
                        SystemOptimizer.saveProfile(ctx, profile)
                    }

                    scope.launch {
                        statusLog = "⏳ Optimizasyon uygulanıyor..."
                        statusLog = SystemOptimizer.applyOptimizationProfile(ctx, profile)

                        // HUD servisi, oyun ön plana gelmeden ÖNCE başlatılır (arka plandan başlatma kısıtı yüzünden).
                        val hudIntent = Intent(ctx, GameOverlayService::class.java)
                            .putExtra(GameOverlayService.EXTRA_GAME_PACKAGE, pkg)
                        startForegroundService(hudIntent)

                        if (pkg.isNotEmpty()) {
                            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                            if (launchIntent != null) {
                                startActivity(launchIntent)
                            }
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Cyan),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Text(
                    text = "MOTORU ATEŞLE & OYUNU BAŞLAT",
                    color = Color.Black,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (!isDevMode) {
                AndroidView(
                    modifier = Modifier.fillMaxWidth(),
                    factory = { context ->
                        AdView(context).apply {
                            setAdSize(AdSize.BANNER)
                            adUnitId = "ca-app-pub-3940256099942544/6300978111"
                            loadAd(AdRequest.Builder().build())
                        }
                    }
                )
            }

            if (showDevDialog) {
                AlertDialog(
                    onDismissRequest = { showDevDialog = false },
                    title = { Text(if (isDevMode) "Geliştirici modu açık" else "Geliştirici kodu") },
                    text = {
                        if (isDevMode) {
                            Text("Reklamlar kapalı, Pro açık.")
                        } else {
                            OutlinedTextField(
                                value = devCodeInput,
                                onValueChange = { devCodeInput = it },
                                singleLine = true
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            if (isDevMode) {
                                monetizationManager.disableDevMode()
                            } else if (!monetizationManager.tryEnableDevMode(devCodeInput)) {
                                Toast.makeText(ctx, "Kod yanlış", Toast.LENGTH_SHORT).show()
                            }
                            devCodeInput = ""
                            showDevDialog = false
                        }) { Text(if (isDevMode) "Kapat" else "Aç") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDevDialog = false }) { Text("Vazgeç") }
                    }
                )
            }
        }
    }
}

private fun lineColor(line: String): Color = when {
    line.startsWith("✓") -> Green
    line.startsWith("✗") -> Red
    line.startsWith("⚠") -> Amber
    else -> Muted
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Surface),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, Outline, RoundedCornerShape(12.dp))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(title, color = Cyan, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            Spacer(modifier = Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun StatBox(label: String, value: String, valueColor: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color(0xFF0E1420), RoundedCornerShape(8.dp))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, color = valueColor, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Text(label, color = Color(0xFF64748B), fontSize = 10.sp)
    }
}

@Composable
private fun AppIcon(packageName: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, packageName) {
        value = withContext(Dispatchers.IO) {
            try {
                context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }
    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, modifier = modifier)
    } else {
        Spacer(modifier = modifier)
    }
}
