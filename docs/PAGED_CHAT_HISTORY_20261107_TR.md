# Uzun sohbet geçmişi — gerçek E2EE ile sayfalama

Başlangıç main9215314b650429c004fc8105025dd9b6b7e86e9c, imzalı1129.
1129'un eksik ek bağımsız kontrolü tamamlandı: public APK61,722,506bayt,
SHA25611f77b5321c30ced5d64091eb76304f4b5ef79989e399df69d9d44b109953be2,
portable checksum ve1126 signer eşleşti; kurtarma kodu DEX paketinde doğrulandı.

## E13 kök nedeni ve gerçek çözüm

Sohbet ekranı tüm geçmişin gövdelerini Room'dan alıp her değişiklikte hepsini
çözüyordu. Artık gerçek E2EE çözme sayfalara uygulanır: ilk100, sonraki50,
prefetch10, maxSize300 hedefi. AndroidX Paging3.3.6 mevcut Compose/Room ile
sabitlendi. maxSize kütüphanenin best-effort hedefidir; katı300nesne, fiziksel
gecikme veya ANR garantisi değildir. Eski kayıtlar silinmez veya erişimi kesilmez.

SQL keyset timestamp+id sırasını kullanır; OFFSET ve tüm geçmiş COUNT/body
okuması yoktur. Eş zamanlı kayıtlar deterministik ID sırası; append/prepend
sınırlarında tekrar yok; refresh gerçek mesaj pivotunu kullanır. Araya250/500
yeni mesaj girse de eski pivot tutulur; silinirse yakın komşu kullanılır.
Yeni indeks/Room şema göçü yoktur; çok büyük arşivde sorgu süresi ayrıca fiziksel
ölçülmelidir. UI en yeniyi altta, geçmişi yukarı yükler. Tarih okurken yeni
ileti aşağı atmaz; görünür eski en yeniye gelen büyük burst takip edilir.
Kimlik-only latest sorgusu, en yeni sayfa cache'de olmasa da doğru başa dönmeyi
sağlar. En yeniye git taslağı/saklı kayıtları sıfırlamaz.

Başarısız gönderim menüsü, plaintext geçmiş yerine en eski20kendi FAILED
kimliğini sorgular. Önceki20'lik batch, aynı ID/ciphertext, kişi/sohbet ve
retry doğrulaması korunur. Eski başarısız mesajın sayfasının yüklenmesi gerekmez.
Teslim/okundu/outbox protokolü ve kota değiştirilmez.

## Yaşam döngüsü, gizlilik ve hata

Cache, metadata akışları ve Room observer'ları açık sohbet job'una aittir.
Ayrılma/seçim değişimi bunları kapatır; Pager flow finally kaynakları invalidate
ederek observer'ı kaldırır. Eski callback ekranı yeniden dolduramaz. Eski
PagingData cache'de anlık kalsa bile satır active/screen/rowConversation
üçlü eşleşmesi olmadan çizilmez. Eski kişiye gönderim/retry yoktur.

DB IO'da, E2EE çözme Default dispatcher'da. İptal çözme hatası diye yutulmaz.
Authenticated medya anahtarı Compose'a taşınmadan önceki marker'a çevrilir.
MessageEntity ve cursor toString içerik/ID/zamanı gizler; ham SQLite exception'ı
Paging/UI'ya verilmez. Hata kullanıcıya sabit tekrar yükleme akışı sunar.
Hesap makinesi, giriş/PIN, Room v4, E2EE zarfı, kimlik/signer, backend, medya
aktivasyon bayrağı, ücret/izinler korunur.

## Kabul ve sınırlar

Test kapsamı: gerçek Room ve Android debug/release20,000kayıt, eş timestamp,
sayfalarda eksiksiz/takrarsız tarama,100satır load sınırı, yabancı sohbet reddi,
pivot+250/500yeni kayıt, silinen pivot, observer iptali ve eski FAILED20ID.
Repository200gerçek E2EE mesajı sayfalarla çözer, diskte ciphertext korunur.
Compose: tarih okuma+200yeni satır, yeni takip, En yeniye git, yanlış sohbeti
göstermeme, güvenli hata/retry. Eski yaşam döngüsü testi boş Paging snapshot
ile korunur. Son HEAD CI→main CI→imzalı OTA→public APK/SHA/signer geçmeden
kabul/yayın iddiası yoktur. Kesin sürüm/CI/hash kanıtı merge PR açıklamasında.

Canlı SQLCipher, Double Ratchet/bağımsız inceleme, cihaz dışı signer kurtarma
ve iki fiziksel telefon FCM/BT/RTP/video/gürültü/gecikme/OTA kabulü ayrı kalır.
Bu geliştirme100/100 sertifikası değildir. Rollback aynı signer+daha yüksek
versionCode'lu revert APK; uninstall/veri temizleme veya eski kayıt silme yoktur.

Resmî API dayanağı:
https://developer.android.com/jetpack/androidx/releases/paging#3.3.6
https://developer.android.com/topic/libraries/architecture/paging/test
https://developer.android.com/reference/kotlin/androidx/paging/PagingConfig
