# LANU Hesap Makinesi — P0/P1 ve 90+ kalite kapıları

**Denetim temeli:** 20 Ekim 2026, main v1.0.0-debug.160 (`fe471574`). Puanlar bir sertifika, bağımsız pentest veya fiziksel cihaz kanıtı değildir.

## Bu geliştirme paketinde yapılanlar

- PIN veritabanı değeri artık `pbkdf2-sha256:210000:<rastgele 16 bayt tuz>:<256-bit türetilmiş özet>` biçiminde; yeni PIN'ler sabit tuzlu SHA-256 kullanmaz. Eski sabit tuzlu kayıt yalnız **doğru PIN doğrulandığında** aynı Room kaydında dönüştürülür. Hatalı PIN denemesi miras veriyi değiştirmez.
- Ağır PIN KDF işlemi `Dispatchers.Default` üzerinde, eşzamanlı girişler `Mutex` altında; beş deneme/30 saniye denetimi korunur.
- `CryptoUtils.encrypt/decrypt/hashPin` artık hata olursa düz metin/PIN/ciphertext gibi görünen değer döndürmez; açık hata üretir. `CryptoUtils.hashPin` yalnız eski kayıtları tanıyan uyumluluk yolu olarak kalmıştır.
- Finans ekranındaki altı hesaplama (yüzde, indirim, KDV, taksit, mevduat, kâr marjı) sınırları kontrol eden `BigDecimal` motoruna bağlandı; sıfır taksit/geçersiz oran/boş input yanlışlıkla 0 veya hesaplanabilir bir sonuç olarak sunulmaz.
- Room testleri aynı sürümde yeniden açıldıktan sonra PIN/ayar değerlerinin korunmasını ve *bilinmeyen eski şemada* yıkıcı sıfırlamanın yapılmamasını hedefler.

## Açık P0 — kullanıcının/altyapı sorumlusunun onayı veya ek kanıtı gerekiyor

1. **Kalıcı APK sertifikası:** GitHub Actions Secrets için gereken beş değer kullanıcı denetiminde kurulmalı; gizli anahtar sohbetten veya repo üzerinden aktarılmaz. En az iki ardışık APK'da aynı sertifika SHA-256, artan versionCode ve gerçek telefonda yerinde veri kayıpsız yükleme doğrulanmalı. Önceki debug APK imzası ile uyumluluk garanti edilemez.
2. **Tüm yerel veri şifreleme ve Kurtarma:** Room veritabanı tamamı SQLCipher/benzeri korumayla şifrelenmiyor; mevcut şemadan geçiş önce bağımsız gerçek cihaz yedeği, eski örnek veri, enerji kesintisi ve restore testleri ile tasarlanmalı. Anahtar kaybında sohbet/rehber/ayarlar taşınmıyor. Uygulamayı kaldırma önerilmez.
3. **Tarihî Room şema geçişleri:** Güncel şema sürüm 4. Eski sürümler v1-v3 için bağımsız şema varlıkları ve test veritabanı örnekleri repoda mevcut değil. Migration uydurulmadı; bilinmeyen sürümlerde veri silme yerine açık hata davranışı korunuyor.
4. **PIN gücü:** Kısa 4 haneli PIN düşük entropilidir; rastgele tuzlu PBKDF2 çevrimdışı saldırı maliyetini artırsa da **Android Keystore'a bağlı PIN doğrulayıcı**, parola politikası ve cihaz destekli ekran kilidiyle ilişki kurulmadan tam güvenlik iddiası verilemez.

## Açık P1 — sonraki bağımsız iş paketleri

1. **Bağımsız mesajlaşma kriptografi denetimi:** P-256 ECDH ve AES-GCM bugün kullanılıyor, ancak ileri gizlilik / cihaz kompromisinden sonra kurtarma için double ratchet yok. Önce protokol/anahtar modelini gözden geçir, sonra uyumluluk protokolünü yükselt.
2. **Sunucuda saklama ve kullanıcı hakkı:** mesaj/nonce meta veri TTL ve temizleme; hesap tamamen silme, KVKK mahremiyet metinleri, olay müdahalesi ve veri taşınabilirliği.
3. **Firebase Crashlytics:** Üretim Firebase projesi, Android google-services.json ve izin/mahremiyet onayı olmadan gerçek Crashlytics raporu çalıştığı iddia edilmemeli. Düşük veri paylaşımı, gizli PIN/mesaj maskeleme, sürüm/build etiketleme gereklidir.
4. **DI / Hilt:** Mevcut manuel `AppContainer` ve üç ViewModel Factory mevcut; modül ayırma ve Hilt dönüşümü bağımsız testli PR ile yapılmalı, mevcut kullanıcı verisini değiştirmemeli.
5. **Performans ve cihaz testi:** Samsung A55 ve en az bir farklı Android modelinde 10+dk hesaplama/grafik FPS, anlık RAM, pil, TalkBack, 200% font, yatay/dikey ekran, büyük geçmiş ve OTA ağ kesintisi.
6. **Para / hesaplama hassasiyeti:** Bu paket `FinancialMath` testlerini ekler; kapsamlı finansal referanslara göre taksit/365 gün varsayımı ve kullanıcı anlaşılır faiz/KDV açıklamaları ayrıca onaylanmalıdır.
7. **Sunucu gerçek cihaz iletişim:** iki farklı cihaz, karşılıklı kod, uçtan uca metin, çevrimdışı alıcı sonradan giriş, kesilen ağ, teslim/okundu, hesap kurtarma, kopya kimlik/çoklu cihaz destek dışı sınırı.

## 90+ geçiş kapıları (önerilen, ölçülebilir)

| Faz | Kapatılacak işler | Kabul kriteri | Sonraki hedef |
| --- | --- | --- | --- |
| A: P0 temizlik | PIN doğrulayıcı, yardımcı şifreleme, finans sınırları | Tüm otomatik test, lint, APK ve SHA-256 doğrulaması yeşil | 65–70 |
| B: İmza ve migration | sabit keystore + önceki/sonraki imza karşılaştırma; gerçek eski veritabanlarıyla migration; yerel veri koruması | `adb install -r` / PIN / geçmiş / sohbet / kişi verileri iki sürüm arasında korundu, rollback testi | 76–82 |
| C: Mesajlaşma ve işletim güvenliği | harici kripto gözden geçirme, canlı iki cihaz senaryoları, sunucu TTL ve KVKK | kanıtlı iki cihaz akışı, yetkisiz erişim, silme/kurtarma, yoğunluk ve ağ hata testleri | 84–88 |
| D: Premium ürün hazırlığı | Hilt, Crashlytics, performans, erişilebilirlik, Beta testleri | 2+ gerçek cihaz, tekrarlanabilir CI kanıtı, crash-free ve performans eşiği, açık P0 kalmaması | 90+ |

**Not:** Puanlar hedef tahminidir; test/cihaz/güvenlik bulguları çıkarsa düşebilir. Kullanıcı hesabı, fiziksel telefon ve eski APK imza anahtarının yokluğu kod yazılarak aşılamaz.
