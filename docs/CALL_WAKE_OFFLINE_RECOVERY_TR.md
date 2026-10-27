# Çevrimdışı çağrı bildirimi iptalinin kurtarılması

## Kök neden

`CallWakeTokenRefreshWorker.enqueue` yalnız açık çağrı iznini kabul ediyordu.
Kullanıcı çevrimdışıyken kapattığında veya kayıt ortasında süreç öldüğünde
kalıcı iptal işareti vardı; sonraki açılış bu iptali planlamıyordu.
Gizli Ayarlar'a dönmeden sunucu kaydı temizlenemeyebiliyordu.

## Davranış

- Yerel kapatma hemen geçerlidir. Mevcut kimlikle imzalı sunucu iptali ana yoldur.
- Ağ yoksa aynı benzersiz WorkManager işi `CONNECTED` koşulu ve 30 saniyeden
  başlayan üstel beklemeyle, mevcut en fazla beş retry sınırında çalışır.
  Süreç başlangıcı `KEEP` ile bekleyen işi korur; planlama hatasında veya
  retry tükendiğinde gizli Ayarlar'dan manuel iptal ve sonraki açılış alternatiftir.
- Soğuk başlangıç yarım kalmış kayıt işaretini iptal işaretine dönüştürür.
  Aktif ekrandaki kayıt bir worker tarafından iptal edilmez. Arayüz ve worker
  ağ işlemleri aynı coroutine mutex üzerinden sıralanır.
- İptal başarısız veya coroutine durdurulmuşsa işaret silinmez. Başarılı sunucu
  onayı ardından temizlenir. Yeni kurulum veya tamamlanmış iptal ağ işi başlatmaz.
- WorkManager girdisine token/kimlik/PIN/mesaj yazılmaz. Yalnız var olan kimlik
  kullanılır; mesaj bildirim tercihi, Room, E2EE anahtarları ve APK imzası değişmez.

## Kanıt ve sınır

Regresyonlar: gerçek WorkManager kuyruğunda çevrimdışı iptal ve KEEP;
yarım kayıt/soğuk başlangıç; yeni kurulum; aktif kayıt koruması; geç token;
çift eşzamanlı iptal; eski yenileme ile yeni kayıt sıralaması;
coroutine iptalinde kalıcı retry işareti ve kilidin serbest bırakılması.

Yerel Python Android gizlilik kontrolleri ve yıkıcı Room geçişi koruması geçti.
Android/Robolectric, Lint, API34 ve R8 sonuçları son PR HEAD CI'da doğrulanmalıdır.
Bu çalışma fiziksel FCM teslimi veya sunucunun iptali gerçekten aldığına dair
iki telefon kabulü değildir. Android zorla durdurma/Doze ve ağ koşulları nedeniyle
anında arka plan yürütme garanti edilmez. Ücretli servis eklenmez.

Resmî dayanak: https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work
