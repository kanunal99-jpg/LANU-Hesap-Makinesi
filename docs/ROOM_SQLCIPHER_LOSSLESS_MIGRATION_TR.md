# LANU — SQLCipher / Room kayıpsız geçiş tasarımı

**25.10.2026 durum:** yalnızca salt okunur veri incelemesi ve birim testleri uygulandı. Mevcut SQLite/Room hala **şifrelenmiş değildir**. Hiçbir APK'da otomatik DB geçişi, silme veya SQLCipher yüklemesi açılmadı.

## Mevcut gerçek durum ve neden önce preflight var?

- Üretim veritabanı: `lanu_calculator_db` (`AppDatabase.kt`, Room v4).
- Veri dosyasına ek olarak SQLite `-wal`, `-shm`, `-journal` kalıntıları bulunabilir. WAL son commit'leri ana DB'ye henüz taşımamış olabilir.
- Güvenli ilk kapı: `DatabaseEncryptionPreflight.inspect(context)` **uygulamanın açılışında otomatik çalışmaz**. Dosya üstverisi ile yalnız 16 bayt SQLite header'ı kontrol eder; dosya/taşıma/Keystore üzerinde herhangi bir yazma yapmaz.
- Eski SQLite header'ını ve en az 100 bayt dosyayı tanır; kesik/şüpheli dosya, yetim WAL, güvenli olmayan yardımcı dosya ve iki kopya+16MiB tahmini boş alan yetersizliği güvenli reddedilir.
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
