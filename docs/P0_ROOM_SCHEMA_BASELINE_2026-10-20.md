# LANU — Room v4 şema temeli ve güvenli migration kapısı (20.10.2026)

## Kanıtlanan başlangıç
- Room `AppDatabase` sürümü **4** ve yedi tablo içeriyor.
- Şema dışa aktarımı önce kapalıydı. Bu PR yalnız v4 için `exportSchema=true` ve KSP dosya çıktısı ayarlar.
- Normal yeniden açılış ile bilinmeyen geçmiş sürümde veri silmeden hata verme testleri mevcut; v1-v3 tarihî şema fixture'ları repo içinde bulunmuyor.
- Room tamamı şifreli değil. Şema ihracı veri şifrelemesi sağlamaz.

## Bu PR'ın geliştirmesi
1. Derleyicinin ürettiği `app/schemas/com.example.core.database.AppDatabase/4.json` dosyasını CI kalite kapısında doğrula.
2. Beklenen yedi tablo ile PIN/rehber/mesaj anahtar sütunlarını kontrol et ve JSON'u test artifact'ı olarak sakla.
3. `version=4` değerini koru; yeni migration, tablo silme veya veritabanını yeniden yaratma davranışı ekleme.
4. Şema JSON'u özel kullanıcı verisi içermez; geçmiş `*.db` örnekleri yalnız kişisel veriler temizlenerek korumalı test ortamına alınır.

## Bilinmeyen v1-v3 geçişleri için kabul planı
- Orijinal kaynak commit/tag ve varsa gerçek eski APK build'inden Room şema kimliği ve anonimleştirilmiş SQLite fixture çıkar.
- Her sürüm için otomatik MigrationTestHelper / cihaz açılış testi; 1->4, 2->4, 3->4 ve ileri 4->N geçişleri.
- Test veri seti: PIN, geçmiş, sohbet, mesaj, rehber, engellenen kişi, ayarlar ve çağrı kaydı.
- Veri sayısı/içeriği öncesi-sonrası denetimi, atomiklik/güç kesintisi/bozuk DB hata senaryoları.
- Üretim veri kaybı riski giderilmeden yıkıcı migration (`fallbackToDestructiveMigration`) ekleme.
- Onaylanmış kurtarma ile yerinde yükseltme ve imza testi olmadan eski kullanıcıya kaldır-yeniden kur önerme.

## Açık dış bağımlılıklar
Kalıcı keystore ve eski APK sertifikası hesap sahibinin kontrolündedir. Debug APK'ler eski kurulumların üzerine güvenli güncelleme sunmayabilir. Gerçek cihaz doğrulaması ve geçmiş sürümlerin veri örnekleri ayrıca gereklidir.

## Test komutları
```sh
./gradlew :app:testDebugUnitTest :app:lintDebug assembleDebug
python3 scripts/verify_room_schema.py app/schemas/com.example.core.database.AppDatabase/4.json
```
CI'da komutların gerçekten geçmesi gereklidir; doküman tek başına bir başarı kanıtı değildir.
