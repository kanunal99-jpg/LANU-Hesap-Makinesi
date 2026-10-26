# LANU Android — Kalıcı imza anahtarının güvenli yedeği (Termux)

**Önem:** Telefon/Samsung A55 veya Termux kaybolursa, sabit Android sertifikası olmadan mevcut LANU APK'ları üzerine **veri kaybı olmadan güncelleme yapmak mümkün olmayabilir**. GitHub Actions Secrets içeriği sonradan okunabilen bir yedek değildir.

## Uygulama ve güvenli varsayılan

Bunun için `scripts/backup_stable_signing_encrypted.sh` geliştirildi. Şunları sağlar:

1. **Var olan** `$HOME/.lanu/signing/lanu-stable.jks`, `store-password` ve `key-password` dosyalarını kontrol eder; eksikse **anahtar yenilemez veya üzerine yazmaz**.
2. Şifreleme şifresini Termux konsolunda **gizli (echo olmadan)** ister ve ikinci kez doğrular. Şifre **en az 20 karakter** olmalıdır; Android PIN'i, sohbet parolası veya GitHub hesabının mevcut şifresi **kullanılmamalıdır**. Hiçbir şifre terminal çıktısında/komut argümanında gösterilmez.
3. `tar` akışını **düz metin arşiv dosyası oluşturmadan** GnuPG AES-256/SHA-512 S2K ile `~/.lanu/backups/*.tar.gz.gpg` dosyasına şifreler; özel dizin 700, arşiv 600 izniyle tutulur.
4. Aynı arşivi geçici ve izole 700 dizinine çözer, **üç dosyanın her byte'ını** orijinalleriyle karşılaştırır, geçici açık dosyaları siler. Başarısız olursa yedek tamamlandı mesajı vermez.
5. Çıktı yalnız telefonun özel alanındadır; **telefon dışına kopyalama işlemini kullanıcı kendisi yapmalıdır**. Ücretli servis, internet API'si, GitHub yüklemesi veya otomatik yedekleme yoktur.

## Samsung A55 / Termux çalışma adımları

```bash
pkg install -y gnupg tar coreutils
cd ~/LANU-Hesap-Makinesi
git pull --ff-only
bash scripts/backup_stable_signing_encrypted.sh
```

Araç, iki kez yeni bir uzun parola ister. Parolanızı **bu sohbete göndermeyin**. Sonunda yalnız **şifreli arşiv dosyasının yolu** görüntülenir.

**Henüz cihaz dışı yedek oluşturuldu sayılamaz:** şifreli `.gpg` dosyasını kaybolabilecek telefonun dışında ikinci bir güvenli yerde (örneğin güvenilir taşınabilir disk) saklayın. Parolayı farklı bir güvenli yerde tutun. Açık ZIP, keystore, password dosyaları veya yeni `*.gpg` şifresini hiçbir GitHub repository'sine, Releases'e, e-postaya veya ChatGPT'ye yüklemeyin.

## Geri yükleme

Mevcut telefon veya yeni telefon üzerindeki **orijinal imza dosyalarını asla otomatik değiştirmeyin**. Şifreli yedeği başka güvenilir cihazda izole geçici dizinde çözerek dosya eşitliği ve `keytool` sertifika SHA-256 kimliği doğrulanmadan repo secret'larını veya APK sertifikasını yeniden yapılandırmayın. Şifreli dosyayı telefon dışına kopyalayıp tekrar açabildiğiniz doğrulanmadan [#83](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/issues/83) kapatılamaz.

## Otomatik test

`scripts/test_backup_stable_signing_encrypted.sh` yalnız **sahte keystore/parola** üreterek terminali simüle eder, AES-256 arşiv oluşturma, GPG ile yeniden açma, byte eşitliği ve 600 arşiv iznini doğrular. GitHub CI'ya bağlanmıştır. **Bu test gerçek kullanıcı anahtarının cihaz dışında güvenle korunduğunu kanıtlamaz.**

## 24.10.2026 — İkinci cihazda sertifika kurtarma doğrulaması

Yeni `scripts/verify_offdevice_signing_recovery.sh` yalnızca **şifreli GPG yedeği**
ve resmi GitHub Release'ten alınmış `app-debug.apk.signer.txt` dosyasını
kullanarak geri yüklemeyi ayrı/izole bir alanda doğrular.

1. Yedeği Termux/telefon dışındaki güvenilir bir bilgisayara veya başka özel cihaza
   taşıyın. **Yalnızca `.gpg` arşivini taşıyın**; açık keystore ve parola dosyalarını
   asla GitHub'a/e-postaya/sohbete göndermeyin.
2. Güvendiğiniz LANU Release sayfasından (örneğin
   `v1.1.0-signed.363`) **`app-debug.apk.signer.txt`** dosyasını o cihaza
   indirin. Dosyanın beklenen resmi sürüme ait olduğunu bağımsız doğrulayın.
3. Güvenilir cihazda GnuPG, GNU tar, JDK `keytool` ve OpenSSL kurulu olmalı.
4. Arşivle aynı kullanıcıya ait yerel terminalde, dosya adlarını kendi
   konumlarıyla değiştirerek çalıştırın:

```bash
bash scripts/verify_offdevice_signing_recovery.sh \\
  /güvenli/yol/lanu-signing-YYYYMMDD.tar.gz.gpg \\
  /güvenli/yol/app-debug.apk.signer.txt
```

Şifre **yalnızca terminalde gizli girişle** istenir; komut satırına veya
sohbete yazmayın. Betik şifreli arşivi **yalnız üç normal dosyaya** sınırlar,
geçici izole alana açar, `lanu` imza sertifikasının SHA-256 parmak izini
resmi stable APK'nin SHA-256 sertifika iziyle karşılaştırır. Eşleşmezse
**başarısız olur**; var olan orijinal imza anahtarının üstüne asla yazmaz.

Bu ayrı cihaz işlemi gerçekten yapılıp sahibince doğrulanmadan **#83
kapatılamaz**. Flash depolamadaki geçici düz dosyaların silinmesi güvenli
fiziksel imhayı garanti etmez; mümkünse güvenilir belleğe dayalı geçici
dizin kullanılır. CI testi **yalnız sahte PKCS12** anahtarla çalışır.

## 26.10.2026 — Gerçek imza atabilme doğrulaması (yalnızca izole kurtarma)

`verify_offdevice_signing_recovery.sh` artık yalnızca PKCS12 özel anahtar girdisini ve sürümdeki açık sertifika parmak izini eşleştirmekle kalmaz; kurtarılan **özel anahtar ile geçici bir JAR imzalar**, bağımsız `jarsigner -verify` ile doğrular. Geçici arşiv ve imzalı dosya güvenli çalışma dizininde silinir; parola yalnız süreç ortamındaki gizli değişkende taşınır, anahtar export edilmez. `jar` ve `jarsigner` için güvenilir JDK gereklidir. Test, sahte CI anahtarıyla çalışır; gerçek cihaz anahtarı hiçbir zaman GitHub'a yüklenmez.

**Bu test tek başına #83'ün kapanması için yeterli değildir.** Kullanıcı, şifreli .gpg yedeğini gerçekten telefon dışına aktarıp farklı güvenilir fiziksel ortamda kurtarmalı ve son stabil APK ile eşleşmeyi doğrulamalıdır. Yeni anahtar üretmeyin, orijinal `~/.lanu/signing` üzerine yazmayın.
