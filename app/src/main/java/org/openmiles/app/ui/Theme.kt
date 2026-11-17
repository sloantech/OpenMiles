package org.openmiles.app.ui

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.openmiles.app.R

// ---------------------------------------------------------------------------
// "Highway" look: deep navy and signal amber, like road signs.
// ---------------------------------------------------------------------------

object Highway {
    val Navy = Color(0xFF0F2A44)
    val NavyDark = Color(0xFF0B1A2B)
    val Amber = Color(0xFFF2A900)
    val OnAmber = Color(0xFF0F2A44)
    val HeaderSubtext = Color(0xFFB9C7D6)
}

/** Colors the standard Material set does not cover. */
@Immutable
data class ExtraColors(
    val header: Color,
    val onHeader: Color,
    val headerSubtext: Color,
    val business: Color,
    val businessBg: Color,
    val personal: Color,
    val personalBg: Color,
    val other: Color,
    val otherBg: Color,
    val todo: Color,
    val todoBg: Color,
    val swipeBusiness: Color,
    val swipePersonal: Color
)

private val LightExtra = ExtraColors(
    header = Highway.Navy,
    onHeader = Color.White,
    headerSubtext = Highway.HeaderSubtext,
    business = Color(0xFF1D5FAF),
    businessBg = Color(0xFFE3EEFA),
    personal = Color(0xFF6B5B00),
    personalBg = Color(0xFFFFF3C4),
    other = Color(0xFF4A3F8C),
    otherBg = Color(0xFFEAE7F7),
    todo = Color(0xFFA04800),
    todoBg = Color(0xFFFDEBD9),
    swipeBusiness = Color(0xFF1D5FAF),
    swipePersonal = Color(0xFF7A5F00)
)

private val DarkExtra = ExtraColors(
    header = Highway.NavyDark,
    onHeader = Color.White,
    headerSubtext = Highway.HeaderSubtext,
    business = Color(0xFF9CC7F5),
    businessBg = Color(0xFF16314F),
    personal = Color(0xFFF2D27A),
    personalBg = Color(0xFF3A3110),
    other = Color(0xFFC9C0F5),
    otherBg = Color(0xFF2B2650),
    todo = Color(0xFFFFB98A),
    todoBg = Color(0xFF3D2614),
    swipeBusiness = Color(0xFF1D5FAF),
    swipePersonal = Color(0xFF7A5F00)
)

private val LightScheme: ColorScheme = lightColorScheme(
    primary = Highway.Navy,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3EEFA),
    onPrimaryContainer = Highway.Navy,
    secondary = Highway.Amber,
    onSecondary = Highway.OnAmber,
    secondaryContainer = Color(0xFFFFF0C2),
    onSecondaryContainer = Color(0xFF3D2E00),
    background = Color(0xFFF3F5F8),
    onBackground = Color(0xFF0F1B2A),
    surface = Color.White,
    onSurface = Color(0xFF0F1B2A),
    surfaceVariant = Color(0xFFEDF1F5),
    onSurfaceVariant = Color(0xFF526173),
    surfaceContainer = Color.White,
    surfaceContainerHigh = Color.White,
    surfaceContainerHighest = Color(0xFFEDF1F5),
    outline = Color(0xFFC5CED8),
    outlineVariant = Color(0xFFE1E6EC),
    error = Color(0xFFB3261E)
)

private val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFF9CC7F5),
    onPrimary = Color(0xFF0B1A2B),
    primaryContainer = Color(0xFF16314F),
    onPrimaryContainer = Color(0xFFD6E6F8),
    secondary = Highway.Amber,
    onSecondary = Highway.OnAmber,
    secondaryContainer = Color(0xFF3A2E05),
    onSecondaryContainer = Color(0xFFFFE08A),
    background = Color(0xFF0A1420),
    onBackground = Color(0xFFE8EEF5),
    surface = Color(0xFF13202F),
    onSurface = Color(0xFFE8EEF5),
    surfaceVariant = Color(0xFF1B2A3B),
    onSurfaceVariant = Color(0xFFA6B4C3),
    surfaceContainer = Color(0xFF13202F),
    surfaceContainerHigh = Color(0xFF182636),
    surfaceContainerHighest = Color(0xFF1B2A3B),
    outline = Color(0xFF3A4B5E),
    outlineVariant = Color(0xFF243447),
    error = Color(0xFFFFB4AB)
)

val LocalExtraColors = staticCompositionLocalOf { LightExtra }

// ---------------------------------------------------------------------------
// Fonts: Inter for text, Space Grotesk for numbers. Bundled, so nothing is downloaded.
// ---------------------------------------------------------------------------

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) = Font(
    res,
    FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight))
)

val InterFamily = FontFamily(
    variable(R.font.inter, 400),
    variable(R.font.inter, 500),
    variable(R.font.inter, 600),
    variable(R.font.inter, 700)
)

val NumberFamily = FontFamily(
    variable(R.font.space_grotesk, 500),
    variable(R.font.space_grotesk, 700)
)

/** Big numbers, like miles on a drive card. */
val BigNumber = TextStyle(fontFamily = NumberFamily, fontWeight = FontWeight.Bold, fontSize = 24.sp, letterSpacing = (-0.5).sp)

private val base = Typography()

private val HighwayType = Typography(
    displayLarge = base.displayLarge.copy(fontFamily = NumberFamily, fontWeight = FontWeight.Bold),
    displayMedium = base.displayMedium.copy(fontFamily = NumberFamily, fontWeight = FontWeight.Bold),
    displaySmall = base.displaySmall.copy(fontFamily = NumberFamily, fontWeight = FontWeight.Bold),
    headlineLarge = base.headlineLarge.copy(fontFamily = InterFamily, fontWeight = FontWeight.Bold),
    headlineMedium = base.headlineMedium.copy(fontFamily = InterFamily, fontWeight = FontWeight.Bold),
    headlineSmall = base.headlineSmall.copy(fontFamily = InterFamily, fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontFamily = InterFamily, fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.copy(fontFamily = InterFamily),
    bodyMedium = base.bodyMedium.copy(fontFamily = InterFamily),
    bodySmall = base.bodySmall.copy(fontFamily = InterFamily),
    labelLarge = base.labelLarge.copy(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold),
    labelMedium = base.labelMedium.copy(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold),
    labelSmall = base.labelSmall.copy(fontFamily = InterFamily, fontWeight = FontWeight.SemiBold)
)

// ---------------------------------------------------------------------------
// Light or dark: follows the phone unless the person picks one in Settings.
// ---------------------------------------------------------------------------

object Appearance {
    const val SYSTEM = "system"
    const val LIGHT = "light"
    const val DARK = "dark"
    private const val PREFS = "appearance"
    private const val KEY = "mode"

    private val _mode = MutableStateFlow(SYSTEM)
    val mode: StateFlow<String> = _mode

    fun load(context: Context) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, SYSTEM)
        _mode.value = if (saved == LIGHT || saved == DARK) saved else SYSTEM
    }

    fun set(context: Context, mode: String) {
        val clean = if (mode == LIGHT || mode == DARK) mode else SYSTEM
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, clean).apply()
        _mode.value = clean
    }
}

@Composable
fun OpenMilesTheme(mode: String, content: @Composable () -> Unit) {
    val dark = when (mode) {
        Appearance.LIGHT -> false
        Appearance.DARK -> true
        else -> isSystemInDarkTheme()
    }
    CompositionLocalProvider(LocalExtraColors provides if (dark) DarkExtra else LightExtra) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = HighwayType,
            content = content
        )
    }
}

// ---------------------------------------------------------------------------
// Line icons drawn in code (24 x 24, 2 px strokes), tinted by whatever shows them.
// ---------------------------------------------------------------------------

private fun lineIcon(name: String, vararg paths: String): ImageVector {
    val builder = ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    )
    paths.forEach {
        builder.addPath(
            pathData = addPathNodes(it),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round
        )
    }
    return builder.build()
}

object HighwayIcons {
    val Road = lineIcon("road", "M8 3L4 21", "M16 3l4 18", "M12 4v3", "M12 11v3", "M12 18v2")
    val Car = lineIcon(
        "car",
        "M5 17h14", "M6 17v2", "M18 17v2", "M4 13l2-6h12l2 6v4H4z",
        "M7.5 14h1", "M15.5 14h1"
    )
    val Chart = lineIcon("chart", "M4 20V10", "M10 20V4", "M16 20v-7", "M22 20H2")
    val Gear = lineIcon(
        "gear",
        "M12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6z",
        "M12 2v3", "M12 19v3", "M2 12h3", "M19 12h3",
        "M4.9 4.9l2.1 2.1", "M17 17l2.1 2.1", "M4.9 19.1L7 17", "M17 7l2.1-2.1"
    )
    val Plus = lineIcon("plus", "M12 5v14", "M5 12h14")
    val Play = lineIcon("play", "M8 5v14l11-7z")
    val Stop = lineIcon("stop", "M7 7h10v10H7z")
}
