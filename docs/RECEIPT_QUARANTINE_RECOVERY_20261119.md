# Üç bulgu için düzeltme ve doğrulama

Başlangıç: b06d97954a2167fa6f7b46abe4b61a03d73b72f9. Bu belge test kapsamıdır; CI veya fiziksel cihaz kabulünün yerine geçmez.

## Kritik: eksik gönderici anahtarı

CommunicationRepository eksik peer anahtarını olay bazında RejectedCiphertext olarak ayırır. Başarısız olayın Room işlemi geri alınır; ayrı işlemle app_settings/lanu_quarantine_v1 içine yalnız mesaj/olay kimliği, revizyon ve sabit neden yazılır. En fazla 128 yerel metadata kaydı vardır. Eski metadata çıkarılması sunucudaki ciphertext'i silmez veya ACK göndermez. Sonraki sağlam olaylar, outgoing kuyruk ve kalıcı sayfalama devam eder. Anahtar geri geldiğinde aynı olay yeniden doğrulanır; yalnız başarılı revizyon karantinayı temizler. Karantina ve event cursor kimliğe bağlı ayarlardır; farklı kimlik restore ederek eski verinin üstüne yazma engellenir. Room şeması, PIN, kimlik veya hesap makinesi değişmez.

Test: 200 anahtarsız olaydan oluşan sayfa; yalnız 128 metadata, sıfır bozuk mesaj/revizyon/ACK; repository yeniden kurulduğunda kalıcı cursor üzerinden sonraki sağlam sayfa işlenir. Ayrı testte anahtar geri geldiğinde aynı olay başarılı olur ve marker kalkar. Önceki bozuk AEAD tam sayfa testi korunmuştur. Metadata sınır/revizyon testi eklenmiştir.

## Kritik: FCM kabulünden sonra gerçek teslim kurtarma

Google kabulü yalnız accepted sonucudur. SQL işi pending tutar; gerçek alıcı lanu_ack işleminin delivered_at güncellemesi işi done yapar. Sender ACK işi kapatamaz. İlk gönderim dahil en fazla dört taşıma denemesi vardır; kabul sonrası gecikmeler 2 dakika, 10 dakika ve 1 saattir. 24 saat sonunda push kurtarma kapanır, mesaj/olay silinmez; mevcut foreground sync fallback devam eder. Google'da bekleyen gizli içeriksiz mesaj hint TTL'i 24 saat, Android kabul aralığı 24 saat + 2 dakika taşıma payıdır. Görünür mesaj bildirimi en fazla 120 saniye ve onlyAlertOnce; çağrı TTL/kabul/çalma 45 saniye olarak korunur. 24 saat HTTP isteği tutulmaz: mevcut tek deneme OAuth+FCM sınırı en fazla 20 saniyedir.

Her lease öncesi canlı opt-in, karşılıklı kişi kaydı, mesaj silinmesi ve gerçek teslim tekrar kontrol edilir. Stale lease tamamlaması reddedilir. Eski relay'in done sonucu rolling deploy sırasında accepted sayılır. Yeni migration yalnız son 24 saatteki mevcut, onaysız, hâlen karşılıklı ve opt-in iş metadata'sını açar; canlı lease'i korur. Kullanıcı mesaj içeriği değiştirilmez. Eski Android sürümü 2 dakikadan eski hint'i reddedebilir; tam 24 saat offline kurtarma için yeni APK gerekir. OEM force-stop, GMS yokluğu veya cihaz izin engelleri aşılmış sayılmaz. FCM bildirimi tek başına E2EE teslim veya okundu kanıtı değildir.

Test: gerçek Postgres (PGlite) üzerinde migration/rolling sonuç/ACK/aktif lease/yeniden deneme/süre sonu/rıza iptali/kişi kaldırma/silme/rol erişimi. Dispatcher sahte Google kabulünde accepted ve taze timestamp üretir. FCM TTL ve mevcut çağrı testleri birlikte çalışır.

## Orta: sayfa başına canlı izin ve iptal

MessageWakeSyncWorker her sayfadan hemen önce ve sonra coroutine iptalini ve kalıcı bildirim tercihini kontrol eder. Tercih kapanınca disabled sonucu ile durur; yeni sayfa veya continuation açmaz. Gerçek coroutine iptali yutulmaz. Foreground eşitleme kullanıcı bildirim tercihi yüzünden kapatılmaz.

Test: ilk sayfa içinde gerçek tercih kapatılır, hasPendingPage=true olsa bile ikinci sayfa ve continuation yoktur. Ayrı test ilk sayfada coroutine iptalini tetikler ve CancellationException'ın yayıldığını, başka sayfa sorgulanmadığını doğrular.

## Yayın ve geri alma

Son HEAD CI → main CI → imzalı OTA veri koruma → public APK/SHA/signer read-back ayrı kapılardır. Migration önce, uyumlu relay sonra uygulanır; eski relay done uyumluluğu sağlar. Sorunda relay önceki sürüme dönebilir; additive sütun/receipt trigger silinmez, veri geri alma veya kullanıcı mesajı silme yapılmaz. Sunucu işi gerçek delivered_at'e bağlı kalır. Android geri alma karantina metadata'sını silmeyi veya kimliği sıfırlamayı gerektirmez. Samsung A55 ve ikinci fiziksel telefonda çevrimdışı/bağlanma, uygulama kapalı/kilitli ve tercih iptali kabulü ayrıca gereklidir.
