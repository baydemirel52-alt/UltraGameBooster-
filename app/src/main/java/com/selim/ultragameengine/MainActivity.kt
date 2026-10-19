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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {

    private lateinit var monetizationManager: MonetizationManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EngineManager.backupOriginalDisplay(this)
        EngineManager.installCrashSafetyRestore(this)
        monetizationManager = MonetizationManager(this)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFF00E5FF),
                    secondary = Color(0xFF7C4DFF),
                    background = Color(0xFF080B12),
                    surface = Color(0xFF121824)
                )
            ) {
                UltraEngineMainScreen()
            }
        }
    }

    @Composable
    fun UltraEngineMainScreen() {
        val scope = rememberCoroutineScope()
        val isProUnlocked by monetizationManager.isProUnlocked.collectAsState()
        var shizukuReady by remember { mutableStateOf(EngineManager.isShizukuReady()) }
        var installedGames by remember { mutableStateOf(emptyList<GameAppInfo>()) }
        var selectedGame by remember { mutableStateOf<GameAppInfo?>(null) }

        // Profil Ayarları
        var resolutionScale by remember { mutableFloatStateOf(1.0f) }
        var targetHz by remember { mutableIntStateOf(120) }
        var enableDnd by remember { mutableStateOf(true) }
        var lowLatencyWifi by remember { mutableStateOf(true) }
        var statusLog by remember { mutableStateOf("Motor hazır. Oyununuzu seçip optimizasyonu başlatın.") }
        var showShizukuWizard by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            installedGames = EngineManager.getInstalledGames(this@MainActivity)
            if (installedGames.isNotEmpty()) {
                selectedGame = installedGames.first()
                val prof = EngineManager.loadProfile(this@MainActivity, installedGames.first().packageName)
                resolutionScale = prof.resolutionScale
                targetHz = prof.targetHz
                enableDnd = prof.enableDnd
                lowLatencyWifi = prof.lowLatencyWifi
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF080B12))
                .padding(16.dp)
        ) {
            // ÜST BAŞLIK & ACİL EKRAN SIFIRLAMA BUTONU
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "ULTRA GAME ENGINE",
                        color = Color(0xFF00E5FF),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Text(
                        text = if (shizukuReady) "MOD: ULTRA DONANIM (SHIZUKU AKTİF)" else "MOD: STANDART OPTİMİZASYON",
                        color = if (shizukuReady) Color(0xFF00E676) else Color(0xFFFFB300),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = {
                        val msg = EngineManager.restoreOriginalSystemSettings(this@MainActivity)
                        stopService(Intent(this@MainActivity, GameOverlayService::class.java))
                        statusLog = msg
                        Toast.makeText(this@MainActivity, msg, Toast.LENGTH_SHORT).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("ACİL SIFIRLA", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // SHIZUKU KURULUM SİHİRBAZI KARTI (Normal Kullanıcılar İçin Rehber)
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF121824)),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(12.dp))
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (shizukuReady) "✅ Shizuku Donanım Yetkisi Bağlı" else "⚡ Donanım Kilidini Aç (Shizuku Rehberi)",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                        TextButton(onClick = {
                            shizukuReady = EngineManager.isShizukuReady()
                            showShizukuWizard = !showShizukuWizard
                        }) {
                            Text(if (showShizukuWizard) "Gizle" else "Kurulumu Gör", color = Color(0xFF00E5FF))
                        }
                    }

                    if (showShizukuWizard) {
                        Text(
                            text = "Çözünürlük ölçekleme ve gerçek FPS ölçümü için Rotsuz ADB yetkisi gerekir:\n" +
                                    "1. Shizuku uygulamasını indirip açın.\n" +
                                    "2. Geliştirici Seçenekleri -> Kablosuz Hata Ayıklama ile Shizuku'yu başlatın.\n" +
                                    "3. 'Yetki İste' butonuna basıp Ultra Motoru ateşleyin.",
                            color = Color(0xFF94A3B8),
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api")))
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("1. Shizuku İndir", fontSize = 11.sp)
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
                                Text("2. Geliştirici Ayarı", fontSize = 11.sp)
                            }
                            Button(
                                onClick = {
                                    try {
                                        if (Shizuku.pingBinder()) {
                                            Shizuku.requestPermission(1001)
                                        } else {
                                            Toast.makeText(this@MainActivity, "Önce Shizuku uygulamasını başlatın.", Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (_: Exception) {}
                                    shizukuReady = EngineManager.isShizukuReady()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("3. Yetki Bağla", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // PRO / ÖDÜLLÜ REKLAM DURUM BARI (Para Kazanma Modülü)
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A142E)),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF7C4DFF), RoundedCornerShape(12.dp))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isProUnlocked) "🔥 ULTRA PRO AKTİF (${monetizationManager.getRemainingRewardMinutes()} dk)" else "🔒 ULTRA DONANIM AYARLARI KİLİTLİ",
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
                            onClick = {
                                monetizationManager.showRewardedAdFor3Hours(this@MainActivity) {}
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("Reklam İzle (3s)", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = {
                                monetizationManager.launchProPurchaseFlow(this@MainActivity)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7C4DFF)),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text("PRO Al", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // ORTA ALAN: OYUN SEÇİCİ VE PERFORMANS KONTROLLERİ
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text("1. Optimize Edilecek Oyunu Seçin:", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                items(installedGames.take(6)) { game ->
                    val isSelected = selectedGame?.packageName == game.packageName
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (isSelected) Color(0xFF1E293B) else Color(0xFF121824),
                                RoundedCornerShape(8.dp)
                            )
                            .border(
                                1.dp,
                                if (isSelected) Color(0xFF00E5FF) else Color.Transparent,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable {
                                selectedGame = game
                                val prof = EngineManager.loadProfile(this@MainActivity, game.packageName)
                                resolutionScale = prof.resolutionScale
                                targetHz = prof.targetHz
                                enableDnd = prof.enableDnd
                                lowLatencyWifi = prof.lowLatencyWifi
                            }
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(game.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(game.packageName, color = Color(0xFF64748B), fontSize = 11.sp)
                        }
                        if (isSelected) {
                            Text("SEÇİLİ", color = Color(0xFF00E5FF), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF121824)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("2. Standart Optimizasyonlar (Shizuku Gerekmez)", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Oyun İçi Rahatsız Etmeyin (Bildirim Kapat)", color = Color.White, fontSize = 12.sp)
                                Switch(checked = enableDnd, onCheckedChange = {
                                    val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                                    if (!nm.isNotificationPolicyAccessGranted) {
                                        startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                                    }
                                    enableDnd = it
                                })
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Düşük Gecikmeli Wi-Fi Donanım Kilidi", color = Color.White, fontSize = 12.sp)
                                Switch(checked = lowLatencyWifi, onCheckedChange = { lowLatencyWifi = it })
                            }
                        }
                    }
                }

                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF121824)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("3. Ultra Donanım Motoru (Android Game API & Shizuku)", color = Color(0xFF00E5FF), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Çözünürlük Ölçekleme: %${(resolutionScale * 100).toInt()} (GPU Yükünü Düşürür)",
                                color = Color.White,
                                fontSize = 12.sp
                            )
                            Slider(
                                value = resolutionScale,
                                onValueChange = { resolutionScale = it },
                                valueRange = 0.70f..1.0f,
                                steps = 5
                            )

                            Text("Hedef Ekran Yenileme Hızı (Hz Sabitleme):", color = Color.White, fontSize = 12.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf(60, 90, 120).forEach { hz ->
                                    FilterChip(
                                        selected = targetHz == hz,
                                        onClick = { targetHz = hz },
                                        label = { Text("$hz Hz") }
                                    )
                                }
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = statusLog,
                        color = Color(0xFF94A3B8),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // MOTORU ATEŞLE & OYUNU BAŞLAT BUTONU
            Button(
                onClick = {
                    if (!Settings.canDrawOverlays(this@MainActivity)) {
                        Toast.makeText(this@MainActivity, "HUD göstergesi için 'Üstte Göster' izni verin.", Toast.LENGTH_LONG).show()
                        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
                        return@Button
                    }

                    // Eğer kullanıcı %100 altı çözünürlük seçtiyse ve Pro/Reklam izni yoksa uyar
                    if (resolutionScale < 0.99f && !monetizationManager.checkAccessStatus()) {
                        Toast.makeText(this@MainActivity, "Donanım ölçekleme için yukarıdan 1 kısa reklam izleyin veya Pro'ya geçin!", Toast.LENGTH_LONG).show()
                        return@Button
                    }

                    val pkg = selectedGame?.packageName ?: ""
                    val profile = GameProfile(
                        packageName = pkg,
                        resolutionScale = resolutionScale,
                        targetHz = targetHz,
                        enableDnd = enableDnd,
                        lowLatencyWifi = lowLatencyWifi
                    )
                    if (pkg.isNotEmpty()) {
                        EngineManager.saveProfile(this@MainActivity, profile)
                    }

                    // Telemetri HUD Servisini Başlat
                    val hudIntent = Intent(this@MainActivity, GameOverlayService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(hudIntent)
                    } else {
                        startService(hudIntent)
                    }

                    scope.launch {
                        statusLog = EngineManager.applyOptimizationProfile(this@MainActivity, profile)
                        if (pkg.isNotEmpty()) {
                            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                            if (launchIntent != null) {
                                startActivity(launchIntent)
                            }
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF)),
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

            // ANA EKRAN ALT BANNER REKLAM ALANI (Sadece Ana Menüde Durur, Oyunda Asla Çıkmaz)
            AndroidView(
                modifier = Modifier.fillMaxWidth(),
                factory = { ctx ->
                    AdView(ctx).apply {
                        setAdSize(AdSize.BANNER)
                        // Resmi Google Test Banner ID (Yayınlarken kendi ID'ni koyacaksın)
                        adUnitId = "ca-app-pub-3940256099942544/6300978111"
                        loadAd(AdRequest.Builder().build())
                    }
                }
            )
        }
    }
}
