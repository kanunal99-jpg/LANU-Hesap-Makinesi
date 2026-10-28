package com.example.core.media

import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Bounded structural preflight for non-macro Office Open XML documents.
 *
 * This deliberately does not parse Office XML, extract ZIP entries, run macros,
 * follow links, or claim malware scanning. Its input must be a private, bounded
 * SAF staging file; never open attacker-selected filesystem paths directly.
 * Sending still requires authenticated private object storage and E2EE delivery.
 */
object PrivateOfficeDocumentPreflight {
    const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    const val PPTX_MIME = "application/vnd.openxmlformats-officedocument.presentationml.presentation"

    private val mandatoryParts = mapOf(
        DOCX_MIME to "word/document.xml",
        XLSX_MIME to "xl/workbook.xml",
        PPTX_MIME to "ppt/presentation.xml"
    )
    private const val MAX_ENTRIES = 2048
    private const val MAX_EXPANDED_BYTES = 128L * 1024 * 1024
    private const val MAX_METADATA_XML_BYTES = 1024 * 1024L

    fun isOfficeMime(mime: String): Boolean = mime in mandatoryParts

    /** Throws on spoofed MIME, malformed ZIP, macros, oversized or unsafe entries. */
    fun validate(privateStagedFile: File, mime: String) {
        val required = mandatoryParts[mime] ?: throw IllegalArgumentException(
            "Bu Office dosyası türü desteklenmiyor."
        )
        require(privateStagedFile.isFile &&
            privateStagedFile.length() in 4L..PrivateMediaCrypto.MAX_FILE_BYTES) {
            "Office dosyası güvenli boyut sınırını aşıyor."
        }
        FileInputStream(privateStagedFile).use { stream ->
            val signature = ByteArray(4)
            require(stream.read(signature) == signature.size &&
                signature.contentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04))) {
                "Office dosyası geçerli bir OOXML/ZIP başlangıcına sahip değil."
            }
        }
        ZipFile(privateStagedFile).use { zip ->
            val names = HashSet<String>()
            var files = 0
            var expanded = 0L
            // ZIP names alone are not evidence of readable OOXML package parts.
            // Parts are case-sensitive inside the package, must be real files,
            // and cannot be empty placeholders or directory lookalikes.
            var contentTypesVerified = false
            var requiredPartVerified = false
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry: ZipEntry = entries.nextElement()
                require(++files <= MAX_ENTRIES) { "Office dosyasında çok fazla ZIP girdisi var." }
                val path = entry.name
                val pathParts = path.trimEnd('/').split('/')
                require(path.isNotBlank() && !path.startsWith('/') &&
                    !path.contains('\\') && !path.contains(':') &&
                    path.none { it.code < 32 } &&
                    pathParts.all { it.isNotBlank() && it != "." && it != ".." }) {
                    "Office ZIP dosyasında güvensiz yol bulundu."
                }
                require(names.add(path.lowercase(java.util.Locale.ROOT))) {
                    "Office ZIP dosyasında yinelenen dosya bulundu."
                }
                require(!path.endsWith("vbaProject.bin", ignoreCase = true)) {
                    "Makro içeren Office dosyaları desteklenmiyor."
                }
                // Do not count a directory with a misleading required-part
                // name as an actual OOXML document. Rejected even if a later
                // filename normalization would otherwise match.
                if (entry.isDirectory) {
                    require(path != required && path != "[Content_Types].xml") {
                        "Office paketinin zorunlu parçası bir dosya olmalı."
                    }
                    continue
                }
                require(entry.method == ZipEntry.DEFLATED || entry.method == ZipEntry.STORED)
                require(entry.size >= 0 && entry.size <= MAX_EXPANDED_BYTES &&
                    entry.compressedSize >= 0) {
                    "Office ZIP içerik boyutu doğrulanamadı."
                }
                expanded += entry.size
                require(expanded <= MAX_EXPANDED_BYTES) { "Office ZIP şişirme sınırı aşıldı." }
                if (entry.size > 65536L) {
                    require(entry.compressedSize > 0 &&
                        entry.size / entry.compressedSize <= 200L) {
                        "Office ZIP sıkıştırma oranı güvenli sınırı aşıyor."
                    }
                }
                if (path == "[Content_Types].xml") {
                    require(entry.size in 1..MAX_METADATA_XML_BYTES) {
                        "Office içerik türü tanımı boş veya büyük."
                    }
                    contentTypesVerified = true
                }
                if (path == required) {
                    require(entry.size in 1..MAX_EXPANDED_BYTES) {
                        "Office belgesinin asıl XML içeriği boş veya büyük."
                    }
                    requiredPartVerified = true
                }
            }
            require(contentTypesVerified && requiredPartVerified) {
                "Dosya, belirtilen Office belgesi yapısına sahip değil."
            }
        }
    }
}
