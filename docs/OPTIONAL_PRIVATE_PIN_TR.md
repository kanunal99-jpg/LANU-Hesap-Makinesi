# LANU — 2011. girişinden sonra isteğe bağlı PIN

## Kullanıcı talebi (23 Ekim 2026)

PIN tercihi **medya seçicisine özel değil**, hesap makinesindeki `2011.` girişinden sonra açılan gizli alanın **ilk erişimine** uygulanmalıdır.

## Davranış sözleşmesi

- Eski kullanıcıda PIN zaten varsa ayar **açık** kalır. Koruma kapatmak için **mevcut PIN doğrulanır**, başarısız deneme/lockout sınırı devam eder.
- Kullanıcı PIN korumasını kapatırsa PIN **silinmez**, sohbetler/veriler korunur, gizli alana yeniden `2011.` ile girilebilir ve **ek PIN sorulmaz**.
- Ayarı yeniden açmak mevcut PIN'i etkinleştirir. **PIN Değiştir** düğmesi açık/kapalı tercihten bağımsız çalışır.
- Henüz PIN oluşturmamış kullanıcı ilk kez `2011.` girince zorla PIN kurmaya yönlendirilmez; isteğe bağlı kurulum Ayarlar'dadır.
- `2011.` dışı hesap makinesi başlığı gibi alternatif gizli alan girişleri kaldırılır. Uygulama arka plana veya telefon ekranı kilidine geçtiğinde gizli alan yeniden gizlenir; PIN kapalıysa tekrar giriş için `2011.` gereklidir.
- PIN kapalıysa kullanıcı güvenlik riski hakkında Ayarlar'da açıkça bilgilendirilir. Screenshot koruması ve mevcut kimlik/şifreleme/depolama ayrı olarak korunur.
- Bozuk ayar güvenli varsayılana (PIN gerektir) döner, yetkisiz doğrulama bypass edilmez.

## Görsel iyileştirmeler

- Material 3 yükseltilmiş gizli alan güvenlik kartı, erişilebilir PIN anahtarı ve onay pencereleri.
- Sohbet ekranı kimlik kartı yenilenmiş, kopyala/paylaş düğmeleri ve hata fallback eklenmiştir.

## Test ve durum

GitHub [PR #94](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/pull/94), [Issue #93](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/issues/93). En az 8 yeni Robolectric/JUnit testi, Android 14 Room veri-koruma, kalıcı signer ve public APK read-back; A55 üzerinde kullanıcı kabulü tamamlanmadan **cihazda doğrulandı** denmez.

## Ayrı ve tamamlanmamış iş

64 MiB parçalı şifreleme çekirdeği bulunmasına rağmen, **büyük fotoğraf, PDF, PPT/PPTX ve Excel/XLS/XLSX dosyaları göndermek henüz mümkün değildir**. Özel nesne depolaması, E2EE medya anahtarı teslimi, alıcı yetkisi, tekrar deneme, TTL/temizlik ve iki cihaz kabulü gerekir; [Issue #85](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/issues/85).
