package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.style.UnderlineSpan
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.dragonview.app.viewer.pptx.PptxParser
import com.dragonview.app.viewer.pptx.SlideElement
import com.dragonview.app.viewer.pptx.SlideView
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PptxFullFeaturesTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun create1x1PngBytes(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color)
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        return stream.toByteArray()
    }

    @Test
    fun testSlideBackgroundDirectAndInherited() = runBlocking {
        val imgBytes1 = create1x1PngBytes(Color.RED)
        val imgBytes2 = create1x1PngBytes(Color.BLUE)

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            // [Content_Types].xml
            zos.putNextEntry(ZipEntry("[Content_Types].xml"))
            zos.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Default Extension="png" ContentType="image/png"/>
  <Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>
</Types>""".toByteArray())
            zos.closeEntry()

            // _rels/.rels
            zos.putNextEntry(ZipEntry("_rels/.rels"))
            zos.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>
</Relationships>""".toByteArray())
            zos.closeEntry()

            // presentation.xml
            zos.putNextEntry(ZipEntry("ppt/presentation.xml"))
            zos.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
  <p:sldSz cx="12192000" cy="6858000"/>
  <p:sldIdLst>
    <p:sldId id="256" r:id="rId1"/>
    <p:sldId id="257" r:id="rId2"/>
    <p:sldId id="258" r:id="rId3"/>
  </p:sldIdLst>
</p:presentation>""".toByteArray())
            zos.closeEntry()

            // presentation.xml.rels
            zos.putNextEntry(ZipEntry("ppt/_rels/presentation.xml.rels"))
            zos.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Target="slides/slide1.xml"/>
  <Relationship Id="rId2" Target="slides/slide2.xml"/>
  <Relationship Id="rId3" Target="slides/slide3.xml"/>
</Relationships>""".toByteArray())
            zos.closeEntry()

            // slide1.xml: Direct gradient background
            zos.putNextEntry(ZipEntry("ppt/slides/slide1.xml"))
            zos.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
  <p:cSld>
    <p:bg>
      <p:bgPr>
        <a:gradFill>
          <a:gsLst>
            <a:gs pos="0"><a:srgbClr val="1E3C72"/></a:gs>
            <a:gs pos="100000"><a:srgbClr val="2A5298"/></a:gs>
          </a:gsLst>
        </a:gradFill>
      </p:bgPr>
    </p:bg>
    <p:spTree>
      <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:grpSpPr/></p:nvGrpSpPr>
      <p:sp>
        <p:spPr><a:xfrm><a:off x="100" y="100"/><a:ext cx="200" cy="200"/></a:xfrm></p:spPr>
        <p:txBody><a:bodyPr/><a:p><a:r><a:t>Slide 1</a:t></a:r></a:p></p:txBody>
      </p:sp>
    </p:spTree>
  </p:cSld>
</p:sld>""".toByteArray())
            zos.closeEntry()

            // slide1.xml.rels
            zos.putNextEntry(ZipEntry("ppt/slides/_rels/slide1.xml.rels"))
            zos.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Target="../slideLayouts/slideLayout1.xml"/>
</Relationships>""".toByteArray())
            zos.closeEntry()

            // slide2.xml: No background, inherits from slideLayout1.xml
            zos.putNextEntry(ZipEntry("ppt/slides/slide2.xml"))
            zos.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
  <p:cSld>
    <p:spTree>
      <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:grpSpPr/></p:nvGrpSpPr>
      <p:sp>
        <p:spPr><a:xfrm><a:off x="100" y="100"/><a:ext cx="200" cy="200"/></a:xfrm></p:spPr>
        <p:txBody><a:bodyPr/><a:p><a:r><a:t>Slide 2</a:t></a:r></a:p></p:txBody>
      </p:sp>
    </p:spTree>
  </p:cSld>
</p:sld>""".toByteArray())
            zos.closeEntry()

            // slide2.xml.rels -> slideLayout1.xml
            zos.putNextEntry(ZipEntry("ppt/slides/_rels/slide2.xml.rels"))
            zos.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Target="../slideLayouts/slideLayout1.xml"/>
</Relationships>""".toByteArray())
            zos.closeEntry()

            // slideLayout1.xml: Has solid background #FF5722
            zos.putNextEntry(ZipEntry("ppt/slideLayouts/slideLayout1.xml"))
            zos.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sldLayout xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
  <p:cSld>
    <p:bg>
      <p:bgPr>
        <a:solidFill><a:srgbClr val="FF5722"/></a:solidFill>
      </p:bgPr>
    </p:bg>
    <p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:grpSpPr/></p:nvGrpSpPr></p:spTree>
  </p:cSld>
</p:sldLayout>""".toByteArray())
            zos.closeEntry()

            // slide3.xml: No background, slideLayout2 has no background, inherits from slideMaster1.xml
            zos.putNextEntry(ZipEntry("ppt/slides/slide3.xml"))
            zos.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
  <p:cSld>
    <p:spTree>
      <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:grpSpPr/></p:nvGrpSpPr>
      <p:sp>
        <p:spPr><a:xfrm><a:off x="100" y="100"/><a:ext cx="200" cy="200"/></a:xfrm></p:spPr>
        <p:txBody><a:bodyPr/><a:p><a:r><a:t>Slide 3</a:t></a:r></a:p></p:txBody>
      </p:sp>
    </p:spTree>
  </p:cSld>
</p:sld>""".toByteArray())
            zos.closeEntry()

            // slide3.xml.rels -> slideLayout2.xml
            zos.putNextEntry(ZipEntry("ppt/slides/_rels/slide3.xml.rels"))
            zos.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Target="../slideLayouts/slideLayout2.xml"/>
</Relationships>""".toByteArray())
            zos.closeEntry()

            // slideLayout2.xml: No background
            zos.putNextEntry(ZipEntry("ppt/slideLayouts/slideLayout2.xml"))
            zos.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sldLayout xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
  <p:cSld>
    <p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:grpSpPr/></p:nvGrpSpPr></p:spTree>
  </p:cSld>
</p:sldLayout>""".toByteArray())
            zos.closeEntry()

            // slideLayout2.xml.rels -> slideMaster1.xml
            zos.putNextEntry(ZipEntry("ppt/slideLayouts/_rels/slideLayout2.xml.rels"))
            zos.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Target="../slideMasters/slideMaster1.xml"/>
</Relationships>""".toByteArray())
            zos.closeEntry()

            // slideMaster1.xml: Has solid background #009688
            zos.putNextEntry(ZipEntry("ppt/slideMasters/slideMaster1.xml"))
            zos.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sldMaster xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
  <p:cSld>
    <p:bg>
      <p:bgPr>
        <a:solidFill><a:srgbClr val="009688"/></a:solidFill>
      </p:bgPr>
    </p:bg>
    <p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:grpSpPr/></p:nvGrpSpPr></p:spTree>
  </p:cSld>
</p:sldMaster>""".toByteArray())
            zos.closeEntry()
        }

        val file = File(context.cacheDir, "test_bg_inheritance.pptx").apply {
            FileOutputStream(this).use { it.write(baos.toByteArray()) }
        }
        val uri = Uri.fromFile(file)

        val result = PptxParser.parse(context, uri)
        assertTrue(result.isSuccess)
        val presentation = result.getOrNull()!!
        assertEquals(3, presentation.slideCount)

        // Slide 1: Gradient
        val s1 = presentation.slides[0]
        assertNotNull(s1.background)
        assertTrue(s1.background!!.isGradient)
        assertEquals("#1E3C72", s1.background!!.gradientColorsHex?.get(0))
        assertEquals("#2A5298", s1.background!!.gradientColorsHex?.get(1))

        // Slide 2: Inherited from layout #FF5722
        val s2 = presentation.slides[1]
        assertEquals("#FF5722", s2.backgroundColorHex)

        // Slide 3: Inherited from master #009688
        val s3 = presentation.slides[2]
        assertEquals("#009688", s3.backgroundColorHex)

        // Verify SlideView rendering
        val slideView1 = SlideView(context).apply {
            setSlide(s1, presentation.slideWidthEmu, presentation.slideHeightEmu)
        }
        assertTrue(slideView1.background is GradientDrawable)

        val slideView2 = SlideView(context).apply {
            setSlide(s2, presentation.slideWidthEmu, presentation.slideHeightEmu)
        }
        val bg2 = slideView2.background as? ColorDrawable
        assertNotNull(bg2)
        assertEquals(Color.parseColor("#FF5722"), bg2!!.color)
    }

    @Test
    fun testFullRunFormattingAndRendering() = runBlocking {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("[Content_Types].xml"))
            zos.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>
</Types>""".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("_rels/.rels"))
            zos.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Target="ppt/presentation.xml"/>
</Relationships>""".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("ppt/presentation.xml"))
            zos.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
  <p:sldSz cx="12192000" cy="6858000"/>
  <p:sldIdLst><p:sldId id="256" r:id="rId1"/></p:sldIdLst>
</p:presentation>""".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("ppt/_rels/presentation.xml.rels"))
            zos.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Target="slides/slide1.xml"/>
</Relationships>""".toByteArray())
            zos.closeEntry()

            // slide1.xml with full run formatting
            zos.putNextEntry(ZipEntry("ppt/slides/slide1.xml"))
            zos.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
  <p:cSld>
    <p:spTree>
      <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:grpSpPr/></p:nvGrpSpPr>
      <p:sp>
        <p:spPr><a:xfrm><a:off x="100000" y="100000"/><a:ext cx="5000000" cy="2000000"/></a:xfrm></p:spPr>
        <p:txBody>
          <a:bodyPr/>
          <a:p>
            <a:r>
              <a:rPr b="1" i="1" u="sng" sz="2400">
                <a:latin typeface="Roboto"/>
                <a:srgbClr val="E91E63"/>
              </a:rPr>
              <a:t>Styled Formatting Test</a:t>
            </a:r>
          </a:p>
        </p:txBody>
      </p:sp>
    </p:spTree>
  </p:cSld>
</p:sld>""".toByteArray())
            zos.closeEntry()
        }

        val file = File(context.cacheDir, "test_run_formatting.pptx").apply {
            FileOutputStream(this).use { it.write(baos.toByteArray()) }
        }
        val uri = Uri.fromFile(file)

        val result = PptxParser.parse(context, uri)
        assertTrue(result.isSuccess)
        val presentation = result.getOrNull()!!
        val slide = presentation.slides[0]

        val textBox = slide.elements.filterIsInstance<SlideElement.TextBox>().first()
        val run = textBox.paragraphs[0].runs[0]
        assertEquals("Styled Formatting Test", run.text)
        assertTrue(run.isBold)
        assertTrue(run.isItalic)
        assertTrue(run.isUnderline)
        assertEquals(24f, run.fontSizePt)
        assertEquals("#E91E63", run.colorHex)
        assertEquals("Roboto", run.fontFamily)

        // Verify SlideView rendering applies spans
        val slideView = SlideView(context).apply {
            setSlide(slide, presentation.slideWidthEmu, presentation.slideHeightEmu)
            measure(
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, 1000, 800)
        }

        val textView = slideView.getChildAt(0) as TextView
        val spanned = textView.text as Spanned

        val styleSpans = spanned.getSpans(0, spanned.length, StyleSpan::class.java)
        assertTrue("Expected bold/italic StyleSpan", styleSpans.isNotEmpty())

        val underlineSpans = spanned.getSpans(0, spanned.length, UnderlineSpan::class.java)
        assertTrue("Expected UnderlineSpan", underlineSpans.isNotEmpty())

        val colorSpans = spanned.getSpans(0, spanned.length, ForegroundColorSpan::class.java)
        assertTrue("Expected ForegroundColorSpan", colorSpans.isNotEmpty())
        assertEquals(Color.parseColor("#E91E63"), colorSpans[0].foregroundColor)

        val typefaceSpans = spanned.getSpans(0, spanned.length, TypefaceSpan::class.java)
        assertTrue("Expected TypefaceSpan", typefaceSpans.isNotEmpty())
        assertEquals("Roboto", typefaceSpans[0].family)

        val sizeSpans = spanned.getSpans(0, spanned.length, AbsoluteSizeSpan::class.java)
        assertTrue("Expected AbsoluteSizeSpan", sizeSpans.isNotEmpty())
    }

    @Test
    fun testPlaceholderInheritanceAndDecorativeShapesAndMultipleImages() = runBlocking {
        val imgRed = create1x1PngBytes(Color.RED)
        val imgBlue = create1x1PngBytes(Color.BLUE)

        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("[Content_Types].xml"))
            zos.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Default Extension="png" ContentType="image/png"/>
  <Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>
</Types>""".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("_rels/.rels"))
            zos.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Target="ppt/presentation.xml"/>
</Relationships>""".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("ppt/presentation.xml"))
            zos.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
  <p:sldSz cx="12192000" cy="6858000"/>
  <p:sldIdLst><p:sldId id="256" r:id="rId1"/></p:sldIdLst>
</p:presentation>""".toByteArray())
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("ppt/_rels/presentation.xml.rels"))
            zos.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Target="slides/slide1.xml"/>
</Relationships>""".toByteArray())
            zos.closeEntry()

            // slide1.xml
            // Shape 1: Title placeholder with no off/ext -> inherits from slideLayout1
            // Shape 2: Decorative solid-fill shape with fill=#3F51B5 and border, but NO text
            // Picture 1: Image red
            // Picture 2: Image blue
            zos.putNextEntry(ZipEntry("ppt/slides/slide1.xml"))
            zos.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
  <p:cSld>
    <p:spTree>
      <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:grpSpPr/></p:nvGrpSpPr>
      <p:sp>
        <p:nvSpPr><p:cNvPr id="2" name="Title 1"/><p:cNvSpPr/><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr>
        <p:spPr/>
        <p:txBody><a:bodyPr/><a:p><a:r><a:t>Inherited Title</a:t></a:r></a:p></p:txBody>
      </p:sp>
      <p:sp>
        <p:nvSpPr><p:cNvPr id="3" name="DecoBox"/><p:cNvSpPr/><p:nvPr/></p:nvSpPr>
        <p:spPr>
          <a:xfrm><a:off x="500000" y="4000000"/><a:ext cx="3000000" cy="1000000"/></a:xfrm>
          <a:solidFill><a:srgbClr val="3F51B5"/></a:solidFill>
          <a:ln w="25400"><a:solidFill><a:srgbClr val="FF9800"/></a:solidFill></a:ln>
        </p:spPr>
      </p:sp>
      <p:pic>
        <p:nvPicPr><p:cNvPr id="4" name="Pic1"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr>
        <p:blipFill><a:blip r:embed="rIdImg1"/></p:blipFill>
        <p:spPr><a:xfrm><a:off x="4000000" y="4000000"/><a:ext cx="2000000" cy="2000000"/></a:xfrm></p:spPr>
      </p:pic>
      <p:pic>
        <p:nvPicPr><p:cNvPr id="5" name="Pic2"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr>
        <p:blipFill><a:blip r:embed="rIdImg2"/></p:blipFill>
        <p:spPr><a:xfrm><a:off x="7000000" y="4000000"/><a:ext cx="2000000" cy="2000000"/></a:xfrm></p:spPr>
      </p:pic>
    </p:spTree>
  </p:cSld>
</p:sld>""".toByteArray())
            zos.closeEntry()

            // slide1.xml.rels
            zos.putNextEntry(ZipEntry("ppt/slides/_rels/slide1.xml.rels"))
            zos.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Target="../slideLayouts/slideLayout1.xml"/>
  <Relationship Id="rIdImg1" Target="../media/image1.png"/>
  <Relationship Id="rIdImg2" Target="../media/image2.png"/>
</Relationships>""".toByteArray())
            zos.closeEntry()

            // slideLayout1.xml: Defines title placeholder position/size
            zos.putNextEntry(ZipEntry("ppt/slideLayouts/slideLayout1.xml"))
            zos.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sldLayout xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
  <p:cSld>
    <p:spTree>
      <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:grpSpPr/></p:nvGrpSpPr>
      <p:sp>
        <p:nvSpPr><p:cNvPr id="2" name="Title Layout"/><p:cNvSpPr/><p:nvPr><p:ph type="title"/></p:nvPr></p:nvSpPr>
        <p:spPr>
          <a:xfrm><a:off x="650000" y="800000"/><a:ext cx="8500000" cy="1800000"/></a:xfrm>
        </p:spPr>
      </p:sp>
    </p:spTree>
  </p:cSld>
</p:sldLayout>""".toByteArray())
            zos.closeEntry()

            // Media images
            zos.putNextEntry(ZipEntry("ppt/media/image1.png"))
            zos.write(imgRed)
            zos.closeEntry()

            zos.putNextEntry(ZipEntry("ppt/media/image2.png"))
            zos.write(imgBlue)
            zos.closeEntry()
        }

        val file = File(context.cacheDir, "test_ph_deco_images.pptx").apply {
            FileOutputStream(this).use { it.write(baos.toByteArray()) }
        }
        val uri = Uri.fromFile(file)

        val result = PptxParser.parse(context, uri)
        assertTrue(result.isSuccess)
        val presentation = result.getOrNull()!!
        val slide = presentation.slides[0]

        // 1. Check placeholder inheritance
        val textBox = slide.elements.filterIsInstance<SlideElement.TextBox>().first()
        assertEquals(650000L, textBox.xEmu)
        assertEquals(800000L, textBox.yEmu)
        assertEquals(8500000L, textBox.wEmu)
        assertEquals(1800000L, textBox.hEmu)

        // 2. Check decorative shape
        val shapeBoxes = slide.elements.filterIsInstance<SlideElement.ShapeBox>()
        assertEquals(1, shapeBoxes.size)
        val deco = shapeBoxes[0]
        assertEquals("#3F51B5", deco.fillColorHex)
        assertEquals("#FF9800", deco.borderColorHex)
        assertEquals(2.0f, deco.borderWidthPt)

        // 3. Check multiple images resolved via .rels
        val imageBoxes = slide.elements.filterIsInstance<SlideElement.ImageBox>()
        assertEquals(2, imageBoxes.size)
        assertNotNull(imageBoxes[0].bitmap)
        assertNotNull(imageBoxes[1].bitmap)

        // 4. Verify SlideView instantiates ShapeView and ImageViews
        val slideView = SlideView(context).apply {
            setSlide(slide, presentation.slideWidthEmu, presentation.slideHeightEmu)
        }
        assertEquals(4, slideView.childCount)
        assertTrue(slideView.getChildAt(0) is TextView)
        assertTrue(slideView.getChildAt(1) is View) // ShapeBox View
        assertTrue(slideView.getChildAt(2) is ImageView)
        assertTrue(slideView.getChildAt(3) is ImageView)
    }

    @Test
    fun testPptxAuditFeatures() = runBlocking {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            // [Content_Types].xml
            zos.putNextEntry(ZipEntry("[Content_Types].xml"))
            zos.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>
</Types>""".toByteArray())
            zos.closeEntry()

            // _rels/.rels
            zos.putNextEntry(ZipEntry("_rels/.rels"))
            zos.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>
</Relationships>""".toByteArray())
            zos.closeEntry()

            // ppt/presentation.xml
            zos.putNextEntry(ZipEntry("ppt/presentation.xml"))
            zos.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:presentation xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">
  <p:sldSz cx="10000000" cy="5000000"/>
  <p:sldIdLst>
    <p:sldId id="256" r:id="rId1"/>
  </p:sldIdLst>
</p:presentation>""".toByteArray())
            zos.closeEntry()

            // ppt/_rels/presentation.xml.rels
            zos.putNextEntry(ZipEntry("ppt/_rels/presentation.xml.rels"))
            zos.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Target="slides/slide1.xml"/>
</Relationships>""".toByteArray())
            zos.closeEntry()

            // ppt/theme/theme1.xml
            zos.putNextEntry(ZipEntry("ppt/theme/theme1.xml"))
            zos.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="Office Theme">
  <a:themeElements>
    <a:clrScheme name="Office">
      <a:dk1><a:sysClr val="windowText" lastClr="111111"/></a:dk1>
      <a:lt1><a:sysClr val="window" lastClr="FAFAFA"/></a:lt1>
      <a:dk2><a:srgbClr val="222222"/></a:dk2>
      <a:lt2><a:srgbClr val="EEEEEE"/></a:lt2>
      <a:accent1><a:srgbClr val="336699"/></a:accent1>
      <a:accent2><a:srgbClr val="FF5500"/></a:accent2>
      <a:accent3><a:srgbClr val="00AA55"/></a:accent3>
      <a:accent4><a:srgbClr val="AA00AA"/></a:accent4>
      <a:accent5><a:srgbClr val="F0AD4E"/></a:accent5>
      <a:accent6><a:srgbClr val="5BC0DE"/></a:accent6>
    </a:clrScheme>
  </a:themeElements>
</a:theme>""".toByteArray())
            zos.closeEntry()

            // ppt/slides/slide1.xml
            zos.putNextEntry(ZipEntry("ppt/slides/slide1.xml"))
            zos.write("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
       xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
  <p:cSld>
    <p:spTree>
      <!-- Shape 1: Rotated Shape with Gradient Fill -->
      <p:sp>
        <p:spPr>
          <a:xfrm rot="5400000"><a:off x="100000" y="100000"/><a:ext cx="2000000" cy="1000000"/></a:xfrm>
          <a:gradFill>
            <a:gsLst>
              <a:gs pos="0"><a:schemeClr val="accent1"/></a:gs>
              <a:gs pos="100000"><a:schemeClr val="accent2"/></a:gs>
            </a:gsLst>
          </a:gradFill>
        </p:spPr>
      </p:sp>

      <!-- Shape 2: Bullet formatting with hierarchy and theme schemeClr -->
      <p:sp>
        <p:spPr>
          <a:xfrm><a:off x="100000" y="2000000"/><a:ext cx="4000000" cy="2000000"/></a:xfrm>
        </p:spPr>
        <p:txBody>
          <p:p>
            <a:pPr lvl="0">
              <a:buChar char="★"/>
            </a:pPr>
            <a:r>
              <a:rPr>
                <a:schemeClr val="accent1">
                  <a:lumMod val="80000"/>
                </a:schemeClr>
              </a:rPr>
              <a:t>Level 0 bullet</a:t>
            </a:r>
          </p:p>
          <p:p>
            <a:pPr lvl="1">
              <a:buAutoNum/>
            </a:pPr>
            <a:r>
              <a:t>Level 1 nested</a:t>
            </a:r>
          </p:p>
        </p:txBody>
      </p:sp>

      <!-- Group 3: Grouped shapes with transform offsets and child scaling -->
      <p:grpSp>
        <p:grpSpPr>
          <a:xfrm>
            <a:off x="5000000" y="1000000"/>
            <a:ext cx="4000000" cy="2000000"/>
            <a:chOff x="0" y="0"/>
            <a:chExt cx="2000000" cy="1000000"/>
          </a:xfrm>
        </p:grpSpPr>
        <p:sp>
          <p:spPr>
            <a:xfrm><a:off x="500000" y="250000"/><a:ext cx="1000000" cy="500000"/></a:xfrm>
            <a:solidFill><a:schemeClr val="accent3"/></a:solidFill>
          </p:spPr>
        </p:sp>
      </p:grpSp>
    </p:spTree>
  </p:cSld>
</p:sld>""".toByteArray())
            zos.closeEntry()
        }

        val file = File(context.cacheDir, "test_pptx_audit.pptx").apply {
            FileOutputStream(this).use { it.write(baos.toByteArray()) }
        }
        val uri = Uri.fromFile(file)

        val result = PptxParser.parse(context, uri)
        assertTrue(result.isSuccess)
        val presentation = result.getOrNull()!!
        val slide = presentation.slides[0]

        // 1. Check Shape 1: Rotated Shape with Gradient
        val shapeBoxes = slide.elements.filterIsInstance<SlideElement.ShapeBox>()
        assertEquals(2, shapeBoxes.size)
        val gradShape = shapeBoxes[0]
        assertEquals(90.0f, gradShape.rotationDeg, 0.01f)
        assertNotNull(gradShape.gradientColorsHex)
        assertEquals(2, gradShape.gradientColorsHex!!.size)
        assertEquals("#336699", gradShape.gradientColorsHex!![0])
        assertEquals("#FF5500", gradShape.gradientColorsHex!![1])

        // 2. Check Shape 2: Bullets and Indentation Hierarchy
        val textBoxes = slide.elements.filterIsInstance<SlideElement.TextBox>()
        assertEquals(1, textBoxes.size)
        val textShape = textBoxes[0]
        assertEquals(2, textShape.paragraphs.size)
        val p0 = textShape.paragraphs[0]
        assertTrue(p0.isBullet)
        assertEquals("★", p0.bulletChar)
        assertEquals(0, p0.indentLevel)
        // Scheme color modified by lumMod
        assertNotNull(p0.runs[0].colorHex)

        val p1 = textShape.paragraphs[1]
        assertTrue(p1.isBullet)
        assertEquals(1, p1.indentLevel)

        // 3. Check Group 3: Transformed coordinates
        // group offX=5000000, offY=1000000, extW=4000000, extH=2000000
        // chOffX=0, chOffY=0, chExtW=2000000, chExtH=1000000 -> scale=2.0
        // child: offX=500000, offY=250000, ext cx=1000000, cy=500000
        // transformedX = 5000000 + 500000 * 2 = 6000000
        // transformedY = 1000000 + 250000 * 2 = 1500000
        // transformedW = 1000000 * 2 = 2000000
        // transformedH = 500000 * 2 = 1000000
        val groupChildShape = shapeBoxes[1]
        assertEquals(6000000L, groupChildShape.xEmu)
        assertEquals(1500000L, groupChildShape.yEmu)
        assertEquals(2000000L, groupChildShape.wEmu)
        assertEquals(1000000L, groupChildShape.hEmu)
        assertEquals("#00AA55", groupChildShape.fillColorHex)

        // 4. Test SlideView layout and bullet formatting
        val slideView = SlideView(context).apply {
            setSlide(slide, presentation.slideWidthEmu, presentation.slideHeightEmu)
            measure(
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, 1000, 500)
        }
        val child0 = slideView.getChildAt(0)
        assertEquals(90.0f, child0.rotation, 0.01f)
        val child1 = slideView.getChildAt(1) as TextView
        val textStr = child1.text.toString()
        assertTrue(textStr.contains("★  Level 0 bullet"))
        assertTrue(textStr.contains("    ◦  Level 1 nested"))
    }
}
