# LANU — SQLCipher / Room kayıpsız geçiş tasarımı

## 28.10.2026 — Canlı Room v4 bozulma halinde veri koruma katmanı

- Kök neden: AndroidX `SupportSQLiteOpenHelper.Callback.onCorruption` varsayılan davranışında veritabanı ve yan dosyalar otomatik silinebilir. Sadece `fallbackToDestructiveMigration` kullanmamak buna karşı yeterli değildir.
- `AppDatabase.getDatabase()` artık `PreserveCorruptRoomFactory` ile Room'un otomatik bozulma-silme callback'ini engeller. Room'un oluşturma/açma/şema doğrulama/yükseltme callback'leri **aynen** yönlendirilir; bozulmuş bağlantı kapatılmaya çalışılır, dosya veya WAL/SHM/journal silinmez.
- Factory, `allowDataLossOnRecovery=false` değerini zorunlu kılar; hiçbir DB migration, şema sürümü, anahtar, PIN, 2011. gizli giriş, mesaj, kişi, medya veya imza değiştirilmez. Corruption, başarılı veri kurtarma anlamına gelmez: mevcut veriler korunduğu sürece uygulama veritabanını kullanamayabilir. Önce bağımsız şifreli yedek ve kontrollü kurtarma kabulü gerekir.
- İzole SQLite fixture testleri bozulma callback'i, normal callback delegasyonu, veri kalıcılığı ve otomatik data-loss recovery engelini kontrol eder. Android 14 çalışma zamanı, Robolectric, lint, R8, önce PR CI ardından main CI / APK doğrulaması ayrı kapılardır. Fiziksel telefonda bozulma/kurtarma onayı verilmeden güvenilirlik puanı yükseltilmez.
- **Önemli sınır:** SQLCipher hâlâ üretimde etkin değil. Bu değişiklik disk üzerinde tüm kayıtları şifrelemez veya fiziksel veri kurtarmayı kendi başına tamamlamaz.


**25.10.2026 durum:** yalnızca salt okunur veri incelemesi ve birim testleri uygulandı. Mevcut SQLite/Room hala **şifrelenmiş değildir**. Hiçbir APK'da otomatik DB geçişi, silme veya SQLCipher yüklemesi açılmadı.

## Mevcut gerçek durum ve neden önce preflight var?

- Üretim veritabanı: `lanu_calculator_db` (`AppDatabase.kt`, Room v4).
- Veri dosyasına ek olarak SQLite `-wal`, `-shm`, `-journal` kalıntıları bulunabilir. WAL son commit'leri ana DB'ye henüz taşımamış olabilir.
- Güvenli ilk kapı: `DatabaseEncryptionPreflight.inspect(context)` **uygulamanın açılışında otomatik çalışmaz**. Dosya üstverisi ile SQLite başlığının 100 baytını, geçerli sayfa boyutunu, sayfa hizasını ve dosya biçimi alanlarını denetler; dosya/taşıma/Keystore üzerinde herhangi bir yazma yapmaz.
- En az bir tam SQLite sayfası olan eski dosyayı tanır; kesik/şüpheli dosya, yetim WAL, güvenli olmayan yardımcı dosya ve iki kopya+64MiB ihtiyatlı boş alan yetersizliği güvenli reddedilir.
- Sonuçta yol, mesaj, tablo/PIN, anahtar veya dosya içeriği bulunmaz; yalnız risk durumu/byte sayımı vardır.
- `safeToBeginOfflineMigrationDesign=true` yalnız **tasarıma/kapalı test aşamasına geçilebilir** demektir; gerçek veri taşımaya izin veren koşul **DEĞİLDİR**.

## Gerçek şifreli Room'a geçiş için zorunlu kapılar

1. Güncel ve API23+/16KiB sayfa uyumlu Zetetic `sqlcipher-android` + Room `SupportOpenHelperFactory` entegrasyonunu lisansla beraber güncel resmî belgelerden doğrula. GMS zorunluluğu ekleme.
2. Mevcut cihazda gerçek verilerin **şifreli, geri okunabilir sahibi kontrollü** tam yedeği ve stabil APK imza sertifikası doğrulanmadan migration başlatma.
3. Tek global migrasyon kilidi, Room'dan bütün yazıcıları kesme, SQLite checkpoint+WAL bütünlüğü, dosya-sistem yedeği/disk sınırı ile tüm yedi tablo için v4 kayıt sayısı + birebir içerik kabulünü tasarla. Özellikle PIN, kimlik, E2EE gelen/giden kuyruk, medya taslakları ve hesap geçmişi aynen kalmalı.
4. Yeni rastgele veri tabanı anahtarını **Android Keystore** korumalı sakla; PIN açma/kapama ayarına bağlama. Kullanıcının PIN kapatma tercihinin anahtar kaybına yol açmasına izin verme. Keystore invalidate/backup/restore politikası açıklığa kavuşmadan etkinleştirme.
5. Güvenli geçici dizinde, plaintext orijinali silmeden, yeni şifreli dosya oluştur ve tüm SQL içerik/şema/indeks/WAL geri açma testini gerçekleştirdikten sonra tek yönlü geçişe aday kabul et.
6. Yetersiz alan, disk dolması, uygulama öldürülmesi, çoklu iş parçacığı yarışı, eski sürüme downgrade ve yarım kalmış migration senaryoları için **geri alınabilir** testler. Doğrulanmamış dosya asla üretim olarak açılmaz; fail-closed, eskisi saklanır.
7. CI'da sentetik v4 + şifreli yedek kaynağı üzerinden gerçek instrumented Android API23/29/34/35 senaryoları, debug/R8 release eşliği ve **2+ fiziksel üretici** kabulü.
8. Eskiden kalan SQLite, WAL, SHM ve backup kopyalarının silinmesi yalnız **yeni şifreli DB + kullanıcı geri dönüş ve veri kurtarma kararı** doğrulandığında ele alınır. Kör dosya silme veya `fallbackToDestructiveMigration` kesinlikle yasak.

## Bu PR'nin kapalı testleri

- Mevcut SQLite dosyası ve WAL/SHM/journal'in byte-byte değişmeden kalması
- Yeni kurulum/yetim WAL/dizin biçimli dosya/kesik ya da bilinmeyen şifreli biçimin reddi
- Disk kapasitesi eşiğinin tam sınırı
- Sonuç nesnesinin dosya yolunu, kimliği, PIN'i veya tablo içeriğini içermemesi

**Ürün notu:** SQLCipher geçişi yüksek etki alanlıdır. Yukarıdaki aşamalar tamamlanmadan mevcut uygulamanın veritabanını sessizce şifrelemeye/yeniden oluşturmaya çalışmak PIN, rehber, mesaj veya hesap geçmişi kaybına neden olabilir. Bu nedenle bu PR'deki preflight henüz çalışma zamanına bağlanmadı.
