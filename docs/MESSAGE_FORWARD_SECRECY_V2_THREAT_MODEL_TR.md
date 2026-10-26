# LANU — İleri gizlilik / Double Ratchet v2 tehdit modeli ve güvenli geçiş (26.10.2026)

**Mevcut gerçek durum:** LANU v1 karşılıklı kimlikten türetilen uzun ömürlü ECDH + AES-GCM ile E2EE kullanır. Bu mekanizma bugüne kadar doğrulanan metin mesajlarını koruyabilir ancak anahtar ele geçirildiğinde geçmiş mesajlar için ileri gizlilik (forward secrecy) ve ele geçirilme sonrası güvenlik (post-compromise security) garantisi **vermez**. Double Ratchet/X3DH/PQXDH **kurulu değildir**; bu belge ve `MessageProtocolUpgradeGate` gerçek v2 mesaj göndermez.

## Üretim geçişinden önce zorunlu güvenlik koşulları

1. Tasarımı gözden geçirilmiş, test vektörleri/kimlik doğrulama kanıtı bulunan standart bir çift ratchet protokolü ve mümkünse olgun bir kütüphane seç; özgün yeni kripto tasarlama.
2. Cihaz ve konuşma bazında **imzalı prekey**, tek kullanımlık prekey, karşılıklı doğrulanmış kimlik ve açıkça bağlanmış oturum kimliği olmadan v2'ye geçme. QR/kod doğrulamasını key değişiminden sonra kendiliğinden kabul etme.
3. Başlangıçta **güçlü kimliği doğrulanmış v2 oturumu** oluşmadan `AUTHENTICATED_RATCHET_V2` bayrağını kalıcı etkinleştirme. Pasif, taklit edilmiş sürüm ilanı v2'ye geçiş yetkisi olamaz.
4. Bir eş için v2 başarıyla doğrulanıp sabitlendikten sonra eski statik ECDH v1'e **otomatik downgrade yasak**: ağ hatası, uygulama geri yüklemesi, yeni telefon, gecikmiş mesaj veya uzaktan sunucunun sunduğu şema değişikliği bunu değiştiremez. Eski peer yükseltemiyorsa güvenli ve açık hata sun.
5. Mesaj numarası, tekrar koruması, eşzamanlı iki cihaz, anahtar atlama üst sınırı, eski anahtar silme, iptal edilmiş bağlantı, geçmiş ciphertext teslimi ve benzersiz nonce davranışları adversarial testlerden geçmeli.
6. Şifreli yedekleme, ratchet durumunu tek kullanımlık oturum anahtarlarıyla güvenle eşleştirmeli; geri yüklenen eski durumun daha önce kullanılmış gönderim anahtarlarını/nonce'u yeniden kullanmasına izin verilmemeli.
7. Mevcut v1 cihazlarda kullanıcı verisi/PIN/kimlik/mesajlar **değişmeden** kalmalı; tam geriye dönük protokol karar matrisi ve karışık sürüm kabulü sağlanmalı.
8. Sentetik iki peer ile sertifikalı protokol test vektörleri + Android API23/34/36, cihaz yeniden başlatma, ağ kopması, replay, rollback ve anahtar ele geçirme testleri tamamlanmadan üretim açma.
9. Kripto algoritması, güvenlik iddiaları ve kullanıcı verilerinin işlenmesi bağımsız incelenmeden **ileri gizlilik var** diye belgelemeyin.

## 26 Ekim ilk aşamanın çıktısı

`MessageProtocolUpgradeGate` mevcut v1'i değiştirmez; yalnızca ileride v2'ye pinlenmiş bir peer'ın gizlice v1'e geri düşmesini önleyen karar matrisini kodlar ve JUnit ile sınar. Bu özellik henüz canlı mesajlaşmaya bağlı değildir. Gerçek kripto geçişi ayrı tasarım incelemesi + ayrı PR gerektirir.

Takip: [GitHub issue #114](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/issues/114).
