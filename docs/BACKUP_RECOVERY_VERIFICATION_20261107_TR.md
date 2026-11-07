# Parolalı tam yedeği yerinde değiştirmeden doğrulama

Başlangıç main `1bb525bdca30bd842242d5fb6071b75ad51140b8`, sürüm1126.
Kullanıcının tamamlanma isteği kapsamında kayıpsız geçiş için eksik bir
ön koşul ele alındı: gerçek, seçilmiş yedek dosyasını telefonda geri açarak
sınayabilme. Bu özellik canlı SQLCipher veya Double Ratchet aktivasyonu değildir.

## Kullanım

Özel alan → Ayarlar → Tam şifreli yedek → Tam yedek dosyası seç →
Yedeği değiştirmeden doğrula → yedek parolasını gir → Doğrulamayı başlat.
Seçili dosya önce parola/AES-GCM ile açılır; sonra yedi tablo yeni ve geçici
SQLCipher Room dosyasında tam ID/alan/tür/sayı eşitliğiyle sınanır. Dosya
kapatılır, şifreli başlığı kontrol edilir, aynı geçici anahtarla yeniden
açılıp tüm kayıtlar tekrar karşılaştırılır. Başarı, geçici dosyalar silinince
gösterilir. Eski uygulama verisi, kimlik, PIN ve seçilmiş arşiv değiştirilmez.
Geri yükleme ayrı düğme ve ayrı onay olarak kalır. Yanlış parola, değiştirilmiş
arşiv, eksik satır, sessiz IGNORE, SQLite tür dönüşümü, NOT NULL ihlali veya
iptal başarı olarak sunulmaz.

## Kaynak ve gizlilik sınırı

- Ağ/servis/izin/ücret eklenmez. Kullanıcı tarafından seçilmeden çalışmaz.
- ContentResolver descriptor açılışı CancellationSignal ile; veri okuma
  boyunca descriptor iptal sahibi altındadır. Boyut32MiB+56, duraklama ve
  parça kontrolleri korunur. Kilitlenme/Activity iptali işi durdurur; toplam
 120s coroutine son tarihi vardır. Platformun bloklu native/provider çağrısı
  her cihazda anında kesilir diye fiziksel kanıt iddia edilmez.
- Yalnız cache/backup-verification altında rastgele ve katı allowlist isimli
  dosyalar açılır. Minimum8MiB+8×arşiv boş alan gerekir. Temizleme yalnız bu
  namespace'in dosya/WAL/SHM/journal ailesini kapsar; canlı DB/arşiv adı reddedilir.
- Anahtar rastgele256bit, yalnız RAM'de; anahtar/parola/kimlik/mesaj/log/telemetri
  kaydı yoktur. Süreç ölürse kalan kopya şifrelidir; kaybolmuş geçici anahtar
  yeniden üretilerek açılmaz. Sonraki açık doğrulamada eski test dosyaları temizlenir.
- İş uygulamanın canlı DB writer'ına, kimlik kasasına, PIN'e veya üretim
  Keystore/signing key'ine yazmaz. Kullanıcı yedeği açılıp doğrulanır; salt
  sentetik dosya ile başarı gösterilmez. Kullanıcının dosyasını bu ortamda
  açtığımız veya fiziksel A55 kabulü yaptığımız iddia edilmez.

## Kabul ve kalan iş

Yeni Compose testleri doğrulama düğmesinin geri yükleme çağırmamasını ve
boş dosya/busy durumunu; Room testleri yedi tablo, seyrek ID, bozuk satır,
sessiz atlama, tür dönüşümü, iptal rollback ve canlı hedef reddini sınar.
API34 debug/release runtime gerçek SQLCipher, tekrar açma/temizleme,
kaynak ve arşiv byte değişmezliği, yanlış parola/tamper/yarım constraint
hatası ve ilgisiz dosyanın korunmasını sınar. Son HEAD CI → main CI → gerçek
imzalı yedi tablo OTA → public APK/SHA/signer read-back zorunludur; kesin yayın
kanıtları birleşen PR açıklamasına yazılır.

Canlı DB göçü, bağımsız cihaz dışı yedek/geri yükleme ve imza kurtarma,
Double Ratchet protokolü/bağımsız kripto incelemesi, iki fiziksel cihazda
FCM/BT/RTP/video/gürültü/latency/OTA kabulü hâlâ ayrıdır.100/100 tamamlandı
diye puan değiştirilmez. Yeni doğrulama ekranı bu kabul işlerini güvenli
biçimde yapabilmek için somut bir kullanıcı kapasitesidir.
