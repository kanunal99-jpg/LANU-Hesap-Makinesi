# LANU CALCULATOR - TEKNİK TASARIM VE MİMARİ DOKÜMANI

## A. Teknik Araştırma (Technical Research)

### 1. Android Minimum API Stratejisi (Android API 23+ Compatibility)
Android 6.0 (API 23), modern Jetpack Compose ve modern kütüphanelerin çalışabileceği en alt sınırlardan biridir. Bu seviyede şu hususlara dikkat edilmelidir:
- **BiometricPrompt:** API 23'te native biyometrik API'ler kısıtlıdır (`FingerprintManager`). `androidx.biometric:biometric` kütüphanesi kullanılarak geriye dönük uyumluluk (biyometrik şifreleme ve doğrulama) otomatik olarak yönetilir.
- **Güvenli Depolama (Crypto):** `Security` kütüphanesi (`androidx.security:security-crypto`) API 23'te KeyStore tabanlı AES-GCM şifreleme sağlar.
- **İzin Yönetimi:** API 23, çalışma zamanı izinlerinin (Runtime Permissions) başladığı sürümdür. Mikrofon, Kamera ve Rehber erişimleri için Compose tabanlı dinamik izin akışları kullanılacaktır.

### 2. Safari PWA Sınırları ve WebRTC
iOS Safari üzerinde çalışan bir PWA için kısıtlamalar:
- **Web Push:** iOS 16.4+ ile PWA'lar için Web Push desteği gelmiştir. Ancak kullanıcının uygulamayı "Ana Ekrana Ekle" (Add to Home Screen) yapması şarttır.
- **WebRTC ve Arka Plan:** iOS Safari, arka planda WebRTC bağlantılarını ve medya akışlarını askıya alır. Gelen aramalar için Web Push veya FCM aracılığıyla uyandırma mekanizması zorunludur.
- **Contact Picker API:** iOS Safari'de `navigator.contacts` kısmen desteklenir veya hiç desteklenmez. Bu durumda telefon numarası ile manuel arama seçeneği birincil yöntem olarak sunulacaktır.

### 3. WebRTC ve LiveKit Mimarisi
- **LiveKit SFU (Selective Forwarding Unit):** LiveKit, WebRTC tabanlı ölçeklenebilir bir medya sunucusudur. MCU mimarilerine kıyasla daha düşük CPU kullanımı ve bant genişliği sunar.
- **TURN/STUN (coturn):** Simetrik NAT arkasındaki kullanıcıların el sıkışabilmesi için `coturn` sunucusu kurulacaktır. P2P başarısız olduğunda medya trafiği TURN üzerinden röle edilecektir.
- **Token Üretimi:** Güvenlik için LiveKit API Key ve Secret asla istemci tarafında tutulmaz. Firebase Cloud Functions veya Supabase Edge Functions aracılığıyla kısa ömürlü JWT token'lar üretilir.

### 4. Supabase Realtime ve Veritabanı Mimarisi
- **Realtime Broadcast:** Anlık mesajlaşma, yazıyor bilgisi (typing indicator) ve çevrimiçi durumu (presence) için PostgreSQL veritabanı yükünü azaltmak amacıyla geçici (ephemeral) WebSocket kanalları kullanılır.
- **PostgreSQL + RLS (Row Level Security):** Kalıcı mesajlar PostgreSQL üzerinde saklanır. RLS kuralları ile her kullanıcının sadece dahil olduğu konuşmalara (`conversations`) ve mesajlara (`messages`) erişmesi garanti edilir.

---

## B. Araştırma Değerlendirmesi ve Teknoloji Seçimleri (Research Evaluation & Tech Decisions)

| Teknoloji | Seçim Nedeni | Alternatifler | Avantajlar | Dezavantajlar / Riskler | Fallback / B Planı |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Supabase** | Auth, DB ve Realtime özelliklerini tek çatı altında sunması. | Firebase + Custom Backend | Hızlı entegrasyon, PostgreSQL gücü, SQL tabanlı RLS. | Vendor lock-in (kısmen), Realtime bağlantı limitleri. | PostgreSQL + Kendi WebSocket sunucumuz. |
| **WebRTC / LiveKit** | SFU mimarisi ve modern mobil SDK desteği. | Jitsi, Twilio, WebRTC Mesh | Düşük gecikme süresi, hazır oda yönetimi, otomatik kalite ayarı. | Sunucu maliyeti ve barındırma gereksinimi. | Ham WebRTC P2P (coturn ile sinyalleşme). |
| **Jetpack Compose** | Modern, bildirimsel (declarative) Android UI standardı. | XML Layouts | Hızlı arayüz geliştirme, temiz durum yönetimi, yüksek performans. | API 23 altındaki eski cihazlarda performans optimizasyonu ihtiyacı. | Standart XML View bileşenleri. |
| **Room Database** | SQLite üzerinde tip güvenli, reaktif yerel veri katmanı. | SQLDelight, Realm | SQLite entegrasyonu, Flow desteği, ksp uyumluluğu. | Schema değişimlerinde migration yönetimi zorluğu. | Ham SQLite veritabanı. |

---

## C. Risk Analizi ve Azaltma (Risk Analysis & Mitigation)

1. **Agresif Pil Tasarrufu Politikaları (Xiaomi, Samsung vb.):**
   - *Risk:* Arka planda Realtime WebSocket bağlantısının işletim sistemi tarafından sonlandırılması ve gelen aramaların/mesajların kaçırılması.
   - *Azaltma:* Arka planda kalıcı bağlantı yerine FCM (Firebase Cloud Messaging) High-Priority push mesajları kullanılacaktır. Kullanıcıya "Pil Optimizasyonunu Devre Dışı Bırakma" kılavuzu gösterilecektir.
2. **Kötü Ağ Koşulları ve Kesintiler:**
   - *Risk:* Tünel geçişlerinde veya asansörde mesajların kaybolması veya çift gönderilmesi.
   - *Azaltma:* Mesajlar yerel Room veritabanında "PENDING" durumunda saklanır. Ağ geri geldiğinde idempotent bir ID (UUID) ile tekrardan gönderilir (Duplicate Prevention).
3. **E2EE (Uçtan Uca Şifreleme) Anahtar Yönetimi:**
   - *Risk:* İkinci bir cihaz eklendiğinde geçmiş mesajların okunamaması veya anahtar sızıntısı.
   - *Azaltma:* Double Ratchet (Signal) protokolü benzeri bir yapı kurulacaktır. İstemci cihaz anahtarları Android Keystore içinde saklanır.

---

## D. Sistem Mimarisi ve Veri Akış Şemaları

```
   +--------------------------------------------------------+
   |                       MİMARİ                           |
   +--------------------------------------------------------+
   |                                                        |
   |   [ Android App (Compose) ]    [ iOS Web (Safari PWA) ]|
   |              \                           /             |
   |               \                         /              |
   |             HTTPS / WSS              HTTPS / WSS       |
   |                 \                     /                |
   |                  v                   v                 |
   |             +-------------------------+                |
   |             |    Supabase Platform    |                |
   |             |                         |                |
   |             |  - Auth & Realtime      |                |
   |             |  - PostgreSQL (RLS)     |                |
   |             |  - Edge Functions       |                |
   |             +-------------------------+                |
   |                          |                             |
   |                     Sinyalleşme                        |
   |                          |                             |
   |                          v                             |
   |             +-------------------------+                |
   |             |    LiveKit SFU / TURN   | <--- Medya     |
   |             +-------------------------+                |
   |                                                        |
   +--------------------------------------------------------+
```

### 1. Kimlik Doğrulama ve Kayıt Akışı
1. Kullanıcı telefon numarasını girer (E.164 formatı).
2. Backend (Supabase/Firebase) OTP SMS gönderir.
3. Kullanıcı OTP kodunu girer, doğrulama başarılı olursa JWT token üretilir.
4. İlk kurulumda kullanıcı yerel App Lock PIN'ini belirler. Bu PIN Keystore ile şifrelenerek SHA-256 olarak yerel depolamada saklanır.

### 2. Mesaj Gönderim Akışı
```
[GÖNDERİCİ]                              [SUPABASE DB / REALTIME]                    [ALICI]
     |                                               |                                  |
     |--- (1) Şifreli Mesaj Oluştur (AES/Signal) --->|                                  |
     |    Durum: SENDING                             |                                  |
     |<-- (2) Mesaj Kaydedildi (Acknowledge) --------|                                  |
     |    Durum: SENT                                |                                  |
     |                                               |--- (3) Broadcast Message ------->|
     |                                               |                                  | (4) Şifreyi Çöz
     |                                               |                                  |     Durum: DELIVERED
     |                                               |<-- (5) Delivery Receipt ---------|
     |<-- (6) Status Update (DELIVERED) -------------|                                  |
```

---

## E. Veritabanı Şeması (Database Schema)

### 1. `profiles`
- `id` (UUID, Primary Key, References Auth.Users)
- `phone_number` (VARCHAR, Unique, E.164)
- `display_name` (VARCHAR)
- `avatar_url` (VARCHAR)
- `status` (VARCHAR)
- `last_seen` (TIMESTAMP)
- `created_at` (TIMESTAMP)

### 2. `conversations`
- `id` (UUID, Primary Key)
- `is_group` (BOOLEAN, Default: FALSE)
- `created_at` (TIMESTAMP)

### 3. `conversation_members`
- `conversation_id` (UUID, FK references conversations)
- `user_id` (UUID, FK references profiles)
- Primary Key (conversation_id, user_id)

### 4. `messages`
- `id` (UUID, Primary Key)
- `conversation_id` (UUID, FK references conversations)
- `sender_id` (UUID, FK references profiles)
- `encrypted_content` (TEXT, Ciphertext)
- `media_url` (TEXT)
- `created_at` (TIMESTAMP)

### 5. `message_receipts`
- `message_id` (UUID, FK)
- `user_id` (UUID, FK)
- `status` (VARCHAR: sent, delivered, read)
- `updated_at` (TIMESTAMP)

---

## F. Güvenlik Mimarisi (Security Architecture)

- **Uçtan Uca Şifreleme (E2EE):** İstemciler mesaj içeriğini göndermeden önce alıcının Public Key'i ile şifreler. Sunucu sadece `encrypted_content` alanını (ciphertext) görür.
- **Yerel Uygulama Kilidi (App Lock):**
  - Kullanıcı PIN'i salt eklenerek PBKDF2/SHA-256 ile hash'lenir.
  - Hassas veriler (Supabase JWT Token'ı ve E2EE Private Key'i) Android Keystore tarafından korunan şifreli depolamada saklanır.
- **Otomatik Oturum Kilitleme:** Uygulama arka plana alındığında zamanlayıcı başlatılır. Seçilen kilit süresi (Hemen, 30s, 1m, 5m) dolduğunda yerel oturum kilitli (`LOCKED`) durumuna geçer.

---

## G. WebRTC ve Çağrı Sinyalleşme Akışı (Call Flow)

1. **Çağrı Başlatma:** Arayan kişi, Supabase Realtime Broadcast kanalı üzerinden `call-invite` sinyali gönderir. Bu sinyal odanın LiveKit ID'sini ve E2EE arama anahtarlarını içerir.
2. **Çağrı Kabulü:** Alıcı çağrıyı kabul ettiğinde `call-accept` sinyalini gönderir ve her iki taraf LiveKit sunucusuna bağlanarak medya akışını (ses/video) başlatır.
3. **Bağlantı Sorunları (Poor Network):** WebRTC paket kaybı oranına göre LiveKit SDK'sı otomatik olarak video çözünürlüğünü düşürür. Çok kritik ağlarda video kapatılarak tamamen ses öncelikli mod (Audio Priority) aktif edilir.

---

## H. Android Uyumluluk Matrisi (Android Compatibility Matrix)

| Özellik | API 23 (Android 6.0) Desteği | API 24+ Desteği | API 30+ (Android 11) Desteği | Graceful Degradation (API 23) |
| :--- | :--- | :--- | :--- | :--- |
| **Biyometrik Kilit** | `FingerprintManager` üzerinden kısıtlı. | `BiometricPrompt` (Android Pie+) | Gelişmiş `BiometricManager` entegrasyonu. | Biyometrik algılanamazsa otomatik olarak şifre/PIN ekranına düşer. |
| **Edge-to-Edge** | Kısmi (Status bar rengi ayarlanabilir) | Tam destek | Gelişmiş `WindowInsets` kullanımı. | API 23'te durum çubuğu rengi sabit tutulur, taşmalar kontrol edilir. |
| **Şifreli Depolama** | `EncryptedSharedPreferences` (Yavaş/Kısıtlı Keystore) | Kararlı `Security-Crypto` | Mükemmel donanımsal Keystore desteği. | Donanımsal Keystore yoksa yazılımsal keystore simülasyonu çalışır. |

---

## I. Test Planı (Testing Strategy)

1. **Hesap Makinesi Motoru Testleri:**
   - Temel matematiksel işlemler (`+`, `-`, `*`, `/`).
   - İşlem önceliği ve parantezli karmaşık ifadeler (`(10 + 2) * 5` = 60).
   - Floating-point hassasiyet kontrolü (`0.1 + 0.2` = `0.3`).
   - Sınır durumlar (Sıfıra bölme hatası, geçersiz ifadeler).
2. **Güvenlik ve Oturum Kilidi Testleri:**
   - Yanlış PIN denemelerinde brute-force engelleme (gecikmeli kilit süresi).
   - Uygulama arka plana geçtiğinde kilitleme süresinin doğrulanması.
3. **Gerçekçi Ağ Testleri (Network Resilience):**
   - Bağlantı tamamen koptuğunda UI üzerinde "Reconnecting" durumunun gösterilmesi, ağ geldiğinde kuyruktaki mesajların otomatik iletilmesi.
