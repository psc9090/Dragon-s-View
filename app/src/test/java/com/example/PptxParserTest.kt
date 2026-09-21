package com.example

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.dragonview.app.router.FileFormat
import com.dragonview.app.router.FormatRouter
import com.dragonview.app.viewer.pptx.PptxParser
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
class PptxParserTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun testValidPptxParsingWithStreamingXmlPullParser() = runBlocking {
        val samplePptxBytes = createMockPptx()

        val file = File(context.cacheDir, "test_valid.pptx").apply {
            FileOutputStream(this).use { it.write(samplePptxBytes) }
        }
        val uri = Uri.fromFile(file)

        // 1. Check FormatRouter detection
        val detection = FormatRouter.detectFormat(context, uri)
        assertEquals(FileFormat.PPTX, detection.format)
        assertFalse(detection.isCorrupted)

        // 2. Check PptxParser streaming result
        val parseResult = PptxParser.parse(context, uri)
        assertTrue(parseResult.isSuccess)
        val presentation = parseResult.getOrNull()
        assertNotNull(presentation)
        assertEquals(2, presentation!!.slideCount)
        assertEquals(12192000L, presentation.slideWidthEmu)
        assertEquals(6858000L, presentation.slideHeightEmu)

        val slide1 = presentation.slides[0]
        assertEquals("DragonView Pitch Deck", slide1.title)
        assertTrue(slide1.elements.any { it is SlideElement.TextBox })

        val textBox = slide1.elements.filterIsInstance<SlideElement.TextBox>().first()
        assertTrue(textBox.plainText().contains("DragonView Pitch Deck"))
    }

    @Test
    fun testCorruptedPptxMissingPresentationXmlHandledGracefully() = runBlocking {
        // Create a ZIP without ppt/presentation.xml
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("other/dummy.txt"))
            zos.write("hello".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        val file = File(context.cacheDir, "test_corrupt.pptx").apply {
            FileOutputStream(this).use { it.write(baos.toByteArray()) }
        }
        val uri = Uri.fromFile(file)

        val parseResult = PptxParser.parse(context, uri)
        assertTrue(parseResult.isFailure)
        val errorMsg = parseResult.exceptionOrNull()?.message ?: ""
        assertTrue(errorMsg.contains("corrupted") || errorMsg.contains("unsupported"))
    }

    @Test
    fun testUnsupportedElementsIgnoredGracefully() = runBlocking {
        val sampleBytesWithUnsupported = createMockPptx(withUnsupportedTags = true)

        val file = File(context.cacheDir, "test_unsupported.pptx").apply {
            FileOutputStream(this).use { it.write(sampleBytesWithUnsupported) }
        }
        val uri = Uri.fromFile(file)

        val parseResult = PptxParser.parse(context, uri)
        assertTrue(parseResult.isSuccess)
        val presentation = parseResult.getOrNull()
        assertNotNull(presentation)
        assertTrue(presentation!!.slides.isNotEmpty())
    }

    private fun createMockPptx(withUnsupportedTags: Boolean = false): ByteArray {
        val contentTypesXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>
  <Override PartName="/ppt/slides/slide1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>
  <Override PartName="/ppt/slides/slide2.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>
</Types>""".trimIndent()

        val rootRelsXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>
</Relationships>""".trimIndent()

        val presentationXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:presentation xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
  <p:sldSz cx="12192000" cy="6858000"/>
  <p:sldIdLst>
    <p:sldId id="256" r:id="rId1"/>
    <p:sldId id="257" r:id="rId2"/>
  </p:sldIdLst>
</p:presentation>""".trimIndent()

        val presentationRelsXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide2.xml"/>
</Relationships>""".trimIndent()

        val unsupportedXml = if (withUnsupportedTags) {
            """<p:graphicFrame><p:nvGraphicFramePr><p:cNvPr id="99" name="UnsupportedChart"/></p:nvGraphicFramePr><a:graphic><a:graphicData><c:chart xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart" r:id="rId9"/></a:graphicData></a:graphic></p:graphicFrame>"""
        } else ""

        val slide1Xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
  <p:cSld>
    <p:spTree>
      <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:grpSpPr/></p:nvGrpSpPr>
      <p:sp>
        <p:spPr>
          <a:xfrm><a:off x="1000000" y="1500000"/><a:ext cx="10192000" cy="1800000"/></a:xfrm>
        </p:spPr>
        <p:txBody>
          <a:bodyPr/>
          <a:p>
            <a:pPr algn="ctr"/>
            <a:r>
              <a:rPr b="1" sz="4000"/>
              <a:t>DragonView Pitch Deck</a:t>
            </a:r>
          </a:p>
        </p:txBody>
      </p:sp>
      $unsupportedXml
    </p:spTree>
  </p:cSld>
</p:sld>""".trimIndent()

        val slide2Xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
  <p:cSld>
    <p:spTree>
      <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:grpSpPr/></p:nvGrpSpPr>
      <p:sp>
        <p:spPr>
          <a:xfrm><a:off x="800000" y="600000"/><a:ext cx="10500000" cy="900000"/></a:xfrm>
        </p:spPr>
        <p:txBody>
          <a:bodyPr/>
          <a:p>
            <a:r><a:rPr b="1" sz="2800"/><a:t>Slide Two Content</a:t></a:r>
          </a:p>
        </p:txBody>
      </p:sp>
    </p:spTree>
  </p:cSld>
</p:sld>""".trimIndent()

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("[Content_Types].xml"))
            zos.write(contentTypesXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("_rels/.rels"))
            zos.write(rootRelsXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("ppt/presentation.xml"))
            zos.write(presentationXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("ppt/_rels/presentation.xml.rels"))
            zos.write(presentationRelsXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("ppt/slides/slide1.xml"))
            zos.write(slide1Xml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("ppt/slides/slide2.xml"))
            zos.write(slide2Xml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return baos.toByteArray()
    }
}
