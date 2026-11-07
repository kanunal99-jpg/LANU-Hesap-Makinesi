# Dosya işlemi sahipliği ve sağlayıcı iptali

Başlangıç main3080f4a / imzalı1136. Büyük medya aktivasyonu öncesindeki gerçek
eksikler: ContentResolver kaynağı açık/son okuma süresince iptal sinyaliyle
sahiplenilmiyordu; onay ve SAF bekleyişi sohbet/oturum kilidine bağlı değildi;
izole büyük medya klasöründeki açık belge parçaları başlangıç temizliğinin
dışındaydı. Arka planda gecikmiş kaynak, farklı sohbetten onay veya kilit
sonrasında kaydetme ihtimali gerçek test gerektirir.

## Uygulanan

- Onay, kaynak hazırlama, aktarım, doğrulanmış indirme ve SAF kaydetme aynı
  tek-sahipli job içindedir. Kilit, sohbet değişimi, Activity kapanması ve
  10dakika işlem bütçesi iptal eder; eski callback işi yeniden canlandıramaz.
- Açık dosya seçicisi varken ikinci dışa aktarma başlatılmaz. Picker callback
  yalnız halen etkin continuation'a sonuç verir; açık dosya anahtar/URI/path
  olarak callback alanına konmaz. Doğrulanmış plaintext bir finally'ye aittir.
- Her ContentResolver descriptor'ı CancellationSignal ile açılır ve son byte'a
  kadar sahiplenilir; okuma/yazmada120s bütçe, bloklar arasında iptal kontrolü,
  bildirilen/gerçek boyut sınırı ve ilerlemeyen kaynak reddi vardır. Signal
  ve descriptor iptalde kapanır. Kötücül OEM/provider'ın işletim sistemi
  içindeki kapanmayan native çağrısı için mutlak zaman garantisi verilmez.
- Şifreleme veya çözme bittikten sonraki withContext dönüşünde iptal kazanırsa
  üretilmiş ciphertext/key/verified plaintext sahipsiz bırakılmaz.
- Başlangıçta yalnız gerçek özel cache kökü ve tam adlı büyük medya klasörünün
  bilinen staged/verified/cipher dosyaları temizlenir. Symlink, alt klasör,
  Room/PIN/yedek/OTA ve başka dosyalar kapsam dışıdır. Flash üzerinde fiziksel
  güvenli silme garantisi yoktur.
- Kaydetme yalnız kullanıcı ACTION_CREATE_DOCUMENT ile hedef seçince yapılır.
  İptalde harici sağlayıcıda yazılmış kısmi hedef kalabilir; başarı gösterilmez,
  kullanıcı dosyası otomatik silinmez.

## Kabul ve kalan ürün kapısı

JUnit sahiplik/sohbet değişimi/süre aşımı/iptal/yeniden deneme; kısa okuma,
boyut taşması, sıfır-ilerleme ve yazma öncesi iptal; startup cleanup ve symlink
negatif testleri. Android34 debug+R8 release gerçek provider ile belge→E2EE
şifreleme→bütünlük doğrulama→SAF çıktı byte eşliği ve descriptor kapatma;
descriptor açılırken CancellationSignal test edilir. Test kaynakları yalnız
ayrı sentetik cache dizinleridir; mevcut kullanıcı verisi kullanılmaz.

Bu değişiklik gerçek Double Ratchet veya canlı SQLCipher uygulaması değildir.
E04 büyük medya UI/backend aktivasyonu ve iki fiziksel telefon kabulü ayrıca
açıktır. UI/server flagleri değişmez;40MiB aktarım kullanıcıya açıkmış veya
100/100 olmuş gibi sunulmaz. Son HEAD CI→main CI→aynı signer OTA→public APK
read-back sonrası kesin sürüm/kanıt PR açıklamasına kaydedilir.

Provider API kaynağı:
https://developer.android.com/reference/android/content/ContentResolver
https://developer.android.com/reference/android/content/ContentProvider
