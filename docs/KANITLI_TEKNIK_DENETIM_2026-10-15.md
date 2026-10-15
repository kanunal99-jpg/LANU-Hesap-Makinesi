# LANU-Hesap-Makinesi — KANITLI TEKNİK DENETİM

**Tarih:** 15.10.2026  
**Kapsam:** GitHub dosya ağacı, seçili kaynak kodu, CI logları, Releases. Gerçek cihaz ve dış servislerin tam E2E testi yapılmadı.  
**Dosya sayısı (denetim anında):** 87  
**CI gözlemi:** 15.10.2026 Android CI başarılı; APK + sha256 Releases mevcut.  
**Anayasa:** [PROJE_ANAYASASI_VE_DUZELTILECEKLER.md](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/blob/main/PROJE_ANAYASASI_VE_DUZELTILECEKLER.md)

## Kanıtlarla Düzeltilmesi Gerekenler

### 1. Arama bağlantısı gerçek WebRTC bağlantısı kurmadan bağlandı oluyor — P1 (DOĞRULANMIŞ İŞLEVSEL SAHTE BAĞLANTI RİSKİ)
- **Kanıt:** [app/src/main/java/com/example/core/calls/CallManager.kt#L38-L63](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/blob/main/app/src/main/java/com/example/core/calls/CallManager.kt#L38-L63); [CI koşusu](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/actions/runs/38673169472)
- **Gözlem:** startOutgoingCall: OUTGOING→800ms→RINGING→1000ms→CONNECTING→1000ms→CONNECTED; bu fonksiyon gerçek signaling/ICE handshake sonucu beklemiyor.
- **Somut örnek / etkisi:** Karşı cihaz çevrimdışıyken bile uygulama arayüzü bağlı gösterebilir.
- **Düzeltme kabul testi:** Fake simülasyon demo etiketiyle sınırlandırılmalı ya da gerçek WebRTC state callback'lerine bağlanmalı; iki cihazdan gerçek ses/video E2E.
- **Durum:** ARAŞTIRILDI, DÜZELTME HENÜZ BAŞLAMADI.

### 2. Yüksek hassasiyet bazı fonksiyonlarda Double'a indirgeniyor — P1 (KOD RİSKİ)
- **Kanıt:** [app/src/main/java/com/example/core/calculator/HighPrecisionMathEngine.kt#L62-L95](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/blob/main/app/src/main/java/com/example/core/calculator/HighPrecisionMathEngine.kt#L62-L95); [CI koşusu](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/actions/runs/38673169472)
- **Gözlem:** BigDecimal motorunda bilimsel fonksiyonlar ve gösterim bazı noktalarda `value.toDouble()` kullanıyor.
- **Somut örnek / etkisi:** 34 basamaklık girdi için log/trig/inverse hesaplarda sayısal hata olabilir.
- **Düzeltme kabul testi:** Sınır değer, BigDecimal round-trip, yüksek basamak hassasiyet ve sıfıra bölme testleri.
- **Durum:** ARAŞTIRILDI, DÜZELTME HENÜZ BAŞLAMADI.

### 3. CI başarılı ama iletişim özelliği uçtan uca kanıtlanmadı — P2 (CI KANITI / CİHAZ TEST GEREK)
- **Kanıt:** [actions/runs/38673169472](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/actions/runs/38673169472); [CI koşusu](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/actions/runs/38673169472)
- **Gözlem:** Derleme/test başarı kaydı var; birbirinden bağımsız iki Android cihazın gerçek bağlantısını doğrulamıyor.
- **Somut örnek / etkisi:** Cihaz A susturulunca karşı tarafa ses kesiliyor mu?
- **Düzeltme kabul testi:** Gerçek iki cihaz, kamera/mikrofon izinleri, ICE restart, ağ değişimi, pil tüketimi, foreground servis testleri.
- **Durum:** ARAŞTIRILDI, DÜZELTME HENÜZ BAŞLAMADI.

### 4. Anayasa test ve mimari klasörü var — P2 (MEVCUT BELGE KORUNMALI)
- **Kanıt:** [anayasa/README.md](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/blob/main/anayasa/README.md); [CI koşusu](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/actions/runs/38673169472)
- **Gözlem:** Ana anayasa klasöründe işlem günlüğü ve test sonuçları belgeleri mevcut.
- **Somut örnek / etkisi:** Yeni ortak kurallar bu özel kayıtları silmemeli.
- **Düzeltme kabul testi:** Yeni bulgular eski belgelere referanslı kalmalı; başarı testleri yeni commit'e bağlı olmalı.
- **Durum:** ARAŞTIRILDI, DÜZELTME HENÜZ BAŞLAMADI.

## Zorunlu 4 Faz
1. Araştır/Envanter: kapsam, kanıt ve etki; bu rapor başlangıç seviyesinde seçili dosya/CI incelemesidir.
2. Kök neden ve düzeltme: P0/P1 önce; en küçük geri alınabilir kod değişimi; gizli veriyi koru; ücret için onay al.
3. Build + test: lint, unit/integrasyon/UI/E2E, offline/kesinti/fallback, gerekirse gerçek cihaz.
4. Doğrula ve yayınla: tüm testler yeşil; Android APK Releases altında SHA-256 ile doğrudan indirilebilir; smoke ve bağlantı doğrulaması.

**Bulgu kapatma:** Kod satırı + kök neden + düzeltme commit'i + test + CI + sürüm/ürün smoke kanıtı olmadan hiçbir madde bitmiş sayılmaz.
