# Ön planda gerçek sesli ve görüntülü arama

Sohbetin üstündeki telefon ve kamera düğmeleri native WebRTC görüşmesi açar. Her iki telefon güncel sürümde, karşılıklı kişi kayıtlı ve güvenli alan açık olmalıdır. Kullanıcı aramayı başlatır/kabul eder, gereken Android mikrofon/kamera iznini verir. Güvenli alanın kapanması görüşmeyi kapatır. Kapalı uygulamaya çağrı bildirimi bu sürümün kapsamında değildir.

## Bağlantı ve gizlilik

Mevcut ECDSA imzalı relay, ayrı 90 saniyelik opaque sinyal tablosu ve karşılıklı kişi şartı kullanılır. ECDH/AES-GCM çağrı AAD'si `LANU-CALL-v1:<signal UUID>` ile sohbetten ayrılır. Offer/answer SDP yalnız RAM ve uçtan uca şifreli sinyaldedir; anahtar/SDP loglanmaz veya Room'a yazılmaz. Sunucu call ID, taraf ID ve zaman metaverisini görür. Medya WebRTC DTLS-SRTP ile doğrudan şifreli gider. Karşı taraf ve Google STUN hizmeti bağlantı IP adreslerini görebilir; bağlantı öncesi kullanıcıya açıklanır.

Gelen davetler mevcut ön plan chat sync yanıtına yalnız v1 isteyen istemcide eklenir. Boşta yeni Edge polling döngüsü yoktur. Arama esnasında 2 saniyelik callSync, sınırlı mesaj gönderim bütçesi ve maksimum kısa ömürlü sinyaller vardır. Eski istemci yanıt formatı aynı kalır. Android arka plan worker'ı çağrı açamaz.

## Medya ve dayanıklılık

`io.github.webrtc-sdk:android:150.7871.04` sabittir. AudioDeviceModule VOICE_COMMUNICATION, destekli donanım AEC/NS ve WebRTC APM/AGC kullanır; mevcut bağımsız Android DSP adaptörü aynı ses oturumuna ikinci kez eklenmez. Cihaz/HAL hatasında görüşme güvenli kapanır. Kamera2, gerekirse Kamera1 ve codec donanım/yazılım alternatifleri bulunur. Ses odağı ve rota kapanışta geri bırakılır. Kamera kapatıldığında capture durur. Renderer, track, capturer, PeerConnection, EGL ve audio module temizlenir.

Bağlandı yalnız gerçek transport ve iki yönde ses paketleriyle gösterilir. Davet 45 saniye, kurulum 35 saniye, kopma 25 saniye bütçelidir. Arayan taraf bir gerçek ICE restart deneyebilir. Ücretsiz Google STUN kullanılır; TURN yoktur. Katı NAT veya operatör ağlarında bağlantı kurulamayabilir; kullanıcı farklı ağ deneyebilir. Ücretli altyapı otomatik açılmaz. ANC veya ölçülmüş gürültü dB iddiası yapılmaz.

## Bağımlılık kabulü

Maven Central POM'unda BSD-3-Clause; AAR manifest minSdk21; armeabi-v7a, arm64-v8a, x86 ve x86_64 native kitaplıkları doğrulandı. ARM64 ELF PT_LOAD hizaları 16384 bayt olarak kontrol edildi. App minSdk23 değişmedi. `org.webrtc` JNI ABI R8'de korunur. Native lib BSD lisansı ve sürüme sabitlenmiş üçüncü taraf bildirimleri APK içinde `assets/licenses` altında dağıtılır (`WebRTC-third-party-notices.md` SHA256 `d1f9382c6878ac024155fd6d44a5977329108bb8b0a01cea40e4a2f1d7de252e`, kaynak `webrtc-sdk/android/v150.7871.04/Licenses/WEBRTC.md`); kaynaklar: https://repo.maven.apache.org/maven2/io/github/webrtc-sdk/android/150.7871.04/ ve https://webrtc.googlesource.com/src/+/refs/heads/main/LICENSE.

## Kabul ve geri dönüş

Node/PGlite protokol/SQL, Robolectric durum makinesi ve API34 debug/release gerçek native paket testi zorunludur. Samsung ve Xiaomi arasında iki yönlü ses/video, mute, kamera çevirme, kulaklık/ahize/hoparlör, izin reddi, ağ geçişi, gürültü ve eski APK'dan veri kayıpsız OTA ayrıca fiziksel olarak doğrulanır. Bu testler yapılmadan tüm cihaz/ağ/ENC kalitesi tamamlandı denmez.

Sunucu değişikliği yalnız yeni tablo/sütun/RPC ekler; mevcut mesaj verisine dokunmaz. Gerekirse Edge wrapper'da calls seçeneği kapatılır, eski messaging çalışır; yeni tablo bırakılır. Kurulu uygulama kaldırılmaz, Room veya kimlik sıfırlanmaz; APK geriye sürüm düşürülmez.

Canlı Supabase geçişi `20261025123907_private_call_signaling`, Edge `lanu-relay` v4: gerçek ECDSA imzalı üç geçici kimlikle eski sync formatı, E2EE davet, idempotency, yetkisiz çağrı/ACK reddi, karşılıklı kişi iptali ve sıfır sohbet kontaminasyonu smoke geçti. RLS açık; anon/authenticated tablo ve üç RPC erişimi yok. Güvenlik danışmanı yalnız tasarım gereği server-only yedi tabloda INFO `rls_enabled_no_policy` bildirir; herkese açık policy eklenmez. Rehber: https://supabase.com/docs/guides/database/database-linter?lint=0008_rls_enabled_no_policy.
