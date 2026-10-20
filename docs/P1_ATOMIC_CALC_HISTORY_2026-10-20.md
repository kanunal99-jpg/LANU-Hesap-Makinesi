# P1 — Hesap makinesi geçmişinde tutarlı veri saklama (20.10.2026)

## Bulgu
Önceki kaydetme + eski kayıtları temizleme işlemleri ayrı SQLite transaction'lardı. SQL prune sorgusu `LIMIT 10000 OFFSET 50` kullandığı için 10.050'den fazla kayıtta en eski satırlar pruneden kaçabiliyordu. Aynı timestamp değerlerinde en yeni 50'yi belirleme de kararsızdı.

## Kod değişikliği
- `CalculationHistoryDao.insertAndPruneHistory()` ile kayıt ve tarihçe temizleme aynı `@Transaction` içinde.
- `ORDER BY timestamp DESC, id DESC LIMIT -1 OFFSET 50` ile 50 kaydı aşan tüm fazlalıklar kaldırılır; timestamp eşitliğinde ID belirleyici.
- `CalculatorRepository.saveToHistory()` atomik DAO metodunu kullanır.
- Room v4 tabloları, veriler, migration sürümü ve E2EE/kimlik kayıtları değiştirilmez.

## Regresyon kabulü
- 80 eşit timestamp kaydından yalnız en yeni 50 kalır.
- SQL DELETE tetikleyicisi yapay hata ürettiğinde INSERT işlemi geri alınır ve eski 50 kayıt korunur.
- 90 eş zamanlı kaydetmede sonuç maksimum 50 kayıttır.
- CI Android unit/Robolectric, Room fingerprint, lint, APK signer/SHA tamamlandıktan sonra merge.

## Açık kritik işler
Bu P1 veri tutarlılığı kazanımı; Room database-at-rest encryption, tarihî fixture migration, APK kalıcı sertifikası ve fiziksel cihaz testlerini kapatmaz.
