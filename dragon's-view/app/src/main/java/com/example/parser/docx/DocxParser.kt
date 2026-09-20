package com.example.parser.docx

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.model.*
import com.example.parser.xml.XmlPullHelpers
import org.xmlpull.v1.XmlPullParser
import java.util.zip.ZipFile

object DocxParser {

  fun parse(fileSource: FileSource): DocumentModel {
    val zip = fileSource.asZip()
    try {
      // 1. Read relationships to map image rIds to zip entry paths
      val relsMap = readRelationships(zip)

      // 2. Parse word/document.xml
      val docEntry = zip.getEntry("word/document.xml")
        ?: throw IllegalStateException("word/document.xml not found in DOCX package")

      val allBlocks = mutableListOf<DocumentBlock>()
      val outline = mutableListOf<OutlineItem>()
      var totalWords = 0

      zip.getInputStream(docEntry).use { stream ->
        val parser = XmlPullHelpers.createSafeParser(stream)
        var eventType = parser.eventType

        while (eventType != XmlPullParser.END_DOCUMENT) {
          if (eventType == XmlPullParser.START_TAG) {
            val name = parser.name
            if (name == "p") {
              val pBlock = parseParagraph(parser, zip, relsMap)
              if (pBlock != null) {
                allBlocks.add(pBlock)
                val text = pBlock.runs.joinToString("") { it.text }
                if (text.isNotBlank()) {
                  totalWords += text.split("\\s+".toRegex()).size
                  if (pBlock.isHeading) {
                    outline.add(OutlineItem(text.take(60), pBlock.headingLevel, allBlocks.size / 25))
                  }
                }
              }
            } else if (name == "tbl") {
              val tableBlock = parseTable(parser)
              if (tableBlock != null) {
                allBlocks.add(tableBlock)
              }
            }
          }
          eventType = parser.next()
        }
      }

      // Group blocks into realistic pages (~25 blocks per page or page breaks)
      val pages = mutableListOf<DocumentPage>()
      var currentBlocks = mutableListOf<DocumentBlock>()
      var pageNum = 1

      for (block in allBlocks) {
        currentBlocks.add(block)
        if (currentBlocks.size >= 25) {
          pages.add(DocumentPage(pageNumber = pageNum++, blocks = currentBlocks))
          currentBlocks = mutableListOf()
        }
      }

      if (currentBlocks.isNotEmpty() || pages.isEmpty()) {
        pages.add(DocumentPage(pageNumber = pageNum, blocks = currentBlocks))
      }

      return DocumentModel(
        title = fileSource.displayName.substringBeforeLast('.'),
        pages = pages,
        outline = outline,
        totalWordCount = totalWords
      )
    } finally {
      try {
        zip.close()
      } catch (_: Exception) {}
    }
  }

  private fun readRelationships(zip: ZipFile): Map<String, String> {
    val map = mutableMapOf<String, String>()
    val relsEntry = zip.getEntry("word/_rels/document.xml.rels") ?: return map
    try {
      zip.getInputStream(relsEntry).use { stream ->
        val parser = XmlPullHelpers.createSafeParser(stream)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
          if (event == XmlPullParser.START_TAG && parser.name == "Relationship") {
            val id = parser.getAttributeValue(null, "Id")
            val target = parser.getAttributeValue(null, "Target")
            if (id != null && target != null) {
              val fullPath = if (target.startsWith("/")) target.removePrefix("/") else "word/$target"
              map[id] = fullPath
            }
          }
          event = parser.next()
        }
      }
    } catch (_: Exception) {}
    return map
  }

  private fun parseParagraph(
    parser: XmlPullParser,
    zip: ZipFile,
    rels: Map<String, String>
  ): ParagraphBlock? {
    val runs = mutableListOf<TextRun>()
    var alignment = Alignment.LEFT
    var isHeading = false
    var headingLevel = 0
    var isBullet = false

    var depth = 1
    while (depth > 0) {
      val event = parser.next()
      if (event == XmlPullParser.START_TAG) {
        depth++
        val tag = parser.name
        when (tag) {
          "jc" -> {
            val alignVal = parser.getAttributeValue(null, "val")
            alignment = when (alignVal) {
              "center" -> Alignment.CENTER
              "right" -> Alignment.RIGHT
              "both" -> Alignment.JUSTIFY
              else -> Alignment.LEFT
            }
          }
          "pStyle" -> {
            val styleVal = parser.getAttributeValue(null, "val") ?: ""
            if (styleVal.startsWith("Heading", ignoreCase = true)) {
              isHeading = true
              headingLevel = styleVal.filter { it.isDigit() }.toIntOrNull() ?: 1
            }
          }
          "numPr" -> isBullet = true
          "r" -> {
            val run = parseRun(parser)
            if (run != null) runs.add(run)
            depth-- // parseRun consumed up to </w:r>
          }
        }
      } else if (event == XmlPullParser.END_TAG) {
        depth--
      } else if (event == XmlPullParser.END_DOCUMENT) {
        break
      }
    }

    if (runs.isEmpty()) {
      return ParagraphBlock(runs = listOf(TextRun("")), alignment = alignment)
    }

    return ParagraphBlock(
      runs = runs,
      alignment = alignment,
      isHeading = isHeading,
      headingLevel = headingLevel,
      isBullet = isBullet
    )
  }

  private fun parseRun(parser: XmlPullParser): TextRun? {
    var isBold = false
    var isItalic = false
    var isUnderline = false
    var isStrike = false
    var fontSize = 11f
    var textColor: Int? = null
    var text = ""

    var depth = 1
    while (depth > 0) {
      val event = parser.next()
      if (event == XmlPullParser.START_TAG) {
        depth++
        when (parser.name) {
          "b" -> isBold = true
          "i" -> isItalic = true
          "u" -> isUnderline = true
          "strike" -> isStrike = true
          "sz" -> {
            val szVal = parser.getAttributeValue(null, "val")?.toFloatOrNull()
            if (szVal != null) fontSize = szVal / 2f // Word sizes in half-points
          }
          "color" -> {
            val c = parser.getAttributeValue(null, "val")
            if (c != null && c.length == 6) {
              textColor = (0xFF000000 or c.toLong(16)).toInt()
            }
          }
          "t" -> {
            text = parser.nextText()
            depth-- // nextText consumes END_TAG
          }
        }
      } else if (event == XmlPullParser.END_TAG) {
        depth--
      } else if (event == XmlPullParser.END_DOCUMENT) {
        break
      }
    }

    return if (text.isNotEmpty()) {
      TextRun(
        text = text,
        isBold = isBold,
        isItalic = isItalic,
        isUnderline = isUnderline,
        isStrike = isStrike,
        fontSizePt = fontSize,
        textColor = textColor
      )
    } else null
  }

  private fun parseTable(parser: XmlPullParser): TableBlock? {
    val rows = mutableListOf<TableRow>()
    var depth = 1

    while (depth > 0) {
      val event = parser.next()
      if (event == XmlPullParser.START_TAG) {
        depth++
        if (parser.name == "tr") {
          val row = parseTableRow(parser)
          if (row != null) rows.add(row)
          depth-- // consumed </w:tr>
        }
      } else if (event == XmlPullParser.END_TAG) {
        depth--
      } else if (event == XmlPullParser.END_DOCUMENT) {
        break
      }
    }

    return if (rows.isNotEmpty()) TableBlock(rows) else null
  }

  private fun parseTableRow(parser: XmlPullParser): TableRow? {
    val cells = mutableListOf<TableCell>()
    var depth = 1

    while (depth > 0) {
      val event = parser.next()
      if (event == XmlPullParser.START_TAG) {
        depth++
        if (parser.name == "tc") {
          val cellText = StringBuilder()
          var tcDepth = 1
          while (tcDepth > 0) {
            val e = parser.next()
            if (e == XmlPullParser.START_TAG) {
              tcDepth++
              if (parser.name == "t") {
                cellText.append(parser.nextText()).append(" ")
                tcDepth--
              }
            } else if (e == XmlPullParser.END_TAG) {
              tcDepth--
            } else if (e == XmlPullParser.END_DOCUMENT) break
          }
          cells.add(TableCell(text = cellText.toString().trim()))
          depth--
        }
      } else if (event == XmlPullParser.END_TAG) {
        depth--
      } else if (event == XmlPullParser.END_DOCUMENT) break
    }

    return if (cells.isNotEmpty()) TableRow(cells) else null
  }
}
