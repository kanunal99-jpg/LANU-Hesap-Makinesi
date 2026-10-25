# LANU — Premium teslimat merkezi ve arka plan kontrolü

## Kullanım ve gerçek kapsam

Güvenli alanın üst çubuğundaki bulut simgesi **Teslimat merkezi**ni açar. Panel son başarılı sunucu kontrolünü, arka plan tercihini, Wi-Fi/kotasız ağ seçimini ve manuel kontrolü gösterir. Son kontrol teslim/okundu onayı değildir; bu bilgiler her mesajın kendi durumunda görünür.

Arka plan kontrolü ilk kurulumda **kapalı**, kotasız ağ tercihi **açık** gelir. Kullanıcı açarsa Android WorkManager işi en az 15 dakikalık periyotla, ağ ve düşük pil kısıtları altında planlanır. Kontrol zamanını Android/Doze/OEM geciktirebilir; uygulama zorla durdurulduysa yeniden açılması gerekir. Bu paket FCM/HMS push veya anlık teslim garantisi vermez; Google servisleri gerektirmez.

## Mimari ve kritik zincir

1. Ana yol: mevcut açık uygulama `CommunicationViewModel` → `CommunicationRepository.synchronize`.
2. Alternatif: açık kullanıcı tercihiyle `BackgroundDeliveryWorker` → aynı singleton repository/Room/E2EE akışı; aktif sohbet parametresi yok, dolayısıyla arka planda okundu onayı yok.
3. Fallback: planlayıcı başlatılamazsa panelden **Uygulama açıkken sohbetleri yenile**; bu mevcut gerçek ön plan akışını kullanır. Bekleyen mesajlar silinmez.
4. Hata/güvenli varsayılan: kapalı veya bozuk tercih, eksik/okunamayan kimlik, kota veya ağ hatası gerçek başarı olarak gösterilmez. Background mevcut kimlik şartı repository içinde de doğrulanır; yeni hesap/anahtar yaratılmaz.
5. Yerel izleme: yalnız boolean tercihler, zaman damgası ve fixed outcome enum. Mesaj, kod, anahtar, PIN, ham exception, SDP veya uzaktan telemetri yoktur. WorkManager veritabanına hassas payload yazılmaz; otomatik Android backup korumaları sürer.
6. Smoke: yeni testler + bütün mevcut JUnit/Robolectric/lint, API34 debug/release veritabanı kabulü, minified release APK, aynı sertifika/SHA256 ve anonim public download read-back. Fiziksel kabul ayrı kapıdır.

`DeliveryDeviceAcceptanceTest` gerçek Android WorkManager üzerinden worker'ı reflection ile başlatır ve opt-out halinde ne kimlik/ağ akışına girildiğini ne de başarı zamanı yazıldığını doğrular. Bu test debug ve R8 release APK üzerinde çalışır; R8 sonrası worker adı/constructor ve özel WorkManager initialization yolu da bu kabulün parçasıdır. Test yalnız izole emulator/test kurulumunda çalıştırılır, gerçek hesap veya backend fixture kullanmaz.

## Sınırlar ve maliyet

- WorkManager **2.11.2** sabittir; 2.12 API24 minimum istediği için API23 desteğini kıracak yükseltme yapılmadı. `work-gcm`, FCM veya yeni cloud servis eklenmedi.
- Periyodik iş 15 dakika, low-battery guard, 90 saniye işlem bütçesi ve en fazla 2 exponential retry içerir. Her repository turu mevcut beş giden istek bütçesini ve kalıcı mesaj retry zamanlarını korur.
- Manuel kontrol, arka plan açık olmasını gerektirmez; ağ tercihini yine uygular. `UNMETERED` çoğunlukla Wi-Fi'dır; Android'in kotasız işaretlediği başka ağlar da kapsanabilir. Kesin bir Wi-Fi transport garantisi olarak gösterilmez.
- Tekil iş isimleri OTA boyunca sabittir. Worker adı/iki argümanlı constructor `@Keep` ile korunur. Normal açılış var olan periyodu KEEP eder; kullanıcı ağ tercihini değiştirince önceki manuel/periyodik işler iptal edilip daha sıkı koşulla yeniden planlanır. Worker başlamadan güncel kotasız tercihini ayrıca denetler.
- Kullanıcı kapatınca tercih önce kaydedilir, ardından işler iptal edilir. Zaten sunucuya ulaşmış bir HTTP isteği geri alınamaz; iptal sonraki işi/isteği ve coroutine/OkHttp zincirini durdurur.
- Yerel Room v4, PIN, hesap geçmişi ve E2EE anahtar/zarf biçimi değiştirilmez. SQLCipher, büyük medya ve gerçek WebRTC bu pakette tamamlanmış sayılmaz.

## Kapanış için kalan cihaz kabulü

- Samsung ve Xiaomi'de uygulama arka planı, kilit, düşük pil, Doze ve yeniden başlatma sonrası veri kaybı olmadan kontrol.
- Kotasız tercihinde mobil ağda istek olmaması; tercihi değiştirirken devam eden işin iptali; tercih kapalıyken ağ/kimlik üretimi olmaması.
- Eski imzalı sürümden yeni APK'ya kurulum; PIN açık/kapalı, kişi/kod, geçmiş, mesaj, küçük ek ve bekleyen kuyruk korunumu.
- Gelen mesajın görünmesi ile teslim/okundu durumunun ayrımı; arka planda yeni okundu onayı gönderilmemesi.
- R8 release'te Android gerçek worker başlatma ve GMS olmayan üreticide JobScheduler kabulü. Birim/emülatör sonucu tek başına fiziksel kabul değildir.

Kaynaklar: [Android WorkManager sürümleri](https://developer.android.com/jetpack/androidx/releases/work), [iş istekleri ve minimum periyot](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work), [işlerin iptali ve durdurulması](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work).
