# P0 — Android çalışma zamanı Room veri koruma kalite kapısı (21.10.2026)

## Kapsam ve dürüst güvenlik sınırı
Bu iş **tam SQLCipher şifreleme**, geçmiş Room v1-v3 için migration veya üç farklı fiziksel marka üzerinde kabul testi değildir. Gerçek Room v1-v3 tarihî fixture'ları bulunmadan sahte migration yazmak yasaktır. Kişisel veriler silinemez, kullanıcı uygulamayı kaldırmaya yönlendirilemez.

## Kod
- `app/src/androidTest/java/com/example/RoomDevicePreservationTest.kt`: cihaz/emülatör üzerinde izole veritabanıyla 4 senaryo.
- Güncel Room v4'ün **yedi tablosu** için kayıt/close/reopen ve PIN, kimlik eşlemesi, profil, rehber, sohbet, bekleyen mesaj, hesap geçmişi, çağrı geçmişi doğrulaması.
- Bilinmeyen `user_version=3` eski SQLite test dosyasında güvenli hata ve sentinel satırın silinmeden kalması. Bu, gerçek tarihî v3->v4 migration testinin yerine geçmez.
- Simüle edilmiş işlem kesintisi için `Room.withTransaction` atomik rollback.
- `android:allowBackup=false` politikası kurulu APK üzerinde runtime denetimi.

## CI / yayın kuralı
`.github/workflows/android.yml` `Android Runtime Database Acceptance (API 34)` işini ekler.
Android 14 / API 34 emulator üzerinde `:app:connectedDebugAndroidTest` çalıştırılır.
`Publish verified APK` işi hem mevcut Unit/Robolectric/lint/build kontrolleri hem runtime instrumented testleri **başarılı** olmadan çalışamaz.
CI emulatoru **fiziksel Samsung/Xiaomi/Huawei** kabulü olarak gösterilemez.

## Kalan yayın engelleri
1. Gerçek geçmiş v4 APK/db örneğiyle yükseltme; dış v1-v3 örnekleri varsa kimliği doğrulandıktan sonra migration.
2. SQLCipher/benzeri tüm veritabanı, WAL, eski plaintext izleri, güç kesintisi, encrypted backup ve key-loss recovery.
3. Korunan GitHub signing secret'larının beşi, iki ardışık aynı imzalı APK, `adb install -r` ile veri kayıpsız yükseltme.
4. Samsung A55, Xiaomi ve Huawei fiziksel cihaz kabulü; canlı iki uç E2EE, offline ve OTA.
5. Güvenli veri kurtarma yolu kanıtlanana kadar hiçbir plaintext->encrypted otomatik dönüşümü aktif etmeme.

## Doğrulama
Bu dosyanın bulunması testi geçtiği anlamına gelmez. Yeni PR ve main CI'nın ilgili iş sonuçları ayrıca kanıtlanır.
