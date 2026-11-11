# LANU ses netliği ve konuşma önceliği — 11 Kasım 2026

## Kök neden ve mevcut teknoloji
- LANU zaten `JavaAudioDeviceModule` / WebRTC APM kullanır. `VOICE_COMMUNICATION` kaynağı, doğrulanmış OEM hardware AEC/NS ve kullanıcı tercihi / OEM mikrofon başlatma hatasında software APM fallback vardır. İkinci AudioFX AEC/NS veya canlı PCM gate **eklenmez**.
- Native Opus codec ve `OpusSdpPolicy.preferReceiveFec` ile FEC alıcı kabiliyeti zaten bildirilir. DTX / bitrate yükseltme / sıfır gecikme iddiası eklenmez.
- Önceki `VoiceFirstNetworkPolicy` uzak ses paket kaybı/RTT bilgisini yalnız genel video kısma sinyalinde birleştiriyordu: konuşma gerçekten sıkıntıdayken görüntü 350 kbit/s tabanına kadar düşüyor, daha aşağıda koruyucu ses önceliği yoktu.

## Uygulanan, ücretsiz ve geri alınabilir iyileştirme
1. `CallSpeechProcessingPolicy` tek WebRTC APM üzerinde mono giriş+çıkış ve ses düzeyi logger kapalı biçimde belirler. AEC/NS/AGC mevcut kaynak üzerinde kalır. `googHighpassFilter=true` yalnız native WebRTC'nin tanıyabildiği cihazda düşük frekanslı uğultu için **opsiyonel hint** olarak iletilir; gerçek filtre/dB kazanımı garantisi değildir.
2. `VoiceFirstNetworkPolicy` sadece tazeliği doğrulanan uzaktaki audio `remote-inbound-rtp` loss >= %12 veya RTT >= 700ms verisine dayanarak `audioUnderPressure` üretir. Video-only ölçüm ses krizi yerine geçmez; null/NaN/Inf/negatif/aralık dışı veriler yok sayılır.
3. `AdaptiveVideoBitratePolicy` iki ardışık kötü sample ve önceki 10s sınırını koruyarak **yalnız görüntü** üst sınırını 220 kbit/s düzeyine indirebilir; Opus encoder, mikrofon, kamera aç/kapa tercihi, E2EE sinyalleri, cihaz rotası ve FEC değiştirilmez. WebRTC `setParameters` reddederse eski çalışma düzeyi korunur; yeni gelen beş sağlıklı örnekten sonra önce 350k, sonra 650k, sonra 1.1M kademeli geri çıkış sürer.
4. Yeni native dependency, RNNoise/NDK, ücret, izin, kaydedilen ham PCM/ses, ses içeriği aktarımı, biyometrik veri, Room migrasyonu ya da PIN değişikliği yok.

## Sınırlar
- `googHighpassFilter` opsiyonel WebRTC MediaConstraints anahtarıdır. Modern native sürüm bu ayarı görmezden gelebilir; böyle bir durumda mevcut güvenli WebRTC APM kullanılmaya devam edilir.
- 220k video önceliği düşük bantta görüntü netliğini azaltabilir; amaç kötü ağda konuşmanın daha iyi korunabilmesi, kesin bir dB/latency değeri değildir.
- Gerçek **Samsung A55 + ikinci fiziksel telefon** ile trafik, fan, hoparlör yankısı, çift taraflı konuşma, Bluetooth/SIM/WhatsApp kesintisi, Wi-Fi/LTE ve ağ kaybı test edilmeden ölçülmüş ses kazancı / 8,5 puanı iddia edilmez.
- Daha ileri **RNNoise veya neural DSP**, lisans/model boyutu, CPU/ısınma, 10–20ms frame, WebRTC native ABI ve cihaz gecikme ölçümü gerektirir; kanıtsız paralel PCM gate/stack yapılmaz.

## Kanıt kapıları
- Pure policy testleri: mono / WebRTC APM ve yüksek geçiren hint, kötü ses/sağlıklı video, anormal istatistik, tek anomali, 10s cooldown, başarısız native ayarı, beş örnekte kademeli iyileşme.
- Son PR HEAD CI, Android14 Room, main CI, iki gerçek imzalı OTA yükseltme ve GitHub public APK SHA256/signer readback olmadan yayınlandı iddiası yok.
