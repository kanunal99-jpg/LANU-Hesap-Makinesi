# 21.10.2026 — P0 APK Release erişilebilirliği ve byte doğrulaması (APK-PUBLISH-01)

## Risk
APK build ve CI artifact denetlense bile yayımlanan GitHub Release APK'sinin anonim internetten gerçekten indirilebildiği ve byte olarak doğrulanmış CI dosyasıyla aynı olduğu ayrı kontrol edilmiyordu.

## Uygulama
- Altı Python regresyonu: temiz APK, değişmiş APK, eksik signer, değişmiş sertifika, geçersiz SHA256, signing mode uyumsuzluğu.
- Ana dal release oluşturulunca APK, SHA256 ve sertifika raporu herkese açık HTTPS adreslerinden anonim olarak yeniden indirilir.
- Üç dosya CI artifact'ı ile byte-byte karşılaştırılır; APK SHA256, signer modu, sertifika kimliği formatı, cihaz yükseltmesi false raporu çapraz denetlenir.
- Dosya erişilemez, eksik veya farklı ise Publish verified APK işi başarısız olur.
- Bu test, mevcut build job apksigner kontrolüne eklenir; onun yerine geçmez.

## Açık üretim engeli
Aynı kalıcı sertifikayla imzalanmış iki APK ve Samsung A55/farklı üretici cihazında veri kayıpsız eski APK üzerine yükseltme gerekir. Bu kurallar geçmeden geçici debug APK güvenli OTA olarak ilan edilemez.
