# LANU APK imzalama ve veri kaybı olmadan güncelleme

## Durum (19.10.2026)

GitHub Actions önceki sürümlerde her koşuda rastgele üretilen debug.keystore kullandı. İki APK aynı applicationId'ye sahip olsa bile sertifika farklıysa Android mevcut uygulamayı **üzerine güncellemez**. Yeni bir anahtar, eski sertifikanın özel anahtarını geri getirmez. Bu nedenle 145 veya daha eski debug APK'ye **veri kaybı olmadan doğrudan güncelleme garantisi yoktur**.

Bu PR, korunan ve sabit bir keystore sağlandığı anda aynı sertifikayla üretim yolunu hazırlar. **Sırları kendiliğinden oluşturmuş veya GitHub Secrets'a yüklemiş değildir.** Yeni build'in kalıcı imzalı olması ancak aşağıdaki 5 secret hesabın sahibi tarafından eklenince mümkündür.

## Ön koşul: Hesap sahibinin kontrolünde bir kez anahtar hazırlama

**Özel anahtar veya keystore dosyasını bu depoya, Issue'ya, PR'a, ChatGPT sohbetine veya GitHub Release'e yükleme.**

Android keytool ile cihaz dışında korunan, parolası güçlü bir keystore üret. SHA-256 sertifika parmak izini belirle. Anahtarı ve parolaları güvenli bir yedekte sakla. Örnek komutlar bir Java/JDK ortamı içindir (telefonda bu araç yoksa güvenilir bir bilgisayar veya kendi kontrolündeki güvenli build ortamı gerekebilir):

```sh
keytool -genkeypair -v -keystore lanu-stable.jks -alias lanu   -keyalg RSA -keysize 3072 -validity 10000
keytool -list -v -keystore lanu-stable.jks -alias lanu
base64 < lanu-stable.jks | tr -d '\n'
```

GitHub deposunda Settings → Secrets and variables → Actions → Repository secrets bölümüne **beş ayrı secret** ekle:

| Ad | Değer |
| --- | --- |
| `LANU_SIGNING_KEYSTORE_BASE64` | Keystore dosyasının base64 hali |
| `LANU_SIGNING_STORE_PASSWORD` | Keystore parolası |
| `LANU_SIGNING_KEY_PASSWORD` | Anahtar parolası |
| `LANU_SIGNING_KEY_ALIAS` | Oluştururken kullanılan alias (örnek `lanu`) |
| `LANU_SIGNING_CERT_SHA256` | `keytool -list -v` çıktısındaki SHA-256 sertifika fingerprint'i |

Bunların herhangi birisi eksikse güvenli imzalama moduna sessizce geçilmez. Bu sırlar sadece **güvenilir `main/master` push** sırasında okunur, dış katkıcı PR koduna verilmez. CI APK'yi **apksigner** ile doğrular; beklenen fingerprint değişirse yayın durur.

## Yayın türleri

- `v1.0.0-debug.N` : Korunan keystore yapılandırılmamış; ephemeral CI debug sertifikası kullanır. **Üzerine güncelleme garantisi YOK**. Sürüm, test ve inceleme için geçicidir.
- `v1.1.0-signed.N` : Beş secret tam sağlandı; sertifika fingerprint doğrulandı. Aynı özel anahtar korundukça sonraki aynı applicationId APK sürümleri Android imzası açısından uyumludur. Ancak henüz önceki 145 vs. sürümlere yerinde yükseltme anlamına gelmez.

Her iki yayına `app-debug.apk`, `app-debug.apk.sha256` ve herkese açık sertifika fingerprint/dağıtım notu olarak `app-debug.apk.signer.txt` eklenir. **Özel anahtar hiçbir artifact'e girmez.**

## Güvenli yükseltme test kapısı

1. İlk korunan sertifikalı APK için fingerprint'i yayınlanan `.signer.txt` ile doğrula.
2. Bu APK'yi test telefonuna yükleyip uygulama/kimlik ve mesajları oluştur.
3. Aynı 5 secret ile ikinci commit üret; fingerprint'in **aynı**, `versionCode`'un **daha yüksek** olduğuna bak.
4. `adb install -r` veya Android'in sistem yükleme ekranı ile ikinci APK'yi veriyi silmeden kur.
5. Kimlik kodu, PIN, hesap geçmişi, mesaj ve rehberin korunmasını kontrol et; gizli alan açılınca bekleyen mesajların geldiğini test et.
6. Eski ephemeral sertifikalı kullanıcılar için orijinal eski keystore yoksa **önce kimlik yedeği ve gerekiyorsa veri dışa aktarma** gereklidir. Mevcut LANU kimlik yedeği **sohbet geçmişi/rehber arşivi taşımaz**; bu nedenle kapsamlı veri taşıma geliştirilmeden kullanıcıları kaldır-yükleye yönlendirme.

## Fiziksel doğrulama durumu

CI unit, Robolectric, lint, build, paket versionCode, imza parmak izi ve SHA-256 kontrol eder. Gerçek telefonla yükseltme veya iki kişi arasında mesajlaşma için emülatör/telefon erişimi gereklidir; sadece CI başarısına dayanarak `production ready` denmez.


## 21.10.2026 — Stable sertifika sürekliliği için yeni CI kapısı

Yeni scripts/verify_stable_signer_continuity.py kontrolü, GitHub Release yayımlanmadan önce yeni stable APK signer raporunu önceki stable Release'in signer raporuyla karşılaştırır. SHA-256 imza parmak izi farklıysa, önceki rapor bozuk/eksikse veya herhangi biri stable değilse **yayını durdurur**. Altı regresyon senaryosu kodlanmıştır. İlk stable yayın önceki stable olmadığından yalnız bir baseline oluşturur; eski geçici debug APK üzerine veri kayıpsız güncelleme vaadi değildir.

Bu kontrol mevcut apksigner ve SHA-256 doğrulaması ile public-download read-back kontrollerine eklenir. İlk stable APK'yi hesap sahibinin GitHub Secrets alanında bulunan beş sırla üretmek gerekir; sırların hazırlanıp eklenmesi bu kod değişikliğinin parçası değildir. Aynı sertifikayla iki sürümün ve gerçek telefonda adb install -r ile yerinde yükseltmenin doğrulanması ayrıca gerekir. Keystore ve parolalar asla depoya, sohbetlere veya sürüm eklerine yazılmaz.
