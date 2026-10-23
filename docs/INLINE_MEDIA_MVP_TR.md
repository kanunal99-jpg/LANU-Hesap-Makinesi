# LANU — Güvenli küçük medya MVP (18 KB)

**Durum:** Geliştirme dalı; CI + gerçek Samsung A55/ikinci telefon kabulü geçmeden tamamlandı denemez.

## İlk sürümün bilinçli sınırı

Mevcut LANU rölesi yalnızca **ECDH tabanlı AES-GCM ile şifreli metin mesajlarını** kabul ediyor; şifreli bir mesajın boyutu en fazla 50.000 karakter. Bu sürümde normal sohbeti ve sunucuyu değiştirmeden **tek bir mesajın içine en fazla 18.000 bayt fotoğraf veya dosya** paketlenir. Başka bir depolama sunucusu açılmaz ve kullanıcıya ücret çıkarılmaz.

- Galeri: Android Photo Picker ile yalnız kullanıcının seçtiği görsel; JPG'ye yeniden kodlama, küçük boyuta sıkıştırma, EXIF konum meta verilerini taşımama.
- Kamera: Android'in başka kamera uygulamasından dönen **düşük çözünürlüklü önizlemesi**; tam çözünürlüklü çekim değildir.
- Belgeler: Android OpenDocument ile **en fazla 18 KB PDF veya UTF-8 TXT**. İçeriği dosya türü ve SHA-256 ile kontrol et.
- Gönderme: Kullanıcı küçük önizlemeyi gördükten sonra **Şifreli gönder** der; dosya, yalnız doğrulanmış kişinin mevcut `PeerMessageCrypto.encrypt()` kanalından geçer.
- Gönderici ve alıcıda Room'a **şifreli mesaj** kaydedilir. Kullanıcının hazırladığı ham dosya içeriği hiçbir yeni tabloya veya sunucuya açık yazılmaz; dış URL üretilmez.
- Alma: mevcut E2EE çözme → `InlineMediaCodec.decode` ile MIME, uzunluk, kanonik Base64, SHA-256 doğrulama → fotoğrafı sınırlandırılmış boyutta önizle veya belgeyi dosya adıyla göster.
- Kaydetme: yalnız kullanıcı **Cihaza kaydet** seçer, Android'in ACTION_CREATE_DOCUMENT sistem seçicisiyle seçtiği konuma açık kopya yazar. Dosya kendiliğinden galeriye veya genel depolama alanına eklenmez.
- İptal: seçimden vazgeçildiğinde sunucuya mesaj gönderilmez; yanlış/eksik dosya reddedilir.
- Offline: mevcut E2EE mesaj PENDING kuyruğu ve 200'lük batch üzerinden iletim tekrar denenir; 18 KB sınırı korunur.

## Güvenlik / desteklenmeyen özellikler

Bu yöntem **tam boy fotoğraf, video veya büyük PDF aktarımını desteklemez**. Küçük görsellerde kalite düşer. Büyük dosya için ayrı private Storage, istemci AEAD, kademeli upload, alıcı yetkilendirme, quota, expiry ve cleanup gerekir. Bu tasarım ayrı #85 kapsamındadır, maliyeti kullanıcıya açıkça onaylatmadan devreye alınmaz.

**Bilinen durum:** Gizli sohbet, PIN, kişi kimliği, veri migration sistemi veya üretilmiş imza anahtarları değiştirilmez. Eski metin mesajları aynı biçimde okunmaya devam eder. Mevcut çağrı ekranları hâlâ demo olarak ele alınır.

## Test ve yayına alma kapıları

1. Boyut sınırı, JPEG/PDF/TXT türü, SHA-256 oynama, Unicode, dosya adı traversal ve PeerMessageCrypto karşılıklı alıcı çözme testleri.
2. GitHub unit/Robolectric, lint, emülatör database ve imza kapıları yeşil.
3. Karşılıklı iki Android cihazda galeri, kamera ve küçük PDF/TXT gerçek gönder/al, offline retry, panoya içerik sızmaması ve yalnız kullanıcı onaylı SAF export doğrulanmalı.
4. İlk Merge'de yeni imzalı APK'nin resmî Release + SHA256 + signer dosyaları anonim HTTPS yeniden indirilerek doğrulanmalı; 286/289'dan OTA uygulanıp PIN/mesajlar korunmalı.
5. Kamera/galeri seçimi dosya içeriğine geniş izin vermez; işletim sistemi klavye sesi/titreşimi değişmez. Crashes #82 ve signing backup #83 kapanmadan genel ürün hazır sayılmaz.

GitHub geliştirme kaydı: [#85](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/issues/85).
