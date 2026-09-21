package com.dragonview.app.ui

import android.content.Context
import android.graphics.Matrix
import android.view.View
import android.widget.FrameLayout
import com.otaliastudios.zoom.ZoomApi
import com.otaliastudios.zoom.ZoomEngine
import com.otaliastudios.zoom.ZoomLayout

/**
 * Standard ZoomLayout factory conforming strictly to:
 * - app:transformation="centerInside"
 * - app:overScrollHorizontal="false"
 * - app:overScrollVertical="false"
 * - app:minZoom="1" app:maxZoom="5"
 *
 * Provides single-point zoom setup for DOCX/RTF/ODT, XLSX/ODS, PPTX/ODP, Code, and ODG.
 */
object ZoomHelper {
    fun createZoomLayout(
        context: Context,
        contentView: View,
        tag: String = "ZoomLayout",
        minZoom: Float = 1.0f,
        maxZoom: Float = 5.0f,
        onZoomChanged: ((Float) -> Unit)? = null
    ): ZoomLayout {
        val zoomLayout = ZoomLayout(context).apply {
            this.tag = tag
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setTransformation(ZoomApi.TRANSFORMATION_CENTER_INSIDE)
            setOverScrollHorizontal(false)
            setOverScrollVertical(false)
            setMinZoom(minZoom, ZoomApi.TYPE_ZOOM)
            setMaxZoom(maxZoom, ZoomApi.TYPE_ZOOM)
            setHasClickableChildren(true)

            if (onZoomChanged != null) {
                engine.addListener(object : ZoomEngine.Listener {
                    override fun onUpdate(engine: ZoomEngine, matrix: Matrix) {
                        onZoomChanged(engine.zoom)
                    }
                    override fun onIdle(engine: ZoomEngine) {}
                })
            }
        }

        contentView.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        zoomLayout.addView(contentView)
        return zoomLayout
    }
}
