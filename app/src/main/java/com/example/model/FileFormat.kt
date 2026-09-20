package com.example.model

enum class FileFormat(
  val displayName: String,
  val category: String,
  val extensions: List<String>
) {
  PDF("PDF Document", "Document", listOf("pdf")),
  DOCX("Word Document", "Document", listOf("docx", "dotx", "docm")),
  RTF("Rich Text Document", "Document", listOf("rtf")),
  ODT("OpenDocument Text", "Document", listOf("odt", "ott", "fodt")),
  XLSX("Excel Spreadsheet", "Spreadsheet", listOf("xlsx", "xltx", "xlsm")),
  ODS("OpenDocument Spreadsheet", "Spreadsheet", listOf("ods", "ots", "fods")),
  CSV("CSV / TSV Data", "Spreadsheet", listOf("csv", "tsv")),
  PPTX("PowerPoint Presentation", "Presentation", listOf("pptx", "potx", "ppsx", "pptm")),
  ODP("OpenDocument Presentation", "Presentation", listOf("odp", "otp", "fodp")),
  ODG("OpenDocument Drawing", "Drawing", listOf("odg", "otg", "fodg")),
  IMAGE_RASTER("Raster Image", "Image", listOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")),
  IMAGE_SVG("Scalable Vector Graphics", "Image", listOf("svg")),
  CODE("Source Code", "Code", listOf(
    "txt", "py", "c", "h", "cpp", "hpp", "java", "kt", "kts", "js", "ts",
    "jsx", "tsx", "html", "css", "json", "xml", "sh", "sql", "yaml", "yml",
    "toml", "ini", "cfg", "conf", "properties", "gradle", "go", "rs", "php",
    "rb", "swift", "dart", "lua", "r", "cs", "pl", "bat", "ps1", "diff", "patch", "log"
  )),
  MARKDOWN("Markdown Document", "Document", listOf("md", "markdown")),
  JSON_TREE("JSON Data Structure", "Data", listOf("json")),
  XML_TREE("XML Data Structure", "Data", listOf("xml")),
  LOG("System / Application Log", "Text", listOf("log")),
  ARCHIVE("Compressed Archive", "Archive", listOf("zip", "tar", "gz", "tgz")),
  LEGACY_OFFICE("Legacy Office Document (.doc/.xls/.ppt)", "Legacy", listOf("doc", "xls", "ppt")),
  APPLE_IWORK("Apple iWork Document", "Proprietary", listOf("pages", "numbers", "key")),
  ENCRYPTED_OFFICE("Encrypted Office Package", "Security", listOf()),
  HEX("Binary File (Hex View)", "Binary", listOf()),
  UNKNOWN("Unknown File", "Unknown", listOf());

  val isZipBased: Boolean
    get() = this in listOf(DOCX, XLSX, PPTX, ODT, ODS, ODP, ODG, ARCHIVE)
}
