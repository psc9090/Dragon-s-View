// app/src/test/java/com/example/OdpParserTest.kt
package com.example

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.dragonview.app.router.FileFormat
import com.dragonview.app.router.FormatRouter
import com.dragonview.app.viewer.odp.OdpParser
import com.dragonview.app.viewer.pptx.PptxAlignment
import com.dragonview.app.viewer.pptx.SlideElement
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
class OdpParserTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun testValidOdpParsing() = runBlocking {
        val sampleOdpBytes = createMockOdp()
        val file = File(context.cacheDir, "presentation.odp").apply {
            FileOutputStream(this).use { it.write(sampleOdpBytes) }
        }
        val uri = Uri.fromFile(file)

        // 1. Format detection
        val detection = FormatRouter.detectFormat(context, uri)
        assertEquals(FileFormat.ODP, detection.format)
        assertFalse(detection.isCorrupted)

        // 2. Parse presentation
        val result = OdpParser.parse(context, uri, detection.format)
        assertTrue(result.isSuccess)
        val presentation = result.getOrNull()
        assertNotNull(presentation)
        assertEquals(2, presentation!!.slideCount)

        // Verify Slide 1
        val slide1 = presentation.slides[0]
        assertEquals("DragonView Architecture", slide1.title)
        assertTrue(slide1.elements.isNotEmpty())

        val titleBox = slide1.elements.filterIsInstance<SlideElement.TextBox>().firstOrNull()
        assertNotNull(titleBox)
        assertEquals(1, titleBox!!.paragraphs.size)
        val firstRun = titleBox.paragraphs[0].runs[0]
        assertEquals("DragonView Architecture", firstRun.text)
        assertTrue(firstRun.isBold)
        assertEquals("#D84315", firstRun.colorHex)
        assertEquals(28f, firstRun.fontSizePt ?: 0f, 0.5f)

        // Verify Slide 2 with list
        val slide2 = presentation.slides[1]
        assertEquals("Key Benchmarks", slide2.title)
        val listBox = slide2.elements.filterIsInstance<SlideElement.TextBox>().firstOrNull { it.paragraphs.size > 1 }
        assertNotNull(listBox)
        assertTrue(listBox!!.paragraphs.any { it.isBullet })
    }

    @Test
    fun testFlatOdpParsing() = runBlocking {
        val sampleFodpBytes = createMockFodp()
        val file = File(context.cacheDir, "deck.fodp").apply {
            FileOutputStream(this).use { it.write(sampleFodpBytes) }
        }
        val uri = Uri.fromFile(file)

        // 1. Format detection
        val detection = FormatRouter.detectFormat(context, uri)
        assertEquals(FileFormat.ODP_FLAT, detection.format)
        assertFalse(detection.isCorrupted)

        // 2. Parse presentation directly without unzipping
        val result = OdpParser.parse(context, uri, detection.format)
        assertTrue(result.isSuccess)
        val presentation = result.getOrNull()
        assertNotNull(presentation)
        assertEquals(1, presentation!!.slideCount)

        val slide = presentation.slides[0]
        assertTrue(slide.title?.contains("Flat XML") == true)
        val textBox = slide.elements.filterIsInstance<SlideElement.TextBox>().firstOrNull()
        assertNotNull(textBox)
    }

    @Test
    fun testOtpTemplateDetection() = runBlocking {
        val sampleOdpBytes = createMockOdp()
        val file = File(context.cacheDir, "template.otp").apply {
            FileOutputStream(this).use { it.write(sampleOdpBytes) }
        }
        val uri = Uri.fromFile(file)

        val detection = FormatRouter.detectFormat(context, uri)
        assertEquals(FileFormat.ODP_TEMPLATE, detection.format)
        assertFalse(detection.isCorrupted)

        val result = OdpParser.parse(context, uri, detection.format)
        assertTrue(result.isSuccess)
    }

    @Test
    fun testCorruptedOdpHandling() = runBlocking {
        // Zip archive without content.xml
        val corruptedBytes = ByteArrayOutputStream().apply {
            ZipOutputStream(this).use { zos ->
                zos.putNextEntry(ZipEntry("mimetype"))
                zos.write("application/vnd.oasis.opendocument.presentation".toByteArray())
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("styles.xml"))
                zos.write("<xml></xml>".toByteArray())
                zos.closeEntry()
            }
        }.toByteArray()

        val file = File(context.cacheDir, "corrupted.odp").apply {
            FileOutputStream(this).use { it.write(corruptedBytes) }
        }
        val uri = Uri.fromFile(file)

        val result = OdpParser.parse(context, uri, FileFormat.ODP)
        assertTrue(result.isFailure)
        assertEquals(OdpParser.CORRUPTED_ERROR_MESSAGE, result.exceptionOrNull()?.message)
    }

    @Test
    fun testSvgLengthConversion() {
        // 1 inch = 914,400 EMUs
        assertEquals(914400L, OdpParser.parseLengthToEmu("1.0in"))
        assertEquals(914400L, OdpParser.parseLengthToEmu("1in"))

        // 1 cm = 360,000 EMUs
        assertEquals(360000L, OdpParser.parseLengthToEmu("1.0cm"))
        assertEquals(900000L, OdpParser.parseLengthToEmu("2.5cm"))

        // 1 mm = 36,000 EMUs
        assertEquals(36000L, OdpParser.parseLengthToEmu("1mm"))
        assertEquals(360000L, OdpParser.parseLengthToEmu("10mm"))

        // 1 pt = 12,700 EMUs
        assertEquals(12700L, OdpParser.parseLengthToEmu("1pt"))
        assertEquals(914400L, OdpParser.parseLengthToEmu("72pt"))
    }

    private fun createMockOdp(): ByteArray {
        val stylesXml = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-styles xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
    xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0"
    xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
    xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0">
  <office:styles>
    <style:style style:name="TitleStyle" style:family="presentation">
      <style:text-properties fo:font-size="28pt" fo:font-weight="bold" fo:color="#D84315"/>
      <style:paragraph-properties fo:text-align="center"/>
    </style:style>
    <style:style style:name="BodyStyle" style:family="presentation">
      <style:text-properties fo:font-size="16pt" fo:color="#1C1B1F"/>
    </style:style>
  </office:styles>
</office:document-styles>""".trimIndent()

        val contentXml = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
    xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0"
    xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
    xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
    xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"
    xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0">
  <office:automatic-styles>
    <style:page-layout style:name="PM1">
      <style:page-layout-properties fo:page-width="28cm" fo:page-height="21cm"/>
    </style:page-layout>
  </office:automatic-styles>
  <office:body>
    <office:presentation>
      <draw:page draw:name="Slide 1">
        <draw:frame svg:x="2.0cm" svg:y="3.0cm" svg:width="24.0cm" svg:height="4.0cm">
          <draw:text-box>
            <text:p text:style-name="TitleStyle"><text:span>DragonView Architecture</text:span></text:p>
          </draw:text-box>
        </draw:frame>
      </draw:page>
      <draw:page draw:name="Slide 2">
        <draw:frame svg:x="2.0cm" svg:y="3.0cm" svg:width="24.0cm" svg:height="3.0cm">
          <draw:text-box>
            <text:p text:style-name="TitleStyle"><text:span>Key Benchmarks</text:span></text:p>
          </draw:text-box>
        </draw:frame>
        <draw:frame svg:x="3.0cm" svg:y="7.0cm" svg:width="22.0cm" svg:height="8.0cm">
          <draw:text-box>
            <text:list>
              <text:list-item><text:p text:style-name="BodyStyle"><text:span>Item 1</text:span></text:p></text:list-item>
              <text:list-item><text:p text:style-name="BodyStyle"><text:span>Item 2</text:span></text:p></text:list-item>
            </text:list>
          </draw:text-box>
        </draw:frame>
      </draw:page>
    </office:presentation>
  </office:body>
</office:document-content>""".trimIndent()

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("mimetype"))
            zos.write("application/vnd.oasis.opendocument.presentation".toByteArray(Charsets.US_ASCII))
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

    private fun createMockFodp(): ByteArray {
        val fodpXml = """<?xml version="1.0" encoding="UTF-8"?>
<office:document xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
    xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0"
    xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
    xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
    xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"
    xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0">
  <office:automatic-styles>
    <style:style style:name="TitleStyle" style:family="presentation">
      <style:text-properties fo:font-size="24pt" fo:font-weight="bold" fo:color="#E65100"/>
    </style:style>
  </office:automatic-styles>
  <office:body>
    <office:presentation>
      <draw:page draw:name="Slide 1">
        <draw:frame svg:x="2.0cm" svg:y="2.5cm" svg:width="24.0cm" svg:height="4.0cm">
          <draw:text-box>
            <text:p text:style-name="TitleStyle"><text:span>Flat XML OpenDocument Presentation</text:span></text:p>
          </draw:text-box>
        </draw:frame>
      </draw:page>
    </office:presentation>
  </office:body>
</office:document>""".trimIndent()

        return fodpXml.toByteArray(Charsets.UTF_8)
    }
}
