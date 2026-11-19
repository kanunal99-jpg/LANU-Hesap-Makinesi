# Ağ kurtarma, canlı yetki ve doğrulanmış yeni mesaj bildirimi

Başlangıç main: 6fad846955fb830f2a98e7d97357519383138e68 (1328). Bu belge tasarım/test kapsamıdır; fiziksel cihaz kabulü yerine geçmez.

## Seçenekler ve seçim

| Seçenek | Artı | Eksi |
|---|---|---|
| Yalnız ConnectivityManager callback | Yaşayan süreçte doğrulanmış ağ geri dönüşünde hızlı | Süreç ölüyken uygulamayı başlatmaz |
| Yalnız CONNECTED WorkManager periyodik iş | Kalıcı; FCM, süreç ve ayrı DeliveryOptions'tan bağımsız | En az 15 dakika aralık; Doze/OEM/kota geciktirebilir |
| Sürekli foreground service | Yaşayan bağlantı izlenebilir | Sürekli bildirim/pil maliyeti, modern Android başlatma sınırları |
| Callback + kalıcı WorkManager yedeği (seçildi) | Yaşayan süreçte hızlı tetikleme; süreç yokken kalıcı yedek; ağ yoksa CONNECTED koşulu bekler | Kesin saniye veya 15 dakikada bitiş garantisi değildir; force-stop ve OEM yasaklarını aşmaz |

Yeni MessageWakeRecovery mevcut mesaj bildirim tercihine bağlıdır. Ayrı arka plan DeliveryOptions kapalı kalsa da çalışır; o seçenek değiştirilmez. Başlangıç ve başarılı opt-in kayıt tamamlanmasında unique KEEP periyodik kurtarma ve ağa bağlı tek iş kurulur. Network callback aynı tek işi KEEP ile tetikler; ağ dalgalanması bekleyen işleri çoğaltmaz. Tercih kapanınca dinleyici, periyodik ve tek iş iptal edilir. Tercih kapalıysa yeni ağ işi kurulmaz. WorkManager verisine kimlik, mesaj, FCM token, gönderen, sohbet veya zaman damgası konulmaz. Hiçbir arka plan işi yeni kimlik oluşturmaz.

Android dokümanı: https://developer.android.com/reference/androidx/work/PeriodicWorkRequest ve https://developer.android.com/develop/background-work/background-tasks/broadcasts . Manifest CONNECTIVITY_ACTION modern Android için süreç başlatan bir kurtarma çözümü değildir. Mevcut WorkManager kendi boot/scheduler desteğini kullanır; gizli foreground service veya yeni ücretli servis yoktur.

## Kilit ve ağ öncesi yetki

MessageSyncSession yalnız background mesaj sync coroutine context'ine taşınan canlı tercihtir. CommunicationRepository syncMutex elde edildikten hemen sonra kontrol eder. Repository relay dekoratörü tüm iç/nested register/sync/ack/send isteklerinden önce aynı yetkiyi kontrol eder. MessagingRelay bütçe beklemesinden sonra tekrar kontrol eder; CancellableHttp gerçek socket enqueue öncesinde kontrol eder. Son enqueue kontrolü ile kalıcı opt-out commit'i aynı requestGate üzerinde serileştirilir: opt-out commit'inden sonra eski session yeni socket başlatamaz. Önceden başlamış istek iptal edilene kadar in-flight sayılır; geçmişte başlamış paket geri alınamaz. Yetki iptali CancellationException türüyle yayılır, Worker disabled sonucu üretir. Foreground, çağrı ve tercih iptali/token revoke akışlarına bu background context'i taşınmaz.

Testler: gerçek repository kilidini başka eşitlemeyle tutarken background waiter başlatılır, yetki kapanır, kilit açılır; sıfır yeni request. Ayrı test register yanıtı ile sync başlangıcı arasında yetkiyi kapatır; sync isteği hiç başlamaz. Ayrı gerçek OkHttp testinde Call oluşturulurken tercih kapatılır; daha önce kontrol geçmiş olsa bile socket enqueue aşamasında sıfır callStart doğrulanır. Mevcut HTTP iptal/body sahipliği testleri korunur.

## Bildirim yalnız yeni gelen mesajdan sonra

FCM callback artık doğrudan mesaj bildirimi göstermez; yalnız uygun Worker'ı sıraya alır. Session sayacı yalnız kimliği doğrulanmış incoming mesajın ilk başarılı Room transaction commit'inden sonra artar. Replay, sunucuda zaten READ olan olay, gönderene ait olay, revizyon güncellemesi, silinmiş/gizlenmiş veya karantina olayı sayılmaz. Worker ancak bu sayı arttığında, canlı tercih açıksa ve özel alan görünür değilse genel bildirimi gösterir. ACK HTTP yanıtı kaybolsa bile gerçekten commit edilmiş yeni mesaj sayılabilir; ACK yine ayrı doğruluk kuralına bağlıdır. Şifreli mesaj kaydı olmadan uyarı yoktur; WorkManager/Keystore/ağ başarısızlığında spekülatif uyarı gösterilmez. Çağrı bildirimi ve 45 saniyelik çağrı süresi değişmez.

Testler: yeni commit sayacı=1; aynı olay replay=0; doğrulanamayan sonraki revizyon=0. Boş eşitleme bildirim=0; yeni gelen mesaj bildirim=1. İki saat gecikmiş FCM hint'i gerçek servis callback'ine verilince eşitleme koşulları sağlanmadan bildirim=0. WorkManager test sürücüsünde FCM olmadan CONNECTED koşulu sağlanınca sync çalışır; ayrı DeliveryOptions=false ve opt-out iptali ayrıca denetlenir.

## Yayın doğrulaması

Son HEAD Android unit/Robolectric, Lint, R8 ve API34 debug/release runtime; main CI; 828 ve önceki stabil APK üzerinden yedi tablo/PIN/kimlik/kuyruk koruyan imzalı OTA; public APK/SHA/signer read-back ayrı kapılardır. Fiziksel Samsung A55 + ikinci telefon testleri yapılmadan saha kabulü tamamlandı denmez. Bu Android değişikliği mevcut backend v22 receipt/lease sözleşmesini değiştirip kullanıcı mesajı veya kimliği silmez.
