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
