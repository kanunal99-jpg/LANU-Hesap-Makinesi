package com.example.features.update

/**
 * Only generic installer status belongs to the calculator-accessible update
 * screen. In particular: no private-area names, sensitive feature names,
 * crash diagnostics, exception messages, session identity or backend details.
 *
 * Sensitive crash diagnostics remain in the separately gated private panel.
 */
object PublicUpdateText {
    const val CHECK_FAILED = "Sürüm denetimi tamamlanamadı. İnternet bağlantısını kontrol edip yeniden deneyin."
    const val DOWNLOAD_FAILED = "İndirme tamamlanamadı. Daha sonra yeniden deneyin."
    const val INSTALL_FAILED = "Paket doğrulaması tamamlanamadı. İşlem durduruldu."
    const val INCOMPATIBLE = "Bu paket mevcut kurulumla uyumlu değil. Kurulum engellendi."
    const val SIGNER_WARNING =
        "Mevcut uygulamayı kaldırmayın. Aynı imzayla hazırlanmış uyumlu bir sürüm kullanın."
    const val FOOTER =
        "Güncellemeler yalnızca kullanıcı onayıyla kurulur. " +
        "Paket imzası ve dosya bütünlüğü doğrulanır. " +
        "İmza uyuşmazlığında kurulum engellenir. " +
        "Android sürümüne göre sistem yükleme izni gerekebilir."
}
