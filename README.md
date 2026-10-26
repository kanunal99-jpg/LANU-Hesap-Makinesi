# LANU Hesap Makinesi

Android 6.0 (API 23)+ hedefleyen, Jetpack Compose tabanlı hesap makinesi ve kullanıcı seçimiyle açılan özel iletişim alanı. Bu depo gerçek ürün kodu, Android CI testleri, Supabase relay servis kaynağı ve güvenlik geliştirme kayıtlarını içerir.

## Mevcut işlevler

- Normal/bilimsel ve finansal hesaplama, geçmiş ve bellek işlemleri.
- `2011.` gizli giriş yöntemi; **PIN kullanıcı tercihine bağlıdır**. PIN açma, PIN kapatma (var olan PIN ile doğrulama) ve yeniden açma korunur. PIN kapalıyken bu girişi bilen başka biri özel alanı açabilir.
- Uzun ömürlü kimlik anahtarlarıyla ECDH + AES-GCM uçtan uca şifreli metin mesajları, şifreli taslak/kuyruk, kişi yönetimi.
- Küçük boyutlu şifreli fotoğraf/PDF/TXT ekleri (**18 KB sınır**); büyük medyanın tam upload/download özelliği **henüz etkin değil**.
- Kullanıcı kontrollü cihaz içi OTA güncelleme; Github Releases'te APK + SHA-256 + imza raporu, sertifika ve sürüm numarası kontrolü.
- Kullanıcı seçimiyle tam veri ve kimlik yedeği; otomatik Android uygulama yedeği gizli alan verileri için kapalı.

## Teknoloji

Kotlin, Jetpack Compose, Hilt, MVVM/ViewModel + Coroutines/Flow, Room v4, R8/ProGuard release, API 23 minimum/API 36 hedef. Mesaj teslimi **Supabase relay** üzerinden yapılır; sunucuda uçtan uca açık metin mesaj saklanmaz. Bu sistem tamamen sunucusuz veya garantili arka plan push bağlantısı olarak **tanıtılmamalıdır**. Eski `CallScreen.kt` ekranı **demo/simülasyondur**; yeni `NativeCallHost.kt` + `NativeCallEngine.kt` ön planda gerçek WebRTC ses/video yakalama, ICE/DTLS ve RTP kanıtına dayalı bağlantı uygular. **Bunun iki farklı fiziksel telefonda karşılıklı ses/video kabulü henüz doğrulanmamıştır**; tüm ağlarda veya arka planda çağrı garantisi verilmez.

## Güvenlik ve bilinmesi gereken sınırlar

- **Room veritabanı halen standart SQLite'tır; tüm tablo/WAL verileri SQLCipher ile şifrelenmemiştir.** Mesaj gövdeleri ve kimlik anahtarları ayrı şifreleme sistemleri kullanır. Kayıpsız DB migration yalnız plan/preflight aşamasındadır.
- Şu anki uzun süreli statik ECDH anahtarları Double Ratchet seviyesinde **forward secrecy sağlamaz**.
- Stable APK imza anahtarının gerçek **cihaz dışı şifreli yedek/geri yükleme kabulü** eksiktir (#83). Anahtar/parola sohbet, issue, repoya eklenmemelidir.
- Telefonun ekran kilidi, PIN tercihleri ve şifreli tam yedekler kullanıcı tarafından korunmalıdır.
- Android emülatör ve Robolectric testleri mevcut; tüm Samsung/Xiaomi/Huawei/Honor/Google Pixel sürümlerinde sorunsuz fiziksel sertifika verilemez. APK güncellerken verileri veya uygulamayı silmeyin.
- Çökme tanılamasında PIN, özel anahtar, mesaj veya ham stacktrace gönderilmez; uzaktan telemetri gizlilik/onay olmadan açılmaz.

## Yapı ve doğrulama

- `app/`: Android uygulaması, Compose ekranları, Room veri katmanı, kripto, mesajlaşma, OTA.
- `server/`: relay/sunucu protokol testleri ve PostgreSQL güvenlik testleri.
- `supabase/`: Edge Function ve SQL migration sürümleri.
- `scripts/`: APK yayın, imza kurtarma ve regresyon doğrulama araçları.
- `docs/`: gizlilik, veri yedeği, büyük medya, Android uyumluluğu, SQLCipher kayıpsız geçişi.
- `PROJE_ANAYASASI_VE_DUZELTILECEKLER.md`: **öncelikler, kanıtlar, açıklar ve kullanıcı kararları**.

Kod/test doğrulama (JDK17, Android SDK, Gradle 9.3.1 ve Node 24 kurulu geliştirme ortamında):

```bash
npm ci --prefix server && npm test --prefix server
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

GitHub Actions ayrıca Android API 34 emülatöründe debug ve release instrumented testlerini çalıştırır. Ana dalın başarılı CI koşuları, koşuya ait imzalı APK'yı GitHub Releases altında yayımlar ve kamuya açık indirmeyi yeniden doğrular. Dosya adının tarihsel uyumluluk nedeniyle `app-debug.apk` olması tek başına debuggable build anlamına gelmez; imza raporu ve release kontrolleri esastır.

## Geliştirme öncelikleri

1. Veri silmeden PIN tercihi, Room/kimlik ve OTA devamlılığını koru.
2. İmza anahtarını gerçek cihaz dışı güvenli geri yüklemeyle doğrula.
3. SQLCipher için önce salt okunur ön denetim ve reversibl migration testleri, ardından kontrollü geçiş.
4. Android çoklu cihaz kararlılığı; bağımsız kripto ve forward secrecy tasarımı.
5. Gerçek büyük medya; GMS/HMS bağımsız arka plan teslim stratejisi.
6. Native WebRTC çağrısının iki farklı fiziksel üreticide ses/video ve yönlendirme kabulünü kanıtla; eski demo ekranını gerçek görüşmeyle karıştırma. Premium arayüz, hesap doğruluğu ve erişilebilirliği regresyon testleriyle geliştir.

Ücretli altyapı kullanıcı onayı olmadan açılmaz. Birim testleri, CI onayı ve **gerçek fiziksel cihaz kabulü farklı kanıt seviyeleridir**.

[Proje Anayasası](PROJE_ANAYASASI_VE_DUZELTILECEKLER.md) · [SQLCipher Kayıpsız Geçiş Planı](docs/ROOM_SQLCIPHER_LOSSLESS_MIGRATION_TR.md) · [Android Genel Uyumluluk](docs/ANDROID_GENERAL_COMPATIBILITY_ACCEPTANCE_TR.md)
