# LANU Android — gizlilik ve yayın hazırlığı (19.10.2026)

Bu belge `AppInformationDialog.kt` içindeki uygulama içi bilgilendirmelerin teknik dayanağını ve **açık kalan** yayın gerekliliklerini ayırır. Hukuken onaylı bir gizlilik politikası veya kullanım sözleşmesi değildir.

## Bu sürümde gerçekten uygulananlar
- Android `android:allowBackup=false`; Android 11 ve önceki otomatik yedekleme XML'inde uygulama kökü hariç.
- Android 12+ cloud-backup ve device-transfer XML'lerinde uygulama kökü hariç. OEM davranışları fiziksel cihazda ayrıca kontrol edilmeli.
- Kamera ve RECORD_AUDIO izinleri manifestten çıkarıldı; bağımlılıklar Android manifest birleştirmesinde bu izinleri tekrar getiriyor mu, Robolectric ile kontrol edilir. Sesle matematik girişi harici Android RecognizerIntent uygulamasına devredilir; LANU kendi mikrofon kaydını yapmaz.
- Gizli alan Ayarlar menüsünden açılan Türkçe Veri Kullanımı, Kullanım Koşulları ve SSS bilgi ekranları. SSS yalnız metin mesajlaşmasının desteklendiğini; arama/ortak çalışma demolarını ve bildirim olmadığını belirtir.
- Fiziksel cihazda farklı yazı boyutları, TalkBack, kontrast ve klavye gezinmesi doğrulanmadı. Önemli anahtarlar için açıklayıcı erişilebilirlik açıklamaları eklendi.
- Bu değişikliklerde kullanıcı davranışı izleyen analitik SDK'sı veya yeni ağ uç noktası eklenmedi.

## Verinin kapsamı
- LANU mesajlaşma servisi cihazdan şifrelenmiş metin gövdeleri alır; LANU kodu, açık anahtar, karşılıklı kişi ilişkileri, zaman ve teslim durumu gibi metaverileri yönetir.
- PIN/kimlik anahtarı cihazın güvenli depolama yöntemlerinde tutulur. Room veritabanının tamamı için ayrıca SQLCipher eşdeğeri şifreleme yapılmamaktadır; bunu kullanıcıya varmış gibi söylemeyin.
- Uygulama kimliği manuel güçlü parola ile dışa aktarılabilir. Manuel yedek **mesaj ve rehber arşivini taşımaz**.
- 10 saniyelik gizleme yalnız yereldir. Uzaktan tüm veriyi silme veya hesap silme uç noktası henüz mevcut değildir.
- Gizli alan önde/açık değilken mesaj bildirimleri planlanan bu sürümün kapsamı dışındadır.

## Yayınlanmadan önce gerekli insan/hukuk işlemleri
- Veri sorumlusunun doğrulanmış adı/unvanı, iletişim adresi, destek kanalı ve kullanıcı talepleri adresi eklenecek.
- Hukuki dayanaklar, hangi verilerin hangi amaçla işlendiği, aktarım ve saklama süreleri doğrulanacak.
- Supabase veri işleme/konum/altyapı kayıtları ve ilgili üçüncü taraf hizmetlerinin beyanı güncellenecek.
- Resmî gizlilik politikası HTTPS üzerinde herkese açık kalıcı bir bağlantıda yayımlanacak; Android mağaza veri güvenliği beyanı ile uyumu incelenecek.
- Nihai kullanım şartları hukuki incelemeden geçecek. Mevcut uygulama içi metinler koşul taslağıdır; gerçek sözleşme gibi sunulmayacak.
- Gerçek iki telefonda mesaj, yedekleme, silme ve gizlilik doğrulaması; TalkBack, ekran döndürme, küçük/büyük font ve açılış/senkronizasyon gecikmesi ölçülecek.
- APK sürümleri arasında **sabit imza** sağlanmadan eski debug APK'nin üstüne veri kaybı olmadan kurulum vaadi verilmeyecek.
- İleride Room şema değişikliğinde açık migration oluşturulacak; destrüktif migration fallback geri eklenmeyecek.

## Neden web SEO dosyaları eklenmedi?
`sitemap.xml`, `robots.txt`, `canonical`, `favicon`, `llms.txt` ve özel web `404` sayfası, bu native Android paketi için işlevsel özellik değildir. Kamuya açık bir ürün web sitesi oluşturulursa onun projesinde ele alınır.
