# LANU Premium Hesap Makinesi — Ekim 2026

## Normal alan
- Hesap makinesi ve birim/finans ekranlarında sistem ekran görüntüsü serbest.
- Uygulama başlığı net LANU, alt başlık Hesap Makinesi. Android başlatıcı etiketi LANU.
- Uzun matematik ifadeleri ve sonuçlar için üstte sınırlı/kaydırılabilir görüntü alanı; uzun bilimsel tuş takımı kendi bölgesinde kayar.
- Yeni özgün LANU marka simgesi: koyu petrol/emeralda şampanya altınlı hesap makinesi, L ekran monogramı. Android adaptive/monochrome ve eski Android için XML simge kaynakları.
- Kullanılmayan simülasyon Ortak Çalışma modülü ve düğmesi tamamen çıkarıldı. Finans ve sesle giriş üstteki araçlar menüsüne taşındı.

## Gizli alan
- PIN ekranı, gizli alan, sohbetler: FLAG_SECURE daima açık.
- Ses/video arama demolarına giden düğmeler ve sahte arama geçmişi sekmesi kaldırıldı. Kod tabanında şimdilik kullanılmayan deneysel demo altyapısı bulunabilir fakat kullanıcı akışında erişilmez.
- Fotoğraf ekleme düğmesi kaldırıldı; henüz gerçek medya sunucu aktarımı yok.
- Şifreli LANU metin mesajları, gelen bekleyen mesajların alımı ve yalnız yerel gizleme aynı şekilde korunur.
- Eski screen_protection_enabled ayarı artık ekran görüntüsüne etki etmez; kullanıcıya gerçek sabit koruma politikası anlatılır.

## Tamamlanması gereken gerçek cihaz doğrulamaları
- Android telefonun normal hesap makinesi ekranında screenshot alınabiliyor mu? PIN/gizli alan/sohbette alınamıyor mu?
- Tema ve büyük yazı boyutunda LANU başlığı tam mı; cihazda uzun sonuç hep görünür mü?
- 360dp ve daha dar ekranlarda tuşlar, klavye, yatay ekran ve erişilebilirlik.
- Android 6/7 ve Android 8+ launcher ikonları.
- İki telefon arasında gizli alan açıldıktan sonra mesaj teslimi.
- Eski debug APK imza farklılığı çözülmeden mevcut kurulum silinmemeli; otomatik yükseltme garantisi yok.
