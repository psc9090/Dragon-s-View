package com.dragonview.app

import android.view.ViewGroup
import android.widget.TextView
import com.otaliastudios.zoom.ZoomLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TestZoomActivityTest {

    @Test
    fun testTestZoomActivityLaunchesWithZoomLayoutAndTextView() {
        val controller = Robolectric.buildActivity(TestZoomActivity::class.java).setup()
        val activity = controller.get()
        assertNotNull("Activity must be created", activity)

        val rootView = activity.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0)
        assertNotNull("Root view must exist", rootView)
        assertTrue("Root view must be ZoomLayout", rootView is ZoomLayout)

        val zoomLayout = rootView as ZoomLayout
        assertEquals("ZoomLayout must have exactly 1 child", 1, zoomLayout.childCount)

        val child = zoomLayout.getChildAt(0)
        assertTrue("Child must be a plain TextView", child is TextView)
        val textView = child as TextView
        assertTrue("TextView must contain dummy text", textView.text.contains("Line 1:"))
        assertTrue("TextView must contain 50 lines", textView.text.contains("Line 50:"))

        // Verify ZoomEngine is accessible and initialized
        assertNotNull("ZoomEngine must be initialized", zoomLayout.engine)
        assertTrue("ZoomEngine zoom must be positive", zoomLayout.engine.realZoom > 0f)
    }
}
