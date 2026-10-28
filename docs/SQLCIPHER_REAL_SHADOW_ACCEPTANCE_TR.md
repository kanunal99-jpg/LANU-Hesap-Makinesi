# LANU — Gerçek SQLCipher Room v4 şifreli gölge kabulü (28.10.2026)

**Hedef:** Kullanıcının tam veritabanı şifrelemesinde en az 95/100 isteğine doğru kanıta dayalı ilerlemek. **Bu dal, üretim `lanu_calculator_db` dosyasını şifrelemiyor; mevcut kullanıcı verisine hiçbir dönüşüm, silme, otomatik anahtar üretimi veya geçiş yapmıyor.**

## Kanıt için kurulan kod

- Resmi Zetetic açık kaynak Community Edition `net.zetetic:sqlcipher-android:4.20.0` ve AndroidX SQLite 2.7.0; minSdk23 desteklenir. Kaynak: https://www.zetetic.net/sqlcipher/sqlcipher-for-android-community/ ve https://github.com/sqlcipher/sqlcipher-android .
- `IsolatedSqlcipherRoomFactory`, yalnız `lanu_sqlcipher_shadow_test_*.db` ismindeki ayrı dosyaları açar. Var olan `lanu_calculator_db`, WAL, SHM, journal ve uygulamanın normal Room bağlantı factory'si **değişmedi**.
- `IsolatedSqlcipherShadowKeyVault` 32 rastgele byte anahtar üretir; 256-bit AES-GCM Android Keystore anahtarıyla sarar; yalnız sarılmış 60-byte kimlikli kayıt `noBackupFilesDir` içinde tutulur. Anahtar dosyası bozuksa, Keystore anahtarı yoksa veya gölge dosyası mevcutken sarılmış anahtar kaybolduysa **yeni anahtar oluşturarak eskisini kaybetmez**.
- `SqlcipherShadowRoomAcceptanceTest` Android 14 debug + R8 release testlerinde gerçek sentetik plaintext Room v4 yedi tablosu ve mesaj/PIN örneklerini ayrı SQLCipher gölge dosyasına kopyalar; SQL sütun türüne duyarlı yedi tablo sayısı + SHA256 fingerprint eşliğini, en az bir kez kapatıp yeniden açmayı, header'ın plaintext SQLite olmamasını, yanlış anahtar ve tahrif edilen GCM etiketinin reddini, orijinal fixture dosyasının hash'inin değişmemesini doğrular.
- `PreserveCorruptRoomFactory` SQLCipher açık yardımcıya sarıldığından anahtar bozulmasında varsayılan AndroidX corruption-delete geri kazanımı kullanılmaz.

## Bu testin kanıtlamadığı noktalar

- Gerçek mevcut kullanıcı verilerinin yerinde SQLCipher geçişi, `AppDatabase.getDatabase` üzerinde etkin **değildir**. Üretimdeki SQLite hâlâ plaintext; mesaj içeriği E2EE olsa da tablo isimleri, PIN verifier ve bazı metadata kayıtları dosyada açık kalabilir.
- AndroidKeyStore şifreleme API'sı, cihazın her zaman donanımsal StrongBox/TEE anahtarı kullandığını kanıtlamaz.
- Şifreli gölge fixture, gerçek çalışan uygulamanın WAL checkpoint, çoklu yazıcı, disk dolması, sistemin kill edilmesi, eski APK downgrade veya kurtarma için sahibi tarafından açılmış bağımsız tam yedek yerine geçmez.
- Üretim kesintisiz cutover için güvenli geri dönüş/izole disk bütçesi, tüm 7 tablo ve tip eşliği, Keystore anahtar kurtarma stratejisi, iki fiziksel OEM cihazında test, uygulama yeniden başlatma ve son olarak imzalı APK/HTTP SHA doğrulaması zorunludur. Bir önceki iki cihaz OTA başarısı şifreleme geçişinin testi **değildir**.

**Yayın kuralı:** CI başarısızsa PR merge yok. Gölge test yeşil ve APK yayımlanmış olsa dahi, kullanıcıya 'tam veritabanı şifreli' veya '95/100 kanıtlandı' denmez. Ayrı ve yeniden denetlenmiş canlı cutover gereklidir.
