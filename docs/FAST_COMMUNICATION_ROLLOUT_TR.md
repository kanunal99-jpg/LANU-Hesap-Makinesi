# 08.11.2026 — İki kişilik iletişim hızlandırma

Kapsam: [PR #358](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/pull/358). Bu belge tasarım ve doğrulama sınırlarını anlatır; yayın/CI/iki fiziksel cihaz kabulü birbirinden ayrıdır. Son HEAD kontrolleri PR Checks üzerinden izlenir.

## Uçtan uca davranış

- Görünür, yetkili özel alan bir paylaşılan WebSocket açar. İstek P-256 imzalıdır; sunucu mevcut kimlik/saat/tek kullanımlık nonce doğrulamasından sonra yalnız o sahibin özel Realtime kanalını seçer. Servis anahtarı Android'e gönderilmez. URL'de kullanıcı token/kimliği yoktur.
- Kalıcı ve karşılıklı yetkilendirilmiş mesaj/arama yazımından sonra yalnız `message` veya `call` sabit olayı yayımlanır. İçerik, SDP, anahtar ve kişi adı taşınmaz. Android mevcut HTTP sync/şifre çözme/Room/ACK akışını tetikler. Bildirim teslim veya okundu kanıtı değildir.
- Socket kapanışı/90 saniye Edge ömrü/yetki hatası sonrası beş saniyelik yeniden deneme; eski HTTP polling sürekli bağımsız fallback olarak kalır. Korumalı ekran kapanınca abonelik iptal edilir. Kilitli hesap makinesindeki izinli gelen arama eski polling/FCM yolunu korur; WebSocket açmak özel alana erişim vermez.
- Canlı olay kanalı varken olayla tetiklenen chat sync için en az 1000 ms aralık vardır; mevcut 2500/5000 ms periyodik fallback korunur. 429 cooldown asla atlanmaz. Sunucunun 120 istek/dakika sınırı değiştirilmez. Çok yoğun kullanımda throttle mümkündür.
- Yeni metin önce aynı şifreli kalıcı kuyruğa kaydedilir; yazma alanı ağ ACK'sini beklemez. Görünür oturum ayrı, seri ve iptal edilebilir gönderim girişimi yapar. Kişi çözümlemesi atlanır; doğrulanmış alıcı bağları, eski kuyruk sırası, değişmez UUID/ciphertext, en fazla beşlik batch ve 429 retry korunur. Ortak veri kilidinde devam eden işlem varsa güvenli sıra beklenir.
- Sunucuda bağımsız mutual/duplicate okumaları paraleldir. Yeni insert `return=representation` ile kalıcı satırı döndürür; eski/boş/yarışan duplicate yanıtında gerçek satır tekrar okunur. Yalnız doğrulanmış sunucu makbuzu SENT olur.

## Trickle ICE ve eski APK

İlk offer gerçek adayları içeren eski non-trickle sözleşmesini korur. `a=x-lanu-trickle:1` yalnız gönderilen SDP'de eklenir; native SDP normalizasyonunun bu uzantıyı silmesine dayanılmaz. Eski peer bilinmeyen SDP attribute'unu yok sayıp tam answer gönderir; aday sinyali ancak yeni yetenekli peer doğrulandıktan sonra gönderilir. Yeni alıcı answer'da toplamanın bitmesini beklemez. Adaylar mevcut E2EE sinyalleşmesinde en fazla dörder, en fazla dört batch/negotiation olarak aktarılır. Ufrag, peer/call, TTL, alan türleri ve boyutlar doğrulanır; eski ICE epoch adayı uygulanmaz. Native okuma, callback ve disposal aynı dispatcher'da sıralanır. Gerçek iki yönlü RTP yokken CONNECTED üretilmez.

Gerçek Android native ICE/DTLS/SCTP kabul testinde SDP'den adaylar çıkarılır, ayrı adaylar eklenir ve gerçek şifreli paket aktarımı kontrol edilir. Bu test mikrofon/kamera kalitesi veya iki fiziksel operatör ağı kabulü değildir.

## Hızlı kişiler ve davet

Kişiler yıldızlanabilir; favori ve son kişiler özel alanda en çok iki hızlı satırda sohbet/ses/video kısayolu sunar. Arama mevcut izin ve onay akışından geçer. Tercih mevcut Room settings tablosunda saklanır; şema/veri migration'ı yoktur.

QR yerel ZXing ile üretilir; kullanıcı seçtiği görselden yerel olarak kod içe aktarır. Görsel boyutu/byte limiti ve kod biçimi doğrulanır. `lanu-contact:v1:` daveti özel alandaki ekleme alanına yapıştırılabilir. QR/davet yalnız kişi önerisidir: açık kaydet onayı ve mevcut karşılıklı kimlik doğrulama şartı sürer. Exported Activity/Intent/deep link veya gizli giriş atlaması eklenmez. QR dialogu ekran korumalıdır.

## TURN: destek ile gerçek hizmeti ayır

İsteğe bağlı yetkili sunucu adresi, kullanıcı adı ve parola özel ayardan girilir. Credentials Android Keystore destekli ayrı şifreli preferences içinde tutulur; düz metin Room/log/URL/backup'a eklenmez. Geçersiz veya açılamayan ayar mevcut çok sağlayıcılı STUN yolunu bozmaz. TURN için yalnız doğrulanmış kullanıcının kendi/yetkili sunucusu kullanılır; uygulama hesap açmaz, abonelik veya ödeme başlatmaz. `turn:host:3478?transport=udp` / `turns:host:443?transport=tcp` biçimi desteklenir.

**Gerçek, erişilebilir ve kimlikli TURN sunucusu sağlanmadı.** Bu nedenle zor NAT fallback'i çalışır kabul edilmedi. Açık kaynak coturn'un yazılım bedelsizliği, barındırma/trafik bedelsizliği değildir. Ayar kaydı bağlantı testi değildir. Yeni cihazda bu ayrı credentials yeniden girilir; mevcut kimlik/mesaj yedeğiyle taşındığı iddia edilmez.

## Kanıt ve kalan kabul

- Yerel mevcut sunucu tabanı 139/139; anlık kanal ekleriyle 143/143; son iki REST fast-path testi ayrıca başarılı. Son tüm test sayısı CI'den alınır.
- Deno giriş dosyası tip kontrolü ve Android veri koruma/public disclosure/yıkıcı migration yasakları yerel kontrolde geçti.
- İzole `lanu-realtime-preview` v2 üzerinde sentetik iki kimlikle imzalı özel kanal, farklı HTTP çağrısından mesaj ve arama olayı, kalıcı HTTP fallback ve ACK sınandı. Özel anahtarlar yalnız test sürecinin belleğinde kaldı; gerçek kişilerin verileri kullanılmadı.
- Çalışma ortamının ağ vekili üzerinden bir mesaj olayı örneği 7266 ms sürdü. Bu tek, uçtan uca sentetik örnek iki Android cihaz gecikmesi veya 1 saniye/2–3 saniye hedefinin karşılandığı kanıtı değildir.
- Son HEAD Android unit/Robolectric, API34 native/Room ve R8 kabulü; main CI, aynı signer ile veri korumalı OTA, APK HTTP/SHA/signer read-back ayrı yayın kapılarıdır.
- Samsung A55 + ikinci fiziksel cihazda kilit/PIN, mesaj P50/P95, arama kabulünden çift yönlü ses, Wi-Fi↔hücresel, kopma, Bluetooth ve video kabulü açık kalır. Çalışan TURN olmadan zor NAT garantisi yoktur.

Geri dönüş: önceki Edge dosyaları ve önceki aynı imzalı APK kaynak kodu ileri versionCode ile yayımlanır; kullanıcı verisi/kimliği/Room silinmez. Yeni Android eski Edge'de WebSocket reddini HTTP fallback'e çevirir; yeni Edge eski Android protokolünü korur.
