# LANU — İletişim kalite +2 hedef paketi (10.11.2026)

> **Hedef, ölçülmüş sonuç değildir.** Bu paket 10 üzerinden yaklaşık +2 kalite puanı hedefler; son puan fiziksel iki cihaz ve gerçek ağ/ses/video ölçümleri gelmeden artırılmaz. Puan azami 10'dur. Kullanıcı veri güvenliği, gizli giriş, isteğe bağlı PIN, eski E2EE kimliği, iki kişilik kullanım, GitHub Releases imzası ve üretim sınırları `PROJE_ANAYASASI_VE_DUZELTILECEKLER.md` MADDE 1'e tabidir.

## Kök nedenler, değişiklikler ve güvenli alternatifler

| Bileşen | Önceki arıza | Ana çözüm | Alternatif / fallback | Güvenli varsayılan |
|---|---|---|---|---|
| Sesli görüşme | Android `AUDIOFOCUS_LOSS_TRANSIENT` etkin WebRTC çağrısını aniden bitiriyordu | Tek RTC işleyicisinde geçici mikrofon sessizleştirme, `AUDIOFOCUS_GAIN` ile yeniden açma | 45 sn'lik tek bounded hold, user mute her zaman korunur | Kalıcı focus loss/yeniden açma hatasında çağrı kapanır; sessizce mikrofon açılmaz |
| Gürültü/yankı AEC/NS | OEM HW AEC/NS başlatması `LinkageError` ile kesilince yazılım işleme denemiyordu | Tek WebRTC yazılım APM fallback, eski modülün temizlenmesi | Hardware AEC/NS → software APM | İki AEC/NS zincirini üst üste çalıştırma yok, permission/cancellation/OOM asla bypass edilmez |
| Görüntü | Bazı Camera1/Camera2 HAL'leri 1280×720@24 isteğini reddediyor | Her gerçek kamera için yeni kaynakla sıralı 720p24 → 480p24 → 240p15 | Camera2 → Camera1 ve mevcut arama ses kanalı | Kamera izni ve gerçek capturer başarısı yoksa görüntü açılmış gibi raporlanmaz |
| Ses/video akıcılığı | Ağ/OEM istatistik callback'i takılınca saniyede bir yeni yerel WebRTC iş yükü birikiyordu | Tek uçuş RTC getStats; 5 sn sonra yavaş isteğin yerine yenisi; eski callback reddi | Gerçek RTP kanıtı + mevcut RTCP-histerezis video uyarlaması | Üretilmiş kalite/FPS/dB/connected verisi yok |
| Mesajlaşma | Room'a doğrulanıp yazılan gelen E2EE olayının `ack` HTTP isteği geçici hata verirse bağımsız giden mesaj kuyruğu çalışamıyordu | ACK hatasını raporlamayı korurken, yalnız geçici ağ hatasında giden şifreli kuyruğu güvenli boşalt | At-least-once ACK tekrarı; UUID + revizyon + idempotent relay | 401/403/409/429, SSL ve protokol hatalarını bypass etme; gerçeği olmayan DELIVERED/READ yok |
| Yayın/OTA | Karmaşık değişiklik mevcut imza/veriyi bozma riski | PR tip CI → kilitli merge → main CI → iki imzalı OTA → public HTTP+SHA/signer | Önceki kararlı APK hâlâ erişilebilir | Herhangi bir yayın kapısı kırmızıysa yeni APK'yi yayınlama |

## Test matrisi

Otomatik: geçici/dakikalık/permanent ses odağı, focus geldiğinde kullanıcı mute durumu; OEM LinkageError, permission, cancellation ve OOM; Camera HAL HD hata/480p başarı ve eski callback/cancellation; native getStats aşırı birikme ve 5 saniye timeout; 408/5xx ACK, 401/403/429/TLS reddi; mevcut E2EE replay/delivery/legacy/Android 14/R8/OTA.

**İki fiziksel telefonda henüz ölçülmeyen kabul:** Samsung A55 + ikinci Android cihaz; bildirim/telefon çağrısı sırasında mikrofon gizliliği ve konuşmanın geri gelmesi; HW ve WebRTC yazılım AEC/NS sessiz/kalabalık/Bluetooth örnekleri (fiziksel ölçüm); Wi-Fi↔LTE geçişinde RTP ve P50/P95 bağlantı/kurtarma; 720p/480p/240p kamera gerçek capture FPS, takılma ve A/V sync; mesaj gidiş/gelis P50/P95, offline ve kota senaryoları. Kişi, PIN, SDP, ses dalgası veya koordinat içeren log toplama yok.

## Ürün kabul kriterleri

1. Eski kurulu iki telefonun kimlik/mesaj verileri ve imzası korunmalı; mevcut kullanıcının gizli giriş davranışı değişmemeli.
2. Süreli ses odağı kesintisi otomatik çağrı kapatma üretmemeli; gerçek odak gelmeden mikrofon açılmamalı ve kullanıcı mute tercihi geri alınmamalı.
3. Kamera yalnız gerçek HAL başarısıyla açılmalı; seçilen çözünürlük kamera aç/kapa sonrasında da korunmalı.
4. ACK hatası veri/okundu onayı üretmemeli ve yetki/kota atlamamalı.
5. Kapanmış çağrının eski stats callback'i yeni çağrıyı bağlandı göstermemeli.
6. İmza ve OTA CI dahil tüm kapılar başarılı olmadan sürüm yayında ilan edilmemeli.

Başlangıç: `main=5795857`, Release `v1.2.0-signed.1242`. PR, merge, CI, GitHub Release ve fiziksel kabul her aşamada ayrı raporlanır.
