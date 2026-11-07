# 1118 sonrası iletişim sağlamlaştırma

Başlangıç: main 7293c0769d2f7ee773abbfdbaa34fa3c963c0ba9; imzalı APK 1118;
Actions 42349683983 tüm kapılardan geçti ve public indirme/SHA/imza doğrulandı.

## G04: okundu onayında sınırlı veritabanı okuması

Açık sohbetin her senkronunda `getMessagesForConversation().first()` tüm mesaj
ve ek dosya gövdelerini belleğe alıyordu; daha sonra yalnız 200 kimlik seçiliyordu.
Artık salt okunur Room sorgusu yalnız gelen, henüz READ olmayan, ilgili sohbetin
kimliklerini `timestamp,id` sırasıyla en fazla 200 satır olarak getirir. Şema,
indeks, migration, ciphertext, geçmiş ve teslim durumları değiştirilmez.

Yeni olaylarla eski mesajların okundu onayı tekilleştirilerek aynı 200-ID ACK
sınırında kalır. Sonraki sunucu READ revizyonları geldikçe sonraki parti seçilir.
405 büyük gövdeli mesajla entegrasyon testi, tüm geçmişi isteyen DAO yolunu
bilerek engeller; 200+200+5 kimliğin doğru sıra ve sohbet sınırıyla işlendiğini,
408 toplam kaydın korunduğunu doğrular.

## G03/G09: hata nedenini koruyan indirme politikası

Sadece üst IOException sınıfına bakılması, adapter içine sarılmış TLS/sertifika,
protokol veya iptal hatasını geçici ağ hatası sanabiliyordu. Ortak indirme karar
modülü en fazla sekiz neden inceler. Sertifika/TLS, protokol, güvenlik, iptal ve
thread kesintisi tekrar edilmez. Döngülü/çok derin zincir güvenli kapanır.
Yalnız işaretlenmiş ağ okuma hatası, mevcut iki tekrar / üç girişim ve beş dakika
bütçesi içinde yeniden denenebilir; yerel disk hatası tekrar edilmez.

Başarı/kesinti testlerine ek olarak sarmalanmış TLS için tek istek + cache
silme, sarmalanmış timeout için başarılı kurtarma ve döngülü neden testleri var.
Hata metni, URL, token veya kişi bilgisi karar modülünde saklanmaz/loglanmaz.

## Koruma / yayın

Hesap makinesi, giriş/PIN, Room v4 şeması, E2EE protokolü, ses/video varsayılanı,
servis ücretleri ve büyük dosya aktivasyon kapısı değişmez. Yeni ücretli servis yok.
Son HEAD PR CI → main CI → aynı signer ile OTA → Releases APK/SHA/signer ve
anonim public read-back zorunlu. Geri dönüş gerekirse kod revert edilip aynı
sertifikayla daha yüksek versionCode yayımlanır; kaldırma/veri temizleme yapılmaz.
Fiziksel A55 + ikinci OEM ses/gecikme kabulü ve dosya boyutu artışı bu çalışma ile
kanıtlanmış/etkinleşmiş sayılmaz.
