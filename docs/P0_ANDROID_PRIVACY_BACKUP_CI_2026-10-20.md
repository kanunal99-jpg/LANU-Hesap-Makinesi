# P0 BACKUP-GUARD-01 — Android Keystore veri gizliliği CI doğrulaması

Tarih: 20 Ekim 2026. Durum: CI kanıtı bekleniyor.

Eski Android otomatik yedekleme ve Android 12+ cloud/device-transfer kuralları tüm özel LANU uygulama verisini hariç tutmak zorundadır. Keystore'a bağlı şifreli kimlik/veri ve düz Room veritabanı bulut yedeğine taşınmamalıdır. OTA FileProvider dışa açık olmamalıdır.

`scripts/verify_android_privacy.py` ve `scripts/test_verify_android_privacy.py` her PR'da çalışan kalite kapısıdır. XML'de `allowBackup=false`, `fullBackupContent`, `dataExtractionRules`, cloud/device-transfer tam kök hariç tutma ve `FileProvider exported=false` denetlenir. Hata durumunda build iş akışı başarısızdır.

Bu statik koruma SQLCipher bütünsel veri şifrelemesi, gerçek iki-cihaz migration testleri, eski SQLite şema kurtarma ve kalıcı APK sertifikası sorunlarını çözmez. Bunlar hâlâ açık P0 kabul maddeleridir.

Test sonucundan sonra anayasanın ana tablosu ve toplu puanlama ayrıca güncellenecektir.
