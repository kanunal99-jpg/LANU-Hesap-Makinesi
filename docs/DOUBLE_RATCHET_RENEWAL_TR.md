# Double Ratchet oturum yenileme ve yayın kabulü

Bu belge geliştirme dalını anlatır. Kapalı bayrak veya PR APK'sı kullanıcı yayını değildir.

## Kullanıcı akışı

İki taraf mevcut LANU kimlikleriyle karşılıklı kişi olmalı. İlk yükseltme her iki tarafta açık onay gerektirir. Yerel eski gönderim kuyruğu ve sunucudaki eski teslim olayları boşalmadan yükseltme yapılmaz. İlk isteği henüz etkinleşmeden sahibi iptal edebilir; etkin oturum eski protokole dönmez.

Anahtar kaybı / yeni cihaz için sohbet menüsündeki durum kontrolü sunucudaki imzalı paketleri, oturum neslini ve varsa eşin yenileme teklifini doğrular. Onay sonrasında cihaz bağımsız yeni anahtar alanında bir aday oluşturur. Eski dosyalar, Keystore girdileri, mesajlar ve gönderim kopyaları silinmez. Yeni paket ve ayrı yenileme imzası ağ isteğinden önce kaydedilir. Her iki sahip aynı turu ve önceki nesli imzalamadan etkin paketler değişmez.

İkinci onaydan sonra yanıt kaybolursa önceden onaylanmış aday, ön plandaki senkronizasyonda iki imza tekrar doğrulanarak tamamlanır. Kayıp anahtar, eksik bağımsız kayıt veya geri sarılmış Room durumu kendiliğinden yeni anahtar üretmez. Yalnız henüz etkinleşmemiş, korumalı geçmişi olmayan başlangıç açık onayla yeni alanda onarılabilir.

Eski nesildeki alınmamış iletiler yeni neslin önünde bekletilmez ve alınmış gibi onaylanmaz. Eski anahtar olmadan bunları açma iddiası yoktur. Gönderen, açık tekrar uyarısıyla en fazla 20 eski iletiyi yeni ileti kimlikleriyle yeniden gönderebilir. Eski arşiv değişmez. Eski→yeni kimlik eşlemesi şifreleme durumu ve yeni gönderimle aynı işlemde saklanır; yeniden tıklama aynı eski iletiyi tekrar kuyruğa almaz. Daha önce karşı tarafa ulaşmış bir iletinin tekrar görünmesi mümkündür; yerel eşleme karşı cihaz geçmişini kanıtlamaz.

## Kayıt ve protokol sınırları

- Motor sabitlenmiş `vodozemac 0.11.2` Olm Double Ratchet, tam MAC yapılandırmasıdır. Signal protokolüyle birlikte çalışabilirlik iddiası yoktur.
- Her eş için ayrı rastgele cihaz kökü ve ayrı şifreli durum/journal vardır. Kimlik imzalı `noBackup` seçici, Room işaretçisine geçişten önce fsync ile yazılır; Room işlemi sonrasında tamamlanır. Her kesinti sınırı tekrar açılırken doğrulanır.
- Etkin nesil ve gönderim nesli Room içinde ayrı saklanır. Sunucu ve istemci yeni iletiyi aynı nesle bağlar. Eski UUID farklı şifreli içerikle veya nesille tekrar kullanılamaz. Yeniden deneme eski şifreli baytları kullanır.
- Sunucu gönderim ve yenilemesi aynı eş kilidini kullanır. Eski nesil olayları gelen kutusu LIMIT'inden önce süzülür; eski gönderim kuyruğu da Room LIMIT'inden önce süzülür.
- Taşınabilir yedekler hiçbir `lanu_dr_private_state_*` kaydını içermez. Public paketler, pinler, seçim bilgileri ve değişmez gönderim kopyaları korunur. Başka cihazda eski ratchet durumu canlandırılmaz; açık iki taraflı yenileme gerekir.
- Yerel okunabilir geçmiş ayrıca mevcut kimliğe şifrelenir. Ağdaki ratchet iletilerinin özellikleri, bu kalıcı arşive cihaz/kimlik ele geçirilmesine karşı ileri gizlilik sağlamaz. Tam cihaz gizliliği veya geçmişin ileri gizliliği iddiası yoktur.
- APK, sabitlenmiş Cargo.lock grafiğinin 102 bağımlılığının lisans/telif bildirimlerini varlık dosyalarında içerir. Bu, bağımsız hukuk veya kriptografi incelemesi değildir.

## Kanıt ve açık kapılar

Yerelde gerçek Rust motorunun dört testi, gerçek Java JNI çift yönlü değişimi ve JNI→imzalı relay→şifreli zarf→teslim→iki sahipli yenileme smoke testi geçti. Postgres testleri eski verinin korunmasını, aynı turun iki onayını, yanlış nesil / UUID değişimini, iptal sınırını ve RLS yetkilerini kapsıyor. Android testleri üç yazım sınırındaki kesintiyi, Room rollback'ini, kayıp seçici/Keystore'u, 210 eski kuyruğun yeni iletiyi engellememesini ve kayıp yenileme yanıtından sonra gerçek repository değişimini kapsar. Android testinin yazılması, çalıştığı kanıtı değildir; son HEAD CI sonucu ayrıca kontrol edilir.

Yayın için son HEAD API23/API34/debug/R8 testleri, ayrı main CI, mevcut imzalı1140'tan OTA veri koruma testi ve public APK SHA/signer geri okuması gerekir. Canlı relay ve imzalı APK etkinleştirmesi ayrıca doğrulanmalıdır. Bağımsız entegrasyon/kripto incelemesi ve iki fiziksel telefonda birlikte çalışma kabulü bu otomatik testlerin yerine geçmez; elde edilmemiş kanıt tamamlandı olarak işaretlenmez. SQLCipher canlı dönüşümü ve büyük dosya canlı teslimi bu belgenin bitmiş özelliği değildir.

## Bağımsız incelemeye teslim

İnceleme `RatchetProtocol`, `RatchetCoordinator`, `RatchetRootRegistry`, `RatchetTransactionStore`, JNI sınırı ve iki additive migration'ı birlikte kapsamalı. Özellikle eski tur imzasının yeni tura taşınamaması, yerel geçmişin uzun ömürlü kimliğe bağlı kalması, selector/journal kesintileri, restore sonrasında anahtar üretmeme, aynı UUID'nin değişmezliği ve eski neslin yanlış teslim onayı almaması değerlendirilmelidir. Kütüphane motorunun incelemesi bu yeni entegrasyonun incelemesi sayılmaz.

Fiziksel kabulde A55 ve ikinci ayrı Android cihazda, ayrı test kimlikleriyle şu senaryolar kaydedilmeli: karşılıklı ilk yükseltme; Wi-Fi/mobil ağ geçişinde çift yönlü teslim; eşzamanlı ilk gönderim; tek onaydan sonra vazgeçme; uygulama kapanırken kayıp yanıt; yeni cihaz/anahtar kaybından sonra aynı turun iki onayı; eski alınmamış mesajların açık yeniden gönderimi; eski geçmiş ve daha yeni taslağın korunması. Gerçek kullanıcı verisini silerek veya stable APK'yı kaldırarak test yapılmaz. PR'nin geçici sertifikalı APK'sı, kurulu stable uygulamanın üstüne güncelleme olarak sunulmaz.

Yerel doğrulama komutları:

```sh
cargo test --locked --manifest-path native/ratchet/Cargo.toml
cargo build --locked --release --manifest-path native/ratchet/Cargo.toml
node server/smoke/ratchet_end_to_end.mjs
npm test --prefix server
python -m unittest discover -s scripts -p 'test_*.py'
```

08.11.2026: `214de364` için CI run42475188990'da gerçek API23'te 14 test ve native dört ABI/JNI job'ı geçti. Birim/Robolectric/lint adımları da geçti. Bu kayıt sonraki HEAD, API34/R8, üretim relay, main/OTA veya public APK kabulü değildir. PR #357 inceleme listesi boş; bağımsız entegrasyon onayı henüz yok.
