// app/src/test/java/com/example/ImageDetectionAndDecodeTest.kt
package com.example

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.dragonview.app.router.FileFormat
import com.dragonview.app.router.FormatRouter
import com.dragonview.app.viewer.image.ImageDecoderHelper
import com.dragonview.app.viewer.image.ImageType
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImageDetectionAndDecodeTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    // Minimal 1x1 PNG bytes
    private fun createMinimalPng(): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos)
        return baos.toByteArray()
    }

    // Minimal 1x1 JPEG bytes
    private fun createMinimalJpeg(): ByteArray {
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.RGB_565)
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, baos)
        return baos.toByteArray()
    }

    // Minimal 1x1 WEBP bytes (valid RIFF....WEBPVP8...)
    private fun createMinimalWebp(): ByteArray {
        return byteArrayOf(
            0x52, 0x49, 0x46, 0x46, // "RIFF"
            0x1A, 0x00, 0x00, 0x00, // size: 26 bytes
            0x57, 0x45, 0x42, 0x50, // "WEBP"
            0x56, 0x50, 0x38, 0x20, // "VP8 "
            0x0E, 0x00, 0x00, 0x00, // chunk size: 14 bytes
            0x30, 0x01, 0x00, // keyframe header
            0x9D.toByte(), 0x01, 0x2A, // start code 9d 01 2a
            0x01, 0x00, 0x01, 0x00, // 1x1 dimensions
            0x02, 0x00, 0x34, 0x25 // frame data
        )
    }

    // Minimal TIFF (2x2 RGB uncompressed little-endian, 122 bytes)
    private fun createMinimalTiff(): ByteArray {
        val out = ByteArrayOutputStream()
        // Header
        out.write(byteArrayOf(0x49, 0x49, 0x2A, 0x00)) // "II", 42
        out.write(byteArrayOf(0x08, 0x00, 0x00, 0x00)) // IFD offset: 8

        // IFD: 8 entries
        out.write(byteArrayOf(0x08, 0x00)) // Count: 8 entries
        // 0x0100: Width = 2
        out.write(byteArrayOf(0x00, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00))
        // 0x0101: Height = 2
        out.write(byteArrayOf(0x01, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00))
        // 0x0102: BitsPerSample = 8
        out.write(byteArrayOf(0x02, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x08, 0x00, 0x00, 0x00))
        // 0x0103: Compression = 1 (none)
        out.write(byteArrayOf(0x03, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00))
        // 0x0106: Photometric = 2 (RGB)
        out.write(byteArrayOf(0x06, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00))
        // 0x0111: StripOffsets = 110 (0x6E)
        out.write(byteArrayOf(0x11, 0x01, 0x04, 0x00, 0x01, 0x00, 0x00, 0x00, 0x6E, 0x00, 0x00, 0x00))
        // 0x0115: SamplesPerPixel = 3
        out.write(byteArrayOf(0x15, 0x01, 0x03, 0x00, 0x01, 0x00, 0x00, 0x00, 0x03, 0x00, 0x00, 0x00))
        // 0x0117: StripByteCounts = 12
        out.write(byteArrayOf(0x17, 0x01, 0x04, 0x00, 0x01, 0x00, 0x00, 0x00, 0x0C, 0x00, 0x00, 0x00))
        // Next IFD offset: 0
        out.write(byteArrayOf(0x00, 0x00, 0x00, 0x00))

        // Pixel data at offset 110: 2x2 RGB = 12 bytes
        out.write(byteArrayOf(
            0xFF.toByte(), 0x00, 0x00, // Pixel 0: Red
            0x00, 0xFF.toByte(), 0x00, // Pixel 1: Green
            0x00, 0x00, 0xFF.toByte(), // Pixel 2: Blue
            0xFF.toByte(), 0xFF.toByte(), 0x00  // Pixel 3: Yellow
        ))
        return out.toByteArray()
    }

    // Minimal HEIC (ftypheic)
    private fun createMinimalHeic(): ByteArray {
        return byteArrayOf(
            0x00, 0x00, 0x00, 0x18, // box size: 24
            0x66, 0x74, 0x79, 0x70, // "ftyp"
            0x68, 0x65, 0x69, 0x63, // "heic" major brand
            0x00, 0x00, 0x00, 0x00, // minor version
            0x6D, 0x69, 0x66, 0x31, // "mif1"
            0x68, 0x65, 0x69, 0x63  // "heic"
        )
    }

    // Minimal 1x1 BMP bytes
    private fun createMinimalBmp(): ByteArray {
        val width = 2
        val height = 2
        val rowSize = (width * 3 + 3) and 3.inv()
        val imageSize = rowSize * height
        val fileSize = 54 + imageSize

        val header = ByteArray(54)
        // Signature "BM"
        header[0] = 0x42
        header[1] = 0x4D
        // File size
        header[2] = (fileSize and 0xFF).toByte()
        header[3] = ((fileSize shr 8) and 0xFF).toByte()
        header[4] = ((fileSize shr 16) and 0xFF).toByte()
        header[5] = ((fileSize shr 24) and 0xFF).toByte()
        // Data offset (54)
        header[10] = 54
        // DIB Header size (40)
        header[14] = 40
        // Width
        header[18] = (width and 0xFF).toByte()
        header[19] = ((width shr 8) and 0xFF).toByte()
        // Height
        header[22] = (height and 0xFF).toByte()
        header[23] = ((height shr 8) and 0xFF).toByte()
        // Planes
        header[26] = 1
        // Bits per pixel (24)
        header[28] = 24
        // Image size
        header[34] = (imageSize and 0xFF).toByte()
        header[35] = ((imageSize shr 8) and 0xFF).toByte()

        val pixelData = ByteArray(imageSize)
        return header + pixelData
    }

    // Minimal 1x1 GIF bytes
    private fun createMinimalGif(): ByteArray {
        return byteArrayOf(
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61, // GIF89a
            0x01, 0x00, 0x01, 0x00, // 1x1
            0x80.toByte(), 0x00, 0x00, // Global color table
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), // White
            0x00, 0x00, 0x00, // Black
            0x2C, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00, // Image Descriptor
            0x02, 0x02, 0x44, 0x01, 0x00, // Image Data
            0x3B // Trailer
        )
    }

    // Minimal SVG
    private fun createMinimalSvg(): ByteArray {
        return """<svg xmlns="http://www.w3.org/2000/svg" width="50" height="50"><circle cx="25" cy="25" r="20" fill="blue"/></svg>""".toByteArray(Charsets.UTF_8)
    }

    @Test
    fun testAllImagesDetection() = runBlocking {
        val testFiles = listOf(
            Triple("test.jpg", createMinimalJpeg(), ImageType.JPEG),
            Triple("test.jpeg", createMinimalJpeg(), ImageType.JPEG),
            Triple("test.png", createMinimalPng(), ImageType.PNG),
            Triple("test.gif", createMinimalGif(), ImageType.GIF),
            Triple("test.webp", createMinimalWebp(), ImageType.WEBP),
            Triple("test.bmp", createMinimalBmp(), ImageType.BMP),
            Triple("test.tiff", createMinimalTiff(), ImageType.TIFF),
            Triple("test.heic", createMinimalHeic(), ImageType.HEIC),
            Triple("test.svg", createMinimalSvg(), ImageType.SVG)
        )

        for ((fileName, bytes, expectedType) in testFiles) {
            val file = File(context.cacheDir, fileName).apply {
                FileOutputStream(this).use { it.write(bytes) }
            }
            val uri = Uri.fromFile(file)
            val detection = FormatRouter.detectFormat(context, uri)

            println("Testing $fileName: format=${detection.format}, isCorrupted=${detection.isCorrupted}, imageType=${detection.imageType}, status=${detection.statusMessage}")

            assertEquals("Format for $fileName should be IMAGE", FileFormat.IMAGE, detection.format)
            assertEquals("ImageType for $fileName", expectedType, detection.imageType)

            val decodeResult = ImageDecoderHelper.decode(context, uri, detection.imageType ?: expectedType)
            println("Decode $fileName: isSuccess=${decodeResult.isSuccess}, error=${decodeResult.exceptionOrNull()?.message}")
            if (expectedType != ImageType.HEIC) {
                assertTrue("Decode should succeed for $fileName", decodeResult.isSuccess)
            } else {
                // On Android devices with HEIC support, decode succeeds. In Robolectric JVM without HEVC native libs,
                // verify that error handling is graceful and logged without crashing.
                println("HEIC decode in Robolectric: isSuccess=${decodeResult.isSuccess}, ex=${decodeResult.exceptionOrNull()}")
            }
        }
    }

    @Test
    fun testMimeTypeNullAndFallback() = runBlocking {
        // File with no extension and null MIME, but valid JPEG bytes
        val file = File(context.cacheDir, "sample_image_no_ext").apply {
            FileOutputStream(this).use { it.write(createMinimalJpeg()) }
        }
        val uri = Uri.fromFile(file)
        val detection = FormatRouter.detectFormat(context, uri)
        assertEquals(FileFormat.IMAGE, detection.format)
        assertEquals(ImageType.JPEG, detection.imageType)

        val decodeResult = ImageDecoderHelper.decode(context, uri, detection.imageType!!)
        assertTrue(decodeResult.isSuccess)
    }
}
