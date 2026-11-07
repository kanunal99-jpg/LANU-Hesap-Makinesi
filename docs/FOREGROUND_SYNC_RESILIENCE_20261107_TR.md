# Ön plan mesajlaşma ve ağ toparlama

E01/E02/E03: QUALITY_AND_REMAINING_WORK_20261107_TR.md dosyasındaki 73/100
başlangıç değerlendirmesinin somut mesajlaşma/ağ açıklıkları hedeflenir.

## Gerçek kullanıcı davranışı

- Yenile, gönderim/yeniden gönderim, düzenleme/tepki ve sohbet açılışı aynı
  foreground oturumuna bir Unit tetik gönderir. Tetik sohbet/mesaj/kişi taşımaz.
- Devam eden sync sırasında gelen tetik kaybolmaz; çok sayıda tetik bir sonraki
  bounded geçişte birleşir. Tek bir foreground sync çalışır; in-flight işlem
  iptal edilip bilinmeyen sunucu sonucuyla körce yeniden başlatılmaz.
- Doğrulanmış internet döndüğünde 10/20/40/60s hata backoff'unda uyuma erken
  biter. En az 2,5s aralık ve 429 Retry-After korunur; ekranı kapatıp açmak
  bu aralığı veya sunucu beklemesini sıfırlamaz. Monotonik RAM deadline kullanılır.
- Ağ callback'i sunucunun erişilebilirliği sayılmaz. ONLINE yalnız gerçek,
  imzalı relay sync başarılı olduğunda gösterilir. Captive portal veya sadece
  INTERNET/VALIDATED özelliklerinden biri tek başına yeni ağ uyanışı üretmez.
- API24+ default callback; API23 normal network callback. Kaynak cold Flow'dur:
  son özel ekran sahibi yoksa callback/timer/sync yok. Android callback kaydı
  başarısızsa normal 2,5/5s polling ve hata backoff'u bağımsız fallback'tir.
- Son sahibi kapatmak sync soketini/coroutine'ini, timer'ı ve ağ aboneliğini
  iptal eder. Yeni görünürlük oturumu yeni kanal kullanır; eski callback yeni
  oturumu uyandıramaz. Kullanıcı onaylı WorkManager/FCM akışı ayrıca korunur.

## Yapı

ForegroundSyncPump: sıralı deneme, conflated tetik, zamanlayıcı/backoff ve kota.
ForegroundSyncSession: home/chat sahipleri, monotonik deadline, callback ve kanal
yaşam döngüsü. CommunicationViewModel: gerçek sonuçları UI state'ine dönüştürür.
AndroidNetworkRecovery: yalnız doğrulanmış değişiklik ipucu; adres/SSID/IP/token
okumaz, kaydetmez veya loglamaz; en fazla 16 geçici Network referansı tutar.

En az 2,5s cadence sürekli oturumda dakikada en çok 24 sync girişimi sağlar.
Normal başarılı yolda 24 sync + 24 ACK + 60 outbox send + 2 kayıt ≈110 istek/dk;
server 429 ve repo outbox bütçeleri ek korumadır. Manuel diğer API işlemleri,
arka plan işleri ve görüşme kontrolü bu sayısal örneğin dışındadır.

Bir sync turu birden çok istek içerebilir. Mevcut aktif HTTP isteğinin 35s
bütçesi ağ olayıyla kesilmez; bu çalışma her
koşulda 2,5s gerçek ağ yanıtı veya P50/P95 garantisi vermez. Android23 callback
sırası ve OEM davranışı değişebilir; polling fallback bu yüzden korunur.

## Test ve teslim

Sanal zamanda: in-flight 1000 tetik → tek takip geçişi; tek aktif tick; 60s
backoff'tan ağ uyanışı; 429'a rağmen 1000 tetikte bekleme; 24/dk cap; son owner
iptali; kaydı başarısız callback → timer fallback; geç callback; hızlı 100
kapat/aç geçişinde quota deadline korunması. API23/24/34 Robolectric üzerinde
cold registration, INTERNET+VALIDATED, duplicate filtreleme ve unregister testleri.

Kaynak: Android Developers, Read network state:
https://developer.android.com/develop/connectivity/network-ops/reading-network-state
ve ConnectivityManager API reference. Başarı test/kod kanıtıdır; gerçek iki OEM
Wi-Fi↔mobil, pil/ısı/gecikme/ses/video fiziksel kabulünün yerine geçmez.

Room şeması, hesap makinesi, gizli giriş/PIN, E2EE biçimi, ses/video varsayılanı,
büyük dosya bayrağı ve backend değişmez. Yeni izin/ücretli servis yok.
Yayın: son HEAD CI → main CI → stable signer + veri korumalı OTA → Releases
APK/SHA/signer + public byte read-back. Rollback daha yüksek versionCode'lu
aynı imzalı revert APK ile; uygulama kaldırma/veri temizleme yok.
