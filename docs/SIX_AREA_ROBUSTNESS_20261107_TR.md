# Altı alan sağlamlaştırma — 7 Kasım 2026

Başlangıç main `875f68cf2d592478f66f8ef4950ada0ee2a49d06`, imzalı1123,
önceki geçici teknik hazırlık75/100. Tam puan hedefi kabul sonucu değildir.

| Alan | Kök neden ve bu sürümdeki gerçek uygulama | Yeni kabul testi | Kalan tam puan kapısı |
|---|---|---|---|
| Gizlilik | Hassas olarak işaretlenmiş kopya panoda kalabiliyor. API24+ tek60s zamanlayıcı, rastgele sahiplik işareti; yalnız kendi kopyasını temizler; içerik zamanlayıcıda tutulmaz; OS ret hatası çökertmez | API24/34 süre sonu, başka uygulama kopyası korunması, yeniden kopya süresi, izin reddi; API23 eski davranış | Fiziksel kilit/ekran/TalkBack/arka plan; API23 ve süreç ölümü/OS kısıtında otomatik temizleme garantisi yok. Canlı SQLCipher/ratchet etkinleştirilmez |
| Mesajlaşma | Bir bozuk E2EE olay tüm batch'i durduruyor. Yalnız kripto ret hatası izole edilir; sonraki sağlam olaylar kalıcı transaction+ACK alır; outbox ilerler; hatalı olayın revision/ACK/READ kaydı oluşmaz; işlem sonunda güvenlik hatası görünür kalır | Gerçek Room+E2EE; bozuk→sağlam olay, tekrar oynatma/dedup, kötü olaya hiçbir onay, sağlam outbox aynı ID/ciphertext ile ilerler | Fiziksel iki cihaz gecikme/kayıp/offline/kuyruk ölçümü; bozuk olay relay'de tutulur, otomatik silinmez; bilinmeyen peer/DB/protokol hatası kör atlanmaz |
| Sesli görüşme | Donanım ses modülünün kurulmasından sonra factory kurulumu hata verirse yazılım fallback kapsam dışıydı. Artık iki aşama birlikte sahiplenilir; başarısız ADM bırakılır; tek yazılım denemesi; iptal/izin/LinkageError tekrar edilmez | Başarılı donanım, factory hatası→release→yazılım, iki hata→iki release, iptal/izin ve software-only stop | A55+ikinci OEM focus/BT/RTP/FCM/NAT kabulü; canlı asenkron mikrofon hatasıyla motor yeniden kurma yok |
| Görüntülü görüşme | Yeni getStats çağrısı eski RTCP raporunu yeniden okuyabilir; eski ICE/5s'den geç callback video ayarlayabilir. Epoch+sıra+yaş kapısı ve audio/video RTCP receipt timestamp dedup; epoch değişince eski iyi/kötü örnekler silinir; gerçek encoder seviyesi korunur | Eski epoch/gecikme/sıra/tekrarlı1000rapor/NaN, iki yeni kötü rapor ve yeni epoch toparlanması | İki OEM görüntü/uzun arama/düşük bant/senkron; desteklenmeyen eksik remote timestamp'de encoder varsayılanı korunur |
| Gürültü/yankı | AEC/NS donanım→tek yazılım APM yedeği factory aşamasını da kapsar; ikinci AudioFX zinciri eklenmez; kullanıcı software tercihi korunur | Aynı pipeline fallback/temizleme testleri + mevcut bağımsız AEC/NS politikası | Fiziksel sessiz/gürültülü/BT yankı ve anlaşılabilirlik A/B; dB/MOS/ANC sonucu yok; asenkron donanım arızası otomatik onarılmış sayılmaz |
| Yayın/güncelleme | OTA başlıklar geldikten sonra body soketi iptal sahibini kaybediyordu. Redirect+416 restart ve metadata/APK streaming tam tüketim boyunca ortak iptal sahibinde | Gerçek loopback HTTP, başlık sonrası bloklu body read iptalinde socket cancel+bounded completion; mevcut URL/Range/SHA/signer testleri | PR+main CI, signer, yedi tablo OTA, public APK/SHA read-back; fiziksel OTA ve bağımsız signer yedek restore |

## Sınırlar ve güvenli geri dönüş

Hesap makinesi/gizli giriş/PIN, yedi Room v4 tablosu, kimlik ve E2EE formatı,
medya aktivasyon bayrağı, backend/kota, yeni izin/ücretli servis değişmez.
Büyük dosya, Double Ratchet veya canlı SQLCipher hazır diye gösterilmez.
Mesaj doğrulama retleri loglanmaz; ham exception, özel metin, anahtar ve ağ
adresleri yeni tanılama verisine dönüştürülmez. Bozuk olay atılmış/teslim
edilmiş sayılmaz. Güvenlik hatası sonunda yine bildirilir.

Son HEAD CI→main→aynı imzalı OTA→publicAPK doğrulaması geçmeden yayımlandı
veya puan arttı denmez. Fiziksel altı alan kabulü ve cihaz dışı signer
kurtarma bu ortamda yapılamaz; bu yüzden100/100 doğrulaması yapılamaz.
Yayın kanıtı merge PR açıklamasında kesin kaynak SHA/CI/tag/SHA256 ile
kaydedilir. Geri dönüş: aynı signer ve daha yüksek versionCode ile düzeltme;
uygulama kaldırma, veriyi temizleme veya otomatik DB dönüşümü yapılmaz.
