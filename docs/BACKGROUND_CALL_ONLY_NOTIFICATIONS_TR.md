# LANU — Yalnız gelen sesli/görüntülü çağrı için arka plan bildirimi

## 26.10.2026 — Seçilen Firebase projesi: `gen-lang-client-0658022317`

**Maliyet/silme kuralı:** Yeni Google/Firebase projesi oluşturulmayacak. Kullanıcının seçtiği mevcut `gen-lang-client-0658022317` kullanılacak. Diğer Gemini / Willy Kilo / Google Cloud projeleri silinmeyecek; bunları silmek Firebase entegrasyonu için gereksiz ve başka uygulamalar için riskli. Kullanıcı onayı olmadan ücretli plan veya gizli veri aktarımı yok.

### Adım 1 — Android uygulamasını Firebase'e kaydet

- <https://console.firebase.google.com/u/0/project/gen-lang-client-0658022317/settings/general> → **Genel** → **Uygulamalarınız** → Android simgesi (`</>` web uygulaması **değil**).
- **Android paket adı:** `com.aistudio.lanucalc.kxwzqm` (büyük/küçük harf birebir). Görünen uygulama takma adı `LANU Hesap Makinesi` olabilir. Eski paket adı `com.lanu.sesli` veya `com.example` **yanlıştır**.
- Aynı uygulama bu Firebase projesinde zaten kayıtlıysa yeni kayıttan ziyade **mevcut Android uygulamasını** seçin.
- Android uygulamasının **google-services.json** dosyasını indirin. Bu Android istemci dosyasıdır; **Service Accounts / Generate new private key** dosyası ile karıştırmayın.

### Adım 2 — Android JSON'u GitHub Secrets'a kur

- Android telefonda Base64 dönüştürmeyi gerektirmemek için derleme hattı **ham JSON** veya eski Base64 Secret'ını kabul eder. Sadece **birini** kullanın.
- Doğrudan <https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/settings/secrets/actions> → **New repository secret** → ad: `LANU_FIREBASE_CLIENT_JSON` → değer: indirdiğiniz **google-services.json** dosyasının eksiksiz metni. İsteğe bağlı eski yöntem: `LANU_FIREBASE_CLIENT_JSON_BASE64` (base64). **İki Secret aynı anda tanımlanırsa derleme fail-closed olur.**
- Dosyayı repoya commit, public issue, CI log, sohbet veya Release'e koymayın. Testli doğrulayıcı Android paketinin ve Firebase proje kimliğinin **birebir** eşleşmesini zorunlu tutar.
- Yeni kod ana dal CI'ına geçip Secret yerleştirildikten sonra **yeni trusted main** CI çalıştırıp özellikle `Install verified Firebase client config on trusted release build` adımındaki gerçek **`secure config installed`** kaydını doğrulayın. **Unit test** içindeki benzer satır yeterli değildir. Yanlış proje/Android paketinde release durmalı.

### Adım 3 — Supabase yalnız sunucu FCM kimliği

- Aynı Firebase projesinde **Cloud Messaging → Firebase Cloud Messaging API (HTTP v1)** etkin olmalı.
- <https://console.firebase.google.com/u/0/project/gen-lang-client-0658022317/settings/serviceaccounts> → yalnız **server-side** Firebase service account erişimi/kimliği. En az yetkiyi tercih edin. Özel anahtar JSON'u varsa sadece Supabase proje `talhknjcjaijqiqwdyhf` → **Edge Function Secrets** alanına `LANU_FCM_SERVICE_ACCOUNT_JSON` ismiyle eklenebilir. Kullanıcıdan dosyayı sohbete veya GitHub'a göndermesi istenmez. Güvenlik ve mümkünse anahtarsız kimlik doğrulama tercih edilir.
- Edge v5 ve `call_only_push_opt_in_20261026` migration varlığı **otomatik olarak FCM yetkilendirmesinin çalıştığını göstermez**. Alıcı token kaydı ve gerçek uyanma testi ayrıca zorunludur.

### Adım 4 — Fiziksel kabul ve sürüm

- Ana dalın yeni SHA'sında Android unit/Robolectric/Lint/R8 + API34, public Release, imza ve APK SHA doğrulandıktan sonra güncel APK **üstüne** kurulur; veri silinmez.
- Samsung A55 ve ikinci cihazın gizli alan Ayarlar'ında **Yalnız arama bildirimlerini etkinleştir** düğmesine basıldığında `Firebase henüz yapılandırılmamış` hatası kalkmalı, signed server kaydı başarılı olmalı; Supabase `lanu_call_wake_devices` sayısı mevcut kullanıcı verisi/FCM tokenı ifşa edilmeden doğrulanmalı.
- Sadece **sesli ve görüntülü çağrı** için normal uygulama arka planı/kilitli/süreç ölmüşken görsel+işitilebilir çağrı uyarısı; metin/fotoğraf/medya için Android sistem bildirimi **yok**. Kullanıcı sesli/sessiz/DND, çağrı iptali, 2011./PIN, ekranda gizlilik ve gerçek görüşme bağlantısı ile test etmeli. **Zorla durdur** davranışını %100 vaat etmeyin.


## 26.10.2026 — Kesin kullanıcı senaryosu / mesajlardan bütünüyle ayrı gelen çağrı

**Ekranda LANU olmasa da arama ulaşmalı.** LANU hesap makinesinde, başka uygulama açıkken, telefon kilitliyken, uygulama normal şekilde arka plana alınmışken veya Android normal bellek yönetimiyle LANU işlemini durdurmuşken; kullanıcının izin verdiği **sesli veya görüntülü çağrı** için sistem çağrı bildirimi ve telefon sesli modda ise uygun zil çalmalıdır. Tek seferlik sessiz ikon/görsel uyarı **kabul değildir**. Uygulama açılıp gizli alan önceden görüntülenmedikçe çağrı ulaşamıyorsa bu başarısızdır.

**Mesajlar sadece gizli alanda.** Metin/fotoğraf/medya için Android bildirim çubuğu, kilit ekranı, banner, bildirim sesi/titreşimi ve çağrı FCM push tetikleyicisi **kullanılmaz**. Uygulama içinde yeni mesaj/okunmamış göstergesi olabilir; mesajlar ayrı şifreli senkronizasyon mekanizmasıyla getirilir.

**Çağrının alınması ile görüşmenin kabulü ayrı.** Kapalı uygulamaya FCM yüksek öncelikli yalnız-çağrı uyandırması gönderilir, Android kısa işlemi açarak gizli içerik göstermeyen gelen çağrı bildirimi üretir; kilidi açılmadan gizli mesaj/kontak/çağrı kimliği okunmaz, mikrofon/kamera aktive olmaz. Gerçek şifreli INVITE çözümlenmesi ve görüşmeye geçiş mevcut `2011.` + varsa PIN güvenli akışında olur. Çalan uyarı kabul/red/iptal ve zaman aşımında sonlanmalıdır; yalnız kısa sistem bildirim tonu, WhatsApp benzeri gerçek arama zili olarak değerlendirilmez.

**İşletim sistemi istisnası:** "Normal şekilde kapatılmış/son uygulamalardan kaydırılmış" ile Android **Zorla durdur** eşdeğer değildir. Zorla durdurma, kullanıcı bildirim kanalı sessize alma/DND, pil kısıtları, ağ/FCM/GMS kesintisi garanti dışıdır; ücretli servislere geçiş veya izinsiz sürekli servis çalıştırma yoktur.

**Eksiklerin şeffaf takibi:** [Issue #133](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/issues/133) altında canlı Supabase migration + Edge FCM + Firebase paket konfigürasyonu, gerçek iki cihaz arka plan/uyku/normal işlem ölümü/kapalı ekran ve işitilebilir zil kanıtları kapatılmadan tamamlandı denmez. [PR #139](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/pull/139) Android bildirim kanalına **tek seferlik ses** ekliyor; bunun sürekli gelen arama zili/iptal-red-çağrı bitiş yaşam döngüsüyle eşdeğer olmadığı ayrıca test edilmelidir.



## 26.10.2026 — Arka planda çağrının fark edilmesi: görsel + zil sesi

Kullanıcı, başka uygulamadayken/gizli alan açık değilken zaten **görsel gelen arama işareti** gördüğünü fakat cihaz normal sesli moddayken **hiç zil sesi duymadığını** bildirdi. Kabul hedefi: alıcının LANU gizli alanını açık tutması veya sürekli çalışan mikrofon/kamera servisi bulundurması gerekmeksizin, gerçek ses/video INVITE için **FCM veri uyarısı → Android yüksek önem bildirim kanalı → ekranda görsel uyarı + cihazın varsayılan telefon zil sesi**. Mevcut FCM opt-in, E2EE, hesap makinesi 2011./PIN, replay/expiry/rate-limit ve mesaja/fotoğrafa bildirim göndermeme ilkeleri değişmez.

Android 8+ kurulu bildirimin `lanu_private_calls_v1` kanalındaki ses yapılandırması geriye dönük değiştirilemediği için yeni sürümlü kanal `lanu_private_calls_ringing_v2` kullanır. Sistem telefon zil sesi (RingtoneManager TYPE_RINGTONE), `USAGE_NOTIFICATION_RINGTONE`, `IMPORTANCE_HIGH` ve kilit ekranı `VISIBILITY_SECRET` uygulanır. Android 7 ve altında kanalsız bildirim doğrudan ses URI'sini alır. Eski kanalı kullanıcı sessize aldıysa / önemini düşürdüyse sessizlik tercihi korunur. Kullanıcı **Gizli alan → Ayarlar → Gelen arama zil sesini ayarla** düğmesiyle işletim sisteminin kanal ayarını açabilir ve sesini kendisi seçebilir.

Bu bir sistem bildirimi sesidir; **sürekli çalan telefon zili veya tam ekran çağrı ekranı garantisi değildir**. Android sesli/titreşim/sessiz/DND, bildirim izni, OEM kısıtları ve kullanıcı kanalı sessize aldıysa bunlar aşılmaz. Tek seferlik sesin yeterli olmadığı tam çağrı zili/cevapla-reddet UX'i ayrı güvenlik, yaşam döngüsü ve fiziksel cihaz kabulü gerektirir; son sürüme dahil edilmiş sayılmaz. Gerçek cihazda Samsung A55 ve diğer telefonda ekran kilitli/başka uygulamada ve sesli/sessiz/DND senaryolarında dinlenebilir ses kanıtı gereklidir.

---

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


## 27.10.2026 — Yeni onaylanan ayrı mesaj bildirimi (önceki call-only satırları tarihsel kanıttır)
Yeni kullanıcı kararı: Arama bildirimi mevcut ayrı tercih olarak korunur. Mesaj bildirimleri gizli alan Ayarlar ekranından **ayrı** ve **varsayılan kapalı** olarak açılır/kapatılır. Yeni kullanıcı kararı eski bu belgedeki "hiçbir sohbet bildirimi yok" hükmünün yerine geçer. İlgili kod için [PR #228](https://github.com/kanunal99-jpg/LANU-Hesap-Makinesi/pull/228) açılmıştır; PR kodu canlı backend, yayımlanmış APK veya fiziksel cihaz testi değildir.

Mesaj FCM taşıma katmanı yalnız `type=lanu_message_wake_v1` ve `at` içerir, kişi/sender/sohbet/metin/önizleme/mesaj kimliği içermez. Android bildiriminde başlık sadece **LANU Hesap Makinesi**, içerik metni **yok**; ses telefonun varsayılan bildirim kanalı sesi, sessiz/DND ayarlarına tabidir. Kilit ekranı `VISIBILITY_SECRET`, bildirim tıklaması hesap makinesine zorunlu dönüş ve `2011.` + isteğe bağlı PIN. Çağrı/ses ayarı bundan bağımsızdır; her iki FCM kayıt ve geri alma serviste ayrı saklanır.

Gerekli sürümlü backend: `20261027195900_private_message_wake_opt_in.sql` migration ve güncel `lanu-relay` Edge deploy; mevcut Firebase doğru Android paket konfigürasyonu ve FCM sunucu anahtarı hâlâ önkoşuldur. Kullanıcı server tokenı veya E2EE anahtarı görmez. Mesaj teslimi FCM yokluğunda da sürer. **Kabul:** PR CI + staging SQL RLS ve opt-out + Android API23–36 ve iki gerçek cihaz testleri olmadan üretim tamamlandı iddiası yapılmaz.
