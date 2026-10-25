# LANU Android — Genel uyumluluk kabulü (24.10.2026)

**Hedef:** Samsung, Xiaomi, Huawei, Oppo, Vivo, Honor, Motorola, Google
Pixel ve diğer üreticilerde Android 6.0 (API 23) ve sonraki sürümlerde
güvenilir çalışma; Google Play Services olmayan cihazlarda temel
işlevleri GMS'e zorunlu bağımlı yapmama. Samsung A55'te APK 369'un
çökmesiz gözlenmesi diğer telefonların kabulü anlamına gelmez.

## Uygulanan iyileştirme

- Android Photo Picker / Storage Access Framework (SAF) belge akışında
  dış dosya sağlayıcısı arka arkaya 0 bayt döndürdüğünde sonsuz döngü
  yok: iki geçici duraksama tolere edilir, üçüncü duraksama reddedilir.
- Geçerli veri okuması sonrası duraksama sayacı sıfırlanır; yavaş
  ancak ilerleyen dosya sağlayıcıları hatalı reddedilmez.
- Kaynak boyut üst limiti ve InputStream kapatma korunur.
- Kalıcı/aralıklı duraksama, sınırlama, boş dosya testleri eklendi.
- Yeni Robolectric API 23 testi manifest gizliliği, izinler,
  korumalı OTA FileProvider, otomatik yedekleme ve eski sürüm crash
  tanılaması fallback'ini çalıştırır.
- API 34 emülatör gerçek Android çalışma zamanı kontrolleri mevcut
  CI'da debug ve küçültülmüş release için korunur.
- Room, kimlik, PIN, sohbet, imzalama, kullanıcının kurulu verileri
  ve canlı Supabase değiştirilmez.

## Cihaz kabul matrisi

| Test alanı | Şimdiki otomasyon | Eksik kabul |
| --- | --- | --- |
| Android 6–8 (API 23–27) | Robolectric API 23 | Fiziksel eski cihaz |
| Android 9–11 (API 28–30) | Mevcut API 28 crash fallback | OEM dosya sağlaycısı / API29 kabulü |
| Android 12–14 (API 31–34) | Android emülatör API 34 | OEM pil/arka plan |
| Android 15–16 (API 35–36) | Mevcut Robolectric API 35; hedef SDK 36 | Gerçek API 36 cihaz |
| GMS'siz Android/Huawei HMS | GMS gerektirmeyen temel testler | Gerçek HMS cihazı |
| Samsung/Xiaomi/Google/diğer | Genel API testleri | Fiziksel 2+ cihazda doğrulama |

Fiziksel kabul: hesaplama, PIN/gizli alan, kimlik, karşılıklı E2EE
mesaj, dosya seçici/dış Activity dönüşü, ağ kopma/geri gelme,
farklı arka plan sınırları, kurulu APK üzerine OTA ve veri sürekliliği.

**Sınır:** Bazı sağlayıcılar read() çağrısı içinde sonsuza kadar
bloklanabilir. Bu değişiklik art arda 0-byte döndürmeyi sınırlar;
gerçekten bloklayan sağlayıcı için iptal ve timeout ayrı çalışmadır.
Bu testler bütün markalarda sorunsuz çalışmanın kanıtı değildir.

## CI testi izolasyonu

İlk çoklu SDK Robolectric deneyiminde JVM ZIP dosya sistemi
çakışması (FileSystemAlreadyExistsException) oluştu. Güvenlik ve
tekrarlanabilirlik için yeni eklenen test yalnız API 23 için koşar;
mevcut Android 34 emülatör testleri ve depodaki API35 Robolectric
sınıfı ayrı çalışır. API29 çoklu-SDK denemesinin başarılı olduğu
iddia edilmez; API29 gerçek kabul ayrı iş kalemidir.

## 25.10.2026 — Gerçek cihaz kabulü: APK 387 / Samsung + Xiaomi

**Kullanıcının doğrudan teyidi:** “387 tamam samsung ve xiaomi de de”. Bu beyan, aynı yayımlanmış **v1.1.0-signed.387 APK** sürümünün **iki ayrı Android üreticisinde (Samsung ve Xiaomi)** kullanıcı tarafından olumlu test edildiği anlamına gelir.

- **KANITLANDI (kullanıcı kabul beyanı):** APK 387 Samsung ve Xiaomi telefonlarda çalıştı. Bu, yalnız A55 merkezli kabulden iki üreticili kullanıcı doğrulamasına ilerlemedir.
- **KANITLANMADI / açık:** Xiaomi model adı, Android sürümü, Samsung üzerinde denenmiş işlemlerin ayrıntısı, uzun dönem çökme/ANR sıklığı, PIN açık↔kapalı regresyonu, veri kayıpsız OTA, offline teslim, tüm medya işlemleri, gece arka plan davranışı, Huawei/HMS veya GMS'siz çalışma. Kullanıcı bunları açıkça teyit etmedikçe varsayma.
- **SQLCipher kapsam dışı:** APK 387 mevcut düz SQLite veritabanıyla üretilmiştir; iki cihazda çalışması SQLCipher migration veya şifreli DB kabulü değildir. #113 açık.
- **PIN ilkesi:** İsteğe bağlı PIN aynen korunur; kullanıcı tercihi zorunlu PIN'e çevrilmez.
- **Takip:** #82 çökme kök nedeninin bulunması ayrı kriterdir; temel cihaz teyidi sebebiyle otomatik kapanmaz.

Kaynak: [GitHub Release v1.1.0-signed.387](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/releases/tag/v1.1.0-signed.387), [CI #387](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/actions/runs/40171770068), [#82 gerçek cihaz kabul yorumu](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/issues/82#issuecomment-6395920354).
