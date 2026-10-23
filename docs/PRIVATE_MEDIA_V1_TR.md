# LANU — Büyük medya için özel şifreleme katmanı (tasarım ve ilk uygulanmış kod)

**Durum:** Geliştirme/altyapı. Fotoğraf, video veya büyük PDF gönderimi henüz canlı değil. Mevcut 18 KB şifreli inline ek gönderimi ve metin iletişimi değişmedi.

## Kaynak kod

- `app/src/main/java/com/example/core/media/PrivateMediaCrypto.kt`
- `app/src/test/java/com/example/PrivateMediaCryptoTest.kt`

## Uygulanan güvenlik protokolü

- Dosya başına rastgele **256 bit AES-GCM anahtarı**, rastgele **128 bit dosya kimliği**, rastgele **64 bit nonce öneki** ve her parçaya benzersiz, monoton **32 bit parça numarası**: parça başına 96 bit AES-GCM nonce.
- **64 KiB parça** ve **64 MiB dosya** üst sınırı. Dosya boyutu yalnız metadata'ya güvenilerek kabul edilmez; veri tam boyutuyla okunur, eksik/fazla veri reddedilir.
- AAD şunları bağlar: protokol sürümü, dosya ID'si, MIME, toplam byte, parça sayısı, parça index'i ve parça byte sayısı. Bir parça diğer dosya/parça ile değiştirildiğinde GCM doğrulaması başarısız olur.
- Şifreli parçalar ardışık yazılır; her biri 16 byte AEAD doğrulama etiketi içerir. **Tüm ciphertext SHA-256** değeri manifest'te tutulur.
- Şifreleme yalnız rastgele isimli **şifreli temp dosyası** üretir. Tamamlanmayan/bozuk dosya ve anahtar temizlenir. Başarısız şifre çözme durumunda tamamlanmamış düz metin dosyası silinir.
- Çözme sonucunda çıkan dosya **yalnız uygulama private** dizininde geçici olarak bulunur. Kullanıcı onaylı görüntüleme/SAF dışa aktarma bitince silinmesi zorunludur. **Dikkat:** geçici çözülmüş dosya anlık düz metindir; üretimde dosya bırakmayan streaming görüntüleme tercih edilmeli ve otomatik TTL temizliği yapılmalıdır.
- `EncryptedFile.keyMaterial` **gizli** materyaldir; uygulamada henüz akışa bağlanmadı, Room'a/relay'e/Storage'a yazılmıyor. `destroyKey()` key dizisini sıfırlar; gerçek sürümde anahtarın doğrulanmış alıcıya **mevcut E2EE PeerMessageCrypto** mesajında iletilmesi, kimlik doğrulaması ve kesintide güvenli kurtarma ayrıca gerekir.

## Kimliği doğrulanmış alıcıya medya anahtarı teslimi — ikinci altyapı aşaması

- `PrivateMediaKeyEnvelope.kt` yalnız bellek üzerinde manifest + rastgele 32 byte dosya anahtarını **önceden doğrulanmış** alıcının `PeerMessageCrypto.encrypt` ECDH/AES-GCM kanalıyla şifreler. Şifreli `lanu2:` mesaj ID'si, gönderici/alıcı kimliği ve mesaj AAD'si mevcut kriptografik kanala bağlıdır. **Bu bir aktarma noktası değil, henüz üretimde kullanılmayan codec'tir.**
- Alıcı aynı doğrulanmış gönderici anahtarıyla zarfı açar; `version=1`, tam JSON alan seti, 64 MiB manifest/sayaç/nonce/file ID/hash ve anahtarın kanonik Base64URL / 32 byte olması tekrar doğrulanır.
- Gizli AES medya anahtarı yalnız `OpenedMediaKey.keyMaterial` byte dizisi olarak geri gelir; iş bitince `destroyKey()` ile sıfırlanmalıdır. Bu uygulama **anahtarı Room'a, log'a, Supabase Storage'a veya açık URL'ye yazmaz**.
- E2EE peer kimliği `peerForConversation` güven ilişkisiyle ayrıca doğrulanmalıdır; başkasının key'ini kullanıcıdan veya sunucudan doğrulamadan kabul etmek güvenli değildir.
- Yanlış mesaj ID'si, üçüncü alıcı, farklı gönderici, şifreli frame oynama, bozuk zarf, fazla alan, yanlış MIME/hash/nonce veya key boyutu reddedilir. Birim testleri alıcının gerçek AES-GCM parçalı dosyayı çözebildiğini de doğrular.
- **Zorunlu sınır:** Bu şifreli zarf şimdi `CommunicationRepository.sendMessage` akışına, Room'a veya üretim ağ API'sine bağlanmadı. Özel bucket kimlik/RLS/signed URL/TTL ve kalıcı offline retry altyapısı hazır olmadan büyük video/fotoğraf paylaşımı kullanıcı arayüzünde görünmeyecek.

## Bilinçli olarak henüz yapılmayanlar

- Sunucuya otomatik dosya yükleme/indirme, Supabase Storage bucket, object storage credential, presigned URL, kimlik doğrulama, kullanıcı bazlı erişim kontrolü.
- MIME dosya içeriği **magic byte** ve PDF/video iç denetimi. Bu katman yalnız izin verilen MIME bildirimini kontrol eder; gerçek medya seçimi aşamasında içerik ayrıca doğrulanmalıdır.
- Parça bazlı upload checkpoint, yeniden denemelerde aynı dosya/nonce çakışmasının önlenmesi, dosya silme TTL, depolama kotası, ağ politikaları, offline alıcı ve çoklu cihaz kurtarma.
- Büyük medyanın **kendiliğinden** galeriye yazılması, alıcı dosya indirme sırasında PIN'in atlanması, otomatik dışa aktarım.
- Gerçek cihaz uçtan uca büyük dosya, video veya dosya yedekleme testleri.

## Üretime açmadan önce zorunlu kabul

1. İstemcide `ContentResolver`/PhotoPicker/SAF üzerinden gerçek boyut+magic byte ve EXIF temizliği; **streaming belleği/disk kotası**, 64 MiB sınırı, bozuk görsel/video testleri.
2. Kullanıcı/peer onaylı private storage tasarımı: dosya anahtarını asla storage sunucusuna vermeyen AES-GCM; yetkili **kısa ömürlü indirme URL'leri**, row-level yetki, başka kullanıcı erişim reddi, quota, retention/TTL, çöp toplama ve ücretsiz kota maliyet denetimi. **Ücretli servise kullanıcının açık onayı olmadan geçilmez.**
3. **Doğrulanmış E2EE peer** kanalında manifest + tek seferlik medya anahtarını kimlik/kopyalama/sürüm/AAD bağlı mesajla iletmek. Anahtarın depolanması gerekiyorsa şifreli Keystore-backed kayıt, kesinlikle plaintext DB/log/secrets/URL değil.
4. Ağ kesilmesi, duplicate/replay, sırasız parçalar, eksik/truncated ve kurcalanmış data, uygulama kapanması, yanlış alıcı, kaybolan anahtar, çakışan upload ve **beklenmeyen ücret** için gerçek fallback ve test.
5. Android 16 Samsung A55 ve ikinci gerçek Android cihazda **tam boy fotoğraf + PDF + video** çift yönlü gönder/al, internet kesintisi/yeniden başlatma, 310/312'den veri kayıpsız yerinde OTA kabulü, APK aynı signing sertifika/GitHub Releases SHA256/read-back.
6. Bu akış gerçek cihazda geçene kadar kullanıcıya “Video gönderme hazır” denmez; #85 açık kalır.

## Diğer açıklar

- #82 Java/Kotlin crash kök nedeni hâlâ bilinmiyor; 312'de gelecekteki çökme için mahremiyet korumalı yerel tanılama mevcut.
- #83 kalıcı APK imza anahtarının telefon dışı şifreli yedeği hâlâ kabul edilmedi.
- #88 picker PIN tercihi kullanıcı tarafından henüz gerçek cihazda test edilmedi.
