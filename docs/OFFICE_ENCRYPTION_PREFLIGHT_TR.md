# LANU — Excel/PowerPoint/Word büyük dosya ön doğrulaması (24.10.2026)

Bu aşama, yalnız makrosuz modern Office Open XML (.xlsx / .pptx / .docx)
dosyalarının yerel, özel uygulama alanına kopyalanmış sürümlerini
AES-256-GCM ile şifreleme öncesinde denetler.

- Güvenli MIME listesi: `application/vnd.openxmlformats-officedocument.spreadsheetml.sheet`,
`application/vnd.openxmlformats-officedocument.presentationml.presentation`,
`application/vnd.openxmlformats-officedocument.wordprocessingml.document`.
- ZIP başlangıç imzası, içerik türleri parçası ve doğru belgeye ait
`xl/workbook.xml`, `ppt/presentation.xml` veya `word/document.xml` doğrulanır.
- 64 MiB kaynak dosya, 128 MiB merkezi dizin şişirme toplamı, en fazla 2048
ZIP girdisi, sıkıştırma oranı ve yol güvenliği sınırları uygulanır.
- Dosya hiçbir zaman ZIP olarak dışa açılmaz. Makro girdileri ve uygulama dışı
güvensiz yollar reddedilir. Veriler şifreli dosya dışında kalıcı yazılmaz.
- Rastgele dosya anahtarı/nonce ile AES-GCM 64 KiB parça şifrelemesi,
bütünlük SHA-256 ve çözerken GCM doğrulaması mevcut katmandan kullanılır.
- Doğrulanmamış bir `InputStream` üzerinden Office MIME verilmesi güvenli
biçimde reddedilir; özel alanda dosya doğrulanması zorunludur.

## Sınırlar

Bu **tam dosya gönderimi değildir**. Gönder/al, Supabase private Storage
yetkilendirmesi, alıcı bazlı erişim, kota, tekrar deneme, SAF dışa aktarma,
güvenli önizleme ve iki gerçek telefon testi hâlâ #85 kapsamındadır.
`[Content_Types].xml` kontrolü yalnız yapısaldır; zararlı içerik taraması
veya tam OOXML parser yerine geçmez. Eski .xls/.ppt, parola korumalı
dosyalar ve makrolu .xlsm/.pptm desteklenmiyor.

Mevcut 18 KB küçük ek gönderimi, Room v4, PIN tercihi, kalıcı imza ve
var olan iletişim işleyişi değişmez. Bu altyapı ancak testler geçen PR
birleştirildikten sonra depodaki `main` üzerinde var sayılmalıdır.
