# PROJE ANAYASASI — 15.10.2026

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
