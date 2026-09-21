// app/src/main/java/com/dragonview/app/viewer/docx/DocxModel.kt
package com.dragonview.app.viewer.docx

import com.dragonview.app.viewer.document.DocumentAlignment
import com.dragonview.app.viewer.document.DocumentData
import com.dragonview.app.viewer.document.DocumentElement
import com.dragonview.app.viewer.document.DocumentRun
import com.dragonview.app.viewer.document.DocumentTableCell
import com.dragonview.app.viewer.document.DocumentTableRow

/**
 * Typealiases mapping DOCX-specific types to universal shared DocumentModel definitions.
 */
typealias DocxRun = DocumentRun
typealias DocxAlignment = DocumentAlignment
typealias DocxElement = DocumentElement
typealias TableRow = DocumentTableRow
typealias TableCell = DocumentTableCell
typealias DocxDocument = DocumentData
