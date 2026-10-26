# LANU — Yalnız gelen sesli/görüntülü çağrı için arka plan bildirimi

## Kapsam ve varsayılan gizlilik

Kullanıcı iki fiziksel cihazda gerçek sesli/görüntülü görüşmenin çalıştığını doğruladı. Bu ayrı bir kullanıcı saha beyanıdır; **arka plan bildirimlerinin** iki telefonda çalıştığı henüz kanıtlanmış değildir.

Bu özellik **varsayılan olarak kapalıdır**. Yalnız mevcut kimliği olan kişinin gizli alan **Ayarlar → Gelen arama bildirimleri** ekranında kendi seçimiyle açılabilir. Android 13 ve sonraki sürümlerde işletim sistemi bildirim izni gerekir. Sesli/görüntülü INVITE dışındaki mesaj/sohbet/medya olaylarına bildirim **yoktur**.

Bildirim şunları göstermez: kişi adı/telefon/kod/kimlik, şifreli mesaj veya SDP, IP, PIN, gizli anahtar. Metin sadece **Gelen arama** / **LANU uygulamasını açarak kontrol edin**. Kilit ekranı içeriği `VISIBILITY_SECRET`. Bildirime basınca **hesap makinesi** açılır; kullanıcı **2011.** ve gerekiyorsa PIN/biometrik doğrulamadan geçer. Çağrı kendiliğinden yanıtlanmaz; arka planda mikrofon/kamera açılmaz.

## Üretime geçiş sırası — sırları repo/issue/CI'ye yazmayın

1. Gizli alana önceden kayıtlı E2EE kimliklerini, rehberi ve çağrıların iki cihazda çalışmasını koruyun. Var olan Android package id: `com.aistudio.lanucalc.kxwzqm`. Yeni bir kimlik/PIN zorlaması veya destructive Room migration **yasak**.
2. Mevcut Firebase projesinde Android uygulama paketini doğru kaydedin. Android Firebase istemci yapılandırması **`app/google-services.json` repoya commit edilmez**. Korunan GitHub Actions Repository Secret **`LANU_FIREBASE_CLIENT_JSON_BASE64`** değerine bu JSON'un base64'ünü yalnız GitHub ayarları üzerinden girin; **sohbete veya issue'ya göndermeyin**. `scripts/install_firebase_client_config.py` güvenilir `main` release job'unda JSON'u doğrular ve yalnız beklenen `com.aistudio.lanucalc.kxwzqm` paket kimliği eşleşirse enjekte eder; PR'lere hiçbir secret verilmez. Secret yoksa APK eski görüşmelerle çalışır, **arka plan bildirimleri etkinleşmez**. Firebase *service account private key* bu GitHub secret'a konulmaz; yalnız Supabase Edge Secrets'a aittir.
3. Supabase projesi `talhknjcjaijqiqwdyhf` üzerinde **önce** `20261026091500_call_only_push_opt_in.sql` additive migration test edilip uygulanmalı. RLS/devre dışı kamu erişimi/rate limit/dedup ve iptal fonksiyonları PGlite testleriyle gözden geçirilmeden dağıtım yapılmamalı.
4. Yetkili Firebase HTTP v1 service account sadece Supabase Edge secret `LANU_FCM_SERVICE_ACCOUNT_JSON` alanında tutulur. GitHub'a, bir issue'ya, loglara veya kullanıcının sohbetine servis anahtarı **yazılmaz**. FCM API erişimi ve projenin eşleşmesi doğrulanmalı. Yeni ücretli plan açılmaz; onay gerektiren maliyetli seçenek etkinleştirilmez.
5. Edge fonksiyonunu, migration aktif olduktan sonra ve sır/izinler doğru eşleştiğinde dağıtın. Sır olmadan `callWakeEnabled=false` ve sunucu mevcut foreground aramaları çalıştırmayı sürdürür; callWakeToken RPC güvenli 503 döndürür ve UI başarı iddia etmez.
6. Kullanıcı gizli alanda opt-in yaptığında önce gerçek Firebase tokenı alınır, sonra mevcut ECDSA kimlikle `callWakeToken` imzalı isteği sunucuya gönderilir; ancak gerçek başarılı kayıtla yerel `enabled=true` olur. Vazgeçme öncelikle **yerel bildirimi** kapatır; ardından uzaktaki token silinir. Sunucu erişilemiyorsa yerel kapatma devam eder.
7. İstemci `callSend` için `wake=true` değerini sadece E2EE `INVITE` sırasında ekler. Sunucu mutual contact/auth, kısa süreli ciphertext, idempotent UUID ve günlük/dakikalık rate-limit uygular. Eski relay 400 verirse istemci aynı şifreli daveti `wake` olmadan tekrar deneyerek görüşme altyapısını korur.
8. FCM veri yükü yalnız iki alan taşır: `type=lanu_call_wake_v1` ve kısa ömürlü `at` zaman damgası. Sunucu/Firebase kimlik, arayan, çağrı ID'si, SDP, anahtar veya sohbet içeriklerini görmez. Çağrı kimliği ve kabul doğrulaması yalnız güvenli alanda açılan E2EE sinyalleşmeyle yapılır.

## Test ve başarısızlıklar

- **Birim/protokol:** Android izin+opt-in kapalı, yanlış chat türü, geç gelen/zamanı geçmiş bildirim, yinelenen payload, foreground çift bildirim, token kaydı/iptali, forged imza, karşılıklı kişi olmayan arayan, SQL RLS/replay/rate-limit, FCM transport 500.
- **Gerçek iki cihaz:** A55 dahil iki OEM; alıcı LANU özel alanda, hesap makinesi ekranında, başka uygulamada, ekran kilitliyken, Wi-Fi/4G değişiminde, bildirim izinli/izinsiz, pil optimizasyonu açıkken, opt-in kapalıyken; çağrı bittikten sonra geç gelen push; bildirime dokunma sonrası `2011.` ve isteğe bağlı PIN. **APK üstüne kurulumda** mesajlar, rehber, PIN, E2EE kimliği korunmalı.
- **Çalışma garantisi yok:** Google Play Hizmetleri olmayan Huawei/GMS'siz cihazlar, zorla durdurulmuş uygulama, FCM/Google kesintisi, pil/Doze/sistem izin kısıtları, internet yokluğu. Gerçek HMS/alternatif dağıtım ve fiziksel test olmadan bu durumlara gerçek zamanlı bildirim sözü verilemez.
- **Açık sınırlama:** SDP şifreli olduğundan backend INVITE içeriğini çözmez; `wake=true` yalnız kimliği doğrulanmış, karşılıklı kayıtlı arayandan gelen istekle tetiklenebilir. Kötü niyetli kayıtlı kullanıcı bildirim tetikleyebilir; sunucu rate-limit vardır. FCM gecikmesi nedeniyle çağrı 45 sn zaman aşımını kaçırabilir. Testte bu durumu başarısızlık olarak raporlayın, başarı diye göstermeyin.
- **Sürüm kapısı:** PR HEAD CI Node/Postgres, Robolectric, Lint, R8, Android API34 başarılı → merge → main CI → stable-signed GitHub Release/APK HTTP/SHA public readback. Yeni APK üretimi ayrı, canlı migration/FCM kurulumu **ayrı**, iki cihazlı push kabulü **ayrı** aşamalardır.
