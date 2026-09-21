// app/src/test/java/com/example/XlsxParserTest.kt
package com.example

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.dragonview.app.router.FileFormat
import com.dragonview.app.router.FormatRouter
import com.dragonview.app.viewer.xlsx.CellType
import com.dragonview.app.viewer.xlsx.SheetLoadState
import com.dragonview.app.viewer.xlsx.XlsxParser
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
class XlsxParserTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun testValidXlsxParsingWithSharedStringsAndStyles() = runBlocking {
        val xlsxBytes = createMockXlsx(corruptSheet2 = false)

        val file = File(context.cacheDir, "test_valid.xlsx").apply {
            FileOutputStream(this).use { it.write(xlsxBytes) }
        }
        val uri = Uri.fromFile(file)

        // 1. FormatRouter detection
        val detection = FormatRouter.detectFormat(context, uri)
        assertEquals(FileFormat.XLSX, detection.format)
        assertFalse(detection.isCorrupted)

        // 2. Parser initialization
        val parserResult = XlsxParser.create(context, uri)
        assertTrue(parserResult.isSuccess)
        val parser = parserResult.getOrNull()
        assertNotNull(parser)
        assertEquals(2, parser!!.workbook.sheets.size)
        assertEquals("Q3 Performance", parser.workbook.sheets[0].name)
        assertEquals("Regional Summary", parser.workbook.sheets[1].name)

        // 3. Load Sheet 1 on demand
        val sheet1State = parser.loadSheet(parser.workbook.sheets[0])
        assertTrue(sheet1State is SheetLoadState.Success)
        val sheet1 = (sheet1State as SheetLoadState.Success).sheet

        // Verify rows and sparse cells
        assertTrue(sheet1.rows.size >= 2)
        val headerRow = sheet1.rows[0]
        assertEquals(1, headerRow.rowIndex)
        val colA1 = headerRow.cells[0]
        assertNotNull(colA1)
        assertEquals("Region", colA1?.displayValue)

        val dataRow = sheet1.rows[1]
        val revenueCell = dataRow.cells[1]
        assertNotNull(revenueCell)
        assertEquals(CellType.NUMBER, revenueCell?.type)
        assertEquals("150000", revenueCell?.displayValue)

        // Check date formatted cell
        val dateCell = dataRow.cells[5]
        assertNotNull(dateCell)
        assertEquals(CellType.DATE, dateCell?.type)
        assertTrue(dateCell!!.displayValue.startsWith("2023-"))

        // 4. Load Sheet 2 on demand
        val sheet2State = parser.loadSheet(parser.workbook.sheets[1])
        assertTrue(sheet2State is SheetLoadState.Success)
        val sheet2 = (sheet2State as SheetLoadState.Success).sheet
        assertEquals("Regional Summary", sheet2.sheetRef.name)
        assertTrue(sheet2.rows.isNotEmpty())
    }

    @Test
    fun testCorruptedWorksheetIsolatedHandling() = runBlocking {
        // Sheet 2 contains broken XML, Sheet 1 is valid
        val xlsxBytes = createMockXlsx(corruptSheet2 = true)

        val file = File(context.cacheDir, "test_partial_corrupt.xlsx").apply {
            FileOutputStream(this).use { it.write(xlsxBytes) }
        }
        val uri = Uri.fromFile(file)

        val parserResult = XlsxParser.create(context, uri)
        assertTrue(parserResult.isSuccess)
        val parser = parserResult.getOrNull()!!

        // Sheet 1 must succeed
        val sheet1State = parser.loadSheet(parser.workbook.sheets[0])
        assertTrue(sheet1State is SheetLoadState.Success)

        // Sheet 2 must fail gracefully with error message without crashing
        val sheet2State = parser.loadSheet(parser.workbook.sheets[1])
        assertTrue(sheet2State is SheetLoadState.Error)
        val errorMsg = (sheet2State as SheetLoadState.Error).message
        assertTrue(errorMsg.contains("This XLSX file appears corrupted or uses an unsupported structure"))
    }

    private fun createMockXlsx(corruptSheet2: Boolean): ByteArray {
        val contentTypesXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>
  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>""".trimIndent()

        val rootRelsXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>""".trimIndent()

        val workbookXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets>
    <sheet name="Q3 Performance" sheetId="1" r:id="rId1"/>
    <sheet name="Regional Summary" sheetId="2" r:id="rId2"/>
  </sheets>
</workbook>""".trimIndent()

        val workbookRelsXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
</Relationships>""".trimIndent()

        val sharedStringsList = listOf(
            "Region", "Target Revenue", "Actual Revenue", "Variance", "Growth Rate", "Fulfillment Date", "Status",
            "North America", "Europe"
        )

        val sharedStringsXml = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""")
            append("""<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="${sharedStringsList.size}">""")
            for (str in sharedStringsList) {
                append("<si><t>$str</t></si>")
            }
            append("</sst>")
        }

        val stylesXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <fonts count="2">
    <font><name val="Calibri"/><sz val="11"/></font>
    <font><b/><name val="Calibri"/><sz val="11"/></font>
  </fonts>
  <cellXfs count="3">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0"/>
    <xf numFmtId="0" fontId="1" fillId="0" borderId="0" applyFont="1"/>
    <xf numFmtId="14" fontId="0" fillId="0" borderId="0" applyNumberFormat="1"/>
  </cellXfs>
</styleSheet>""".trimIndent()

        val sheet1Xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <sheetData>
    <row r="1">
      <c r="A1" t="s" s="1"><v>0</v></c>
      <c r="B1" t="s" s="1"><v>1</v></c>
      <c r="C1" t="s" s="1"><v>2</v></c>
      <c r="D1" t="s" s="1"><v>3</v></c>
      <c r="E1" t="s" s="1"><v>4</v></c>
      <c r="F1" t="s" s="1"><v>5</v></c>
      <c r="G1" t="s" s="1"><v>6</v></c>
    </row>
    <row r="2">
      <c r="A2" t="s"><v>7</v></c>
      <c r="B2"><v>150000</v></c>
      <c r="C2"><v>185420</v></c>
      <c r="D2"><f>C2-B2</f><v>35420</v></c>
      <c r="E2"><f>(C2-B2)/B2</f><v>0.236</v></c>
      <c r="F2" s="2"><v>45180</v></c>
      <c r="G2"><v>1</v></c>
    </row>
  </sheetData>
</worksheet>""".trimIndent()

        val sheet2Xml = if (corruptSheet2) {
            "<<<BROKEN XML NOT A VALID DOCUMENT>>>"
        } else {
            """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <sheetData>
    <row r="1">
      <c r="A1" t="s"><v>0</v></c>
      <c r="B1"><v>999</v></c>
    </row>
  </sheetData>
</worksheet>""".trimIndent()
        }

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("[Content_Types].xml"))
            zos.write(contentTypesXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("_rels/.rels"))
            zos.write(rootRelsXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("xl/workbook.xml"))
            zos.write(workbookXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("xl/_rels/workbook.xml.rels"))
            zos.write(workbookRelsXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("xl/sharedStrings.xml"))
            zos.write(sharedStringsXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("xl/styles.xml"))
            zos.write(stylesXml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zos.write(sheet1Xml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("xl/worksheets/sheet2.xml"))
            zos.write(sheet2Xml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        return baos.toByteArray()
    }
}
