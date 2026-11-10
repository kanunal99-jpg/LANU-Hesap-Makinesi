# LANU — +5 kalite hedefi ve kanıt kaydı (10 Kasım 2026)

Bu çalışma tüm kategorilerde +5/10 kalite artışını **hedefler**; hiçbir kategori için ölçülmemiş +5 kazanımı iddia edilmez. Sayısal başlangıç ölçümü bulunmadığından mevcut ve nihai puanlar BİLİNMİYOR. 10 üstü puan yok.

Başlangıç: main commit 40d8c2d9b902e614d708573933ee4c4d8b760b4d; Actions #1247 başarılı; yayın v1.2.0-signed.1247 (prerelease). Önceki WebRTC, HW→SW AEC/NS, ICE/TURN, uyarlanabilir video, E2EE, canlı konum "Durdur", bildirim ayrımı, PIN, Room, SQLCipher shadow ve OTA işlevleri korunur.

| Kategori | Başlangıç 0–10 | Bu PR'da kod etkisi | Kanıt / ölçülmemiş risk |
|---|---|---|---|
| Sesli görüşme / gecikme | Ölçülmedi | İşaretleme retry güvenlik filtresi | Sinyal unit; P50/P95 cihaz yok |
| Görüntü / FPS / sync / bitrate | Ölçülmedi | Var olan uygulama korunur | Gerçek kamera FPS/AV yok |
| AEC / NS / AGC / yankı | Ölçülmedi | Var olan HW→SW fallback korunur | dB/echo cihaz yok |
| WebRTC / ICE / NAT / TURN | Ölçülmedi | Hatalı sinyal tekrarları engellenir | NAT matrix fiziksel yok |
| Wi-Fi ↔ mobil veri | Ölçülmedi | Gerçek geçici ağ retry korunur | Handover cihaz yok |
| E2EE / offline / kuyruk / read | Ölçülmedi | ACK ve sinyal için ortak fail-closed sınıflama | E2EE iki cihaz yok |
| Bildirimler / çağrı | Ölçülmedi | Önceki akış korunur | OEM bildirimi yok |
| Canlı konum / erken durdurma | Ölçülmedi | Önceki akış korunur | OEM STOP testi yok |
| Performans / pil / bellek | Ölçülmedi | Gereksiz security retry önlendi | Benchmark yok |
| Room / backup / recover | Ölçülmedi | Şema değiştirilmedi | Gerçek yükseltme kanıtı gerekli |
| Güvenlik / PIN / gizli alan | Ölçülmedi | Wrapped permission/key/OEM hata retry reddi | Penetrasyon ve cihaz testi yok |
| API / Supabase / Firebase | Ölçülmedi | Mevcut 408/5xx vs 401/429 korunur | Sunucu değişimi yok |
| Samsung / Huawei / Xiaomi | Ölçülmedi | Yeni SDK/izin yok | Fiziksel matris yok |
| Hata / log / izleme | Ölçülmedi | Causal chain güvenli sınırlandı | Üretim metrikleri yok |
| Arayüz / erişilebilirlik | Ölçülmedi | UX/gizli giriş değiştirilmedi | Kullanıcı testi yok |
| APK / imza / OTA | Ölçülmedi | Hatalı OTA read length reddi | Release kapıları gerekli |
| CI / otomatik test | Ölçülmedi | Yeni hata-kökü / OTA unit testleri | HEAD CI henüz kanıt bekliyor |
| Normal / bilimsel / finansal hesap | Ölçülmedi | Matematik motorları değiştirilmedi | CI regresyon gerekli |

## Zorunlu dayanıklılık zinciri
Mevcut güvenli sinyal → yalnız gerçek ağ/408/5xx bounded retry → 401/403/429, crypto/permission/OEM/TLS/cancellation safe stop → mevcut kullanıcı hata ve private telemetry → negatif test. OTA: orijinal imzalı paket+SHA ve dosya denetimi → InputStream sıfır/bozuk byte-count fail-closed → eski sürüm korunur → otomatik OTA smoke.

Yeni maliyet, SDK, izin, şema/migration, PIN, gizli hesap girişi, kripto kimliği, loglanmış kişisel veri yoktur. Yeni release YALNIZ son PR HEAD CI+Lint+R8, iki eski imzalı OTA tabanı, main CI, Release APK/SHA/signer ve anon HTTP readback geçerse hazırlanır. Fiziksel Samsung A55 + ikinci telefon P50/P95, dB, FPS ve modem handover sonuçları mevcut değildir.
