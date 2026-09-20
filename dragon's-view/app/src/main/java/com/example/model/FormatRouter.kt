package com.example.model

import android.content.Context
import android.net.Uri
import java.io.InputStream
import java.util.zip.ZipInputStream

data class DetectionResult(
  val format: FileFormat,
  val confidence: Float,
  val evidence: List<String>,
  val warnings: List<String>
)

object FormatRouter {

  fun detect(context: Context, source: FileSource): DetectionResult {
    val evidence = mutableListOf<String>()
    val warnings = mutableListOf<String>()

    val ext = source.displayName.substringAfterLast('.', "").lowercase()
    val mime = try {
      context.contentResolver.getType(source.uri)
    } catch (_: Exception) {
      null
    }

    if (source.sizeBytes == 0L) {
      // Could be empty file or unknown size
    }

    // Read first 2048 bytes for magic bytes and container sniffing
    val headerBytes = ByteArray(2048)
    var bytesRead = 0
    try {
      source.openStream().use { stream ->
        bytesRead = stream.read(headerBytes)
      }
    } catch (e: Exception) {
      warnings.add("Failed to read header bytes: ${e.message}")
    }

    if (bytesRead <= 0) {
      return DetectionResult(
        format = FileFormat.UNKNOWN,
        confidence = 0.0f,
        evidence = listOf("Empty or unreadable stream"),
        warnings = listOf("0 bytes read")
      )
    }

    // 1. PDF
    if (bytesRead >= 4 && headerBytes[0] == '%'.code.toByte() &&
      headerBytes[1] == 'P'.code.toByte() &&
      headerBytes[2] == 'D'.code.toByte() &&
      headerBytes[3] == 'F'.code.toByte()
    ) {
      evidence.add("Magic bytes: %PDF")
      if (ext != "pdf" && ext.isNotEmpty()) {
        warnings.add("Opened as PDF (the file extension said .$ext).")
      }
      return DetectionResult(FileFormat.PDF, 1.0f, evidence, warnings)
    }

    // 2. RTF
    if (bytesRead >= 5 && headerBytes[0] == '{'.code.toByte() &&
      headerBytes[1] == '\\'.code.toByte() &&
      headerBytes[2] == 'r'.code.toByte() &&
      headerBytes[3] == 't'.code.toByte() &&
      headerBytes[4] == 'f'.code.toByte()
    ) {
      evidence.add("Magic bytes: {\\rtf")
      return DetectionResult(FileFormat.RTF, 1.0f, evidence, warnings)
    }

    // 3. ZIP based formats: PK\x03\x04
    if (bytesRead >= 4 && headerBytes[0] == 0x50.toByte() && headerBytes[1] == 0x4B.toByte() &&
      headerBytes[2] == 0x03.toByte() && headerBytes[3] == 0x04.toByte()
    ) {
      evidence.add("Magic bytes: PK\\x03\\x04 (ZIP container)")
      val zipResult = sniffZipContainer(source, evidence, warnings, ext)
      if (zipResult != null) {
        return zipResult
      }
    }

    // 4. OLE2 compound document (legacy Office or Encrypted package)
    if (bytesRead >= 8 &&
      headerBytes[0] == 0xD0.toByte() && headerBytes[1] == 0xCF.toByte() &&
      headerBytes[2] == 0x11.toByte() && headerBytes[3] == 0xE0.toByte() &&
      headerBytes[4] == 0xA1.toByte() && headerBytes[5] == 0xB1.toByte() &&
      headerBytes[6] == 0x1A.toByte() && headerBytes[7] == 0xE1.toByte()
    ) {
      evidence.add("Magic bytes: OLE2 compound document")
      // Check if encrypted package
      val headerStr = String(headerBytes, 0, bytesRead, Charsets.ISO_8859_1)
      if (headerStr.contains("EncryptedPackage") || headerStr.contains("EncryptionInfo")) {
        evidence.add("OLE2 EncryptedPackage stream detected")
        return DetectionResult(FileFormat.ENCRYPTED_OFFICE, 0.95f, evidence, warnings)
      }
      return DetectionResult(FileFormat.LEGACY_OFFICE, 0.95f, evidence, warnings)
    }

    // 5. Images: PNG, JPEG, GIF, WEBP, BMP
    if (bytesRead >= 8 &&
      headerBytes[0] == 0x89.toByte() && headerBytes[1] == 0x50.toByte() &&
      headerBytes[2] == 0x4E.toByte() && headerBytes[3] == 0x47.toByte()
    ) {
      evidence.add("Magic bytes: PNG")
      return DetectionResult(FileFormat.IMAGE_RASTER, 1.0f, evidence, warnings)
    }

    if (bytesRead >= 3 &&
      headerBytes[0] == 0xFF.toByte() && headerBytes[1] == 0xD8.toByte() && headerBytes[2] == 0xFF.toByte()
    ) {
      evidence.add("Magic bytes: JPEG")
      return DetectionResult(FileFormat.IMAGE_RASTER, 1.0f, evidence, warnings)
    }

    if (bytesRead >= 6 &&
      headerBytes[0] == 'G'.code.toByte() && headerBytes[1] == 'I'.code.toByte() && headerBytes[2] == 'F'.code.toByte()
    ) {
      evidence.add("Magic bytes: GIF")
      return DetectionResult(FileFormat.IMAGE_RASTER, 1.0f, evidence, warnings)
    }

    if (bytesRead >= 12 &&
      headerBytes[0] == 'R'.code.toByte() && headerBytes[1] == 'I'.code.toByte() &&
      headerBytes[2] == 'F'.code.toByte() && headerBytes[3] == 'F'.code.toByte() &&
      headerBytes[8] == 'W'.code.toByte() && headerBytes[9] == 'E'.code.toByte() &&
      headerBytes[10] == 'B'.code.toByte() && headerBytes[11] == 'P'.code.toByte()
    ) {
      evidence.add("Magic bytes: RIFF...WEBP")
      return DetectionResult(FileFormat.IMAGE_RASTER, 1.0f, evidence, warnings)
    }

    if (bytesRead >= 2 && headerBytes[0] == 'B'.code.toByte() && headerBytes[1] == 'M'.code.toByte()) {
      evidence.add("Magic bytes: BMP")
      return DetectionResult(FileFormat.IMAGE_RASTER, 1.0f, evidence, warnings)
    }

    // 6. SVG or XML-based documents
    val headerText = String(headerBytes, 0, bytesRead, Charsets.UTF_8).trimStart()
    if (headerText.startsWith("<svg", ignoreCase = true) ||
      (headerText.startsWith("<?xml", ignoreCase = true) && headerText.contains("<svg", ignoreCase = true))
    ) {
      evidence.add("Content: SVG root element")
      return DetectionResult(FileFormat.IMAGE_SVG, 0.95f, evidence, warnings)
    }

    // Flat ODF XML formats (FODT, FODS, FODP, FODG)
    if (headerText.contains("<office:document") || headerText.contains("xmlns:office=")) {
      evidence.add("Content: Flat ODF XML")
      when {
        headerText.contains("mimetype=\"application/vnd.oasis.opendocument.text\"") || ext == "fodt" ->
          return DetectionResult(FileFormat.ODT, 0.95f, evidence, warnings)
        headerText.contains("mimetype=\"application/vnd.oasis.opendocument.spreadsheet\"") || ext == "fods" ->
          return DetectionResult(FileFormat.ODS, 0.95f, evidence, warnings)
        headerText.contains("mimetype=\"application/vnd.oasis.opendocument.presentation\"") || ext == "fodp" ->
          return DetectionResult(FileFormat.ODP, 0.95f, evidence, warnings)
        headerText.contains("mimetype=\"application/vnd.oasis.opendocument.graphics\"") || ext == "fodg" ->
          return DetectionResult(FileFormat.ODG, 0.95f, evidence, warnings)
      }
    }

    // 7. Check by extension for Apple iWork
    if (ext in listOf("pages", "numbers", "key")) {
      evidence.add("Extension: Apple iWork .$ext")
      return DetectionResult(FileFormat.APPLE_IWORK, 0.9f, evidence, warnings)
    }

    // 8. Markdown
    if (ext in listOf("md", "markdown")) {
      evidence.add("Extension: Markdown .$ext")
      return DetectionResult(FileFormat.MARKDOWN, 0.9f, evidence, warnings)
    }

    // 9. CSV / TSV
    if (ext in listOf("csv", "tsv")) {
      evidence.add("Extension: .$ext delimiter table")
      return DetectionResult(FileFormat.CSV, 0.9f, evidence, warnings)
    }

    // 10. Code & Text
    if (ext in FileFormat.CODE.extensions) {
      evidence.add("Extension: Source code / text .$ext")
      return DetectionResult(FileFormat.CODE, 0.85f, evidence, warnings)
    }

    // 11. Text sniffing heuristics (is it readable text?)
    var nullCount = 0
    for (i in 0 until bytesRead) {
      if (headerBytes[i] == 0.toByte()) nullCount++
    }

    val nullRatio = nullCount.toFloat() / bytesRead
    if (nullRatio > 0.05f) {
      // Binary file
      evidence.add("Binary content (null byte ratio: ${(nullRatio * 100).toInt()}%)")
      return DetectionResult(FileFormat.HEX, 0.6f, evidence, warnings)
    } else {
      evidence.add("Clean text content (null byte ratio < 5%)")
      return DetectionResult(FileFormat.CODE, 0.6f, evidence, warnings)
    }
  }

  private fun sniffZipContainer(
    source: FileSource,
    evidence: MutableList<String>,
    warnings: MutableList<String>,
    ext: String
  ): DetectionResult? {
    try {
      source.openStream().use { stream ->
        ZipInputStream(stream).use { zip ->
          var entry = zip.nextEntry
          var entryCount = 0
          var hasWord = false
          var hasXl = false
          var hasPpt = false
          var odfMime: String? = null

          while (entry != null && entryCount < 30) {
            val name = entry.name
            if (name == "mimetype") {
              val mimeBytes = ByteArray(256)
              val read = zip.read(mimeBytes)
              if (read > 0) {
                odfMime = String(mimeBytes, 0, read).trim()
              }
            } else if (name.startsWith("word/")) {
              hasWord = true
            } else if (name.startsWith("xl/")) {
              hasXl = true
            } else if (name.startsWith("ppt/")) {
              hasPpt = true
            }
            entryCount++
            entry = zip.nextEntry
          }

          // Check ODF mimetype
          if (odfMime != null) {
            evidence.add("ODF mimetype entry: $odfMime")
            return when {
              odfMime.contains("opendocument.text") -> DetectionResult(FileFormat.ODT, 1.0f, evidence, warnings)
              odfMime.contains("opendocument.spreadsheet") -> DetectionResult(FileFormat.ODS, 1.0f, evidence, warnings)
              odfMime.contains("opendocument.presentation") -> DetectionResult(FileFormat.ODP, 1.0f, evidence, warnings)
              odfMime.contains("opendocument.graphics") -> DetectionResult(FileFormat.ODG, 1.0f, evidence, warnings)
              else -> DetectionResult(FileFormat.ARCHIVE, 0.8f, evidence, warnings)
            }
          }

          if (hasWord) {
            evidence.add("OOXML package: contains word/")
            return DetectionResult(FileFormat.DOCX, 1.0f, evidence, warnings)
          }
          if (hasXl) {
            evidence.add("OOXML package: contains xl/")
            return DetectionResult(FileFormat.XLSX, 1.0f, evidence, warnings)
          }
          if (hasPpt) {
            evidence.add("OOXML package: contains ppt/")
            return DetectionResult(FileFormat.PPTX, 1.0f, evidence, warnings)
          }

          // Check fallback by extension
          when (ext) {
            "docx", "dotx", "docm" -> return DetectionResult(FileFormat.DOCX, 0.9f, evidence, warnings)
            "xlsx", "xlsm", "xltx" -> return DetectionResult(FileFormat.XLSX, 0.9f, evidence, warnings)
            "pptx", "potx", "ppsx", "pptm" -> return DetectionResult(FileFormat.PPTX, 0.9f, evidence, warnings)
            "odt", "ott" -> return DetectionResult(FileFormat.ODT, 0.9f, evidence, warnings)
            "ods", "ots" -> return DetectionResult(FileFormat.ODS, 0.9f, evidence, warnings)
            "odp", "otp" -> return DetectionResult(FileFormat.ODP, 0.9f, evidence, warnings)
            "odg", "otg" -> return DetectionResult(FileFormat.ODG, 0.9f, evidence, warnings)
            else -> {
              evidence.add("Standard ZIP archive")
              return DetectionResult(FileFormat.ARCHIVE, 0.9f, evidence, warnings)
            }
          }
        }
      }
    } catch (e: Exception) {
      warnings.add("ZIP inspection warning: ${e.message}")
    }
    return null
  }
}
