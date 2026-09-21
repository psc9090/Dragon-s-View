package com.example

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.dragonview.app.router.FileFormat
import com.dragonview.app.router.FormatRouter
import com.dragonview.app.viewer.docx.DocxParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DocxParserTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun testValidDocxParsingWithStreamingXmlPullParser() = runBlocking {
        val sampleDocxBytes = createMockDocx(
            withHeading = true,
            headingText = "Test Engineering Report",
            paragraphText = "Streaming XML Pull Parser verification."
        )

        val file = File(context.cacheDir, "test_valid.docx").apply {
            FileOutputStream(this).use { it.write(sampleDocxBytes) }
        }
        val uri = Uri.fromFile(file)

        // 1. Check FormatRouter detection
        val detection = FormatRouter.detectFormat(context, uri)
        assertEquals(FileFormat.DOCX, detection.format)
        assertFalse(detection.isCorrupted)

        // 2. Check DocxParser streaming result
        val parseResult = DocxParser.parse(context, uri)
        assertTrue(parseResult.isSuccess)
        val doc = parseResult.getOrNull()
        assertNotNull(doc)
        assertTrue(doc!!.elements.isNotEmpty())
        assertEquals(2, doc.paragraphCount)
    }

    @Test
    fun testCorruptedDocxMissingDocumentXmlHandledGracefully() = runBlocking {
        // Create a ZIP without word/document.xml
        val baos = java.io.ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("other/file.txt"))
            zos.write("hello".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        val file = File(context.cacheDir, "test_corrupt.docx").apply {
            FileOutputStream(this).use { it.write(baos.toByteArray()) }
        }
        val uri = Uri.fromFile(file)

        val parseResult = DocxParser.parse(context, uri)
        assertTrue(parseResult.isFailure)
        val errorMsg = parseResult.exceptionOrNull()?.message ?: ""
        assertTrue(errorMsg.contains("corrupted") || errorMsg.contains("unsupported"))
    }

    private fun createMockDocx(withHeading: Boolean, headingText: String, paragraphText: String): ByteArray {
        val docXml = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""")
            if (withHeading) {
                append("""<w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:rPr><w:b/></w:rPr><w:t>$headingText</w:t></w:r></w:p>""")
            }
            append("""<w:p><w:r><w:t>$paragraphText</w:t></w:r></w:p>""")
            append("""</w:body></w:document>""")
        }

        val baos = java.io.ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("[Content_Types].xml"))
            zos.write("<Types></Types>".toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("word/document.xml"))
            zos.write(docXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return baos.toByteArray()
    }
}
