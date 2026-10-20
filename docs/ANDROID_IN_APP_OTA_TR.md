# LANU — Uygulama İçi Güncelleme (OTA)

## Kullanım

**Normal ekran:** LANU Hesap Makinesi → üst sağdaki üç nokta → **Güncelleme Kontrol**.

**Gizli alan:** Sohbetler / Kişiler / Ayarlar / **Güncelleme** sekmesi.

Bölüm açıldığında GitHub Releases API üzerinden otomatik denetim yapılır; **Güncelleme kontrol et** düğmesi tekrar denetler.

Yeni sürüm bulunduğunda şu sıra takip edilir:

1. Repo/tag/asset URL'si resmî GitHub deposuna ait olmalı.
2. Sürüm kodu kurulu `versionCode`'dan daha yüksek olmalı.
3. Yayında `app-debug.apk`, `app-debug.apk.sha256` ve `app-debug.apk.signer.txt` birlikte bulunmalı.
4. `.signer.txt` içindeki SHA-256 imza parmak izi kurulu LANU sertifikasıyla eşleşmeli. **Eşleşmiyorsa indirme ve kurulum engellenir.**
5. Kullanıcı İndir'e basarsa resmi APK HTTPS üzerinden boyut sınırı ile indirilir.
6. İndirilen dosyanın SHA-256 değeri ayrı yayımlanan checksum dosyasıyla karşılaştırılır.
7. Android PackageManager, APK'nin paket adını, daha yüksek sürüm kodunu ve gerçek imza sertifikasını kurulu uygulamaya karşı doğrular.
8. **Android üzerinden güncelle** düğmesi sistem paket yükleyicisini açar; kurulum **sadece kullanıcı onayıyla** yapılır. Android 8+ cihazlarda LANU için "Bu kaynaktan uygulama yüklemeye izin ver" ayarı ilk seferde gerekebilir.

Uygulama eski LANU'yu kaldırmayı **asla** teklif etmez veya gizli verileri otomatik silmez. Uygulama içi sessiz veya kullanıcıdan habersiz kurulum mümkün değildir.

## Güçlendirilmiş OTA güvenlik zinciri

- Güncelleme kontrolü, **yeni sürümün doğrulama dosyaları eksikse** artık "güncelsiniz" sonucunu göstermez; bu sürümü açıkça **güvenlik nedeniyle kurulamaz** olarak işaretler.
- Resmî GitHub API ve asset URL'leri için kesin repo/tag/dosya doğrulaması yapılır. İndirme HTTP yönlendirmeleri otomatik izlenmez; **her adımda HTTPS + izin verilen GitHub CDN adresleri** denetlenir, en çok 5 yönlendirmeye izin verilir.
- APK akışı kopyalama sırasında **boyut sınırı**, SHA-256 ve kurulu uygulamanın sertifika kimliği, paket/sürüm kontrollerini uyguluyor.
- İndirme **yüzde/KB ilerlemesi** görüntülenerek kullanıcı tarafından iptal edilebilir; kısmi APK temizlenir. Dosyalar benzersiz adla iç cache'de saklanır; 2 günden eski cache artıkları kaldırılır.
- **Android yükleyicisi açılmadan hemen önce** indirilen dosyanın tam SHA-256'sı tekrar hesaplanır ve `PackageManager` ile APK imzası + `packageName` + daha yüksek `versionCode` yeniden kontrol edilir. Herhangi bir uyuşmazlıkta kurulum başlatılmaz.
- Sistem yükleme onayı her zaman kullanıcıdadır; SMS, root, sessiz yükleme veya mevcut uygulamayı kaldırma yoktur.

## Kesilen indirmeye devam etme ve doğrulanmış önbellek

- İnternet bağlantısı kesilir veya aktarım erken biterse kısmi APK, yalnız **dahili OTA cache** alanında kalır. Aynı sürüm ve SHA-256 için yeniden denendiğinde HTTP `Range` ile kaldığı yerden devam etmeyi dener.
- Sunucu `206 Partial Content` gönderirse `Content-Range` kesin olarak istenen ofsetle başlamalı, toplam APK boyutu güvenlik sınırları içinde olmalı. Hatalı/belirsiz aralık **reddedilir**. Sunucu `200 OK` gönderirse eski kısım temizlenir ve dosya sıfırdan güvenli biçimde indirilir.
- `416 Range Not Satisfiable` yanıtında otomatik tam indirmeye dönülür.
- Kullanıcı **İndirmeyi iptal et** dediğinde kısmi APK silinir; ağ hatası nedeniyle yarıda kalan dosya ise sadece sonraki aktarımı hızlandırmak için saklanır. Bir güncelleme hiçbir zaman **tam APK SHA-256 + Android paket/sertifika doğrulaması olmadan** yükleyiciye verilmez.
- Bir sürümün doğrulanmış APK'sı cache'de mevcutsa **yeniden SHA-256 / paket / sertifika kontrolüyle** kullanılabilir; doğrulama başarısızsa silinir.
- Boş depolama alanı indirme başlamadan değerlendirilir; düşük alanda açık hata verilir. Aynı işlemde iki indirme ekranı birbirinin dosyasını değiştiremez: OTA aktarımı süreç başına seri yürütülür.

## Üretim seviyesi ek dayanıklılık (20.10.2026)

- **Sıkı HTTP kısmi indirme sözleşmesi:** `206 Partial Content` yanıtının `Content-Length` değeri ilan ettiği `Content-Range` boyutuyla kesin eşleşmelidir. `gzip` gibi beklenmeyen sıkıştırılmış APK yanıtları reddedilir. `200 OK` yanıtında yanlışlıkla `Content-Range` dönülürse sistem fail-closed davranır. Hiçbir sunucu beyanı tek başına kurulum izni vermez: nihai SHA-256 + kurulu paket imzası kontrolü zorunlu.
- **Tam SHA-256 cache kimliği:** Daha önce yalnız checksum'un ilk 16 hex karakteri kullanılmaktaydı. Artık sürüm kodu ve checksum'un tamamı kullanılır, böylece cache dosyası çakışma ihtimali minimize edilir.
- **Sınırlı disk kullanımı:** OTA dosyaları 2 günlük yaş filtresi ve toplam 180 MiB sınırı altında tutulur. Yalnız LANU'nun `lanu-<sürüm>-<hash>.apk` biçimindeki kendi dosyaları temizlenir; gizli alan verisi, sohbetler ve uygulama veritabanı asla OTA önbellek temizliğiyle silinmez.
- **CI en az yetki:** GitHub Actions test ve APK derleme işi `contents: read` ile çalışır; GitHub Release yazma yetkisi ayrı bir yayın işinde bulunur. Release yayın işi eski `main` commit'lerinden gelen paketleri yayımlamaz; indirilen artifact'in SHA-256 değerini yeniden doğrular. Bir PR'ın CI testinde yayın yetkisi yoktur.
- **Kanıt:** Android/Robolectric testleri, Android lint, APK sertifika raporu, indirilebilir Release APK ve SHA-256 dosyasıyla yayımlanır. Fiziksel telefonda kesintili ağ ve sistem kurulum testleri halen ayrıca gereklidir.

## OTA v3 — bağlantı iptali ve ayrı kurulum kopyası

- Kullanıcı indirmeyi iptal ettiğinde HTTP isteği de **OkHttp üzerinden iptal edilmeye çalışılır**; sonlandırma ağın/işletim sisteminin soket durumuna bağlı olarak kısa süre alabilir. Kısmi dosya iptal durumunda temizlenir.
- ZIP/APK kaynağı `cache/ota/` içinde indirilir ve SHA-256, imza, sürüm/paket kontrollerinden geçer.
- Kullanıcı **Android üzerinden güncelle** dediğinde uygulama aynı APK'yi **yeniden doğrular**, ardından `cache/ota/install/` altında ayrı bir kurulum kopyası oluşturur.
- Kopya da **yeniden SHA-256 ve APK sertifikasıyla doğrulanır**. Android FileProvider artık sadece `install/` klasöründeki biçimi doğru, mevcut dosyayı yükleyiciye devredebilir.
- Diğer OTA indirmeleri ve yükleyici için kopya hazırlama aynı `downloadMutex` ile sıralanır. İndirme önbelleği temizliği kurulum kopyası klasörünü taramaz.
- Android yükleyici hâlâ sistem onayı ister. Kurulum kopyası, güncelleme denemesinden sonra hemen silinmez; sistem paket yükleyicisinin okumaya devam edebilmesi gerekir. Yeni yükleme isteğinde iki günden eski kopyalar temizlenir.
- Kurulum esnasında dış Android PackageInstaller süreçlerinin davranışı yalnızca fiziksel cihaz/emülatör testiyle kesin doğrulanabilir. Bu korumalar rootlu cihazda, değiştirilmiş ROM'da veya işletim sistemi arızasında mutlak bütünlük garantisi değildir.

## Mevcut eski debug sürüm kullanıcıları

CI, v145 ve v148 gibi önceki debug APK'lerde **rastgele ve farklı** sertifikalar üretmiş olabilir. Aynı `applicationId` ile farklı sertifika yerinde güncellemeyi engeller. Bu sürümlerde OTA ekranı doğru şekilde **İmza uyumsuz** uyarısı gösterebilir. Bu hata değil, veri kaybı önleyen güvenlik kontrolüdür.

İmza sorunu çözülmeden eski LANU uygulamasını kaldırmayın. Eski sürümün keystore özel anahtarı kayıpsa onu sonradan üretmek mümkün değildir. Korunan imzalama anahtarlarının GitHub Secrets'ta kurulumu `docs/ANDROID_APK_SIGNING_AND_UPGRADES_TR.md` dosyasında açıklanır.

## Yayın gereklilikleri

- GitHub Releases erişilebilir değilse açık bağlantı hatası.
- Yalnız yayınlanan resmî LANU `v1.0.0-debug.N` veya `v1.1.0-signed.N` etiketleri. Eski numara indirilmez.
- Sertifika yan dosyası olmayan eski APK'ler otomatik yükseltme için uygun sayılmaz.
- APK, dahili uygulama cache alanında tutulur; sadece `FileProvider` ile sistem yükleyicisine geçici okuma yetkisi verilir.
- Android, kimlik eşleşmediğinde `INSTALL_FAILED_UPDATE_INCOMPATIBLE` hatası verir; LANU da bunu önceden engellemeye çalışır.
- CI testleri tasarım/protokol doğrulamasıdır; Samsung/Pixel gibi fiziksel Android telefonlarda ağ kopma, installer izinleri, APK açılma ve **mesaj/PIN/veri korunumu** testi yapılmadan "tam OTA" garantisi yoktur.

## Mağaza dağıtımı

Play Store sürümleri için Android App Bundle ve Play In-App Updates farklı gereksinimlere tabidir; bu akış GitHub üzerinden APK yükleyen dağıtımlar içindir. Google Play'in `REQUEST_INSTALL_PACKAGES` kullanım politikası yayımlamadan önce ayrıca incelenmelidir.
