# 24.10.2026 — Canlı LANU özel medya migration kaynak eşitlemesi

Bu dosya yeni veri tabanı işlemi **başlatmaz**. `talhknjcjaijqiqwdyhf`
(LANU Code Messaging) projesinde zaten uygulandığı Supabase migration
envanteriyle görülen `20261024105720_private_media_grants_and_quota`
sürümünün SQL kaynağı, `supabase_migrations.schema_migrations.statements[1]`
kaydı üzerinden birebir GitHub'a taşınmıştır.

## Canlı okumayla doğrulananlar

- `lanu-private-media-v1` Storage bucket'ı: `public=false`,
  `application/octet-stream` ile sınırlı, obje başına 44.040.192 bayt (42 MiB).
- `public.lanu_media_objects` tablosu RLS etkin; `anon` ve
  `authenticated` rollerine doğrudan tablo izni verilmemiş, servis rolüyle
  backend üzerinden erişim öngörülmüş.
- Storage `objects` tablosunda bu kova için anonim erişim açan bir politika
  görülmedi. Bu, uçtan uca yetkilendirme ve imzalı URL akışının başarıyla
  test edildiği anlamına **gelmez**.
- Kota SQL'i 512 MiB proje, 256 MiB gönderen ve son 24 saatte kullanıcı
  başına 4 rezervasyon sınırı ile sınırlı; yalnız karşılıklı rehber kaydı
  olan ve desteklenen alıcıyla rezervasyon yapabilir.
- Migration kova ve SQL tablolarını hazırlar; mevcut yayınlanan
  `lanu-relay` Edge fonksiyonunun büyük medya upload/download yetkileri
  uygulandığı henüz kanıtlanmadı. Büyük dosya gönderimi #85 açıktır.

## Geri uyumluluk ve koruma

- Bu SQL tekrar çalıştırılmaz; canlı migration kaydı mevcuttur. Kodun
  kaynak eşitlemesi, canlı veritabanı üzerinde DDL/DML yazımı değildir.
- 42 MiB Storage üst sınırı ile Android'deki 64 MiB yerel şifreleme
  katmanı aynı şey değildir. Gerçek taşıma entegrasyonunda
  **ciphertext + GCM tag toplamı** en fazla 42 MiB olmalı;
  aşım güvenli reddedilmeli, parça/retention politikası ayrıca tasarlanmalıdır.
- SQL'de `private_media_version` ile eski istemcilere medya olaylarının
  erişimi kısıtlanır. Relay gerçek protokol uygulaması ve mixed-version
  testleri olmadan özellik açılmamalıdır.
- Kullanıcı verileri, mevcut mesajlar, kimlik/PIN, Room şeması veya imzalı
  APK üzerinde bu eşitleme hiçbir değişiklik yapmaz.

Kanıt türü: bağlı Supabase projesinde salt-okunur envanter/DDL incelemesi.
Canlı Storage PUT/GET, yeni dosya üretimi veya iki telefon kabulü yapılmadı.
