# PROJE ANAYASASI — 15.10.2026

## 28.10.2026 — 80 altında kalan 11 kategoriyi eş zamanlı geliştirme (yeni kullanıcı kararı)

**Bağlayıcı kapsam:** Son kanıtlı 18 kategori puanlamasında 80 altındaki 11 alan eş zamanlı mühendislik iş hattında tutulacaktır: E2EE/kimlik (73), medya (70), sesli arama (75), görüntülü arama (73), AEC/NS (68), arka plan/FCM (66), Room/yerel veri (68), Android uyumluluk (67), performans/tanılama (75), erişilebilirlik (74), KVKK/mağaza (61). İş sırası tek tek bitirmeyi beklemez; fakat P0 güvenlik ve veri kaybı önceliği, PR izolasyonu ve yayımlama kapıları değişmez.

- Her kategori için var olan PR/issue önce kullanılır; olmayanlar için küçük, birbirinden bağımsız kod + başarısızlık yolu + regresyon/test PR'ları açılır. Bir PR'ın yeşil CI'ı başka kategoriye veya yayımlanmış APK'ya devredilemez.
- Başarı: kod commit'i + son HEAD CI, güvenlik/kapsam testi, gerekiyorsa API23/29/34/36 ve A55/ikinci OEM fiziksel kabulü, sonra kontrollü main merge ve ayrı main CI / imzalı OTA read-back. **80 hedefi, 80 kanıtı demek değildir**; son belgelenmiş puanlar kabul eksikse aynen kalır.
- E2EE protokolü ve DB canlı migration'ı, PIN, 2011., Room v4, mesaj/kimlikler, FCM opt-in, Android signing key, OTA signer, kişisel medya ve yerel veriler geri dönülmez veya onaysız değiştirilemez. Gizlilik yasal onayı, public hesap silme servisi ve iki fiziksel cihaz testi ancak gerçekten tamamlanınca kabul edilebilir; sentetik CI bunların yerine geçmez.
- Eş zamanlı ilerleme, CI kaynaklarını boşa harcayan tekrar PR/kopya özellik anlamına gelmez. P0 PR'ların değişen SHA'sı tekrar doğrulanır; yalnız en son HEAD yeşilse merge adayı olur. Kullanıcı onaysız maliyet/yayın veya uygulama sıfırlama yok.
- Kabul matrisi: docs/PREMIUM_KALITE_90PLUS_PROGRAM_2026-10-27.md ve ilgili issue/PR kanıt kayıtları.

---


## 27.10.2026 — Ara kapı: 70 puanın altında kategori kalmayacak

**Kullanıcı kararı:** Önceki 80+ ve 90+ nihai hedefleri aynen korunur. Bunlardan önce, mevcut uzman değerlendirmesinde 70'in altında olan **beş kategori** için 70+ **kanıtlı ara kabul kapısı** getirilmiştir. Bu bir puan yükseltme talimatı değildir: gerçek test ve kaynak kod kanıtı olmadan 61/66/67/68 değerleri değiştirilmez. Önce kritik P0, sonra P1; başarısız test önce kök neden ve regresyon.

| Sıra | Kategori | Başlangıç | Minimum | Uygulanacak en yakın iş ve kabul kanıtı |
|---|---|---:|---:|---|
| 1 P0 | Mağaza / KVKK | 61 | 70 | [PR #220](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/pull/220) gizlilik URL/doğru özellik beyanı kod ve CI; **gerçek erişilebilir HTTPS politika, veri sorumlusu ve hukuki insan onayı ayrıca şarttır**. Kod değişikliği bu dış onayın yerine geçmez |
| 2 P0 | Arka plan çağrı | 66 | 70 | #133: FCM sadece çağrı, gelen sesli zil+görsel; kilit ekranı, normal süreç ölümü, sessiz/DND, PIN yeniden kilidi; Android unit/CI ve **iki gerçek cihaz** ayrı |
| 3 P0 | Android uyumluluğu | 67 | 70 | #82: A55 çökme/ANR çıkış nedeni; API23/29/34/36 matrisi, ikinci üretici; eski kurulum üstüne imzalı OTA veri koruması |
| 4 P0 | Room / yerel koruma | 68 | 70 | #113: gerçek Room plaintext sınırını gizleme; sentetik v4 SQLCipher shadow migration + WAL/disk dolması/rollback + schema parity; üretim migration **onaysız etkinleştirilmez** |
| 5 P1 | Gürültü engelleme | 68 | 70 | Gerçek WebRTC AEC/NS kullanımının yetenek/route testi; çifte işlemeyi engelleme, Bluetooth/kulaklık; ölçüm yoksa ANC/ENC/dB iyileşmesi iddiası yok |

**İş sırası:** 1→2→3→4→5. Her adımda mevcut kod/CI incelenir; gerekirse ayrı küçük PR, son PR HEAD'de test, merge sonrası yeni main CI ve imzalı APK. Fiziksel cihaz veya bağımsız güvenlik onayı eksikse durum **kısmi/doğrulanmadı** olarak kalır. Başarı: 18 kategorinin **tamamı ≥70**; ardından 18/18 ≥80 ve kanıtlı 90+ programı. Kullanıcının Room/PIN/2011./E2EE/OTA imzası ve verileri silinmez. Ücretli servis izinsiz açılmaz.

---

## 27.10.2026 — 90+ PREMIUM KALİTE PROGRAMI VE KANIT KAPISI (en güncel bağlayıcı karar)

> Bu karar yalnız LANU Hesap Makinesi deposu ve com.aistudio.lanucalc.kxwzqm paketi içindir; LANU Lens ile birleştirilmez. Aşağıdaki eski tarihli SHA, CI, sürüm ve puan kayıtları kendi günlerinin **tarihsel anlık görüntüleridir**, güncel tamamlandı kanıtı sayılmaz.

- **27.10 uzman puanı:** 18 kategorinin eşit ağırlıklı ortalaması **76/100**, **11 kategori 80 altında**. Hedef **önce bütün kategoriler 80+**, ardından kanıtlı **genel/kritik 90+**. Puanlar fiziksel cihaz, otomatik sertifikasyon veya pentest değildir. Kanıt yoksa puan artırılmaz ve 80 altı saklanmaz.
- **Başlangıç SHA ve CI:** main f61e8dc2564d682f5c41ec4fe82dfb9e3347e3b8; PR #218 CI #642 başarılı; **main CI #643 denetim anında çalışıyordu**. Son yayımlı APK signed.641, daha önceki d35c451 commitindendir; #218'li güncel main yayını tamamlandı diye iddia edilemez.
- **P0 önceliği:** CI kök nedeni, PIN/E2EE yanlış alıcı, veri kaybı/Room, OTA imzası, #82 A55 ANR, #83 imza yedeği, #3/#133 iki gerçek cihaz çağrı+zil. Canlı SQLCipher ve Double Ratchet bugün mevcut değildir; geçiş koşullu ve bağımsız inceleme gerektirir.
- **Program:** G0 27–30 Ekim CI ve release -> G1 31 Ekim–6 Kasım A55/iki cihaz/veri -> G2 7–13 Kasım E2EE/Room offline güvenlik -> G3 14–20 Kasım WebRTC/medya/NS -> G4 21–27 Kasım erişilebilirlik/performans -> G5 28 Kasım–4 Aralık hukuki/mağaza -> G6 5–18 Aralık saha ve 90+ kabul. Bu tarihler **koşullu hedef** olup teslim sözü değildir.
- **Asla aşılmayacak kapı:** kırmızı test -> log kök nedeni -> minimal düzeltme/regresyon -> güncel PR HEAD CI -> merge -> *yeni* main CI -> signed GitHub Releases APK, SHA-256, önceki sertifika eşleşmesi, gerçek public download. Gerçek cihazda veri kayıpsız yerinde OTA ayrıca test edilir. Kurulu uygulama/Room/PIN/E2EE kimliği silinmez, izinsiz ücretli servis/anahtar değişimi yapılmaz.
- **Tam 18 kategori ve sorumlu/kanıt matrisi:** [Premium 90+ programı](docs/PREMIUM_KALITE_90PLUS_PROGRAM_2026-10-27.md). Bu ek ve kanıt düzeni anayasanın yürürlükteki parçasıdır.

---

## 27.10.2026 — Premium geliştirme, başarısız test kök nedeni ve puan eşiği (kullanıcı kararı)

- **Kapsam:** LANU Hesap Makinesi için sesli/görüntülü gerçek görüşme, gizli alanın E2EE mesajlaşması, cihazın yeteneklerine uygun gerçek WebRTC AEC/NS işleme ve premium/erişilebilir Compose arayüzü önceliklidir. Demo görüşmesi veya kanıtsız ANC/dB vaadi gerçek işlev gibi gösterilemez.
- **Hata döngüsü:** Her başarısız CI/Android/relay/güvenlik testi için hata adımı ve ilgili log önce incelenir; kök neden doğrulanır, yan etkisiz düzeltme ve regresyon testi eklenir, son PR HEAD'de test yeniden çalıştırılır. Başarısız test yok sayılmaz; sadece testi susturmak/skip etmek çözüm değildir.
- **Kategori puanı:** Güvenlik, özel mesajlaşma, görüşmeler, ses işleme, arayüz/erişilebilirlik, test/CI, sürüm/OTA ayrı ayrı **kanıta dayalı 0–100** puanlanır. Kullanıcının **80 altına düşmeme hedefi** bir yayın kabul kapısıdır; kanıtsız puan artırma ya da 80 altı gerçek sonucun saklanması değildir. Kanıt eksikse 'doğrulanmadı' olarak işaretlenir ve üretime hazır denmez.
- **Veri koruma:** Sohbet ve mesaj silme gibi geri alınamayan eylemlerde açık kullanıcı teyidi aranır; sohbet okunurken yeni ileti gelen ekran kullanıcıyı beklenmedik biçimde kaydırmaz. 2011. giriş, isteğe bağlı PIN, E2EE kimlikleri, mevcut Room kayıtları ve OTA signer asla sıfırlanmaz.
- **Kanıt sınırı:** PR CI başarı, güncel main CI + Release checksum, iki farklı fiziksel üreticide gerçek WebRTC/video/çağrı zil testi ve ölçülmüş ses kalitesi ayrı aşamalardır; kullanıcıya gerçekleşmeyen aşama bitmiş sayılmaz.

---

## 27.10.2026 — Güncel üretim durumu, BEŞ geliştirme ve test kanıtları (09:45 TR)

> **Kanıt ilkesi:** Bu bölüm 27.10.2026 tarihinde GitHub main/CI/Releases, açık PR/issue ve Supabase canlı fonksiyon/yalnız sayım SQL okumalarıyla doğrulanmıştır. Aşağıdaki önceki tarihli durumlar tarihseldir. `CI success` **fiziksel telefon kabulü, tam SQLCipher/Double Ratchet veya canlı FCM teslimi** anlamına gelmez.

### Üretim ve testlerin doğrulanmış durumu

- **Son `main` commit:** `ab858d36754421f384c057034b1c78e9c5bfc8c6`. [GitHub CI #607](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/actions/runs/40458565649) **completed/success**; Android 14 gerçek emülatör/Room kabul işi **12 başarılı adım**, Build APK/relay security/unit-Robolectric/lint/R8/imza/hash işi **38 başarılı adım**, Publish verified APK işi **9 başarılı adım** (bunlar *iş adımlarıdır*, test vakası sayısı değildir). Başarısız adım yok.
- **Yayımlanan APK:** [`v1.1.0-signed.607`](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/releases/tag/v1.1.0-signed.607), `app-debug.apk` (53.792.841 bayt), `app-debug.apk.sha256`, `app-debug.apk.signer.txt` üç ayrı Release asset'i; tag hedefi yukarıdaki `main` SHA ile eşleşir. CI imza/hash/yayın doğrulamasından geçti; **telefon üzerinde 607 OTA yükleme/PIN/mesaj veri sürekliliği ayrıca test gerektirir**. Kullanıcı mevcut uygulamayı silmeyecek ve verilerini temizlemeyecek.
- **PR kod testleri:** [CI #608](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/actions/runs/40458758091) ve [CI #609](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/actions/runs/40458766662) her ikisi de **success** (Android API34 12, build/test 36 başarılı iş adımı; PR publish job **skipped — doğru**). [PR #200](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/pull/200) ve [PR #201](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/pull/201) **AÇIK**, henüz `main` veya Build 607 içinde **değil**. #200 çelişkili/tekrarlanan signer metadata reddi; #201 duraksayan/eksik ciphertext medya stream reddi. Birleştirme sonrasında yeni main CI ve ayrıca yeni Release kanıtı gerekir.
- **Sunucu:** Supabase `lanu-relay` **v13 ACTIVE**; `lanu_call_wake_devices` **2** kayıtlı cihaz, son 24 saatte `wake_requested` **0**. Bu yalnız veritabanı sayımıdır; **en son APK/Edge ile iki gerçek telefon FCM zil+görsel çağrı başarılı** sonucu çıkarılamaz. Token, PIN, SDP, içerik, kişi/kimlik verileri sorguda veya bu raporda yoktur.

### 5 stratejik geliştirme — uygulanan kanıt / eksik kabul / karar

1. **P0 İmzalama anahtarı ve felaket kurtarma — KISMİ / [Issue #83](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/issues/83).** Şifreli yedek/kurtarma betikleri ve sentetik testler mevcut; arşiv genişleme bombası ([#181](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/pull/181)) ve imzalama dizini symlink ikamesi ([#196](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/pull/196)) engelleri `main` içinde. İmza sabitliği CI'da doğrulanır. Çelişkili signer kanıtı reddi #200 **CI yeşil fakat PR açık**. **Kabul açık:** Sahibin kontrolünde *şifreli cihaz dışı* gerçek PKCS12 yedeğinin bulunması ve orijinal anahtara dokunmadan **ayrı cihaz/ortamda gerçek kurtarma + sertifika SHA-256 eşleşmesi**. Hiçbir şifre/keystore/özel anahtar sohbete, issue'ya, artifact'e veya Release'e taşınmayacak.
2. **P0 Samsung A55 çökme/ANR — KISMİ / [Issue #82](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/issues/82).** Android çıkış nedeni gizli veri içermeyen kategorilerle raporlanıyor ([#187](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/pull/187)); tekrar eden çökme/ANR izleme ([#183](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/pull/183)) ve sıfır ilerlemeli yerel rapor okumanın sınırlanması ([#199](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/pull/199)) `main` içinde. **Kabul açık:** Samsung A55'te gerçek geçmiş kapatma nedeninin *hassas içerik olmadan* görülmesi, belirli kök nedenin doğrulanması ve 607 sonrası tekrarlı PIN/hesaplama/mesajlaşma/arka plan ANR saha testleri. CI başarıları bu gerçek cihaz sorununu otomatik kapatmaz.
3. **P0 Sesli/görüntülü WebRTC — KISMİ / [Issue #3](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/issues/3) ve [#133](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/issues/133).** Gerçek WebRTC, E2EE imzalı çağrı sinyalleşmesi, RTP/ICE epok kanıtı, tek ICE restart limiti ve [#197](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/pull/197) native ICE `FAILED` sonrası kontrollü toparlanma `main` içinde; [#188](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/pull/188) geç/ters sıralı örneklerin reddi. **Kabul açık:** 607 sürümünde Samsung A55 + farklı üretici iki fiziksel cihazla gelen arama zil+görsel, PIN yeniden kilidi, ses/video çift yönlü RTP, Bluetooth/kulaklık ve Wi-Fi↔mobil geçiş; zor NAT için **kanıtlanmış TURN fallback yok** (yalnız STUN), ücretli TURN kullanıcı onayı olmadan etkinleştirilemez. Geçmiş kullanıcı iki cihaz görüşme beyanı yeni sürümün tam kabul kanıtı değildir.
4. **P0 Room → SQLCipher — YALNIZ HAZIRLIK / [Issue #113](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/issues/113).** Dosya/WAL/disk read-only preflight, [#156](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/pull/156) fail-closed shadow geçiş kapısı, [#182](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/pull/182) sentetik satır eşitliği ve [#189](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/pull/189) 7 tablo şema parity kontrolleri `main` içinde. **Gerçek Room dosyası hâlen plaintext SQLite; SQLCipher kurulumu, şifreli dosya yazımı veya üretim migration etkin DEĞİL.** Kabul: izole sentetik v4→şifreli DB aç/kapat/restore/rollback, kesinti/disk dolması/WAL ve API23/29/34/35, iki üreticili saha kanıtı, ayrı anahtar ve kayıpsız geçiş; destrüktif migration veya sessiz mevcut-veri silinmesi yasak.
5. **P1 Mesaj ileri gizliliği — YALNIZ TASARIM / [Issue #114](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/issues/114).** [#185](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/pull/185) v2 tehdit modeli ve [#190](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/pull/190) kanıtsız kripto geçişini reddeden kontrol `main` içinde. **Çalışan iletişim hâlen statik ECDH + AES-GCM v1; Double Ratchet, forward secrecy veya post-compromise security etkin DEĞİL.** Kabul: bağımsız kriptografi/lisans incelemesi, imzalı handshake, sürümlü zarf, sıra dışı/kayıp/tekrar paketler, yedek/rollback/sessiz downgrade engeli ve iki cihaz birlikte çalışabilirlik; mevcut kullanıcı mesajları ve anahtarları korunacak.

### Değişmez koruma ve yayınlama hükmü

ANA SERVİS → ALTERNATİF → GERÇEK TEST EDİLMİŞ FALLBACK → HATA → GÜVENLİ VARSAYILAN → LOG/İZLEME → SMOKE TEST. PIN/kimlik/E2EE/rehber/mesaj/ota sertifikası korunur. Ücretli servis kullanıcı onayı olmadan açılmaz. Her kritik kod PR'si güncel HEAD'de testlenir; main Android CI, R8, API34, SHA-256 ve stabil imzalı **erişilebilir Release APK** olmadan `yayınlandı` denmez. PR CI geçmesi veya algoritma tasarımı fiziksel cihaz kabulü ya da canlı kriptografik koruma demek değildir. Öncelikli saha kabulü: imza yedeği gerçek restore, A55 crash nedeni, iki cihaz canlı çağrı; SQLCipher/ratchet ise yalnız izolasyon ve kripto inceleme sonunda ilerler.

---

## 26.10.2026 — Firebase doğrulaması olmadan yeni çağrı APK'sı YAYINLAMA (P0)

**Saha / CI kök nedeni:** Kullanıcı `LANU_FIREBASE_CLIENT_JSON` korumalı GitHub Actions Secret'ına Firebase Android `google-services.json` metnini eklediğini bildirdi. [CI #500](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/actions/runs/40302493509) bundan **önce** başlayıp `NOTICE: Firebase client config absent; background call push is unavailable.` mesajıyla APK yayımladı. Birim testinin sahte fixture için yazdığı `secure config installed` mesajı **gerçek secret kuruldu kanıtı değildir**.

**Sürüm/doğrulama kapısı:** Yeni trusted main APK yayını öncesi installer'ın doğru Firebase projesi `gen-lang-client-0658022317` ve Android paketi `com.aistudio.lanucalc.kxwzqm` eşleşen yapılandırma dosyasını **gerçekten oluşturması** zorunlu. Yoksa yayımlama job'ına geçmeden **CI açık ve güvenli biçimde başarısız olacak**, eski kullanıcı verileri/sunucu etkilenmeyecek. Hiçbir secret/JSON/token/özel anahtar CI çıktısına yazılmayacak. Sonrasında sadece APK/CI başarısı yeterli değildir; Supabase servis hesabı, token kaydı ve iki gerçek telefon arka plan çağrı + zil kabulü ayrıca kanıtlanmalı. FCM/izin/sistem zorla durdur sınırları aynen geçerlidir.

---

## 26.10.2026 — Firebase proje eşleşmesi: kapalı LANU'ya sadece gerçek sesli/video çağrısı (P0)

**Kullanıcı seçimi:** Firebase projesi `gen-lang-client-0658022317` LANU'nun çağrı uyandırması için hedef projedir. Mevcut Google Cloud / Gemini / Willy Kilo projeleri **silinmeyecek**; silme, FCM bağlamak için gerekli değildir. Android package `com.aistudio.lanucalc.kxwzqm` sabittir, mevcut veriler/Room/E2EE anahtarı/rehber/PIN/OTA imzası korunur.

**Kritik yayımlama kapısı:** Trusted main release CI yalnızca paket adı ve **tam Firebase proje kimliği** eşleşen Android `google-services.json` dosyasını kabul eder; başka Firebase projesi, yanlış paket, servis hesabı JSON'u veya hatalı yapılandırma fail-closed. Kullanıcı korumalı GitHub Actions Secret alanına **JSON'un kendisini** `LANU_FIREBASE_CLIENT_JSON` olarak veya geriye dönük `LANU_FIREBASE_CLIENT_JSON_BASE64` olarak girebilir. İkisi aynı anda girilmişse güvenli reddet; hiçbiri yoksa açıkça `background call push unavailable` bildir. Hiçbir JSON içeriği, token veya özel anahtar log/PR/kaynak kodunda görünmez. Android Firebase config ile **Supabase Edge'deki server-side** `LANU_FCM_SERVICE_ACCOUNT_JSON` iki ayrı kimliktir; yanlış yere yüklenemez.

**Kabul:** Firebase projesinde kayıtlı Android uygulama ve ilgili FCM HTTP v1 API etkin; CI logunda gerçek trusted kurulum adımında uygun proje+paket doğrulandı; yeni APK SHA/signer/OTA indirme doğrulandı; Sunucuda aynı projeye ait service account yetkili; Supabase'de en az alıcı cihaz kaydı sayımı (token görülmeden) kanıtlandı; gerçek iki cihazda LANU normal arka plan/süreç ölümünde sesli ve görüntülü çağrı görsel+sesli teslimi test edildi. Mesaj dış bildirimleri **kesinlikle yok**. Android 'Zorla durdur' gibi sistem engelleri aşılıyor diye söz verilmez. FCM kurulmamış yeni APK **yayınlanmış olsa bile arka plan çağrı tamamlandı denemez**.

---

## 26.10.2026 — Gelen sesli/görüntülü çağrıda görsel uyarı **ve** işitilebilir zil (YENİ KABUL ŞARTI)

**Sahadan kullanıcı bildirimi:** Gelen çağrıda görsel bildirim görünüyor ancak telefon sesli moddayken ses çalmıyor. Bu durum çözülene kadar arka plan çağrı bildirimi sesli kabulden geçmiş sayılmaz. Kullanıcı mevcut görsel bildirimin korunmasını ve yalnız gerçek sesli/görüntülü INVITE için zil sesi ister.

**Zorunlu davranış:** Uygulama dışından gelen gerçek çağrı veri push'u, mevcut güvenli anonim görsel bildirimi koruyarak Android bildirim sisteminde **gelen arama için ayrı yüksek önem seviyeli ve sistem telefon zil sesini kullanan bir kanal** üzerinden gösterilir. `AudioAttributes.USAGE_NOTIFICATION_RINGTONE` kullanılır. İlk kurulan eski `lanu_private_calls_v1` kanalının sesi Android'de güncellenemeyeceği için **ayrı sürümlü kanal** açılır; eski mesaj/medya kanalı etkilenmez. Android 7 ve altında kanal yerine builder ses+yüksek öncelik uygulanır. Kullanıcı uygulama bildirim sesi/kanal sesini Android ayarlarından değiştirebilir.

**Saygı duyulacak ayarlar:** Zil Android/cihaz yönetimindedir; telefonun sessiz/titreşim modu, Rahatsız Etmeyin, kişi tarafından kapatılmış uygulama/kanal izni, OEM/pil kısıtları veya kullanıcı sessiz zil seçimi **asla bypass edilmez**. Kendi başına audio playback servisi veya `Ringtone.play()` ile zil zorlanmaz, ses seviyesi değişmez. Uygulamanın arayüz sessizliği, yalnızca çağrı bildirim kanalının ses davranışını engellemez. Bildirimin görsel içeriği, opt-in, E2EE, `2011.` ve PIN kilidi, FCM kısa ömür/tekrar engeli aynı kalır; mesaj/sohbet/fotoğraf/medya için ses **yasaktır**.

**Doğrulama:** Android 8–16 sesli kanal importance/high, sistem ringtone URI, `AudioAttributes` ve eski kanaldan sürümleme; Robolectric tests. Mevcut cihazda önceki sessiz kanalın yeni kanala etkisi / kişisel mute tercihinin korunması; Samsung A55 + diğer gerçek cihaz, normal/rahatsız etmeyin/sessiz/titreşim, uygulama arka planda/ekran kilitli, geç bildirim ve yinelenen uyarı, izin reddi, PIN kilidi için gerçek saha testi. PR yalnız HEAD CI tam başarılıysa merge; main CI, Releases APK/hash/signer ve fiziksel ses çıktısı birbirinden ayrı kanıtlanır.

---

## 26.10.2026 — Arka plan arama bildirimi iptalinin kalıcı güvenli başarısızlık yönetimi

- **Risk:** Kullanıcı gelen çağrı bildirimini kapattığında cihazda opt-in hemen sıfırlansa bile çevrimdışı/503 durumunda uzak FCM cihaz tokenı kalabilir. FCM sunucusunun iptal isteğini almadığı kullanıcıya görünür olmalı; uzak saklama konusunda gerçekte gerçekleşmemiş iptal vaat edilmez. Güvenlik ve mahremiyet gereği kullanıcı çağrı verileri, kimlik, PIN veya E2EE anahtarları değiştirilmez.
- **Uygulama kararı:** Yerel opt-out **önce** kalıcı `enabled=false, pending_remote_revoke=true` olarak atomik yazılır; cihazda FCM otomatik başlangıç kapatılır. Yalnız mevcut kimlikle imzalı `callWakeToken(null)` başarılı dönünce bekleyen iptal işareti silinir. Sunucu/kimlik yokluğu halinde işaret korunur; kullanıcı güvenli alanda ağ gelince iptali yeniden deneyebilir. Ağ hatası/senkronizasyon başarılı olarak sunulmaz. İsteğe bağlı bildirim yeniden etkinleştirmeden önce bekleyen uzak iptal tamamlanır; böylece gecikmiş iptal yeni cihaz tokenını yanlışlıkla silmez.
- **Kabul:** Android/Robolectric tercihin varsayılan kapalı olduğunu, kapatmanın kalıcı bekleyen iptal bıraktığını, hata halinde bekleyen işaretin silinmediğini ve başarılı uzaktan onaydan sonra silindiğini doğrular. Sohbet/medya bildirimine izin verilmez; cihaz verileri silinmez, ücretli servis devreye alınmaz. Android testleri, Lint, R8 ve API34 main CI + Release APK/public checksum ayrı tamamlanır. **FCM cihaz yapılandırması ve canlı backend onayı olmadan uçtan uca arka plan çağrı kabulü tamamlanmış değildir.**

---


## 26.10.2026 — Korunan CI Firebase istemci yapılandırması (arka plan çağrı uyandırma)

- `app/google-services.json` hiçbir zaman GitHub commit, yayın/artifact eki, açık PR veya logda bulunamaz. `.gitignore` ilgili yolu korur; Firebase **HTTP v1 service-account private key** GitHub'daki istemci sırrıyla ASLA karıştırılamaz: sadece Supabase Edge Secret içindir.
- CI release (yalnız güvenilir main/workflow_dispatch) Firebase proje istemcisi yapılandırmasını `LANU_FIREBASE_CLIENT_JSON_BASE64` korumalı GitHub Secret üzerinden alır. UTF-8 JSON, proje kimliği, mobil uygulama paket adı `com.aistudio.lanucalc.kxwzqm` ve doğru Android client gözlenmeden dosya üretilmez. PR CI **asla protected secret almaz**. Sır verilmemişse debug/release derlemesi bozulmaz; çağrı push opt-in işlevi **inaktif** kalır ve yayında 'bildirim çalışıyor' iddiası yasaktır.
- Kullanıcı/owner Firebase yapılandırmasını GitHub Secret üzerinden yapmalıdır; asla sohbet içine uzun ömürlü servis anahtarı, özel imza/FCM tokenı kopyalatılmamalıdır. Lokal geçici dosya `app/google-services.json` yayımlanan release assetlerine kopyalanmaz.
- Gereken test: geçerli/missing/yanlış package/bozuk JSON/oversize için sır göstermeyen yükleyici testleri; Android native unit/Lint/R8+API34 CI; imzalı APK, SHA ve canlı Edge+FCM+gerçek 2 cihaz kabulü **ayrı doğrulama**.

---

## 26.10.2026 — KESİN ÜRÜN DAVRANIŞI: uygulama kapalıyken çağrı, mesaj yalnız uygulama içi

**Kullanıcının 26.10.2026 tarihli netleştirmesi (önceki çağrı kuralını tamamlar, geçersiz kılmaz):** Kullanıcı LANU hesap makinesi ekranında, başka bir uygulamadayken, ekran kilitliyken, arka plandayken veya Android tarafından uygulama işlemi normal şekilde sonlandırılmışken **yalnızca gerçek gelen sesli ya da görüntülü çağrının karşı tarafa ulaşmasını**; seçilmiş bildirim izni ve telefonun sesli modu varsa **işitilebilir zil + nötr görsel gelen çağrı uyarısı** istiyor. Görsel bildirim tek başına başarı değildir. Gizli alanı önceden açma veya 15 dakikalık WorkManager bekleme zorunluluğu olamaz. Arama yalnızca kimliği doğrulanmış karşılıklı kişi, kısa süreli E2EE INVITE ve kayıtlı cihaz için tetiklenebilir. Aktif görüşme daveti varsayılan bildirim olarak değil, gerçek arama deneyimi olarak ele alınmalıdır.

**Mesaj sınırı (kesin):** Metin, fotoğraf, medya ve diğer sohbet olayları **yalnız uygulamanın kendi gizli alanı içinde** yeni mesaj / okunmamış göstergesi oluşturabilir; Android sistem bildirim alanı, kilit ekranı, uygulama dışı banner, zil, titreşim, FCM yüksek öncelikli wake, yeni servis veya SMS üzerinden **hiçbir mesaj bildirimi gönderilmez**. Çağrı alıcısı ve FCM taşıyıcısı sohbetten ayrı tutulur. Hiçbir zaman mesaj içeriği, sohbet başlığı veya gönderen bilgisi push'a yazılmaz.

**Android'in 'kapalı' hallerini karıştırma:** Kullanıcının uygulama ekranından çıkması veya son uygulamalardan kaydırması, normal süreç ölümünden yeniden açılması, ekran kilidi ve Doze test edilmeli; Google servisli cihazlarda yüksek öncelikli kısa ömürlü FCM, gerekli olduğunda Android'in işlemi başlatıp yerel gelen arama bildirimini göstermesi için kullanılır. Android **Ayarlar → Zorla durdur**, cihaz üreticisinin katı pil kısıtı, bildirim izninin kapalı olması, FCM/GMS yokluğu ya da bağlantı kesintisi durumunda teslimat garanti edilemez. Sistemi kandıran sonsuz arka plan servisi, pil optimizasyonunu izinsiz kaldırma veya zorla ses çalma **yasak**. Huawei/GMS'siz cihazlar ancak ayrı testli sağlayıcı ile desteklenmiş sayılır.

**Ses ve görüşme yaşam döngüsü:** Cihazın zil/bildirim kanalının kullanıcı tarafından açık olması, normal ses modu ve DND izinleri korunur. Arama için yeni/güncellenebilir bildirim kanalı, yüksek öncelik, uygun çağrı zil sesi, nötr kilit içeriği ve sınırlı süreli davet gerekir. Yalnız tek seferlik kısa *mesaj bildirimi sesi* hedefteki arama deneyiminin tamamını kanıtlamaz: süreklilik, kabul/red/iptal, yanlış/geç gelen wake, zaman aşımı ve sesin sonlandırılması iki gerçek cihazda ayrıca test edilir. Android CallStyle/izinli full-screen/uygun çalışan servis yöntemleri Android/Play politikasına göre değerlendirilir, izin olmadan tam ekran açılmaz. Dokunma/yanıtlama otomatik çağrı kabulü değildir; mevcut `2011.` ve isteğe bağlı PIN/biometrik kontrol zorunludur. Mikrofon/kamera kendiliğinden başlatılmaz.

**Kabul matrisi:** A55 + ikinci OEM cihazda [gizli alan ön planda, hesap makinesi, başka uygulama, ekran kilitli, işlem normal şekilde sonlandırılmış, son uygulamalardan kapatılmış, Doze, ağ değişimi] × [sesli/görüntülü] × [sesli/sessiz/DND/bildirim izni kapalı] deneyleri; arayanın gerçek E2EE davetinin teslimi ve yanıt sonrası görüşmenin başlaması; mesaj geldiğinde **sıfır sistem bildirimi/sesi**; çağrı sonlandıysa **sıfır geç gelen zil**, kullanıcı opt-out yaptıysa **sıfır ses**; yanlış veya yinelenen davet tekrar zil üretmez. Kabul ve loglarda kişisel/E2EE veri yok. Release APK, SHA, imza sürekliliği, Supabase/Firebase canlı yapılandırma, CI ve fiziksel kabul **ayrı kanıtlardır**; hiçbiri tek başına 'uygulama kapalıyken çağrı %100 çalışıyor' iddiası değildir.

---

## 26.10.2026 — Yalnız gerçek çağrı için arka plan bildirimi (YENİ ÜRÜN KARARI)

**Kullanıcı kabulü:** Kullanıcı sesli ve görüntülü görüşmenin iki gerçek cihazda başarıyla test edildiğini bildirdi. Bu bir **kullanıcı saha beyanıdır**; cihaz/Android sürümü, rapor, imza veya laboratuvar ölçümü sağlanmadığı için kod/CI ile bağımsız saha sertifikası olduğu iddia edilemez. Önceden açık fiziksel test listesi, bu bildirimle notlanarak ayrıntılı OEM/OTA/gürültü testleri açık tutulur.

**İstenen deneyim:** Kullanıcı gizli alandan gerçek sesli veya görüntülü çağrı başlatırsa, karşı taraf LANU'nun hesap makinesi ekranında, başka bir uygulamada veya LANU arka plandayken gelen çağrı bildirimi alabilmeli. **Bu özellik sohbet, mesaj, fotoğraf, medya veya başka olaya bildirim göndermez.** Tekrar bildirim ve eski/bitmiş çağrıya zil çalma engellenir. Kullanıcı dokunduğunda mevcut gizli alan `2011.` ve isteğe bağlı PIN/biometrik erişim akışı **atlatılmaz**; arama otomatik yanıtlanmaz, mikrofon/kamera otomatik açılmaz.

**Gizlilik/güvenlik:** Kilit ekranı, bildirim gövdesi ve push yükünde gönderen kişi adı, kodu, kimliği, sohbet, mesaj, SDP, aday IP, PIN, anahtar veya rehber yer almaz; nötr arama işareti kullanılır. Bildirim kanalının varlığı dahi özel kullanım hakkında ipucu olabilir: ayar güvenli alanda opt-in olarak açılır; Android 13+ POST_NOTIFICATIONS izni istenir, reddedilirse sessiz güvenli varsayılan. Çağrı token'ı yalnız imzalı, kayıtlı mevcut kimlikle sunucuya bağlanır, çıkışta devre dışı bırakılır ve loglanmaz. Yalnız doğrulanmış karşılıklı kişiler için, kısa süreli, oran sınırlı wake; istek tekrarında çift bildirim yok.

**Teslimat mimarisi ve dürüst sınırlar:** Mevcut 15 dakikalık WorkManager ve gizli alan foreground çağrı poll'u arka plan gerçek zamanlı çağrı değildir. Android Google servisli cihazda yüksek öncelikli FCM veri bildirimi, doğrulanmış relay çağrı işareti ve yerel bildirim kullanılır. Sunucu çağrı sinyalini saklayıp alıcıya opaque wake gönderir; FCM içeriği asla çağrı kabul veya kullanıcı oturum açma yetkisi değildir. Çağrı kabulü yine E2EE sinyal çözümüyle ön planda doğrulanır. Firebase kurulum/Google hizmeti/OEM, ağ/Doze, zorla durdurma, izin veya kullanıcı pil kısıtları teslimatı geciktirebilir/engelleyebilir; **%100 garantili teslimat denmez**. Huawei/GMS'siz cihaz için alternatif bildirim sağlayıcısı ayrı, gerçek testli yapılmadan varmış gibi gösterilmez. Firebase veya Supabase ücretli tarife ve servis anahtarı kullanıcı onayı olmadan etkinleştirilemez.

**Uygulama kabul sırası:** 1) Anayasa ve tehdit modeli; 2) sadece önceden yetkilendirilmiş opt-in ve token kaydı, RLS/service-role izolasyonu ve atomik yeniden gönderim limiti; 3) sunucuda aç/kapat anahtarlı push forwarder ve yanlış kaynak/tekrar/rate-limit testleri; 4) FCM alıcı ve Android 13/14/15/16 bildirim izni/kanalı, gizli içerik, dokununca güvenli giriş; 5) uyku/arka plan/uygulama kapanması/çağrı bitti/geç kalmış push ve gerçek iki cihaz testleri; 6) yalnız son PR SHA CI başarılıysa merge; 7) main CI + imzalı APK Release/hash/read-back + canlı backend migration/deploy/smoke ayrı kanıtlanır. Gerekli sunucu sırları yoksa iş **hazırlık/entegrasyon** statüsünde kalır.

---

## 26.10.2026 — Ana dalın doğrulanmış yayını ve SDP tekrar güvenliği

**Kanıt durumunu yeniden kontrol etme kuralı:** 25.10.2026 tarihli #459 ve açık #128 bilgisi, o tarihteki tarihsel kesittir; güncel durum yerine kullanılamaz. Yeni çalışmada repo HEAD/PR/CI/Release asset durumunun ayrıca doğrulanması esastır.

- **Doğrulanmış merge:** [PR #128](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/pull/128) ile gerçek medya kontrollerinin durum/yaşam döngüsü; [PR #129](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/pull/129) ile kapanmış çağrıya geç SDP yanıtının gönderilmemesi ana dala birleştirildi. `main` SHA: `7ea3e2caa1b2f919d88bc444b24b392ab17bc3c1`.
- **Doğrulanmış APK:** [CI #475](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/actions/runs/40264707545) bütün işleriyle başarılı; [Release #475](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/releases/tag/v1.1.0-signed.475) APK, SHA256 ve imza raporunu içerir. Public yeniden indirme ve hash kontrolü CI yayın işinde başarıyla tamamlandı. Fiziksel Samsung/Xiaomi yerinde güncelleme veya iki yönlü ses/video kabulü **değildir**.
- **Açık sağlamlaştırma:** [PR #130](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/pull/130) doğrulanmış sinyalleşmede aynı SDP OFFER'ın farklı zarf/sinyal UUID'siyle tekrarını ayıklar. Aynı SDP ikinci bir ANSWER veya ikinci teklif kotasını tüketmemeli; gerçekten farklı ICE restart SDP'si yalnız bir kez kabul edilmeli. Sıfır ham SDP/IP logu, yalnız geçici bellekte SHA256 özeti; işlev unit/Robolectric ve Android kabul testleriyle kanıtlanmadan main/release'e alınamaz.
- **Değişmez P0/P1 açıklar:** [#82](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/issues/82) A55 çökme kökü, [#83](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/issues/83) gerçek signing anahtarı dış yedek/geri yükleme, [#113](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/issues/113) kayıpsız SQLCipher, [#114](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/issues/114) Double Ratchet ve [#3](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/issues/3) iki fiziksel cihaz/ENC kabulü. Otomatik veri sıfırlama, PIN zorlaması, ölçülmemiş dB, onaysız ücretli altyapı yasaktır.

---

## 25.10.2026 — ANAYASA ÖNCELİKLİ SÜREKLİ SAĞLAMLAŞTIRMA VE GERİYE DÖNÜK DENETİM (KALICI KURAL)

**Sahip talimatı:** "Devam; her zaman sağlamlaştır, yapılana dön bak, eksikleri tamamla." Yeni veya değişmiş kalite/süreç/güvenlik kuralı varsa **önce bu anayasa düzenlenir**, kaydedilir ve değişikliğin kendi kanıtı sağlanır; **ardından** üretim kodu/CI/Release üzerinde çalışma başlar. Her yeni çalışmada bu bölüm varsayılan ve bağlayıcıdır. Geçmişteki tamamlandı iddiaları en güncel commit, PR, CI, APK ve açık issue ile yeniden çapraz kontrol edilir.

### Zorunlu çalışma döngüsü ve kanıt seviyeleri

1. **Ön denetim:** Güncel `main` SHA, açık PR'lar, mevcut release/tag, çalıştırmalar/başarısız adımlar, issue ve anayasa maddeleri ile eski "tamamlandı" kayıtlarını eşleştir; yanlış/eskimiş durumu açıkça düzelt. Tek bir eski CI sonucunu yeni HEAD'e atfetme.
2. **Anayasa önce:** Yeni talebi/anlaşmayı/mühendislik standardını risk, veri koruma, maliyet, performans ve tamamlama kanıtıyla birlikte bu dosyaya yaz. Anayasa değişikliği repo commit'i olmadan "kaydedildi" deme. Tarihsel kayıtları silip puanları geriye dönük değiştirme; yeni kararların önceliği açık olsun.
3. **Açık analizi ve önceliklendirme:** P0 veri kaybı, kimlik, şifreleme, OTA imzası, güvenli iletişim ve yanlış başarı göstergeleri önce; ardından P1 erişilebilirlik, OEM, performans ve mimari. Düzeltmenin kök nedenini ve aynı sınıftan komşu işlevleri denetle.
4. **Uygula → tersini de test et:** Her kritik düzeltme için başarılı yolun yanında başarısızlık, iptal, eski oturumdan gecikmiş callback, yeniden bağlanma, çift dokunma, izin geri alma, düşük disk/bellek, offline/429 ve geri dönüş senaryolarından uygun olanlara regresyon ekle. Kaynak/iş parçacığı/Room/Keystore/veri yaşam döngüsü kapanışını kanıtla. Sadece yoruma, geçici mock'a veya statik kontrole güvenme.
5. **Katmanlı dayanıklılık:** ANA SERVİS → ALTERNATİF → GERÇEK, TEST EDİLMİŞ FALLBACK → SINIRLI HATA YÖNETİMİ → GÜVENLİ VARSAYILAN → GİZLİLİĞE UYGUN LOG/İZLEME → SMOKE/KABUL. Tek başarısızlık noktası veya sahte başarı oluşturma. Gerçek fallback yoksa özelliğin desteklenmediğini dürüstçe göster.
6. **CI ve sürüm kapısı:** Değiştirilen **en son PR HEAD SHA** için Android unit/Robolectric, güvenlik/protokol, Android Lint, API34 runtime, R8 APK derleme, paket versionCode, signer ve SHA doğrulamaları başarıyla tamamlanmadan `main`e birleştirme; eski ve yeni CI koşularını karıştırma. Başarılı merge sonrası **yeni main SHA** CI ve gerçek GitHub Release APK + SHA + imza asset'ları ile public read-back'i ayrı doğrula. Artifakt üretimini APK yayınlandı diye adlandırma.
7. **Gerçek cihaz kanıtı:** CI, emulator, mock ve statik analiz farklı seviyelerdir. İki farklı üreticide karşılıklı gerçek ses/video, OTA veri koruma, çevresel gürültü azaltımı ve keystore yedek/geri dönüş doğrulaması olmadıkça bu başlıkları tamamlandı ve 9–10/10 ilan etme. Ölçülmemiş dB, gecikme, başarı oranı veya pil kazanımı yazma.
8. **Kapanışta geriye dönük kontrol:** Kendi yeni kodunu tekrar oku; gerekirse başka katmandaki UI davranışı, unit, regresyon ve dokümanı eşleştir; PR diff, mergeability, kod kapsamı, güncel CI ve release read-back'i yeniden kontrol et. Eksik kalanları somut issue/PR bloklayıcı olarak bırak; karar, tarih ve kanıtı anayasaya güncelle.

### Sabit ürün/veri güvenliği değişmezleri

- Kullanıcının `2011.` gizli giriş akışı ve **isteğe bağlı PIN** tercihi aynen korunur. Kullanıcıdan onay almadan zorunlu PIN, kimlik reset, veritabanı boşaltma, sessiz medya izinleri, yeni ücretli servis veya kota aşımı yok.
- Hesap geçmişi, Room v4/gelecek migration, PIN/kimlik, E2EE mesaj/geçmiş, kuyruğu, rehber, medya taslağı ve kullanıcı yedekleri **silinmez**; `fallbackToDestructiveMigration` yasaktır. Kayıpsız SQLCipher geçişi gerçek yedek/rollback/WAL/Keystore/fiziksel kabul bitmeden otomatik açılmaz.
- PIN, E2EE anahtarı, stabil APK imza anahtarı, parola ve hassas görüntü/mesaj/IP/SDP asla repo, issue, log veya halka açık artifakta konmaz. Kalıcı imza anahtarı felaket kurtarması gerçek fiziksel ve **cihaz dışı şifreli** kabul gerektirir.
- E2EE için ileri gizlilik/Double Ratchet bağımsız inceleme ve protokol geriye uyumluluk kabulü olmadan "uygulandı" denmez. Ses/video sadece gerçek capture/transport/karşılıklı RTP kanıtıyla aktif gösterilir; demo ve ölçülmemiş ENC/ANC hiçbir yerde gerçek diye sunulmaz.
- Her yeni Android geliştirmesinde MVVM/MVI, Jetpack Compose, Hilt, Coroutines/Flow, R8/ProGuard, izin/şifreleme, kontrollü hata deneyimi ve gizliliğe duyarlı crash izleme standardı korunur. Güvenlik kritik kod ve release için Android API23+ ve GMS/HMS bağımsızlık regresyonu gözetilir. Crashlytics dahil uzak veri aktarımı kullanıcı onayı/gizlilik değerlendirmesi olmadan etkinleştirilmez.
- Masraf prensibi: yerel/ücretsiz/açık kaynak öncelikli; fiyatlandırılmış TURN, storage, Firebase veya benzeri değişiklikte **açık kullanıcı onayı olmadan** kurulum/harcama yok.
- Görev çalışma döngüsü: araştır → kök neden → düzelt → kodla → build → test → doğrula → merge → release → smoke → kanıt. Yapılamayan adımı somut engel/eksik diye raporla; asenkron bitirme, henüz geçmemiş testin başarı iddiası veya kanıtsız tamamlandı söylemi yok.

### 25.10.2026 güncel kontrol kesiti (sonuçla değiştirilmeli)

- [PR #126](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/pull/126) ve [PR #127](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/pull/127) main'e birleştirildi. Main `d493bb5239053f76e59786955b1e20cbba2d1442` CI #459 başarılı ve `v1.1.0-signed.459` APK asset'ları yayımlı.
- [PR #128](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/pull/128) medya kontrol durumu ve lifecycle sağlamlaştırması üzerinde çalışıyor: **açık**, en son HEAD CI doğrulaması gerekir. Bu bölümün eklenmesi önceki #466 koşusunun HEAD kanıtı olarak kullanılmasına izin vermez.
- [#83](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/issues/83) gerçek signing key restore, [#113](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/issues/113) SQLCipher, [#114](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/issues/114) Double Ratchet ve [#3](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/issues/3) iki cihaz çağrı/ENC kabulü açık; sentetik CI ile kapatılamaz.

---

## 25.10.2026 — LANU 447 bağımsız mühendislik puanlaması, 0–6 puan güvenlik öncelikleri ve ses/video/NS programı

> **Kayıt ilkesi:** Aşağıdaki puanlar kod, GitHub Actions ve açık riskler üzerinden yapılan **öznel teknik ön değerlendirmedir**; sızma testi, cihaz ölçümü, güvenlik sertifikası veya garantili çalışma oranı değildir. `0–6` ifadesi 10 üzerinden **puanı 6,0 veya altında kalan kategoriler** için hedeflenen güçlendirme grubudur. 6,6/6,8/7,0 puanlı ses-video-gürültü başlıkları kullanıcı talebiyle bu gruba **eklenmiştir**. Puanları hedef değere otomatik yükseltmek yasaktır.

**Doğrulama anı:** 25.10.2026, `main` commit `7a481f2a719bcd12682973d4fdd7d6fea542b92a`; son doğrulanmış yayımlı `v1.1.0-signed.447`; CI `40219653126` başarılı. Android 14 emülatör debug/release ve yerel test geçişi, fiziksel iki cihazda gerçek ses/video veya eskiden yeniye veri kayıpsız yükseltmenin kanıtı **değildir**. İmzalı APK'nin yayın sonrası HTTPS/SHA256 read-back'i CI içinde geçmiştir. Puanlama aralığı genel **7,2 / 10**; yalnızca başlangıç karşılaştırması.

### Kalem kalem 24 değerlendirme başlığı

| # | Bileşen | Başlangıç puanı /10 | Temel kanıt ve açık sınır |
|---|---|---:|---|
| 01 | Android mimarisi (MVVM/Hilt/Flow) | 8,0 | Compose/Hilt/Flow var; büyük katman/sınıf sorumlulukları temizlenmeli |
| 02 | Modülerlik ve bakım | 7,0 | Büyük repository/screen sınıfları bölünmeli, contract testleri artırılmalı |
| 03 | Jetpack Compose arayüz | 8,0 | Modern arayüz; çoklu OEM/ekran erişilebilirlik kabulü açık |
| 04 | Hesap makinesi/dönüştürücü | 8,6 | İşlev/testler var; uç BigDecimal matematik #5 açık |
| 05 | Gizli alan/PIN/biyometri | 7,5 | PIN isteğe bağlı kullanıcı kararı **değişmez**; kapalı PIN gizlilik riski açık anlatılır |
| 06 | E2EE mesaj şifrelemesi | 8,0 | ECDH/AES-GCM AAD/kimlik/relay doğrulama var; bağımsız kripto inceleme yok |
| 07 | İleri gizlilik/Double Ratchet | **3,5** | #114 açık; kalıcı ECDH, ratchet ve post-compromise recovery yok |
| 08 | Room/WAL/SHM disk şifrelemesi | **4,5** | #113 açık; standart Room SQLite, SQLCipher yok; eski veri **silinemez** |
| 09 | Supabase/relay yetki ve RLS | 8,0 | İmzalı istek, karşılıklı kişi, RLS protokol/sunucu testleri var |
| 10 | Mesaj teslimi/çevrimdışı | 7,0 | E2EE kuyruk + isteğe bağlı WorkManager; anlık background teslim yok |
| 11 | Gerçek WebRTC ses | 7,0 | Native ICE/DTLS emülatör, mikrofon izni/ön plan kontrolü var; fiziksel ses testleri açık |
| 12 | Gerçek WebRTC görüntü | 6,8 | Kamera2/1 ve renderer var; fiziksel görüntü/çevirme/uzun kullanım testleri açık |
| 13 | AEC/NS/AGC, gürültü kontrolü | 6,6 | WebRTC APM/uyumlu donanım; gürültü dB kazanımı ölçülmedi; kulaklık ANC **iddia edilmez** |
| 14 | Zor NAT/TURN/ağ değişimi | **4,5** | Ücretsiz STUN/ICE restart var; TURN yok; P2P bütün ağlarda garanti edilemez |
| 15 | Fotoğraf/belge/büyük medya | **5,0** | Küçük E2EE içerik var; büyük dosya storage grant/atomic ready/TTL/indirme #85 açık |
| 16 | Şifreli yedekleme/kurtarma | 7,0 | Parolalı kullanıcı yedeği var; fiziksel geri yükleme matrisi eksik |
| 17 | OTA/uygulama içi güncelleme | 8,6 | Sertifika, sürüm, SHA ve şüpheli URL reddi var; fiziksel veri koruma kabulü açık |
| 18 | APK imza anahtarı felaket kurtarma | **4,0** | #83 açık; gerçek PKCS12'nin bağımsız ortamdaki şifreli geri yükleme kanıtı yok |
| 19 | CI/CD, R8, yayın doğrulama | 9,3 | Test/emülatör/sha/signing/live Release read-back; fiziksel OEM yerine geçmez |
| 20 | Çökme/gizlilik odaklı izleme | **6,0** | Yerel sanitizasyon/diagnostic var; izinli üretim crash telemetry bütünlüğü eksik |
| 21 | Samsung/Xiaomi/Huawei OEM kabulü | **4,5** | Eski APK cihaz gözlemleri var; **447** gerçek çağrı, GMS'siz ve yeni OTA matrisi açık |
| 22 | Performans/pil/bellek | **6,0** | Coroutines/R8/cleanup; uzun çağrı, ısı, CPU, pil ve gecikme ölçümü eksik |
| 23 | ADR/dokümantasyon | 6,5 | Anayasa ve test kayıtları kapsamlı; eski demo/açık durumlar tarihsel etiketlenmeli |
| 24 | Lisans/ABI/16KiB uyumluluğu | 8,0 | WebRTC BSD, dört ABI, ARM64 hizası ve JNI-R8 koruma kontrolleri mevcut |

**Test olgunluğu (başarı yüzdesi değil, değerlendirme puanı):** birim/Robolectric 9,3; CI/CD 9,3; Android emülatör kabulü 8,7; fiziksel cihaz/ağ 4,5; gerçek veriyle kurtarma/OTA 5,0. Bir testin geçmesi tüm üretim koşullarının sağlandığı anlamına gelmez.

### 0–6 puanlı alanlar + istenen ses/video/gürültü sağlamlaştırması

| Öncelik | Alan, başlangıç puanı | Güçlendirme mimarisi ve güvenlik kapısı | Tamamlama kanıtı |
|---|---|---|---|
| **P0** | Room 4,5 | SQLCipher üzerinde sürümlü, **kayıpsız**, iki aşamalı/atomik geçiş; WAL checkpoint + veri bütünlüğü; Keystore-sarılı anahtar, düşük alan/süreç öldürme kurtarma; **geri dönüşte eski DB tutulur** | Kopya veritabanında satır/ek/hash karşılaştırma, kill/disk-full/rollback, Samsung/Xiaomi OTA; bunlar yokken **MIGRASYON AÇILMAZ** |
| **P0** | İmza felaket kurtarma 4,0 | İkinci güvenli ortamda gerçek PKCS12 şifreli yedek, test geri yükleme, aynı sertifika SHA256; sırların GitHub/chat/CI loglarına **asla çıkmaması** | Sahip kontrollü bağımsız restore kanıtı; CI'daki sahte fixture testi gerçek kurtarma değildir |
| **P1** | Forward secrecy 3,5 | Bağımsız tehdit modeli, çift ratchet/X3DH veya denetlenmiş muadil, sürüm/old-client birlikte çalışma; yeni kriptoyu el yordamıyla yazma yok | Bağımsız kripto incelemesi, downgrade/replay/rotasyon testleri, kimlik/yedek uyumluluğu |
| **P1** | Zor NAT/TURN 4,5 | Önce ücretsiz STUN, sınırlı ICE restart, bağlantı zaman aşımı ve kullanıcıya dürüst hata; maliyet onaysız TURN yok | Farklı operatör/hücresel/Wi-Fi, simetrik NAT, IPv6 ve 25s kopma cihaz matrisi |
| **P1** | Medya paylaşımı 5,0 | Streaming E2EE, sınırlı boyut/MIME, dosya imzası, TLS, private storage, pending→ready atomiklik, TTL/anahtar/log ayrımı | Şifreli upload/download, sahte URL/bozuk payload, ağ kesme, kota, iki gerçek cihaz |
| **P1** | OEM 4,5 | A55, Xiaomi ve Huawei/GMS'siz: mikrofon, kamera, izin iptali, kulaklık/BT, Doze, Android 23–36, 16KiB, OTA | Modelli fiziksel test/ekran kaydı/ölçüm; emülatör ≠ OEM kabulü |
| **P1** | Crash/diagnostic 6,0 | Gizlilik korumalı yerel sabit hata kodları; uzaktan toplama varsa açık tercih ve **sır/SDP/IP/mesaj yok**; Crashlytics seçeneği veri politikasıyla | Sentetik crash/redaction testleri; üretim telemetry gizlilik incelemesi |
| **P1** | Performans 6,0 | Görüşme sırasında bounded queue, RTC kaynak yaşam döngüsü, kamera HAL fallback, ağ/termal/bellek değişimi, ≤2–3 sn UI tepki **hedefi** | Ölçülmüş p50/p95, pil/ısınma/bellek, 30–60 dk görüşme, bağlantı ağ geçişi |
| **P1** | WebRTC ses 7,0 / video 6,8 | Kullanıcı kabulünden sonra capture; camera2→camera1 gerçek fallback; foreground kapanışta tüm medya/renderer/EGL bırakılır; ICE/DTLS-SRTP gerçek paket kanıtı | Android 14 debug+R8 release; iki OEM'de karşılıklı ses/video, mute/flip/rota/hata |
| **P1** | AEC/NS/AGC 6,6 | WebRTC JavaAudioDeviceModule/AudioProcessing, cihazda varsa donanım AEC/NS; yoksa WebRTC yazılım yolu; iki farklı AEC/NS motorunu üst üste bağlama yok | Sessiz/gürültülü A/B ses kaydı, MOS/PESQ veya nesnel referans, yankı ve ses anlaşılırlığı; **kanıtsız dB/ANC vaadi yasak** |

**Evrensel kritik zincir:** ana yol → doğrulanmış alternatif → gerçek fallback → sınırlandırılmış hata → güvenli varsayılan → hassas verisiz tanılama → birim+emülatör smoke → fiziksel kabul. Her değişiklikte 429/rate-limit, kötü ağ, cancel/timeout, anahtar/sertifika uyuşmazlığı, bozuk dosya, yetkisiz karşı taraf, kamera HAL ve batarya/bellek senaryoları ayrı kapıdır. **Sahte başarı, placeholder fallback ve ücretli otomatik altyapı yasaktır.**

**Paket güncelleme sınırı:** kullanıcı PIN tercihi ve gizli giriş davranışı **değişmez**; mevcut kimlik, E2EE mesaj, Room veri, hesap geçmişi veya imza silinmez. `v1.1.0-signed.447` referans geri dönüş sürümüdür; sonrakinin yayımlandığı yalnız GitHub CI başarı + Release asset + anonim HTTPS/SHA read-back ile söylenir. Fiziksel iki telefon ve veri kayıpsız OTA açık kaldıkça **tam 9–10 puan veya tüm cihazlarda garanti** iddia edilmez.

### Eylem / statü protokolü

- **DOĞRULANDI:** 447 üzerinde başarılı CI; repo listesi, kullanıcı izinleri, Android 14 gerçek native WebRTC emülatör testi.
- **YAPILACAK:** emülatör ve birim testleri eklenirken kamera fallback, AEC/NS capability kararları, güvenlik regression kapıları güçlendirilir; PR kendi CI tamamlanmadan `main`'e girmez.
- **AYRI PROJE/KABUL:** SQLCipher kayıpsız migration, Double Ratchet protokol yükseltmesi, gerçek anahtar restore, ücretsiz TURN erişilebilirlik değerlendirmesi, büyük medya E2EE depolama ve fiziksel cihaz kabulü. Yapılmamış özellikler etkinmiş gibi açıklanamaz.
- **Puan güncelleme kuralı:** yalnız somut commit, test sonucu, üretim ve fiziksel kanıt ile yeniden puanlanır; salt plan/yorum puan artırmaz.

---



## 25.10.2026 — Gerçek ön plan WebRTC araması uygulaması

- **Uygulama:** Sohbet üstünde sesli/görüntülü arama düğmeleri; gerçek native WebRTC kamera/mikrofon, karşılıklı kabul, sustur/kamera kapat/çevir, ahize/hoparlör/kulaklık yönlendirme ve güvenli tam ekran görüşme. Eski demo yolu gerçek arama kanıtı sayılmaz.
- **Sinyalleşme:** ECDSA kimlik doğrulaması, karşılıklı kişiler, E2EE ve çağrıya özel AAD; mesaj tablosundan ayrı 90 saniyelik RLS korumalı sinyaller. Gelen davetler mevcut ön plan eşitlemesine eklenir; boşta ek polling yok. Etkin görüşmede 2 saniye kontrol, gönderim bütçesi ve gerçek ICE restart vardır.
- **Medya:** Sabit `io.github.webrtc-sdk:android:150.7871.04`, BSD-3-Clause; dört ABI, minSdk21 bağımlılığı ile uygulama API23 desteği korunur. ARM64 ELF yükleme hizaları 16KiB doğrulandı. R8 JNI kuralları ve debug/release gerçek DTLS paket testi eklendi. AEC/NS/AGC motoru kullanılır; sahte dB/kalite/bağlandı değeri yok. CONNECTED için transport + çift yönlü gerçek ses paket sayacı gerekir.
- **Güvenli varsayılan:** Açık kabul ve Android izni olmadan medya açılmaz; özel alandan çıkışta kaynaklar kapanır. PIN isteğe bağlı; kimlik, Room v4, geçmiş, E2EE kuyruk ve APK imzası korunur. Yeni ücretli hizmet açılmaz.
- **Kabul sınırı:** Ücretsiz STUN/P2P vardır, TURN yoktur; katı NAT/ağlarda hata mümkündür. İki güncel uygulama güvenli alanda açık olmalıdır; kapalı uygulamaya gelen arama yoktur. Samsung/Xiaomi iki fiziksel cihaz ses/video, gürültü ve OTA kabulü ayrıca açıktır. #3 bütün bu kabul kapıları tamamlanmadan kapanmaz.
- **Kanıt:** Yerel relay/Postgres 54/54 geçti. Android CI, ana dal yayını ve canlı smoke sonuçları doğrulandıkça işlem günlüğüne eklenir; bu uygulama kaydı tek başına yayın/cihaz kabulü değildir. Ayrıntı: `docs/FOREGROUND_WEBRTC_CALLS_TR.md`.

## 25.10.2026 — Premium teslimat merkezi ve GMS bağımsız arka plan kontrolü

- **Kullanıcı talebi:** Önemli açıkları profesyonelce ele al; premium kullanım ve dayanıklılık ekle. Ücretli plan, yeni backend ve zorunlu PIN açılmaz.
- **Uygulandı:** Güvenli alanın üst çubuğunda **Teslimat merkezi**; MVVM/Flow tabanlı durum, gerçek son başarılı sunucu kontrol zamanı, açık kullanıcı tercihiyle arka plan kontrolü, Wi-Fi/kotasız ağ seçimi, manuel kontrol, uygulama açıkken mevcut senkronizasyona çalışan alternatif.
- **Güvenli varsayılan:** Arka plan kontrolü **kapalı**, ağ tercihi **kotasız** başlar. İş WorkManager 2.11.2 ile API23+ JobScheduler üzerinden, en az 15 dakika aralıkla ve düşük pil korumasıyla planlanır. GMS/FCM/HMS SDK veya yeni ücretli altyapı eklenmez. Periyodik kontrol anlık push veya zorla durdurulmuş uygulamaya teslim garantisi değildir.
- **Kimlik/veri koruma:** Worker yeni kimlik oluşturamaz; eksik/bozuk kimlik veya tercih güvenli durur. Aynı repository/Room transaction, E2EE kuyruğu ve sınırlı gönderim yolu kullanılır; arka planda aktif sohbet/okundu onayı gönderilmez. İşe mesaj, kişi kodu, PIN veya anahtar eklenmez. Room şeması, PIN tercihi ve OTA sertifikası değişmez; SQLCipher **henüz etkin değil**.
- **Dayanıklılık:** Açık uygulama senkronizasyonu ana yol; kullanıcı açarsa Android periyodik iş alternatif; planlayıcı hatasında ön planda yenileme fallback. Ağ/429 için en fazla iki artan aralıklı retry, 90 saniye işlem bütçesi ve iptal aktarımı vardır. Yalnız gerçek sunucu kontrolü başarı zamanını günceller; kullanıcı kapatınca işler iptal edilir. Daha katı ağ tercihi eski işin ağ koşulunu da yeniden kontrol eder.
- **Test kapsamı:** `DeliveryRunTest`, `BackgroundDeliveryAndroidTest`, `BackgroundIdentityPreservationTest`; iptal/ebeveyn timeout, kota, disk/pref hatası, kimlik üretmeme, WorkManager constructor, opt-out, gerçek worker adaptörü ve veri saklama. Başarı yalnız ilgili GitHub CI + ana dal APK yayın kapısından sonra raporlanır; **Samsung/Xiaomi fiziksel arka plan/Doze/OTA kabulü ayrıca açık**.
- **Önceki açık dal:** PR #118 birim/Robolectric/lint + API34 debug/release kapıları yeşilken `637f0fa3a75cd9c5afeee36593372324bbefa096` ile birleştirildi. Bu ses yönlendirme hazırlığı gerçek WebRTC ses/video aktarımı değildir; #3 açık.
- Ayrıntı: [Teslimat ve arka plan kabul planı](docs/PREMIUM_DELIVERY_CENTER_TR.md). #83 gerçek signing kurtarma, #113 SQLCipher ve #82 eski çökme kökü kapanmış sayılmaz.


## 25.10.2026 — Gerçek sesli/görüntülü görüşme ve ENC/AEC/AGC talebi

**Kullanıcı hedefi:** Samsung ve Xiaomi dahil farklı Android telefonlarında gerçek iki yönlü **WebRTC sesli/görüntülü arama**; konuşma mikrofonundan motor/fan/rüzgâr vb. ortam sesini mümkün olduğunca azaltan **ENC/Noise Suppression**, konuşma yankısını bastıran **AEC**, dengeli ses için **AGC**. **Kulaklık ANC** cihaz/kulaklık donanımıdır; uygulamada tüm telefonlarda açılacağı vaat edilemez. İnsan konuşması ve tüm ortam sesleri kesinlikle sıfırlanır iddiası yasak.

- **DOĞRULANDI / eksik:** 387 çağrı ekranı ve `CallManager` yalnız **demo/simülasyon**; ağ/dB/netlik değerleri temsili, gerçek medya veya WebRTC PeerConnection yok. `AudioDspProcessor` içindeki basit PCM gate, RNNoise veya ölçülmüş canlı ENC değildir.
- **AŞAMA 1 uygulanıyor:** [Ses/videolu arama + ENC yol haritası](docs/REAL_WEBRTC_VOICE_VIDEO_ENC_ANC_TR.md); gerçek audio-session ID alabilen Android NS/AEC/AGC adaptörü, izin/HAL hata fallback'ı, kaynak temizliği, birim testi, yanlış 'aktif ANC / RNNoise / 34 dB' göstergelerinin dürüst demo olarak düzeltilmesi. **Bu tek başına gerçek çağrı veya gerçek ENC testi değildir**; main CI ve APK olmadan başarı ilan edilmez.
- **AŞAMA 2–4 AÇIK:** Lisans/ABI/R8 doğrulamalı native WebRTC medya motoru + güvenli şifreli offer/answer/ICE sinyalleşmesi + STUN/kimlikli TURN + gerçek kamera/mikrofon izinleri + iki fiziksel üretici üzerinde ses/video, ağ geçişi, gürültü karşılaştırmalı kabul. Bağlantı yalnız transport kanıtıyla **CONNECTED** olur; sahte 'ağ iyileşti' iddiası yasak. Kullanıcı onayı olmadan ücretli TURN ve altyapı yok.
- **Mevcut PIN değişmez:** `2011.` gizli alanında PIN tamamen **isteğe bağlı** kalır. Çağrı entegrasyonu mesaj, geçmiş, anahtar, veri ve kurulu APK'yı silmez.
- **Durum:** [#3](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/issues/3) gerçek ses/görüntü ve ENC kabulü bitene kadar **AÇIK**.


## 25.10.2026 — APK 387 Samsung + Xiaomi kullanıcı cihaz teyidi

**Kullanıcı beyanı:** “387 tamam samsung ve xiaomi de de.” Yayın `v1.1.0-signed.387` için Samsung ve Xiaomi olmak üzere **iki Android üreticisinde olumlu fiziksel cihaz gözlemi** vardır. Bu, cihaz/model veya otomatik CI çıkarımı değil, **kullanıcının doğrudan teyididir**.

- [CI #387](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/actions/runs/40171770068) başarılı: Android birim ve API34 emülatör kabulü, APK yayın ve doğrulama.
- [APK 387 Release](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/releases/tag/v1.1.0-signed.387) yayımlı; SHA256 ve imza raporu var.
- **Kanıt sınırı:** Samsung/Xiaomi tam model, Android sürümü, eski→yeni OTA zinciri, PIN açık/kapalı senaryoları, 2 cihazda E2EE gönder/al, Room/veri karşılaştırması ve kapsamlı stres testleri ayrıca tek tek bildirilmedi. Bu başlıkların doğrulandığı varsayılmaz.
- Önceki A55/369 kararlılık gözlemi ayrı kabul kaydıdır; yeni beyanı otomatik olarak A55 387 ayrıntılı teste dönüştürmeyin.
- **İsteğe bağlı PIN kararı değişmez.** SQLCipher gerçek migrasyonu henüz yapılmadı; #113, imza anahtarının cihaz dışı gerçek kurtarma #83 ve eski çökme kök nedeni #82 açık tutulur. “Bütün Android cihazlarda sorunsuz” iddiası yapılmaz.



## 25.10.2026 — Claude bağımsız inceleme bulguları, kodla çapraz doğrulama ve değişmez PIN kararı

**KULLANICI KARARI / DEĞİŞMEZ KURAL (25.10.2026):** Gizli alana `2011.` üzerinden girildiğinde **PIN isteğe bağlı kalacaktır**. PIN olmayan kurulumun otomatik zorunlu PIN kurulumuna geçirilmesi, mevcut kullanıcının PIN'siz giriş tercihinin değiştirilmesi, arka kapı niteliğinde ayrı bir master PIN eklenmesi **yasaktır**. Var olan PIN'i kapatmak isteyen kullanıcı için mevcut PIN ile onay zorunluluğu, kalıcı deneme kilidi ve PIN yeniden açma seçeneği korunur. PIN kapatıldığında giriş yöntemini bilen kişilerin özel alana erişebileceği güvenlik uyarısı **açıkça görünür**; bu bilinçli ödünleşim kullanıcıya aittir. Bilinmeyen/bozuk tercih, PIN korumasını sessizce kaldırmaz. PIN/kimlik/Room verileri hiçbir güncellemede silinmez. `app/src/main/java/com/example/core/repository/Repositories.kt`, `SecurityViewModel.kt`, `SettingsScreen.kt` ve `OptionalPrivatePinTest.kt` mevcut uyumluluk dayanaklarıdır.

**Kanıt düzeyi:** Claude değerlendirmesi tek başına kod denetimi değildir. Aşağıdaki kayıtlar 25.10.2026 tarihinde güncel `main` Android kaynakları, release/CI durumu ve önceki issue kayıtlarıyla çapraz doğrulandı. Puanlama yalnız yönetsel ön değerlendirmedir; "uygulandı", "CI başarılı" ve "gerçek cihazda kabul" birbirinden farklıdır.

| Kod | Önem | Doğrulanan eksik / risk | Güncel kanıt ve tamamlanma şartı | Durum |
| --- | --- | --- | --- | --- |
| CR-01 | **P0** | **Room veritabanı (ve SQLite WAL/SHM) SQLCipher ile tam disk-üstü şifreli değil.** E2EE mesaj gövdesi ve Keystore/EncryptedSharedPreferences kimliği ayrı korunur; fakat tablo metaverisi, kullanıcı tercihleri, hesaplama geçmişi ve olası geçmiş/veri izi aynı bütün-disk şifrelemesine sahip değildir. | `AppDatabase.kt` standart `Room.databaseBuilder(...,"lanu_calculator_db")` çağırıyor, SQLCipher factory yok. Önce kayıpsız migration tasarımı, SQLite/WAL checkpoint ve Keystore-sarılı rastgele DB anahtarı; kopyada dönüşüm/geri okuma, süreç kesilmesi ve disk-dolu senaryoları; *eski DB asla silinmeden* atomik geçiş ve geri dönüş. SQLCipher kitaplık güncelliği/API23+/16KiB sayfa uyumu ayrıca doğrulanacak. | **AÇIK — kodlama ve fiziksel kabul yapılmadı** |
| CR-02 | **P0** | **Stable APK imzalama anahtarının fiziksel cihaz dışı kurtarma kanıtı yok.** | #83: Şifreli yedek/geri yükleme **test betikleri mevcut ve CI'da testli**, ancak gerçek PKCS12+parola kaynaklarının ikinci güvenilir ortamda izole geri yüklenmesi ve sertifika SHA-256 eşitliği kullanıcı gözetiminde henüz doğrulanmadı. Sırlar/loglar/GitHub/sohbet/CDN'ye ASLA konmaz. | **AÇIK — gerçek kurtarma kanıtı eksik** |
| CR-03 | **P1** | **Forward secrecy / post-compromise security yok.** | `MessagingIdentity.kt` içindeki `PeerMessageCrypto`, uzun ömürlü statik ECDH + SHA-256 + rastgele AES-GCM IV kullanır; her mesajda ratchet key güncellenmez. ECDSA imza ve E2EE var ama Double Ratchet/X3DH yok. Tehdit modeli, protokol sürümü, çoklu cihaz/eskilerle uyumluluk, anahtar yedeği, olası AGPL lisans analizi, güvenlik regresyonu ve kriptografik inceleme zorunlu. | **AÇIK** |
| CR-04 | **P1** | **Arka planda güvenilir mesaj teslimi/push ve üretici/GMS alternatifleri** doğrulanmadı. | `CommunicationViewModel` aktif sahipler için senkronizasyon yapar; genel Android/Huawei/HMS/GMS'siz cihazlar için garantili uyandırma ve arka plan teslimi kanıtı yok. FCM tek başına tüm Android için kapsayıcı değil. Mesaj içeriği ve anahtar push yüküne girmez. | **AÇIK** |
| CR-05 | **P1** | **Gerçek iki cihaz arasında sesli/görüntülü WebRTC arama yok**; demo çağrı gerçek hizmet diye sunulmamalı. | `CallScreen`, `ExampleRobolectricTest` demo etiketleri; signaling, STUN/TURN, mikrofon/kamera izinleri ve karşılıklı bağlantı ayrı güvenli geliştirme, maliyet ve kabul aşaması. | **AÇIK** |
| CR-06 | **P1** | **Büyük medya gerçek upload/download henüz çalışmıyor.** | Mevcut `InlineMediaCodec` üzerinden **şifreli 18KB fotoğraf/PDF/TXT** gönderim yolu var. #85/PR #108-#110: 40MiB kadar yerel şifreleme/bütçe, private storage rezervasyon ve sunucu SHA256 doğrulama **hazırlığı** var, fakat canlı storage grant, atomik `pending→ready`, alıcıya güvenli indirme, TTL temizliği ve 2 cihaz kabulü tamamlanmadı. | **AÇIK — küçük dosyayı yok sayma** |
| CR-07 | **P1** | **Bağımsız kriptografi/protokol incelemesi, KVKK kapsamı ve veri yaşam döngüsü kanıtı eksik.** | ECDH KDF ve AAD, kimlik güven sabitleme, anahtar rotasyonu, metadata, kullanıcı onayı, silme/retention, yedek ve logların bağımsız denetimi; yalnız birim testi veya RLS bu inceleme değildir. | **AÇIK** |
| CR-08 | **P1** | **Eski `CryptoUtils.decrypt` deterministik IV/MD5-türevli legacy okuma yolu** kaldırılmadan önce göç kanıtı gerekiyor. | Yeni `encrypt` rastgele 12-byte GCM IV üretir; legacy decrypt uyumluluğu mevcut. Mevcut kayıtların sayımı, versiyonlu şifreleme zarfı, doğrulanmış yeniden şifreleme ve geriye dönük açma testi olmadan legacy kod silinemez. | **AÇIK — migration tasarlanmalı** |
| CR-09 | **P1** | **Üretici/Android sürümü/genel cihaz performansı ve çökme kabulü eksik.** | #82 kök neden bulunmadı; kullanıcı A55 APK 369 kurulduktan sonra yeniden çökme görmediğini bildirdi. CI #380/API34 emülatör ve API23/28/34/35 üzerindeki belirli Robolectric testleri olumlu; bu düzeylerin hepsinde tam uçtan uca senaryo kanıtı yok; fiziksel Xiaomi, Huawei/HMS, GMS'siz/Google Pixel, Android eski/yeni, arka plan/bellek/insets/OTA testleri YAPILMADI. Gizlilik onaylı Crashlytics veya alternatif uzaktan raporlama üretim kabulü eksik. | **AÇIK** |
| CR-10 | **P2** | **README/proje mimarisi ADR'si ve sürdürülebilir dokümantasyon** yetersiz. | Repo kökünde README.md 25.10 kontrolünde yok, GitHub açıklaması yalnız "Hesap Makinesi", anayasa yaklaşık 576 satırdı. Ürünün relay kullanmasıyla ilk "telefondan telefona / Supabase'siz" hedefi arasındaki değişiklik **onaylanmış varsayılmamalı**; bağımlılık, güven modeli, anonim/polling teslim ve işletme maliyeti ADR'de netleştirilmeli. | **AÇIK** |

### Claude raporunda artık geçerli olmayan / yanlış durumlar

- **Hilt yok**: GÜNCELDE YANLIŞ. `LanuApplication` @HiltAndroidApp; `ApplicationModule` ve singleton `AppContainer` Hilt ile yönetiliyor. DI göçü için eski TODO kapatılmalı; sınır testleri sürmeli.
- **R8 üretimde yok**: GÜNCELDE YANLIŞ. `app/build.gradle.kts` release için `isMinifyEnabled=true`, `isShrinkResources=true`; CI `assembleRelease`, API34 connectedReleaseAndroidTest, signer+SHA256 kamu indirme kapısı çalışıyor.
- **Çoklu cihaz testi yalnız A55**: TAM doğru değil; API34 gerçek Android emülatörü, API23/29/35 Robolectric çalışıyor, **fiziksel çok üretici kabulü** eksik. Cihaz uyumluluğu tek telefon modeli için tasarlanamaz.
- **Medya hiç yok**: YANLIŞ; 18KB uçtan uca şifreli küçük ekler çalışıyor; **büyük medya** kapalı.
- **PIN kapalı özelliği kaldırılmalı**: KULLANICI KARARINA AYKIRI. Opsiyonel PIN korunur, risk şeffaf gösterilir.

### Kanıtlı son durum (25.10.2026)

- Son yeşil `main` CI **#380**, `v1.1.0-signed.380` Release: APK, SHA256 ve signer doğrudan indirilebilir; fiziksel cihazda #380 OTA kabulü henüz yok.
- CI #379 ile PR #111 genel Android dosya sağlayıcısının tekrarlı 0-byte duraksamasında sonsuz döngü güvenli kapatıldı; daha önceki test altyapısı ZIP kaynaklı hataları düzeltildikten sonra birleştirildi. Dış sağlayıcının bir `read()` çağrısında sonsuza kadar bloklamasına özel timeout hâlâ araştırılmalı.
- Supabase relay yalnız kontrol edilen servis üzerinden mesaj iletir, doğrudan DB anon/authenticated okuma/yazma izni yok. Canlı large-media uçtan uca aktarım henüz açılmadı.
- Şu metindeki eksikler **"yapıldı" olarak işaretlenmeyecek**; ilgili PR CI/yayın ve gerçek cihaz kabul kanıtı gelince ayrı kayıtla güncellenecek.

### 25.10.2026 — Bağımlılığa göre yürütülecek geliştirme sırası

1. **P0 — Mevcut veri/OTA/opsiyonel PIN'i koru.** 369'un A55 cihazındaki olumlu geri bildirimi referans; yeni sürümde PIN açık ve PIN kapalı senaryoları veri silmeden test edilir. Geri dönüşü olmayan migration, kimlik sıfırlama, otomatik PIN zorunluluğu YASAK.
2. **P0 — #83 gerçek signing key kurtarma kabulü.** Mevcut CI'da testli şifreli yedek scripti kullanılarak sahibin ikinci güvenli ortamda izole restore ve public sertifika eşleştirmesi; üretim anahtarına dokunulmaz.
3. **P0 — CR-01 SQLCipher'a kayıpsız, kademeli geçiş.** Önce sadece dosya/boş alan/journal/WAL/DB kimlik analizli READ-ONLY preflight+test; sonra Android API23/29/34/35 + kesinti/geri dönüş özellikli izole migration; fiziksel veri/PIN/mesaj kabulü olmadan ana veritabanını değiştirme.
4. **P1 — #82/CR-09 platform kararlılık ve gizlilik destekli hata tanılama.** Gerçek crash kanıtı yokken rastgele kod değiştirme; isteğe bağlı anonim tanılama; üretici, eski Android, arka plan, düşük bellek ve OTA testleri.
5. **P1 — CR-03/CR-07 ileri gizlilik protokolü ve bağımsız kripto kontrolü.** Lisans/versiyon/uyumluluk kararı, E2EE kimlik sürekliliği, anahtar yenileme, metadata, güvenlik testleri.
6. **P1 — #85 kontrollü büyük medya.** Private Storage grant, sunucuda doğrulama, atomik ready, kısa süreli alıcı indirme, retry/TTL ve iki cihaz testleri; kullanıcının onayı olmadan ödeme/ücretli plan yok.
7. **P1 — CR-04 arka plan teslimi.** GMS bağımsız fallback; push üzerinde sadece içeriksiz uyandırma, eski/yeni cihazların pil ve süreç kısıtları.
8. **P1/P2 — CR-05 gerçek arama, hesap makinesi kenar durumları, UX/erişilebilirlik ve performans.** Gerçek WebRTC sinyal/TURN tasarımı ayrı kabul ve maliyet kapısından geçer.
9. **P2 — CR-08 eski şifreleme formatı güvenli göçü, CR-10 README+ADR, bağımsız KVKK metni.** Şifreli eski kayıtlar kanıtlı biçimde açılmadan legacy decrypt yolu kaldırılmaz.

**Geliştirme sırası bağımlılık bazlıdır:** README/ADR gibi düşük riskli işler paralel yapılabilir; kritik geçişler birbiriyle karıştırılmaz. Her adımda önce kanıt, sonra PR/CI, merge ve gerçek yayın, gerekirse fiziksel kabul.


## 24.10.2026 — Güncel kullanıcı kabulü ve dayanıklılık çalışması

- Kullanıcı açıkça **"Pin tamamen isteğe bağlı çalışıyor"** diye teyit etti. `2011.` sonrası isteğe bağlı PIN gereksinimi **kullanıcı kabul edildi**; önceki "PIN tercihinin çalıştığı teyit edilmedi" notları bu yeni beyanla güncellenir. Yeni beyan belirli bir APK numarasına veya tek tek belgelenmemiş ek senaryolara atfedilmez.
- [PR #101](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/pull/101) birleştirildi; PR CI #345 birim/Robolectric, lint ve API34 Room kabulü başarılı. Gelen mesajlar önce alınır; giden kuyruk sınırlı partiler ve kalıcı retry zamanı kullanır. Kalıcı hatalı mesajlar silinmeden ayrılır ve kullanıcı tekrar deneyebilir. Metin taslakları kimlikle şifreli saklanır; gönderim transaction'ı yalnız gerçekten kuyruğa alınan taslağı temizler. Android relay yanıtında decompressed boyut/UTF-8/call-timeout sınırları var. Yardım metinleri küçük fotoğraf/PDF/TXT desteğini doğru açıklar.
- Tam şifreli yedek geliştirmesi: kimlik + yedi Room tablosu + küçük inline ekler + şifreli taslak; 32 MiB arşiv; parolalı AES-GCM/PBKDF2; kaydedilen dosyada read-back SHA; çakışmada üzerine yazmayan ve mevcut PIN'i koruyan transaction restore. Ayrıntı `docs/FULL_DATA_BACKUP_TR.md`. Bu paketin CI/yayın ve A55/ikinci telefon kabulü ayrı doğrulanır.
- Açık fiziksel/harici kapılar: #82 eski çökmenin gerçek cihaz kök nedeni; #83 gerçek signing key'in cihaz dışı şifreli yedeği ve geri yükleme; yeni tam yedeğin iki telefonda kabulü; #85 büyük medya; bağımsız kriptografi incelemesi. Bunlar kod/betik testiyle tamamlandı yazılmaz.


Bu belge mevcut ANAYASA.md, docs/ANAYASA.md, AGENTS.md ve proje belgelerini **silmez veya geçersiz kılmaz**; çelişkide daha güvenli/katı kural uygulanır, karar ADR'de belgelenir.

## Değişmez çalışma kuralları
1. Her proje için dört faz: **Faz 1 Envanter/Araştırma; Faz 2 Kök neden/Düzeltme; Faz 3 Uygulama/Build/Test; Faz 4 Doğrulama/Yayın/Üretim smoke**. Her fazda Araştır → Düzelt → Uygula → Test/Doğrula → Yayınla kapısı tamamlanır; başarısızsa yayın yapılmaz.
2. Kritik her zincir: **Ana servis → Alternatif → Çalışan fallback → Hata yönetimi → Güvenli varsayılan → Log/izleme → Otomatik smoke testi**. AI, üçüncü taraf API, veritabanı, ödeme/kota, dosya, görsel, kimlik doğrulama, frontend/backend, APK/backend ve üretim özel kapsamdır.
3. Ücretsiz/açık kaynak/yerel çözüm önceliklidir. **Kullanıcı açık onayı olmadan ücretli hizmet veya harcama yoktur.**
4. Girdiler ve yetkiler hem istemci hem sunucuda doğrulanır; sırlar asla repoya/loga yazılmaz; kişisel veri asgari tutulur. Yetkisiz veri toplama, hukuka aykırı entegrasyon veya Android kısıtını dolanma yapılmaz.
5. Modül/menü/buton/ekran/izin/veri/export ve offline senaryoları işlev testiyle kanıtlanır. Görünür ama işlevsiz buton yayınlanamaz. Eksik izin ve offline durumunda anlaşılır uyarı zorunludur.
6. CI kalite kapısı: bağımlılık kilidi → lint/typecheck → birim/integrasyon → build → smoke. Hata halinde fail-closed; test başarısızken başarılı release denmez.
7. Android projesinde yayınlanabilir her sürümün **Releases/Yayınlar** bölümünde APK ve mümkünse SHA-256 vardır; URL ve kurulabilirlik doğrulanır. Workflow artifact tek başına yeterli değildir.
8. Prod değişiklikleri küçük, geri alınabilir ve diff ile incelenebilir olmalı; main korunmalı, rollback/yedek ve migration planı tutulmalı. Gizli bilgileri veya eski bulguları silerek 'temiz' rapor hazırlanmaz.
9. **Ürün tam hazır olana kadar** Araştır → Kök neden → Düzelt → Uygula → Build → Test → Doğrula → Deploy → Production smoke → Kanıtla döngüsü geçerli. Çalışma gerçekleştirilmeden 'tamamlandı' yazılmaz.
10. Her hata kaydı: kimlik, etkilenen dosya/modül, etki, önem(P0/P1/P2/P3), kanıt/link, önerilen düzeltme, test, durum, son doğrulama commit'i içermeli.

## Durum etiketleri
- **DOĞRULANDI:** Günlük, test veya kod satırıyla kanıtlanan sorun.
- **RİSK / TEST GEREK:** Kod/iş akışından doğan ama cihaz/üretim testi isteyen konu.
- **BAŞARILI CI:** Yalnızca ilgili iş akışı başarılı; bütün ürünün kusursuzluğu anlamına gelmez.
- **P0:** yayın engelleyici; **P1:** kritik özellik/ güvenilirlik; **P2:** test/iyileştirme açığı; **P3:** dokümantasyon/kozmetik.

## Düzeltilmesi gerekenler ve takip
Bulguları aşağıdaki bölümde kanıt ve tamamlanma kriteriyle izleyin. Tamamlama için kaynak kod değişikliği, başarılı CI, çalışan ürün smoke ve (Android için) Release APK gerekir.

---
# LANU-Hesap-Makinesi — DÜZELTİLMESİ GEREKENLER

**Tarama tarihi:** 15.10.2026  
**Depo:** https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi  
**Öncelik:** P2  
**Durum:** KISMEN DOĞRULANDI / TEST GEREK

## Kanıtlanan durum / ön bulgu
Son iki Android CI başarılı; Releases APK ve SHA içeriyor. Mevcut anayasa klasörü bulunuyor.

## İlgili düzeltme / kabul kriteri
Hesap doğruluğu, güvenli saklama, iletişim modülü izinleri ve cihaz testlerini yeniden doğrula.

## Kontrol adımları
- [ ] README, ağaç, iş akışı, bağımlılık ve gerekiyorsa Android manifest bütünlüğü
- [ ] Kod içinde her görünür özelliğin olay işleyicisi ve veri akışı
- [ ] CI tüm kontroller yeşil, başarısız log kök nedeni giderilmiş
- [ ] Alternatif/fallback, offline, boş/bozuk veri, iptal/izin ret ve güvenlik kontrolleri
- [ ] Android ise gerçek cihaz testi, sağ/sol kulaklık varsa ayrı test, Release APK bağlantı+SHA kontrolü
- [ ] Üretim smoke kanıtı, sürüm/commit ve tarih güncellemesi

## Faz takibi
- **Faz 1 — Araştır:** Repo dosya ağacı ve son workflow/release kayıtları incelendi; tüm fonksiyonların cihazda çalışması henüz kanıtlanmadı.
- **Faz 2 — Düzelt:** Yukarıdaki konu için kod ve konfigürasyonda en küçük güvenli değişiklik gerekli.
- **Faz 3 — Test:** Birim + entegrasyon + cihaz / gerçek servis testi gerekir.
- **Faz 4 — Yayın:** Başarılı ve indirilebilir çıktı ile production smoke yapılmadan kapatılmaz.

---

## Ek Anayasa — Kanıtlı İnceleme ve Düzeltme Takibi (15.10.2026)

- **Kanıtlı araştırma:** [4 bulguluk kaynak-kod/CI/Release incelemesi](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/blob/main/docs/KANITLI_TEKNIK_DENETIM_2026-10-15.md).
- **Özel proje kuralı:** Gerçek ağ bağlantısı olmadan CONNECTED gösterilemez; simülasyon açıkça demo olur; yüksek hassasiyetin Double'a düşmesi test edilir.
- **Kanıt sınıfları:** Doğrulanmış hata / kaynak kodu ile doğrulanmış eksik / hipotez / gerçek cihaz testi gerekli. Bu statüler birbirine karıştırılamaz.
- **Gerçek işlev zorunluluğu:** Her görünen menü, form, buton, kayıt, harita, oynatıcı ve servis işlev testiyle kanıtlanır; sahte bağlantı ve sahte başarı yasaktır.
- **Veri güvenliği:** Kullanıcı bilgisi uydurulamaz, sızdırılamaz veya sessizce kaybedilemez. Kimlik, senkronizasyon ve import/export yetki testinden geçer.
- **CI/Release kapısı:** Başarısız testte ürün tamamlanmış sayılmaz. Android'de SHA-256 ve indirilebilir Release APK; kritik modülde gerçek cihaz smoke zorunludur.
- **Ücret onayı:** Ücretli API/kota/servis tüketimi kullanıcı onayı olmadan genişletilmez; gerçek alternatif ve fallback test edilir.
- **Kanıtlı kapanış:** Bulgu ID, önem, dosya+satır, CI kanıtı, kök neden, düzeltme commit'i, kabul testi, sürüm, geri dönüş planı ile izlenir.
- **Faz durumu:** Bu ekleme yalnız analiz/dokümantasyon; kaynak kod düzeltmesi veya üretim doğrulaması değildir.


## 20.10.2026 — P0/P1

**Kaynak:** PR #37 ve 20.10.2026 tarihli main CI #39411770120 (başarılı). Release: v1.0.0-debug.165.

### Tamamlananlar
- PIN PBKDF2 rastgele tuz ve yalnız doğru giriş sonrası eski PIN geçişi; güvenli hata davranışı.
- Finansal BigDecimal hesaplamaları ve regresyon testleri.
- Biyometri ayarları Keystore hatasında güvensiz diske yazılmaz; güvenlik olayları yalnız geçici belleğe gider.
- Main CI: unit/Robolectric, lint, APK, sertifika, SHA-256 ve yayın başarılı.

### Yapılacaklar
- **P0:** Sabit APK imzası ve iki sürümle cihaz yükseltme; Room v1-v3 gerçek şemaları, kayıpsız migration; Room şifrelemesi/yedek-kurtarma; iki cihaz mesaj güvenilirliği; OTA testi.
- **P1:** Bağımsız kripto incelemesi; Hilt/MVVM; Crashlytics gizliliği; finansal referans testleri; Samsung A55 ve ikinci cihaz; KVKK, performans ve Compose erişilebilirlik.

### Geçici kalite puanları
| Ölçüt | Ağırlık | Mevcut | Hedef |
|---|---:|---:|---:|
| Güvenlik | 30% | 68 | 95 |
| Veri bütünlüğü | 25% | 45 | 95 |
| Mesajlaşma | 15% | 50 | 90 |
| CI ve APK | 15% | 85 | 95 |
| UX ve performans | 15% | 55 | 85 |
| **Toplam** | **100%** | **60/100** | **93/100** |

Puanlar planlama amaçlı ön değerlendirmedir, bağımsız sertifikasyon değildir. Hedef puan ancak test kanıtıyla geçerli olur.

### Geçiş kapısı
Önce Room v4 şema ihracı ve CI şema kontratı. Tarihî migration uydurulmaz; veritabanı sürümü değiştirilmez; veri silinmez. Kod ancak test, lint, build ve yayın önkoşulları doğrulandıktan sonra birleştirilir. Geçici debug imzalı APK, cihaz üstü güvenli güncelleme garantisi vermez.


### 20.10.2026 — P0 kriptografi sınır durumları (PR incelemesi)
- **Bulgu CRYPTO-01:** `CryptoUtils.encrypt("")` sonucu AES-GCM formatında tam 28 bayttır (12 IV + 16 tag). Eski decrypt kodu yalnız 28 bayttan büyük çerçeveyi yeni format saydığından boş metin doğru anahtarla bile açılamıyordu.
- **Düzeltme:** Modern çerçevenin minimum uzunluğu `>= 28` olarak belirlenir. Var olan eski deterministik IV okuma yolu yalnız geriye uyumluluk içindir; yeni şifrelemede kullanılmaz.
- **Kabul testleri:** boş değer roundtrip, tag/IV oynama reddi, kesilmiş çerçeve reddi, eski verinin şifre çözülebilmesi.
- **Durum:** Değişiklik ve testler ayrı geliştirme dalına işlendi; yeni PR CI tamamen geçmeden BAŞARILI/TAMAMLANDI etiketlenemez.


### 20.10.2026 — P0-05 gelen mesaj kaydında atomiklik

- **Bulgu MSG-ATOMIC-01 (kaynak kod incelemesi):** `CommunicationRepository.synchronize()` mesajı ve güncellemeyi ayrı Room DAO çağrılarıyla yazıyordu. Mesaj kaydından sonra, okunmamış sayacını güncellemeden veya replay revizyonunu saklamadan oluşan kesinti; bir sonraki tekrarda ilk kayıt sanılmadığı için okunmamış sayısının eksik kalmasına neden olabilir.
- **Düzeltme:** Tek gelen mesaj olayında sohbet oluşturma, doğrulanmış şifreli mesaj, okunmamış sayaç ve olay revizyonu `AppDatabase.withTransaction` ile atomik yürütülür. Sunucu teslim/okundu onayı yalnız işlem tamamlandıktan sonra gönderilir; tamper edilen içerik yine reddedilir.
- **Regresyon:** Sahte bir DAO ile okunmamış sayacı adımında hata enjekte et; mesajın, sohbetin ve revizyonun geri alındığını ve sunucu onayı verilmediğini kanıtla. Ardından aynı olayı yeniden işle; okunmamış 1 olsun, tekrar oynatma 2 yapmasın.
- **Kapsam ve sınır:** Room v4 şeması ve kullanıcı verileri değişmez. Kod, test ve CI doğrulaması bitmeden başarı ilan edilmez. Gerçek iki cihaz/çevrimdışı sunucu senaryoları hâlâ P0-05 kapısıdır.


### 20.10.2026 — P0-02/P0-03 Room yedi tablo veri koruma regresyonu

- **Kapsam:** Güncel Room v4 kapatıp yeniden açıldığında PIN doğrulama özeti, mesaj revizyonu, kullanıcı kimliği, rehber/engelleme, sohbet/okunmamış sayacı, bekleyen şifreli mesaj, finansal hesap geçmişi ve çağrı kaydı korunmalıdır.
- **Kanıt yöntemi:** Kalıcı test SQLite dosyasına yedi tablonun her birinde örnek kayıt yaz; bağlantıyı tamamen kapat, yeniden aç; alanları ve `PENDING` teslim kuyruğunu doğrula.
- **Durum:** Otomatik test geliştirme dalında. Şema sürümü değiştirilmedi. Eski v1-v3 migration, bozuk disk ve gerçek cihaz güncelleme kanıtı yerine geçmez. PR CI yeşil olmadan tamamlandı sayılmaz.


### 20.10.2026 — P0-03: eski Supabase anahtarlarının düz metin Room kaydı

- **Bulgu CRED-01 / etki:** `SecurityRepository.saveSupabaseCredentials()` bağlantı anahtarını `app_settings.supabase_key` sütununda şifresiz saklıyordu. Güncel Room v4 dosyası tümüyle şifreli olmadığı için bu yerel gizlilik riskiydi.
- **Düzeltme:** Yeni URL ve anahtar yalnız Keystore destekli `EncryptedSharedPreferences` kaydına yazılır. Uygulama başlangıcında eski `supabase_url` / `supabase_key` Room kayıtları, şifreli kopyanın dayanıklı biçimde yazıldığı ve yeniden okunduğu doğrulanmadan silinmez. Korunan kaydın eksik olduğu durumda veya Keystore/commit hatasında işlem durur; eski veriler korunur; düz metne dönüş yapılmaz.
- **Test kapıları:** Şifreli yeni kayıt, doğru eski kayıt geçişi, commit hatasında geri alınabilirlik, bozuk/eksik korumalı kayıt, önceki şifreli kaydın eski satırdan öncelikli tutulması; CI test, lint, APK, imza/SHA256.
- **Sınırlar:** Testlerde enjekte edilen düz `SharedPreferences` yalnız sahte depodur; üretimde Keystore şifreli depolama kullanılır. Bu geliştirme tüm Room veritabanını şifrelemez; eski veriler taşınamıyorsa kullanıcıdan habersiz silinmez. PR CI ve gerçek cihaz kontrolü tamamlanmadan üretim güvenliği iddiası yok.


### 20.10.2026 — P0-07 Supabase bağlantı anahtarlarının güvenli saklanması

- **Bulgu CRED-01:** Eski `SecurityRepository.saveSupabaseCredentials`, `supabase_key` değerini şifrelenmemiş Room `app_settings` tablosuna yazıyordu. Sunucu tarafı gerçek yüksek ayrıcalıklı service-role anahtarı Android istemciye hiç konmamalıdır.
- **Kod düzeltmesi:** Yeni bağlantı bilgilerinin kalıcı saklandığı konum Android Keystore'a bağlı `EncryptedSharedPreferences` olur; şifreli depo kullanılamazsa fail-closed. Başlangıçta eski URL/anahtar çiftinin güvenli depoya kopyalanması denenir; yeni kayıt kalıcı biçimde yazılıp geri okunmadıkça eski veriler silinmez. Kısmi/kırık güvenli kayıtlar düzeltildi sayılmaz.
- **Kabul:** Yeni gizli değer Room'a düşmez; eski değerler başarılı geçişte temizlenir; şifreli yazma hatasında eski kaynak korunur; bozuk/kısmi kayıt reddedilir. Android Unit/Robolectric, lint, APK, sertifika/SHA256 yeşil olmadan merge yok.
- **Sınırlama:** Room dosyasının tamamı hâlâ şifreli değildir; eski dosya sayfalarında WAL/serbest alan izleri kalmış olabilir. Bu adım geçmiş düz metin kalıntılarını veya önceki sızıntıları geriye dönük yok ettiğini iddia etmez. Gerçek cihaz, eski sürüm yükseltme ve bütünsel veri şifreleme P0 olmaya devam eder.
- **Durum:** Geliştirme dalında; CI doğrulama bekleniyor. Puan bağımsız doğrulama olmadan otomatik yükseltilmez.


### 20.10.2026 — P1 güvenlik günlüğü kurtarma (AUDIT-01)

- **Bulgu:** Şifreli tercih deposu okunabiliyor ancak yazma işlemi başarısız oluyorsa olaylar RAM'e aktarılıyordu; `getLoggedEvents` diskteki eski olayları döndürüp RAM'deki yeni güvenlik olaylarını gizliyordu.
- **Düzeltme:** Görüntüleme sırasında şifreli kalıcı ve geçici RAM olaylarını birlikte göster. Sonraki başarılı korumalı yazımda RAM'deki olayları şifreli depoya aktar, yalnız başarılı `commit` sonrasında RAM kuyruğunu temizle.
- **Kabul:** Şifreli commit başarısızken RAM olayları görünür ama diske düz metinle yazılmaz; depo toparlanınca olaylar şifreli kayıt halinde yeniden açılışta görünür; en fazla 50 kayıt tutulur. Android CI, lint, APK ve imza doğrulanmadan birleştirme yok.
- **Sınır:** Çalışma sırasında cihaz yeniden başlatılırsa yalnız RAM'deki kurtarılamamış olaylar kaybolabilir. Tamamen yeniden başlayan süreçten RAM kurtarma vaadi verilmez.


### 20.10.2026 — P0-08 Kimlik anahtar çifti bütünlüğü (ID-KEY-01)

- **Bulgu:** Şifreli kimlik dosyası okunurken kullanıcı kodu ve açık anahtar denetleniyordu; özel anahtarın bu açık anahtar çiftine gerçekten ait olduğu kanıtlanmıyordu. Bozuk veya tutarsız kimlik okunmuş gibi görünüp ağ istek imzası/iletişim arızasına yol açabilir.
- **Düzeltme:** `UserCodes.isConsistentIdentity` kod + public ID + özel anahtarla ECDSA challenge imzasını aynı anda doğrular. `EncryptedMessagingIdentityStore.read/save` uyuşmazlıkta **fail-closed**: eski kaydı silmeden hata, yeni bozuk kaydı yazmayı reddetme.
- **Kabul:** Geçerli kimlik roundtrip; özel anahtarı başka hesaba ait/bozuk kaydın okunmasının reddi; yeni tutarsız kaydın önceki geçerli kimliğin üzerine yazılamaması; Room v4 şema ve tüm Android CI kapıları yeşil.
- **Açık:** Uyuşmazlık varsa otomatik anahtar sıfırlamak yasak. Kullanıcı, varsa önceden oluşturduğu parolalı kimlik yedeği üzerinden bilinçli kurtarma yapabilir; gerçek cihaz/anahtar kaybı kurtarma hâlâ açık P0.
- **Durum:** PR CI ve APK kanıtı bekleniyor; genel hedef puan bağımsız test kanıtı olmadan yükseltilmez.


### 20.10.2026 — P0-09 Kimlik yedeklenebilirlik kapısı (BACKUP-01)

- **Bulgu:** `IdentityBackup.export` hatalı eşleştirilmiş özel ve açık anahtarla bile parolalı yedek oluşturabiliyordu. Böyle bir yedek açılmaya çalışıldığında geçersiz sayılır; kullanıcı yedeğe güvenerek hesabını riske atabilir.
- **Düzeltme:** Yedek şifrelenmeden önce açık/özel anahtar ve kullanıcı kodu matematiksel doğrulanır. Geçersiz kimliğin yedeği üretilmez; import işlemi de aynı ortak doğrulayıcıyı kullanır.
- **Kabul:** Geçerli kimlik şifreli yedek/parola/geri yükleme eşitliği; farklı özel anahtar, bozuk özel anahtar, yanlış kod veya ID için yedek oluşturma reddi; unit + lint + APK imza/SHA256 CI başarılı.
- **Sınır:** Geçerli kimlik yedeği yalnız kimlik anahtarlarını içerir; tam sohbet/rehber/finans verisi taşımaz. Geçersiz mevcut kimlik otomatik sıfırlanmaz. Fiziksel cihaz restore ve tam veri kurtarma ayrı P0'dır.
- **Durum:** Kod/test GitHub geliştirme dalında; CI sonuçlanmadan birleştirildi/başarılı ilan etme.


### 20.10.2026 — Paralel P0 paketi ve 90+ üretim hedefi
**Birleştirilen ve PR CI testleri başarılı gelişmeler:**
- [PR #45](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/pull/45) — geçersiz private/public kimlik anahtar çiftinden kullanılamaz yedek oluşturmayı reddetme.
- [PR #47](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/pull/47) — ardışık yanlış PIN gruplarında 30 saniye / 2 dakika / 10 dakika / 1 saat / 12 saat kilitleme; restart sonrası kademe korunur.
- [PR #46](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/pull/46) — şifreli kimlik kaybolduğunda E2EE geçmişini rastgele yeni kimlikle ilişkilendirmeme; uygun gerçek kimlik yedeğiyle geri yükleme; eski telefon numarası verisi geçişini koruma.
- [PR #49](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/pull/49) — sohbet silme ve temizlemede tüm yerel mesajları replay tombstone'ları ile atomik silme ve hatada rollback. İlk PR #48 merge çakışması nedeniyle #49 ile güvenle yeniden uygulandı.

**Tartılı kalite / kanıt temelli ön değerlendirme:**

| Kategori | Ağırlık | Önce | Şimdi | Hedef |
|---|---:|---:|---:|---:|
| Güvenlik/PIN/kimlik | 30% | 71 | 77 | 95 |
| Yerel veri koruma | 25% | 45 | 53 | 95 |
| Mesajlaşma | 15% | 50 | 56 | 90 |
| CI/APK/yayın | 15% | 85 | 85 | 95 |
| UX/performans | 15% | 55 | 55 | 85 |
| **Toplam** | **100%** | **61/100** | **66/100** | **93/100** |

Bu puan bir sertifika değildir; yerinde ve bağımsız kanıtlar tamamlanmadan 90+ ilan edilmez. Bu turda +5 puan testli gelişmeye karşılık gelmektedir.

**Yayın engelleyici açıklar (P0):**
1. Kalıcı imzalı APK keystore ve beş güvenli Actions secret, iki ardışık aynı fingerprint'li build, gerçek cihazda silmeden yükseltme.
2. Gerçek v1-v3 Room şema/fixture ile kayıpsız 1→4 / 2→4 / 3→4 migration; uydurma migration ve destructive fallback yasak.
3. Tam Room dosyası şifrelemesi, WAL/serbest alan koruması, anahtar kaybı ve tam veri yedek/restore testi.
4. Samsung A55 + farklı Android üreticisinden fiziksel cihazlarda mesaj, offline, read/ack, PIN/kimlik kurtarma ve OTA regresyonu.
5. Sunucu retention/TTL/KVKK, kriptografi bağımsız denetim; Hilt/Compose erişilebilirlik, gizlilik kontrollü Crashlytics ve performans.

**Önemli:** PR CI başarılı olması production/cihaz onayı değildir. Son APK sürümü ancak main workflow Release işi tamamlanıp APK asset indirilebilirliği doğrulanınca ilan edilir.


### 20.10.2026 — P0 PIN kalıcı deneme kilidinde atomiklik (PIN-TX-01)
- **Bulgu:** PIN kilit kademe sayacı `pin_lockout_strikes` ile bitiş süresi `pin_attempt_state` ayrı Room işlemleriyle yazılıyordu; iki yazma arasında I/O hatası/çökme tutarsız durum bırakabilirdi.
- **Düzeltme:** `SettingsDao.persistPinGuard` iki satırı tek `@Transaction` altında commit eder; artan kilit ve başarılı PIN / biyometri sıfırlama yolları aynı atomik işlemden geçer.
- **Test:** 4 yanlış girişten sonra 5. denemede `pin_lockout_strikes` INSERT hatası tetiklenir; iki satır da eski halinde kalır; hata kaldırılıp yeniden denenince 30 saniyelik kilit ve strike 1 oluşur. Başarılı biyometride her ikisi sıfırlanır.
- **Durum:** GitHub PR + CI bağımsız testleri bitmeden üretim/başarı etiketi yok. Room v4 şeması sabit.


### 20.10.2026 — P0 Kimlik kayıt kalıcılığının geri okunarak doğrulanması (ID-PERSIST-01)
- Mevcut Keystore korumalı kimlik `save`, şifreli `commit=true` ise geri okunabilirliği kontrol etmeden kaydı başarılı sayıyordu. Kaydedilemeyen/okunamayan kimlik, kullanıcıya kurtarma tamamlanmış gibi gösterilememeli.
- Şifreli `identity` verisi başarıyla commit edildikten hemen sonra tekrar okunup kodlanan içerikle birebir karşılaştırılır. Uyuşmazlıkta işlem fail-closed; eski kayıtlar otomatik temizlenmez veya yeni kimlik üretilmez.
- Android Robolectric: `commit=false` olduğunda önceki kimlik korunur; veri yazılsa bile getString başarısızsa yedekleme/restore yanlış başarılı bildirilmez. Testte sahte preferences üretimde kullanılmaz.
- Kapsam: kimlik depolama dayanıklılığı, **tam Room veri şifrelemesi veya fiziksel cihaz Keystore kurtarma garantisi değil**.
- Durum: PR + CI test sonucu olmadan tamamlandı sayılmaz.


### 20.10.2026 — P0 OTA imza raporu belirsizliği (OTA-META-01)
- Önceki `parseSigner` aynı imza alanının birden fazla satırda tanımlanmasını `toMap` ile son satır lehine kabul ediyordu. Bozuk veya ek tanımlı metadata da atlanıyordu.
- İzin verilen alanlar `apk`, `signing_mode`, `certificate_sha256` ve isteğe bağlı `upgrade_verified_on_device`; her alan en fazla bir kez bulunur. Yanlış/tekrar alan, fazladan eşitlik, belirsiz boşluk ve 2048 karakterden büyük rapor güvenli biçimde reddedilir.
- İlk CI kapıları: çoklu fingerprint, iki imza modu, beklenmeyen alan, bozuk satır, çok büyük rapor için test; mevcut gerçek CI signer.txt formatını koruma.
- Sertifika pin kontrolü hâlâ gerçek kurulu sertifika ile yapılır. Kalıcı imzalı iki APK aynı sertifika ile üretilemiyorsa OTA güvenli yerinde yükseltme üretim kapısı kapanmaz.



### 20.10.2026 — P0 tekil mesaj silme güvenliği (MSG-DELETE-01)
- **Bulgu:** `hideMessageLocally` ve `deleteMessage` için `lanu_hidden_<id>` tombstone kaydı ve `messages` satır silme ayrı işlemlerdi; gönderilmiş mesajda sunucudan silme onayı alındıktan sonra yerel tombstone yoktu. Gecikmiş relay olayları silinen içeriği tekrar yazabilir, yarım DB işleminde gizlilik bozulabilirdi.
- **Düzeltme:** `syncMutex` altında tek `Room.withTransaction` içinde tombstone ve mesaj silme. `PENDING/WAITING/SENDING` mesajlar otomatik zamanlayıcıda korunur; kullanıcı açıkça iptal ederse kalıcı yerel tombstone ile kuyruktan çıkarılır. Uzaktaki silme başarısızsa yerel veri erken silinmez.
- **Kabul:** tekil zaman aşımı/silme tombstone, pendings korunma, SQLite silme hata enjeksiyonunda tüm durumun geri alınması, uzaktaki 503 hatasında yerel kopyanın korunması.
- **Sınır:** Bu yerel silme garantisi, sunucudaki kayıtların KVKK/TTL uyumu veya SQLite WAL'daki eski byte izleri için silme sertifikası değildir.



### 20.10.2026 — P0 E2EE mesaj boyutu sözleşmesi (MSG-SIZE-01)
- **Bulgu:** Sunucu şifreli mesajı en fazla 50.000 karakter kabul ederken Android `PeerMessageCrypto` şifresiz/şifreli boyutu sınırlandırmadan Base64 decode ve AES işlemleri yapabiliyordu. Bu durum kaynak tüketimi ve istemci/sunucu sözleşme uyuşmazlığı riskiydi.
- **Düzeltme:** UTF-8 metnin en fazla 37.467 bayt olması; gelen `lanu2:` çerçevesinin 44–50.000 karakter ve yalnız URL-güvenli Base64 karakterlerinden oluşması decode öncesinde doğrulanır. 12 bayt IV ve 16 bayt GCM etiketi korunur.
- **Test:** sınırdaki mesaj, Türkçe/Unicode, boş mesaj, aşırı büyük çerçeve ve bozuk Base64 reddi; CI yeşil olmadan kapanmış sayılmaz.
- **Sınır:** Tam SQLCipher/veri tabanı şifrelemesi, kullanıcı cihazında kötü niyetli relaya karşı kapsamlı güvenlik denetimi ve iki cihaz testleri ayrı P0.

### 20.10.2026 — P1 hassas bilimsel hesap makinesi belleği (CALC-MEM-01)
- **Bulgu:** Bilimsel hesap makinesi `highPrecisionMemory` için BigDecimal kullanmasına rağmen `CalculatorRepository` M+, M-, MS değerlerini Double'a indiriyordu; 0,1 + 0,2 gibi işlemler bellek API'sinde yuvarlama hatası oluşturuyordu.
- **Düzeltme:** Repo içinde `exactMemoryValue` BigDecimal tek aritmetik kaynak; eski Double `memoryValue` salt geriye dönük UI gösterimi. ViewModel BigDecimal değerleri dönüştürmeden aktarır. Eski Double overload'ları korunur.
- **Test:** 0.1+0.2=0.3, çok basamaklı sayı, legacy Double API ve temizlemenin tutarlılığı; CI ve UI kabul bekliyor.


### 20.10.2026 — P0 RELAY-BODY-01 istek boyutu sınırının sunucuda akış halinde uygulanması
- **Bulgu:** Edge endpoint önce tüm HTTP gövdesini `request.text()` ile belleğe alıyor, **ancak sonrasında** 100.000 karakter sınırını denetliyordu. Yetkisiz/kimliği doğrulanmamış devasa yükler gereksiz bellek kullanabilirdi.
- **Düzeltme:** Gelen veriyi Web ReadableStream parçaları halinde oku, toplam UTF-8 byte sayısını 100.000 ile sınırla; Content-Length üst sınırını okuma öncesi denetle, eksik/yanlış Content-Length olsa bile aktüel byte sınırını uygula. Limit aşımında HTTP 413, bozuk UTF-8/başlıkta 400 ve stream iptali.
- **Test:** 100.000 bayt tam sınırı ve 100.001 reddi, chunked/eksik Content-Length, geçersiz başlık, çok baytlı UTF-8, bozuk UTF-8, normal JSON; Node sunucu testlerinde otomatik.
- **Açık:** Üretim Supabase Edge Function yayınına ilişkin canlı endpoint kanıtı yoksa production düzeltmesi olarak ilan edilmez. Dış güvenlik değerlendirmesi, sunucu TTL ve gerçek iki cihazlı senaryolar ayrı P0.

### 20.10.2026 — P0 PIN başarılı girişte atomik sıfırlama (PIN-TX-02)
- **Bulgu:** Yanlış PIN kilitleme kademesi önceki PR ile transaction olmuştu ancak doğru PIN `pin_attempt_state` ve `pin_lockout_strikes` kayıtlarını ayrı yazıyordu. İkinci yazma hatasında sahte veya tutarsız sıfırlanma oluşabilir.
- **Düzeltme:** Başarılı PIN kontrolü, biyometriyle aynı `SettingsDao.persistPinGuard(0,0,0)` transaction yolunu kullanır; yalnız başarılı commit sonrasında UI kilidi açılır.
- **Kanıt kapısı:** SQLite trigger ile ikinci satırda hata: ilk satır rollback, kullanıcı oturumu kilitli kalır; hata kalkınca doğru PIN guardı birlikte sıfırlar; 5 yeni yanlış deneme ilk 30s kademesidir.
- **Sınır:** PIN gerçek cihaz donanım güvenliğine bağlanmış değildir, Room şifreli değildir; tarihî migration ve stable APK imzası P0.

### 20.10.2026 — P0 sunucu yanıtı bellek sınırı (RELAY-RESP-01)
- **Bulgu:** Edge Function istek gövdesinde streaming boyut sınırı olmasına rağmen, PostgREST yanıtları `response.text()` ile kısıt olmaksızın belleğe alınıyordu. Çok büyük `sync`/olay yanıtı DoS ve kaynak tüketimi riski yaratır.
- **Düzeltme:** `RestStore.query` PostgREST gövdesini akış sırasında (en fazla 16 MiB, dekompresyon sonrası) denetleyerek okur; Content-Length yalanı, UTF-8 bozulması veya sınır aşımı güvenli 503 hatasıyla reddedilir. Ham PostgREST hata/verisi sızdırılmaz.
- **Test:** gerçek JSON, büyük Content-Length, chunked aşımda stream cancel, geçersiz UTF-8, PostgREST 403 hata redaksiyonu ve `RestStore` doğrudan sınır testi.
- **Açık:** İki farklı fiziksel cihazla ağ/latency, üretim sunucu gözlemi, saklama/TTL ve genel oran sınırları ayrı kabul kapısıdır.

### 20.10.2026 — P0 silinmiş sohbetin sunucu olayıyla yeniden oluşmasının önlenmesi (CONV-REPLAY-01)
- **Bulgu:** Sohbet silerken mevcut mesajların ID tombstone'ları vardı; ancak o anda cihazda olmayan eski uzaktan gelen mesajlar gelecekte `synchronize` sırasında sohbeti ve kayıtları yeniden oluşturabiliyordu.
- **Çözüm:** `lanu_hidden_conv_<id>` kalıcı Room gizlilik işareti sohbet silme transaction'ında saklanır. Gelen olaylarda sohbet işareti kontrol edilip mesaj/sohbet depolanmaz ve yanlış okundu bildirimi gönderilmez; teslim olayı revizyonu kaydedilip ACK alabilir.
- **Kurtarma/UX:** Kullanıcı `createConversation` ile bilerek tekrar sohbet başlatınca işaret atomik temizlenir. `deleteConversation` aynı `syncMutex` altında senkronizasyonla seri çalışır.
- **Test:** Daha önce cihaza gelmemiş eski E2EE olayıyla yeniden oluşmama, açıkça yeniden başlatınca yeni mesaj alabilme, silme hatasında sohbet işareti rollback.
- **Sınır:** SQLite WAL/serbest alan eski byte izleri, sunucu retention ve diğer cihazlarda sohbet silme bu yerel işaretin kapsamında değildir.

### 21.10.2026 — P1 Sunucu veri yaşam döngüsü ve güvenli temizlik (SERVER-RETENTION-01)
- **Bulgu:** Sunucuda `lanu_events` ACK edilmiş eski olayları süresiz tutuyordu; pasif hesapların `lanu_nonces` kayıtları da kendi başına silinmiyordu. Artan kayıtların KVKK / kapasite riskleri vardı.
- **Değişiklik:** Yeni yalnız yetkili `service_role` kullanıcıların çalıştırabildiği `lanu_prune_retained_data(batch)` SQL işlevi. 30 günü geçen *ACK edilmiş* olaylar, 1 günü geçen nonce'lar ve ancak **silinmiş**, 90 günü aşmış, hiçbir bekleyen olay referansı kalmamış mesajlar en fazla 5000'lik partilerle temizlenebilir. Hiçbir canlı mesaj veya ACK bekleyen olay silinmez.
- **Testler:** PGlite Postgres üzerinde ACK/pending ayrımı, sona ermiş silinen mesaj, canlı/yenileri koruma, batch sınırı, yetkisiz rol reddi.
- **Dağıtım şartı:** Migrasyonun gerçek Supabase'e uygulanması ve imtiyazlı bakım zamanlaması yapılmadan canlı sunucuda TTL etkin ilan edilemez. 21.10.2026 günü imtiyazlı cron zamanlaması kuruldu, kontrollü ilk bakım çağrısı doğrulandı. Periyodik görev ilk çalışması ve operasyonel yedek doğrulaması ayrıca gereklidir.


### 21.10.2026 — Çoklu P0/P1 gerçek yayın ve canlı sunucu durum belgesi

**GitHub'da testlerle birleştirilen gelişmeler:**
- PR #70: PostgREST cevaplarının açılmış veri boyutunu 16 MiB ile sınırlandırma; Node güvenlik testleri ve Android CI başarılı.
- PR #71: Sunucudan henüz gelmemiş mesajlar nedeniyle silinen sohbetin otomatik yeniden yaratılmasını engelleme, kullanıcı isteğiyle yeniden açma; Android/Robolectric CI başarılı.
- PR #73: E2EE olaylarını kaybetmeden sunucu veri yaşam döngüsünü yöneten partili ve yalnız servis rolüyle çağrılabilir bakım fonksiyonu; Postgres/PGlite ve Android CI başarılı. PR #72 aynı çözümün eski dala dayanan, çakışan taslağı olarak kapatıldı.

**Gerçek bağlı LANU Supabase kanıtları:**
- Edge Function `lanu-relay` **v3 / ACTIVE**; mevcut GitHub main `core.mjs` ile birebir eşit olarak yeniden okundu. `verify_jwt=false` korunur; protokol uygulama içinde kullanıcı ECDSA imzasını doğrular.
- SQL migration `20261021160108_safe_relay_retention_20261021` canlı veritabanına uygulandı. `public.lanu_prune_retained_data(integer)` var ve sadece `service_role` EXECUTE hakkına sahip; `anon`/`authenticated` yok. Üç retention indeksi var.
- Beş LANU tablosunda RLS açık, `anon` ve `authenticated` doğrudan SELECT alamıyor. Güvenlik danışmanının "RLS no policy" INFO durumu bu yalnız sunucu modeliyle uyumludur.
- Canlı DB'de **13 onay bekleyen olay** korunmuş. `pg_cron` v1.6.4 ve `lanu-safe-retention-daily` 03:15 UTC (06:15 Türkiye) aktiftir. İlk kontrollü çağrıda **0 olay, 34 eski nonce, 0 mesaj** temizlendi; 13 onaysız olay ve 2 mesaj korundu.
- Fiziksel cihazla canlı iki uç testi yapılamadı; yerel HTTP doğrudan smoke ağ çözümleme kısıtı nedeniyle doğrulanamadı. Edge ACTIVE/source eşitliği ve CI, canlı son-kullanıcı trafiği kanıtı değildir.

**Kalem kalem proje iç puanı (üretim kapıları kapalıyken):**
| Kategori | Ağırlık | Önce | Şimdi | Hedef |
|---|---:|---:|---:|---:|
| Güvenlik / kimlik | 30% | 79 | 80 | 95 |
| Veri / kurtarma | 25% | 57 | 60 | 95 |
| E2EE / mesajlaşma | 15% | 59 | 64 | 90 |
| CI / yayın | 15% | 86 | 87 | 95 |
| Kullanıcı deneyimi | 15% | 55 | 55 | 85 |
| **Ağırlıklı genel** | **100%** | **68/100** | **70/100** | **93/100** |

Bu puanlar matematiksel sertifikasyon değil, testli geliştirme ve açık üretim risklerine dayalı tutarlı yönetim değerlendirmesidir.

**Açık P0/P1 (tamamlandı denemez):**
- P0: Hesap sahibi denetiminde kalıcı imza anahtarı + 2 aynı sertifikalı APK + gerçek cihazda `adb install -r` ve sohbet/PIN korunması.
- P0: Gerçek tarihî Room v1/v2/v3 dosya/şema örnekleriyle kayıpsız migration testleri; eski şemalar bilinmeden uydurulamaz.
- P0: Tam şifreli Room, eski düz metin WAL/serbest alan izleri, gerçek yedek/geri yükleme ve güç kesintisi dayanıklılığı.
- P0: Samsung A55, Xiaomi, Huawei üzerinde iki farklı kullanıcı/cihazla E2EE, çevrim dışı, OTA ve erişilebilirlik testleri; dış kriptografi gözden geçirmesi.
- P1: Supabase cron kuruldu ve ilk manuel çalışma doğrulandı; **ilk bağımsız otomatik cron koşusunun hata kaydı, operasyonel yedek/restore denemesi ve KVKK incelemesi** hâlâ kabul bekliyor. SMS/telefon verisi, düz metin message body ve istemcide service-role key asla kullanılmaz.
- P1: Hilt ve Crashlytics ile premium arayüz/performans; uygun gerçek Firebase yapılandırması olmadan etkin ilan edilmez.


#### 21.10.2026 — Tarihî Room kök neden kanıtı (SCHEMA-HISTORY-01)
- GitHub `app/src/main/java/com/example/core/database/AppDatabase.kt` dosyasının mevcut depo geçmişindeki tüm kayıtları kontrol edildi: ilk takip edilen commit `685d0775136c` (16.09.2026) **version = 4 / exportSchema = false**; `125fabf6b28e` (19.10.2026) **version = 4**; `9e46f4d2cf2f` (20.10.2026) **version = 4 / exportSchema = true**.
- Yedi tablo tüm izlenebilen sürümlerde korunmuş; 16.09 sürümü eski `fallbackToDestructiveMigration()` kullanmış, 19.10'da kaldırılmış.
- **Kanıt sınırı:** Bu depo yolu geçmişinde doğrulanabilir v1-v3 şeması bulunmadığından gerçek v1-v3 migration implementasyonu mümkün değil. Dışarıda yayımlanmış ayrı APK/db örnekleri varsa bilinmiyor. Gerçekte yayımlanan v4 APK'den güncel v4'e veri koruma/uyumluluk testleri öncelikli; eski şemaları uydurmak yasaktır.


### 21.10.2026 — Canlı Supabase pg_cron ve migration kaynak eşitliği
- **Gerçek migration envanteri:** `20261019073855_persistent_user_code_messaging`, `20261021160108_safe_relay_retention_20261021`, `20261021160752_enable_pgcron_for_safe_lanu_retention_20261021`, `20261021160807_schedule_lanu_safe_retention_daily_20261021`. GitHub `supabase/migrations/` dosya adları birebir bu gerçek kayıtlarla eşitlendi.
- **Cron:** `pg_cron` v1.6.4 kuruldu. `lanu-safe-retention-daily` (jobid=1) **active=true**, `postgres` tarafından günlük `15 3 * * *` zamanında `public.lanu_prune_retained_data(500)` çalıştırmak üzere kaydedildi.
- **İlk kontrollü üretim çağrısı:** `{"events":0,"nonces":34,"deleted_messages":0}`; sonradan kimlik=4, mesaj=2, event=14 ve **onaysız=13** korundu, nonce=0. Bu test işlevin gerçekte çalıştığını kanıtlar; cron'un ilk otomatik çalışması için ayrı `cron.job_run_details` kanıtı gereklidir.
- **CI koruması:** `scripts/test_verify_supabase_migrations.py` canlı migration numaralarını, tekil sürümleri, eski ACK olayları + silinen/referanssız mesajlar kuralını ve günlük limitli cron'ı her PR'da doğrular; PGlite SQL davranış testleri ayrıca vardır.
- **Rollback:** Yetkili bakım görevi `select cron.unschedule('lanu-safe-retention-daily');` ile durdurulabilir. Silinmiş eski nonce/event byte'ları bu işlemle geri alınamaz; veri yedeği/operasyon politikası hâlâ gereklidir.


### 21.10.2026 — P0 E2EE kişi güven anahtarlarının atomik kaydı (PEER-TRUST-TX-01)
- **Bulgu:** `savePeer` doğrulanmış kişinin public key / ID / code alias ve sohbet eşlemesini birden çok Room yazımıyla kaydediyordu. İkinci yazmada SQLite hatası/çökme kısmi güven haritası bırakabiliyor, kişi doğrulanmış görünürken gönderme/çözme tutarlılığını bozabiliyordu.
- **Düzeltme:** `ensureIdentity` ile hesap ID'si işlem öncesinde alınır; `database.withTransaction` içinde eski public-key continuity kontrolü, bütün `lanu_peer_*` alias'ları, kişinin güncellenmesi ve sohbet kaydı beraber commit edilir ya da beraber rollback olur.
- **Regresyon:** Alias kaydına hata enjekte et ve tüm map alanlarının temiz kaldığını doğrula; contact DAO hata verdiğinde eski kişi kaydı ve bütün haritalar korunmalı. Hata giderildikten sonraki `synchronize` başarılı olmalı.
- **Sınır:** İki fiziksel cihaz E2EE trafiği, server retention ve Signal tarzı forward secrecy ayrı P0. Room v4 şema değişmedi.


### 21.10.2026 — Proje koordinatörlüğü: E2EE güven, APK yayını, offline ölçek (TUR-20261021)

**Güncel doğrulanmış temel:** main CI #257 tamamen yeşil, debug.257 APK/sha256/signer GitHub Releases erişilebilir. Debug sertifikası yerinde üretim OTA garantisi değildir.

**Bu turda ayrı ayrı PR CI + merge tamamlanan işler:**
- PR #75 PEER-TRUST-TX-01: ECDSA doğrulanmış kişi ID / public key / LANU kod alias'ları ve sohbet iletişim kaydı tek Room transaction. Şifreli ID eşlemesi/kişi DAO hatasıyla rollback ve tekrar sync regresyonları (GitHub Actions #258).
- PR #76 APK-PUBLISH-01: Build + apksigner + checksum sonrasında GitHub Release APK, SHA256 ve signer dosyalarını anonim HTTPS adresinden gerçekten yeniden indir; byte-byte CI artifact eşitliği, SHA256, signing-mode ve cihaz upgrade=false metadata doğrula. Altı verifier regresyonu (PR Actions #259); gerçek birleşik main publish koşusu ayrıca doğrulanacak.
- PR #77 OFFLINE-BATCH-01: en eski 200 PENDING/WAITING mesajı sabit timestamp+ID sırasıyla işle; 215 bekleyen ve 5 gönderilmiş mesaj testi hiçbir satırın silinmediğini, iki turda kuyruğun boşalabildiğini doğrular (PR Actions #260).

**Kalem kalem puan (bağımsız üretim kanıtı eksik):**
| Kategori | Ağırlık | Mevcut doğrulanan | Bu turun main+Release testleri geçerse | Nihai hedef |
|---|---:|---:|---:|---:|
| Güvenlik / kimlik | 30% | 80 | 82 | 95 |
| Yerel veri / kurtarma | 25% | 60 | 62 | 95 |
| E2EE / mesajlaşma | 15% | 64 | 67 | 90 |
| CI / APK / OTA yayın | 15% | 87 | 90 | 95 |
| UX / performans | 15% | 55 | 55 | 85 |
| **Ağırlıklı toplam** | **100%** | **70/100** | **72/100 (koşullu)** | **93/100** |

**Yayın kabulü:** PR'ların geçtiği testleri gerçek cihaz/production testleri sayma. Sadece main'deki son commit için main build/test/lint/signer/sha256 ve yeni anonim GitHub Release APK indirme kontrolü yeşilse 72/100 kabul edilir.

**Açık yüksek etkili üretim P0:**
1. Hesap sahibinin güvenli GitHub Secrets alanına kalıcı APK keystore + 4 parola/alias/fingerprint bileşenlerini eksiksiz eklemesi; aynı sertifikayla iki ardışık artan versionCode APK, Samsung A55 üzerinde adb install -r ve PIN/kimlik/mesajların korunması. Debug eski APK kaldırma kesinlikle önermeme.
2. Gerçek tarihi Room v1-v3 uygulama/veritabanı şema örnekleri varsa elde et; bunlar depoda olmadığından hayali migration üretme. Mevcut gerçek Room v4 kurulum verisinin güncellemeyle kalıcılığına fiziksel cihazda test ekle.
3. Kesintiye dayanıklı SQLCipher benzeri yerel veritabanı şifreleme ve plaintext-WAL kalıntı temizleme, gerçek kimlik anahtarı / tam veri yedeği ve veri geri yükleme kanıtları. Kullanıcının verisini silen fallback yasak.
4. Samsung A55 + Xiaomi + HMS'li veya GMS'siz Huawei gerçek iki kullanıcı ile E2EE teslim/read, offline, çoklu 200'lü backlog, silme/replay, OTA ve TalkBack testi; dış kripto protokolü incelemesi.
5. Firebase gerçek yapılandırma ve kullanıcı mahremiyeti onayı olmadan Crashlytics etkin gösterme; Hilt/MVVM, ergonomi ve büyük veri performans testleri hâlâ P1.
6. Canlı Supabase retention cron'un ilk bağımsız çalışma günlüğü, operasyonel restore ve KVKK denetimi son kabul için gerekli.

**Otomatik çalışma sınırı:** GitHub CI/test/release takip edilebilir; kullanıcı telefonu veya özel keystore olmadan gerçek cihaz yükseltme yapıldığı iddia edilemez.


### 21.10.2026 — P0 Android Runtime Room koruma kabul kapısı (DB-DEVICE-GATE-01)
- **Kök neden:** Önceki CI yalnız JVM/Robolectric ve şema kontrolü çalıştırıyordu; `app/src/androidTest` gerçek Android Runtime yoluna bağlanmamıştı. Yeşil CI fiziksel veri korumasını kanıtlamıyordu.
- **Düzeltme:** API 34 Android emülatörde yedi tablo reopen + bilinmeyen v3 fail-closed + transaction rollback + installed backup flag kontrolleri eklendi; release işi iki bağımsız test işine bağlandı.
- **Kabul:** PR CI Android Runtime job ve main CI gerçekten yeşil olmadan başarılı olarak işaretlenemez. Emülatör sonucu gerçek Samsung/Xiaomi/Huawei fiziksel testlerinin yerine geçmez.
- **Kalan P0:** tarihî v1-v3 gerçek şemalar, bütün Room SQLCipher/WAL şifreleme, key-loss kurtarma, iki aynı sertifikalı stable APK ve `adb install -r` cihaz kabulü. Bu engeller bitmeden `production ready` ilan edilmez.
- **Ayrıntı:** `docs/P0_ANDROID_RUNTIME_DATABASE_GATE_2026-10-21.md`.


---

### 21.10.2026 — LANU premium 90+ eylem sırası (UPGRADE-P0-20261021)

**Talimat:** Önce #1 kalıcı APK imzası ve veri kayıpsız güvenli yükseltme; #2–#5 anayasa takip listesine alınır. Kod değişikliği ve başarılı CI hiçbir zaman gerçek cihaz kabulü sayılmaz.

**#1 / P0 — Kalıcı APK sertifikası ve güvenli yükseltme — AÇIK, kısmen kodlandı**
- Mevcut CI, korunan beş GitHub Actions imzalama sırrı tam sağlanınca imzayı SHA-256 ile doğrular, sürüm kodunu artırır ve imzalı sürümü ayrı etiketler. Sır yokken çıkan APK yalnız geçici debug'dır.
- Bu iş paketinde stable APK yayımlanmadan önce **önceki stable release'in sertifika fingerprint'ini karşılaştıran** yeni CI kapısı ve altı regresyon testi hazırlandı; sertifika değişirse, eski signer yoksa veya bozuksa yayın durur. İlk stable APK ancak yeni başlangıç noktasıdır; eski geçici debug sürümlerini güncellediği iddia edilmez.
- **Bloker:** Korunan kalıcı keystore/özel anahtar ve beş GitHub Actions Secrets değeri hesap sahibi tarafından sağlanmadıkça bu depo kalıcı imzalı APK üretemez. Sırlar sohbet, kod, issue, PR veya Release içine konmaz.
- **Kabul:** İki ardışık stable APK'da aynı sertifika parmak izi ve artan versionCode; SHA-256 + anonim GitHub indirme doğrulaması; gerçek Samsung A55'te ilk stable sürüme PIN/kimlik/sohbet/geçmiş/ayar verisi ekleyip ikincisini uygulamayı kaldırmadan adb install -r ile yükleme; veri ve OTA koruması; önceki farklı imzalı debug kurulumları asla kaldırmaya zorlamama. Bu kanıtlar oluşmadan #1 kapanmaz.
- İzleme: GitHub issue #16, #22 ve docs/ANDROID_APK_SIGNING_AND_UPGRADES_TR.md.

**#2 / P0 — Yerel veritabanı ve kurtarma — BEKLİYOR**
- Tam Room (WAL dâhil) şifreleme ve anahtar kaybı; gerçek yedek/geri yükleme, elektrik/ağ kesintisi ve migration rollback tasarımı. Tarihî v1-v3 veritabanı/şemaları olmadan sahte migration oluşturulmaz.
- Kabul: gerçek örneklerde yıkımsız göç, Room v4 veri/kimlik/sohbet/rehber bütünlüğü ve cihaz kanıtı; destructive fallback yok.

**#3 / P0-P1 — Gerçek cihaz ve canlı iletişim — BEKLİYOR**
- Samsung A55, Xiaomi ve Huawei üzerinde OTA, kilit ekranı, pil, ağ kesintisi, performans; iki ayrı cihazda gerçek E2EE metin mesajı ve offline teslim/okundu testleri. Bağımsız kriptografi incelemesi.
- Kabul: cihaz/Android/build kimlikli test raporları, canlı sunucu smoke, PIN/veri koruma; demo çağrılar gerçek WebRTC olarak sunulmaz.

**#4 / P1 — Mimari, Crashlytics ve R8 — BEKLİYOR**
- Manuel AppContainer'dan Hilt'e geçiş; Compose/MVVM, Coroutine/Flow ve güvenli exception handling. Gerçek Firebase projesi ve mahremiyet onayıyla Crashlytics; üretim release için R8/ProGuard.
- Kabul: çalışan gerçek Crashlytics kayıtları (PII gizli), küçültülmüş imzalı release build, unit/integrasyon testleri, veri bütünlüğü.

**#5 / P1 — Premium UX, performans ve yayın uygunluğu — BEKLİYOR**
- A55 öncelikli FPS, RAM, CPU, pil, başlangıç, uzun ifade ve büyük hesap geçmişi ölçümleri; TalkBack, %200 yazı, 360dp, yatay/dikey ve tema/kontrast. KVKK, kalıcı gizlilik politikası ve silme/saklama akışları.
- Kabul: en az iki fiziksel cihaz ölçümleri, üretim politikalarının yayımlanması, tüm açık P0'ların kapatılması ve güvenli imzalı APK'nin doğrulanması.

**Kalite referansı:** 21.10.2026 itibarıyla kanıta dayalı 69/100 değerlendirmesi; 90+ yalnız hedef, gerçekleşmiş sonuç değildir. Sıra #1 → #2 → #3 → #4 → #5. Kullanıcı verisi korunmadan özellik etkinleştirilmez veya başarı iddiası kurulmaz.


---

### 23.10.2026 — P0 OTA sertifika engeli / kullanıcıda henüz veri yok (OTA-BOOTSTRAP-01)

**Kullanıcı gözlemi:** Samsung A55'te versionCode 267, GitHub v1.0.0-debug.269 için APK sertifika uyumsuzluğu. Kök neden: her CI'da yeni geçici debug anahtarı oluşturulması. Android aynı applicationId ve farklı sertifika için uygulama içi yerinde yükseltmeyi reddeder.

**Yeni kullanıcı koşulu:** Kullanıcı 23.10.2026 tarihinde şimdilik LANU içinde korunacak veri olmadığını belirtti. Bu yalnız bu ilk cihaz için kullanıcı onayıyla tek seferlik temiz kurulumu mümkün kılar; eski APK'nin üzerine imza denetimini atlayarak kurulum yapılmaz. Başka cihazların mevcut kullanıcı verisi korunmalıdır.

**Kod ve kontrol:** scripts/bootstrap_stable_signing.sh yerel anahtar üretme/yedekleme ve GitHub Actions Secrets yerleştirme akışı; .github/workflows/android.yml manuel stable yayın (yalnız imzalama anahtarı korumalı ve beş secret tam ise), UI imza uyumsuzluğu için doğrulanmış resmî GitHub release sayfası ve veri olmayan kullanıcıya ayrı temiz kurulum yönergesi, negatif URI ve CI bash testleri.

**Kabul kapısı:** (1) Kullanıcı hesabı güvenilir yerel signing bootstrap çalıştırdı; (2) ilk stable APK Release + SHA256/signer yayımlandı; (3) ikinci stable sürüm aynı sertifika SHA256 + artan versionCode ile çıktı; (4) temiz kurulan ilk stable'dan ikinciye fiziksel Samsung A55 üzerinde uygulama içi güncelleme doğrulandı; (5) main CI ve release download read-back başarılı. Bu beş kanıt sağlanmadan durum **P0 AÇIK**. Eski debug sürüm kaldırılacaksa uygulamada korunacak veri bulunmamalı ve kullanıcı bilinçli onay vermelidir.


---

### 23.10.2026 — İlk kalıcı imzalı LANU APK yayını (SIGNING-BASELINE-273)

- **Kanıt:** [GitHub Actions #273](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/actions/runs/39938347797) — kullanıcı Samsung A55 Termux üzerinden GitHub Actions Secrets'a kalıcı PKCS12 keystore'u sağladı ve güvenilir workflow_dispatch çalışmasını başlattı; Android 14 runtime, build/test, sertifika parmak izi eşitliği, sha256 ve anonim Release read-back **başarılı**.
- **İlk stable yayın:** [v1.1.0-signed.273](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/releases/tag/v1.1.0-signed.273), GitHub ana dal commit 36cc558bdce16b353258af90df0fa174cb987d46. Bu, korunan sertifika kimliği için temel referanstır.
- **İkinci doğrulama:** Bu belgeye ait kontrollü ana dal güncellemesi yeni GitHub CI sürüm numarası tetikler; ikinci protected stable APK'nın önceki stable sürümle fingerprint eşitliği, artan versionCode, SHA256 ve anonim indirilebilirlik kontrolünden geçmesi beklenir. CI sonucu doğrulanmadan başarı kabul edilmez.
- **Fiziksel cihaz kabulü açık:** Kullanıcı LANU'da korunacak veri olmadığını belirtti. Android, eski ephemeral debug üzerine yeni stable sertifikalı APK'yı kuramaz. Kullanıcının kendi onayıyla bir defalık eski LANU'yu kaldırıp **ilk stable.273** APK'yı temiz kurması ve sonrasında **ikinci stable sürüme uygulama içinden güncelleme** yapması gerekir. Bu gerçekleşmeden P0 kapatılmış sayılmaz.
- **Özel anahtarın kalıcılığı:** Telefonun Termux içindeki $HOME/.lanu/signing klasörü, keystore ve parolalarla birlikte yalnız kullanıcı denetimindedir; cihaz/Termux silinmeden önce erişimi kısıtlı, güvenilir şifreli yedek gerekir. Secret veya keystore içeriği dokümanlara, CI loglarına ya da GitHub Release'e yüklenmez.


---

### 23.10.2026 — SAMSUNG A55 KANITLI 274→286 OTA KABULÜ (OTA-DEVICE-ACCEPTANCE-20261023)

**İspat tarihi:** 23.10.2026, Türkiye saati. **Kaynak:** Samsung Galaxy A55 (Android 16) kullanıcısının gerçek cihaz ekran görüntüsü ve kullanıcı tarafından açık kabul beyanı. Bu madde, önceki "OTA henüz cihazda doğrulanmadı" tarihli notları silmez; onları **23.10.2026 itibarıyla** yeni kanıtla günceller.

#### KABUL EDİLDİ — mevcut gerçek cihazda kanıtlananlar
- **Sürüm 274 → 286:** Kullanıcı, kurulu `v1.1.0-signed.274` sürümünden uygulama içindeki **Güncelleme Kontrol** akışıyla sürüm kodu **286**'ya geçtiğini ve güncellemenin başarılı olduğunu doğruladı. Ekran görüntüsünde **"Android sürüm kodu: 286"** ve **"Yeni uygun bir sürüm bulunamadı. Yüklü sürüm kodu: 286."** yazıyor.
- **Veri bütünlüğü:** Kullanıcı güncelleme sonrasında açıkça **"Veri kaybı yok"** bilgisini verdi. Bu gerçek cihazda gözlemlenen 274→286 yükseltmenin kullanıcı kabulüdür. PIN, mesaj/kimlik/ayarların *her biri için ayrı otomatik veri karşılaştırma raporu* yoktur; kullanıcı onayının kapsamı olduğundan geniş yazılamaz.
- **Mesajlaşma:** Kullanıcı daha önce iki gerçek kişinin karşılıklı mesajlaşabildiğini test edip başarılı bildirdi. Bu test, 286 sonrasında yeniden gerçekleştirildiği yönünde kanıt değildir; uçtan uca gecikme, offline teslim ve şifreleme denetimi ayrıca açık kalır.
- **İmza ve dağıtım:** GitHub Actions [#273](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/actions/runs/39938347797), [#274](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/actions/runs/39939057506), [#286](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/actions/runs/39949015300) **SUCCESS**. Her korunan stable APK resmî [Releases](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/releases/tag/v1.1.0-signed.286) alanında APK+SHA256+signer ile yayımlandı. #286'da **önceki stable sertifika sürekliliği ve anonim public indirme read-back** kapıları geçti. `versionCode` artıyor. Anahtar GitHub Secrets içindedir, repo veya yayın artefaktına konmamıştır.
- **Sessiz arayüz güncellemesi:** #286'da hesap makinesi, PIN, sohbet, kişi/ayar etkileşimlerindeki uygulama kaynaklı zorunlu titreşim çağrıları kaldırıldı; Compose host View için click sound/haptic kapatıldı. CI/Robolectric testleri geçti. **Samsung A55'te 286 sürümünde tüm tuşların gerçekten sessiz/titreşimsiz kaldığına dair ayrı kullanıcı teyidi henüz yok.** Sistem klavyesi ve erişilebilirlik ayarları LANU tarafından değiştirilmez.
- **Hata dayanıklılığı:** Mesaj Flow yükleme ve süreli gizleme coroutine hataları kullanıcı dostu mesaja düşecek şekilde korumaya alındı; Android 11+ sistem process-exit sınıflandırması OTA sayfasında **yerel, hassas veri içermeyen** tanılama olarak eklendi.

#### AÇIK P0 — Android uygulama çökmesi (#82)
- **Cihaz bulgusu:** Kullanıcının 286 sürümü açıkken aldığı ekran görüntüsünde **"Android kapanma tanılaması: Uygulama kodu hatası (Java/Kotlin)"**, tarih **23.10.2026 15:03:42**. Ekran görüntüsü yaklaşık 15:04'te çekilmiş. Bu *gerçek bir kapanma kategorisi kaydıdır*; olayın 274 sırasında mı, 286 sırasında mı olduğu, hangi metot/stack trace'den kaynaklandığı bu özetten **anlaşılamaz**.
- **İlke:** Bildirimin kök nedeni doğrulanmadan "crash bitti", "production ready", "hata düzeldi" denemez. [GitHub #82](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/issues/82) açık kalır. Android system logs / privacy-safe stack trace veya yerinde tekrarlanabilir senaryo ile kök neden bulunmalı; fix + regresyon + yeni aynı imzalı APK + gerçek cihazda tekrar smoke gerekir. Kullanıcıdan PIN, açık mesaj, token, kimlik anahtarı, hassas log istenmez.

#### YENİ/DEVAM EDEN STANDARTLAR VE AÇIKLAR
1. **P0 Crash & ANR:** Samsung A55 üzerinde kilit açma, PIN, ekran geçişi, hesaplama, sohbet açma/gönderme/arka plan-ön plan ve OTA sonrası senaryoları tekrarla; cihazdaki Java/Kotlin çökmesinin sınıfını değil **kök nedenini** kanıtla. #82 kapatılmadan kusursuzluk iddiası yok.
2. **P1 İmza kurtarma [#83](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/issues/83):** Telefon Termux `$HOME/.lanu/signing` içindeki PKCS12 keystore + parolalarının *erişim sınırlandırılmış, şifreli ve cihaz dışı* yedeği ile geri yükleme testi yapılmalı. GitHub Actions Secrets orijinal özel anahtarın yeniden okunabilir tek güvenilir yedeği değildir. **İmzalama dosyası/şifresi/logu sohbete, repo veya açık bulut klasörüne yüklenmez.** Yedek başarısı ve yedekten doğrulama ayrı kanıtlanır.
3. **P1 Çoklu cihaz:** Samsung A55 üzerinde 274→286 OTA gerçek kullanıcı kabulü tamamlandı; **Xiaomi ve GMS/HMS farklı Huawei** cihazları, Android sürüm farkları, pil optimizasyonu, arka plan teslim ve çevrim dışı/reconnect testleri hâlâ açık.
4. **P1 Veri ve uçtan uca güvenlik:** Room v4 gerçek veri saklama ve kalıcı yedek/kurtarma, cihaz kaybında kimlik geri yükleme, SQLCipher/eşdeğer yerel veritabanı şifrelemesi ve WAL koruması, doğrulanabilir E2EE/anahtar sürekliliği ve bağımsız kriptografi incelemesi ayrı kabul gerektirir. Tarihî v1-v3 migration örnekleri olmadan sahte göç üretilmez.
5. **P1 Premium Android mimarisi:** Hilt ile DI (mevcut manuel AppContainer tamamlanmış sayılmaz), gerçekten yapılandırılmış ve mahremiyete uygun Crashlytics/alternatif çökme gözlemi, global exception handling, gerçek release R8/ProGuard ve sürüm arttırma. Bağlantı/ücret/onay eksikse tamamlandı yazılmaz. Uygulama içinde verileri ifşa eden log ve gereksiz takip yok.
6. **P1 Operasyon:** Supabase saklama/temizleme cron'unun bağımsız koşu ve geri yükleme kanıtı, düşük ağ/bant genişliği, mesaj kuyruğu 200+ ve silme/tekrar oynatma durumları, yedek kurtarma drill, KVKK/veri saklama politikası.
7. **P2 UX [#84](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/issues/84):** 286 ekran görüntüsünde sol üst "← Hesap makinesine dön" satırı sistem saat/çubuk alanıyla üst üste geliyor. **Kod düzeltmesi uygulandı:** `MainActivity.kt` güncelleme ekranına `WindowInsets.safeDrawing` eklendi (commit `5f300a58e06e7b42f3b0726fbdb70f0b7fcb3e85`). Gerçek A55 ekran görüntüsü, TalkBack ve büyük yazı kontrolü henüz yapılmadı; açık kalır.
8. **P2 Sessiz UI gerçek cihaz kabulü:** 286'da hesap makinesi+PIN+sohbet uygulama kaynaklı titreşimi ve tıklama sesi gerçekten yok mu kontrol et. Android klavyesinin kendi ses/titreşimi ayrıca telefonun kullanıcı ayarıdır; LANU sistem ayarlarını gizlice değiştirmez.
9. **P1 Video/sesli arama:** İki yönlü **metin mesajı** kanıtı, gerçek ses/video WebRTC çağrısı kanıtı değildir. Demo çağrıları gerçekmiş gibi sunma; TURN maliyeti/onayı ve taşıma güvenliği ayrıca gerekli.

#### P0 imzalama/OTA sorunu için kapanış kuralları
- Önceki [#16](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/issues/16) (geçici sertifika engeli) ve [#22](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/issues/22) (kalıcı sertifika ve artan versionCode), test ve kullanıcı kanıtlarıyla 23.10.2026 tarihinde **TAMAMLANDI / CLOSED**: stable sertifika aynı, 274→286 gerçek kullanıcı OTA sonucu başarılı, kullanıcı veri kaybı olmadığını doğruladı. Bu kabulün kapsamı **Samsung A55 üzerinde gözlenen test** ile sınırlıdır. Bu, `#82` uygulama çökmesini, cihaz dışı keystore yedeğini, tüm Android cihazlarında OTA uyumunu veya tüm ürünün hazır olduğunu kanıtlamaz.
- Geri dönüşte önceki veri kayıplı debug imzasına asla otomatik dönme; üretimde silme/yeniden kurma önerisi ancak verilerin doğrulanmış yedeği ve kullanıcının açık tercihiyle olabilir.


---

### 23.10.2026 — ÖNBELLEK TEMİZLİĞİ SONRASI SAMSUNG A55 ÇÖKME GÖZLEMİ

- **Gerçek cihaz kullanıcısı bildirimi:** Samsung Galaxy A55'te sürüm 286'ya veri kaybı olmadan geçildikten sonra Android'in `ApplicationExitInfo.REASON_CRASH` kaydında 23.10.2026 15:03:42 tarihli **Java/Kotlin kod hatası** görülmüştü. Kullanıcı daha sonra yalnız **uygulama önbelleğini temizlediğini** ve bu işlemden sonra **şu ana kadar yeni benzer hata almadığını** bildirdi.
- **Kanıt sınıfı:** *Şu an tekrarlanmıyor / gözlem altında*. Önbellek temizliği, olayın kesin kök nedenini göstermiyor; eski tarihli exit log'u kalabilir. **P0 #82 açıktır; kullanıcı verisi silinemez, uygulama kaldırılmaz.** Tekrar oluşursa gizli verileri ifşa etmeyen stack trace/kök neden ve cihaz senaryosu esas alınır.
- **Güncel APK yayını:** [GitHub Actions #289](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/actions/runs/39951330311) — tüm işler SUCCESS, korunan aynı APK imzası ve SHA-256 / anonim APK read-back başarılı; [v1.1.0-signed.289](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/releases/tag/v1.1.0-signed.289) yayımlandı. Bu sürümde status bar/OTA ekranı güvenli çizim alanı düzeltmesi bulunuyor. **289 gerçek A55'te güncelleme ve çakışma yok testi henüz yapılmadı**; eski 286 cihaz kabulü geriye dönük geçerlidir.
- **Kalan P1:** Özel imzalama anahtarının erişim kontrollü şifreli cihaz dışı yedeği ve geri yükleme testi [#83](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/issues/83). Başka marka/model cihazlarda ağ, mesaj, PIN ve OTA testleri; Room şifreleme, güvenli kurtarma, Hilt ve gerçek gizlilik onaylı Crashlytics/R8 üretim yayın zinciri henüz tam kabul edilmemiştir.


---

### 23.10.2026 — LANU GÜVENLİ MEDYA PAYLAŞIMI BAŞLANGIÇ KARARI

**İş paketi:** [GitHub #85 — Güvenli Medya Paylaşımı](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/issues/85). Kullanıcı 23.10.2026 tarihinde geliştirmeye başlama onayı verdi. **Bu onay ücretli altyapı, gizlilik zayıflatma, gerçek kullanıcı verilerini silme veya doğrulanmamış APK yayınlama onayı değildir.**

**Ürün sırası:** (0) P0 crash kök nedeni #82, imza kurtarma #83 ve offline metin kuyruğu regresyon güvenliği; (1) Compose sohbet eki (+), Android Photo Picker ve SAF ile fotoğraf/galeri/belge seçimi, kamera ile fotoğraf çekme; (2) istemci tarafında AEAD medya şifrelemesi, doğrulanmış alıcıya anahtar teslimi, yetkili özel storage, checksum, upload/download state machine, retry/offline; (3) A55 + ikinci gerçek cihazda veri kayıpsız OTA ve iki yönlü dosya kabulü; (4) video/sesli mesaj, premium UX, çoklu cihazlar ve gerçek WebRTC çağrı ayrı aşamalar.

**Gerçek mevcut kod durumu:** `CommunicationViewModel.sendMediaMessage()` işlevi kapalı olduğunu bildiriyor; `CommunicationRepository.sendMessage(mediaUrl)` medyayı açıkça reddediyor. `MessageEntity.mediaUrl` alanı ve Compose `AsyncImage` önizlemesi tek başına gerçek medya aktarımı değildir. Kullanıcıya özellik çalışıyor diye gösterilmez.

**Güvenlik ve maliyet sözleşmesi:** Galerinin tamamına erişim izni isteme; Android sistem seçicilerini kullan. Medya içerik anahtarı veya açık medya URL'sini sunucu/DB loglarına yazma; şifreli payload ve yalnız alıcıya yetkili erişim. Dosya tipleri/boyutları/EXIF/retention/TTL doğrulanır. Her kritik yol ana servis→alternatif→fallback→hata yönetimi→güvenli varsayılan→log→smoke test. Kullanıcının açık onayı olmadan ücretli storage planına geçilmez.

**Release kabulü:** Yeni özellik `READY` olamaz; gerçek iki cihazda E2EE medya teslimi, kesilen ağda güvenli retry, mevcut metin/PIN korunumu, SHA256/signer continuity, CI ve doğrudan indirilebilir GitHub Release APK kanıtları sağlanmadan.


---

### 23.10.2026 — GÜVENLİ KÜÇÜK MEDYA PAYLAŞIMI 304 CI KABULÜ

**Milestone:** [#85 Medya Paylaşımı](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/issues/85); [PR #86](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/pull/86) ve [PR #87](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/pull/87) **MERGED**; kalıcı imzalı [v1.1.0-signed.304](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/releases/tag/v1.1.0-signed.304) resmî GitHub Releases'te yayımlandı. [Actions #304](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/actions/runs/39968054234) **SUCCESS**: Android birim/Robolectric, Lint, API34 emülatör Room, APK bütünlük ve imza doğrulama, önceki stable sertifika SHA-256 sürekliliği, anonim public APK read-back. 304 uygulaması **A55 veya ikinci fiziksel cihazda henüz kabul testinden geçmedi**.

#### Bu sürümde gerçek uygulama özelliği
- Sohbet mesaj alanındaki **+** menüsünden Android **Photo Picker** ile galeri fotoğrafı, sistem kamerasından düşük çözünürlüklü fotoğraf önizlemesi veya Android SAF **OpenDocument** ile PDF/TXT seçme.
- **Yalnız dosya başına 18.000 bayt veya daha küçük** JPEG/PDF/UTF-8 TXT. Fotoğraf 18 KB'a sığana kadar yeniden JPEG sıkıştırılır, kaynak EXIF/konum metaverisi yeni JPEG'e eklenmez. Kaynak görsel **12 MiB streaming byte** ve piksel boyut kontrolünden geçer; yanlış MIME, bozuk Base64/UTF-8, aşırı dosya boyutu ve yanlış SHA-256 reddedilir.
- Mevcut `PeerMessageCrypto` ECDH/AES-GCM şifreli mesaj biçimiyle gönderilir; gönderici/alıcı için Room'da yalnız şifreli frame saklanır; mevcut relay'den iletilir. Yeni public storage URL veya özel medya depolama servisi **yoktur**.
- Android başka uygulama seçicisini açınca mevcut güvenlik politikası PIN kilidini sürdürür. Activity-owned picker ve save callback ile veriler kaybolmadan aktarılır; hazırlanan ek en fazla 5 dakika yalnız RAM'de bulunur. Kilitli hesap makinesi ekranında gizli sohbet olduğunu ele veren medya başarı bildirimi gösterilmez.
- Alıcıda şifre çözümünün ardından ek biçimi/hash tekrar kontrol edilir, küçük JPEG önizlemesi veya dosya adı gösterilir, sadece kullanıcının açık **Cihaza kaydet** seçimiyle Android'in seçtiği dosyaya dışarı aktarılır.
- **Farklı protokol/sürüm uyumu:** İlk medya özelliği için gönderici ve alıcının da 304+ olması önerilir; eski sürüm uyumluluğu ve kullanıcı hataları ayrıca test edilmelidir.

#### Açık kabul ve sınırlamalar
1. **Gerçek cihaz A55 OTA 286/289 → 304**, mevcut PIN/sohbet/verilerin korunması ve Samsung A55 + ikinci Android cihaz arasında iki yönlü fotoğraf/PDF/TXT gönder-al-kaydet; Android seçicisi→PIN→taslak onayı; izin reddi/iptal, çevrim dışı tekrar, bozuk ve büyük dosya, FLAG_SECURE ve crash/ANR smoke hâlâ **AÇIK**. #85 bu kanıtlar alınmadan kapatılamaz.
2. **Büyük fotoğraf, video, ses, çok dosyalı ekler ve büyük PDF DESTEKLENMİYOR.** Alıcı ve gönderici için 18 KB sınırı açıkça belirtilir. Gerçek büyük dosya için istemci AEAD + private auth storage, süreli URL, chunk/resumable upload, sınır/retention/temizlik, şifreli metadata ve doğrulanabilir yetki/maliyet tasarımı uygulanmadan özellik hazır sayılmaz.
3. **Güvenlik sürekliliği:** P0 Android crash [#82](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/issues/82) ve cihaz dışı keystore yedeği [#83](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/issues/83) açıktır; kullanıcı dosyalarını silme, APK'yı kaldırma ya da PIN'i gevşetme önerilmez.
4. **Sürüm standardı:** CI derlemesi başarılı olmadan `READY`, gerçek cihaz testi olmadan `DEVICE ACCEPTED` yazılmaz. Bütün yayınlarda kalıcı sertifika SHA-256 karşılaştırması ve doğrulanmış indirilebilir APK zorunlu; hiçbir secret repo/log/issue içine konulmaz.


---

### 23.10.2026 — A55 FOTOĞRAF KABULÜ VE KULLANICI KONTROLLÜ MEDYA PIN TERCIHİ

**Kullanıcının aynı gün açık geri bildirimi:** "Güncelleme tamam", "Foto çek gönder tamam", "Galeriden Foto seç gönder tamam". Bunlar Samsung A55 üzerinde **bildirilen başarılı gerçek cihaz adımlarıdır**. Uygulama içi güncelleme ve gönderme kanıtı vardır; karşı cihazdan fotoğrafı açma/kaydetme, PDF/TXT, offline retry ve tam veri kıyaslama burada ayrıca raporlanmadı, bunlar [#85](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/issues/85) kapsamında **açık** kalır.

**Yeni kullanıcı kararı:** Otomatik medya seçici dönüş PIN ekranı istenmiyor; kullanıcı Ayarlar'dan **kendisi açabilsin**. [#88](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/issues/88) ve [PR #89](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/pull/89).

**Uygulama tasarım sözleşmesi (otomatik CI + A55 kabulü gerektirir):**
- Gizli Alan → Ayarlar → Güvenlik'te "Fotoğraf/dosya seçimi sonrası PIN iste" anahtarı; **yeni kurulumda varsayılan KAPALI**, kullanıcı AÇIK yaparsa sistem medya seçicisinden dönüşte tekrar PIN gerekir. Tercih kalıcı SettingsDao row'da tutulur, bozuk kayıt güvenli şekilde PIN'i gerektirir.
- **Kapsamı dar tut:** Yalnız LANU'nun açtığı Android galeri/Photo Picker, kamera, dosya seçici ve kullanıcının doğrudan açtığı dosya kaydetme ekranlarında en çok **2 dakikalık**, yalnız bellekte tutulan geri dönüş muafiyeti. **PIN ile ilk giriş, uygulama elle kilitleme, ekran kapatma/Keyguard, normal arka plan geçişi, uzun süreli ayrılık ve yeniden başlatma eski PIN korumasına tabi kalır.** Ekran görüntüsü FLAG_SECURE, E2EE, parola kontrolü ve PIN kilitleme oranı değişmez.
- Kullanıcı tercihi açıkken picker dönüşünde Android onStop olmasa bile PIN gerekiyorsa uygulanır. Kısa picker seçimi PIN kapalıysa daha önce doğrulanmış oturumu korunur; yeni bir doğrulamasız oturum yaratılmaz. Güvenlik tercih hatasında fail closed.
- Regresyon: ayar varsayılanı/persistence, PIN açık-kapalı, ekran kapatmak, elle kilitlemek, picker TTL, izin iptali, içerik doğrulama, iki cihaz medya, OTA veri korunumu ve yeni sürümde çökme/ANR yokluğu testleri.
- **Kapanış:** PR CI, resmî kalıcı imzalı GitHub APK ve gerçek Samsung A55'te PIN anahtarının iki durumunda tekrar test olmadan bu madde kapatılmaz. #82 çökme, #83 keystore yedeği ayrıca açık kalır.


---

### 23.10.2026 — LANU 324 SAMSUNG A55 KULLANICI KABULÜ

**Kullanıcı tarafından açıkça beyan edilen cihaz kabulü:** “Evet başarılı 324 sürüm pın dahil herşey aktif ve doğru.” Bu, Samsung Galaxy A55 üzerinde kurulu **324 sürümündeki mevcut özelliklerin, özellikle kullanıcı kontrollü PIN davranışının, doğru çalıştığına ilişkin gerçek cihaz geri bildirimidir**. Önceki aynı gün cihaz kabulü: “Güncelleme tamam”, “Foto çek gönder tamam”, “Galeriden Foto seç gönder tamam.” Birlikte değerlendirildiğinde uygulama içi OTA, kameradan küçük fotoğraf gönderme, galeriden küçük fotoğraf gönderme ve medya dönüşü PIN tercihi **KULLANICI KABUL EDİLDİ** statüsündedir.

**CI ve dağıtım kanıtı:** [GitHub Actions #324](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/actions/runs/39995876046) tamamı SUCCESS: birim/Robolectric, Android API 34 Room kabulü, APK kalıcı imzası + signer continuity ve yayımlanan APK için anonim tekrar indirme bütünlük kontrolü. Resmî [v1.1.0-signed.324](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/releases/tag/v1.1.0-signed.324) APK, checksum ve sertifika dosyalarıyla erişilebilir.

**GitHub kapanışı:** [#88](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/issues/88) — kullanıcı tarafından yönetilen “Fotoğraf/dosya seçimi sonrası PIN iste” tercihi, kod/test ve v324 cihazdaki genel kabul kanıtı üzerine **CLOSED**. Kullanıcı AÇIK/KAPALI seçeneklerinin her birini tek tek belgeleyen ayrı ekran kaydı sunmadı; cihaz onayının kapsamı olduğundan geniş anlatılmaz. Android ilk PIN, elle kilitleme, ekran kilidi ve veri koruma standartları değişmez.

**Bilerek AÇIK tutulanlar:** [#82](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/issues/82) önceki Java/Kotlin crash'in kesin kök nedeni (önbellek temizliği sonrası yeni çökme bildirilmedi; yeni tanılama ancak sonraki olayda işe yarar), [#83](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/issues/83) Termux APK signing anahtarı için cihaz dışı şifreli yedek ve geri yükleme doğrulaması, [#85](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/issues/85) 18 KB ötesi tam boy fotoğraf/belge/video için güvenli yetkili storage + anahtar teslimi ve iki cihaz gönder/al kabulü. Büyük medya AES-GCM parçalı şifreleme çekirdeğinin mevcut olması **canlı video gönderimi olduğu anlamına gelmez**.


---

### 23.10.2026 — 342 DOĞRULANMIŞ MEDYA DAYANIKLILIĞI VE 78/100 ÖN DEĞERLENDİRME

**Güncel mühendislik kanıtı:** [PR #99](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/pull/99) **MERGED**; [CI #341](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/actions/runs/40023393269) unit/Robolectric, Android Lint, APK ve API34 Room veri-koruma **SUCCESS**. [CI #342](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/actions/runs/40024378379) tüm 3 job **SUCCESS**; kalıcı signer sertifikası önceki stabil sürümle aynı, SHA-256 ve anonim public APK read-back doğrulandı; resmî [v1.1.0-signed.342](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/releases/tag/v1.1.0-signed.342) APK + checksum + signer.txt yayımlandı. Bu **otomatik CI/yayın kabulüdür**; Samsung A55 üzerinde 342 kurulduğu veya kullanıcı verileri 342'de korunduğu henüz bildirilmedi.

#### Şu anda kanıtlanan/uygulanan
1. **Hesap makinesi:** Normal, bilimsel, finans (KDV, faiz, kâr marjı, indirim), birim dönüştürücü, hesap geçmişi ve grafik çizme Compose ekranları vardır. Büyük BigDecimal değerlerinde #5 edge-case denetimi ayrı açık.
2. **Gizli alan:** `2011.` gizli alan girişi, kullanıcı tercihiyle isteğe bağlı özel alan PIN'i, kayıtlı PIN değiştirme, kilitlenme/deneme sınırlaması, FLAG_SECURE koruması, tema ve erişilebilir ayarlar. Eski PIN'li kullanıcı varsayılanı korumalı; PIN kapatma mevcut PIN doğrulaması gerektirir. Kod PR #94; **#93 altında gerçek A55 tüm açık/kapalı/yeniden etkinleştirme adımları ayrı kabul gerektirir**.
3. **Gerçek mesajlaşma:** Kullanıcı daha önce iki kişinin karşılıklı metin mesajı gönderdiğini test etti; sunucuda ECDSA kimlik doğrulama, çift taraflı kişi ekleme, cihazda ECDH/AES-GCM şifreli mesaj gövdesi, teslim/okundu, offline PENDING retry, cihazda şifreli kimlik ve parolalı kimlik yedeği. Yedek eski mesaj/rehber arşivini taşımaz; aynı kimlikle eş zamanlı çoklu cihaz yok. PIN/sohbet, cihazlar arası ayrıntılı audit ve bağımsız kriptografi kanıtı hâlâ açık.
4. **Küçük medya:** Gerçek Samsung A55'te kamera fotoğrafı gönderimi ve galeriden fotoğraf seçip gönderim kullanıcı tarafından başarılı bildirildi. Mevcut inline E2EE medya **18.000 byte sınırı** JPEG/PDF/UTF-8 TXT, alıcı görüntüleme ve kullanıcı onaylı SAF kaydetme kodu; iki cihazda foto/PDF/TXT al/kaydet ve offline senaryoları tümüyle kabul edilmedi.
5. **Büyük medya hazırlığı:** 64 MiB AES-256-GCM / 64 KiB chunk, kaynak mime magic-byte ön kontrolü ve EXIF-siz fotoğraf yeniden JPEG üretimi, alıcıya özel E2EE media key envelope ve güvenlik testleri **kodlandı**. `PrivateMediaCrypto` artık tüm şifreli nesne SHA-256 hash'ini **plaintext temp oluşturulmadan önce** kontrol eder, her blok GCM/AAD ile doğrulanır. 0-byte takılan dış file provider fail-closed; kaynak MIME/izin reddinde stream kapanır; uygulama yeniden başlatılınca sadece `Context.cacheDir` içindeki LANU'nun terk edilmiş medya temp dosyaları sınırlı ad listesiyle temizlenir. Temizleme hiçbir Room/kimlik/PIN/OTA/imza dosyasına dokunmaz. Tüm bunlar, **büyük medya gönderme özelliği kullanıma açık** anlamına gelmez.
6. **OTA:** Kullanıcı A55'te 274→286 veri kaybı olmadan in-app update, kamera/galeri gönderimi ve daha sonra v324 medya PIN davranışının doğru olduğunu bildirdi. CI #342 imza sürekliliği ve indirilebilir APK'yı doğruladı. **342 cihaz kabulü bekler.**
7. **Canlı relay/retention:** Bağlı LANU Supabase projesi `talhknjcjaijqiqwdyhf` ACTIVE_HEALTHY. Read-only SQL ile Storage bucket **0** (public bucket 0), zamanlanmış `lanu-safe-retention-daily` görev **active**, 03:15 UTC, son 7 günde **2 success** (son 23.10.2026 03:15 UTC) doğrulandı. 5 tablo için "RLS enabled, no direct policy" INFO uyarısı özel imzalı relay/service-only erişim modeliyle tutarlıdır; kimliksiz herkese erişim sağlayan politika eklenemez. Performans advisory'de 4 unused-index INFO var; silmek için canlı sorgu kullanım kanıtı gerekir. **Bu çalışmada canlı veri değişmedi.**
8. **Çağrı:** Arama ekranı, ANC/DSP demo/telemetri mevcut; iki gerçek cihaz arasında sesli/görüntülü **WebRTC görüşmesi kanıtlanmadı**; gerçek çağrı varmış gibi iddia edilemez.
9. **Crash/kurtarma:** Samsung eski Java/Kotlin kapanma kategorisi #82'nin kesin kök nedeni hâlâ bilinmiyor; kullanıcı önbellek temizliğinden sonra yeni benzer hata bildirmemiştir. Yerel mahremiyet-korumalı kaynak satırı tanılama var. Signing keystore şifreli Termux backup betiği CI'da testli; **gerçek cihaz dışı yedek ve izole geri yükleme kabulü #83 açık**.

#### Ön değerlendirme ve değişmez öncelikler
- **78/100 geçici mühendislik puanı**, resmî ürün sertifikası veya A55 342 testi değildir. Otomatik imza/OTA ve çalışan metin/küçük fotoğraf özellikleri güçlü; crash kök nedeni, kurtarma, üretim R8/ProGuard, Hilt, gizlilik uygun crash izlemesi, 2+ cihaz ve büyük medya private storage henüz ürün tam kabulünü engelliyor.
- **P0:** #82 crash sebebi/cihaz kabulü, #83 signing key disaster recovery (yüksek operasyonel öncelik), #93 isteğe bağlı `2011.` PIN akışının gerçek cihaz acceptance.
- **P1:** #85 özel **public=false** object storage, kullanıcı/alıcı için ayrı imzalı auth grant + replay koruma, kota/TTL ve otomatik temizlik, AES dosya anahtarının yalnız E2EE eşe ulaşması, resumable upload/download, video ve PDF/PPT/XLS content-validation. Ücret doğuran plan/bucket, anon public erişim veya service_role'ın APK'ya gömülmesi **yasak**. Telefonlarda çift yönlü güvenli medya + retry doğrulanmadan açılmaz.
- **P1/P2:** #84 edge-to-edge ekran gerçek cihaz kontrolü; Android 16 A55 + Xiaomi + Huawei cross-device testleri; UI erişilebilirliği, onboarding, ağ/offline fallback, release R8 ve profesyonel Compose/Hilt mimari standartları.
- **Güvenli geliştirme sınırı:** main→alternatif→gerçek fallback→hata yakalama→güvenli varsayılan→hassas veri içermeyen log/izleme→smoke. Kullanıcı sohbet/kimlik/PIN verisi veya keystore hiçbir çalışmada silinemez. Her önemli yayın APK + SHA256 + signer GitHub Releases'te doğrudan indirilebilir ve önceki sertifikayla aynı olmalıdır.

**Raporun kaynak ve kapsam notu:** Bu bölüm repo kodu, [CI #341](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/actions/runs/40023393269), [CI #342](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/actions/runs/40024378379), önceki kullanıcı cihaz kabul beyanları ve salt okunur Supabase metadata/cron sonuçlarıyla hazırlanmıştır; bu turn içinde 342 APK'sı gerçek Android cihazda test edilmedi.


---

### 24.10.2026 — SAMSUNG A55 PIN + FOTOĞRAF + VERİ KORUNUMU EK CİHAZ KABULÜ

**Yeni kullanıcı teyidi (iki kez):** “Pin fotoğraf gönderme ve veriyi koruyor.” Bu, gerçek cihaz üzerinde **PIN korumasının çalışması, fotoğraf gönderebilme ve kullanıcı verilerinin korunması** için olumlu gözlem olarak kabul edildi. 23.10.2026 tarihli önceki cihaz beyanları (“Güncelleme tamam”, “Foto çek gönder tamam”, “Galeriden Foto seç gönder tamam” ve sürüm 324'te “pın dahil herşey aktif ve doğru”) ile tutarlıdır.

**Doğru kapsam:** Bugünkü mesaj tek başına kullanıcının tam olarak **343 sürümünü kurduğunu**, PIN'i kapatıp/açıp yeniden denediğini, ikinci telefonun fotoğrafı alıp kaydettiğini, büyük JPEG/video/PDF/PPT/Excel gönderdiğini veya veri saklamasının bağımsız ölçümlerle karşılaştırıldığını kanıtlamaz. Kullanıcının önceki **324 cihaz kabulü** geçerlidir; bu yeni genel teyit belli bir sürüme atfedilmez.

**Takip:** [#85](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/issues/85) büyük medya ve iki cihaz gönder/al kabulüne kadar açık. [#93](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/issues/93) `2011.` sonrası PIN'i isteğe bağlı yapma ve aç/kapat/yeniden etkinleştirme kabulüne kadar açık. [#82](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/issues/82) çökme kök nedeni ve [#83](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/issues/83) özel imzalama anahtarının güvenli cihaz dışı yedeği halen açık. [#88](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/issues/88) eski medya seçimi dönüşü PIN tercihi olarak ayrı ve önceki 324 kullanıcı kabulüyle kapatılmıştır; bugün açık/kapalı iki senaryo test edildi diye **yeni kanıt uydurulmaz**.

**Güncel kamuya açık yayın (repo kontrolü):** [v1.1.0-signed.343](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/releases/tag/v1.1.0-signed.343), [Actions #343](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/actions/runs/40025323705) SUCCESS. Bu **otomatik yayın kanıtıdır**; kullanıcının kurulu sürümünün 343 olduğunu göstermez. Büyük dosya AES-GCM protokol kodu olsa da yetkili canlı dosya depolaması ve E2EE anahtar teslimi ile gerçek video gönderimi tamamlanmış sayılmaz.



## 2026-10-27 — Kullanıcı onayı: Gizliliği koruyan isteğe bağlı mesaj bildirimi ve bağlantı bütçesi

**Yetkili kullanıcı kararı (2026-10-27):** LANU gizli mesajları için bildirimler yalnız özel alandaki Ayarlar ekranından kullanıcı açıkça etkinleştirdiğinde çalışır; varsayılan KAPALI. Kullanıcı aynı yerden kapatabilir ve sunucu kayıt iptali kalıcı/retry edilebilir olmalıdır. Arama bildirimleri ayrı, bağımsız tercih olarak korunur; birinin kapatılması diğerinin iznini yok etmez. Android 13+ `POST_NOTIFICATIONS` izni açık rızanın ardından istenir. İzin verilmez veya kanal sistemden susturulursa bildirim ve ses taklit edilmez.

**Bildirim mahremiyet sözleşmesi:** Sistem bildirimi başlığı yalnız `LANU Hesap Makinesi`; mesaj metni, gönderen, sohbet, adres/kimlik, telefon, alıntı, dosya, okunmamış sayısı veya gizli içeriğe dair önizleme hiçbir push verisinde, başlıkta, gövdede, PendingIntent'te, kilit ekranında ya da uygulama logunda yer alamaz. Bildirim sesi ayrı Android bildirim kanalına bağlı, kullanıcının sistem ses/sessiz/DND tercihlerine tabidir. Bildirime dokununca normal hesap makinesi açılır, gizli alana otomatik geçiş olmaz, `2011.` ve isteğe bağlı PIN kapıları aynen korunur. Sohbet bildirim kanalı çağrı kanalından ayrı olmalıdır.

**Teslim, özerklik ve performans:** Gizli bildirim yalnız karşılıklı yetkilendirilmiş E2EE mesajın sunucuda kalıcı yazılmasından sonra, yeniden gönderimlere karşı idempotent şekilde, sunucu tarafındaki doğrulanmış ve opt-in cihaz için meta-verisiz `data-only` FCM ipucu ile iletilebilir. FCM bir teslim garantisi/şifre çözme izni değildir. Sunucu anahtarı mobil istemciye konulmaz, abonelikler tablo ve RPC üzerinden RLS/service-role ile korunur. Gizli veriye erişmek için arka plan içerik eşitlemesi başlatılmaz. Kullanıcı mesajı kapattığında yerel blok hemen geçerli olur; sunucuda iptal başarısızsa güvenli imzalı retry gerekir. Şebeke/FCM bağlantısı arıza yaptığında normal şifreli mesaj teslimi sürer.

**Bağlantı:** Ön plandaki mevcut birleştirilmiş eşitleme ve HTTP bağlantı havuzu korunur; hata durumunda aşamalı geri çekilme, uygulama açıldığında yeniden deneme ve gereksiz arka plan poll/foreground-service açmama temel varsayılandır. Hız iyileşmesi ölçümle kanıtlanmadan yüzdelik performans iddiasında bulunulmaz.

**Kabul kapıları:** Android API 23–36 izin/kilit ekranı/sessiz mod/kanal geçişi; call-only ve message-only tercih matrisi; geç gelen token, offline opt-out, yeniden kurulum/kimlik değişimi; sahte/tekrar/eskimiş FCM; backend RLS/mutual/duplicate/rate-limit; iki fiziksel cihazda uygulama kapalıyken bildirim ve hesap makinesi giriş testi. **Durum: mimari karar onaylı, uygulama ve saha doğrulaması bekliyor.**


### 2026-10-27 — Mesaj bildirimi onayı için çökme güvenliği (sertleştirme)

**Doğrulanmış hata modeli:** FCM cihaz tokenı sunucuda kaydedilmişken UI işlem sonunda yerel onay bayrağına geçmeden uygulama kapanırsa, sunucu izni kalıcı gibi kalabilir. Buna karşı mesaj bildirim kaydı yapılmadan **önce** yalnız boolean türünde `enrollment_in_progress + pending_remote_revoke` kurtarma işareti diske `commit()` ile yazılır. Sunucu başarı yanıtından **sonra** bu işaret atomik olarak açık tercihe dönüşür. Uygulama işlem ortasında kapanırsa sonraki açılışta işaret iptal-öncelikli hâle getirilir; FCM kayıt silme isteği mevcut ECDSA kimliğiyle ve ağ varsa gönderilir. Çalışan bir kayda aynı anda bağlanan WorkManager iptal gönderemez. Yeni token, sohbet, kullanıcı kimliği ve bildirim içeriği SharedPreferences/WorkManager/loglara eklenmez.

**Kapatma mahremiyeti:** Kullanıcı bildirimleri kapattığında daha önceki *mesaj* sistem bildirimi cihazda iptal edilir; arama bildirim kimliği, kanalı ve izni korunur. Google Play Services/Firebase token alma işlemi sonlu süreli ve iptal edilebilir olmalıdır. Yarım kalan sunucu iptalinin kalıcılığı, offline/çökme/enrollment race senaryolarıyla test edilir. Mevcut şifreli mesaj geçmişi, hesap, kişi ve Room tabloları değişmez.

**Kanıt kapısı:** Bu katman testlerinin yeşil olması, gerçek bir cihazda FCM ulaştığı anlamına gelmez. Ana dal/kararlı APK yayınından önce son PR commit'inin CI, Android 14 kabulü, imzalama ve bağımsız çağrı bildirim testi gerekir.

### 2026-10-27 — Hızlı bağlantı ve gizli bildirim dayanıklılığı

**Arama kurulumu:** LANU mevcut non-trickle, E2EE SDP sözleşmesini korur. Bounded ICE hazırlığında gerçek `a=candidate` satırı, bu görüşmenin WebRTC `onIceCandidate` olayı ve en az bir sunucu-yansımalı (`typ srflx`) aday **yerel SDP'ye yazılmışsa**, en az 1400 ms hazırlık + 650 ms aday durgunluğu sonrası offer/answer sinyali erken iletilebilir. ICE zaten `COMPLETE` olduğunda geçerli adaydan sonra 250 ms sakinlik yeterlidir. Boş, eski-oturum veya yalnız host adayları erken gönderilmez; 8 saniyelik üst sınır, ağ hatası mesajı, 35 saniye görüşme kurma ve 25 saniye kurtarma bütçeleri korunur. Bu yalnız bir hazırlık gecikmesi optimizasyonudur; gerçek hız kazanımı ve NAT uyumluluğu iki fiziksel telefonda ölçülmelidir. Trickle ICE, arka planda kontrolsüz polling, yeni stun/turn sağlayıcısı, paralel ikinci çağrı veya ücretli TURN bu aşamada **eklenmez**.

**Bildirim dayanıklılığı:** İsteğe bağlı ve varsayılan kapalı mesaj bildirimindeki gizlilik + re-entry kapısı aynen kalır. Arama bildiriminde de Android `notify` çağrısı öncesinde ve sonrasında mevcut yerel çağrı izni yeniden kontrol edilir. İzin yarışta kapanmışsa aynı arama bildirim ID'si iptal edilir; mesaj bildirimine dokunulmaz. Çağrı bildirimlerini kapatma veya hatalı kaydı geri alma işleminde cihazdaki eski çağrı uyarısı da silinir. Uygulama yeniden başladığında yerel izin kapalıysa eski çağrı uyarısı temizlenir. Arama–mesaj kanal/izin ayrımı, FCM tokenı, gönderici, kişi, mesaj içeriği, SDP ve kimliğin yerel bildirim/veri/loglara eklenmemesi temel şarttır.

**Resmi rehberlerle sınırlılık:** Android/Firebase yüksek öncelikli FCM, kullanıcıya görünen acil uyarılar içindir; OEM Doze, izinler, ağ ve kullanıcı sessiz ayarı nedeniyle garanti edilmez. Kısa `onMessageReceived` içinde yalnız gerçek yerel bildirim basılır, mesaj geçmişi için arka plan sunucu senkronizasyonu veya uzun iş yapılmaz. Android'de uzun işler gerektiğinde ayrı WorkManager uygun olabilir, ancak burada gereksiz bir sürekli servis, pil tüketen döngü veya özel içeriğin FCM'e gönderilmesi yasaktır.

**Test kapısı:** Yeni `CallIceGatherPolicyTest` gerçek CRLF, srflx/host, sessiz süre, eski ICE oturumu, bounded completion ve kötü biçimli aday satırlarını test eder. Çağrı bildirimindeki opt-out yarışında yalnız çağrı uyarısının silindiği; diğer mesaj kanalının korunduğu Robolectric testi ve tüm Android 14 kabul/CI imza-R8-APK kapıları zorunludur. Gerçek cihazda çift yönlü arama süresi, mesaj FCM teslimi ve eski APK üzerinde veri kayıpsız yükseltme ayrıca ölçülene kadar doğrulandı denmez.

