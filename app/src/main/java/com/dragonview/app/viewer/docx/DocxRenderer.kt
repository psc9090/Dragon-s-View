// app/src/main/java/com/dragonview/app/viewer/docx/DocxRenderer.kt
package com.dragonview.app.viewer.docx

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.dragonview.app.router.FormatDetectionResult
import com.dragonview.app.viewer.document.DocumentRenderer

/**
 * DocxRenderer delegates to the unified DocumentRenderer, ensuring shared
 * low-RAM recycling and Spannable formatting across both DOCX and RTF formats.
 */
@Composable
fun DocxRenderer(
    uri: Uri,
    detectionResult: FormatDetectionResult,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null
) {
    DocumentRenderer(
        uri = uri,
        detectionResult = detectionResult,
        modifier = modifier,
        onBack = onBack
    )
}
