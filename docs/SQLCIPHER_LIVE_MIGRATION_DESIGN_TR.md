# LANU — Room v4 tam SQLCipher canlı geçiş tasarımı (yalnız inceleme, 28.10.2026)

> **DURUM: TASARIM / GÜVENLİK İNCELEMESİ.** Bu PR **üretim kodunu değiştirmez**, migration başlatmaz, anahtar üretmez, veritabanına dokunmaz, APK yayımlamaz. `lanu_calculator_db` halen **plaintext SQLite**. İzole SQLCipher gölge testi (#291) yeşil olsa bile 95/100 kanıtı değildir.

## Güvenlik hedefi ve değişmezler

- Yedi Room v4 tablosundaki **bütün kayıtların tüm sütunları ve SQLite tipleri**, primary key'ler, foreign key'ler, index'ler, trigger'lar, `user_version=4` ve Room identity hash kayıpsız korunacak. Null/Blob/Float/Int/Text ayrımı, sıra-bağımsız canonical fingerprint ve satır sayısı eşleşecek.
- Kullanıcının isteğe bağlı PIN'i, gizli `2011.` alanı, hesap/rehber/mesaj/teslim kuyruğu/çağrı geçmişi, E2EE kimliği ve Android Keystore alias'ları **değişmeyecek**. Veritabanı anahtarı PIN'den, E2EE anahtarından veya APK imza anahtarından türetilmeyecek.
- `fallbackToDestructiveMigration`, otomatik silme, anahtar kaybında yeni anahtar üretme, bozuk dosyayı yeniden oluşturma, plaintext'e sessiz fallback, kullanıcıdan habersiz otomatik geçiş ve eski APK'ya güvenli olmayan downgrade **yasak**.
- Veri kaybı olasılığında **fail closed**: mevcut plaintext DB ve şifreli bağımsız yedek korunur; yeni DB aktif edilmez. Hata metni/logu kişisel içerik veya anahtar içermez.
- AndroidKeyStore anahtarını kaybetmek mümkündür. Keystore tek kurtarma yolu olamaz: kullanıcıya bağımsız, başka cihaza aktarılabilir, **kullanıcının gerçekten geri açabildiği şifreli yedek** sağlanmalı; yanlış parola ve eksik yedek reddedilmeli.

## Bağımlılık ve karar kapıları

1. #291'in **son HEAD** CI'ında Kotlin/Robolectric, Android 14 gerçek SQLCipher native debug+minified R8 instrumentation, yanlış anahtar, tam yedi tablo typed parity, kapanıp açılma ve orijinal hash değişmezliği **başarılı** olmalı. PR291 test-fixture kodu ile üretim cutover kodu ayrı PR'larda kalır.
2. Yeni canlı geçiş PR'ı **varsayılan kapalı** ve üretim açılışından ulaşılamaz olmalı; önce yalnız sentetik/izole snapshot ve hata enjeksiyonu kabul edilir. Gerçek kullanıcı DB'sine temas eden bir bayrak açılmadan bağımsız kod incelemesi ve açık kullanıcı onayı gerekir.
3. Eski plaintext .828 imzalı OTA'nın iki cihazda çalışması SQLCipher migration testi değildir. Android API 23/29/34/36, Samsung A55 ve ikinci gerçek OEM cihazda **gerçek geçiş** ayrıca test edilecek.
4. `main` ve PR CI, stable signer continuity, Android `adb install -r` veriyi koruyan OTA, GitHub Releases APK SHA256 ve public HTTP read-back **ayrı** kapılardır. Testlerin geçmesi otomatik merge/release izni vermez.

## İşlem sırası (tasarım; henüz uygulama yok)

### A. Ön denetim, kullanıcı yedeği ve disk bütçesi

- Uygulama, kullanıcının anlayacağı şekilde **veriler şifreli yedeklenmeden geçiş yapılamayacağını** açıklar; kullanıcıya bağımsız dosya ve geri yükleme doğrulaması sağlar. Backup AEAD, salt/KDF parametreleri, sürüm, tüm dosya checksum ve authenticated manifest ile taşınabilir olmalı; Android Keystore'a **tek başına** bağlı olmamalı. Kullanıcının belirlediği güçlü kurtarma parolası veya bağımsız kurtarma anahtarı gereklidir; anahtar veya kişisel veri telemetry'ye çıkmaz.
- Yedek sadece dosyanın yazılmasıyla tamam sayılmaz: yeni/izole veritabanında **gerçek restore + typed parity + schema/identity + integrity check** yapılır. Geri yükleme mekanizması kullanıcı erişilebilir olmalı. Yedek dosyası cihaz dışında saklanabildiği doğrulanır; cihaz içinde kalan tek kopya bağımsız değildir.
- Disk bütçesi taşma güvenli hesaplanır: plaintext main+WAL+SHM, encrypted staging+WAL, bağımsız yedek, geçici journal ve rezerv alanının toplamı; `StatFs` boş alanı ile gerçek dosya boyları okunur. Disk dolu/izin hatası/eksik yedek **geçişi başlatmadan** reddedilir.

### B. Tüm yazıcıları durdur, tutarlı snapshot al

- Mesaj sync, background worker, foreground call, DAO, backup/restore ve UI dahil bütün Room yazıcıları tek migration gate altında **durur ve bekler**. Aktif görüşme veya bekleyen teslim kuyruğu varken kesintisiz yazım iddiası yok; kullanıcıya erteleme sunulur.
- Açık Room transaction ve DB handle'ları kontrollü kapanır; `wal_checkpoint(TRUNCATE)` sonucu `busy=0` ve WAL/SHM tutarlılığı doğrulanır. WAL dosyası salt kopyalanmaz, eski DB'nin main dosyası da açık yazıcı varken kopyalanmaz. Journal/SHM anomalisinde fail closed.
- Kaynak DB `PRAGMA quick_check`, `integrity_check`, `foreign_key_check`, Room v4 schema identity ve typed fingerprint ile doğrulanır. Bu fingerprint'ler yalnız işlem içi veya şifreli/kimliği doğrulanmış manifestte tutulur; hassas satırlar loglanmaz.

### C. İzole, geri alınabilir SQLCipher export

- Canlı `lanu_calculator_db` dosyası SQLCipher ile **asla writable açılmaz**. Dondurulmuş snapshot ayrı ve özel adla oluşturulur, fsync edilir; `sqlcipher_export` yalnız bu snapshot'tan **ayrı yeni encrypted staging** dosyasına çalışır. Desteklenmeyen SQLite/SQLCipher `ATTACH` yolu veya R8 ABI hatası durumunda aktif DB değişmez.
- Yeni 32-byte rastgele SQLCipher anahtarı AndroidKeyStore AES-GCM ile sarılır; ciphertext envelope ve dosya yalnız doğrulanmış durable commit sonrası etkinleştirilir. Yanlış, eksik, bozuk wrap/alias kesinlikle fail closed. `noBackupFilesDir` ve bağımsız kurtarma yedeği politikası çelişmeyecek.
- SQLCipher açılışı, `cipher_integrity_check` (hatasız sonuç için **0 hata satırı**), `integrity_check=ok`, `foreign_key_check`, Room identity, `user_version`, yedi tablo tüm typed row parity, pending E2EE ciphertext ve PIN verifier eşliğiyle doğrulanır. Şifreli dosyanın ilk 16 byte'ı plaintext SQLite header olmamalı. Kapat/aç/yanlış anahtar/anahtar kaybı test edilir.

### D. Crash-safe aktivasyon ve geri dönüş

- **Çok dosyalı rename tek atomik transaction değildir.** Plaintext `lanu_calculator_db` üzerinde silme/overwrite yerine versiyonlu encrypted dosya ve küçük, checksum/MAC korumalı, fsync edilmiş **aktif-DB seçim kaydı** kullanılır. Tek dosyalık atomic rename, parent directory fsync ve idempotent recovery mantığı ayrıca cihaz/FS üzerinde sınanır.
- Kalıcı durum makinesi: `PLAIN_ACTIVE` → `BACKUP_VERIFIED` → `WRITERS_QUIESCED` → `EXPORT_VERIFIED` → `ACTIVATION_PREPARED` → `ENCRYPTED_ACTIVE` → `FINALIZED`. Her durum için kill/power loss, yeniden başlatma, stale marker ve disk dolması davranışı belirlenir. Commit marker güvenilir değilse **aktif DB'yi tahmin ederek açma yok**.
- Aktivasyondan önce kaynak plaintext DB değişmez ve rollback mümkündür. Aktivasyondan sonra yeni şifreli DB'ye yazılmış kayıtları eski plaintext'e geri döndürmek veri kaybettirebilir; otomatik downgrade/rollback **yasak**. Kurtarma için şifreli yedekten restore, veri uzlaştırma ve kullanıcı onayı gerekir.
- Plaintext kaynak/yedek dosyası cihazda kaldığı sürece **tam disk-at-rest şifreleme iddiası yoktur**. Silme kararı ancak bağımsız kurtarılabilir şifreli yedek ve yeni DB kabulünden sonra kullanıcı onayıyla verilir; flash üzerinde güvenli fiziksel silme garanti edilemez. Eski sürümün plaintext dosyayı açmasını engelleyen downgrade/OTA güvenlik kapısı zorunludur.

## Zorunlu olumsuz test matrisi (kod PR'ı gelmeden kabul edilmez)

- Sentetik yedi tablo: boş/çok satırlı, BLOB/null/Unicode, büyük kayıt, FK/trigger, çakışan PK, pending queue, kullanıcı PIN verifier ve E2EE ciphertext eşliği.
- WAL checkpoint busy, SHM/journal anomali, eşzamanlı Room writer, worker restart, active call, kayıp ACK ve stale migration marker.
- Disk dolması **her yazım/fsync/rename** noktasında, permission denied, partial copy, kesilmiş export, corruption, bozuk/eksik wrapped key, kayıp Keystore alias, yanlış parola, kısmi yedek, restore sırasında eksik kayıt.
- Uygulama öldürme ve power-loss benzetimi **her durum geçişinde**, yeniden açma, ikinci kez migration çağrısı, eski APK `adb install -r` downgrade reddi, OTA imza eşliği.
- API23/29/34/36 + debug ve minified R8 release instrumentation, SQLCipher native `libsqlcipher.so` ABI/16KiB page, Room schema v4 ve fiziksel A55 + ikinci OEM. CI emülatörü fiziksel kabul yerine geçmez.
- Her testte değişmezler: kaynağın checksum'u, yedek restore edilebilirliği, yedi tablo typed parity, PIN/E2EE anahtarları, kararlı signer, mahrem loglar. Testi susturmak veya başarısız sonucu başarılı saymak yasaktır.

## Açık kararlar / riskler

- Kullanıcıya bağımsız şifreli yedeği **nasıl dışa aktaracağı ve kurtarma sırrını nasıl saklayacağı** tasarlanmalı; uygulama bunu kullanıcıdan gizleyerek otomatikleştiremez.
- Room singleton'ın sürüm işaretçisi ve eski sürümle uyumluluğu; aktif çağrı/worker yazıcılarını eksiksiz durdurma; Keystore kaybı sonrası yalnız doğrulanmış bağımsız yedekten kurtarma; plaintext artıkları ve Android backup kuralları ayrı inceleme gerektirir.
- **Kabul sonucu:** Bu doküman plan kanıtıdır, kod/CI/cihaz/95 puan kanıtı değildir. Canlı migration ayrı PR ve ayrı açık onayla etkinleştirilir.
