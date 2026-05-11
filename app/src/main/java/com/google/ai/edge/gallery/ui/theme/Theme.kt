package com.google.ai.edge.gallery.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val PhoneLlamaDarkColors = darkColorScheme(
  primary = NeonPurple,
  onPrimary = Color.White,
  primaryContainer = Color(0xFF2D0D5A),
  onPrimaryContainer = NeonPurpleLight,
  secondary = NeonCyan,
  onSecondary = DeepNavy,
  secondaryContainer = Color(0xFF003344),
  onSecondaryContainer = NeonCyan,
  tertiary = NeonCyanDim,
  onTertiary = DeepNavy,
  background = DeepNavy,
  onBackground = OnDark,
  surface = DarkSurface,
  onSurface = OnDark,
  surfaceVariant = DarkCard,
  onSurfaceVariant = OnDarkSecondary,
  outline = DarkBorder,
  error = ErrorNeon,
  onError = Color.White,
  errorContainer = Color(0xFF4A0020),
  onErrorContainer = ErrorNeon,
)

private val PhoneLlamaLightColors = lightColorScheme(
  primary = NeonPurple,
  onPrimary = Color.White,
  primaryContainer = Color(0xFFE8D5FF),
  onPrimaryContainer = Color(0xFF2D0D5A),
  secondary = NeonCyanDim,
  onSecondary = Color.White,
  background = Color(0xFFF5F5FF),
  onBackground = Color(0xFF1A1A2E),
  surface = Color.White,
  onSurface = Color(0xFF1A1A2E),
  surfaceVariant = Color(0xFFEEEEFF),
  onSurfaceVariant = Color(0xFF44445A),
)

@Immutable
data class CustomColors(
  val appTitleGradientColors: List<Color> = listOf(),
  val tabHeaderBgColor: Color = Color.Transparent,
  val taskCardBgColor: Color = Color.Transparent,
  val taskBgColors: List<Color> = listOf(),
  val taskBgGradientColors: List<List<Color>> = listOf(),
  val taskIconColors: List<Color> = listOf(),
  val taskIconShapeBgColor: Color = Color.Transparent,
  val homeBottomGradient: List<Color> = listOf(),
  val userBubbleBgColor: Color = Color.Transparent,
  val agentBubbleBgColor: Color = Color.Transparent,
  val linkColor: Color = Color.Transparent,
  val successColor: Color = Color.Transparent,
  val recordButtonBgColor: Color = Color.Transparent,
  val waveFormBgColor: Color = Color.Transparent,
  val modelInfoIconColor: Color = Color.Transparent,
  val warningContainerColor: Color = Color.Transparent,
  val warningTextColor: Color = Color.Transparent,
  val errorContainerColor: Color = Color.Transparent,
  val errorTextColor: Color = Color.Transparent,
  val newFeatureContainerColor: Color = Color.Transparent,
  val newFeatureTextColor: Color = Color.Transparent,
  val bgStarColor: Color = Color.Transparent,
  val promoBannerBgBrush: Brush = Brush.verticalGradient(listOf(Color.Transparent)),
  val promoBannerIconBgBrush: Brush = Brush.verticalGradient(listOf(Color.Transparent)),
)

val LocalCustomColors = staticCompositionLocalOf { CustomColors() }

private val PhoneLlamaDarkCustomColors =
  CustomColors(
    appTitleGradientColors = listOf(NeonPurpleLight, NeonCyan),
    tabHeaderBgColor = DarkCard,
    taskCardBgColor = DarkCard,
    taskBgColors = listOf(Color(0xFF1C1123), Color(0xFF111F26), Color(0xFF16162C), Color(0xFF22181A)),
    taskBgGradientColors = listOf(
      listOf(NeonPurple, NeonPurpleLight),
      listOf(NeonCyanDim, NeonCyan),
      listOf(Color(0xFF4450FF), NeonCyan),
      listOf(Color(0xFFFF7A59), NeonPurple),
    ),
    taskIconColors = listOf(NeonPurpleLight, NeonCyan, Color(0xFF8BA2FF), Color(0xFFFF9A7A)),
    taskIconShapeBgColor = DarkSurface,
    homeBottomGradient = listOf(Color.Transparent, DarkCard),
    userBubbleBgColor = Color(0xFF2D0D5A),
    agentBubbleBgColor = DarkCard,
    linkColor = NeonCyan,
    successColor = SuccessNeon,
    recordButtonBgColor = ErrorNeon,
    waveFormBgColor = OnDarkSecondary,
    modelInfoIconColor = OnDarkSecondary,
    warningContainerColor = Color(0xFF4A3A00),
    warningTextColor = Color(0xFFFFD166),
    errorContainerColor = Color(0xFF4A0020),
    errorTextColor = ErrorNeon,
    newFeatureContainerColor = Color(0xFF2D0D5A),
    newFeatureTextColor = NeonPurpleLight,
    bgStarColor = NeonPurple.copy(alpha = 0.22f),
    promoBannerBgBrush = Brush.linearGradient(
      colorStops = arrayOf(
        0.0f to NeonPurple.copy(alpha = 0.55f),
        1.0f to DeepNavy.copy(alpha = 0.88f),
      ),
      start = Offset(0f, 0f),
      end = Offset(0f, Float.POSITIVE_INFINITY),
    ),
    promoBannerIconBgBrush = Brush.linearGradient(
      colorStops = arrayOf(
        0.0f to NeonPurple.copy(alpha = 0.5f),
        1.0f to NeonCyan.copy(alpha = 0.5f),
      ),
      start = Offset(0f, 1f),
      end = Offset(1f, 0f),
    ),
  )

private val PhoneLlamaLightCustomColors =
  CustomColors(
    appTitleGradientColors = listOf(NeonPurple, NeonCyanDim),
    tabHeaderBgColor = Color(0xFFE8D5FF),
    taskCardBgColor = Color.White,
    taskBgColors = listOf(Color(0xFFF7F0FF), Color(0xFFEFFBFF), Color(0xFFF3F3FF), Color(0xFFFFF4F6)),
    taskBgGradientColors = listOf(
      listOf(NeonPurpleLight, NeonPurple),
      listOf(NeonCyan, NeonCyanDim),
      listOf(Color(0xFF7B9CFF), NeonCyanDim),
      listOf(Color(0xFFFF9A7A), NeonPurpleLight),
    ),
    taskIconColors = listOf(NeonPurple, NeonCyanDim, Color(0xFF5A67D8), Color(0xFFD65A8A)),
    taskIconShapeBgColor = Color.White,
    homeBottomGradient = listOf(Color.Transparent, Color(0xFFE8D5FF)),
    userBubbleBgColor = NeonPurple,
    agentBubbleBgColor = Color(0xFFEEEEFF),
    linkColor = NeonCyanDim,
    successColor = SuccessNeon,
    recordButtonBgColor = ErrorNeon,
    waveFormBgColor = Color(0xFF7A7A96),
    modelInfoIconColor = Color(0xFF9A9AB4),
    warningContainerColor = Color(0xFFFFF4CC),
    warningTextColor = Color(0xFF8A5A00),
    errorContainerColor = Color(0xFFFFE0EB),
    errorTextColor = ErrorNeon,
    newFeatureContainerColor = Color(0xFFE8D5FF),
    newFeatureTextColor = Color(0xFF2D0D5A),
    bgStarColor = NeonPurple.copy(alpha = 0.14f),
    promoBannerBgBrush = Brush.linearGradient(
      colorStops = arrayOf(
        0.0f to NeonPurple.copy(alpha = 0.25f),
        1.0f to NeonCyan.copy(alpha = 0.18f),
      ),
      start = Offset(0f, 0f),
      end = Offset(0f, Float.POSITIVE_INFINITY),
    ),
    promoBannerIconBgBrush = Brush.linearGradient(
      colorStops = arrayOf(
        0.0f to NeonPurple.copy(alpha = 0.22f),
        1.0f to NeonCyan.copy(alpha = 0.22f),
      ),
      start = Offset(0f, 1f),
      end = Offset(1f, 0f),
    ),
  )

val MaterialTheme.customColors: CustomColors
  @Composable @ReadOnlyComposable get() = LocalCustomColors.current

@Composable
fun GalleryTheme(
  darkTheme: Boolean = true,
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme = if (darkTheme) PhoneLlamaDarkColors else PhoneLlamaLightColors
  val customColorsPalette = if (darkTheme) PhoneLlamaDarkCustomColors else PhoneLlamaLightCustomColors

  val view = LocalView.current
  if (!view.isInEditMode) {
    SideEffect {
      val window = (view.context as Activity).window
      window.statusBarColor = colorScheme.background.toArgb()
      WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
    }
  }

  CompositionLocalProvider(LocalCustomColors provides customColorsPalette) {
    MaterialTheme(
      colorScheme = colorScheme,
      typography = AppTypography,
      content = content,
    )
  }
}
