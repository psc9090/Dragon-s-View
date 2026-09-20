package com.example.parser.odf

import com.example.model.*
import com.example.parser.xml.XmlPullHelpers
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.zip.ZipFile

object OdfParser {

  fun parse(fileSource: FileSource, isFlatXml: Boolean = false): DocumentModel {
    val blocks = mutableListOf<DocumentBlock>()
    val outline = mutableListOf<OutlineItem>()

    if (isFlatXml) {
      fileSource.openStream().use { stream ->
        parseContentStream(stream, blocks, outline)
      }
    } else {
      val zip = fileSource.asZip()
      try {
        val entry = zip.getEntry("content.xml")
          ?: throw IllegalStateException("content.xml not found in ODF package")
        zip.getInputStream(entry).use { stream ->
          parseContentStream(stream, blocks, outline)
        }
      } finally {
        try { zip.close() } catch (_: Exception) {}
      }
    }

    val pages = mutableListOf<DocumentPage>()
    var currentBlocks = mutableListOf<DocumentBlock>()
    var pageNum = 1

    for (b in blocks) {
      currentBlocks.add(b)
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
      outline = outline
    )
  }

  private fun parseContentStream(
    stream: InputStream,
    blocks: MutableList<DocumentBlock>,
    outline: MutableList<OutlineItem>
  ) {
    val parser = XmlPullHelpers.createSafeParser(stream)
    var event = parser.eventType

    while (event != XmlPullParser.END_DOCUMENT) {
      if (event == XmlPullParser.START_TAG) {
        val name = parser.name
        if (name == "p" || name == "h") {
          val isHeading = (name == "h")
          val level = parser.getAttributeValue(null, "outline-level")?.toIntOrNull() ?: 1
          val textBuilder = StringBuilder()

          var depth = 1
          while (depth > 0) {
            val e = parser.next()
            if (e == XmlPullParser.START_TAG) {
              depth++
            } else if (e == XmlPullParser.TEXT) {
              textBuilder.append(parser.text)
            } else if (e == XmlPullParser.END_TAG) {
              depth--
            } else if (e == XmlPullParser.END_DOCUMENT) break
          }

          val text = textBuilder.toString()
          if (text.isNotBlank()) {
            val p = ParagraphBlock(
              runs = listOf(
                TextRun(
                  text = text,
                  isBold = isHeading,
                  fontSizePt = if (isHeading) 14f else 11f
                )
              ),
              isHeading = isHeading,
              headingLevel = level
            )
            blocks.add(p)
            if (isHeading) {
              outline.add(OutlineItem(text.take(60), level, blocks.size / 25))
            }
          }
        }
      }
      event = parser.next()
    }
  }
}
