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
