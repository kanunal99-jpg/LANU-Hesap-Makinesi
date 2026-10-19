package com.example.features.security

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag

/** A readable, honest in-app disclosure; not a substitute for reviewed publication terms. */
data class InformationSection(val heading: String, val explanation: String)

enum class InformationTopic(val title: String) {
    PRIVACY("Gizlilik ve Veri Kullanımı"),
    TERMS("Kullanım Koşulları"),
    HELP("Yardım ve Sık Sorulan Sorular")
}

/** Keep claims here aligned with the actual Room, backup, and LANU relay implementation. */
object LanuInformation {
    val privacy = listOf(
        InformationSection(
            "Cihazınızda tutulan bilgiler",
            "Hesap geçmişi, ayarlar, kullanıcı kodu, kişi listesi ve şifreli sohbet kayıtları cihazın uygulama alanında tutulur. PIN ve kimlik anahtarı korumalı depolama kullanır. Room veritabanının tamamı ayrıca şifrelenmiş değildir."
        ),
        InformationSection(
            "Sunucu üzerinden mesajlaşma",
            "İki kişi birbirinin LANU kodunu kaydettiğinde uygulama Supabase üzerinde çalışan LANU mesajlaşma servisine bağlanır. Sunucu kullanıcı kodları, açık anahtarlar, karşılıklı kişi bağlantıları, zaman damgaları, teslim/okundu durumları ve şifreli mesaj gövdeleri tutar. Mesaj içeriği cihazlarda şifrelenir."
        ),
        InformationSection(
            "Telefon ve bildirimler",
            "Bu sürüm mesajlaşmak için telefon numarası, SMS veya e-posta doğrulaması istemez. Gizli iletişim alanı açık ve uygulama öndeyken sunucu yaklaşık 5 saniyede bir kontrol edilir. Uygulama kapalıyken mesaj bildirimi gönderilmez."
        ),
        InformationSection(
            "İzinler ve hizmet sağlayıcısı",
            "İnternet ve bağlantı durumu izinleri mesajlaşma içindir. Biyometri isteğe bağlıdır; titreşim dokunsal geri bildirim içindir. Mikrofon/kamera izinleri istenmez; arama ekranı bir demodur. Mesajlaşma altyapısı Supabase tarafından barındırılır; altyapı hizmetine ait teknik bağlantı günlükleri bulunabilir."
        ),
        InformationSection(
            "Yedekleme, silme ve veri kaybı",
            "Hassas uygulama verileri Android otomatik yedeklemesine ve cihaz aktarımına dahil edilmez. Ayarlardan ayrı parola ile şifrelenen bir kimlik yedeği dışa aktarılabilir; bu yedek sohbet arşivini içermez. Bir sohbeti cihazda temizlemek, tüm sunucu kayıtlarını otomatik silmez. Uygulamayı kaldırmak yerel verileri kaybettirebilir."
        ),
        InformationSection(
            "Yayın öncesi hukuki inceleme",
            "Bu metin mevcut sürümün teknik veri kullanımını açıklar. Kamuya açık resmî gizlilik politikasına dönüştürülmeden önce veri sorumlusu kimliği, iletişim kanalı, saklama süreleri ve ilgili kişi başvuru süreçleri doğrulanıp eklenmelidir."
        )
    )

    val terms = listOf(
        InformationSection(
            "Ürünün kapsamı",
            "LANU bu sürümde hesaplama araçları ve LANU koduyla çalışan şifreli metin mesajlaşması sunar. Ortak çalışma ve ses/görüntü arama alanları gerçek çok kullanıcılı servis değildir; demo olarak işaretlenmiştir."
        ),
        InformationSection(
            "Mesaj teslimi",
            "Mesajlar internet bağlantısı sağlandığında ve karşılıklı LANU kodları kaydedildiğinde gönderilir. Alıcı gizli alana girdiğinde bekleyen mesajlar alınır. Teslimin belirli bir sürede veya kesintisiz gerçekleşeceği garanti edilmez."
        ),
        InformationSection(
            "Gizlilik ve kimlik",
            "PIN, yedekleme parolası ve dışa aktardığınız kimlik dosyasını korumak sizin kontrolünüzdedir. LANU kodu tek başına hesap kurtarmaya yetmez. Hesap anahtarını kaybederseniz geçmiş yazışmalarınıza erişiminizi kaybedebilirsiniz."
        ),
        InformationSection(
            "Özelliklerin sınırları",
            "10 saniye sonra gizle özelliği, sunucu onayından sonra mesajı yalnız bu cihazda gizler. Karşı tarafın cihazından veya sunucudan silmez. Fotoğraf gönderimi ve gerçek arama henüz desteklenmez."
        ),
        InformationSection(
            "Kullanıcıya bildirim",
            "Bu ekran teknik kullanım koşullarını özetler; hukuken onaylanmış son kullanıcı sözleşmesi değildir. Yayın öncesi geliştirici/işletmeci bilgileri, destek iletişimi ve resmî koşullar ayrıca tamamlanmalıdır."
        )
    )

    val help = listOf(
        InformationSection(
            "Mesaj nasıl gönderirim?",
            "Güvenli alanda LANU kodunuzu paylaşın, karşı tarafın kodunu Kişiler bölümüne ekleyin. Her iki kişinin de birbirini kaydetmesi gerekir. Ardından Sohbetler bölümünden metin mesajı gönderebilirsiniz."
        ),
        InformationSection(
            "Karşı taraf çevrimdışıysa?",
            "Mesaj bağlantınız olduğunda şifreli olarak sunucuya aktarılır. Karşı taraf daha sonra güvenli alana girdiğinde bekleyen mesajları alır. Sunucuya ulaşmamış mesajlar gönderim kuyruğunda bekler."
        ),
        InformationSection(
            "Neden bildirim gelmiyor?",
            "Bu sürümde gizli alan kapalıyken bildirim yoktur. Gizli alan açıkken yaklaşık beş saniyede bir otomatik eşitleme yapılır; gerekirse Yenile düğmesine dokunun."
        ),
        InformationSection(
            "Kod değişirse ne olur?",
            "Ayarlar > Kullanıcı kodumu değiştir bölümünden kodunuzu yenileyebilirsiniz. Kimlik anahtarınız korunur; yeni kişiler güncel kodunuzla sizi eklemelidir."
        ),
        InformationSection(
            "Telefon değiştirirsem?",
            "Önce Ayarlar > Kimliğimi yedekle ile güçlü parolalı kimlik dosyası oluşturun. Yeni cihazda geri yükleyin. Yedek sohbet geçmişini taşımaz. Önceki APK'lerin farklı debug imzaları nedeniyle güncellemeden önce mevcut kurulumunuzu kaldırmayın."
        ),
        InformationSection(
            "Gerçek arama ve fotoğraf var mı?",
            "Hayır. Arama ekranları yalnızca arayüz demosudur; fotoğraf aktarımı etkin değildir. Bu sürümün çalışır iletişim kanalı şifreli metin mesajlarıdır."
        ),
        InformationSection(
            "Mesajı 10 sn sonra gizlemek ne demek?",
            "Gönderimi onaylanmış mesaj 10 saniye sonra yalnız bu cihazda gizlenir. Karşı taraftan ve sunucudan otomatik silinmez. Gönderimi bekleyen mesajlar silinmez."
        ),
        InformationSection(
            "Bir hata olursa?",
            "Sunucu bağlantı durumunu güvenli alanın üst kısmından kontrol edin ve Yenile'ye dokunun. Kodlarınızı karşılıklı kaydettiğinizi doğrulayın. Önemli verileri kaybetmemek için kimlik yedeğinizi güvenli saklayın."
        )
    )

    fun sections(topic: InformationTopic): List<InformationSection> = when (topic) {
        InformationTopic.PRIVACY -> privacy
        InformationTopic.TERMS -> terms
        InformationTopic.HELP -> help
    }
}

@Composable
fun AppInformationDialog(topic: InformationTopic, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(topic.title) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("information_${topic.name.lowercase()}"),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LanuInformation.sections(topic).forEach { section ->
                    Text(section.heading, style = MaterialTheme.typography.titleSmall)
                    Text(section.explanation, style = MaterialTheme.typography.bodyMedium)
                    HorizontalDivider()
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("information_close")) {
                Text("Kapat")
            }
        }
    )
}
