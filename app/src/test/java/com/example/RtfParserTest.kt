package com.example

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.dragonview.app.router.FileFormat
import com.dragonview.app.router.FormatRouter
import com.dragonview.app.viewer.document.DocumentElement
import com.dragonview.app.viewer.rtf.RtfParser
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RtfParserTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun testValidRtfParsingWithStreamingTokenizer() = runBlocking {
        val rtfContent = """
            {\rtf1\ansi\deff0
            {\fonttbl{\f0 Calibri;}}
            {\colortbl ;\red255\green0\blue0;\red0\green128\blue0;}
            \pard\qc\b\fs32 DragonView Test Report\b0\fs24\par
            \pard\ql This is a \i styled\i0  paragraph with \cf1 red text\cf0  and \cf2 green text\cf0 .\par
            \bullet  \ul Underlined item\ulnone\par
            }
        """.trimIndent()

        val file = File(context.cacheDir, "test_doc.rtf").apply {
            FileOutputStream(this).use { it.write(rtfContent.toByteArray(Charsets.ISO_8859_1)) }
        }
        val uri = Uri.fromFile(file)

        // 1. Check FormatRouter detection
        val detection = FormatRouter.detectFormat(context, uri)
        assertEquals(FileFormat.RTF, detection.format)
        assertFalse(detection.isCorrupted)

        // 2. Check RtfParser parsing
        val parseResult = RtfParser.parse(context, uri)
        assertTrue(parseResult.isSuccess)
        val doc = parseResult.getOrNull()
        assertNotNull(doc)
        assertTrue(doc!!.elements.isNotEmpty())

        val p1 = doc.elements[0] as DocumentElement.Paragraph
        assertEquals("DragonView Test Report", p1.plainText())
        val headingRun = p1.runs.first()
        assertTrue(headingRun.isBold)
        assertEquals(16f, headingRun.fontSizeSp)

        val p2 = doc.elements[1] as DocumentElement.Paragraph
        assertTrue(p2.plainText().contains("styled"))
        assertTrue(p2.plainText().contains("red text"))
    }

    @Test
    fun testInvalidRtfHeaderHandledGracefully() = runBlocking {
        // Plain text file without {\rtf header
        val invalidContent = "This is just a plain text file, not RTF."
        val file = File(context.cacheDir, "corrupted.rtf").apply {
            FileOutputStream(this).use { it.write(invalidContent.toByteArray(Charsets.UTF_8)) }
        }
        val uri = Uri.fromFile(file)

        // FormatRouter should catch corrupted header
        val detection = FormatRouter.detectFormat(context, uri)
        assertTrue(detection.isCorrupted)
        assertTrue(detection.statusMessage?.contains("corrupted or invalid") == true)

        // RtfParser should fail safely with descriptive error
        val parseResult = RtfParser.parse(context, uri)
        assertTrue(parseResult.isFailure)
        val errorMsg = parseResult.exceptionOrNull()?.message ?: ""
        assertTrue(errorMsg.contains("corrupted or invalid"))
    }

    @Test
    fun testUnicodeAndEscapedCharactersInRtf() = runBlocking {
        val rtfContent = """
            {\rtf1\ansi
            \pard Escaped braces: \{ and \} and slash: \\ \par
            Unicode: \u8364? Euro symbol\par
            }
        """.trimIndent()

        val file = File(context.cacheDir, "test_escapes.rtf").apply {
            FileOutputStream(this).use { it.write(rtfContent.toByteArray(Charsets.ISO_8859_1)) }
        }
        val uri = Uri.fromFile(file)

        val parseResult = RtfParser.parse(context, uri)
        assertTrue(parseResult.isSuccess)
        val doc = parseResult.getOrNull()
        assertNotNull(doc)

        val allText = doc!!.elements.filterIsInstance<DocumentElement.Paragraph>().joinToString(" ") { it.plainText() }
        assertTrue(allText.contains("{ and } and slash: \\"))
        assertTrue(allText.contains("€") || allText.contains("Euro symbol"))
    }
}
