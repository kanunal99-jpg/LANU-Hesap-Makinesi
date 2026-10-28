package com.example

import com.example.core.media.PrivateMediaCrypto
import com.example.core.media.PrivateOfficeDocumentPreflight
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class PrivateOfficeDocumentPreflightTest {
    @get:Rule val temp = TemporaryFolder()

    private fun officeArchive(name: String, vararg parts: String): File {
        val target = temp.newFile(name)
        ZipOutputStream(FileOutputStream(target)).use { zip ->
            for (path in parts) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(("<test>" + path + "</test>").toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return target
    }

    private fun assertRejected(action: () -> Unit) {
        var failed = false
        try { action() } catch (_: IllegalArgumentException) { failed = true }
        assertTrue("Unsafe OOXML input must be rejected", failed)
    }

    @Test fun docxXlsxPptxAreVerifiedAndEncryptedRoundTrip() {
        val variants = listOf(
            Triple("good.docx", PrivateOfficeDocumentPreflight.DOCX_MIME, "word/document.xml"),
            Triple("good.xlsx", PrivateOfficeDocumentPreflight.XLSX_MIME, "xl/workbook.xml"),
            Triple("good.pptx", PrivateOfficeDocumentPreflight.PPTX_MIME, "ppt/presentation.xml")
        )
        val outputDirectory = temp.newFolder("ciphertext")
        for ((name, mime, part) in variants) {
            val document = officeArchive(name, "[Content_Types].xml", "_rels/.rels", part)
            PrivateOfficeDocumentPreflight.validate(document, mime)

            // An unvalidated InputStream cannot bypass the ZIP safety preflight.
            assertRejected {
                PrivateMediaCrypto.encryptToPrivateFile(
                    document.inputStream().use { it.readBytes().inputStream() },
                    document.length(), mime, outputDirectory)
            }
            val encrypted = PrivateMediaCrypto.encryptOfficeDocumentToPrivateFile(
                document, mime, outputDirectory)
            try {
                assertEquals(mime, encrypted.manifest.mime)
                assertFalse(encrypted.ciphertextFile.readBytes().contentEquals(document.readBytes()))
                val decoded = PrivateMediaCrypto.decryptToVerifiedPrivateFile(
                    encrypted.ciphertextFile, encrypted.manifest, encrypted.keyMaterial,
                    outputDirectory)
                try { assertArrayEquals(document.readBytes(), decoded.readBytes()) }
                finally { decoded.delete() }
            } finally {
                encrypted.destroyKey()
                encrypted.ciphertextFile.delete()
            }
        }
    }

    @Test fun mimeSpoofingMissingPartsAndMacroEnabledArchivesAreRejected() {
        val fake = officeArchive("fake.xlsx", "[Content_Types].xml", "word/document.xml")
        assertRejected { PrivateOfficeDocumentPreflight.validate(fake, PrivateOfficeDocumentPreflight.XLSX_MIME) }
        val macro = officeArchive("macro.xlsx", "[Content_Types].xml",
            "xl/workbook.xml", "xl/vbaProject.bin")
        assertRejected { PrivateOfficeDocumentPreflight.validate(macro, PrivateOfficeDocumentPreflight.XLSX_MIME) }
        val pdfDisguised = temp.newFile("notzip.xlsx")
        pdfDisguised.writeText("%PDF-1.7")
        assertRejected { PrivateOfficeDocumentPreflight.validate(pdfDisguised, PrivateOfficeDocumentPreflight.XLSX_MIME) }
        assertRejected { PrivateOfficeDocumentPreflight.validate(fake, "application/x-msdownload") }
    }

    @Test fun traversalBackslashAbsoluteAndDuplicateZipPathsAreRejected() {
        for (path in listOf("../xl/workbook.xml", "/xl/workbook.xml",
            "xl/../workbook.xml", "xl\\workbook.xml", "xl//workbook.xml")) {
            val unsafe = officeArchive("bad" + path.hashCode() + ".xlsx",
                "[Content_Types].xml", "xl/workbook.xml", path)
            assertRejected {
                PrivateOfficeDocumentPreflight.validate(
                    unsafe, PrivateOfficeDocumentPreflight.XLSX_MIME)
            }
        }
        val duplicate = officeArchive("collision.xlsx", "[Content_Types].xml",
            "xl/workbook.xml", "XL/WORKBOOK.XML")
        assertRejected { PrivateOfficeDocumentPreflight.validate(duplicate, PrivateOfficeDocumentPreflight.XLSX_MIME) }
    }

    @Test fun zipBombLikeCompressionRatioIsRejectedWithoutExtracting() {
        val file = temp.newFile("bomb.xlsx")
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            for ((name, body) in listOf(
                "[Content_Types].xml" to "<Types/>".toByteArray(),
                "xl/workbook.xml" to ByteArray(512 * 1024)
            )) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(body)
                zip.closeEntry()
            }
        }
        assertRejected {
            PrivateOfficeDocumentPreflight.validate(file, PrivateOfficeDocumentPreflight.XLSX_MIME)
        }
    }
    @Test fun emptyOrCaseSpoofedRequiredXmlPartsCannotPassStructuralGate() {
        val formats = listOf(
            Triple("reject-empty-docx.docx", PrivateOfficeDocumentPreflight.DOCX_MIME, "word/document.xml"),
            Triple("reject-empty-xlsx.xlsx", PrivateOfficeDocumentPreflight.XLSX_MIME, "xl/workbook.xml"),
            Triple("reject-empty-pptx.pptx", PrivateOfficeDocumentPreflight.PPTX_MIME, "ppt/presentation.xml")
        )
        for ((name, mime, required) in formats) {
            val emptyRequired = temp.newFile(name)
            ZipOutputStream(FileOutputStream(emptyRequired)).use { zip ->
                zip.putNextEntry(ZipEntry("[Content_Types].xml"))
                zip.write("<Types/>".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(required))
                zip.closeEntry() // Empty placeholder is not a valid document part
            }
            assertRejected { PrivateOfficeDocumentPreflight.validate(emptyRequired, mime) }

            val wrongCase = officeArchive("wrong-case-$name", "[Content_Types].xml", required.uppercase())
            assertRejected { PrivateOfficeDocumentPreflight.validate(wrongCase, mime) }

            val correct = officeArchive("nonempty-$name", "[Content_Types].xml", required)
            PrivateOfficeDocumentPreflight.validate(correct, mime)
        }
    }

    @Test fun missingOrDirectoryOnlyContentTypesCannotPassStructuralGate() {
        val mime = PrivateOfficeDocumentPreflight.DOCX_MIME
        val empty = temp.newFile("missing-types.docx")
        ZipOutputStream(FileOutputStream(empty)).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write("<document/>".toByteArray())
            zip.closeEntry()
        }
        assertRejected { PrivateOfficeDocumentPreflight.validate(empty, mime) }

        for (directory in listOf("[Content_Types].xml/", "word/document.xml/")) {
            val spoofed = temp.newFile("fake-dir-" + directory.hashCode() + ".docx")
            ZipOutputStream(FileOutputStream(spoofed)).use { zip ->
                zip.putNextEntry(ZipEntry("[Content_Types].xml"))
                zip.write("<Types/>".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("word/document.xml"))
                zip.write("<document/>".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(directory))
                zip.closeEntry()
            }
            assertRejected { PrivateOfficeDocumentPreflight.validate(spoofed, mime) }
        }
    }

}
