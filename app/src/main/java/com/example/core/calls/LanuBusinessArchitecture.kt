package com.example.core.calls

/**
 * Data models and catalog defining LANU's communication infrastructure
 * monetization and enterprise business model tiers.
 */
data class BusinessTier(
    val id: String,
    val title: String,
    val price: String,
    val targetAudience: String,
    val badge: String,
    val features: List<String>
)

object LanuBusinessArchitecture {
    val tiers = listOf(
        BusinessTier(
            id = "core_free",
            title = "LANU Core",
            price = "Ücretsiz",
            targetAudience = "Bireysel Kullanıcılar",
            badge = "Açık Standart",
            features = listOf(
                "Uçtan Uca Şifreli P2P Görüşme (DTLS-SRTP)",
                "Yazılımsal Akıllı Spektral DSP (Cihaz Bağımsız)",
                "720p HD Video & Standart Opus Ses (32 kbps)",
                "Genel STUN Altyapısı"
            )
        ),
        BusinessTier(
            id = "privacy_pro",
            title = "LANU Pro Privacy",
            price = "₺49 / ay",
            targetAudience = "Profesyoneller & Gizlilik Odaklılar",
            badge = "En Çok Tercih Edilen",
            features = listOf(
                "Nöral Derin Gürültü ve Yankı Giderme (RNNoise Yapay Zeka)",
                "1080p 60fps Kristal Netliğinde Video",
                "Ultra Düşük Bant Genişliği Modu (12 kbps Uydu/2G Uyumu)",
                "Global Anycast Güvenli TURN Relay Sunucuları (TLS :443)"
            )
        ),
        BusinessTier(
            id = "enterprise_mesh",
            title = "LANU Enterprise Mesh",
            price = "Özel Fiyatlandırma / Lisans",
            targetAudience = "Finans, Hukuk, Sağlık & Savunma Şirketleri",
            badge = "Kurumsal B2B",
            features = listOf(
                "Şirkete Özel Adanmış Özel TURN/STUN Kümeleri",
                "Kurumsal Santral (SIP / PSTN PBX) Entegrasyonu",
                "Sıfır-Bilgi Kriptografik Denetim İzi (Audit Log)",
                "KVKK, GDPR, HIPAA ve SOC2 Uyumluluk Garantisi",
                "%99.999 Uptime SLA & 7/24 Öncelikli Mühendislik Desteği"
            )
        )
    )
}
