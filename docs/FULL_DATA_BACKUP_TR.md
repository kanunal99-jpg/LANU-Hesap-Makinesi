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
