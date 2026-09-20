package com.example.util

import android.content.Context
import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintManager
import java.nio.charset.Charset

object TextEncodingDetector {
  fun detect(bytes: ByteArray): Charset {
    if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
      return Charsets.UTF_8
    }
    if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
      return Charsets.UTF_16BE
    }
    if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
      return Charsets.UTF_16LE
    }
    return Charsets.UTF_8
  }
}

object PrintHelper {
  fun printFile(context: Context, docName: String, adapter: PrintDocumentAdapter) {
    try {
      val printManager = context.getSystemService(Context.PRINT_SERVICE) as? PrintManager
      printManager?.print(docName, adapter, PrintAttributes.Builder().build())
    } catch (_: Exception) {}
  }
}
