# LANU #85 — İmzalı medya rezervasyonu protokolü (varsayılan KAPALI)

Bu PR yalnızca mevcut E2EE metin protokolüne **hazırlık amaçlı** bir
`reservePrivateMedia` eylemi ekler. Mevcut Supabase Edge giriş noktası
`createRelay(new RestStore(url, key))` çağrısını **options olmadan** yapar;
dolayısıyla `privateMediaReservation` varsayılan `false` olur.
Canlı LANU istemcisi bu yeni eylemi gönderse bile 503 güvenli reddi alır.

## Sözleşme

İstemcinin mevcut P-256 ECDSA kimliğiyle imzalanmış ve tekil
`requestId` içeren istekte yalnızca `fileId` (32 küçük hex),
`messageId` (UUID), `peerId` (64 küçük hex),
`cipherBytes` (17..41.953.280) ve `cipherSha256` (64 küçük hex)
bulunabilir. Düz dosya, AES key, passphrase, MIME, storage yolu,
Supabase anahtarı, açık URL veya kişi kimlik anahtarı **gönderilmez**.

Rezervasyon için sunucu:

1. Özellik sunucu tarafında açık mı? Varsayılan: hayır
2. ECDSA imza, zaman aralığı, nonce / replay geçerli mi?
3. Alıcı kişinin **karşılıklı** kaydı var mı?
4. Gönderen ve alıcı `private_media_version=1` sürümünde mi?
5. Dosya kimliği, boyut, özet ve veri alanları tam eşleşiyor mu?
6. Mevcut `lanu_reserve_private_media` SQL işlevinin transaction/kota
   sonucunda dönen gönderici, alıcı, message, file, bytes, hash, expiry
   beklenenle eşleşiyor mu?

Başarı halinde sadece `fileId`, `cipherBytes`, `state` ve
`expiresAt` döner. **Signed upload/download bağlantısı üretmez.**
SQL kotaları kullanıcı başına 24 saatte 4, kullanıcı toplamda 256 MiB,
proje toplamda 512 MiB. İstemci bu metaveri işlemini bile
sunucunun varsayılan kapalı durumunda yapamaz.

## Devreye alma güvenlik kapısı

Android UI, ağ, Storage, mevcut E2EE mesajları ve Room değiştirilmedi.
İşlev etkinleştirilmedi; backend mock testleri
`createRelay(store, Date.now, { privateMediaReservation: true })` kullanır.
`supabase/functions/lanu-relay/index.ts` hiçbir seçenek iletmez.

Gerçek kullanım için henüz zorunlu işler:

- Gizli Storage bucket üzerinde kullanıcıya göre yalnız tekil
  şifreli obje için yetkili signed upload grants
- En fazla 2 saatlik bearer upload URL'si kötüye kullanımına karşı
  benzersiz yol, değiştirmeyi reddetme ve iptal/TTL yönetimi
- Sunucunun yüklenen nesnede byte uzunluğu ve SHA-256 kontrolü
  yapmasından SONRA metadata `ready` durumuna geçiş
- Yalnızca doğru alıcıya ECDSA imzalı kısa süreli download
- Telefonlardaki doğrulanmış E2EE anahtar zarfının iletilmesi
- Ücretsiz kota aşımı, 413/429/replay/offline/temizlik ve iki
  gerçek telefon kabulü

Bu kapanış kriterleri olmadan servis açılmamalı veya #85
tamamlandı sayılmamalıdır. Gerçek kullanıcı verisi, Storage objeleri,
şema veya izinler değişmedi.
