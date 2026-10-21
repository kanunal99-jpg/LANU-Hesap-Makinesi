# LANU kodla mesajlaşma servisi

Telefon, SMS, e-posta veya paylaşılabilir kod ile oturum açma yoktur. Uygulama P-256 özel anahtarıyla her isteği imzalar. Kod yalnızca kişi bulmak içindir; iki kullanıcı birbirinin kodunu kaydetmeden mesaj gönderilemez. Kod yalnız açık kullanıcı isteğiyle değişir; hesap anahtarı ve sohbet kimliği sabit kalır.

Mesaj gövdesi cihazlarda ECDH + AES-256-GCM ile şifrelenir. Sunucuda kimliklerin açık anahtarları/kodları, karşılıklı rehber bağlantıları, zamanlar, teslim durumları ve şifreli mesajlar tutulur. Bu protokol ileri gizlilik veya bağımsız güvenlik denetimi iddiası taşımaz. Anahtar ilk kayıtta kodla doğrulanır ve cihazda sabitlenir.

## Doğrulama

```bash
npm ci --prefix server
npm test --prefix server
npm exec --yes --package=deno@2.9.6 -- deno check supabase/functions/lanu-relay/index.ts
LANU_RELAY_URL=https://PROJECT.supabase.co/functions/v1/lanu-relay node server/live-smoke.mjs
```

Yerel testler bellek deposu ve gerçek PostgreSQL motoru (PGlite) kullanır; canlı smoke gerçek HTTPS/Edge/Postgres üzerinde üç geçici kimlik oluşturur. Smoke çıktısındaki kimlikler yalnız test verisidir; özel anahtarlar yalnız süreç belleğinde kalır. Fiziksel Android cihaz doğrulaması ayrıdır.

## Yayın ve geri dönüş

LANU projesi: `talhknjcjaijqiqwdyhf` (Frankfurt). Servis: `https://talhknjcjaijqiqwdyhf.supabase.co/functions/v1/lanu-relay`. Oluşturmada bildirilen bedel 0 $/ay; ücretli plana geçiş kullanıcı onayı gerektirir.

`supabase/migrations/20261019073855_persistent_user_code_messaging.sql` yalnız LANU projesine uygulanır. Beş tabloda RLS etkin, `anon`/`authenticated` doğrudan erişimi kapalıdır. Servis, sunucu ortamındaki Supabase secret/service key ile erişir; bu anahtar Android'e veya depoya konulmaz. `verify_jwt=false` yalnız kendi P-256 istek doğrulaması kullanıldığı içindir. Tekrar istekler kalıcı nonce ve atomik hız sınırı ile reddedilir.

Edge giriş dosyası `supabase/functions/lanu-relay/index.ts`, bağımlılığı aynı dizindeki `core.mjs`. Geri dönüşte önceki Edge sürümü ve Android commit'i yeniden yayımlanır; tablolar veya kullanıcı verileri silinmez. Android Room şema sürümü değiştirilmedi; eski sütun adları veri kaybını önlemek için korundu. `LANU_RELAY_URL` ortam değişkeni derleme sırasında farklı HTTPS sunucusu seçebilir; sır değildir.

## Kullanıcı kimliği ve mevcut sınırlar

Kimlik Android Keystore korumalı depoda kalır. Parolalı kimlik yedeği AES-GCM/PBKDF2 ile şifrelenir; ayrı güçlü parola ve dosya olmadan başka cihazda aynı kimlik geri alınamaz. Paylaşılabilir kod tek başına kurtarma anahtarı değildir. Yedek yalnız kimliği taşır, eski cihazdaki mesaj/rehber arşivini taşımaz. Aynı anahtarla eşzamanlı çoklu cihaz senkronizasyonu desteklenmez.

Senkronizasyon gizli iletişim ekranı açık ve uygulama öndeyken yaklaşık beş saniyede bir yapılır. Uygulama kapalıyken push bildirimi yoktur. Bekleyen mesajlar bağlantı gelince tekrar denenir; sunucu onayı olmadan gönderildi/teslim/okundu gösterilmez. Ses/video arama ve medya aktarımı bu metin mesajlaşması sürümünün kapsamı dışındadır. Mevcut yerel arama demosu gerçek arama değildir.

### 21.10.2026 — Canlı LANU Supabase migration/cron eşitlemesi
- Repository SQL migration sürümleri bağlı Supabase LANU Code Messaging veritabanının gerçekten uygulanmış sürüm numaraları ile eşitlendi: `20261019073855`, `20261021160108`, `20261021160752`, `20261021160807`.
- `lanu-safe-retention-daily` 03:15 UTC / 06:15 Türkiye, `cron.schedule` ve azami 500 kayıt ile çalışır. `pg_cron` v1.6.4 canlıda.
- Operasyonel durdurma: yetkili SQL ile `select cron.unschedule('lanu-safe-retention-daily');`. İş mantığı sadece eski ACK edilmiş olayları, süresi geçmiş nonce'ları ve 90 gün eski, ayrıca bütün olayı silinmiş mesajları temizleyebilir. UnACKed olaylar saklanır.
- İlk kontrollü çalışma (21.10): events=0, nonces=34, deleted_messages=0; işlem sonrası 13 unACKed event, 2 mesaj değişmedi. İlk bağımsız zamanlanmış çalışma sonucu henüz kanıtlanmadı.
- PostgreSQL PGlite testleri base + retention işlevi migration'larını yükler; `pg_cron` uzantısı PGlite test ortamında olmadığından canlı Supabase job envanteri ayrıca doğrulanır.
