# 🏛️ LANU VAULT & SECURE CHAT - MİMARİ VE KLASÖR YAPISI

Uygulama, modern Android geliştirme standartlarına uygun olarak **MVVM (Model-View-ViewModel)** ve **Clean Architecture** prensipleriyle yapılandırılmıştır.

---

## 📂 Klasör Haritası

```text
app/src/main/java/com/example/
│
├── LanuApplication.kt             # Uygulama Başlangıç Sınıfı (DI Container'ı başlatır)
├── MainActivity.kt                # Tek Aktivite (Giriş Noktası & Animasyonsuz Hafif Navigator)
│
├── core/                         # 🧠 Çekirdek İş Mantığı ve Altyapı Katmanı
│   ├── calculator/                # Hesap Makinesi ve Dönüştürücü Matematik Motoru
│   ├── calls/                     # Sesli/Görüntülü Arama Sinyalizasyon ve Durum Yapısı
│   ├── crypto/                    # AES-256-GCM tabanlı Dosya/Mesaj Şifreleme Algoritmaları
│   ├── database/                  # Room SQLite Veritabanı ve Şifreli Tablolar (Mesaj, İletişim vb.)
│   ├── di/                        # Basit Constructor Injection Servis Sağlayıcı (AppContainer)
│   └── repository/                # Veri Kaynakları Koordinasyonu (Güvenlik, Hesaplama, İletişim)
│
├── features/                     # 🎨 Kullanıcı Arayüzü ve Ekran Katmanı (Jetpack Compose)
│   ├── calculator/                # Hesap Makinesi & Birim Dönüştürücü Ekranları (Kamuflaj Katmanı)
│   ├── communication/             # Şifreli Sohbet Detayları, İletişim Listesi ve Canlı Arama Ekranları
│   └── security/                  # PIN Kilit Ekranı, Şifreleme Sihirbazı ve Ayarlar Ekranı
│
└── ui/                           # 🎭 Stil ve Tasarım Katmanı
    └── theme/                     # Material Design 3 Renk Şemaları, Tipografi ve Şekiller (Theme.kt)
```

---

## 🔧 Çekirdek Bileşenler ve Görevleri

### 1. `MainActivity.kt`
Uygulamanın tek giriş noktasıdır. İçerisinde animasyonsuz ve sessiz geçişler barındıran hafif, durum tabanlı bir geri yığın (backstack) yönlendiricisi (`navigationBackstack`) barındırır.
*   **Görevleri:** 
    *   Ekran kapandığında (`ACTION_SCREEN_OFF`) veya kilitlenme süresi dolduğunda otomatik kilitlemeyi yönetir.
    *   Sallama hareketlerini algılayarak (`Sensor.TYPE_ACCELEROMETER`) hesap makinesini sıfırlar.
    *   Dinamik olarak ekran korumasını (`FLAG_SECURE`) açıp kapatır.

### 2. `core/crypto/CryptoUtils.kt`
Uygulamanın güvenlik kalbidir.
*   **Görevleri:** Mesajları ve paylaşılan medyaları yerel Room veritabanına veya buluta göndermeden önce **AES-256-GCM** algoritması ile askeri düzeyde şifreler.

### 3. `core/database/`
Yerel ve isteğe bağlı bulut veritabanı yönetimini sağlar.
*   **Görevleri:** SQLite tabanlı yerel Room kütüphanesini kullanır. Mesajları, kişileri ve güvenlik günlüklerini cihaz hafızasında güvenle saklar.

### 4. `features/security/`
Kullanıcı güvenliğini ve sistem kontrollerini sağlar.
*   **Görevleri:**
    *   `SecurityScreen.kt`: PIN şifre doğrulaması ve brute-force kilitleme ekranı.
    *   `SettingsScreen.kt`: Görünüm, otomatik kilitleme zaman aşımı, şifre değiştirme ve denetim günlüklerinin yer aldığı arayüz.

### 18.10.2026 — Gizli alan doğrulaması

- core/security/PinPolicy.kt: ortak PIN biçimi/sınırları ve PinAttemptStatus.
- core/repository/Repositories.kt (SecurityRepository): yetkili kilit durumu, tek satırda kalıcı PIN deneme kaydı, Mutex, ortak PIN doğrulama/değiştirme ve biyometrik başarı yolu.
- features/security/SecurityViewModel.kt: yükleme/doğrulama durumları, kalıcı kayıttan geri sayım, geç gelen giriş sonucunu iptal eden oturum nesli.
- features/security/SecurityScreen.kt: 4–12 hane ve açık gönderme, prompt iptali, küçük ekran kaydırması.
- MainActivity.kt: yetkili repository durumuna göre özel ekran kapısı, kilitleyerek geri dönüş, zorunlu özel ekran koruması.
- test/java/com/example/HiddenAreaSecurityTest.kt: 22 otomatik güvenlik regresyonu. Veritabanı şeması değişmez.

### 19.10.2026 — Kod kimliği ve mesaj taşıma

- core/messaging/MessagingIdentity.kt: kalıcı EC kimliği, kod üretme/yenileme, Keystore depolama ve kimliğe bağlı AES-GCM.
- core/messaging/MessagingRelay.kt: imzalı, yeniden yönlendirme kabul etmeyen HTTPS istekleri.
- core/repository/CommunicationRepository.kt: telefon bağımsız kimlik/rehber, şifreli gönderim kuyruğu ve sunucu olaylarını kalıcı kayda uygulama.
- features/communication: gerçek bağlantı durumları, kullanıcı kodu kartı, karşılıklı kayıt ve kimliği silmeyen çıkış.
- UserCodeMessagingTest.kt: kimlik, eski veri uyumu ve kriptografik sınır regresyonları.
