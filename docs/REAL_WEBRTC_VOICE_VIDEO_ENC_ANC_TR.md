# LANU — Gerçek sesli/görüntülü WebRTC ve sağlam ENC/AEC/AGC planı

**Durum: 25.10.2026 — AŞAMA 1 geliştirildi; iki cihaz arasında gerçek çağrı HENÜZ YOK.** Bu belge vaat/test/teslim ayrımını korur. [Konu #3](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/issues/3).

## Terimler ve dürüst hedef

- **ENC / NS (Noise Suppression):** Karşı tarafa gönderilen **mikrofon** sinyalindeki motor, klima, rüzgâr, klavye vb. arka plan gürültüsünü azaltmaya çalışır. İnsan konuşması ya da ani sesler *her koşulda tamamen silinemez*. Konuşmacının sesini aşırı bozmamak ve gecikmeyi sınırlamak kalite kapısıdır.
- **AEC:** Karşıdan gelen sesin hoparlörden mikrofona dönmesiyle oluşan yankıyı engeller.
- **AGC:** Mikrofondaki konuşma seviyesini düzenler; tek başına gürültü bastırmaz.
- **ANC:** Dinleyenin kulağına ulaşan dış sesi elektronik olarak iptal eden **kulaklık donanımı**dır; Android uygulaması Samsung/Xiaomi/Huawei cihazlarında herhangi bir kulaklıkta garantili ANC açıp kapatamaz. Kulaklığın mevcut ANC ayarı korunur.
- **WebRTC APM:** Desteklenen cihazda donanımsal NS/AEC; diğerlerinde WebRTC yazılımsal işleme denenir. Android sistem efekti ve WebRTC yazılım algoritmaları birbirini iki kez işlememeli. RNNoise kurulu değilken *RNNoise aktif* yazmak yasaktır.

## Aşama 1 — Bu PR'de yapılan gerçek geliştirme

1. `CallAudioPreprocessor.kt`: Gerçek `AudioRecord.audioSessionId` alındığında Android `AcousticEchoCanceler`, `NoiseSuppressor` ve `AutomaticGainControl` için üretici kabiliyeti/etkinleşme/denetim kontrolü; bağımsız hata fallback'i; işlem sonu tüm efektleri bırakma. Mikrofon izni veya geçerli ses oturumu yoksa hiçbir efekt etkin gösterilmez.
2. `CallAudioPreprocessorTest.kt`: geçersiz audioSessionId, izin yokluğu, üretici HAL hatası, tek efekt kullanılamazlığı, kaynak temizleme/oturum geçişi; hiçbir test canlı mikrofon kullanmaz.
3. `AudioDspProcessor.kt`: DEMO üzerinde ölçülmemiş NS dB, DSP ms, % ses netliği ve *RNNoise/WebRTC aktif* iddiası kapatıldı. Basit PCM threshold gate, WebRTC APM değildir.
4. `CallScreen.kt`: Kullanıcı tarafından değiştirilen ENC seviyesi yalnız **önizleme/tercih**. Gerçek mikrofona veya PeerConnection'a bağlanmadan **aktif ANC/ENC** iddiası yok. Ağ telemetrisi ve ICE butonu temsili olarak etiketlenir.
5. Kodun bu aşaması Android mikrofon/kamera izni **istemiyor**, ses/ görüntü yakalamıyor, kimlik/ses içeriği saklamıyor, yeni sunucu/bucket/ücret gerektirmiyor. Kullanıcının **isteğe bağlı PIN** seçimi değişmiyor.

## Aşama 2 — Gerçek WebRTC medya oturumu (henüz yapılmadı)

1. Lisans/ABI/minSdk/R8/indirme boyutu doğrulanmış WebRTC Android kitaplığı (`io.github.webrtc-sdk:android` veya kabul gören aktif alternatif), gerçek `PeerConnectionFactory` ve `PeerConnection`. Eski `org.webrtc:google-webrtc:1.0.+` JCenter örneği kullanılmaz; sürüm sabitlenir, SBOM ve native lisanslar incelenir.
2. Kullanıcıdan **yalnız görüşme başlattığında** `RECORD_AUDIO` / video için `CAMERA` çalışma zamanı izni alınır. İzin reddi ve Android 12+ gizlilik anahtarı mikrofonun sessiz/açık durumuyla uyumlu; video kapatırken kamera kaydı durur. Gizli ekranda veya izinsiz arka planda otomatik kayıt yasaktır.
3. Mikrofon `VOICE_COMMUNICATION` kaynaklı, `JavaAudioDeviceModule` APM uyumlu yapılandırılır. Varsayılan/cihaz destekli AEC + NS çalışırken ikinci Android efekt oturumu eklenmez; AEC/NS üreticiden kaynaklı bozuksa yalnız gerçek ve testli WebRTC yazılım APM fallback seçilir. AGC davranışı ölçülür.
4. Kamera ön/arka geçişi, görüntü kapatma, gerçek uzaktan `VideoTrack`, Opus konuşma, gerçek ağ `getStats` ölçümü, suspend/resume ve arama sonlandırmada kamera/mikrofon/codec/native kaynak cleanup zorunlu.
5. `CONNECTED` yalnız gerçek ICE/DTLS + karşılıklı media track / yetkili remote peer state doğrulandıktan sonra; sahte zamanlayıcı bağlantısı ana çağrı akışından kaldırılır.

## Aşama 3 — Güvenli arama sinyalleşmesi ve ağ (henüz yapılmadı)

1. Mevcut E2EE kimlik sistemiyle tanımlanan eşe bağlı **imzalı ve şifreli** çağrı daveti, kabul/ret, kısa TTL, rasgele 128-bit+ session nonce, replay filtresi, cihaz sınırı ve kota. SDP/ICE adayları özel IP/metaveri içerebilir; herkese açık log/mesaj/analytics içine yazılmaz.
2. Tek kullanımlık `offer/answer/ICE candidate` protokolü ve `hangup` doğrulaması: konuşma kimliği, taraf kimlikleri, tür, sıra, son kullanma ve sürüm alanları. Yabancı katılımcı/oturumdaki adaylar düşürülür.
3. STUN ile doğrudan bağlantı; erişilemeyen NAT/mobil ağlar için **güvenli kimlikli TURN** fallback. Ücretsiz STUN tek başına tüm mobil ağlarda yeterli değil. TURN sunucusu trafik/maliyet/erişim riski ayrıca ölçülür; **ücretli TURN veya yeni kaynak kullanıcı onayı olmadan açılmaz**.
4. WebRTC DTLS-SRTP aktarım şifrelemesi açıkça belirtilir; karşı tarafın kimlik sabitlemesi ve gerçek uçtan uca tehdit modeli olmadan “tam kimliği doğrulanmış E2EE video” etiketi kullanılmaz.

## Aşama 4 — Samsung + Xiaomi gerçek kabul ve performans

- *Asgari 2 gerçek telefon, farklı üretici* aynı anda: Samsung–Xiaomi iki yönde ses ve video, izin aç/kapat, mikrofon sustur, kulaklık/Bluetooth, ön-arka kamera, Wi-Fi ↔ mobil internet, uçak modu/reconnect, pil tasarrufu, ekran kapalıyken kullanım politikası, kilit/gizli alan, arka plan, çağrı reddi, sürüm OTA.
- Motor/klima, fan, sokak, rüzgâr, yan konuşma ve sessizlikte **aynı cihaz-mesafe-mikrofon yerleşiminde** ENC kapalı/açık kayıtlar; bağımsız dinleme/objektif MOS/PESQ/STOI benzeri kabul, clipping/gecikme/CPU/ısı. Gerçek ölçüm yokken dB ve % netlik iddia edilmez.
- Android API 23/29/34/35/36, GMS'li ve GMS'siz OEM örnekleri; erişilebilirlik, veri/ses/video izni, R8 release, release APK imza sürekliliği.
- **Kabul tanımı:** Gerçek iki cihazda ses+görüntü aktarımı ve güvenli sinyalleşme olmadan **“Gerçek görüşme tamamlandı” denmez**.

## Kaynak

- [Android NS](https://developer.android.com/reference/android/media/audiofx/NoiseSuppressor), [AEC](https://developer.android.com/reference/android/media/audiofx/AcousticEchoCanceler), [AGC](https://developer.android.com/reference/android/media/audiofx/AutomaticGainControl)
- [WebRTC JavaAudioDeviceModule](https://webrtc.googlesource.com/src/+/refs/heads/main/sdk/android/api/org/webrtc/audio/JavaAudioDeviceModule.java), [WebRTC SDK Android](https://github.com/webrtc-sdk/android)
