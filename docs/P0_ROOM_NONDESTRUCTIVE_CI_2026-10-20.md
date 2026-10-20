# P0 — Room verisinin yanlışlıkla silinmesini engelleyen CI kapısı

20.10.2026 — Başlangıç durumu: güncel Room `version=4`, `exportSchema=true`, `fallbackToDestructiveMigration` yok.

## Risk
Veritabanının yanlış bir Room sürüm artışında sessizce silinmesi, LANU PIN doğrulama verisini, E2EE mesajlarını, rehberi, çağrı geçmişini ve hesap makinesi geçmişini geri dönüşsüz kaybettirebilir.

## Bu PR
`scripts/verify_room_nondestructive.py` tüm üretim Kotlin dosyalarında `fallbackToDestructiveMigration`, `fallbackToDestructiveMigrationFrom`, `fallbackToDestructiveMigrationOnDowngrade`, `allowMainThreadQueries` kullanımını reddeder. Ayrıca kaynak `AppDatabase` sürümü 4 ve gerçek JSON export açık olmalıdır. Mevcut şema SHA-256 doğrulama adımı ile beraber çalışır.

Altı adet Python negatif/politika testi, zorunlu Room CI suite'ine dahil edildi. Bu, *gelecekteki yanlış değişiklikleri* engeller; eski Room v1-v3 kaynak şemalarının eksik olduğu gerçeğini değiştirmez.

## Üretim kabulü için kalanlar
1. Gerçek v1-v3 SQL fixture/schema kaynağı ve kayıpsız migration testleri.
2. Tam SQLite encryption, WAL ve eski plaintext sayfaları incelemesi.
3. Kalıcı signing key ile iki ardışık Android APK ve gerçek cihaz yükseltme.
4. İki farklı üretici cihazda offline mesaj, kimlik backup/restore ve OTA smoke testi.

CI sonuçlanmadan bu PR kapanmış sayılmaz.

## Birleştirme notu
Önceki #63 geliştirmesi CI'da başarılıydı ancak ilerleyen `main` ile çakışma oluştu; P0 koruması ana dalın güncel sürümü üzerine yeniden eklendi.
