# LANU gerçek arama ses yönlendirme — Android 6+ hazırlığı

**Durum (25.10.2026):** Ses yönlendirme kodu ve seçici testler eklendi; **gerçek WebRTC çağrı henüz çalışmıyor.** Bu modül `CallScreen` demosundan bağımsızdır ve demo tarafından başlatılmaz. Kullanıcı izni ve doğrulanmış gerçek medya oturumu olmadan mikrofon/ses yönlendirme başlatılmaz. PIN isteğe bağlı davranışı ve kullanıcı verileri değiştirilmez.

## İstenen kullanım

- Kablolu/USB kulaklık varsa varsayılan olarak kullan.
- Mikrofon destekli Bluetooth kulaklık (SCO, BLE/Hearing Aid platform profili) varsa kullan; yalnız A2DP müzik çıkışını iki yönlü çağrı sanma.
- Kulaklık yoksa normal sesli aramada telefon kulaklığı; görüntülü görüşmede telefon hoparlörü.
- Kullanıcı mevcut ses yolları arasında seçim yapabilir; ayrılan kulaklığın tercihi aktif tutulmaz, sonraki uygun ses yoluna düşülür.
- Android 12+ `AudioManager.setCommunicationDevice` ile yapılan yönlendirme **başarılı dönüş ve seçilen iletişim cihazı ile** kontrol edilir.
- Android 6–11'de Bluetooth SCO yalnız **SCO_AUDIO_STATE_CONNECTED** bildirimi geldikten sonra aktif sayılır; eski `isSpeakerphoneOn` ayarı cihaz üreticisine göre kesin yönlendirme kanıtı değildir.
- Çağrı bitince iletişim cihazı talebi/önceki mod ve speaker tercihi serbest bırakılır. Üretici HAL hatası/crash halinde sessizce gerçek bağlantı varmış gibi gösterilmez.
- Gürültü bastırma **ENC/NS** karşı tarafa giden mikrofon sesini etkiler; kulaklığın **ANC** kapasitesi donanımına bağlıdır. Mutlak sessizlik veya her cihazda aynı kalite vaat edilmez.

## Henüz eksik kabul kapıları

1. Gerçek WebRTC ses ve görüntü aktarımı, gerçek çağrı sinyalleşmesi ve iki telefonlu test (#3).
2. Her çağrıda explicit `RECORD_AUDIO` ve görüntülü çağrıda kamera izinleri, GMS gerektirmeyen uygulama; OS foreground service kısıtlarına uyum.
3. `AndroidCallAudioRouter.start(realCallAuthorized=true, ...)` yalnız gerçek medya oturumunda ve izin sonrası çağrılır; Activity/Service kapanışında mutlaka `close()`.
4. Bluetooth mikrofon/ses, kablolu/USB kulaklık, telefondan hoparlöre ve geri, çağrı sırasında kulaklık bağlantısını koparma, Bluetooth iptali, ses odağı, gelen telefon çağrısı senaryolarının Samsung ve Xiaomi üzerinde **iki yönlü işitsel doğrulaması**.
5. WebRTC yazılımsal APM kullanılıyorsa Android NS/AEC/AGC donanım zinciri iki defa işletilemez. Seçili ses yoluna göre kazanç/gürültü davranışı ölçülür.
6. Android 6–11 eski Bluetooth SCO'nun yanıt vermediği veya sonsuza kadar beklediği durumda süre sınırı, hata ve kulaklıksız alternatif yol testleri. Cihaz OEM farklılıkları ayrıca kabul gerektirir.
7. Yeni cihaz bağlantısı değişimlerinde çağrı kesintisi olmaması ve Android'de medya yönlendirmenin arka plan/yaşam döngüsü yönetiminin teyidi.

**Test kanıt seviyesi:** `CallAudioRoutePolicyTest` üretici bağımsız *politikayı* doğrular; fiziksel yönlendirme veya WebRTC'nin çalıştığını kanıtlamaz. CI ve cihaz kabulü ayrı işlenmelidir.
