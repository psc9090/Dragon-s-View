package com.example.parser.drawing

import com.example.model.*
import com.example.parser.xml.XmlPullHelpers
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.zip.ZipFile

object OdgParser {

  fun parse(fileSource: FileSource, isFlatXml: Boolean = false): DrawingModel {
    val pages = mutableListOf<DrawingPageModel>()

    if (isFlatXml) {
      fileSource.openStream().use { stream ->
        parseContentStream(stream, pages)
      }
    } else {
      val zip = fileSource.asZip()
      try {
        val entry = zip.getEntry("content.xml")
          ?: throw IllegalStateException("content.xml not found in ODG package")
        zip.getInputStream(entry).use { stream ->
          parseContentStream(stream, pages)
        }
      } finally {
        try { zip.close() } catch (_: Exception) {}
      }
    }

    if (pages.isEmpty()) {
      pages.add(
        DrawingPageModel(
          pageIndex = 1,
          elements = listOf(
            DrawingRect(50f, 50f, 200f, 150f),
            DrawingText(70f, 120f, "OpenDocument Drawing", fontSize = 16f)
          )
        )
      )
    }

    return DrawingModel(
      title = fileSource.displayName.substringBeforeLast('.'),
      pages = pages
    )
  }

  private fun parseContentStream(stream: InputStream, pages: MutableList<DrawingPageModel>) {
    val parser = XmlPullHelpers.createSafeParser(stream)
    var event = parser.eventType

    var pageIndex = 1
    val elements = mutableListOf<DrawingElement>()

    while (event != XmlPullParser.END_DOCUMENT) {
      if (event == XmlPullParser.START_TAG) {
        val name = parser.name
        when (name) {
          "page" -> {
            elements.clear()
          }
          "rect" -> {
            val x = parseDim(parser.getAttributeValue(null, "x"))
            val y = parseDim(parser.getAttributeValue(null, "y"))
            val w = parseDim(parser.getAttributeValue(null, "width")).coerceAtLeast(20f)
            val h = parseDim(parser.getAttributeValue(null, "height")).coerceAtLeast(20f)
            elements.add(DrawingRect(x, y, w, h))
          }
          "ellipse", "circle" -> {
            val cx = parseDim(parser.getAttributeValue(null, "cx"))
            val cy = parseDim(parser.getAttributeValue(null, "cy"))
            val rx = parseDim(parser.getAttributeValue(null, "rx")).coerceAtLeast(20f)
            val ry = parseDim(parser.getAttributeValue(null, "ry")).coerceAtLeast(20f)
            elements.add(DrawingEllipse(cx, cy, rx, ry))
          }
          "path" -> {
            val d = parser.getAttributeValue("urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0", "d")
              ?: parser.getAttributeValue(null, "d") ?: ""
            if (d.isNotEmpty()) {
              elements.add(DrawingPath(pathData = d))
            }
          }
          "frame" -> {
            val x = parseDim(parser.getAttributeValue(null, "x"))
            val y = parseDim(parser.getAttributeValue(null, "y"))
            val textBuilder = StringBuilder()

            var depth = 1
            while (depth > 0) {
              val e = parser.next()
              if (e == XmlPullParser.START_TAG) depth++
              else if (e == XmlPullParser.TEXT) textBuilder.append(parser.text).append(" ")
              else if (e == XmlPullParser.END_TAG) depth--
              else if (e == XmlPullParser.END_DOCUMENT) break
            }
            val text = textBuilder.toString().trim()
            if (text.isNotEmpty()) {
              elements.add(DrawingText(x, y + 20f, text))
            }
          }
        }
      } else if (event == XmlPullParser.END_TAG && parser.name == "page") {
        pages.add(
          DrawingPageModel(
            pageIndex = pageIndex++,
            elements = elements.toList()
          )
        )
      }
      event = parser.next()
    }
  }

  private fun parseDim(dim: String?): Float {
    if (dim.isNullOrEmpty()) return 50f
    val clean = dim.trim().lowercase()
    val num = clean.filter { it.isDigit() || it == '.' }.toFloatOrNull() ?: 50f
    return when {
      clean.endsWith("cm") -> num * 28.35f
      clean.endsWith("mm") -> num * 2.835f
      clean.endsWith("in") -> num * 72f
      clean.endsWith("pt") -> num
      else -> num
    }
  }
}
