package com.example

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.model.FileFormat
import com.example.model.FileSource
import com.example.model.FormatRouter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Dragon's View", appName)
  }

  @Test
  fun `test format detection by extension and magic bytes`() {
    val context = ApplicationProvider.getApplicationContext<Context>()

    // Test RTF detection
    val rtfFile = File(context.cacheDir, "test.rtf").apply {
      writeText("{\\rtf1\\ansi\\deff0 {\\fonttbl {\\f0 Arial;}} \\f0\\fs24 Hello World!}")
    }
    val rtfSource = FileSource.fromUri(context, Uri.fromFile(rtfFile))
    val rtfResult = FormatRouter.detect(context, rtfSource)
    assertEquals(FileFormat.RTF, rtfResult.format)

    // Test CSV detection
    val csvFile = File(context.cacheDir, "test.csv").apply {
      writeText("Year,Make,Model\n1997,Ford,E350\n2000,Mercury,Cougar")
    }
    val csvSource = FileSource.fromUri(context, Uri.fromFile(csvFile))
    val csvResult = FormatRouter.detect(context, csvSource)
    assertEquals(FileFormat.CSV, csvResult.format)

    // Test Markdown detection
    val mdFile = File(context.cacheDir, "test.md").apply {
      writeText("# Title\n\n- [x] Task 1\n- [ ] Task 2\n\n> Note")
    }
    val mdSource = FileSource.fromUri(context, Uri.fromFile(mdFile))
    val mdResult = FormatRouter.detect(context, mdSource)
    assertEquals(FileFormat.MARKDOWN, mdResult.format)
  }
}
