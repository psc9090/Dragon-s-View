// app/src/test/java/com/example/OdsParserTest.kt
package com.example

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.dragonview.app.router.FileFormat
import com.dragonview.app.router.FormatRouter
import com.dragonview.app.viewer.ods.OdsParser
import com.dragonview.app.viewer.xlsx.CellType
import com.dragonview.app.viewer.xlsx.SheetLoadState
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
class OdsParserTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun testValidOdsParsingWithRepeatCompression() = runBlocking {
        val sampleOdsBytes = createMockOds()

        val file = File(context.cacheDir, "financial_report.ods").apply {
            FileOutputStream(this).use { it.write(sampleOdsBytes) }
        }
        val uri = Uri.fromFile(file)

        // 1. Format detection
        val detection = FormatRouter.detectFormat(context, uri)
        assertEquals(FileFormat.ODS, detection.format)
        assertFalse(detection.isCorrupted)

        // 2. Parser initialization
        val parserResult = OdsParser.create(context, uri, detection.format)
        assertTrue(parserResult.isSuccess)
        val parser = parserResult.getOrNull()
        assertNotNull(parser)
        assertEquals(2, parser!!.workbook.sheets.size)
        assertEquals("Revenue", parser.workbook.sheets[0].name)
        assertEquals("Expenses", parser.workbook.sheets[1].name)

        // 3. Load Sheet 1 with repeat compression
        val sheet1State = parser.loadSheet(parser.workbook.sheets[0], maxRows = 100)
        assertTrue(sheet1State is SheetLoadState.Success)
        val sheet1 = (sheet1State as SheetLoadState.Success).sheet

        // Verify rows: only non-empty rows should be materialized
        assertTrue(sheet1.rows.isNotEmpty())
        assertEquals(3, sheet1.rows.size) // Row 1 (Header), Row 2 (Data), Row 3 (Summary)

        // Check Header Row
        val headerRow = sheet1.rows[0]
        assertEquals("Quarter", headerRow.cells[0]?.displayValue)
        assertEquals("Amount", headerRow.cells[1]?.displayValue)
        assertTrue(headerRow.cells[0]?.isBold == true)

        // Check Data Row (with currency, number, and repeat column compression)
        val dataRow = sheet1.rows[1]
        assertEquals("Q1 2026", dataRow.cells[0]?.displayValue)
        assertEquals(CellType.TEXT, dataRow.cells[0]?.type)
        assertEquals("$50,000", dataRow.cells[1]?.displayValue)
        assertEquals(CellType.NUMBER, dataRow.cells[1]?.type)

        // Notice: Column repeat compression of 1000 empty cells should NOT materialize 1000 cells
        assertEquals(2, dataRow.cells.size)

        // Check Summary Row with Formula
        val summaryRow = sheet1.rows[2]
        assertEquals(CellType.FORMULA, summaryRow.cells[1]?.type)
        assertEquals("=SUM(B2:B2)", summaryRow.cells[1]?.formula)
    }

    @Test
    fun testOtsTemplateDetectionAndParsing() = runBlocking {
        val sampleOtsBytes = createMockOds()

        val file = File(context.cacheDir, "budget_template.ots").apply {
            FileOutputStream(this).use { it.write(sampleOtsBytes) }
        }
        val uri = Uri.fromFile(file)

        val detection = FormatRouter.detectFormat(context, uri)
        assertEquals(FileFormat.ODS_TEMPLATE, detection.format)
        assertFalse(detection.isCorrupted)

        val parserResult = OdsParser.create(context, uri, detection.format)
        assertTrue(parserResult.isSuccess)
        val parser = parserResult.getOrNull()
        assertNotNull(parser)
        assertEquals(2, parser!!.workbook.sheets.size)
    }

    @Test
    fun testFodsFlatXmlDetectionAndParsing() = runBlocking {
        val fodsXml = """<?xml version="1.0" encoding="UTF-8"?>
<office:document xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
    xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0"
    xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0"
    xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0"
    xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0">
  <office:automatic-styles>
    <style:style style:name="HeaderStyle" style:family="table-cell">
      <style:text-properties fo:font-weight="bold" fo:color="#1E88E5"/>
    </style:style>
  </office:automatic-styles>
  <office:body>
    <office:spreadsheet>
      <table:table table:name="Inventory">
        <table:table-row>
          <table:table-cell table:style-name="HeaderStyle" office:value-type="string"><text:p>SKU</text:p></table:table-cell>
          <table:table-cell table:style-name="HeaderStyle" office:value-type="string"><text:p>In Stock</text:p></table:table-cell>
          <table:table-cell table:number-columns-repeated="256"/>
        </table:table-row>
        <table:table-row>
          <table:table-cell office:value-type="string"><text:p>DRAGON-100</text:p></table:table-cell>
          <table:table-cell office:value-type="float" office:value="142"><text:p>142</text:p></table:table-cell>
        </table:table-row>
        <table:table-row table:number-rows-repeated="10000">
          <table:table-cell table:number-columns-repeated="10"/>
        </table:table-row>
      </table:table>
    </office:spreadsheet>
  </office:body>
</office:document>""".trimIndent()

        val file = File(context.cacheDir, "inventory.fods").apply {
            FileOutputStream(this).use { it.write(fodsXml.toByteArray(Charsets.UTF_8)) }
        }
        val uri = Uri.fromFile(file)

        // 1. Detection
        val detection = FormatRouter.detectFormat(context, uri)
        assertEquals(FileFormat.ODS_FLAT, detection.format)
        assertFalse(detection.isCorrupted)

        // 2. Parser initialization (bypassing zip decompression)
        val parserResult = OdsParser.create(context, uri, detection.format)
        assertTrue(parserResult.isSuccess)
        val parser = parserResult.getOrNull()
        assertNotNull(parser)
        assertEquals(1, parser!!.workbook.sheets.size)
        assertEquals("Inventory", parser.workbook.sheets[0].name)

        // 3. Load sheet
        val sheetState = parser.loadSheet(parser.workbook.sheets[0], maxRows = 100)
        assertTrue(sheetState is SheetLoadState.Success)
        val sheet = (sheetState as SheetLoadState.Success).sheet
        assertEquals(2, sheet.rows.size)
        assertEquals("DRAGON-100", sheet.rows[1].cells[0]?.displayValue)
        assertEquals("142", sheet.rows[1].cells[1]?.displayValue)
        assertEquals(CellType.NUMBER, sheet.rows[1].cells[1]?.type)
    }

    @Test
    fun testCorruptedOdsGracefulFailure() = runBlocking {
        // Zip missing content.xml
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("mimetype"))
            zos.write("application/vnd.oasis.opendocument.spreadsheet".toByteArray(Charsets.US_ASCII))
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("styles.xml"))
            zos.write("<styles/>".toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }

        val file = File(context.cacheDir, "corrupted.ods").apply {
            FileOutputStream(this).use { it.write(baos.toByteArray()) }
        }
        val uri = Uri.fromFile(file)

        val parserResult = OdsParser.create(context, uri, FileFormat.ODS)
        assertTrue(parserResult.isFailure)
        val errorMsg = parserResult.exceptionOrNull()?.message
        assertTrue(errorMsg?.contains("corrupted") == true || errorMsg?.contains("unsupported") == true)
    }

    private fun createMockOds(): ByteArray {
        val stylesXml = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-styles xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0"
    xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0"
    xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0">
  <office:styles>
    <style:style style:name="HeaderStyle" style:family="table-cell">
      <style:text-properties fo:font-weight="bold" fo:color="#FFFFFF"/>
      <style:table-cell-properties fo:background-color="#2E7D32"/>
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
    <style:style style:name="ce_bold" style:family="table-cell">
      <style:text-properties fo:font-weight="bold" fo:color="#1B5E20"/>
    </style:style>
  </office:automatic-styles>
  <office:body>
    <office:spreadsheet>
      <table:table table:name="Revenue">
        <table:table-row>
          <table:table-cell table:style-name="ce_bold" office:value-type="string"><text:p>Quarter</text:p></table:table-cell>
          <table:table-cell table:style-name="ce_bold" office:value-type="string"><text:p>Amount</text:p></table:table-cell>
          <table:table-cell table:number-columns-repeated="1024"/>
        </table:table-row>
        <table:table-row>
          <table:table-cell office:value-type="string"><text:p>Q1 2026</text:p></table:table-cell>
          <table:table-cell office:value-type="currency" office:value="50000" office:currency="USD"><text:p>$50,000</text:p></table:table-cell>
          <table:table-cell table:number-columns-repeated="1000"/>
        </table:table-row>
        <table:table-row>
          <table:table-cell office:value-type="string"><text:p>Total</text:p></table:table-cell>
          <table:table-cell table:formula="of:=SUM([.B2:.B2])" office:value-type="currency" office:value="50000"><text:p>$50,000</text:p></table:table-cell>
        </table:table-row>
        <table:table-row table:number-rows-repeated="50000">
          <table:table-cell table:number-columns-repeated="500"/>
        </table:table-row>
      </table:table>
      <table:table table:name="Expenses">
        <table:table-row>
          <table:table-cell office:value-type="string"><text:p>Category</text:p></table:table-cell>
          <table:table-cell office:value-type="currency" office:value="12000"><text:p>$12,000</text:p></table:table-cell>
        </table:table-row>
      </table:table>
    </office:spreadsheet>
  </office:body>
</office:document-content>""".trimIndent()

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("mimetype"))
            zos.write("application/vnd.oasis.opendocument.spreadsheet".toByteArray(Charsets.US_ASCII))
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
