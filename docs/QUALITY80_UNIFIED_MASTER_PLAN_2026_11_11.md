# LANU — QUALITY80: 11 kategoride 80+ birleşik ürün yükseltmesi

**Tarih:** 11 Kasım 2026 · **Durum:** PLAN ONAYLI KOD HEDEFİ / HENÜZ YAYINA HAZIR DEĞİL · **Başlangıç:** main 142d4540c5df5000c29f87959ddf781fce3636a1 · **Son doğrulanmış dağıtım:** v1.2.0-signed.1284, CI #1284 tam başarılı.
**Ürün:** kanunal99-jpg/LANU-Hesap-Makinesi, com.aistudio.lanucalc.kxwzqm, Android API23+, GitHub Releases imzalı APK + kullanıcı onaylı yerinde OTA. **Google Play yayını yoktur.**

## Yönetici kararı ve tek teslim tanımı
Kullanıcı 80 altında kalan 11 alanın TÜMÜNÜ 80 ve üzerine taşıyan **tek büyük, koordineli geliştirme planı ve tek doğrulanmış yayın** talep etti. Birimin ortalaması ya da CI yeşil sayısı değil, her satırdaki **ayrı bağımsız kabul** ölçüdür. Plan önce GitHub commit, sonra geliştirme, sonra cihaz+güvenlik kabulü, SONRA yayın. Yalnız doküman/manifest güncellemesi için APK sürümü artırılmaz. Kullanıcı verisi, PIN, kimlik, E2EE ve eski OTA signer değiştirilmez veya silinmez.

**Tek programın teslimleri:** bütünleştirilmiş ürün kodu, Android/relay testleri, ölçüm sonuçları, bağımsız tehdit modeli, cihaz kalite matrisi, güvenli rollback, güncel 18 satırlık puan tablosu, tek Release APK + sha256 + signer, kurulu uygulama üzerine güvenli yükleme kanıtı, sürüm notları. Alt işler geliştirici için izole commit/PR olabilir fakat ayrı mini ürün çıkışı değildir.

## 11 alan: başlangıç, kabul, uygulama ve engeller

| # | Kategori | Başlangıç | Hedef | Sistemik geliştirme paketi | 80+ kabul kanıtı |
|---|---|---:|---:|---|---|
| 1 | E2EE/kimlik | 73 | 80+ | Uzun dönem statik ECDH yerine review edilmiş sürümlü ratchet anahtar yenilemesi, peer kimliği bağlama, rollback/replay/downgrade engeli; mevcut v1 metin geçmişi ve offline sıra saklama | Eski-yeni protokol çiftleri, çift cihaz, gecikmiş/tekrarlı 1k mesaj, kurtarılmış yedek, başarısız upgrade, key-compromise tehdit modeli ve bağımsız kripto onayı; kanıt yoksa ratchet kapalı |
| 2 | Fotoğraf/video/belge medya | 70 | 80+ | E2EE dosya baştan sona iş akışı (picker→preflight→şifreleme→kota→imzalı yükleme→alıcı grant→hash doğrulama→indirme→temizlik), çakışmaya dayanıklı transfer; ücretsiz quota | Uçtan uca iki cihazda fotoğraf/belge/video, değiştirilmiş ciphertext/hash, ağ kaybı/Range/TUS, low-storage, iptal, eski sürüm ve izin reddinde sızıntısız rollback; kapalı bayrak geçerli kalabilir |
| 3 | Sesli görüşme | 75 | 80+ | E2EE/DTLS-SRTP çağrı yaşam döngüsü, odak/hold-resume, Opus/FEC, RTP kanıtı, tekrar arama, zayıf ağ ve gerçek TURN/STUN alternatif değerlendirmesi | İki fiziksel cihazda çift yön ses; SIM+WhatsApp gelen çağrı sonrası bekleme/dönüş, arka plan/foreground, Wi-Fi↔LTE, 30 dk kesintisiz seans, BT/HFP ve bozuk ICE; karşı tarafın sesi gerçekten duyduğu kabul |
| 4 | Görüntülü görüşme | 73 | 80+ | Video kamera lifecycle/Camera2→Camera1 fallback, R8/ABI, adaptif bitrate, görüntü olmadan sesin korunması, izin/geçiş/mute güvenliği | İki üreticide ön/arka kamera ve ekran kilidi, 20 dk video, kamera bozulsa ses sürmesi, kötü ağ/video alıcısı; gerçek video frame sayacı ve kullanıcı kabul kaydı |
| 5 | Gürültü/yankı AEC/NS/AGC | 68 | 80+ | Tek WebRTC APM veya OEM hardware pipeline; başarısız OEM’de testli SW APM; mono/HPF/Opus tercih doğrulama; RNNoise yalnız ABI/lisans/ısı ölçümüyle ikinci katman olmadan değerlendir | A55 + ikinci OEM, sokak/fan/motor/rüzgâr/hoparlör çift konuşma; aynı mikrofonda A/B örneklem, anlaşılabilirlik ve echo return loss, gecikme P95/CPU; ölçülmemiş dB/ANC iddiası YOK |
| 6 | Arka plan çağrı/FCM | 66 | 80+ | Kullanıcı kontrollü ayrı call/message izinleri, token iptal+kayıt yarışı, soğuk açılış, geç gelen uyarı TTL, Android 13+ notification izinleri, Doze/lifecycle, kilit PIN | A55 + Huawei/Xiaomi eşdeğeri ikinci farklı OEM; ekran kilidi/DND/sessiz, ağ yok, process death, LTE, izin aç/kapat; beklenen bildirim/çalma oranı her şart için sayılarla; zorla durdurmada OS sınırı açıklanmış |
| 7 | Room/veri şifrelemesi | 68 | 80+ | Plaintext Room→SQLCipher v4 atomik shadow migration; Keystore zarflama, WAL/SHM, disk rezervasyonu, geri alma/restore; 7 tablo ve kimlik/PIN/kuyruk invariants | Veritabanı tüm 7 tablo birebir parity ve gerçek eski APK üzerinden migration, disk dolması/güç kesintisi/bozuk keystore/wrong key, API23/29/34/36, cihaz verisi korunur; güvenli değilse live cutover kapalı |
| 8 | Android/OEM | 67 | 80+ | API23–36, Android 12+ asenkron BT setCommunicationDevice onayı, ses odağı, Huawei GMS'siz izin/bildirim alternatifleri, ANR/crash koruması ve 16KiB native hizalama | CI API23/29/34/36 + A55, ikinci Xiaomi/Huawei saha regresyonu (cihaz mevcutsa üçüncü OEM), 30 senaryo; BT seçim ONAY geldiğinde gerçekten routed, yanlışsa güvenli fallback |
| 9 | Performans/tanılama | 75 | 80+ | Çağrı/websocket/Room/Compose P50/P95 ölçümü, jank, pil, RAM, ANR, lifecycle, gizlilik güvenli crash/diag; maliyetsiz ve opt-in; yavaş ağda bounded retry | Önce-sonra P50/P95 2 gerçek cihaz; örneklem sayısı, ortam ve yüzde95 CI; sıfır tekrarlanabilir P0 crash/ANR; bellekte sınırlı buffer ve ağ hata oranı bütçeleri |
| 10 | Erişilebilirlik | 74 | 80+ | Tüm call/mute/hold, PIN, OTA, fotoğraf ve yedek ekranlarına semantik/testTag, 48dp target, kontrast, 200% font, ekran döndürme ve erişilebilir hata metinleri | TalkBack uçtan uca akış (hesap, PIN, çağrı, medya, OTA), 200% font, küçük ekran, cihaz üzerinde ekran görüntüsü/manuel kontrol; tüm kritik eylemler odaklanabilir |
| 11 | KVKK / hesap silme | 61 | 80+ | Kullanıcının istediği gerçek imzalı hesap sahipliği, transactional/idempotent sunucu silme, ilişkili ciphertext/meta/FCM temizliği, retention ve kullanıcıya açık şeffaf bilgi sayfası; **Play Store işi yapılmaz** | Canlıdan izole doğru sahip silme, yanlış hesap reddi, retry/rollback/0-row, veri kapsamı; herkese açık HTTPS başvuru/aydınlatma doğrulaması; veri sorumlusu ve hukuki insan onayı olmadan tamamlandı denmez |

### Ortak omurga: bütün 11 alan için katmanlı dayanıklılık
- **Ana işlem → Alternatif → Fallback → Hata yönetimi → Güvenli varsayılan → Gizliliği koruyan izleme → Smoke test.**
- Çoklu ağ bağımsızlığı: ICE/STUN keşfi (var olan), izinli ve maliyet kontrollü TURN, ses önce video sonra. TURN'süz her NAT ağının garanti edildiği söylenmez.
- Veri/kimlik/medya: imza ve alıcı bağlama, idempotency token ve TTL; eski callback/yeni kuşak, tekrar/yarış, geç izin, 429/500, offline, parse overflow ve iptalde deterministik state.
- Fallback canlı kullanıcı medyasını iki filtreye sokmaz, gerçek aktif görüşmenin şifrelemesini sıfırlamaz. Aktif ratchet veya SQLCipher geçişi körlemesine açılmaz. Yeni maliyetli servis/depoma/FCM/TURN kotası için kullanıcı onayı zorunludur.
- Varsayılanlar: mikrofon/kamera/konum yalnız kullanıcı izniyle, mesaj/kişisel içerik sistem bildiriminde veya public loglarda yok, opt-out korunur.
- Android 23+, Huawei GMS'siz durum için FCM bağımlılığı tek teslim yolu sayılmaz; desteklenmeyen platform koşulu dürüst gösterilir.

## Entegrasyon tasarımı — tek büyük sürüm hattı

**Başlangıç:** main 142d454... v1.2.0-signed.1284. Açık [#357](../pull/357) ratchet/SQLCipher taslağı, [#331](../pull/331) imzalı medya taslağı ve [#266](../pull/266) sunucu silme taslağı birbirinin relay/repository/auth koduyla çakışıyor: aynen merge ETME. Tek master entegrasyon dalında her paketin yalnız güncel main'le uyuşan kısmı, migration ve geri dönüş kanıtı geldikçe taşınır. P0 düzeltmeleri izole kod incelemesinden geçer; tek son sürüme toplanır.

**Katman sırası:**
1. **P0 Sözleşme ve ön test:** korunacak yedi Room tablosu ve v1 mesaj protokolü snapshot; sürümlü zarfı, relay payload'ı, offline kuyruğu, medya hash/permission ve cihaz izinleri için contract tests. OTA önce/sonra imza/kimlik sertifika eşitliği.
2. **P0 Durum sahipliği:** tek session generation, RTC audio focus, FCM call wake token, storage operation owner ve DB transaction boundary. Eski callback yan etkisi reddedilir.
3. **P0 E2EE ve Room laboratuvarı:** sentetik eski sürüm→yeni sürüm migrasyonu, şifreli shadow, ratchet offline gated; kullanıcı geçmişinin kopya ve geri dönüş doğrulaması olmadan flag açma yok.
4. **P0 Media + hesap silme sunucu:** signed authenticated role/scope; gerçek server migration öncesi migration diff, RLS deny test, çoklu eşzamanlı istek, maliyetsiz kapasite, geri alınabilir deploy.
5. **P1 WebRTC + OEM:** mikrofon/BT / encode RTP / hold, video fallback, FCM açılış; native ABI/ısınma/CPU ve Android sürüm uyumu.
6. **P1 Erişilebilirlik/perf:** 200% font, TalkBack ve negatif UI akışları, privacy-safe yerel P50/P95 ölçüm, A55 OEM kabul.
7. **Yayın adayı (RC):** 11 satırın tamamı ayrı ayrı kanıtlandıysa tek signed Release/OTA. Aksi halde yalnız taslak kod ve eksik kanıt matrisi; yayın numarasını artırma.

## Önce planla → sonra yayınla: kesin kapı şeması

| Kapı | Doğrulama | Geçmediğinde |
|---|---|---|
| G0 — Master plan | Anayasa kararı, 11 satır 80 hedefi, bağlı PR/issue ve baseline SHA | Kod değişimi başlamaz |
| G1 — Güvenlik / geri dönüş | E2EE peer identity, PIN, Room v4 parity, yanlış alıcı, server role, crash/rollback testleri | Ratchet/SQLCipher/silme kapalı, hiçbir user data silinmez |
| G2 — Modül entegrasyonu | Son entegrasyon PR HEAD; Node/Postgres/RLS, Kotlin/JUnit/Robolectric; Lint, R8, native ABI, API23/29/34/36; no flaky skip | Merge ve release yasak |
| G3 — Fiziksel kabul | Samsung A55 + ikinci farklı OEM: ses/video çift yön, FCM, AEC/NS, BT, kamera, OTA ve 200% TalkBack; hata ve sample logları (PII redacted) | 11 alandan ilgili puan 80 değil; otomatik release yok |
| G4 — Bağımsız güvenlik / hukuk | Ratchet kripto review, signer cihaz dışı şifreli yedek gerçek restore, KVKK veri silme / retention / hukuki onay, ücret inceleme | İlgili yüksek risk özellik kapalı; 80 tamam değil |
| G5 — Üretim CI | Son PR HEAD CI yeşil, squash main, **yeni** main CI 5/5, R8 minified, iki eski signed APK'dan gerçek Android14 emülatör OTA ve 7 tablo parity | Release yok |
| G6 — Gerçek yayın | Öncekiyle signer sertifika SHA eşit; versionCode artırılmış; v1.2/v1.1 OTA köprü etiketi; public HTTPS APK/SHA/signer bağımsız byte-for-byte readback, cihazda kullanıcı onayıyla yerinde kurulum | Yayınlandı ifadesi yok |
| G7 — Son puan | 18 satır tekrar puanla, 11 hedefin **hepsi** >=80 ve kanıt linki var; özel ağ/cihaz/hukuk redlerini açık belirt | "Hepsi 80+" iddiası yok; önceki iyi sürüm son yayın |

## Gözlem/ölçüm protokolü
- Ses: temiz ve trafik/fan/rüzgâr A/B, hoparlör yankı, aynı cihaz/mesafe, referans ve karşı uç alıcı karşılaştırması; kulaklık ANC ile gönderilen ENC ayrımı. Sahte dB/10 üzerinden kalite iddiası yok. Mikrofon örnekleri analize yalnız açık test katılımcısı izniyle cihaz üzerinde; uzak API'ye PCM çıkmaz.
- Görüşme: iki fiziksel uçta ayrı audio/video RTP paket artışı ve karşı taraf algısı; Wi-Fi↔LTE, 5–25% test kaybı, 150–500ms RTT, ses odağı/BT/kamera değişimleri.
- FCM: testler yalnız açık izinli test telefonlarında, push içeriği PII'siz; beklenen/teslim/silinme sayaçları Doze ve ekran kilidi dahil; zorla durdurma OS sınırı kabul.
- Performans: P50/P95 = ham sayaçlarla, örneklem sayısı ve cihaz/build kaydıyla. Ölçülmemiş sıfır çökme/2–3sn gibi iddia yok.
- Veri: bütün 7 Room tablosunda satır sayısı/anahtar/hash karşılaştırması, kullanıcı PIN/kimlik doğruluğu; XML, Room WAL/SHM, storage ve backup restore ayrı.
- Erişilebilirlik: 48dp, TalkBack sesli eylem, 200% font, ekran döndürme ve ağ hatası ekranı; A55 ve ikinci cihaz ekran kayıtları yalnız kişisel içerik gizlenerek.

## Açık riskler, kapama sahipleri, ölçü birimi
| Engel | Kanıtı sağlayabilecek | Bugünkü güvenli karar |
|---|---|---|
| A55 / ikinci fiziksel telefon, SIM/WhatsApp, BT, FCM, gerçek OTA | Cihaz sahibi / gerçek fiziksel cihaz testi | Yalnız yazılım CI/emülatör puanı; gerçek kabul gerektiren satırlar <80 |
| Anahtar cihaz dışı şifreli yedek ve gerçek geri yükleme | İmza anahtarı sahibi | Signer değiştirme, anahtarı repoya taşıma YASAK |
| KVKK veri sorumlusu / gerçek HTTPS sayfa / retention hukuku | Ürün/veri sorumlusu ve gerekirse hukuk | Hesap silme 80+ ilan etme; Google Play işi yok |
| Ratchet ve DB canlı migration | Bağımsız inceleme + cihaz kabulü | Feature-gate FAIL CLOSED; eski veri korunur |
| Ücretsiz medya/relay/TURN kapasitesi | Altyapı sağlayıcısı kota kanıtı ve kullanıcı | Ücret oluşturma; limit içinde kal, onaysız etkinleştirme |
| Branch protection / codesign disaster recovery | Repo sahibi | CI yeşili kalite kanıtı; prod yönetişim P0 açık |

## Planın başlangıç durum kaydı
- **Kanıtlanan:** Başlangıç main ve signed.1284 tam CI/OTA/signature/public readback. Mevcut AEC/NS/Opus, tek WebRTC APM, hold/resume ve kontrollü bitrate kodu var.
- **Kanıtlanmayan:** Sesin ölçülmüş netlik kazancı, cihazlar arası WhatsApp/SIM davranış matrisi, gerçek SQLCipher/Double Ratchet kullanıcı geçişi, büyük medyanın uçtan uca tüm kullanıcılarda etkinliği, gerçek sunucu silme, hukuki ve OEM bağımsız kabul.
- **Puanlar (11 satır):** 73,70,75,73,68,66,68,67,75,74,61. Bunlar son kanıtlı başlangıç değerleridir; ilerleme PR sayısına göre artırılmaz.
- **Puan ilerleme kuralı:** Her kategori için issue/PR + sürüm commit + CI + Android/gerçek cihaz/evidence + güvenlik/legal + başarısızlık/rollback kayıtları bir arada olmadan >=80 yazılmayacak. Alt kategoriler hedefe geçene dek **tek büyük sürüm henüz yayınlanmaz**.

## Son kontrol listesi (RC çıkışında eksiksiz)
- [ ] 11 kategoride ayrı 80+ kabul kanıtı (ve 18 kategoride gerileme yok)
- [ ] İki fiziksel cihazda RTC, FCM, ses A/B ve BT/wired test sonuçları
- [ ] Ratchet/sunucu silme/Room için bağımsız güvenlik ve veri koruma onayı
- [ ] Android API23/29/34/36, R8, 16KiB, lint, unit, instrumentation ve regression full green
- [ ] Eski imzalı APK üzerinden **aynı paket/sertifikayla** OTA, PIN/2011. ve 7 tablo koruma
- [ ] Ücretsiz servis, kota, log gizliliği, opt-in/opt-out, veri sorumlusu ve yasal kontrol
- [ ] Yeni main CI 5/5 + public signed APK SHA256/signer byte-for-byte
- [ ] Fiziksel cihazdaki kullanıcı onaylı yerinde kurulumu doğrulayan kanıt; eski app kaldırılmadı
- [ ] Yayın notlarında ölçülmeyen/OS sınırlı davranışlara dürüst açıklama
