# LANU Hesap Makinesi — 10 ana özellik geliştirme programı (2026-10-27)

Durum: **plan ve kabul kriterleri; kodlanmış veya cihazda doğrulanmış sayılmaz.**

## Kapsam
1. WebRTC hızlı bağlantı: bağlantı kurma gecikmesi, ICE alternatifleri, başarısızlık telemetrisi (kişisel veri olmadan).
2. Ağ kopması/yeniden bağlanma: sınırlı exponential backoff, iptal, ağ geçişi, eski callback koruması.
3. Gizli mesaj bildirimleri: içerik, gönderen, önizleme, kilit ekranında hassas veri yok; izin ve kullanıcı tercihi zorunlu.
4. Sesli arama bildirimleri: Android sürümlerine uygun izin/kanal ve foreground service davranışı.
5. Bildirim kapatma/çakışma koruması: tercih tutarlılığı, tekrar bildirim ve ses engelleme.
6. E2EE/veri güvenliği: mevcut anahtar, PIN, Room ve geçmiş korunur; şifreleme iddiası kanıtsız yükseltilmez.
7. Performans: soğuk açılış, ANR, bellek, pil, Compose ve ağ yükü ölçülür.
8. Mesaj güvenilirliği: çevrimdışı kuyruk, idempotency, teslim durumları, veri kaybı/tekrar engelleme.
9. Hata kurtarma: ana servis → alternatif → fallback → hata yönetimi → güvenli varsayılan → log/izleme → smoke test.
10. OTA/APK yayın: son main SHA, CI, imza sürekliliği, SHA256, Releases asset read-back, geri dönüş.

## Uygulama sırası
P0: (3,4,5) bildirim mahremiyeti ve izinler; (1,2) bağlantı güvenilirliği.
P1: (8,9) mesaj bütünlüğü ve hata kurtarma; (6) veri güvenliği regresyonu.
P2: (7) ölçüme dayalı performans; (10) OTA/yayın kapısı.

## Zorunlu kabul
- Her başlık için mevcut durum, ölçüm, test kanıtı, risk ve alt kategori puanı ayrı kaydedilir.
- 80 altı kabul edilmez; 90+ hedef, 95+ ileri hedef; **kanıt olmadan puan artırılmaz**.
- Android API23+, Android 34 runtime, CI ve iki gerçek cihaz (Samsung A55 + ikinci telefon) ayrı kapılardır.
- Canlı Supabase, Room verisi, PIN, E2EE anahtarları, imza anahtarı ve kullanıcı verileri izinsiz değiştirilmez.
- Başka PR otomatik merge edilmez; bu planın açılması release/uygulama dağıtımı değildir.
