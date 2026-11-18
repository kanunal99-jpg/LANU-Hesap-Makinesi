# Arka plan mesaj ve çağrı onarımı — 18.11.2026

## Kullanıcı bildirimi ve doğrulanmış başlangıç
- Beklenen mesajın öğlen yerine yaklaşık 16:40’ta kullanıcı mesaj yazınca görünmesi; uygulama açılmadan çağrı alamama; arayan tarafta gerçek çalma geri bildirimi gereksinimi.
- Başlangıç main `7bf4e205c966ffa1e0cfb64ea8784d4b29a91e53`, son mevcut yayın signed.1314; main CI 43309972800 başarılı. Kullanıcının kurulu sürümü bilinmiyor.
- Üretim relay v18 kaynakları ve yalnız toplu kayıtlar incelendi. İki çağrı ve iki mesaj cihaz kaydı var; FCM yapılandırma şekli geçerli. Bu, FCM’in cihazda teslim edildiği anlamına gelmez.
- 18 Kasım 12:00–13:00 Türkiye (09:00–10:00 UTC) aralığında mevcut kalıcı mesaj satırı görülmedi; 15:00–16:00 Türkiye aralığında iki mesaj ve iki wake claim var. Tekil gönderen/alıcı/içerik sorgulanmadı. Bu nedenle bildirilen öğlen olayının gönderici kuyruğu, alıcı Doze veya başka bir nedeni kesinleştirilemedi. Kayıt yokluğu, mesajın gönderilmediğinin kesin kanıtı değildir (silme/saklama etkileri olabilir).

## Somut kod eksikleri ve onarım
1. Mesaj WorkManager KEEP: eski iş inbox snapshot’ını aldıktan sonra yeni uyarı gelirse yeni iş atlanıyordu. APPEND_OR_REPLACE mevcut işlemi iptal etmeden takip eşitlemesi kurar; başarısız zincir yeni uyarıyı zehirlemez.
2. Android 12+ acil işler expedited ve kota bitince normal iş fallback kullanır. API23–30’da gereksiz foreground-service/bildirim açılmaz. Ağ ve önceki açık rıza şartları korunur.
3. Mesaj tokenı için yalnız tek seferlik yenileme vardı. Çağrıdaki gibi 12 saatlik, ağ kısıtlı ve opt-out ile duran onarım eklendi; token/kimlik iş verisine yazılmaz. FCM onDeletedMessages mevcut izinle kalıcı inbox’ı eşitler.
4. Gösterilmiş, geçerli, opt-in çağrı uyarısı kısa bir iş ile mevcut kimliğin E2EE davetini doğrular. Özel UI/giriş açılmaz; medya başlamaz. Yalnız callSync çalışır; çağrı izni sohbet eşitleme iznine dönüşmez. Tek model ve en çok özgün 45 saniyelik çağrı yaşamı korunur.
5. Ayrı E2EE RINGING sinyali eklendi. Arayanın HTTP ACK’i çalma sayılmaz. Call/peer/video eşleşmesi ve DIALING aşaması olmadan ringback yok; ACCEPT/END/REJECT sonrası geç gelen RINGING yok sayılır. Eski istemci yeni kontrolü tanımadan atlayabilir; gerçek ringback için iki APK’nın da güncel olması gerekir.
6. Geciken INVITE artık yeni bir 45 saniye başlatmaz. Görünür host bildirimi devralır; çağrı bitince eski uyarı temizlenir. Sistem yönetimli insistent zil, kanal/DND tercihlerini korur. Yanıtla yeşil, Reddet kırmızı.
7. Mesaj FCM taşıması da geçici 408/429/5xx/taşıma hatalarında aynı iki alanlı ipucuyla en fazla üç deneme kullanır. Kalıcı auth/token hataları tekrar edilmez; şifreli mesajın kabulü push başarısına bağlanmaz.

## Doğrulama ve kalan saha kapıları
- Yeni Android regresyonları: takip işi, kapatma/periodic repair, gerçek sinyal öncesi ringback yokluğu, yanlış/eski oturum ve ACCEPT sonrası RINGING, host olmadan davet doğrulama ve mikrofon açılmaması, eski/izinsiz wake.
- Sunucu hedef testi: 12/12 geçti (call_wake_protocol.test.mjs). Tam protokol suite ve Android/Lint/R8/OTA CI ayrıca izlenir; bu belge başlangıçta CI başarısı iddia etmez.
- Fiziksel A55 ve ikinci telefonda, iki tarafta güncel APK + opt-in + Android bildirim/tam ekran izniyle: ekran kilitli ve uygulama arka planda mesaj; uygulama açık/kapalı çağrı; yanıt/ret/iptal; iki yönlü ses/video; Wi-Fi/mobil geçişi; DND/pil kısıtı; 10–30 dakika Doze sonrası tekrar.
- Force-stop, internet yokluğu, kapalı Android izni veya GMS bulunmaması için anlık teslim garantisi yoktur. Ücretli veya bilinmeyen alternatif push/TURN hizmeti açılmadı. Üretim FCM anahtarı/token/kişi/mesaj/SDP loga veya repo’ya eklenmedi; Room/kimlik/PIN/signer değişmedi.
- Son HEAD CI, main CI, imzalı APK ve HTTP/SHA/signer doğrulaması ile iki fiziksel cihaz kabulü ayrı raporlanmalıdır; eski yayın bu onarımı içermez.
