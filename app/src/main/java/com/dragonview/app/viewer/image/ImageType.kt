// app/src/main/java/com/dragonview/app/viewer/image/ImageType.kt
package com.dragonview.app.viewer.image

/**
 * Supported image format classifications for DragonView's image viewer engine.
 */
enum class ImageType(
    val label: String,
    val badge: String,
    val extensions: List<String>,
    val badgeColorHex: Long = 0xFFFF7043
) {
    JPEG("JPEG Image", "JPEG", listOf("jpg", "jpeg"), 0xFFFF7043),
    PNG("Portable Network Graphics", "PNG", listOf("png"), 0xFF26A69A),
    GIF("Graphics Interchange Format", "GIF", listOf("gif"), 0xFFAB47BC),
    WEBP("WebP Image", "WEBP", listOf("webp"), 0xFF42A5F5),
    BMP("Bitmap Picture", "BMP", listOf("bmp"), 0xFF78909C),
    TIFF("Tagged Image File Format", "TIFF", listOf("tiff", "tif"), 0xFF8D6E63),
    HEIC("High Efficiency Image", "HEIC", listOf("heic", "heif"), 0xFF5C6BC0),
    SVG("Scalable Vector Graphic", "SVG", listOf("svg"), 0xFFFFA726);

    companion object {
        fun fromExtension(ext: String?): ImageType? {
            if (ext.isNullOrBlank()) return null
            val clean = ext.lowercase().trimStart('.')
            return entries.firstOrNull { it.extensions.contains(clean) }
        }

        fun isImageExtension(ext: String?): Boolean {
            return fromExtension(ext) != null
        }
    }
}
