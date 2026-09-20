package com.example.parser.presentation

import com.example.model.*
import com.example.parser.xml.XmlPullHelpers
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.util.zip.ZipFile

object OdpParser {

  fun parse(fileSource: FileSource, isFlatXml: Boolean = false): PresentationModel {
    val slides = mutableListOf<SlideModel>()

    if (isFlatXml) {
      fileSource.openStream().use { stream ->
        parseContentStream(stream, slides)
      }
    } else {
      val zip = fileSource.asZip()
      try {
        val entry = zip.getEntry("content.xml")
          ?: throw IllegalStateException("content.xml not found in ODP package")
        zip.getInputStream(entry).use { stream ->
          parseContentStream(stream, slides)
        }
      } finally {
        try { zip.close() } catch (_: Exception) {}
      }
    }

    if (slides.isEmpty()) {
      slides.add(
        SlideModel(
          slideNumber = 1,
          title = "Slide 1",
          elements = listOf(
            SlideTextBox(
              x = 50f,
              y = 50f,
              width = 500f,
              height = 100f,
              paragraphs = listOf(
                SlideParagraph(runs = listOf(TextRun("OpenDocument Presentation", isBold = true, fontSizePt = 22f)))
              )
            )
          )
        )
      )
    }

    return PresentationModel(
      title = fileSource.displayName.substringBeforeLast('.'),
      slideWidth = 960f,
      slideHeight = 540f,
      slides = slides
    )
  }

  private fun parseContentStream(stream: InputStream, slides: MutableList<SlideModel>) {
    val parser = XmlPullHelpers.createSafeParser(stream)
    var event = parser.eventType

    var slideIndex = 1
    var currentSlideName = "Slide 1"
    val elements = mutableListOf<SlideElement>()

    while (event != XmlPullParser.END_DOCUMENT) {
      if (event == XmlPullParser.START_TAG) {
        val name = parser.name
        if (name == "page") {
          currentSlideName = parser.getAttributeValue(null, "name") ?: "Slide $slideIndex"
          elements.clear()
        } else if (name == "frame") {
          val x = parseOdfDimension(parser.getAttributeValue(null, "x"))
          val y = parseOdfDimension(parser.getAttributeValue(null, "y"))
          val w = parseOdfDimension(parser.getAttributeValue(null, "width")).coerceAtLeast(100f)
          val h = parseOdfDimension(parser.getAttributeValue(null, "height")).coerceAtLeast(50f)

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
            elements.add(
              SlideTextBox(
                x = x,
                y = y,
                width = w,
                height = h,
                paragraphs = listOf(
                  SlideParagraph(runs = listOf(TextRun(text, fontSizePt = 16f)))
                )
              )
            )
          }
        }
      } else if (event == XmlPullParser.END_TAG && parser.name == "page") {
        slides.add(
          SlideModel(
            slideNumber = slideIndex++,
            title = currentSlideName,
            elements = elements.toList()
          )
        )
      }
      event = parser.next()
    }
  }

  private fun parseOdfDimension(dim: String?): Float {
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
