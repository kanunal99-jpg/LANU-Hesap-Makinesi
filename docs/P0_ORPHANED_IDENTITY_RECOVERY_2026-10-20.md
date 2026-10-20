# P0 — Kayıp kimlik ile mevcut sohbetleri koruma (20.10.2026)

## Bulgu
Önceki `ensureIdentity()` şifreli kimlik okunamadığında (veya kayıt bulunmadığında), Room'da eski mesaj, sohbet, rehber ya da çağrı kaydı kalsa bile yeni ECDSA anahtar çifti üretiyordu. Bu durum verileri kriptografik olarak eski sahibinden koparabilir.

## Uygulanan değişiklikler
- Kimlik yoksa mesaj sayısı, tüm rehber/sohbetler, çağrı kaydı, geçmişte sunucuya kayıt durumu ve LANU profili kontrol edilir.
- Bunlardan biri varsa **asla otomatik yeni kimlik oluşturulmaz**. Veriler silinmez; hata kullanıcıyı gerçek kimlik yedeğiyle geri yüklemeye yönlendirir.
- `restoreIdentity` kimlik yokken yeni hesap üretmeden çalışır. Mevcut kayıtları sahiplenmek için yedekteki LANU kodunun yerel profille eşleşmesi gerekir.
- Eski profili/mesajları taşıyan yeni doğrulanmış kimlik eski sohbet kayıtları üzerinde değiştirme yapmadan geri yüklenir.
- Sadece tamamen yeni/iletişim verisi olmayan kurulumda eskisi gibi yeni kimlik üretilebilir.
- Room v4 şeması değiştirilmedi. Yeni `COUNT(*)` DAO sorguları şemayı değiştirmez.

## Kabul testi
1. Kimlik kaydı yok + PENDING şifreli mesaj: fail-closed; yeni anahtar üretilmez, mesaj silinmez.
2. Kimlik kaydı yok + çağrı kaydı: fail-closed.
3. Kayıp kimlik + eşleşen parolalı yedek + PENDING mesaj: asıl kimlik tekrar açılır, mesaj kaybolmaz.
4. Kayıp kimlik + farklı kişinin yedeği: mevcut profil ve sohbetler korunur, üzerine yazılmaz.
5. Eski LANU profil var ancak mesaj yok: yine yeni anahtar üretme reddedilir.

## Açık kalan sınırlar
Kimlik yedeği yokken anahtar kaybı kriptografik olarak kurtarılamayabilir. Bu düzenleme anahtarı uydurmaz; verilerin üstüne yazılmasını engeller. Tarihî Room v1-v3 migration, kalıcı APK imzası, tüm yerel veritabanı şifrelemesi ve cihaz üzerinde gerçek kurtarma hâlâ P0'dır.
