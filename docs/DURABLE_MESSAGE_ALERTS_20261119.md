# Kalıcı ve güncel okunmamışlığa bağlı mesaj bildirimi

Başlangıç main 50cdd2b0be3ab4aab7f9dfccbfaad30f924694ac / APK 1332.

## Kalıcı kuyruk

Room app_settings tablosunda her doğrulanmış yeni gelen mesaj için lanu_pending_message_alert_<id> → conversationId kaydı, mesaj/unread/revision ile AYNI transaction içinde yazılır. İçerik, kişi adı, anahtar veya FCM token eklenmez. Mevcut yedi tablo ve Room v4 şeması korunur; migration/reset yoktur. En fazla 200 kayıtlık bir bildirim grubu için opaque UUID ve mesaj ID listesi Room'a gösterimden ÖNCE commit edilir. Android kabulü/activeNotifications read-back doğrulandığında bekleyen kayıtlar silinir, son gösterilen opaque grup belirteci Room'a yazılır. Doğrulama başarısızsa, Worker iptal olursa veya süreç gösterimden önce ölürse kayıtlar kalır.

Worker yeni ağ eşitlemesinden önce eski kuyruktan bildirim üretmeyi dener; yeni mesaj sayacı bildirim karar kaynağı değildir. Ağ eşitlemesi sonunda tekrar dener. FCM/network/periodic kurtarma yollarının tamamı aynı kuyruğu kullanır. Mevcut kimlik zorunluluğu ve canlı tercih/özel alan/OS izin kontrolleri sürer. Yerel bildirim kurtarması için yeni HTTP isteği gerekmez.

## Son okunmamışlık kontrolü

Grup hazırlandıktan sonra gösterim anında mesajın hâlâ mevcut olması, READ olmaması, sohbet unreadCount>0 ve gizlenmemiş olması yeniden sorgulanır. markConversationOpened unread sıfırlama ve ilgili bekleyen kayıtların temizlenmesini tek Room transaction'ına alır. Son kontrol/Android post/Room tamamlaması Room yazma işlemi içinde serileştirilir; okuma işlemi araya girip geçmiş bir snapshot'ı geçerli kılamaz. Önce okuma commit olduysa bildirim yoktur. Bütün adaylar okunmuş/silinmişse grup temizlenir. Farklı sohbetteki yeni okunmamış adaylar kaybolmaz.

## Tekrar ve işlem sınırı

Süreçler/Worker'lar arasında kayıt kaynağı Room'dur; aynı süreçte iki yayıncı ortak Mutex ile serileşir. Başarılı gösterimden sonra aynı olayın replay'i mesaj/revision idempotency sayesinde kuyruğa yeniden eklenmez. Android'de sabit bildirim ID'si, onlyAlertOnce ve yalnız opaque grup belirteci kullanılır. Post başarılı olup Room commit'inden önce kesilirse Android'deki aynı aktif grup okunur ve notify tekrar çağrılmadan Room tamamlanır.

Android NotificationManager ile Room arasında dağıtık atomik transaction yoktur. Tam o aralıkta süreç ölür VE Android'deki bildirim daha sonra kaybolur/sona erer/kullanıcı tarafından kaldırılırsa, henüz Room'a tamamlanmamış grup yeniden gösterilebilir. Bu belirsizlikte bildirim kaybı yerine yeniden deneme seçilir; matematiksel exactly-once veya fiziksel ekran/ses/DND garantisi iddia edilmez. Android kabul kaydı gerçek ekranda görünmenin fiziksel kanıtı değildir. Normal tekrar, iki Worker, replay ve aktif Android post sonrası kesilme testleri tekrar uyarı üretmemelidir.

## Testler

- Diskte gerçek Room: mesaj/grup kaydı → DB kapanır → yeni DB/journal açılır → aynı token ile tek gösterim → sonraki çalışma göstermez.
- Android post sonrası/Room tamamlaması öncesi CancellationException: transaction rollback; grup kalır; yeni journal aynı token'ı kullanır; Android kabul simülasyonu ikinci post üretmez.
- Hazırlama ve gösterim arasında gerçek markConversationOpened: bildirim yok, pending temizlenir.
- Gösterim false: pending kalır, shown yazılmaz; sonraki true tamamlar; daha sonra tekrar yok.
- İki eşzamanlı yayıncı: tek post.
- Hazırlanan sohbet okunurken başka okunmamış sohbet gelirse yalnız yeni grup gösterilir.
- Room rollback: mesajla birlikte pending de geri alınır; orphan kayıt yoktur.
- Gerçek notification read-back: aynı token ikinci çağrıda yeniden post edilmez; onlyAlertOnce korunur.
- Gerçek E2EE repository akışı pending kaydını oluşturur; replay ve bozuk ciphertext yeni pending oluşturmaz.

Android unit/Robolectric API28/34, mevcut API23+ ağ/iptal testleri, sunucu protokol/Postgres testleri, Lint/R8, API34 runtime, imzalı 828 + önceki stabil OTA ve public APK SHA/signer read-back ayrı kanıtlardır. Fiziksel A55 + ikinci telefon ve gerçek OS süreç öldürme kabulü ayrıca gereklidir. Backend v22 ve teslim/okundu ACK sözleşmesi değişmez.
