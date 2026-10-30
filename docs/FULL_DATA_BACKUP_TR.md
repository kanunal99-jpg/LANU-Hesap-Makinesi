# LANU tam şifreli yedek

Kimlik dosyasından ayrı `.lanubak` arşivi; kimlik anahtarları, yedi Room v4 tablosu, şifreli mesajlar, küçük inline ekler, kişiler, hesap/arama geçmişi, ayarlar ve şifreli metin taslaklarını kapsar. Cihaza bağlı biyometrik izinler veya APK imza anahtarı bu arşivin parçası değildir.

## Kullanım

Gizli alan > Ayarlar > Tam şifreli yedek. En az 12 karakterli parolayı iki kez girin; Android dosya kaydedicisinde telefon dışında güvenli bir yer seçin. Parola dosyanın yanında saklanmamalı. Yazılan dosya yeniden okunur, SHA-256 karşılaştırılmadan başarılı kabul edilmez. Yeni cihazda dosyayı seçip gizli alanın Ayarlar bölümünde parolayla geri yükleyin.

## Güvenlik ve veri korunumu

- AES-256-GCM; her arşiv için rastgele 16 bayt salt ve 12 bayt nonce. PBKDF2-HMAC-SHA256 210.000 tur; dosya başlığı AEAD AAD kapsamındadır. Dosyadan değişken KDF maliyeti kabul edilmez.
- GCM etiketi doğrulanmadan JSON ayrıştırılmaz ve hiçbir kayıt içe aktarılmaz. Boyut 32 MiB, kayıt sayısı 100.000, JSON derinliği 12 ile sınırlıdır. Takılan dosya sağlayıcısı güvenli reddedilir.
- Dosyada yalnız sabit yedi tablo ve sabit sütun sıraları kabul edilir; dosyadan SQL/tablo/sütun adı çalıştırılmaz. Uygulama içi geçici dosya yalnız şifrelidir. Parola/anahtar/plaintext içerik kaydedilmiş ekran durumuna veya günlüğe yazılmaz.
- Restore mevcut kayıtları silmez veya değiştirmez. Aynı kimlikte aynı satır tekrar eklenmez; çakışan sohbet/kişi/mesaj kayıtları bütün işlemi durdurur. Farklı hesabın arşivi mevcut sohbetlere bağlanamaz.
- Yerel sayısal hesap/arama kayıt ID'leri taşınabilir kimlik değildir; içerik eşitliğiyle birleştirilir ve gerekirse yeni yerel ID alınır.
- Mevcut PIN, etkinlik tercihi ve kalıcı deneme kilidi korunur. Önceki PIN tercihi örtük olsa bile yedekteki kapalı tercih cihazın mevcut korumasını kapatamaz.
- Ön kontrol, tüm eklemeler ve geri yüklenen kimlik sahipliği işareti tek Room transaction'ındadır. Kimlik korumalı depoya geri okunarak yazılmadan veritabanı commit edilmez. Hata halinde önceki kimlik geri konur; sahiplik işareti olası bir Keystore geri dönüşünde arşivin başka anahtara bağlanmasını engeller.
- Otomatik Android backup ve device transfer kapalı kalır. Kullanıcı dosyayı/parolayı kaybederse bu akış kurtarma garantisi vermez. Mevcut Room dosyasının bütünü bu değişiklikle şifrelenmez.

## Doğrulama

`FullDataBackupTest`: gerçek SQLite/Room yedi tablo ve kimlik/draft/pending içerik round-trip; mevcut PIN ve yerel hesap geçmişi korunumu; idempotent tekrar restore; yanlış parola/kurcalama/KDF reddi; SQLite trigger ile çok tablo rollback; farklı hesap reddi; korumalı kimlik yazma hatası ve takılan sağlayıcı.

Android CI, lint ve API34 emülatör kapıları zorunludur. Samsung A55 ile ikinci fiziksel telefonda dosyaya kaydetme, dosyayı geri seçme, iki parola doğrulaması ve geri yüklenmiş mesajı açma kabulü ayrıca gerekir. Kod/birim testi fiziksel cihaz kabulü olarak gösterilmez. Kaynak kayıtlar kabul tamamlanana kadar korunmalıdır.

## 28.10.2026 — Sessiz kayıt kaybı için geri yükleme okuma doğrulaması

- `FullDataBackup.verifyImported` yedi tablodaki yedek satırlarının gerçekten SQLite tarafından yazıldığını **tek Room transaction'ı içinde COMMIT öncesi** denetler. Mesaj/kişi/sohbet kolonları birebir; arama/hesap geçmişi cihaz-yerel ID yerine içerik eşitliği; PIN/ayar tercihleri overwrite edilmeksizin anahtar korunması; profil kimliği ayrıca kontrol edilir.
- `INSERT OR IGNORE` veya SQLite trigger ile satır sessiz atlanır ya da şifreli mesaj değiştirilirse restore tamamlanmaz, Room rollback yapar, Keystore kimliği önceki hesaba geri döner. Veriler, PIN ve 2011. korunur.
- İzole regresyon: atlanan mesaj, sonradan değiştirilen mesaj, atlanan arama kaydı, atlanan rehber kaydı. Bunlar fiziksel cihaz veya tam SQLCipher şifreleme kabulü yerine geçmez; canlı SQLCipher migration otomatik etkinleştirilmez.

### Hesap kimliği bağlama koruması

Room tablosunda hiç mesaj/kayıt kalmasa bile `lanu_draft_*`, `lanu_peer_*`, `lanu_retry_after_*`, `lanu_hidden_*`, revizyon/uzak kayıt ve son restore marker'ları önceki E2EE kimliğine bağlı olabilir. `SettingsDao.hasIdentityBoundSettings()` bu durumu tek SELECT EXISTS ile algılar; yedekte **farklı** kimlik varsa restore reddedilir. PIN-only yeni kurulumlarda güvenli restore ve aynı-kimlikte tekrar geri yükleme korunur. Sekiz farklı marker ile Robolectric regresyonu eklendi.

### 28.10.2026 — Bozuk SQLite üzerinden yanıltıcı yedek alınmasını engelleme

- Tam yedek **yalnız kullanıcı talebiyle**, tutarlı Room transaction'ı içinde `PRAGMA quick_check(1)` ve `PRAGMA foreign_key_check` salt okunur kontrolden geçer. Bozuk indeks/sayfa veya FK uyumsuzluğu bulunursa güvenilirmiş gibi yeni bir arşiv üretmek yerine işlem genel/mahrem hata ile durur. Kullanıcının mevcut SQLite dosyası, WAL ve E2EE kimliği silinmez veya onarılmaya çalışılmaz.
- Android başlangıcında rutin tam tablo taraması yapılmaz. Negatif sentetik fixture'lar boş/belirsiz/çoklu quick_check sonucu, bozuk referans ve sızıntısız hata metnini doğrular. Bu **veri kurtarma aracı veya tam veritabanı şifrelemesi değildir**; fiziksel cihazdaki gerçek dosya bozulması kabulü gerektirir.
