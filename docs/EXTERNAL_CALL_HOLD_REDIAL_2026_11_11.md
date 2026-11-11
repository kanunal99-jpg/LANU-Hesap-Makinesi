# LANU — SIM/VoIP çağrıları sırasında bekletme ve güvenli tekrar arama (11 Kasım 2026)

## Kullanıcıdan gelen arıza
* LANU üzerinde gerçek E2EE sesli/görüntülü görüşme sürerken SIM veya WhatsApp araması geliyor, LANU görüşmesi kesiliyor.
* Çağrı kapandıktan sonra tekrar arama bazen bağlanamıyor.
* Hedef: dış arama sırasında LANU bekletilsin, uzak LANU görüşme ekranında **"Diğer kullanıcı meşgul"** yazsın, görüşme kimliği düşmesin, ses odağı geri gelince veya kullanıcı "LANU görüşmesine dön"e basınca devam etsin.

## Kod kök nedeni
1. `NativeCallEngine` eski kodu `AUDIOFOCUS_LOSS` alındığında medya oturumunu **hemen bitiriyordu**; geçici odak kaybında ise **45 saniye** sonra zorunlu hata vardı. Android kalıcı odak kaybından sonra otomatik `AUDIOFOCUS_GAIN` bildirmek zorunda değildir.
2. `NativeCallViewModel` 25s ICE bağlantı kesilme zamanlayıcısı odak bekletme durumunu bilmiyordu. SIM çağrısı mobil veriyi askıya alırsa bu süre dolup E2EE çağrıyı bitirebilirdi.
3. `CallForegroundService` eski `ENDED` StateFlow değerine hemen tepki verip yeni servisi durdurabiliyor; eski servisin `onDestroy()` ve eski bildirimdeki "Bitir" işlemi yeni görüşmeyi kapatabiliyordu.
4. Bitirilen çağrının `starting` işi iptal edilse bile aktif sayılmaya devam ettiği kısa aralıkta yeni meşru arama engellenebiliyordu.

## Çözüm ve sınırlar
* `CallAudioFocusPolicy`: hem kalıcı hem geçici Android ses odağı kaybında WebRTC PeerConnection **korunur**, gönderen mikrofon/ses/görüntü kanalları kapatılır; `AndroidCallAudioRouter` MODE_IN_COMMUNICATION ve seçilmiş ses çıkışını dış aramanın sahiplenebilmesi için bırakır, ses odağı dönüşünde yeniden kurar; kamera mümkünse başka video uygulamasına bırakılır. Aynı peer SDP/DTLS anahtarı ve kullanıcı mute tercihi korunur. Odak geri geldiğinde yalnız yetkiler hâlâ geçerliyse açılır. Kamera OEM nedeniyle açılamıyorsa sesli görüşme güvenli biçimde sürer ve gizli ekranda bilgi çıkar.
* `CallMediaSession.interruptionListener` ile sahip olunan gerçek Android odak değişikliği ViewModel'e bildirilir. Bu kaynak SIM veya WhatsApp olduğunu kesin olarak söylemez. Bazı Android/OEM ve WhatsApp sürümleri ses odağı callback'i vermeyebilir; **harici uygulamaların çağrı durumunu tespit ettiğimiz iddia edilmez**. Kullanıcı yeniden ses odağı istemek için `LANU görüşmesine dön` eylemini kullanabilir; Android hâlâ başka aramaya ses odağı vermiyorsa medya kapalı kalır.
* `CallSignaling` mevcut E2EE peer şifreli ve doğrulanmış çağrı envelope formatına `HOLD`/`RESUME` olaylarını ekler. SDP, dış çağrı türü/numara/kişi/WhatsApp hesabı taşınmaz; genel Android bildirimi ve FCM kullanılmaz. Geç gelen eski HOLD, daha yeni RESUME olayını tersine çeviremez. Ağ geçici erişilemezse son durumun tekrar iletimi 10s aralıkla sınırlanır; teslim edilene kadar karşı tarafın meşgul bilgisini mutlaka göreceği iddia edilmez. İki cihazda da bu yeni sürüm gerekir.
* E2EE çağrı beklerken ICE 25s timeout'u ve restart denemeleri boşa harcanmaz. Bekleme bitince 35s içinde yeni bidirectional RTP kanıtı ve gerekliyse sınırlı ICE restart aranır, sahte bağlı göstergesi yoktur. `CallRtpEvidenceTracker.invalidateOnHoldTransition` hem bekletme hem dönüş anında RTP kanıt epoch'unu yeniler: gecikmiş `getStats` sonuçları ve önceki görüşme/süreç paket sayaçları yeni `CONNECTED` durumuna geçiremez; iki yönde yeni paket ilerlemesi gerekir.
* FGS sahipliği `callId` ve **her çağrıya özel rastgele kuşak token'ı** ile ayrılır. Geç terminal durum, geç eski `onDestroy` ve eski "Bitir" PendingIntent'i yeni oturumu sonlandıramaz. `starting` ve kapanış iş nesneleri sıfırlanır, hızlı arama tekrar serbest kalır.
* PIN/`2011.`, kimlik/Keystore/Room/E2EE, tüm eski mesajlar, ücret politikası ve FCM gizlilik varsayılanları değişmez. Android 15+ ses odağı koşulları ve üretici cihaz politikası geçerlidir; çalışan bir foreground service zorunludur.

## Doğrulama ve yayın kapıları
- Birim/Robolectric: kalıcı/geçici odak kaybı, 90s bekletme ve 25s zaman aşımı, uzak HOLD/RESUME ve bayat signal, izin/manuel odak, eski FGS kuşak token'ı, anında yeni arama, PIN/notification regresyonları.
- CI son PR HEAD: Java/Kotlin, Android unit/Robolectric, Lint, R8, gerçek API34 Room runtime, imza doğrulaması.
- Yalnız CI yeşilse merge; main'de iki eski imzalı APK'dan yedi tabloluk yerinde OTA veri koruma ve release signer/SHA256/anonym HTTPS readback.
- Gerçek **Samsung A55 + ikinci cihaz**: SIM arama çalarken, cevaplandığında, 2–5 dakika sürerken, reddedilirken; WhatsApp sesli ve görüntülü; Wi-Fi ve mobil veride, ekran kilitli, Bluetooth/kamera sahipliği, üç defa arka arkaya arayıp kapatma. Bu fiziksel testler yapılmadıysa tam cihaz uyumluluğu doğrulandı diye raporlanamaz.
