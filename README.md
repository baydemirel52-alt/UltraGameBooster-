# Ultra Game Engine

Android için oyun performans aracı. Sistem seviyesinde çalışır (tek bir oyuna özel config dosyası düzenlemez) ve
yaptığı her şeyi, gerçekten yapıp yapmadığını kontrol ederek raporlar.

## Gerçekçi beklenti

Hiçbir uygulama telefonun donanımını hızlandıramaz. Bu uygulama şunu yapar: oyunun önündeki **engelleri** kaldırır
(aşırı yük, kesinti, kısıtlama) ve sana neyin işe yaradığını **ölçerek** gösterir. FPS'e en çok etki eden üç şey:
GPU yükünü düşürmek (çözünürlük), ısınmayı yönetmek ve Pil Tasarrufu'nu kapatmak.

## Özellikler

| Özellik | Shizuku? | Gerçek etkisi |
|---|---|---|
| Arka plan temizliği + ölçülen RAM farkı | Hayır (Shizuku varsa `am kill-all` da) | Orta/düşük. Android arka planı zaten yönetir; rapor ne kadar kazandığını gösterir. |
| Rahatsız Etmeyin (önceki durumu geri yüklenir) | Hayır | Oyun sırasında bildirim kesintisini önler. |
| Düşük gecikmeli Wi-Fi kilidi | Hayır | Cihaza göre değişir; yalnızca Wi-Fi bağlıyken. |
| Çözünürlük ölçekleme (%100–%70) | Evet | **En güçlü etki.** GPU yükünü düşürür, sıcaklığı azaltır. |
| Yenileme hızı kilidi (cihazın desteklediği Hz) | Evet | Cihaza/oyuna göre değişir; bazı üreticiler yok sayar. |
| Android Game Mode (Performans) | Evet (Android 12+) | Yalnızca oyun destekliyorsa. |
| Canlı HUD: ping, CPU/pil sıcaklığı, Android termal durumu | CPU sıcaklığı için evet | Isınma kaynaklı FPS düşüşünü görmeni sağlar. |
| Pil tasarrufu / ısınma uyarıları | Hayır | FPS düşüşünün en sık gerçek nedenleri. |
| Oyun kapanınca ayarları otomatik sıfırlama | "Kullanım erişimi" izni | Çözünürlük/Hz/DND'nin takılı kalmasını önler. |
| Hazır profiller (Dengeli / Performans / Maksimum) | — | Tek dokunuşla ayar seti. |

## Kurulum

1. Uygulamayı kur, "Üstte göster" ve bildirim izinlerini ver.
2. Çözünürlük ve Hz kilidi için **Shizuku**: Play Store'dan indir → Geliştirici Seçenekleri → Kablosuz Hata Ayıklama ile
   başlat → uygulamadaki "Yetki Bağla"ya bas. (Telefon yeniden başlayınca Shizuku'yu tekrar başlatman gerekir.)
3. Oyunun kapanınca ayarların otomatik dönmesi için Ayarlar → Kullanım erişimi'nden izin ver (isteğe bağlı).

## Ekran çözünürlüğü takılı kalırsa

Uygulamadaki **ACİL SIFIRLA** düğmesine ya da bildirimdeki "AYARLARI SIFIRLA & KAPAT" işlemine bas (Shizuku açık olmalı).
Bilgisayardan: `adb shell wm size reset` ve `adb shell wm density reset`.
Not: Çözünürlük değişikliği yeniden başlatmada kalıcıdır ve Shizuku telefon açılınca kendiliğinden başlamaz.

## Derleme ve imza

GitHub Actions (`.github/workflows/build.yml`) AAB ve APK üretir. **Sabit imza anahtarı olmadan** her derleme yeni bir
anahtar üretir; bu yüzden kurulu uygulamanın üzerine güncelleme yapılamaz ve Play Store yüklemesi reddedilir.
Bir kez kendi anahtarını oluştur ve repo → Settings → Secrets and variables → Actions içine ekle:

- `KEYSTORE_BASE64` — keystore dosyasının base64 hali
- `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`

Anahtarı kaybetme ve kimseyle paylaşma; kaybedersen uygulamayı güncelleyemezsin.

## Play Store öncesi kontrol listesi

- [ ] AdMob **test** kimlikleri hâlâ kodda (`MonetizationManager.kt`, `MainActivity.kt`, `AndroidManifest.xml`). Gerçek kimliklerle
      değiştirmeden reklam geliri olmaz.
- [ ] Play Console'da `ultra_game_engine_pro_lifetime` ürününü oluştur.
- [ ] Ön plan servisi (özel kullanım) ve `PACKAGE_USAGE_STATS` izni için Play Console beyanlarını kontrol et.
- [ ] Sabit imza anahtarını (yukarıda) ayarla.

## Dosyalar

- `SystemOptimizer.kt` — optimizasyon ve geri alma mantığı, Shizuku komutları, cihaz durumu
- `GameOverlayService.kt` — canlı HUD ve oyun kapanınca otomatik sıfırlama
- `MainActivity.kt` — arayüz
- `MonetizationManager.kt` — reklam ve Pro satın alma
- `EmergencyRestoreReceiver.kt` — acil sıfırlama ve açılışta geri alma
