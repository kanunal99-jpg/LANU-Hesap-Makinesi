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
