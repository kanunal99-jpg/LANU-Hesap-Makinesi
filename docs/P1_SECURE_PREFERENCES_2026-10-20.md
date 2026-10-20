# LANU — Güvenli ayar ve olay kayıtları

Bu paket, eski sürümlerin Android Keystore/EncryptedSharedPreferences başlatılamadığında oluşturduğu **şifresiz yedek SharedPreferences dosyalarını** kaldırır.

- Üretimde biyometrik izin bayrağı artık düz metne kaydedilmez. Android Keystore kullanılamazsa `isBiometricEnabled()` false döner; biyometrinin etkinleştirilmesi hata verir. **PIN erişimi etkilenmez.**
- Güvenlik olayları yalnız `EncryptedSharedPreferences` içine yazılır. Şifreli depolama bozulursa olaylar en fazla 50 kayıtla geçici bellekte tutulur ve uygulama kapanınca unutulur; diske düz metin fallback yapılmaz.
- Uygulama açılışında yalnız `secure_settings_prefs_fallback` ve `secure_security_events_fallback` isimli tarihî **şifresiz yedek dosyalar** temizlenir. Mesaj geçmişi, Room, LANU iletişim kimliği ve gerçek şifreli SharedPreferences hedeflenmez.
- Testin gerçek Keystore yerine açıkça sahte prefs geçmesi, JUnit/Robolectric'in JVM'de güvenli donanımsal anahtara erişememesinden kaynaklanır. Bu kolaylık **üretim `AppContainer` kurulumunda kullanılmaz**.

**Kalan önemli konu:** PIN doğrulayıcısı PBKDF2'ye geçirilmiş olsa da 4 haneli PIN'in çevrimdışı tahmin direnci sınırlıdır. Android Keystore'a donanım bağımlı bağlama, Room'un verilerinin şifrelenmesi, gerçek cihaz sıfır/veri koruma testleri ve kalıcı APK imzası tamamlanmadan 90+ güvenlik notu verilmemelidir.

**İzleme:** Google/Firebase yapılandırması sağlanana kadar gerçek Crashlytics izleme/sunucudaki hata telemetrisi etkinleşmiş sayılmaz. Gerçek telefonla biyometrik on/off ve Keystore bozulma senaryoları hâlâ gerekli.
