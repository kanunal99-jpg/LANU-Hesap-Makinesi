# LANU #85 — Sunucu tarafı şifreli nesne bütünlüğü (hazırlık)

**Durum: doğrulayıcı modül ve regresyon testleri; canlı aktarım KAPALI.**

## Neden gerekli?

Telefonun `PrivateMediaTransferBudget.verifyForReservation()` kapısı
yalnız yerel dosyanın gönderim **öncesi** bütünlüğünü kanıtlar.
Depolama servisine ulaşan dosya farklı, eksik ya da fazla bayt içerebilir.
Yalnız Storage metadata boyutuna güvenip `ready` işaretlemek
alıcının yetkisiz veya bozuk nesne indirmesine yol açabilir.

## Yeni güvenlik sınırı

`supabase/functions/lanu-relay/media-verify.mjs` içindeki
`verifyPrivateCiphertextResponse(response, reservation)`:

- **Yalnız önceden sunucu tarafından doğrulanmış** kimlik/alıcı/nesne-yolu
  rezervasyonuyla kullanılabilir; kullanıcıdan URL almaz ve URL üretmez.
- `fileId` (32 lowercase hex), `cipherBytes` (17..41.953.280),
  `cipherSha256` (64 lowercase hex), `state=pending` ve geçerli TTL şarttır.
- Storage GET sonucu tam **HTTP 200** ve `application/octet-stream`
  olmalıdır. `206 Partial Content`, content-encoding `gzip`,
  yanlış ilan edilmiş Content-Length ve eksik body reddedilir.
- Yanıt **akış hâlinde** okunur, tüm ~40 MiB RAM'e alınmaz. Bayt sınırı
  aşılırsa akış iptal edilir. `node:crypto.createHash('sha256')` kullanarak
  toplam ciphertext özeti hesaplanır, `timingSafeEqual` ile karşılaştırılır.
- Dosya içeriği, AES anahtarı, PIN, kişisel mesaj veya Storage yetki
  anahtarı yanıtla dönmez; yalnız anahtarsız dosya kimliği, byte, hash döner.
- Doğrulama başarılı olmadan hiçbir `ready` geçişi yapılamamalıdır;
  yardımcı **hiçbir DB/Storage yazımı yapmaz**.

## Üretimde etkinleştirmeden önce

Bu yardımcı **mevcut Edge `index.ts` veya `createRelay` akışına
bağlı değildir**; kaynak kodu değiştirmek kullanıcılara upload hakkı
vermez. Gerçek uygulama sırası:

1. ECDSA imzalı mutual-contact, 40 MiB kota ve `pending` rezervasyon.
2. Sunucu denetiminde şifreli Storage obje yolu/tekil upload grant.
3. İstemci gönderimi; sunucu yetkili service-only Storage yolundan GET ile
   ciphertext’i okur; hem boyut hem SHA-256 bu modülle doğrulanır.
4. Doğrulama sonrası **atomik** DB `pending→ready` geçişi; TTL, sahiplik,
   messageId/peer ve idempotency yeniden kontrol edilir.
5. Yalnız doğru alıcı için kısa geçerli indirme yetkisi; E2EE manifest
   anahtar zarfı ve eski istemci uyumluluk kapısı.
6. Başarısız yükleme, bağlantı kopması, tekrarlar ve Storage TTL silme;
   iki gerçek Android telefonda kabul.

`node:crypto` yerleşik modül desteği Supabase Edge/Deno üzerinde belgeli;
bununla birlikte gerçek Edge runtime uyumluluğu dağıtımdan önce ayrıca
test edilmelidir. Prototip günlükleme/saklama politikası gizli dosya
veya URL içermemelidir. Aktif ürün kullanıcıları etkilenmez.
