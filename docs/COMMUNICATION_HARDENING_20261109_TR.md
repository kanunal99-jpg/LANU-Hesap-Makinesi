# 09.11.2026 — İletişim teknolojisi ve altyapı sağlamlaştırması

Başlangıç: main `0c08d77`, imzalı 1176. Kullanıcı bu sürümde yapılan işin iki fiziksel cihazda doğrulandığını bildirdi. Bu kullanıcı kabulüdür; cihaz modeli, ağ matrisi veya sayısal P50/P95/dB ölçümü verilmedi. Yeni sürüm için fiziksel kabul ayrıca değerlendirilir.

## Gerçek korumalar

| Katman | Uygulama ve fallback |
|---|---|
| Yerel istek bütçesi | Tek HttpMessagingRelay örneğinde kimlik başına monoton kayan 60 saniyelik pencere; en fazla 112 istek, sıradan işlemler en fazla 84. Polling dahil kritik olmayan işlemler en fazla 104; son 8 kapasite callSend/callAck için korunur. Sunucunun 120/dakika limiti değiştirilmez. Bu işlem belleği içindeki dayanıklılık korumasıdır; güvenlik limiti sunucuda kalıcı nonce ile uygulanır. |
| Yoğunluk ve iptal | Permit bekleyen iş iptal edilebilir; iptal edilen bekleme gelecekteki kotayı tüketmez. İmza/timestamp permit sonrasında oluşturulur. HTTP 429 tüm aynı-kimlik işlemlerinde 5–120 saniyelik sunucu beklemesini korur; kontrol önceliği 429'u aşmaz. En fazla dört aktif kimlik penceresi tutulur. Room kuyruğu ve değişmez mesaj UUID/ciphertext korunur. |
| Arama kontrol zamanı | callSend/callSync/callAck için 6 saniye connect, 8 saniye read, 12 saniye toplam HTTP sınırı. Bağlı RTP kanıtı ve mevcut sınırlı retry/35 saniye kurulum bütçesi korunur. Sıradan mesajlar 35/20, kapalı büyük medya doğrulama yolu 110/100 saniye kalır; benzer adlı bilinmeyen action uzatılmış süre alamaz. |
| Yeniden bağlantı | Hazır olmayan kanal 10 saniyede iptal edilir. Hızlı açılıp kapanmalar üstel gecikme ve 0–1500 ms jitter ile 60 saniyeye kadar seyreltilir. Yalnız en az 20 saniye yaşayan hazır oturum backoff'u sıfırlar. Özel ekran çıkışı abonelik/bekleme/socket'i iptal eder. HTTP polling bağımsız kalır. |
| Sunucu kanal sağlığı | 10 saniyelik join deadline, doğrulanmış heartbeat ACK, kaçan ACK sonrasında iki socket'in kapatılması; 90 saniyelik mutlak yaşam sınırı. Geç callback kapanmış kanalı açamaz. Owner topic sunucuda imzalı kimlikten seçilir; içerik/SDP/anahtar paylaşılmaz. |
| Wake iletim hatası | En fazla iki yayın denemesi; ağ/408/5xx için tek 150 ms gecikme. 401/403/429 yeniden denenmez. Response stream her durumda kapatılır. Makbuz kaybı yalnız aynı sabit hint'i yineleyebilir, kalıcı mesajı çoğaltmaz. İçeriksiz hata kategorisi ve istemci sağlık sayaçları kullanılır; tanılama hatası kabul edilmiş mesajı bozmaz. |
| Ses/video ICE | Adayın mid ve m-line index'i birlikte bağlanır; ses ve video için ayrı ICE ufrag doğru seçilir. Session seviyesindeki RFC fallback desteklenir. Yanlış kanal/eski epoch adayı uygulanmaz. Geçerli ama kullanılamayan isteğe bağlı aday tek başına sağlıklı RTP'yi sonlandırmaz. |
| TURN hazırlığı | Yetkili TURN ayarı varsa ilk non-trickle SDP, yalnız erken STUN keşfiyle relay adayı gelmeden gönderilmez; gerçek relay adayı veya ICE tamamlanması beklenir, sekiz saniye üst sınır korunur. Ayar yokken mevcut hızlı yol aynıdır. Bu, gerçek TURN hizmetinin kurulduğu/erişiminin doğrulandığı anlamına gelmez. |

## Test ve yayın kapıları

- Bütçe sınırı, kontrol rezervi, saat geri gitmesi, 429, iptal ve yeni pencere testleri.
- Ayrı ses/video ICE kimliği, yanlış mid/index, eski epoch, session ufrag ve relay hazırlığı testleri.
- Sahte zamanlayıcıyla join timeout, heartbeat ACK/kayıp ACK, geç callback, 90 saniye kapatma; publisher transient/auth/429 ve tanılama hatası testleri.
- Eski istemci v1 sinyal/HTTP/FCM yolu, mevcut giriş/PIN, R8 native kabulü, Room ve gerçek eski imzalı APK→yeni APK yedi tablo OTA korunur. Son HEAD PR CI → main CI → APK/SHA/signer yayın/read-back ayrı kanıtlardır; sonuçlar PR/Actions/Release bağlantılarından alınır.
- Aynı imza ve paket adı korunur; veriler silinmez. SQLCipher, Double Ratchet, büyük medya ve hesap silme bu değişiklikle etkinleştirilmez. Ücretli altyapı açılmaz.

Geri dönüş: önceki Edge dosyaları uygulanabilir; Android kodu aynı sertifika ve ileri versionCode ile geri alınır. Room/kimlik/kuyruk sıfırlanmaz. Yeni Android eski Edge ile HTTP fallback'i; yeni Edge eski Android'in X-Lanu-Authorization ve v1 protokolünü korur.

## Puanlama

Son yayın tamamlandıktan sonra anayasa MADDE 1B ağırlıkları kullanılır: gizli giriş/gizlilik 30, mesaj 20, ses 15, video 15, gürültü/yankı 15, imzalı OTA/veri koruma 5. Puan mühendislik değerlendirmesidir. 1176'nın iki cihaz kabul beyanı dikkate alınır; yeni sürümün cihaz kabulü, nicel gecikme/gürültü ölçümü, doğrulanmış TURN ve bağımsız kurtarma/kriptografi kabulleri uydurulmaz.
