# P0 — Sohbet silme ve yerel veri gizliliği (20.10.2026)

## Veri gizliliği sorunu
Eski `CommunicationRepository.deleteConversation` yalnızca `conversations` satırını siliyor, ilişkili `messages` kayıtlarını diskte bırakıyordu. Buna şifreli mesaj gövdeleri ve sunucuya henüz gönderilmemiş PENDING mesajlar da dahil. Gelecekteki senkronizasyon olayları, silinmiş içeriği geri getirebilir.

## Çözüm
- `deleteConversation`: **tek Room transaction** içinde tüm ilişkili mesajların ID'lerini `lanu_hidden_<messageId>` tombstone ile sakla, mesajları ve sohbet kaydını sil.
- `clearConversationMessages`: aynı atomiklik; sohbet kaydı korunur ancak içeriği ve okunmamış sayacı sıfırlanır.
- İşlem yarıda kesilir veya DAO hata verirse Room rollback ile hem silme hem tombstone değişiklikleri geri alınır; sahte silinmiş kayıt üretilmez.
- Önceden uygulanmış `synchronize` içindeki `lanu_hidden_<messageId>` kontrolü gecikmiş eski mesajın otomatik geri yazılmasını engeller.
- Room şeması `version=4` olarak aynı kalır. Haricî sunucudaki mesaj kaydı/retention bu yerel silme işleminin kapsamı dışında.

## Kabul testleri
- Silinen sohbetle beraber PENDING ve READ durumlarındaki mesaj gövdeleri de yerel veritabanından silinir.
- Tekrar sync için mesaj başına tombstone bulunur.
- Silme DAO'suna enjekte edilen yapay hatada transaction rollback tüm veriyi yerinde tutar.
- Sohbeti temizleme eylemi mesajları siler, boş ve okunmamış sayacı sıfırlanmış sohbeti bırakır.

## Açık P0 riskleri
SQLite WAL/serbest alan forensik kalıntıları yerel disk şifrelenmeden garantiyle yok edilemez. Sunucu-side retention/silme ve iki cihazlı yeniden senkronizasyon testleri ayrıca gereklidir. Kurulu sürüm yükseltmesi için kalıcı APK imzası hâlâ şarttır.
