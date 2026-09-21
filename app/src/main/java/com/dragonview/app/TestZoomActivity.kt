package com.dragonview.app

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.TextView
import com.otaliastudios.zoom.ZoomLayout

/**
 * Isolated diagnostic activity containing ONLY:
 * 1. A com.otaliastudios:zoomlayout ZoomLayout as the root view.
 * 2. A single plain TextView with 50 lines of dummy text.
 * 3. Zero custom gesture code, zero custom views, zero theme customization.
 */
class TestZoomActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val zoomLayout = ZoomLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        val textView = TextView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(32, 48, 32, 48)
            textSize = 16f
            text = (1..50).joinToString("\n") { lineIndex ->
                "Line $lineIndex: Diagnostic ZoomLayout test — The quick brown fox jumps over the lazy dog."
            }
        }

        zoomLayout.addView(textView)
        setContentView(zoomLayout)
    }
}
