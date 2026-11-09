# Yerel hız ölçümü, ağ geçişi ve çağrı hazırlığı

Başlangıç: imzalı 1186, main c288225. Yeni kodun kabulü son HEAD PR CI, main CI, aynı signer ile gerçek eski APK→yeni APK yedi tablo OTA ve APK/SHA/signer tekrar indirme kapılarıyla değerlendirilir. Cihaz kabulü ayrı kanıttır.

## Kullanım

Özel alan → iletişim ayarları → **Bağlantı ölçümü ve arama hazırlığı**. Ölçüm varsayılan kapalıdır; kullanıcı bu uygulama oturumu için açar. Mesaj gönderip/eşitleyip görüşme yaptıktan sonra Yenile ile sonuçlar okunur. Her aşamanın son 128 başarılı örneğinde nearest-rank P50/P95 ve tamamlanmayan işlem sayısı gösterilir. Hiç örnek yoksa süre gösterilmez. Temizle/kapat ölçüm neslini değiştirir; eski bekleyen işlem yeni oturuma örnek ekleyemez. Sayaçlar RAM'dedir; içerik, kişi, kimlik, IP, URL, SDP, token, wall-clock zaman, dosya veya telemetri yüklemesi yoktur.

Mesaj sunucu kabulü ve eşitleme/arama sinyali süreleri istemcinin permit beklemesi, HTTP ve yanıt doğrulamasını içerir. Bunlar iki telefon arasındaki uçtan uca teslim/çizim süresi değildir. Arama kurulumu yalnız kabul→taze çift yönlü ses RTP kanıtında; kurtarma yalnız transport kopması→taze çift yönlü ses RTP kanıtında ölçülür. ICE CONNECTED tek başına örnek üretemez. Bitmemiş/başarısız işlem başarılı percentile'a karışmaz; geç/negatif/180 saniye üstü saat örneği alınmaz.

## Ağ geçişi

Android 24+ varsayılan ağının eski/geç capabilities/lost callback'leri yeni varsayılan ağı canlandıramaz veya bozmaz. Android 23 mevcut çoklu-ağ fallback'ini korur. Callback aboneliği iptalde kapanır ve ağ bilgisi kalıcı yazılmaz.

Gerçek çağrı oturumu kendi cold ağ aboneliğini tutar. Validated yeni yol imzalı çağrı sinyali eşitlemesini hızlandırır; 2 saniyelik bildirim aralığı ve mevcut ortak HTTP kotası taşmayı sınırlar. Sağlıklı RTP sırf ağ bildirimi geldi diye kapatılmaz/yeniden başlatılmaz. Mevcut native FAILED/grace, iki gerçek ICE restart bütçesi, 25 saniye kurtarma deadline ve bağımsız iki saniyelik HTTP polling fallback'i korunur. Oturum bitince/özel sahip bırakılınca ağ işi iptal olur; geç olay yeni görüşme başlatamaz.

## Yetkili TURN tahsis testi

**İsteğe bağlı bağlantı aktarma sunucusu → Kaydedilen sunucuyu test et**. Yalnız kullanıcının kaydettiği ve kullanmaya yetkili olduğu sunucu; relay-only ICE, yerel data m-line ve en fazla 12 saniyelik bekleme. Mikrofon, kamera, ses odağı veya karşı eş SDP sinyali yoktur. Uzak description/veri gönderimi yoktur. Gerçek relay adayı yoksa başarı gösterilmez. Timeout/iptal/hata/geç callback sonrasında peer/channel/factory kaynakları bırakılır, kaydedilmiş credentials değiştirilmez. Native loglar mevcut sessiz initializer ile kapalıdır. Debug ve R8 native kabulü, kapalı loopback portuyla negatif tahsis/temizlik yolunu çalıştırır.

**Bu özellik bir TURN sunucusu kurmaz. Gerçek yetkili sunucu/credentials sağlanmadı.** Tahsis başarılı olsa bile iki telefon arası RTP, NAT geçişi veya tüm operatörlerde görüşme kanıtı değildir. Ücretsiz Edge/HTTP hizmeti UDP/TURN sunucusu yerine sunulmaz; ücretli barındırma veya bilinmeyen genel relay açılmaz.

## Arka plan hazırlığı

Yalnız yerel/okuma amaçlı denetim: arama tercihi, bekleyen iptal, sistem bildirim izni, gerçek arama kanalı ve sesi, pil optimizasyonu, mikrofon ve Bluetooth izni. Ekran otomatik izin istemez, push göndermez, pil/DND tercihini değiştirmez ve FCM teslimi garantisi göstermez. Telefon uygulama ayarlarına yalnız kullanıcı dokununca gidilir; dönünce Yenile. Google servisleri/OEM Doze/kilit ekranı/bildirim sesi ve Bluetooth fiziksel kabulü ayrıca gerekir.

## Kabul sınırı

Saf histogram nesil/iptal/percentile/boyut testleri; API23/24/34 eski ağ callback testleri; çağrı state-machine ağ/sağlıklı RTP/sona erme ve gerçek kurulum/kurtarma örneği testleri; API23/31/34 yan etkisiz hazırlık okuması; debug/R8 WebRTC native negatif tahsis testi. Hiçbiri fiziksel A55+ikinci OEM gecikme, dB, zil veya yetkili TURN pozitif saha testi diye raporlanmaz. Hesap makinesi, giriş/PIN, kimlik/Room/signer ve kapalı diğer özellikler korunur.
