# LANU gizli alan G01–G14 — eş zamanlı iş ve kabul matrisi

**Başlangıç:** 30 Ekim 2026. **Kapsam:** Sadece gizli alan; normal hesap makinesi ekranı, matematik ve tasarım dondurulmuş halde kalır. Kaynak: [Proje Anayasası](../PROJE_ANAYASASI_VE_DUZELTILECEKLER.md). Bu belge işlevsel aktivasyon veya saha sertifikası değildir.

**Başlangıç GitHub kanıtı:** Son doğrulanan genel sürüm `v1.2.0-signed.910`, main `c0b71e0`; beş başlık için güvenli PR #315, final-head CI #914 PASS sonrası main `36e1504` ile birleşti; main #916 emülatör OTA/yayın sonuçları ayrıca gerekli. Matematik PR #316 hesap makinesi dondurması kapsamında **birleştirilmeyecek**, kullanıcı açık onayı yoksa yeniden işlenmeyecek.

| G | İşlev alanı | Çalışan/kodlanmış temel | Eksik gerçek aktivasyon veya kanıt | Paralel hat |
| --- | --- | --- | --- | --- |
| G01 | Gizli giriş ve oturum | PIN/isteğe bağlı PIN, FLAG_SECURE, çağrı sonrası kilit | Gerçek A55 soğuk/yeniden açılış, çağrı kapanışı, demo izlerinin kamusal yüzeyde bulunmaması | A: UX/gizlilik |
| G02 | Kimlik ve E2EE | Karşılıklı kod, mevcut ECDH/AES-GCM, peer-key binding (#315) | İleri gizlilik Double Ratchet, eski-yeni sürüm ve downgrade güvenliği, bağımsız kripto denetimi (#114) | B: protokol |
| G03 | Ağ dayanıklılığı | Relay retry/timeouts/koruma | Gerçek farklı ağ, 503, kayıp ACK, roaming ve bağlantı yokken güvenli fallback | C: sunucu/ağ |
| G04 | Mesaj trafiği | Şifreli metin, offline outbox, teslim/okundu | İki cihazda idempotent retry/süreç ölümü/OTA/çakışmaların gerçek kabulü | C: mesaj |
| G05 | Çağrı/mesaj bildirimi | Ayrı izinli FCM tercihleri, nötr bildirim, server kayıt/yenileme | Gerçek A55+ikinci cihaz Doze/kilit/normal process kill, zil yaşam döngüsü, HMS alternatifi (#133); mesaj saat aralığı overflow testi bu PR'de | D: bildirim |
| G06 | Sesli arama | Gerçek WebRTC native ses, iki cihaz kullanıcı beyanı | İki OEM ICE/TURN düşme ve yeniden bağlanma, gecikme, bütünlük, testli fallback (#3) | E: arama |
| G07 | Görüntülü arama | WebRTC kamera/renderer/izin | Kamera çevirme/reconnect/arka plan, iki cihaz ses-görüntü senkron kabulü (#3) | E: arama |
| G08 | Ses işleme/yönlendirme | Android AEC/NS/AGC yetenek sorgusu, ses rota durumu ve hata geri dönüşleri | Gerçek RTP üzerinde ENC/AEC ölçümü, HFP/BLE/USB, donanımsal ANC vaadi yok (#245) | E: ses |
| G09 | Fotoğraf/belge/video | 18 KB E2EE inline fotoğraf/PDF/TXT ve yerel şifreli medya yardımcıları | Büyük medya server şifreli upload/download erişimi, EXIF, kota, video, iki cihaz red/timeout (#85) | F: medya |
| G10 | Yerel veri/backup | Room v4, şifreli tam .lanubak, sentetik SQLCipher shadow | Kayıpsız **canlı** SQLCipher, rollback/downgrade ve fiziksel geri yükleme (#113) | G: veri |
| G11 | Veri silme/KVKK | Yerel silme ve fail-closed readiness; #266 sahnelenmiş sunucu tasarımı | Sahip doğrulamalı sunucu silme, kamuya açık HTTPS talep sayfası, insan/hukuk kabulü (#224) | H: veri hakları |
| G12 | Premium UX/erişilebilirlik | Compose gizli ekranlar, durum mesajları, bazı semantics | TalkBack, 200% font, 48dp hedefler, iki navigasyon modu, kamera/arama izin açıklaması | A: UX/gizlilik |
| G13 | Üretim dayanıklılığı | Hilt/R8, yerel mahrem crash raporu | Kullanıcı izinli Crashlytics/özel tanılama, gerçek A55/Huawei/Xiaomi ANR ve kaynak ölçümü (#100) | I: üretim |
| G14 | İmza/OTA/kurtarma | Signed OTA emülatör testleri, APK/SHA/signer Release, dummy key yedek testi | Sahip kontrollü cihaz dışı gerçek PKCS12 kurtarma, fiziksel yerinde OTA, signer doğrulama (#83/#84) | I: yayın |

**İlgili açık issue'lar:** #3, #5, #83, #84, #85, #100, #113, #114, #133, #177, #224, #245. **#5 matematik dondurması nedeniyle otomatik yeni iş değildir.** #177 yalnız ilgili bütün kategori kanıtları gerçeğe uygun olduğunda kapanır. G03/G04/G05/G08/G09/G10/G12/G13 gibi bölümlerde daha önce kapatılmış issue'lar ile tekrar iş açılmadan kod/test ve saha kanıtları ilişkilendirilmeli.

## Bütün iş akışlarında mecburi kanıt
1. Gerçek implementasyon/izinler/sunucu durumu ile sadece plan, demo, stub veya regresyon kodunu ayır.
2. Ana hizmet → alternatif → testli fallback → hata yönetimi → fail-closed güvenli varsayılan → mahrem ölçüm → smoke test.
3. Her feature için `source-commit`, son PR HEAD test, ana dal SHA, APK tag, otomatik unit/Room/API34/R8, gerçek telefon senaryosu, geri dönüş ve kullanıcı onayı durumunu ayrı yaz.
4. Birbirinden bağımsız A–I hatları paralel PR'larla ilerleyebilir; **tek ana dal ve tek signer/OTA sırası** serileştirilir. 7+5 issue'yu otomatik "kapandı" diye işaretleme.
5. Uygulamanın normal hesap makinesi alanında, bildirimlerde, loglarda, OTA notlarında, uygulama bilgilerinde PIN, kişi, sohbet, çağrı, özel modül veya saklı erişimle ilgili ipucu yok. Ancak gizli alandaki gerçek kullanıcı bilgilendirmesi eksiksiz kalmalı.
6. Fiziksel A55 + ikinci OEM test olmadan ve hukuk/imza sahibi onayı gerektiren işlemlerde fail-closed. SQLCipher gerçek migrasyonunu, Double Ratchet canlı anahtar geçişini, uzak veri silmeyi, ücretli TURN/FCM/Supabase servisini ve signing key reset'ini otomatik başlatma.
7. Tam sonuç yalnız **işlev gerçekten çalışıyor + son kaynaklı CI + imzalı GitHub APK + fiziksel cihaz/sunucu sahip kabulü + geri dönüş denemesi** ile kanıtlanabilir. CI/emülatör fiziksel kanıt yerine geçmez.

## Bu PR'deki küçük, bağımsız iyileştirme
Yalnız özel mesaj FCM zaman damgası kabulü `Long` taşmalarını ve yanlış pozitif kabulü dışlar. Mesaj göndermez, bildirim tercihlerini otomatik açmaz ve hesap makinesinin kodunu değiştirmez. Tüm yeni kullanıcıya açık aktivasyonlar diğer bağımsız ve testli PR'larla yapılacak.
