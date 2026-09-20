package com.example.parser.xray

import com.example.model.FileSource
import com.example.parser.xml.XmlPullHelpers
import org.xmlpull.v1.XmlPullParser
import java.util.zip.ZipFile

data class XRayInspection(
  val fileName: String,
  val totalEntries: Int,
  val uncompressedTotal: Long,
  val compressedTotal: Long,
  val parts: List<XRayPart>,
  val metadata: Map<String, String>,
  val securityRisks: List<SecurityRisk>
)

data class XRayPart(
  val path: String,
  val size: Long,
  val compressedSize: Long,
  val isXml: Boolean,
  val isMedia: Boolean
)

data class SecurityRisk(
  val level: RiskLevel,
  val title: String,
  val description: String
)

enum class RiskLevel {
  INFO, WARNING, DANGER
}

object XRayInspector {

  fun inspect(fileSource: FileSource): XRayInspection {
    val parts = mutableListOf<XRayPart>()
    val metadata = mutableMapOf<String, String>()
    val risks = mutableListOf<SecurityRisk>()

    var totalUncomp = 0L
    var totalComp = 0L

    val zip = fileSource.asZip()
    try {
      val entries = zip.entries()
      var entryCount = 0

      var hasMacros = false
      var hasExternalRels = false
      var hasOleObjects = false

      while (entries.hasMoreElements()) {
        val entry = entries.nextElement()
        entryCount++
        val name = entry.name
        val size = entry.size
        val cSize = entry.compressedSize

        if (size > 0) totalUncomp += size
        if (cSize > 0) totalComp += cSize

        val isXml = name.endsWith(".xml") || name.endsWith(".rels")
        val isMedia = name.startsWith("word/media/") || name.startsWith("ppt/media/") ||
          name.startsWith("xl/media/") || name.startsWith("Pictures/")

        parts.add(XRayPart(name, size, cSize, isXml, isMedia))

        // Security checks
        if (name.contains("vbaProject.bin", ignoreCase = true) ||
          name.contains("macro", ignoreCase = true) ||
          name.contains("Basic/", ignoreCase = true)
        ) {
          hasMacros = true
        }

        if (name.contains("oleObject", ignoreCase = true) || name.endsWith(".bin") || name.endsWith(".exe")) {
          hasOleObjects = true
        }

        // Check for external relationships in .rels
        if (name.endsWith(".rels")) {
          try {
            zip.getInputStream(entry).use { stream ->
              val parser = XmlPullHelpers.createSafeParser(stream)
              var ev = parser.eventType
              while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && parser.name == "Relationship") {
                  val mode = parser.getAttributeValue(null, "TargetMode")
                  val target = parser.getAttributeValue(null, "Target") ?: ""
                  if (mode == "External" || target.startsWith("http://") || target.startsWith("https://")) {
                    hasExternalRels = true
                  }
                }
                ev = parser.next()
              }
            }
          } catch (_: Exception) {}
        }
      }

      // Read metadata from docProps/core.xml if available
      val coreEntry = zip.getEntry("docProps/core.xml")
      if (coreEntry != null) {
        try {
          zip.getInputStream(coreEntry).use { stream ->
            val parser = XmlPullHelpers.createSafeParser(stream)
            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
              if (ev == XmlPullParser.START_TAG) {
                val tag = parser.name
                if (tag in listOf("title", "creator", "description", "lastModifiedBy", "revision", "created", "modified")) {
                  val text = parser.nextText()
                  if (text.isNotBlank()) metadata[tag.replaceFirstChar { it.uppercase() }] = text
                }
              }
              ev = parser.next()
            }
          }
        } catch (_: Exception) {}
      }

      // Read meta.xml for ODF
      val metaEntry = zip.getEntry("meta.xml")
      if (metaEntry != null) {
        try {
          zip.getInputStream(metaEntry).use { stream ->
            val parser = XmlPullHelpers.createSafeParser(stream)
            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
              if (ev == XmlPullParser.START_TAG) {
                val tag = parser.name
                if (tag in listOf("title", "creator", "creation-date", "editing-cycles", "generator")) {
                  val text = parser.nextText()
                  if (text.isNotBlank()) metadata[tag.replaceFirstChar { it.uppercase() }] = text
                }
              }
              ev = parser.next()
            }
          }
        } catch (_: Exception) {}
      }

      // Add security findings
      if (hasMacros) {
        risks.add(
          SecurityRisk(
            RiskLevel.WARNING,
            "Embedded Macros Detected",
            "This document contains embedded macros (e.g. VBA). Dragon's View never executes macros, so your device remains safe."
          )
        )
      } else {
        risks.add(
          SecurityRisk(
            RiskLevel.INFO,
            "No Macros Found",
            "No executable VBA or macro code detected in package."
          )
        )
      }

      if (hasExternalRels) {
        risks.add(
          SecurityRisk(
            RiskLevel.WARNING,
            "External References Present",
            "Contains relationships pointing to external network targets. Blocked by offline policy."
          )
        )
      } else {
        risks.add(
          SecurityRisk(
            RiskLevel.INFO,
            "Self-Contained Content",
            "All resources and media are embedded locally within this file package."
          )
        )
      }

      if (hasOleObjects) {
        risks.add(
          SecurityRisk(
            RiskLevel.WARNING,
            "Embedded OLE Objects",
            "Contains binary OLE objects. Dragon's View does not launch external binaries."
          )
        )
      }

      // Compression ratio check
      if (totalComp > 0) {
        val ratio = totalUncomp.toDouble() / totalComp.toDouble()
        if (ratio > 100.0) {
          risks.add(
            SecurityRisk(
              RiskLevel.DANGER,
              "Anomalous Compression Ratio",
              "Compression ratio is ${ratio.toInt()}:1 (potential zip bomb pattern)."
            )
          )
        }
      }

      return XRayInspection(
        fileName = fileSource.displayName,
        totalEntries = entryCount,
        uncompressedTotal = totalUncomp,
        compressedTotal = totalComp,
        parts = parts,
        metadata = metadata,
        securityRisks = risks
      )
    } finally {
      try { zip.close() } catch (_: Exception) {}
    }
  }

  fun readPartContent(fileSource: FileSource, partPath: String): String {
    val zip = fileSource.asZip()
    try {
      val entry = zip.getEntry(partPath) ?: return "Part not found: $partPath"
      zip.getInputStream(entry).use { stream ->
        return stream.bufferedReader(Charsets.UTF_8).readText()
      }
    } finally {
      try { zip.close() } catch (_: Exception) {}
    }
  }
}
