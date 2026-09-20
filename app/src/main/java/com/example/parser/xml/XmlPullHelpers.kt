package com.example.parser.xml

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import java.io.StringReader

object XmlPullHelpers {
  private val factory: XmlPullParserFactory = XmlPullParserFactory.newInstance().apply {
    isNamespaceAware = true
  }

  fun createSafeParser(stream: InputStream): XmlPullParser {
    val parser = factory.newPullParser()
    try {
      parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
    } catch (_: Exception) {}
    parser.setInput(stream, "UTF-8")
    return parser
  }

  fun createSafeParser(xmlString: String): XmlPullParser {
    val parser = factory.newPullParser()
    try {
      parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
    } catch (_: Exception) {}
    parser.setInput(StringReader(xmlString))
    return parser
  }
}
