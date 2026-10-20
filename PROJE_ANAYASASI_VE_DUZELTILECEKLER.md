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


### 20.10.2026 — P0 tekil mesaj silme güvenliği (MSG-DELETE-01)
- **Bulgu:** `hideMessageLocally` ve `deleteMessage` için `lanu_hidden_<id>` tombstone kaydı ve `messages` satır silme ayrı işlemlerdi; gönderilmiş mesajda sunucudan silme onayı alındıktan sonra yerel tombstone yoktu. Gecikmiş relay olayları silinen içeriği tekrar yazabilir, yarım DB işleminde gizlilik bozulabilirdi.
- **Düzeltme:** `syncMutex` altında tek `Room.withTransaction` içinde tombstone ve mesaj silme. `PENDING/WAITING/SENDING` mesajlar otomatik zamanlayıcıda korunur; kullanıcı açıkça iptal ederse kalıcı yerel tombstone ile kuyruktan çıkarılır. Uzaktaki silme başarısızsa yerel veri erken silinmez.
- **Kabul:** tekil zaman aşımı/silme tombstone, pendings korunma, SQLite silme hata enjeksiyonunda tüm durumun geri alınması, uzaktaki 503 hatasında yerel kopyanın korunması.
- **Sınır:** Bu yerel silme garantisi, sunucudaki kayıtların KVKK/TTL uyumu veya SQLite WAL'daki eski byte izleri için silme sertifikası değildir.
