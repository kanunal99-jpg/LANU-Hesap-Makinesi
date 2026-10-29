# LANU Hesap Makinesi

LANU, Android 6.0 ve üzerindeki cihazlar için geliştirilen Jetpack Compose tabanlı bir hesap makinesidir. Normal ve bilimsel işlemleri, finansal hesaplamaları, birim dönüştürmeyi, sonuç geçmişini ve kullanıcı kontrollü uygulama güncellemelerini destekler.

## Güvenli güncelleme

Yüklenebilir Android sürümlerinin resmî dağıtım adresi [GitHub Releases](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/releases) sayfasıdır. İlgili sürümde `app-debug.apk`, `app-debug.apk.sha256` ve `app-debug.apk.signer.txt` dosyalarının bulunması; APK imzasının ve SHA-256 bütünlüğünün doğrulanması gerekir. Tarihsel APK dosya adı, tek başına paket türünü göstermez.

**Telefon üzerinden:** Hesap makinesinde üç nokta → **Güncelleme Kontrol** → APK'yi indir ve doğrula → Android'in yükleme onayını ver. Güncellemeler otomatik kurulmaz. İmza uyuşmazlığı veya paket doğrulama hatasında işlemi durdurun.

**Uygulamayı kaldırmayın; uygulama verilerini temizlemeyin.** Yerinde güncelleme için önceki ve yeni APK'nin paket adı, imza sertifikası ve sürüm sırası uyumlu olmalıdır. Eski geçici test sertifikalı kurulumların yerinde yükseltmesi garanti edilemez.

## Geliştirme ve kalite

Teknoloji: Kotlin, Jetpack Compose, Hilt, Android ViewModel, Coroutines/Flow, Room, R8/ProGuard. Minimum Android API 23; hedef API 36.

Kaynak kod ve testler GitHub Actions üzerinde Android birim testleri, Android Lint, Android 14 emülatör kabulü, imzalı sürüm ve yerinde OTA koruma aşamalarından geçirilir. Başarılı bir PR testi, tek başına yayımlanmış bir APK anlamına gelmez. Gerçek Samsung A55 ve ikinci üretici üzerinde fiziksel kabul, emülatör CI kanıtından ayrı değerlendirilir.

Yerel doğrulama için JDK, Android SDK, Gradle ve Node ortamında:

```bash
npm ci --prefix server && npm test --prefix server
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
```

Sürüm doğrulamasında mevcut uygulamayı silmeden, aynı sertifikayla imzalanmış bir APK kullanın. Teknik geliştirme kayıtları ve test raporları, hassas bilgi veya kişisel veri paylaşmadan tutulmalıdır. Ücretli servisler ayrıca onay gerektirir.
