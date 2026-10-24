# Hilt ve gerçek release kabul kapısı

- Application graph Hilt 2.60.1/KSP 2.3.6 ile üretilir. Room singleton, mevcut veritabanı dosyası, mevcut kimlik deposu ve master-key adı korunur. AppContainer yalnız mevcut ViewModel Factory çağrıları için facade'dır; repository ve servis singleton'larını Hilt sağlar.
- `release` R8 minification + resource shrinking kullanır. Yayın iş akışı `assembleRelease` çıktısını imzalar/doğrular. OTA istemcisiyle uyum için indirilen asset adı `app-debug.apk` kalır; dosya gerçek release variant'tır. CI mapping dosyasını ve debuggable=false durumunu ayrıca doğrular.
- API34 emülatöründe aynı yedi tablo/PIN/kuyruk koruma testleri hem debug hem minified release üzerinde çalışır. Release test başarısızsa yayın kapalıdır. Sabit APK sertifikası, versionCode, SHA ve anonim read-back kapıları korunur.
- Güvenlik ayarları yüklenemediğinde coroutine exception'ı uygulamayı sonlandırmaz; uygulama kilitli kalır, açıklayıcı hata ve yeniden deneme sunar. PIN bilgileri ve ayarlar yüklenmeden hazır durumu verilmez. Tema/biyometri/timeout ve lifecycle işlemleri de exception boundary kullanır.
- Bu kod, eski A55 crash'inin kesin kök nedeninin bulunduğu anlamına gelmez (#82). Fiziksel A55/Xiaomi/Huawei kabulü ve gerçek signing key'in cihaz dışı şifreli yedeği (#83) ayrı kalır.
- Firebase projesi/gerçek yapılandırma ve gizlilik tercihi olmadan Crashlytics gönderimi etkin ilan edilmez. Yerel mahremiyet korumalı tanılama çalışmaya devam eder; gizli veri aktarımı veya ücretli plan açılmaz.
