package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

// Sharp, technical aesthetic - corner radius <= 2-4 dp
val DragonShapes = Shapes(
  extraSmall = RoundedCornerShape(2.dp),
  small = RoundedCornerShape(2.dp),
  medium = RoundedCornerShape(4.dp),
  large = RoundedCornerShape(4.dp),
  extraLarge = RoundedCornerShape(6.dp)
)

private val DragonColorScheme = darkColorScheme(
  primary = DragonRed,
  onPrimary = TextOnDark,
  primaryContainer = DarkCrimson,
  onPrimaryContainer = TextOnDark,
  secondary = BrightDragonRed,
  onSecondary = TextOnDark,
  secondaryContainer = AshSurfaceVariant,
  onSecondaryContainer = TextOnDark,
  tertiary = CodeType,
  background = NearBlack,
  onBackground = TextOnDark,
  surface = AshSurface,
  onSurface = TextOnDark,
  surfaceVariant = AshSurfaceVariant,
  onSurfaceVariant = TextOnDarkSecondary,
  outline = AshOutline,
  error = DragonRed,
  onError = TextOnDark
)

@Composable
fun DragonsViewTheme(
  content: @Composable () -> Unit
) {
  MaterialTheme(
    colorScheme = DragonColorScheme,
    shapes = DragonShapes,
    typography = Typography,
    content = content
  )
}

@Composable
fun MyApplicationTheme(
  content: @Composable () -> Unit
) {
  DragonsViewTheme(content = content)
}
