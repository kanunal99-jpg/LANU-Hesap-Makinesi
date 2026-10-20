# P0 — Artan PIN deneme kilidi, kanıt ve sınırlamalar (20.10.2026)

## Risk
PIN hâlâ 4–12 rakam kabul ediyor. Önceki beş yanlış denemede her defasında sadece 30 saniye bekliyordu; bekleme bitince uygulama tekrar beş tahmine izin veriyordu. Kısa PIN'ler için uzun süreli kaba kuvvet denemelerini ucuz hale getiriyordu.

## Yeni uygulanmış davranış
- Her beş yanlış PIN girişinden sonra ardışık kilit süresi: **30 saniye → 2 dakika → 10 dakika → 1 saat → 12 saat**.
- Bu kademeler yalnız başarılı PIN doğrulaması (veya başarılı, etkin biyometri) sonrasında sıfırlanır. Süreyi beklemek yeni güvenlik seviyesini sıfırlamaz.
- Kademe `pin_lockout_strikes` Room ayarıyla yeniden başlatmalar arasında saklanır; mevcut `pin_attempt_state` biçimi değiştirilmez.
- Bozuk kademe kaydı hafif kilide sıfırlanamaz; azami kademe kabul edilir. Maksimum bekleme 12 saattir.
- İlk 5 yanlış PIN davranışı ve mevcut 30 saniyelik UX korunur.

## Otomatik kabul testleri
- Yeniden başlatılan repository sonrasında ikinci 5 deneme kilidi 2 dakika.
- Kilit süresi dolmadan doğru PIN reddi ve 1 saniye kalan süre.
- Gerçek doğru PIN sonrası ilk kademe yeniden 30 saniye.
- Bozuk sayaçta 12 saatlik fail-closed kademe; süre yükseltmeleri ve üst sınır.

## Bilinen riskler
- Android cihaz saatini ileri almak, kalıcı duvar-saati tabanlı kilidi hâlâ etkileyebilir. Tek başına uygulama içi sayaç donanımsal rate-limit yerine geçmez.
- PIN özetleri hâlâ tam DB şifrelemesinin yerine geçmez. Gelişmiş cihaz-bağlı Keystore/PIN modeli ve parola dışı kurtarma ayrı P0.
- Eski APK'ler ile veri kayıpsız yükseltme, kararlı imza kurulmadan garanti edilemez.
- Değişiklik DB şemasını değiştirmez.
