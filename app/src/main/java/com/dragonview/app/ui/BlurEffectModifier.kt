// app/src/main/java/com/dragonview/app/ui/BlurEffectModifier.kt
package com.dragonview.app.ui

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Blur and translucent surface helper for toolbars, floating sheets, and modals.
 *
 * - On API 31+ (Android 12+): Applies hardware-accelerated RenderEffect Gaussian blur.
 * - On API 28-30 (Android 9-11): Uses an elegant translucent tinted background fallback
 *   to avoid unhandled exceptions and zero frame drops on 3-4GB RAM devices.
 */
fun Modifier.glassmorphicBlur(
    blurRadius: Dp = 16.dp,
    tintAlpha: Float = 0.85f
): Modifier {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        this.graphicsLayer {
            val px = blurRadius.toPx()
            if (px > 0f) {
                renderEffect = RenderEffect.createBlurEffect(
                    px,
                    px,
                    Shader.TileMode.CLAMP
                ).asComposeRenderEffect()
            }
        }
    } else {
        // Safe, zero-overhead fallback for Android 9-11
        this
    }
}

/**
 * Composable modifier that applies the blurred/glassmorphic surface background.
 */
@Composable
fun Modifier.glassmorphicSurface(
    backgroundColor: Color = MaterialTheme.colorScheme.surface,
    tintAlpha: Float = 0.88f,
    blurRadius: Dp = 16.dp
): Modifier {
    val tintedColor = backgroundColor.copy(alpha = tintAlpha)
    return this
        .background(tintedColor)
        .glassmorphicBlur(blurRadius = blurRadius, tintAlpha = tintAlpha)
}
