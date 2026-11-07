# İletişim performansı — 7 Kasım 2026

Başlangıç: main 212363c; başarılı CI 41273776448; imzalı APK 1114.
Hesap makinesi kaynakları, giriş/PIN, veritabanı şeması, E2EE formatı ve ücretli servisler değiştirilmez.

| Sorun | Uygulanan çözüm | Sınır / geri dönüş |
|---|---|---|
| Her poll için tekrar kayıt round-trip | Başarılı, tam kimlik eşleşmesine bağlı 30 saniyelik RAM kaydı | Her işlemde yerel kimlik ve hesap silme kilidi denetlenir; başarısız sync, süre aşımı, geri saat veya farklı kimlik yeni kayıt ister. Soğuk başlangıçta kayıt zorunlu. |
| Boş outbox bile gönderimi 5 saniye erteliyordu | Bekleme yalnız gerçek gönderim/kişi çözümleme girişiminde başlar | Mevcut 5 mesaj / 5 saniye ve aramada 1 mesaj bütçesi korunur. |
| Aktif sohbetin 5 saniye poll aralığı | Yalnız açık sohbet için 2,5 saniye, diğer ön plan ekranlarında 5 saniye | Hata backoff'u ve lifecycle iptali korunur. Gerçek ağ P50/P95 ölçülmedi. |
| Aynı turda iki READ ACK | Yeni/eski okundu listesi tek, benzersiz, en çok 200 kimlikli ACK | Sunucu sözleşmesi değişmez; alıcı doğrulaması ve kalıcı kayıt ACK'den önce tamamlanır. |
| Arama hazırlığı outbox'ı boşaltmayı bekliyordu | Kimlik/kişi/gelen olay senkronu sürer; hazırlık outbox göndermez | Normal mesaj sync'i kuyruğu sonradan işler. Önceden başlamış bir sync mutex'i hâlâ beklenebilir. |
| Bazı OEM'lerde donanım ses işleme uygun olmayabilir | Özel ayarlarda, sonraki arama için WebRTC yazılım AEC/NS seçimi | Varsayılan otomatik mod aynı. Açık görüşme yeniden kurulmaz; donanım ve yazılım filtreleri üst üste eklenmez. Bozuk tercih otomatik moda döner. Fiziksel kalite ölçümü yok. |
| Kesilen dosyanın tümünün yeniden indirilmesi | En çok 3 girişim; exact Content-Range doğrulamasıyla kalan ciphertext'i al | Range desteklemeyen 200 yanıtı baştan yazar; 403/bozuk başlık/bütünlük/TLS hatasında güvenli kapanır. Beş dakika toplam bütçe. |
| Yanıt başlığı sonrası iptalde bloklanan soket | Mesaj, TUS ve dosya gövdelerinin tamamında ortak coroutine/socket sahipliği | İptal soketi kapatır; geç yanıt kapatılır; eksik indirme dosyası silinir. |

## Dosya boyutu ve etkinlik kapısı

Sunucu rezervasyon limiti 40 MiB plaintext / 41.953.280 ciphertext bayttır.
Mevcut APK'da büyük dosya UI bayrağı varsayılan kapalıdır; bu çalışma bayrağı açmaz,
sunucu şemasını veya Storage kotasını büyütmez. Mevcut küçük ek dosya akışı korunur.
#331'deki ayrı sunucu/arayüz aktivasyonu tamamlanmadan “40 MiB kullanıcıya açıldı” denmez.

## Doğrulama

Regresyonlar gerçek Room + E2EE fixture'larıyla kayıt önbelleği, rollback saat,
ACK tekilleştirme, ilk mesaj gecikmesi, çağrı/outbox ayrımı ve batch bütçesini denetler.
Yerel HTTP interceptor testleri Range/restart, sahte Range, hash, sınırlı yeniden deneme,
iptal ve dosya temizliğini denetler. API23/34 ses tercihi saklama/bozuk veri testleri vardır.
Önceki WebRTC/Room/hesap makinesi testleri CI'da korunur.

Bu belge uygulama kararlarını açıklar. Son HEAD CI/Release ve fiziksel A55 + ikinci
telefon kabulü ayrı kanıtlardır. Ölçülmemiş hız, dB, netlik veya toplam puan artışı iddia edilmez.
