# LANU Room v4 — gerçek imzalı APK OTA veri korunumu kabulü

## 28 Ekim 2026 — otomatik Android 14 emülatör kapısı

Kaynak: `RoomActualOtaUpgradeTest.kt`, `scripts/ci_ota_upgrade_acceptance.sh`,
`scripts/ci_prepare_android_image.sh`, `.github/workflows/android.yml`.

**P0 yayın koşulu**: Kullanıcının kurulu sürümünü kaldırmadan, önceki
`v1.2.0-signed.822` APK'sını Google API Android 14 **izole emülatörüne**
kurmak; yeni test APK'sını **aynı stabil imzayla** hazırlamak; baz sürümde
`lanu_calculator_db` Room v4'ün 7 tablosuna kayıtlar, PIN verifier,
kuyrukta uçtan uca şifreli mesaj ve Android Keystore destekli E2EE kimliği
yazmak; yeni **aynı paket/aynı imzalı, yükselen versionCode** APK'sını
`adb install -r` ile eski APK'nın **üzerine** kurmak; tekrar test ederek
önceki tüm kayıtların birebir kaldığını doğrulamak.

- Kimlik hesabının özel/public anahtarı hash'i ve geçerli imza doğrulaması;
  PBKDF2 PIN verifier'ının byte-düzeyinde korunması; `PENDING` mesajın
  şifreli baytlarının korunması ve eski özel anahtarla çözülebilmesi;
  sohbet okunmamış sayısı, rehber engeli, geçmiş kayıtları, çağrı geçmişi,
  kimlik profili ve güvenlik ayarları doğrulanır.
- Test başlangıcı yalnız `lanu_ota_ci=isolated-emulator-v1` ile açıkça
  etkinleştirilir. Fiziksel cihaza karşı bu aşamalar **reddedilir**.
  Normal `connected...AndroidTest` koşusu bu özel iki aşamayı çalıştırmaz.
- İndirilen eski APK'nın resmî SHA-256 sidecar'ı, eski/yeni/test APK
  sertifika kimliği ve strictly increasing `versionCode` **kurulumdan önce**
  doğrulanır. Test aracı `adb uninstall` veya `pm clear` çağırmaz.
- Emülatör SDK paketinin kısmi/bzuk ZIP indirmesi 3 kez temiz indirme
  cache'i ile denenir; cihaz kabulü olmadan yayına izin verilmez.
- PR işlerinde gizli imzalama anahtarlarına erişim **yoktur**; imzalı
  cihaz-üstü karşılaştırma yalnız korumalı `main`/owner çalışmasında
  yapılır. `publish` bu bağımsız işi de `needs` olarak şart koşar.
- Her iki APK aynı imzaya sahip olsa da, **Android'in aynı paket üzerine
  kurulum davranışı** kullanılmadan veri devamlılığı kanıtlanmış sayılmaz.
  `upgrade_verified_on_device=false` imza sidecar'ı değiştirilmez:
  emülatör doğrulaması **gerçek fiziksel cihaz kabulü değildir**.

## Fiziksel Samsung A55 + ikinci telefon (açık kabul, henüz çalıştırılmadı)

İlk olarak iki cihazda mevcut sürümün yerel şifreli yedeğini kullanıcı
tarafından güvenli dış konuma alın. Sürümü kaldırmadan aynı APK
sertifikası ve yükselen `versionCode` ile yerinde güncelleme yapın.
Güncelleme sonrasında her iki telefonda PIN/2011. girişi, E2EE kimliği,
bekleyen şifreli mesaj, sohbet/rehber/geçmiş, çağrı geçmişi ve
`lanu_calculator_db` Room v4 içeriği; WAL etkinliği ve uygulamayı
yeniden başlatma kontrolleri ölçülerek **yeniden okumayla** karşılaştırılmalı.
Ağ kesilmesi, düşük depolama alanı, bekleyen mesaj varken kurulum iptali,
APK imzası değişikliği ve downgrade senaryoları kurulu veriyi silmeden
reddedilmeli. Bu kabul için kullanıcıya ait test telefonları ve gerçek
ADB/kurulum kanıtları zorunludur.

**90/100 hedefinin gerçekleştiğini bu emülatör işinden tek başına
çıkaramayız.** Canlı SQLCipher dönüşümü ve gerçek fiziksel OTA verisi
tamamlanana kadar ölçülmeyen noktalar eksik sayılır.

### Önceki R8 sürümünün ABI uyumu

Önceki .822 APK R8 ile küçültüldüğü için *yeni* kaynakla derlenen bir
AndroidTest APK'sını doğrudan eski APK'ya bağlamak yanlış
\`NoSuchMethodError\` verebilir. CI eski \`f7f587...\` kaynak
commit'ini ayrıca denetimli şekilde checkout eder; **aynı test kaynağını**
eski uygulamanın ABI'sine karşı tekrar derler ve aynı stabil anahtarla
yalnız izole CI içinde imzalar. Önceki APK'yı eski test APK'sı ile seed eder;
\`adb install -r\` sonrası yeni test APK'sını (aynı .test package +
aynı sertifika) yerinde güncelleyerek yeni uygulamayı doğrular.
Özel anahtarlı test APK'ları hiçbir Release veya CI artifact'a yüklenmez.
