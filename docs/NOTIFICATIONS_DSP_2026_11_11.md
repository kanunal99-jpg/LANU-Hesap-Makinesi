# LANU bildirim ve WebRTC AEC/NS dayanıklılığı — 11 Kasım 2026

## Kalite hedefi ve mevcut kanıt

Kullanıcının hedefi mesaj ve arama bildirimleri ile gürültü/yankı azaltmada **en az 8,5/10**. Önceki 6,5/10 puanları ölçülmüş fiziksel performans değildir; bu çalışma sonucunda 8,5 gerçekleştiği iddia EDİLMEZ. Ölçülmüş A55/ikinci cihaz P50/P95, echo return loss, dB, konuşma anlaşılabilirliği, FCM arka plan teslimat yüzdesi ve Huawei/Xiaomi OEM kabulü yoktur.

### Kök nedenler

1. Gelen arama bildiriminin 75 saniyelik `setTimeoutAfter` sayacı, gerçek arama ipucu 60+ saniye gecikerek FCM'den geldikten sonra **yeniden** başlıyordu. Bitmek üzere olan bir çağrı, geçersiz hale geldikten sonra uzun süre çalıyor görünebilirdi. Mesaj uyarısında ise Android O+ için süre sonu yoktu.
2. WebRTC JavaAudioDeviceModule donanım AEC/NS seçimi kurulumda başarılı olabilir ancak OEM `AudioRecord` başlatması asenkron başarısız olabilir. Mevcut kurulum sırasındaki HW→SW fallback bu geç callback'i yakalayamaz. Aynı uygulama sürecindeki sonraki çağrı hatalı donanımı tekrar seçebiliyordu.
3. Media owner kapandıktan sonra gelen gecikmiş AudioRecord callback'i bir sonraki çağrıya ait yanlış ses sağlığı kararı üretebilirdi.

### Yapılan kod iyileştirmeleri

- Tek, içeriksiz ve overflow-safe `WakeNotificationLifetimePolicy` ile arama ve mesajın Android O+ görünür süresi **orijinal hint zaman damgasından** hesaplanır. Arama için azami 65s geçmiş +15s saat ileri toleransı; mesaj için 120s +15s. Sıfır kalan süre `setTimeoutAfter(0)` kullanılarak sınırsız bildirime dönüştürülmez. Sınırda bekleyen ipuçları hiç yayımlanmaz.
- Ayrı call/message kanalları ve sistem zil/sessiz/DND tercihi, opt-in, PIN, normal hesap makinesi PendingIntent'i, ciphertext gizliliği ve notification ID'leri değişmedi. Otomatik silinen şey yalnız geçici Android uyarısıdır; Room şifreli mesaj geçmişi değil.
- Yetkili mikrofon yakalama hatası donanım AEC/NS üzerinde oluşursa sonraki aramada var olan WebRTC software APM (AEC/NS) seçilir. Kurulu kalıcı kullanıcı tercihi veya aktif görüşme ayarları değiştirilmez. Kapalı oturum callback'i ve izin geri alınması donanım arızası sayılmaz.
- Özel ses ayarları ekranında yazılım fallback durumu gizlilik bozmayacak şekilde açıklanır.
- Yeni yalnız mantıksal ve Robolectric negatif testler: gecikme, zamanaşımı, Long.MIN/MAX, eski çağrı, kullanıcı izin iptali, geç callback, ikinci görüşmede tek APM, bildirim kilit ekranı gizliliği.

### Zorunlu yayın kanıtları

1. Son PR HEAD'de unit/Robolectric, gerçek WebRTC politikaları, backend, Lint, R8 ve API34 emülatör kabulü.
2. `main` CI'ında stable signer, iki farklı önceki APK'dan cihaz kaldırmadan Room veri korumalı OTA.
3. `v1.2.0-signed.<run>` ve eski istemci için uyumluluk etiketi, APK+SHA256+imza, herkese açık anonim HTTPS yeniden indirme ve byte-eşitliği.
4. A55 + farklı marka ikinci telefon: uygulama kapalı/ekran kilitli/Doze, mesaj-only ve call-only opt-in, kanal NONE/sessiz/DND, gelen/cevapsız/eski FCM, Wi-Fi→LTE geçiş, Bluetooth/wired/speaker ve AEC/NS karşılaştırmalı P50/P95 ölçümleri **yapılmadıkça ürün 8,5/10 olarak onaylanmaz**.

Gerçek zamanlı OEM ses arızasını mevcut RTP oturumu içinde yeniden kurmak, aktif kullanıcı mute veya şifreli görüşmeyi bozabileceğinden bilinçli olarak yapılmadı. Donanım sonradan arızalanırsa çağrı mevcut dostane hata mesajıyla sonlanabilir; sonraki görüşmede yazılım fallback gerçektir. Android API23–25 `setTimeoutAfter` desteklemediğinden otomatik sistem bildirim sonlandırma bu SDK'larda sınırlıdır; FCM içerik gizliliği ve zaman filtresi korunur.

Yeni ücretli servis, izin, Room migration, keystore, kimlik ya da kişisel veri kaydı yoktur.
