# LANU Hesap Makinesi — mühendislik ajanı kuralları

**Tek ana karar kaynağı:** [PROJE_ANAYASASI_VE_DUZELTILECEKLER.md](PROJE_ANAYASASI_VE_DUZELTILECEKLER.md). Kod değişikliklerinden önce anayasayı ve güncel `main` / açık PR / CI / Release / issue durumunu incele. Kullanıcı yeni bir genel kural belirlediyse **önce anayasayı GitHub commit'iyle güncelle**, sonra geliştirmeye başla. Eski notların test ve yayın kanıtını güncel sayma.

## Her çalışmada zorunlu çalışma döngüsü

1. Güncel PR HEAD SHA'yı, açık P0/P1 konularını ve mevcut cihaz/veri güvenliğini denetle.
2. Yeni kullanıcı standardı/kararı varsa anayasaya ekle; tarih ve kanıt seviyesi belirt.
3. Kök nedeni ve etkilenen komşu katmanları saptayıp küçük, gözden geçirilebilir değişiklik yap.
4. Her başarılı yol için uygun başarısızlık/iptal/rollback/kısmi yazım/eski callback/kötü ağ testlerini ekle.
5. Üretim değişimini **son HEAD** CI başarıyla geçmeden birleştirme. `main` CI ve Release APK'nin gerçek yayımlanmasını ayrıca doğrula.
6. Değiştirilen dosyaları, diff'i, test sonuçlarını tekrar oku; kalan eksikleri ve engelleri kanıtla raporla.

**Koruma sınırları:** `2011.` ve isteğe bağlı PIN korunur; Room/mesaj/kimlik/rehber/geçmiş/teslim kuyruğu ve kullanıcı verisi silinmez. Destructive migration, sessiz sıfırlama, gerçek yedeği olmayan SQLCipher aktivasyonu, kanıtsız Double Ratchet, sahte WebRTC/ENC ölçümü, kişisel anahtar/sırların log/issue/repo/CI artifact'a yazılması yasaktır. Kullanıcı onayı olmadan maliyet oluşturan servis açılmaz. Gerçek fallback, gizliliğe uygun hata raporlama ve Android API23+ kapsaması gerekir.

**Sonuç ifadeleri:** `kod yazıldı`, `CI geçti`, `main'e birleştirildi`, `APK Releases'e eklendi`, `APK HTTP/SHA read-back doğrulandı` ve `iki fiziksel cihazda kabul edildi` birbirinden farklı aşamalardır. Kanıt olmayan aşamaya tamamlandı deme; puanları kanıtsız artırma.

Bu dosya anayasanın yerini **almaz**; uyumsuzluk durumunda anayasanın daha sıkı güvenlik ve veri koruma kuralı geçerlidir.
