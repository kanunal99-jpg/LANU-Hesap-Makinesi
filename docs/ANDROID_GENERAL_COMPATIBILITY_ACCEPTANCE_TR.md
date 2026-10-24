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
- Robolectric API 23, 29, 35 testleri manifest gizliliği, izinler,
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
| Android 9–11 (API 28–30) | Robolectric API 29 | OEM dosya sağlayıcısı |
| Android 12–14 (API 31–34) | Android emülatör API 34 | OEM pil/arka plan |
| Android 15–16 (API 35–36) | Robolectric API 35; hedef SDK 36 | Gerçek API 36 cihaz |
| GMS'siz Android/Huawei HMS | GMS gerektirmeyen temel testler | Gerçek HMS cihazı |
| Samsung/Xiaomi/Google/diğer | Genel API testleri | Fiziksel 2+ cihazda doğrulama |

Fiziksel kabul: hesaplama, PIN/gizli alan, kimlik, karşılıklı E2EE
mesaj, dosya seçici/dış Activity dönüşü, ağ kopma/geri gelme,
farklı arka plan sınırları, kurulu APK üzerine OTA ve veri sürekliliği.

**Sınır:** Bazı sağlayıcılar read() çağrısı içinde sonsuza kadar
bloklanabilir. Bu değişiklik art arda 0-byte döndürmeyi sınırlar;
gerçekten bloklayan sağlayıcı için iptal ve timeout ayrı çalışmadır.
Bu testler bütün markalarda sorunsuz çalışmanın kanıtı değildir.
