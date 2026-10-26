# Ön planda gerçek sesli ve görüntülü arama

## 25.10.2026 — Çok sağlayıcılı STUN, Camera2/Camera1 fallback ve ses işleme koruması (yeni PR)

Bu dal, Camera2/HAL sorgusu veya kamera oluşturma hatasıyla otomatik Camera1 enumerate/create girişimi yapar. Her gerçek kaynak birim testlerle doğrulanır; kamera bulunmazsa güvenli hata gösterilir, sanal kamera yerine geçmez. AEC ve NS donanım desteği bağımsız sorgulanır; desteklenmeyen işlev WebRTC'nin kendi yazılım APM yoluna bırakılır; ikinci Android AudioEffect eklenmez ve ölçülmemiş ANC/dB başarımı iddia edilmez. Ağ keşfi artık Google + Cloudflare STUN kullanır; Cloudflare dahil STUN tarafları IP adresini görebilir. Çoklu STUN, TURN olmadığı sürece zor NAT için kesin çözüm değildir. Gelecekteki SQLCipher geçişi kanıtlanıncaya kadar ayrı CI politikası yıkıcı Room migration'ı yasaklar; **bu kontrol SQLCipher'ın kurulduğu anlamına gelmez**.


Sohbetin üstündeki telefon ve kamera düğmeleri native WebRTC görüşmesi açar. Her iki telefon güncel sürümde, karşılıklı kişi kayıtlı ve güvenli alan açık olmalıdır. Kullanıcı aramayı başlatır/kabul eder, gereken Android mikrofon/kamera iznini verir. Güvenli alanın kapanması görüşmeyi kapatır. Kapalı uygulamaya çağrı bildirimi bu sürümün kapsamında değildir.

## Bağlantı ve gizlilik

Mevcut ECDSA imzalı relay, ayrı 90 saniyelik opaque sinyal tablosu ve karşılıklı kişi şartı kullanılır. ECDH/AES-GCM çağrı AAD'si `LANU-CALL-v1:<signal UUID>` ile sohbetten ayrılır. Offer/answer SDP yalnız RAM ve uçtan uca şifreli sinyaldedir; anahtar/SDP loglanmaz veya Room'a yazılmaz. Sunucu call ID, taraf ID ve zaman metaverisini görür. Medya WebRTC DTLS-SRTP ile doğrudan şifreli gider. Karşı taraf ile Google ve Cloudflare'ın STUN hizmetleri bağlantı IP adreslerini görebilir; bağlantı öncesi kullanıcıya açıklanır. STUN yalnız adres keşfi yapar; TURN bulunmadığından bazı NAT/operatör ağlarında bağlantı mümkün olmayabilir.

Gelen davetler mevcut ön plan chat sync yanıtına yalnız v1 isteyen istemcide eklenir. Boşta yeni Edge polling döngüsü yoktur. Arama esnasında 2 saniyelik callSync, sınırlı mesaj gönderim bütçesi ve maksimum kısa ömürlü sinyaller vardır. Eski istemci yanıt formatı aynı kalır. Android arka plan worker'ı çağrı açamaz.

## Mikrofon/kamera kontrolünün doğrulanması

Yeni görüşmenin istenmesi veya davetin çalması kameranın aktif olduğu anlamına gelmez: `cameraOn` başlangıçta **false** tutulur. Yalnız native WebRTC `start(video=true)` başarıyla döndüğünde, aynı görüşme hâlâ ön plandaysa aktif olarak gösterilir; başarısız veya iptal edilen başlangıçta asla aktif görünmez. Sesli görüşmelerde kamera kapalı kalır. Medya henüz başlatılmadığı `CONNECTING` aşamasında mikrofon/kamera kontrol düğmeleri HAL'e komut göndermez. Bu durum otomatik testlerle korunur.

Görüşmede mikrofonu susturma ve kamerayı kapatma/açma işlemi yalnız gerçek WebRTC ADM/AudioTrack veya kamera capturer + VideoTrack işlemi başarıyla tamamlandıktan sonra arayüzde güncellenir. Önceye ait iyimser kamera/mikrofon ikon değişimi kaldırıldı. Aynı kontrolün art arda iki kez basılması bir önceki medya komutunu bitmeden çoğaltmaz. Kamera açma/kapama komutu devam ederken özel alan kapanırsa geç cevap eski UI durumunu değiştiremez. İzin/HAL hatasında başarısızlık kullanıcıya bildirilir ve görüşme güvenli kapatılır; hiçbir başarısızlık 'kamera kapandı' veya 'mikrofon susturuldu' başarı göstergesi üretemez. Sesli görüşmede sonradan izinsiz kamera açılması desteklenmez.

Bu değişiklik yalnız yerel medya/UI yaşam döngüsü doğrulamasıdır; gürültü azaltma dB kazanımı, fiziksel OEM kamera güvenilirliği veya SQLCipher'ı kanıtlamaz.

## Medya ve dayanıklılık

`io.github.webrtc-sdk:android:150.7871.04` sabittir. AudioDeviceModule VOICE_COMMUNICATION, destekli donanım AEC/NS ve WebRTC APM/AGC kullanır; mevcut bağımsız Android DSP adaptörü aynı ses oturumuna ikinci kez eklenmez. Cihaz/HAL hatasında görüşme güvenli kapanır. Kamera2, gerekirse Kamera1 ve codec donanım/yazılım alternatifleri bulunur. Ses odağı ve rota kapanışta geri bırakılır. Kamera kapatıldığında capture durur. Renderer ana thread üzerinde başlatılır; mutex ile yaşam döngüsü sıralanır ve renderer kapanmadan EGL bırakılmaz. Track, capturer, PeerConnection, EGL ve audio module temizlenir. Kamera HAL hataları açıkça görüşmeyi sonlandırır.

Bağlandı yalnız gerçek transport ve iki yönde ses paketleriyle gösterilir. Davet 45 saniye, kurulum 35 saniye, kopma 25 saniye bütçelidir. Arayan taraf bir gerçek ICE restart deneyebilir. Google STUN ve bağımsız Cloudflare STUN keşif alternatifleri kullanılır; TURN yoktur, otomatik maliyet doğurulmaz. Bağımsız STUN alternatifleri TURN işlevini sağlamaz. Katı NAT veya operatör ağlarında bağlantı kurulamayabilir; kullanıcı farklı ağ deneyebilir. Ücretli altyapı otomatik açılmaz. ANC veya ölçülmüş gürültü dB iddiası yapılmaz.

## Bağımlılık kabulü

Maven Central POM'unda BSD-3-Clause; AAR manifest minSdk21; armeabi-v7a, arm64-v8a, x86 ve x86_64 native kitaplıkları doğrulandı. ARM64 ELF PT_LOAD hizaları 16384 bayt olarak kontrol edildi. App minSdk23 değişmedi. `org.webrtc` ve `org.jni_zero` JNI ABI R8 içinde korunur. Native lib BSD lisansı ve sürüme sabitlenmiş üçüncü taraf bildirimleri APK içinde `assets/licenses` altında dağıtılır (`WebRTC-third-party-notices.md` SHA256 `d1f9382c6878ac024155fd6d44a5977329108bb8b0a01cea40e4a2f1d7de252e`, kaynak `webrtc-sdk/android/v150.7871.04/Licenses/WEBRTC.md`); kaynaklar: https://repo.maven.apache.org/maven2/io/github/webrtc-sdk/android/150.7871.04/ ve https://webrtc.googlesource.com/src/+/refs/heads/main/LICENSE.

## Kabul ve geri dönüş

Node/PGlite protokol/SQL, Robolectric durum makinesi ve API34 debug/release gerçek native paket testi zorunludur. Samsung ve Xiaomi arasında iki yönlü ses/video, mute, kamera çevirme, kulaklık/ahize/hoparlör, izin reddi, ağ geçişi, gürültü ve eski APK'dan veri kayıpsız OTA ayrıca fiziksel olarak doğrulanır. Bu testler yapılmadan tüm cihaz/ağ/ENC kalitesi tamamlandı denmez.

Sunucu değişikliği yalnız yeni tablo/sütun/RPC ekler; mevcut mesaj verisine dokunmaz. Gerekirse Edge wrapper'da calls seçeneği kapatılır, eski messaging çalışır; yeni tablo bırakılır. Kurulu uygulama kaldırılmaz, Room veya kimlik sıfırlanmaz; APK geriye sürüm düşürülmez.

Canlı Supabase geçişi `20261025123907_private_call_signaling`, Edge `lanu-relay` v4: gerçek ECDSA imzalı üç geçici kimlikle eski sync formatı, E2EE davet, idempotency, yetkisiz çağrı/ACK reddi, karşılıklı kişi iptali ve sıfır sohbet kontaminasyonu smoke geçti. RLS açık; anon/authenticated tablo ve üç RPC erişimi yok. Güvenlik danışmanı yalnız tasarım gereği server-only yedi tabloda INFO `rls_enabled_no_policy` bildirir; herkese açık policy eklenmez. Rehber: https://supabase.com/docs/guides/database/database-linter?lint=0008_rls_enabled_no_policy.
