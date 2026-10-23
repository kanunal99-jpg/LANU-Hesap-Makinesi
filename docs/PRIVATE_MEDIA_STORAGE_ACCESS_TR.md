# LANU Büyük Medya: Yetkilendirme ve özel depolama karar kaydı

## 23.10.2026 canlı ortam gözlemi

Bağlı Supabase **LANU Code Messaging** projesinde özel Storage bucket sayısı **0**. Mevcut kullanıcı kimliği Supabase Auth JWT değil; `lanu-relay` içindeki **P-256 ECDSA imzalı mesaj**, kayıtlı public key, istek zamanı ve tek kullanımlık requestId ile doğrulanıyor. Bu nedenle Storage'a yalnız `anon`/JWT temelli basit RLS eklemek, LANU'nun kullanıcı kodu/peer yetkisini otomatik sağlamaz. Herkese açık bucket veya istemcide `service_role` anahtarı **yasaktır**.

Kaynak: `supabase/functions/lanu-relay/core.mjs`, `MessagingIdentity.kt`; bağlı Supabase şema/bucket okuma kontrolü. Supabase resmî Storage belgeleri: https://supabase.com/docs/guides/storage/buckets/fundamentals ve https://supabase.com/docs/guides/storage/security/access-control

## Uygulanacak güvenli aktarım sözleşmesi (henüz canlı DEĞİL)

1. Özel, **public=false** bucket, encrypt edilmiş nesne dışında hiçbir içerik kabul etmemeli. Düşük kullanıcı-başı toplam kota, tek dosyada en fazla 64 MiB ve TTL olmadan deploy yapılmayacak.
2. Dosyayı gönderen kullanıcı, mevcut relay P-256 özel anahtarıyla **kısa süreli imzalı upload isteği** yollayacak; `identityId`, `requestId`, `ts`, manifest `fileId`, boyut, alıcı `recipientId`, `cipherSha256` imzalı olacak. Edge sunucu anahtar sahipliğini, replay engelini, sınırları ve gönderen-alıcı kayıtlarını kontrol edecek.
3. Sunucu **tek kullanımlık kısıtlı nesne yolunu** ve kısa süreli upload yetkisini oluşturacak. İstemciye hiçbir zaman `service_role`, master signing key veya genel bucket erişimi verilmeyecek.
4. İndirici, **ayrı ECDSA imzalı** istekle alıcı olduğunu kanıtlayacak; sadece bu alıcıya yönelik nesne için kısa ömürlü indirme URL'si verilecek. Tahmin edilebilir dosya ID'si veya sohbet kodu bilmek yetmeyecek.
5. Gerçek anahtar + manifest, bucket içinde değil, ancak `PrivateMediaKeyEnvelope` üzerinden kimliği doğrulanmış eşin **E2EE** kanalında iletilecek. Dosya ciphertext hash, parça AAD/AEAD ve toplam boyutla doğrulanacak.
6. Başarısız upload, boş nesne, tekrar gönderim, expired link, 403, 413, 429, offline, yanlış alıcı ve bozulan cipher için idempotent işlem ve retry. Günlük kota, dosya TTL, bakım/temizlik ve maliyet alarmı olmadan yeni bucket etkinleştirilmeyecek.
7. Veri tabanı değişikliklerinde var olan mesaj tablolarına **destructive migration yok**; yeni özel `lanu_media_grants` şeması kendi satır yetkileri, `recipientId`, kullanım sınırları, expiration ve denetim kayıtlarıyla sınırlandırılacak.

## Uygulama sırası

- Tamamlandı: AES-256-GCM parça şifreleme; E2EE key envelope, iki kullanıcıyla kripto regresyonu.
- PR #97: Büyük fotoğraf/PDF/MP4 için içerik imzası ön kontrolü, EXIF ve video metadata onayı.
- Sıradaki: Android PhotoPicker/SAF import ve metadata-scrub, backend için read-only test fixture, özel grant katmanı, ücretsiz sınırda test ortamı.
- **Eksik:** Gerçek private bucket, server-backed auth grant, upload/download API, 2 cihaz gerçek video testi, maliyet/TTL gözetimi.
- Kullanıcı onayı olmadan ücretli Storage/tarife değişikliği veya anonim public dosya paylaşımı yapılamaz. Mevcut 18 KB fotoğraf özelliği üretimde kullanılmaya devam eder.
