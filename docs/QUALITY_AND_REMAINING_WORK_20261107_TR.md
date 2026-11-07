# LANU — güncel durum, eksikler ve geliştirme kaydı (7 Kasım 2026)

## Kanıt ve puanın anlamı

Başlangıç kaynak: c169c2a8efab3f3492ebd1da8cb79d5b00b5037f (main).
Kullanıcıya sunulan imzalı APK: v1.2.0-signed.1120.
Başarılı CI: https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/actions/runs/42354640790 .
Android unit/Robolectric, Lint, R8, API34 native/Room, aynı imzalı gerçek OTA
emülatör testi ve public APK/SHA/signer read-back geçmiştir. İki fiziksel
telefonda yeni sürüm kabulü ve gecikme/ses kalitesi ölçümü yapılmamıştır.

Aşağıdaki 73/100, kaynak + son CI + dağıtım kanıtına dayalı **öznel teknik
hazırlık değerlendirmesidir**; güvenlik sertifikası, test başarı yüzdesi veya
iki cihazda ölçülmüş teslim puanı değildir. Ölçülmemiş sonuçlara tam puan yok.
Hesap makinesi ve mağaza yayını bu puana dahil değildir.

| Ana ölçüt | Ağırlık | Başlangıç | Puanı sınırlayan açık |
|---|---:|---:|---|
| Gizli giriş, tercihli PIN ve görünür yüz gizliliği | 30 | 26 | Son APK'da fiziksel kilit ekranı/OEM kabulü eksik; ileri kripto/disk koruma ayrı açıklar |
| Mesajlaşma hızı, doğruluğu, kararlılığı | 20 | 16 | Ayrı timer/manual refresh işleri, kaybolabilen yenileme tetikleri, ağ geri dönüşünde 60s backoff; P50/P95 ölçülmedi |
| Sesli görüşme bağlantı ve süreklilik | 15 | 10 | Gerçek cihaz FCM/Doze/Wi-Fi↔mobil/uzun arama kabulü ve zor NAT çözümü eksik |
| Görüntülü görüşme netlik ve akıcılığı | 15 | 9 | İki OEM kamera/BT/video-audio senkron ve uzun süre ölçümü eksik |
| Gürültü/yankı azaltma, anlaşılabilirlik | 15 | 8 | Otomatik ve yazılım modu var; gerçek A/B gürültü/yankı/MOS ölçümü yok |
| İmzalı OTA, veri koruma, hata yönetimi ve kabul | 5 | 4 | CI ve public APK var; fiziksel OTA/bağımsız signer kurtarma kabulü ayrı |
| **Toplam** | **100** | **73** | **Geçici teknik hazırlık; fiziksel kullanıcı kabulü tamamlanmadı** |

## Eksikler — açık kayıt

| ID | Öncelik / alan | Somut eksik | Tamamlanma kanıtı / sınır |
|---|---|---|---|
| E01 | G03/G04, yüksek | Manuel yenileme, timer ve gönderim sonrası yenileme ayrı job'lar; refresh sürerken gelen istek düşebilir | Tek sahipli, sıralı, conflated pump; in-flight tetik korunmalı, 429 beklemesi aşılamamalı; en çok 24 ön plan sync/dakika |
| E02 | G03, yüksek | Doğrulanmış ağ geri dönüşünde foreground backoff 60s'ye kadar bekleyebilir | API23/24+ ağ callback → yalnız aktif özel ekran pump'ını uyandır; doğrulanmamış internet ONLINE sayılmamalı; callback yoksa timer fallback |
| E03 | G01/G04, yüksek | Son özel ekran kapanınca ayrı refresh işi de tamamen durmalı | Son owner çıkışında HTTP/collector/timer iptali; eski callback yeni oturumu tetiklememeli; background opt-in yolu ayrı korunmalı |
| E04 | G09, yüksek | 40 MiB sunucu sınırı var, büyük dosya UI/production aktivasyonu kapalı; #331 taslak | Gerçek signed grant→upload→server SHA→E2EE manifest→download, TTL/cleanup, iki fiziksel cihaz; sayı değiştirerek etkinmiş denmez |
| E05 | G02, yüksek | Gerçek Double Ratchet/ileri gizlilik aktif değil (#114) | Denetlenmiş protokol, downgrade/restore/eski istemci kabulü; mevcut E2EE/kimlikler korunur |
| E06 | G10, yüksek | Room tamamı canlı SQLCipher değil; shadow/preflight mevcut (#113) | Kayıpsız yedek/restore, WAL, Keystore, disk/güç kaybı, downgrade ve iki OEM; gerçek veri üzerinde otomatik cutover yok |
| E07 | G05/G06, yüksek | Fiziksel arka plan FCM/Doze/kilit ekranı ses-video kabulü eksik (#133/#3) | A55 + ikinci OEM, izin iptali, token yenileme, gerçek çift yönlü RTP ve çağrı sonrası kilit |
| E08 | G08, yüksek | Fiziksel AEC/NS/AGC/BT/echo/anlaşılırlık ölçümü eksik (#245) | Sessiz/gürültülü/BT A/B testleri; dB veya kalite sonucu uydurulmaz |
| E09 | G07, orta | Kamera/video/bitrate uyarlaması fiziksel OEM testleri eksik | A55 + ikinci üretici, kamera çevirme, düşük bant, 30–60 dk, pil/ısı/senkron |
| E10 | G03/G06, orta | TURN yok; zor/simetrik NAT'ta her ağda bağlantı garantisi yok | Ücretsiz mevcut yollar önce; ücretli TURN kullanıcı açık onayı olmadan açılmaz |
| E11 | G13, orta | Gerçek P50/P95, pil, ısı, uzun kullanım ve erişilebilirlik saha ölçümü yok | Özel veriyi kaydetmeyen gerçek iki cihaz deney raporu |
| E12 | G14, yüksek | Gerçek signer'ın bağımsız cihaz dışı şifreli restore kabulü açık (#83) | Sahibin ayrı ortamda restore + aynı sertifika kanıtı; sırlar repo/chat/log'a yazılmaz |
| E13 | G04/G12, orta | Sohbet UI tüm geçmişi listeliyor; sınırlı ACK sorgusu UI sayfalama yerine geçmez | Mesaj sırası/viewport/draft/retry korunarak ayrı sayfalama kabulü; mevcut geçmiş silinmez |
| E14 | Kapsam dışı/ertelenmiş | Hesap silme #224/#266 ve mağaza işleri | Kullanıcı yeniden istemeden production silme aktivasyonu yok; mağaza yayını iptal |

## Bu turdaki büyük geliştirme

E01+E02+E03 tek ürün akışında: foreground senkronizasyon pompası; manuel,
gönderim sonrası ve doğrulanmış ağ uyanışlarını tek sırada toplama; in-flight
istekte gelen tetikleri kaybetmeme; en az 2,5s aralık; 429 Retry-After'ı koruma;
ön plan sahibinin yaşam döngüsüyle soket/callback/timer iptali; API23 fallback.

Başarı ölçütü: seri ve bounded çalışma; sahte ONLINE yok; callback kayıt hatası
periodic sync'i bozmaz; captive portal tetiklemez; eski oturum yeni oturumu
uyandıramaz; gerçek foreground kapalıyken yeni ağ takibi/sync başlatılmaz.

Hesap makinesi/giriş/PIN/Room şeması/E2EE formatı, çağrı ses-video varsayılanları,
büyük medya flag'i ve ücretli servisler korunur. Son HEAD CI → main CI → aynı
imzalı OTA → public Releases APK/SHA/signer read-back olmadan yayımlandı denmez.
Teknik puan, ancak bu kanıtlar sağlandığında yeniden değerlendirilecek; hedef
puan kabul kanıtı değildir. Fiziksel ölçümlere bağlı puanlar kodla otomatik artmaz.
