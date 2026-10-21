# 21.10.2026 — P1 Çevrimdışı E2EE gönderi kuyruğu: 200'lü deterministik batch

## Mevcut durum
Room getPendingMessages tüm PENDING/WAITING satırlarını tek seferde belleğe alıyordu. Büyük bir çevrimdışı gönderi birikiminde aynı senkronizasyonda kontrolsüz çok sayıda şifreli içerik ve ağ isteği oluşturma riski vardı.

## Geliştirme
- Tek senkronizasyon turu için en eski 200 PENDING/WAITING mesaj, timestamp + id sırasıyla okunur.
- Sadece ilk 200 satır seçilir; kalan kuyruk ve SENT/DELIVERED/READ mesajlar silinmez ve değiştirilmez.
- İlk 200 mesaj sunucu tarafından kabul edilip durumları SENT olduğunda sonraki senkronizasyonda geriye kalan 15 mesaj sırayla seçilir.
- SQL sorgusu DAO imzası, Room v4 schema ve üst katman protokolünü değiştirmez.

## Kabul / test
- 215 bekleyen ve 5 gönderilmiş mesajdan ilk 200 deterministik seçilir; 220 satır korunan mesaj sayısıdır.
- İlk 200 satır teslimden sonra kalan 15 sonraki turda gelir; veri kaybı yoktur.
- Aynı timestamp'li mesajlar id'ye göre deterministik dizilir.
- GitHub Android/Robolectric, Room hash, lint, APK ve cert/SHA256 testleri yeşil olmadan tamamlandı yazılamaz.

## Açık
Gerçek cihaz/iki kullanıcılı server smoke, ağ zaman aşımı, büyük backlog throughput ve arka plan WorkManager scheduling ayrı P1 kabul konularıdır. Bu adım P0 veritabanı şifrelemesi veya APK stable imzası değildir.

### Puan / hedef
- Mevcut proje: 70/100.
- Bu paket PR CI ve release kanıtıyla geçerse mesajlaşma/performans kategorisinde küçük artış mümkün.
- Nihai hedef: 93/100; yüksek riskli üretim engelleri kapanmadan genel puan otomatik yükselmez.
