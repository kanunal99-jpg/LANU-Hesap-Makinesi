# LANU özel medya gönderimi — gerçek sunucu boyut sınırı

24 Ekim 2026 tarihli canlı Supabase `20261024105720_private_media_grants_and_quota`
migration'ında `lanu_media_objects.cipher_bytes` en fazla **41.953.280 bayt**
ve `lanu-private-media-v1` özel kovasında en fazla **44.040.192 bayt**
kaydedilebilir. AES-GCM her 65.536 baytlık parça başına 16 bayt
doğrulama etiketi üretir; sadece kaynak dosya boyutunu ölçmek yetersizdir.

Yeni `PrivateMediaTransferBudget`, **40 MiB** düz içerik sınırında
tam **640 × 16 = 10.240 bayt** GCM etiket yükünü hesaba katar.
`encryptedBytes(40 MiB) == 41.953.280` olmalıdır.

Makrosuz Office kaynak hazırlığı, içerik şifrelenmeye başlanmadan
bu limitin üstünü reddeder. Yerel 64 MiB şifreleme alt sistemi
ve mevcut küçük fotoğraf mesajlaşması değişmez.

**Bu PR upload/download özelliğini açmaz.** İlerideki aktarma
entegrasyonu, şifreli dosyanın gerçek boyutunu rezervasyonla
eşleştirmek için `verifyCiphertext` kullanmalıdır.
Sunucudan gelen signed URL'leri doğru alıcıya, kısa ömürlü ve
tekil, yetkilendirilmiş yollarla vermek; sonradan temizlik ve
iki gerçek cihaz kabulü #85 işinde açıktır.

Sınır doğrulaması yalnız güvenli yerel hazırlıktır; şifreli dosyanın
gönderilmesi, kaydedilmesi veya sunucu kotasına yetkili erişim
garantisi vermez.
