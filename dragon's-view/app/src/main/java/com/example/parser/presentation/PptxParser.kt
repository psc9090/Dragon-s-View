package com.example.parser.presentation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.example.model.*
import com.example.parser.xml.XmlPullHelpers
import org.xmlpull.v1.XmlPullParser
import java.util.zip.ZipFile

object PptxParser {

  private const val EMU_PER_POINT = 12700f

  fun parse(fileSource: FileSource): PresentationModel {
    val zip = fileSource.asZip()
    try {
      // 1. Read presentation.xml for slide dimensions
      var slideW = 960f
      var slideH = 540f
      val presEntry = zip.getEntry("ppt/presentation.xml")
      if (presEntry != null) {
        zip.getInputStream(presEntry).use { stream ->
          val parser = XmlPullHelpers.createSafeParser(stream)
          var event = parser.eventType
          while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "sldSz") {
              val cx = parser.getAttributeValue(null, "cx")?.toFloatOrNull()
              val cy = parser.getAttributeValue(null, "cy")?.toFloatOrNull()
              if (cx != null && cy != null) {
                slideW = cx / EMU_PER_POINT
                slideH = cy / EMU_PER_POINT
              }
            }
            event = parser.next()
          }
        }
      }

      // 2. Discover slides
      val slides = mutableListOf<SlideModel>()
      var slideIndex = 1

      while (true) {
        val slideName = "ppt/slides/slide$slideIndex.xml"
        val entry = zip.getEntry(slideName) ?: break

        val relsMap = readSlideRels(zip, slideIndex)
        val notes = readSlideNotes(zip, slideIndex)

        val elements = mutableListOf<SlideElement>()
        var slideTitle = "Slide $slideIndex"

        zip.getInputStream(entry).use { stream ->
          val parser = XmlPullHelpers.createSafeParser(stream)
          var event = parser.eventType

          while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
              val tagName = parser.name
              if (tagName == "sp") {
                val element = parseShapeOrText(parser)
                if (element != null) {
                  elements.add(element)
                  if (element is SlideTextBox && slideTitle == "Slide $slideIndex") {
                    val firstText = element.paragraphs.firstOrNull()?.runs?.firstOrNull()?.text ?: ""
                    if (firstText.isNotBlank()) slideTitle = firstText.take(40)
                  }
                }
              } else if (tagName == "pic") {
                val img = parsePicture(parser, zip, relsMap)
                if (img != null) elements.add(img)
              }
            }
            event = parser.next()
          }
        }

        slides.add(
          SlideModel(
            slideNumber = slideIndex,
            title = slideTitle,
            elements = elements,
            notes = notes
          )
        )
        slideIndex++
      }

      if (slides.isEmpty()) {
        slides.add(
          SlideModel(
            slideNumber = 1,
            title = "Empty Presentation",
            elements = listOf(
              SlideTextBox(
                x = 100f,
                y = 100f,
                width = 500f,
                height = 100f,
                paragraphs = listOf(
                  SlideParagraph(
                    runs = listOf(TextRun("Empty Presentation", isBold = true, fontSizePt = 24f))
                  )
                )
              )
            )
          )
        )
      }

      return PresentationModel(
        title = fileSource.displayName.substringBeforeLast('.'),
        slideWidth = slideW,
        slideHeight = slideH,
        slides = slides
      )
    } finally {
      try { zip.close() } catch (_: Exception) {}
    }
  }

  private fun readSlideRels(zip: ZipFile, slideIndex: Int): Map<String, String> {
    val map = mutableMapOf<String, String>()
    val relsEntry = zip.getEntry("ppt/slides/_rels/slide$slideIndex.xml.rels") ?: return map
    try {
      zip.getInputStream(relsEntry).use { stream ->
        val parser = XmlPullHelpers.createSafeParser(stream)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
          if (event == XmlPullParser.START_TAG && parser.name == "Relationship") {
            val id = parser.getAttributeValue(null, "Id")
            val target = parser.getAttributeValue(null, "Target")
            if (id != null && target != null) {
              val path = if (target.startsWith("/")) target.removePrefix("/") else "ppt/$target"
              map[id] = path
            }
          }
          event = parser.next()
        }
      }
    } catch (_: Exception) {}
    return map
  }

  private fun readSlideNotes(zip: ZipFile, slideIndex: Int): String? {
    val entry = zip.getEntry("ppt/notesSlides/notesSlide$slideIndex.xml") ?: return null
    val sb = StringBuilder()
    try {
      zip.getInputStream(entry).use { stream ->
        val parser = XmlPullHelpers.createSafeParser(stream)
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
          if (event == XmlPullParser.START_TAG && parser.name == "t") {
            sb.append(parser.nextText()).append("\n")
          }
          event = parser.next()
        }
      }
    } catch (_: Exception) {}
    return if (sb.isNotEmpty()) sb.toString().trim() else null
  }

  private fun parseShapeOrText(parser: XmlPullParser): SlideElement? {
    var x = 50f
    var y = 50f
    var w = 400f
    var h = 200f
    var shapeType = ShapeType.RECTANGLE
    val paragraphs = mutableListOf<SlideParagraph>()

    var depth = 1
    while (depth > 0) {
      val event = parser.next()
      if (event == XmlPullParser.START_TAG) {
        depth++
        when (parser.name) {
          "off" -> {
            val ox = parser.getAttributeValue(null, "x")?.toFloatOrNull()
            val oy = parser.getAttributeValue(null, "y")?.toFloatOrNull()
            if (ox != null) x = ox / EMU_PER_POINT
            if (oy != null) y = oy / EMU_PER_POINT
          }
          "ext" -> {
            val cx = parser.getAttributeValue(null, "cx")?.toFloatOrNull()
            val cy = parser.getAttributeValue(null, "cy")?.toFloatOrNull()
            if (cx != null) w = cx / EMU_PER_POINT
            if (cy != null) h = cy / EMU_PER_POINT
          }
          "prstGeom" -> {
            val prst = parser.getAttributeValue(null, "prst") ?: ""
            shapeType = when (prst) {
              "roundRect" -> ShapeType.ROUNDED_RECTANGLE
              "ellipse" -> ShapeType.ELLIPSE
              "line" -> ShapeType.LINE
              "triangle" -> ShapeType.TRIANGLE
              else -> ShapeType.RECTANGLE
            }
          }
          "p" -> {
            val p = parseSlideParagraph(parser)
            if (p != null) paragraphs.add(p)
            depth--
          }
        }
      } else if (event == XmlPullParser.END_TAG) {
        depth--
      } else if (event == XmlPullParser.END_DOCUMENT) break
    }

    if (paragraphs.isNotEmpty()) {
      return SlideTextBox(x, y, w, h, paragraphs)
    }

    return SlideShape(x, y, w, h, shapeType)
  }

  private fun parseSlideParagraph(parser: XmlPullParser): SlideParagraph? {
    val runs = mutableListOf<TextRun>()
    var depth = 1

    while (depth > 0) {
      val event = parser.next()
      if (event == XmlPullParser.START_TAG) {
        depth++
        if (parser.name == "r") {
          var text = ""
          var bold = false
          var italic = false
          var sizePt = 16f
          var rDepth = 1

          while (rDepth > 0) {
            val re = parser.next()
            if (re == XmlPullParser.START_TAG) {
              rDepth++
              when (parser.name) {
                "t" -> {
                  text = parser.nextText()
                  rDepth--
                }
                "rPr" -> {
                  bold = parser.getAttributeValue(null, "b") == "1"
                  italic = parser.getAttributeValue(null, "i") == "1"
                  val sz = parser.getAttributeValue(null, "sz")?.toFloatOrNull()
                  if (sz != null) sizePt = sz / 100f
                }
              }
            } else if (re == XmlPullParser.END_TAG) {
              rDepth--
            } else if (re == XmlPullParser.END_DOCUMENT) break
          }

          if (text.isNotEmpty()) {
            runs.add(TextRun(text = text, isBold = bold, isItalic = italic, fontSizePt = sizePt))
          }
          depth--
        }
      } else if (event == XmlPullParser.END_TAG) {
        depth--
      } else if (event == XmlPullParser.END_DOCUMENT) break
    }

    return if (runs.isNotEmpty()) SlideParagraph(runs) else null
  }

  private fun parsePicture(
    parser: XmlPullParser,
    zip: ZipFile,
    rels: Map<String, String>
  ): SlideImage? {
    var x = 50f
    var y = 50f
    var w = 200f
    var h = 150f
    var rId = ""

    var depth = 1
    while (depth > 0) {
      val event = parser.next()
      if (event == XmlPullParser.START_TAG) {
        depth++
        when (parser.name) {
          "off" -> {
            val ox = parser.getAttributeValue(null, "x")?.toFloatOrNull()
            val oy = parser.getAttributeValue(null, "y")?.toFloatOrNull()
            if (ox != null) x = ox / EMU_PER_POINT
            if (oy != null) y = oy / EMU_PER_POINT
          }
          "ext" -> {
            val cx = parser.getAttributeValue(null, "cx")?.toFloatOrNull()
            val cy = parser.getAttributeValue(null, "cy")?.toFloatOrNull()
            if (cx != null) w = cx / EMU_PER_POINT
            if (cy != null) h = cy / EMU_PER_POINT
          }
          "blip" -> {
            rId = parser.getAttributeValue("http://schemas.openxmlformats.org/officeDocument/2006/relationships", "embed")
              ?: parser.getAttributeValue(null, "r:embed") ?: ""
          }
        }
      } else if (event == XmlPullParser.END_TAG) {
        depth--
      } else if (event == XmlPullParser.END_DOCUMENT) break
    }

    var bitmap: Bitmap? = null
    val imagePath = rels[rId]
    if (imagePath != null) {
      val imgEntry = zip.getEntry(imagePath) ?: zip.getEntry("ppt/$imagePath")
      if (imgEntry != null) {
        try {
          zip.getInputStream(imgEntry).use { stream ->
            val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
            bitmap = BitmapFactory.decodeStream(stream, null, opts)
          }
        } catch (_: Exception) {}
      }
    }

    return SlideImage(x, y, w, h, bitmap)
  }
}
