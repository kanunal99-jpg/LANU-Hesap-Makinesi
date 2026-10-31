# LANU — sunucu hesabı ve ilişkili veri silme, kapalı aşamalı güvenlik tasarımı

**Durum: TEST AŞAMASI; ÜRETİMDE ETKİN DEĞİL.**
Kullanıcı uygulamadaki hesap silme işlevini çalışır olarak teyit etti. Ancak canlı
Supabase'de tam ilişkili hesap silme RPC'si mevcut değildi (28.10.2026 salt-okunur
kontrolü). Yerel sohbeti silmek, sunucu E2EE metadata/FCM kaydını silmek değildir.

Bu PR'ın aşamalı bileşenleri:
1. `server/staged/lanu_account_erasure_v1.sql` — **bilinçli olarak**
   `supabase/migrations/` dışında tutulur; canlı veritabanına uygulanmaz.
   Tüm ilişkili tablolarda transaction ve yalnız `service_role` EXECUTE.
2. İmzalı `checkAccountDeletion` salt-okunur ön kontrolü: yalnız
   `{accountDeletionPreflightEnabled:true}` ile staging'de çalışır.
   `lanu_account_erasure_preflight_v1` RPC'si sadece hesabın mevcut
   **medya metadata** kayıtlarının `purged` olup olmadığını döndürür.
   `storageReady:true` fiziksel Storage temizliği, makbuz, hukuki onay
   veya silme yetkisi değildir. Preflight bayrağı **silme bayrağını açmaz**.
   İstemci mevcut ECDSA kimliğiyle kendi güncel kodunu imzalar; başka
   kullanıcı hesabı seçemez. Canlı Edge wrapper iki bayrağı da açmaz.
3. `createRelay` içinde `deleteAccount` — yalnız
   `{accountDeletionEnabled: true}` sağlanırsa açılır. Dağıtılan Edge wrapper
   bu bayrağı vermez. `DELETE_ACCOUNT` teyidi + değişmemiş hesap kodu
   + mevcut ECDSA/nonce anti-replay gerekir. İstemci başka ID seçemez.

## Bağımsız kabul olmadan ETKİNLEŞTİRME

- [ ] Veri sorumlusu/hukuk: KVKK ve Play Store için saklama istisnaları,
      karşı tarafın cihazındaki mesajlar, destek kanalı ve açık aydınlatma
      metni onaylandı.
- [ ] Web: kullanıcının hesap ve ilişkili veriyi silme talebinde bulunabileceği,
      gerçek çalışır ve herkese açık **HTTPS hesap silme başvuru URL** doğrulandı;
      Play Console sayfası ile eşleştirildi.
- [ ] Medya Storage: her paylaşılan şifreli nesnenin gerçek Storage API
      üzerinden temizlenmesi, sonrasında DB `state='purged'` kanıtı. Bu
      gerçekleşmeden SQL işlemi iptal olur; mevcut dosyaları silmez.
- [ ] Ağ kesilmesi/yinelenen istek: başarılı DB commit sonrası HTTP cevabı
      kaybolursa tekrar kimlik doğrulaması yapılamaz. Kalıcı, güvenli ve
      gizliliği koruyan **silinecek hesaba özel tamamlanma makbuzu**
      tasarlanmalı; bir 401 yanıtı yanlışlıkla başarı diye gösterilmemeli.
- [ ] İstemci: ayrı geri alınamaz işlem ekranı, iki aşamalı onay,
      `2011.` ve varsa PIN kilidi korunması, sadece doğrulanmış sunucu
      silme sonrasında yerel kimlik/Room temizliği için kullanıcının
      açık yetkisi; ağ/503 durumunda hiçbir yerel veri silinmez.
- [ ] Supabase: staging ortamında EXPLAIN/PG danışmanları, FOREIGN KEY ve
      RLS, yeni tablo/FK eklenince kör/eksik silme olmadığının kabulü.
      **İki bağımsız PostgreSQL oturumu** ile eşzamanlı medya durum
      değişimi, yeni FK-bağlı medya ekleme, lock timeout/deadlock ve
      silme işlemi yarışları ayrıca denenmeli. SQL mevcut tüm
      gönderici/alıcı medya satırlarını `FOR UPDATE` ile kilitleyerek
      kontrol ve silme arasındaki durum değişimini engellemeyi hedefler;
      tek bağlantılı PGlite yapısal testi canlı eşzamanlılık kanıtı değildir.
      Üretimde gerçek hesap/mesaj silme **testi yapılmaz**.
- [ ] İki farklı üreticide sürüm yükseltme, önceki PIN, konuşma ve rehber
      kaybı/karşı kullanıcının verisinin korunması, bağımsız UAT.
- [ ] Son HEAD CI + birleşik PR CI + ayrı main CI + imza/HTTPS APK SHA256
      doğrulanmadan yayımlandı denmez.

## Açık tehditler ve sözleşme

- REST API yalnız servis anahtarını Edge'de tutar. Doğrudan
  `anon`/`authenticated` tabloların DELETE'ine veya RPC'ye ulaşamaz.
- Hesap sahibi yalnız ECDSA ile doğrulanmış `identityId` olabilir.
  `peerId` veya herhangi bir kurban `accountId` veri sözleşmesinde yoktur.
- Transaction: mesajlar, iki tarafın ilgili mesaj olayları ve FCM claim'leri,
  özel medya metadata, çağrı sinyalleri, çağrı/mesaj FCM cihaz kayıtları,
  karşılıklı ilişki bağlantıları ve nonce'lar, sonrasında kimlik.
  **Yalnızca bu hesaba bağlı ilişkili kayıtlar** silinir; diğer iki kişinin
  bağımsız mesajlaşması, hesapları, bildirim tercihleri korunur.
- Dış Storage fiziksel dosyası Postgres işlemiyle birlikte silinemez;
  güvenli delil gelmedikçe `ready/pending/cancelled` medya varsa hiç silme.
- Veritabanı şeması yeni bir FK ile genişletilirse parent DELETE hata verir;
  o transaction tüm veri silmelerini geri alır, böylece sessiz kısmi silme yok.
- `deleted:false` kesinlikle silme başarısı sayılmaz. API timeout veya
  kayıp yanıt idempotence belirsizliği giderilmeden canlı etkinleştirme yok.
- Loglar, bildirimler, GitHub ve kamuya açık sayfalara kod/PIN/FCM token,
  anahtarlar, E2EE gövdesi veya hesap ID yazılmaz.

**Testler:** `server/test/account_deletion_transaction_staged.test.mjs`
sentetik PGlite üzerinden normal/sahte/geri alınan işlem, bireysel izinler,
başka kullanıcının korunması. `account_deletion_signed_relay_staged.test.mjs`
imza ve kapalı varsayılanı kontrol eder.

Bu taslak, işlevin tamamlandığı, hukuken onaylandığı veya yayına açık
olduğu iddiası değildir.
