// app/src/test/java/com/example/OdgParserTest.kt
package com.example

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.dragonview.app.router.FileFormat
import com.dragonview.app.router.FormatRouter
import com.dragonview.app.viewer.odg.DrawingElement
import com.dragonview.app.viewer.odg.OdgParser
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
class OdgParserTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun testValidOdgParsing() = runBlocking {
        val sampleOdgBytes = createMockOdg()
        val file = File(context.cacheDir, "test_diagram.odg").apply {
            FileOutputStream(this).use { it.write(sampleOdgBytes) }
        }
        val uri = Uri.fromFile(file)

        // 1. Format Detection
        val detection = FormatRouter.detectFormat(context, uri)
        assertEquals(FileFormat.ODG, detection.format)
        assertFalse(detection.isCorrupted)

        // 2. Parse Drawing
        val result = OdgParser.parse(context, uri, detection.format)
        assertTrue("Parsing should succeed", result.isSuccess)
        val doc = result.getOrNull()
        assertNotNull(doc)

        // Check elements
        val elements = doc!!.elements
        assertTrue("Document should have elements", elements.isNotEmpty())

        val rect = elements.filterIsInstance<DrawingElement.Rect>().firstOrNull()
        assertNotNull("Should contain a rect", rect)
        assertTrue("Rect width should be positive", rect!!.width > 0f)
        assertEquals("#3B151C", rect.fill?.colorHex)

        val ellipse = elements.filterIsInstance<DrawingElement.Ellipse>().firstOrNull()
        assertNotNull("Should contain an ellipse", ellipse)
        assertTrue("Ellipse rx should be positive", ellipse!!.rx > 0f)

        val line = elements.filterIsInstance<DrawingElement.Line>().firstOrNull()
        assertNotNull("Should contain a line", line)
        assertEquals("#FF5252", line!!.stroke?.colorHex)

        val polygon = elements.filterIsInstance<DrawingElement.Polygon>().firstOrNull()
        assertNotNull("Should contain a polygon", polygon)
        assertTrue("Polygon should have points", polygon!!.points.size >= 3)

        val path = elements.filterIsInstance<DrawingElement.Path>().firstOrNull()
        assertNotNull("Should contain an SVG path", path)
        assertTrue("Path should have SVG data", path!!.svgPathData.isNotEmpty())

        val group = elements.filterIsInstance<DrawingElement.Group>().firstOrNull()
        assertNotNull("Should contain a group", group)
        assertEquals("Group should contain children", 2, group!!.children.size)
        assertNotNull("Group should have transform", group.transform)
    }

    @Test
    fun testValidFodgParsing() = runBlocking {
        val fodgXml = """<?xml version="1.0" encoding="UTF-8"?>
<office:document xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
    xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0"
    xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
    xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
    xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0"
    xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0"
    office:mimetype="application/vnd.oasis.opendocument.graphics-flat-xml">
  <office:body>
    <office:drawing>
      <draw:page draw:name="Flat Page">
        <draw:rect svg:x="1.0cm" svg:y="2.0cm" svg:width="10.0cm" svg:height="5.0cm">
          <text:p><text:span>Flat XML Box</text:span></text:p>
        </draw:rect>
      </draw:page>
    </office:drawing>
  </office:body>
</office:document>""".trimIndent()

        val file = File(context.cacheDir, "diagram.fodg").apply {
            writeText(fodgXml)
        }
        val uri = Uri.fromFile(file)

        val detection = FormatRouter.detectFormat(context, uri)
        assertEquals(FileFormat.ODG_FLAT, detection.format)

        val result = OdgParser.parse(context, uri, detection.format)
        assertTrue("Parsing should succeed", result.isSuccess)
        val doc = result.getOrNull()
        assertNotNull(doc)
        assertEquals(1, doc!!.elements.size)
        val rect = doc.elements[0] as DrawingElement.Rect
        assertEquals(1, rect.text?.paragraphs?.size)
        assertEquals("Flat XML Box", rect.text?.paragraphs?.get(0)?.runs?.get(0)?.text)
    }

    @Test
    fun testCorruptedOdgHandling() = runBlocking {
        // Create corrupted ZIP file with missing content.xml
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("mimetype"))
            zos.write("application/vnd.oasis.opendocument.graphics".toByteArray())
            zos.closeEntry()
        }

        val file = File(context.cacheDir, "corrupted.odg").apply {
            FileOutputStream(this).use { it.write(baos.toByteArray()) }
        }
        val uri = Uri.fromFile(file)

        val result = OdgParser.parse(context, uri, FileFormat.ODG)
        assertTrue("Parsing should fail for missing content.xml", result.isFailure)
        assertEquals(OdgParser.CORRUPTED_ERROR_MESSAGE, result.exceptionOrNull()?.message)
    }

    @Test
    fun testTransformParsing() {
        val matrixTransform = "matrix(1.0, 0.0, 0.0, 1.0, 50.0, 100.0)"
        val matrix = OdgParser.parseTransform(matrixTransform)
        assertNotNull(matrix)

        val values = FloatArray(9)
        matrix!!.getValues(values)
        assertEquals(50f, values[2], 0.01f) // X translation
        assertEquals(100f, values[5], 0.01f) // Y translation

        val translateTransform = "translate(30, 40)"
        val trMatrix = OdgParser.parseTransform(translateTransform)
        assertNotNull(trMatrix)
        trMatrix!!.getValues(values)
        assertEquals(30f, values[2], 0.01f)
        assertEquals(40f, values[5], 0.01f)
    }

    @Test
    fun testUnitConversion() {
        // 1 inch = 96 px @ 96 DPI
        val inPx = OdgParser.unitToPx("1in")
        assertEquals(96f, inPx, 0.5f)

        // 2.54 cm = 1 in = 96 px
        val cmPx = OdgParser.unitToPx("2.54cm")
        assertEquals(96f, cmPx, 0.5f)

        // 72 pt = 1 in = 96 px
        val ptPx = OdgParser.unitToPx("72pt")
        assertEquals(96f, ptPx, 0.5f)
    }

    private fun createMockOdg(): ByteArray {
        val stylesXml = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-styles xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
    xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0"
    xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
    xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0">
  <office:styles>
    <style:style style:name="HeaderBox" style:family="graphic">
      <style:graphic-properties draw:fill="solid" draw:fill-color="#3B151C" svg:stroke-color="#D32F2F" svg:stroke-width="0.08cm" draw:stroke="solid"/>
    </style:style>
    <style:style style:name="CyanNode" style:family="graphic">
      <style:graphic-properties draw:fill="solid" draw:fill-color="#0E2E3B" svg:stroke-color="#00ACC1" svg:stroke-width="0.06cm" draw:stroke="solid"/>
    </style:style>
    <style:style style:name="AccentStroke" style:family="graphic">
      <style:graphic-properties svg:stroke-color="#FF5252" svg:stroke-width="0.08cm" draw:stroke="solid"/>
    </style:style>
  </office:styles>
</office:document-styles>""".trimIndent()

        val contentXml = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-content xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
    xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0"
    xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
    xmlns:draw="urn:oasis:names:tc:opendocument:xmlns:drawing:1.0"
    xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0"
    xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0">
  <office:body>
    <office:drawing>
      <draw:page draw:name="Page 1">
        <draw:rect svg:x="1.0cm" svg:y="1.0cm" svg:width="10.0cm" svg:height="4.0cm" draw:style-name="HeaderBox">
          <text:p><text:span>Test Rect</text:span></text:p>
        </draw:rect>
        <draw:ellipse svg:cx="4.0cm" svg:cy="7.0cm" svg:rx="2.0cm" svg:ry="1.5cm" draw:style-name="CyanNode"/>
        <draw:line svg:x1="7.0cm" svg:y1="7.0cm" svg:x2="10.0cm" svg:y2="7.0cm" draw:style-name="AccentStroke"/>
        <draw:polygon draw:points="12.0cm,5.0cm 15.0cm,5.0cm 16.0cm,8.0cm 13.0cm,8.0cm" draw:style-name="CyanNode"/>
        <draw:path svg:d="M 10 10 L 50 50 L 90 10" svg:x="1.0cm" svg:y="10.0cm" svg:width="8.0cm" svg:height="3.0cm" draw:style-name="AccentStroke"/>
        <draw:g draw:transform="translate(20, 20)">
          <draw:rect svg:x="0.5cm" svg:y="0.5cm" svg:width="3.0cm" svg:height="1.5cm"/>
          <draw:rect svg:x="4.0cm" svg:y="0.5cm" svg:width="3.0cm" svg:height="1.5cm"/>
        </draw:g>
      </draw:page>
    </office:drawing>
  </office:body>
</office:document-content>""".trimIndent()

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("mimetype"))
            zos.write("application/vnd.oasis.opendocument.graphics".toByteArray(Charsets.US_ASCII))
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
