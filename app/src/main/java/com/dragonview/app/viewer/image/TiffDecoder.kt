// app/src/main/java/com/dragonview/app/viewer/image/TiffDecoder.kt
package com.dragonview.app.viewer.image

import android.graphics.Bitmap
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Lightweight pure-Kotlin TIFF decoder for baseline uncompressed and PackBits-compressed
 * TIFF images (RGB, RGBA, Grayscale, Palette).
 */
object TiffDecoder {

    private const val TAG = "TiffDecoder"

    fun decode(bytes: ByteArray, maxDimensionPx: Int = 2048): Bitmap {
        if (bytes.size < 8) {
            throw IllegalArgumentException("TIFF data too short (${bytes.size} bytes)")
        }

        val isLittleEndian = bytes[0] == 0x49.toByte() && bytes[1] == 0x49.toByte()
        val isBigEndian = bytes[0] == 0x4D.toByte() && bytes[1] == 0x4D.toByte()

        if (!isLittleEndian && !isBigEndian) {
            throw IllegalArgumentException("Not a valid TIFF: invalid byte order indicator (${bytes[0]}, ${bytes[1]})")
        }

        val order = if (isLittleEndian) ByteOrder.LITTLE_ENDIAN else ByteOrder.BIG_ENDIAN
        val buffer = ByteBuffer.wrap(bytes).order(order)

        val magic = buffer.getShort(2).toInt() and 0xFFFF
        if (magic != 42) {
            throw IllegalArgumentException("Not a valid TIFF: expected magic 42, got $magic")
        }

        var ifdOffset = buffer.getInt(4).toLong() and 0xFFFFFFFFL
        if (ifdOffset <= 0 || ifdOffset >= bytes.size) {
            throw IllegalArgumentException("Invalid IFD offset: $ifdOffset")
        }

        var width = 0
        var height = 0
        var bitsPerSample = 8
        var compression = 1 // 1 = uncompressed, 32773 = PackBits
        var photometric = 1 // 0 = WhiteIsZero, 1 = BlackIsZero, 2 = RGB, 3 = Palette
        var samplesPerPixel = 1
        var rowsPerStrip = 0
        var stripOffsets = longArrayOf()
        var stripByteCounts = longArrayOf()
        var colorMap = intArrayOf()

        val numEntries = buffer.getShort(ifdOffset.toInt()).toInt() and 0xFFFF
        var entryOffset = ifdOffset + 2

        for (i in 0 until numEntries) {
            if (entryOffset + 12 > bytes.size) break
            val tag = buffer.getShort(entryOffset.toInt()).toInt() and 0xFFFF
            val type = buffer.getShort((entryOffset + 2).toInt()).toInt() and 0xFFFF
            val count = buffer.getInt((entryOffset + 4).toInt()).toLong() and 0xFFFFFFFFL
            val valueOffset = entryOffset + 8

            fun readValue(idx: Int = 0): Long {
                val elemSize = when (type) {
                    1, 2, 6, 7 -> 1
                    3, 8 -> 2
                    4, 9 -> 4
                    else -> 4
                }
                val totalBytes = count * elemSize
                val offset = if (totalBytes <= 4) valueOffset.toInt() + idx * elemSize
                else (buffer.getInt(valueOffset.toInt()).toLong() and 0xFFFFFFFFL).toInt() + idx * elemSize

                if (offset < 0 || offset + elemSize > bytes.size) return 0L

                return when (type) {
                    1 -> bytes[offset].toLong() and 0xFFL
                    3 -> buffer.getShort(offset).toLong() and 0xFFFFL
                    4 -> buffer.getInt(offset).toLong() and 0xFFFFFFFFL
                    else -> buffer.getInt(offset).toLong() and 0xFFFFFFFFL
                }
            }

            fun readValueArray(): LongArray {
                val arr = LongArray(count.toInt().coerceAtMost(65536))
                for (j in arr.indices) {
                    arr[j] = readValue(j)
                }
                return arr
            }

            when (tag) {
                0x0100 -> width = readValue().toInt()
                0x0101 -> height = readValue().toInt()
                0x0102 -> bitsPerSample = readValue().toInt()
                0x0103 -> compression = readValue().toInt()
                0x0106 -> photometric = readValue().toInt()
                0x0111 -> stripOffsets = readValueArray()
                0x0115 -> samplesPerPixel = readValue().toInt()
                0x0116 -> rowsPerStrip = readValue().toInt()
                0x0117 -> stripByteCounts = readValueArray()
                0x0140 -> {
                    // ColorMap: array of count SHORTs (red, green, blue)
                    val arr = readValueArray()
                    colorMap = arr.map { it.toInt() and 0xFFFF }.toIntArray()
                }
            }

            entryOffset += 12
        }

        if (width <= 0 || height <= 0) {
            throw IllegalArgumentException("Invalid TIFF dimensions: $width x $height")
        }

        if (rowsPerStrip <= 0) {
            rowsPerStrip = height
        }

        if (stripOffsets.isEmpty()) {
            throw IllegalArgumentException("TIFF has no strip offsets")
        }

        // Decompress and decode pixels
        val pixels = IntArray(width * height)
        var currentRow = 0

        for (stripIdx in stripOffsets.indices) {
            val offset = stripOffsets[stripIdx].toInt()
            val byteCount = if (stripIdx < stripByteCounts.size) stripByteCounts[stripIdx].toInt()
            else bytes.size - offset

            if (offset < 0 || offset >= bytes.size) continue
            val safeCount = byteCount.coerceAtMost(bytes.size - offset)
            val stripRaw = bytes.copyOfRange(offset, offset + safeCount)

            val decompressedBytes = if (compression == 32773) {
                decompressPackBits(stripRaw)
            } else {
                stripRaw
            }

            val rowsInThisStrip = rowsPerStrip.coerceAtMost(height - currentRow)
            var srcByteIdx = 0

            for (r in 0 until rowsInThisStrip) {
                val row = currentRow + r
                if (row >= height) break
                val rowStartPixel = row * width

                for (col in 0 until width) {
                    val pixelIdx = rowStartPixel + col
                    if (pixelIdx >= pixels.size) break

                    when (samplesPerPixel) {
                        3 -> { // RGB
                            if (srcByteIdx + 2 < decompressedBytes.size) {
                                val red = decompressedBytes[srcByteIdx++].toInt() and 0xFF
                                val green = decompressedBytes[srcByteIdx++].toInt() and 0xFF
                                val blue = decompressedBytes[srcByteIdx++].toInt() and 0xFF
                                pixels[pixelIdx] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
                            }
                        }
                        4 -> { // RGBA
                            if (srcByteIdx + 3 < decompressedBytes.size) {
                                val red = decompressedBytes[srcByteIdx++].toInt() and 0xFF
                                val green = decompressedBytes[srcByteIdx++].toInt() and 0xFF
                                val blue = decompressedBytes[srcByteIdx++].toInt() and 0xFF
                                val alpha = decompressedBytes[srcByteIdx++].toInt() and 0xFF
                                pixels[pixelIdx] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
                            }
                        }
                        1 -> { // Grayscale or Palette
                            if (srcByteIdx < decompressedBytes.size) {
                                val rawVal = decompressedBytes[srcByteIdx++].toInt() and 0xFF
                                if (photometric == 3 && colorMap.isNotEmpty()) {
                                    val numColors = colorMap.size / 3
                                    if (rawVal < numColors) {
                                        val red = (colorMap[rawVal] shr 8) and 0xFF
                                        val green = (colorMap[rawVal + numColors] shr 8) and 0xFF
                                        val blue = (colorMap[rawVal + 2 * numColors] shr 8) and 0xFF
                                        pixels[pixelIdx] = (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
                                    } else {
                                        pixels[pixelIdx] = 0xFF000000.toInt()
                                    }
                                } else if (photometric == 0) { // WhiteIsZero
                                    val gray = 255 - rawVal
                                    pixels[pixelIdx] = (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
                                } else { // BlackIsZero (1) or default
                                    val gray = rawVal
                                    pixels[pixelIdx] = (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
                                }
                            }
                        }
                        else -> {
                            // Fallback for multi-channel
                            if (srcByteIdx < decompressedBytes.size) {
                                val v = decompressedBytes[srcByteIdx++].toInt() and 0xFF
                                pixels[pixelIdx] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
                            }
                        }
                    }
                }
            }
            currentRow += rowsInThisStrip
            if (currentRow >= height) break
        }

        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun decompressPackBits(src: ByteArray): ByteArray {
        val out = mutableListOf<Byte>()
        var i = 0
        while (i < src.size) {
            val n = src[i++].toInt()
            if (n in 0..127) {
                val count = n + 1
                for (k in 0 until count) {
                    if (i < src.size) out.add(src[i++])
                }
            } else if (n in -127..-1) {
                val count = 1 - n
                if (i < src.size) {
                    val b = src[i++]
                    for (k in 0 until count) {
                        out.add(b)
                    }
                }
            }
            // n == -128 is a no-op
        }
        return out.toByteArray()
    }
}
