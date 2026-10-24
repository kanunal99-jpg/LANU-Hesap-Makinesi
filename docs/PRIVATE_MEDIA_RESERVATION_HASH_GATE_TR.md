# LANU #85 — Şifreli özel dosya rezervasyonu için SHA-256 kapısı

Bu değişiklik bir **ön koşul doğrulamasıdır**, dosya yükleme değildir.

`PrivateMediaTransferBudget.verifyForReservation`, sunucudan *gelecekte*
kısa süreli özel Storage yükleme yetkisi istenmeden **önce** çalıştırılması
gereken yerel sınırlayıcıdır:

- Manifestin sürümü, dosya kimliği, MIME, nonce, parça sayısı ve
  SHA-256 biçimi `PrivateMediaCrypto.validate` ile doğrulanır.
- Kaynak içerik sınırı **40 MiB**; şifreli bayt hesabında her 64 KiB
  parçanın 16 baytlık GCM etiketi sayılır.
- Şifreli nesne yalnız arayanın uygulama-özel klasöründeki
  `lanu-media-v1-*.cipher` adından kabul edilir; farklı dizin reddedilir.
- Tam dosya *parçalı okunur*, böylece 40 MiB'lik içerik RAM'e bütünüyle
  alınmadan SHA-256 alınır. Manifest özetiyle sabit zamanlı karşılaştırılır.
- Sunucuya önerilebilecek sonuç yalnız `fileId`, `cipherBytes`,
  `cipherSha256` alanlarından oluşur: **dosya anahtarı ve düz dosya yolu yoktur.**
- Boyut, hash veya dosya yolu yanlışsa güvenli reddetme yapılır.

**Bu kontrol, dosya değiştirilebiliyorsa sonradan aktarılan baytların aynı
kaldığını tek başına ispatlamaz.** Uygulamanın sonraki upload yolu
aynı doğrulanmış dosyayı araya yazma sokmadan akıtmalı; backend
Storage yüklemesinin gerçek boyutunu/özetini doğrulamadan medya
`ready` yapmamalıdır. E2EE anahtar zarfı ve gönderen/alıcı
yetkilendirmesi ayrıca şarttır.

Canlı Supabase schema/bucket, mesajlaşma, anahtarlar, kullanıcı PIN'i
ve kurulu uygulama verisi değiştirilmez. İki fiziksel cihazda gerçek
gönderim/indirme yapılmadığı için #85 **açıktır**.
