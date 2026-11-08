# Üçlü iletişim paketi — uygulama tasarımı ve kabul sınırları

Başlangıç: main29317dc / imzalı1140. Çalışma dalı
codex/secure-communications-bundle-20261108. Kullanıcı üçünü birlikte
kullanıcı APK'sına istedi. Bu dosya tamamlanma veya yayın kanıtı değildir.
Küçük güvenlik yaması yayımlayıp bu üç özellik bitmiş gibi davranılmaz.

## Double Ratchet motoru

- Yeni kripto primitive'i yazılmaz. Apache2.0 vodozemac0.11.2 sabitlenir.
  Bu sürüm 5Kasım2026'da yayımlandı;31/33-byte bozuk anahtar panic'i ve
  birden çok anahtar zeroization eksiğini giderir. Kaynak tag0.11.2,
  commitfb4a9980a1038c646824ea7e64567eec28bf582e.
- Kullanılan algoritma Olm Double Ratchet'tır; Signal uygulaması veya
  Signal protokol uyumluluğu iddiası değildir. İki LANU uç için aynı
  SessionConfig::version_2 (tam HMAC, experimental-session-config özelliği)
  kullanılır. Denetlenmiş eski çekirdek ve yeni JNI/uygulama entegrasyonu
  aynı kabul değildir. Bağımsız entegrasyon incelemesi ayrıca gerekir.
- Native API hesap oluşturma,pre-key başlangıcı,kabul,gönderme,alma sağlar.
  Hesap/oturum sadece motorun şifreli pickle biçiminde dışarı çıkar.
  Anahtar32byte;plaintext32KiB;tek state1MiB;istek2MiB sınırları.
  JNI hata/panic mesajları sabittir; giriş,anahtar ve state yazılmaz.
- Rust1.90.0;Cargo.lock; dört Android ABI;API23;NDK28 ve16KiB ELF sayfa
  hizası gerekir. Android native ve R8 testleri geçmeden engine APK'ya
  etkin bağlanmış sayılmaz. Rust host testleri Android testi değildir.

## LANU protokolüne bağlama (henüz uygulanacak)

- P256 kimlik/kullanıcı kodu değiştirilmez. Olm Curve25519 kimliği,one-time
  prekey,generation ve katılımcı kimliği mevcut ECDSA kimliğiyle imzalanır.
  İmzasız veya farklı peer transcript'i kabul edilmez. Sadece motoru eklemek
  ileri gizlilikli gerçek mesajlaşma değildir.
- Sürüm3 desteği ve imzalı bundle sunucuda additive alanlarla tutulur;
  karşılıklı opt-in ve v1 offline queue drain/barrier olmadan pin yoktur.
  v3 kontrol olayları eski client'a LIMIT'ten önce filtrelenir. Güncellenmemiş
  karşı taraf mevcut v1'i kullanır; pinlenmiş peer'da sessiz v1 downgrade yok.
- Olm iç plaintext çerçevesi messageId,senderId,recipientId,epoch ve işlem
  türünü bağlar. Ağ zarfı mevcut P256/AES-GCM lanu2 taşımasında kalabilir;
  ağdaki iç içerik Olm ile ayrıca şifrelenir. Bozulan uzun dönem P256 anahtarı
  tek başına eski Olm trafik anahtarlarını geri getirmez. Call DTLS ayrı kalır.
- Tarih görüntülenirken ratchet decrypt tekrar çalıştırılmaz. Gelen içerik
  tek sefer doğrulanır ve kendi kimliğiyle ayrı yerel arşiv zarfına alınır;
  geçmişi görmek için tüketilmiş skipped-key tekrar gerektirilmez.
- Native state değişimi ile inbox/outbox mesajı/revision aynı Room transaction
  içindedir. Retry aynı messageId ve aynı tel ciphertext'ini kullanır.
  Yeni crypto state üretmek retry yöntemi değildir.
- NoBackup alanındaki Keystore-sarımlı anahtarlar ve şifreli write-ahead journal
  Room'dan bağımsızdır. Journal önce fsync,sonra Room commit,sonra journal
  finalize. Restart'ta DB eski ise hazır işlem aynen replay edilir,DB yeni ise
  finalize edilir; başka digest/eksik journal/state eski DB restore'u olarak
  fail-closed olur. Desteklenen DB/portable archive rollback'i ile root'un
  tüm cihaz/Keystore snapshot rollback'i aynı güvence değildir.
- Sadece ratchet private state yedekten dışlanır. Public protokol pinleri ve
  değişmez şifreli wire-outbox yeniden deneme kopyaları korunur. Yeni cihazda
  ratchet state yokken eski pinle otomatik v1 veya sessiz yeni hesap yok;
  iki taraflı açık oturum yenileme gerekir. Eski mesaj/kimlik arşivi korunur.

## Kayıpsız SQLCipher (henüz uygulanacak)

- Otomatik açılışta rastgele kullanıcı DB'sini dönüştürme yok. Kullanıcı
  tam portable yedeği seçer,parola ile izole gerçek restore doğrulanır ve
  current snapshot ile7tablo/tip/alan eşliği kanıtlanır. Disk bütçesi,aktif
  çağrı ve pending transfer/yazıcı kapıları gerekir; PIN kapalı da çalışır.
- Snapshot ayrı sürümlü SQLCipher dosyasına yazılır. Orijinal açık Room
  yazıcıları varken rename/delete yok. Bütün typed veriler,Room identity,
  user_version4,integrity/FK,encrypted header ve reopen doğrulanır.
- Canlı dönüşüm restart'ta tek ilk-open factory'sinde yapılır. O anda mevcut
  plaintext snapshot hazırlanmış snapshot'tan değişmişse aktivasyon iptal;
  kullanıcı tekrar hazırlayabilir,eski kayıtlar devam eder. Eski çalışan
  process'te alınan bir snapshot sonradan kayıp kayıtla aktive edilmez.
- Keystore-sarımlı bağımsız DB key;durable PREPARED→ACTIVATING→ACTIVE seçici;
  crash/disk-full her geçişte test edilir. ACTIVE sonrası yanlış/eksik key'de
  plaintext'e sessiz dönüş veya yeni key üretimi yoktur.
- Eski APK'nın açık kaynak dosyasını açıp yeni kayıtları kaybettirmesini
  engelleyen downgrade işareti ve telafi yolu gerekir. Çok dosyalı rename
  atomik sayılmaz. Şifreli DB aktifken yeni yazıları eski dosyaya rollback yok.
- Plaintext eski kopya dururken tam at-rest koruma iddiası yoktur. Bağımsız
  kurtarılabilir şifreli yedek ve kullanıcıya ait açık son onay olmadan eski
  dosya kaldırılmaz. Flash'ta fiziksel secure erase garantisi verilmez.
- Key kaybında calculator motoru/veri dosyaları korunur; özel veri erişimi
  fail-closed ve kullanıcı kontrollü backup recovery gerekir. Çöküp kapanma,
  otomatik boş DB,destructiveMigration veya PIN bypass kabul edilmez.

## Büyük dosya (henüz tam etkinleştirilecek)

- Mevcut40MiB plaintext/41,953,280cipher byte bütçesi ve private bucket;
  iki peer capability;signed grant→upload→server SHA→immutable ready→E2EE
  manifest→authorized download→hash+GCM→explicit SAF zinciri tamamlanır.
- Anahtar/isim/source URI/public URL server'a girmez. Legacy alıcı yeni medya
  manifestini göremez; mevcut18KB inline aktarım bozulmaz.
- TUS kayıp ACK/HEAD offset veya tek PUT sonrası doğrulanmış complete recovery,
  bounded retry,progress,cancel,expired grant,quota veTTL ciphertext cleanup.
  Metadata tek başına silinip quota boşalmış gibi gösterilmez.
- Temizlenmiş JPEG/PNG,PDF/TXT ve doğrulanmış Office;MP4 için metadata/konum
  riski ve özgün video paylaşım onayı ile doğrulanmış bounded hazırlama.
- UI flag/server flag sadece bu uçtan uca akış kabul edildiğinde etkin olur.
  Bir flag'i true yapmak veya limit sayısını artırmak geliştirme değildir.

## Yayın kapısı

Host/JNI/Rust→Kotlin/Room→API23+34/R8→negatif restore/downgrade/low-disk/
crash→relay/storage E2E→son HEAD CI→main CI→same-signer OTA→public APK/SHA/
signer read-back. Gerçek iki telefon ve bağımsız inceleme kanıtı yoksa bu
sınır açıkça raporlanır;fixture,emülatör veya hedef puan bu kanıt olamaz.

Kaynaklar:
https://github.com/matrix-org/vodozemac/releases/tag/0.11.2
https://github.com/matrix-org/vodozemac/blob/0.11.2/src/olm/session_config.rs
https://github.com/matrix-org/vodozemac#security-notes
https://www.zetetic.net/sqlcipher/sqlcipher-for-android-community/
