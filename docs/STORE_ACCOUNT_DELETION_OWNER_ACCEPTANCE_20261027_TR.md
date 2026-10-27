# LANU Hesap Makinesi — Mağaza hesabı/veri silme zorunlu kabulü

**Durum:** 27 Ekim 2026 — **AÇIK / ÜRETİMDE KANITLANMADI**. Bu dosya yayımlanmış gizlilik politikası, hazır silme endpoint'i veya hukuki onay değildir. Android paket: `com.aistudio.lanucalc.kxwzqm`.

## Teknik gerçekler ve risk

- LANU mesajlaşması Supabase relay üzerinde kullanıcı kodu, karşılıklı kişi bağlantısı, açık anahtar, teslim bilgisi ve uçtan uca şifreli gövde metaverisini işler. Uygulamada sohbeti yerel sil/gizle, uzak cihazın veya sunucunun verilerinin tamamını silmekle aynı değildir.
- Bugünkü kodda **doğrulanmış uçtan uca sunucu hesabı ve ilişkili verilerin silinmesi** kabulü yok. Silme talebi ekranı, güvenli kimlik doğrulama, sunucuda ilişkisel temizlik ve tamamlanma kanıtı ayrıca tasarlanmalıdır.
- Gizli `2011.` girişi, isteğe bağlı PIN, Room v4, E2EE/kimlik, imza ve mevcut kişilerin mesajları **silme kabulünden önce değiştirilmez**. Geri alınamaz eylem kullanıcı teyidi ve kimlik doğrulaması gerektirir.
- Play Store `StorePublicationReadiness` kontrolü artık **iki ayrı eksik** olarak `ACCOUNT_DELETION_FLOW` ve `PUBLIC_ACCOUNT_DELETION_REQUEST` reddini gösterebilir. URL doğrulaması **yalnız şekle** bakar, servis varlığını/çalışmasını kanıtlamaz. İki sentetik alan olumlu olsa bile `HumanLegalReviewRequired` döner.

## Kullanıcı ve geliştirme sahibi tarafından tamamlanacaklar

1. **[SAHİP/HUKUK]** Veri sorumlusu adı/unvanı, işlevsel destek ve kişisel veri başvuru kanalı, işleme amaçları, işleme hukuki sebebi, aktarım grupları ve alıkoyma süreleri için hukuki inceleme yap. Hiçbir kullanıcı kodunu/kimlik anahtarını sohbete veya GitHub issue içine koyma.
2. **[KOD/SUNUCU]** Hesap silme gereksinimlerini bağımsız güvenlik tasarımında yaz: başka kullanıcıya ait veriyi silmeye izin vermeyen kimlik/ownership doğrulaması; kullanıcıdan geri alınamaz işlem teyidi; idempotent hata/tekrar; ilişkili sunucu tabloları/mesaj kuyrukları/FCM tokenları; yetkisiz çapraz-kullanıcı silme negatif testleri.
3. **[KOD/CI]** Yalnız sentetik fixture ile silme akışı testleri, ağ kopması, başarısız transaction/rollback, yarış koşulları ve `0 rows affected` sessiz hatasının ayrımı. Şema değişikliği öncesi eski veri tam yedek/geri yükleme kanıtı; hiçbir üretim veri silme testi yapılmaz.
4. **[SAHİP/WEB]** Kullanıcının hesabını ve ilişkili verilerinin silinmesini **talep edebileceği işlevsel kamuya açık HTTPS sayfası** oluştur; güvenli kullanıcı doğrulama/talep/yanıt hattı tanımla; erişilebilir URL'yi doğru Play Console beyanıyla ilişkilendir. Alan adı, içerik ve erişilebilirlik ayrı doğrulanmadan `ready` olarak işaretlenmez.
5. **[SAHİP/HUKUK]** Verilen hizmetin `KVKK m.10` aydınlatma gerekleri, gerçek silme/retention sınırlamaları, üçüncü taraf aktarımlar ve Google Play Data safety beyanını birbirine tutarlı biçimde onayla.
6. **[CİHAZ]** Gerçek iki telefonda `2011.`+isteğe bağlı PIN, silme isteği izni/kimliği ve kalan kişinin güvenliği; gizli sohbet yerel görünürlüğü ile sunucu silmesini karıştırmayan açık kullanıcı bilgilendirmesi.
7. **[YAYIN]** Son PR HEAD CI, merge sonrası yeni main CI, release SHA256 + kalıcı sertifika + herkese açık dosya yeniden indirme; gerçek cihaz üzerinde kurulu kullanıcı verisini koruma. Bu kontroller tamamlanmadıkça mağaza/kategori için 70 puan ya da `Play hazır` iddia edilmez.

## Kaynaklar (27.10.2026'da kontrol)

- [Google Play User Data Policy](https://support.google.com/googleplay/android-developer/answer/10144311): hesap açılan uygulamalar için hesap ve ilişkili veri silme yöntemi, erişilebilir gizlilik politikası ve doğru Data safety beyanı.
- [Google Play Data Safety açıklamaları](https://support.google.com/googleplay/android-developer/answer/10787469): farklı sürümlerde veri işleme kapsamının etiketlenmesi.
- [KVKK aydınlatma yükümlülüğü](https://www.kvkk.gov.tr/Icerik/2033/Aydinlatma-Yukumlulugu-): veri sorumlusunun kimliği, işleme amacı, aktarım, yöntem/hukuki sebep ve ilgili kişinin hakları.

**Güvenlik sınırı:** Uygulamada henüz çalışmayan sunucu silme, hukuken onaylı politika, Double Ratchet ve tam SQLCipher **varmış gibi tanıtılmaz**.
