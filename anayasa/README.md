# 📜 LANU VAULT & SECURE CHAT - ANAYASA (CONSTITUTION)

Bu dizin, uygulamanın güvenlik standartlarını, mimari yapısını, tüm özelliklerin test edilme yöntemlerini, test sonuçlarını ve yapılan her bir işlemin kayıt altına alındığı sistemi barındırır.

> ⚠️ **KRİTİK KURAL:** Uygulamada yapılan veya yapılacak olan **her bir değişiklik, test işlemi, iyileştirme veya genişletme** muhakkak bu "Anayasa" (Constitution) dökümanlarına işlenecek ve kayıt altına alınacaktır. Bu kural esnetilemez.

---

## 📂 Dizin ve Dosya Yapısı

*   `/anayasa/README.md` (Bu dosya) - Genel Giriş, Kurallar ve Dizin Yapısı.
*   `/anayasa/MIMARI_VE_KLASOR_YAPISI.md` - Uygulamanın tam klasör ve bileşen haritası.
*   `/anayasa/OZELLIK_TESTLERI_VE_SONUCLARI.md` - Her bir özelliğin işlevselliği, test senaryoları ve test sonuçları raporu.
*   `/anayasa/ISLEM_GUNLUGU.md` - Uygulama üzerinde yapılan tüm geliştirmelerin, güncellemelerin ve hata düzeltmelerinin kronolojik kaydı.

---

## 🏛️ Anayasa Temel İlkeleri

1.  **Gizlilik Birinci Sınıftır (Privacy First):** Uygulama hiçbir koşulda arka plandayken veya son uygulamalar listesindeyken şifresiz veri sızdıramaz (`FLAG_SECURE`).
2.  **Kusursuz Kamuflaj (Flawless Camouflage):** Hesap makinesi ve birim dönüştürücü arayüzleri, normal bir application gibi tamamen çalışır durumda olmalıdır.
3.  **Yerel Öncelikli Güvenlik (Local-First Secure):** Tüm veriler şifreli yerel veritabanında saklanır. Bulut eşitlemesi tamamen isteğe bağlıdır ve uçtan uca şifrelidir (E2EE).
4.  **Hatasız Derleme ve Lint Garantisi (Zero-Error Compilation):** Yapılan her kod değişikliği sıfır hata ile derlenmek ve Android Lint kurallarından başarıyla geçmek zorundadır.
5.  **Her Yenilik APK Olarak Çıkarılacak (APK Release for Every Feature):** Eklenen her bir yenilik, güncelleme veya hata düzeltmesinden sonra uygulama APK formatında derlenip hazır hale getirilecektir.
6.  **Hatalardan Ders Çıkarma (Failed Attempts Log):** Başarısız olan tüm işlemler, karşılaşılan derleme/mantık hataları ve yapılan yanlışlar işlem günlüğüne detaylıca işlenecektir. Böylelikle aynı tuzaklara tekrar düşülmesi engellenecektir.
