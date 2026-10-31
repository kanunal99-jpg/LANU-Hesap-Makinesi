# LANU Gizli Alan — Tek tam özellik teslim kuyruğu (30.10.2026)

> **Yeni anayasa hükmü:** Kullanıcı istemedikçe paralel bağımsız geliştirme yok. Önce kullanıcı açısından tek bir tam özellik; tam backend, Compose, güvenli veri, testler, imzalı GitHub APK yayını ve gerçek kullanım kabulü; SONRA sıradaki özellik. Eski G01–G14 eşzamanlı geliştirme planları bu sıraya tabidir. Bu belge plan/kanıt kaydıdır, uygulama yayını değildir.

## Son güvenilir başlangıç sürümü

- **v1.2.0-signed.984**, main `6cbd1ce87f53a2eb8ad7f6e5e27ed84d52e49ff0`, Build/API34/OTA/Publish CI 4/4 PASS; APK, .sha256 ve .signer.txt public read-back doğrulandı.
- APK 984'te G04 başarısız mesajları toplu dene, G08 otomatik ses çıkışı ve G01/G03/G05/G12 mahremiyet/çağrı düzeltmeleri kaynak olarak vardır. Samsung A55 ve ikinci OEM fiziksel **kullanıcı kabulü doğrulanmadı**; bu nedenle `tam fiziksel kabul` işaretlenmez.
- Açık issue: 12; açık PR: #266, #287, #311. Matematik #5 kullanıcının talimatıyla donduruldu. #287/#311 ile mevcut main davranışını tekrarlayan iş açılmaz.

## Tek teslim kuyruğu

| Öncelik | Teslim edilecek TAM işlev | İlgili G ve kayıtlar | Kapanması gereken gerçek kabul |
|---|---|---|---|
| **ŞİMDİ / P0** | **Gerçek hesap ve ilişkili sunucu verisi silme** | G11, #224, **mevcut tek PR #266** | Kişinin imzalı/iki aşamalı onayı, yalnız sahibin verisi, Storage nesne temizliği, kesintide idempotent sonuç/makbuz, güvenli yerel yaşam döngüsü, kamuya açık HTTPS başvuru ve hukuki onay, iki cihaz, main+APK |
| Sıradaki / P0 | İmzalama anahtarı felaket kurtarma | G14 #83 | Sahibin cihaz dışında güvenli şifreli PKCS12 yedeği, ayrı ortamda aynı sertifikayla test, süreklilik ve APK |
| Sıradaki / P0 | Üretim Android sağlamlığı | G13 #100 | Mahrem Crashlytics opt-in, crash/ANR kök neden, Android sürüm uyumu, A55+OEM, performans ve APK |
| Sıradaki / P0 | Gerçek Double Ratchet E2EE | G02 #114 | Mevcut kimlik ve mesaj kayıpsız geçiş, sürüm/downgrade/anti-replay, bağımsız kripto denetimi, iki telefon ve APK |
| Çapraz kabul kapısı | QUALITY11 — tüm 11 kategoride kanıta dayalı 80+, nihai 95+ | #177 | Tek özellik değil; her dikey teslimin kod, test, gerçek cihaz, backend ve APK kanıtını birleştirir |
| Sonraki | Tam şifreli Room/SQLCipher | G10 #113 | Yedi tablo/WAL/Keystore, rollback, bozuk anahtar/disk kesintisi, kurulu verilerin kayıpsız OTA geçişi, A55+ikinci cihaz, APK |
| Sonraki | Fotoğraf + belge + büyük medya/video gerçek E2EE paylaşımı | G09 #85 | Android Picker/Kamera/SAF, MIME+magic/EXIF, AEAD/key envelope, özel storage, kota/resume, alıcı çözme, fail-safe/iki cihaz, APK |
| Sonraki | Gerçek WebRTC ses/görüntü, arka plan FCM ve çağrı yanıtı | G05–G07 #3 #133 | Gerçek çift yönlü RTP, kilit/Doze, süreç ölümü, Wi-Fi/LTE, FCM, iki cihaz, PIN/özel alan gizliliği, APK |
| Sonraki | Bluetooth/BLE, kablolu/USB, AEC/NS ve asenkron rota doğrulama | G08 #245 | ROUTED yalnız gerçekten seçilen aygıtta, kopma/fallback, OEM/A55 fiziksel doğrulama, APK |
| Sonraki | Erişilebilir UX/OTA görüntüsü ve son kullanıcı kabulü | G12 #84 | Android 16 A55 güvenli insets/sistem çubuğu, TalkBack, geniş font, yayın, cihaz ekran görüntüsü |
| Kapanış | G01–G14 uçtan uca tam ürün kabulü | Proje anayasası | 14/14 gerçek özellik, tüm alt işlev, main CI, SHA/signer, APK, iki cihaz, kullanıcı kullanabilir ve 11 kategori kabulü |

## Şu an aktif tek paket: #224

1. **Var:** Sunucu PGlite sentetik transaction/RLS/anti-replay testleri ve owner-only silme SQL; mevcut PR #266'da Android özel ayarlar ön kontrolü. Canlı Supabase'de özellik **bilerek kapalı**.
2. **Bu çalışma sırasında eklendi:** Backend `deletionEnabled` yetenek yanıtı; yerel özel ayarlarda ikinci onay/kod/geri alınamazlık teyidi; imzalı silme request'i; sunucu sonucu kaybolursa kalıcı `lanu_server_erasure_state` ve sessiz `register` ile silinmiş hesabın yeniden açılmasını durdurma; başarı/belirsizlik durumları; yeni Robolectric ve Node regresyonları.
3. **Henüz eksik:** Post-delete idempotent makbuz/receipt sorgusu, karşı taraf verisi retention kararı, fiziksel Storage object purge kanıtı, gerçek public HTTPS silme talebi, sahibi/hukuk onayı, açık ve güvenli yerel veri son temizliği, iki fiziksel telefon ve yeni imzalı APK.
4. **Yayın kuralı:** Bunlar güvenilir biçimde sağlanmadan `accountDeletionEnabled` canlı açılmaz, PR #266 bütün özellik tamamlandı diye merge edilmez, yeni kullanıcıya 'silme tam çalışıyor' söylenmez. Riskli veri silme için açık sahibi/hukuk onayı ayrıca gerekir.

## İki otomatik görevin çakışmayan sorumluluğu

- **LANU Kalan 5 APK Yayını:** #224 PR #266'nın tek kod sahibi; tek dikey özelliğin tüm alt işlevlerini bitirir, tek PR son HEAD testleri ve güvenlik/servis kök nedenleri.
- **LANU G01–G14:** Bağımsız okuma, aynı PR'nin test/Release/OTA/cihaz kabul kapısı; #224 bitmeden yeni PR veya rakip release başlatmaz.

**Durum etiketleri:** PLAN / KOD / SON HEAD TEST / MAIN / İMZALI APK YAYINI / GERÇEK CİHAZ KABUL / TAM KULLANILABİLİR. Başka hiçbir kısaltma 'tamam' yerine geçmez.
