# Dayanıklılık tamamlaması — 9 Kasım 2026

Önceki mühendislik değerlendirmesi 88/100 idi; bu puan bağımsız sertifika veya fiziksel cihaz ölçümü değildir.

Bu değişiklik çağrı denetim isteklerini tek uçuşta birleştirir. Eşzamanlı zamanlayıcı, ağ ve realtime bildirimleri aynı sonucu paylaşır; en fazla bir ek kontrol tutulur. Monoton saatle istek başlangıçları arasında en az bir saniye ve ortak 429 beklemesi uygulanır. İptal edilen bekleyici sahibi iptal etmez; iptal edilen sahip yaşayan bekleyicinin tekrar kontrolünü engellemez.

Kullanıcı açıkça seçerse aynı yetkili TURN sunucusu ve portunda UDP/TCP alternatifi eklenir. Varsayılan kapalıdır. TLS kullanan turns bağlantısı şifresiz yola düşürülmez. Sunucu kimlik bilgileri mevcut şifreli tercihlerde kalır.

CI geçici, kimlik doğrulamalı ve yalnızca loopback üzerinde dinleyen TURN kurar. Debug ve küçültülmüş release kabulü gerçek relay-only ICE/DTLS/SCTP paketlerini iki yönde doğrular. UDP, TCP, UDP engelliyken açık TCP alternatifi ve yanlış kimlik bilgisi reddi zorunludur. Bu veri paketi testi ses/video RTP, fiziksel NAT veya gerçek kullanıcı gecikmesi ölçümü değildir. Test sunucusu yayınlanan uygulamaya sağlanan kalıcı bir hizmet değildir.

100/100 için açık kabul koşulları: yetkili kalıcı TURN ve kapasite ölçümü; bağımsız backend arıza alanı; yeni sürümde iki fiziksel cihaz/ağ kabulü; imzalama anahtarının bağımsız yedekten gerçek kurtarma provası; etkinleştirilecek kriptografik yenilikler için bağımsız inceleme. Bunlar yapılandırılmadan veya ölçülmeden tamamlandı sayılmaz. Ücretli kaynak açılmaz, yıkıcı Room geçişi yapılmaz, kullanıcı kimliği ve mevcut imza korunur.
