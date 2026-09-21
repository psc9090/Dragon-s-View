// app/src/test/java/com/example/OdtParserTest.kt
package com.example

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.dragonview.app.router.FileFormat
import com.dragonview.app.router.FormatRouter
import com.dragonview.app.viewer.document.DocumentElement
import com.dragonview.app.viewer.odt.OdtParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OdtParserTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun testValidOdtParsingWithStreamingXmlPullParser() = runBlocking {
        val sampleOdtBytes = createMockOdt(
            headingText = "DragonView ODT Engine",
            paragraphText = "Testing streaming OpenDocument Text extraction."
        )

        val file = File(context.cacheDir, "test_valid.odt").apply {
            FileOutputStream(this).use { it.write(sampleOdtBytes) }
        }
        val uri = Uri.fromFile(file)

        // 1. Check FormatRouter detection
        val detection = FormatRouter.detectFormat(context, uri)
        assertEquals(FileFormat.ODT, detection.format)
        assertFalse(detection.isCorrupted)

        // 2. Check OdtParser streaming result
        val parseResult = OdtParser.parse(context, uri, detection.format)
        assertTrue(parseResult.isSuccess)
        val doc = parseResult.getOrNull()
        assertNotNull(doc)
        assertTrue(doc!!.elements.isNotEmpty())
        assertTrue(doc.paragraphCount >= 2)
        assertEquals(1, doc.tableCount)

        // Check heading run and style
        val heading = doc.elements.filterIsInstance<DocumentElement.Paragraph>().firstOrNull { it.isHeading }
        assertNotNull(heading)
        assertEquals(1, heading!!.headingLevel)
        assertEquals("DragonView ODT Engine", heading.runs.firstOrNull()?.text)
    }

    @Test
    fun testOttTemplateDetectionAndParsing() = runBlocking {
        val sampleOttBytes = createMockOdt(
            headingText = "Invoice Template",
            paragraphText = "Standard template layout."
        )

        val file = File(context.cacheDir, "invoice_template.ott").apply {
            FileOutputStream(this).use { it.write(sampleOttBytes) }
        }
        val uri = Uri.fromFile(file)

        val detection = FormatRouter.detectFormat(context, uri)
        assertEquals(FileFormat.ODT_TEMPLATE, detection.format)
        assertFalse(detection.isCorrupted)

        val parseResult = OdtParser.parse(context, uri, detection.format)
        assertTrue(parseResult.isSuccess)
        val doc = parseResult.getOrNull()
        assertNotNull(doc)
        assertEquals("OpenDocument Template", doc!!.formatLabel)
    }

    @Test
    fun testFodtFlatXmlDetectionAndParsing() = runBlocking {
        val fodtXml = """<?xml version="1.0" encoding="UTF-8"?>
<office:document xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
    xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0"
    xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
    xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0">
  <office:automatic-styles>
    <style:style style:name="T1" style:family="text">
      <style:text-properties fo:font-weight="bold" fo:color="#1E88E5"/>
    </style:style>
  </office:automatic-styles>
  <office:body>
    <office:text>
      <text:h text:outline-level="1">Flat XML Headline</text:h>
      <text:p><text:span text:style-name="T1">Bold Blue Span</text:span> in Flat XML.</text:p>
    </office:text>
  </office:body>
</office:document>""".trimIndent()

        val file = File(context.cacheDir, "document.fodt").apply {
            FileOutputStream(this).use { it.write(fodtXml.toByteArray(Charsets.UTF_8)) }
        }
        val uri = Uri.fromFile(file)

        val detection = FormatRouter.detectFormat(context, uri)
        assertEquals(FileFormat.ODT_FLAT, detection.format)
        assertFalse(detection.isCorrupted)

        val parseResult = OdtParser.parse(context, uri, detection.format)
        assertTrue(parseResult.isSuccess)
        val doc = parseResult.getOrNull()
        assertNotNull(doc)
        assertEquals("Flat OpenDocument XML", doc!!.formatLabel)
        assertEquals(2, doc.paragraphCount)
    }

    @Test
    fun testCorruptedOdtMissingContentXml() = runBlocking {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("mimetype"))
            zos.write("application/vnd.oasis.opendocument.text".toByteArray(Charsets.US_ASCII))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("meta.xml"))
            zos.write("<meta></meta>".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        val file = File(context.cacheDir, "missing_content.odt").apply {
            FileOutputStream(this).use { it.write(baos.toByteArray()) }
        }
        val uri = Uri.fromFile(file)

        val parseResult = OdtParser.parse(context, uri, FileFormat.ODT)
        assertTrue(parseResult.isFailure)
        val message = parseResult.exceptionOrNull()?.message ?: ""
        assertEquals("This ODT file appears corrupted or uses an unsupported structure.", message)
    }

    @Test
    fun testCorruptedOdtInvalidZipBytes() = runBlocking {
        val junkBytes = "NOT A ZIP CONTAINER AT ALL".toByteArray(Charsets.UTF_8)
        val file = File(context.cacheDir, "junk.odt").apply {
            FileOutputStream(this).use { it.write(junkBytes) }
        }
        val uri = Uri.fromFile(file)

        val parseResult = OdtParser.parse(context, uri, FileFormat.ODT)
        assertTrue(parseResult.isFailure)
        val message = parseResult.exceptionOrNull()?.message ?: ""
        assertEquals("This ODT file appears corrupted or uses an unsupported structure.", message)
    }

    private fun createMockOdt(headingText: String, paragraphText: String): ByteArray {
        val stylesXml = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-styles xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
    xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0"
    xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0">
  <office:styles>
    <style:style style:name="Heading_1" style:family="paragraph">
      <style:text-properties fo:font-size="20pt" fo:font-weight="bold" fo:color="#8E24AA"/>
    </style:style>
    <style:style style:name="BoldRun" style:family="text">
      <style:text-properties fo:font-weight="bold" fo:color="#D81B60"/>
    </style:style>
  </office:styles>
</office:document-styles>""".trimIndent()

        val contentXml = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
    xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0"
    xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
    xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0"
    xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0">
  <office:automatic-styles>
    <style:style style:name="T1" style:family="text">
      <style:text-properties fo:font-style="italic" fo:color="#1E88E5"/>
    </style:style>
  </office:automatic-styles>
  <office:body>
    <office:text>
      <text:h text:outline-level="1" text:style-name="Heading_1">$headingText</text:h>
      <text:p><text:span text:style-name="T1">Styled Run: </text:span>$paragraphText</text:p>
      <table:table table:name="Table1">
        <table:table-row>
          <table:table-cell><text:p>Cell 1A</text:p></table:table-cell>
          <table:table-cell><text:p>Cell 1B</text:p></table:table-cell>
        </table:table-row>
      </table:table>
    </office:text>
  </office:body>
</office:document-content>""".trimIndent()

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("mimetype"))
            zos.write("application/vnd.oasis.opendocument.text".toByteArray(Charsets.US_ASCII))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("styles.xml"))
            zos.write(stylesXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("content.xml"))
            zos.write(contentXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return baos.toByteArray()
    }
}
