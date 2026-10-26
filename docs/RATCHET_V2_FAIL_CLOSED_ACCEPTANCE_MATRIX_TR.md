# LANU — İleri gizlilik ve Double Ratchet v2 kabul kapıları

**Durum (27.10.2026): TASLAK / PROTOKOL KAPALI.** Bu dosya yeni bir kriptografi motoru, uç nokta, anahtar veya etkinleştirilmiş protokol değildir. Bugünkü `PeerMessageCrypto` statik ECDH + AES-GCM kullanır; mesaj başına IV yenilemesi **ileri gizlilik (forward secrecy)** ya da **ele geçirilme sonrası iyileşme (post-compromise security)** sağlamaz. Mevcut `MessageProtocolUpgradeGate` yalnızca bir tasarım kapısıdır, gerçek v2 handshake değildir.

## Güven sınırları

1. Mevcut `MessagingIdentity`, kullanıcı kodu ve karşılıklı kişi sabitlemesi korunur; yeni kurulum/eski kurulum aynı kullanıcıyı farklı kimlik sanmaz.
2. `v2` sunucu veya karşı taraf tarafından düz metin bir özellik bayrağıyla etkinleştirilemez. Uzak ucun ECDSA kimliği, imzalı taze ephemeral anahtarı, cihaz kimliği, katılımcı çifti ve görüşme/mesaj oturumu tek bir transcript'e bağlanır. Sahte/eksik/tekrarlanan transcript reddedilir.
3. İncelemeden geçmiş bir ratchet uygulaması ve lisans incelemesi olmadan `libsignal`, özel kripto primitive'i, `X3DH` veya `PQXDH` uygulanmış gibi gösterilmez. Mevcut Android API23+ uyumu korunur.
4. `v2` kimliğe/cihaza güvenle sabitlendikten sonra sunucunun eski capability ilan etmesi, offline kuyruk, geri yükleme, uygulama downgrade veya saat kayması otomatik `v1` geri dönüşe izin vermez. `v1` geçmişi silinmez; güvenli read-only erişim ayrı ele alınır.
5. Anahtar malzemesi/ratchet sayacı/atlanan mesaj anahtarları **transactional, Keystore tarafından korunmuş ve rollback algılayabilen** depoda olacaktır. İsteğe bağlı PIN özelliğine bağlanmaz. Şüpheli restore sonrası sessiz sıfırlama/yeniden kimlik üretme yasaktır.
6. Eski sürüm ve GMS'siz cihazlar desteklenmediği bir v2 oturumu sessizce çözmüş/sent etmiş gibi davranamaz; açık uyumsuzluk uyarısı ve kimlik koruması gerekir. Sunucu ve FCM mesaj içeriği, ratchet anahtarı, transcript veya SDP taşımaz.
7. Mesaj/kimlik/anahtar/ratchet konumu, exception mesajları, açık IP adresi ve transcript ham veri olarak log, crash telemetry veya günlük dosyasına yazılmaz.

## Mutlaka geçecek test matrisi

| Alan | Güvenli kabul |
|---|---|
| İlk anahtar değişimi | İmzalı transcript ve karşılıklı sabitlenen peer kimliği; üçüncü taraf veya farklı cihaz transcript'i reddi |
| Anahtar rotasyonu | Her gönderme/alma ratchet durumu tek atomik commit; iki eşzamanlı işlemde anahtar tekrar kullanımı yok |
| Yeniden oynatma | Aynı şifreli zarf / yeni nakil UUID'si / eski handshake karşısında tek kabul |
| Kayıp ve sıra dışı teslimat | Sınırlı skipped-key penceresi, bellek/disk limiti, tekrar deneme, idempotent ACK |
| Çevrimdışı geçiş | Wi-Fi/LTE değişimi, yeniden giriş ve kilit ekranında otomatik medya/kimlik açılmaması |
| Yedek ve geri yükleme | Eski ratchet sayaçlarının geri sarılması tespit edilip fail-closed; eski v1 mesajları kaybolmaz |
| Çoklu cihaz | Her cihaz ayrı oturum/ephemeral state; bir cihazın silinmesi ötekinde sessiz kimlik değişimi yaratmaz |
| İzin ve PIN | PIN isteğe bağlı kalır; medya izinleri yalnız görüşme başlatılınca alınır |
| Uyumluluk | v1-only alıcı/v2-pinned gönderici açık uyumsuzluk; **sessiz downgrade yok** |
| Kaynak sınırları | Sabit zarf, transcript ve skipped-key limitleri; bellek/CPU/DB kaynak taşmasına karşı negatif testler |
| Cihaz kabulü | Android API23/29/34/36, R8 release, iki gerçek telefon, GMS'siz temel çalışma |
| Kriptografik inceleme | Bağımsız dış değerlendirme; lisans, sürümleme, test vektörleri, çözme/tamper testleri |

## Yayın kararı

- **Aşama 0 (mevcut):** v1 şifreli mesaj sistemi değişmez; ratchet iddiası yok. `MessageProtocolUpgradeGate` fail-closed regresyonları korunur.
- **Aşama 1:** Lisans onaylı, test vektörleri bulunan gerçek bir kütüphane/motor; sentetik anahtarla kapalı uçtan uca test; canlı peer veya Room verisi yok.
- **Aşama 2:** Bağımsız audit, geri yükleme/downgrade/çift cihaz testleri, ayrı test sunucusu ve açık kullanıcı onayı. Yeni v2 yalnız güvenli capability handshake doğrulanırsa.
- **Aşama 3:** Sınırlı, geri alınabilir rollout. V2'den v1'e otomatik downgrade asla geçerli rollback yöntemi değildir.

**Blokaj:** Bu kanıtlar sağlanmadan Android üretiminde `v2` mesaj şifrelemesi etkinleştirilmez. #114 ancak gerçek uçtan uca v2 ve cihaz kabulüyle kapatılabilir.
