package com.indirici.ui

import android.content.Context
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Yalnızca arayüze ait tercihler (tema vb.). İndirme ayarları ortak `Settings` içinde. */
class UiPrefs(context: Context) {
    private val p = context.getSharedPreferences("indirici_ui", Context.MODE_PRIVATE)
    private val _v = MutableStateFlow(0)
    val version: StateFlow<Int> = _v
    private fun changed() { _v.value++ }

    /** "system" | "light" | "dark" */
    var theme: String
        get() = p.getString("theme", "system") ?: "system"
        set(v) { p.edit().putString("theme", v).apply(); changed() }
    var amoled: Boolean
        get() = p.getBoolean("amoled", false)
        set(v) { p.edit().putBoolean("amoled", v).apply(); changed() }
    var dynamicColor: Boolean
        get() = p.getBoolean("dynamic", false)
        set(v) { p.edit().putBoolean("dynamic", v).apply(); changed() }
    var onboarded: Boolean
        get() = p.getBoolean("onboarded", false)
        set(v) { p.edit().putBoolean("onboarded", v).apply(); changed() }
    var filesGrid: Boolean
        get() = p.getBoolean("filesGrid", false)
        set(v) { p.edit().putBoolean("filesGrid", v).apply(); changed() }
}

// Marka paleti (tasarım tokenları): çekirdek çivit #4355B9, vurgu turkuaz #006A60
val LightColors = lightColorScheme(
    primary = Color(0xFF4355B9), onPrimary = Color.White, primaryContainer = Color(0xFFDEE0FF), onPrimaryContainer = Color(0xFF00105C),
    secondary = Color(0xFF5B5D72), onSecondary = Color.White, secondaryContainer = Color(0xFFE0E1F9), onSecondaryContainer = Color(0xFF181A2C),
    tertiary = Color(0xFF006A60), onTertiary = Color.White, tertiaryContainer = Color(0xFF9EF2E4), onTertiaryContainer = Color(0xFF00201C),
    error = Color(0xFFBA1A1A), onError = Color.White, errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFBF8FF), onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFFBF8FF), onSurface = Color(0xFF1B1B21), surfaceVariant = Color(0xFFE3E1EC), onSurfaceVariant = Color(0xFF46464F),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF5F2FA), surfaceContainer = Color(0xFFEFEDF4),
    surfaceContainerHigh = Color(0xFFE9E7EF), surfaceContainerHighest = Color(0xFFE3E1E9),
    outline = Color(0xFF767680), outlineVariant = Color(0xFFC6C5D0),
    inverseSurface = Color(0xFF303036), inverseOnSurface = Color(0xFFF2EFF7), inversePrimary = Color(0xFFBAC3FF),
)

val DarkColors = darkColorScheme(
    primary = Color(0xFFBAC3FF), onPrimary = Color(0xFF08218A), primaryContainer = Color(0xFF293CA0), onPrimaryContainer = Color(0xFFDEE0FF),
    secondary = Color(0xFFC4C5DD), onSecondary = Color(0xFF2D2F42), secondaryContainer = Color(0xFF434659), onSecondaryContainer = Color(0xFFE0E1F9),
    tertiary = Color(0xFF82D5C8), onTertiary = Color(0xFF003731), tertiaryContainer = Color(0xFF005048), onTertiaryContainer = Color(0xFF9EF2E4),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005), errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF121318), onBackground = Color(0xFFE4E1E9),
    surface = Color(0xFF121318), onSurface = Color(0xFFE4E1E9), surfaceVariant = Color(0xFF46464F), onSurfaceVariant = Color(0xFFC6C5D0),
    surfaceContainerLowest = Color(0xFF0D0E13), surfaceContainerLow = Color(0xFF1B1B21), surfaceContainer = Color(0xFF1F1F25),
    surfaceContainerHigh = Color(0xFF292A2F), surfaceContainerHighest = Color(0xFF34343A),
    outline = Color(0xFF90909A), outlineVariant = Color(0xFF46464F),
    inverseSurface = Color(0xFFE4E1E9), inverseOnSurface = Color(0xFF303036), inversePrimary = Color(0xFF4355B9),
)

/** Tam siyah: yalnızca yüzey katmanları değişir. */
fun ColorScheme.amoled() = copy(
    background = Color.Black, surface = Color.Black, surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0B0B0F), surfaceContainer = Color(0xFF111116),
    surfaceContainerHigh = Color(0xFF18181E), surfaceContainerHighest = Color(0xFF222228),
)

/** Gizli sekme yüzeyi: temadan bağımsız, ayırt edilebilir koyu mor-gri. */
val IncognitoColors = DarkColors.copy(
    surface = Color(0xFF1D1A29), background = Color(0xFF1D1A29), surfaceContainerLow = Color(0xFF262236), surfaceContainer = Color(0xFF2B273C),
    surfaceContainerHigh = Color(0xFF353046), surfaceContainerHighest = Color(0xFF403A52), onSurface = Color(0xFFECE6F4),
    onSurfaceVariant = Color(0xFFCBC3D9), outlineVariant = Color(0xFF4D4660), primary = Color(0xFFD3BBFF), onPrimary = Color(0xFF3B1E73),
    secondaryContainer = Color(0xFF4A4260), onSecondaryContainer = Color(0xFFECE6F4),
)

// Hız, boyut ve süre her yerde eşit genişlikli rakamla yazılır
private val Base = Typography()
private fun TextStyle.t(size: Int, line: Int, w: FontWeight, ls: Double = 0.0) =
    copy(fontSize = size.sp, lineHeight = line.sp, fontWeight = w, letterSpacing = ls.sp, fontFeatureSettings = "tnum")

val AppTypography = Typography(
    displayLarge = Base.displayLarge.t(57, 64, FontWeight.Normal, -0.25),
    displayMedium = Base.displayMedium.t(45, 52, FontWeight.Normal),
    headlineLarge = Base.headlineLarge.t(32, 40, FontWeight.Medium, -0.2),
    headlineMedium = Base.headlineMedium.t(28, 36, FontWeight.Medium),
    headlineSmall = Base.headlineSmall.t(24, 32, FontWeight.Medium),
    titleLarge = Base.titleLarge.t(22, 28, FontWeight.Medium),
    titleMedium = Base.titleMedium.t(16, 24, FontWeight.SemiBold, 0.1),
    titleSmall = Base.titleSmall.t(14, 20, FontWeight.SemiBold, 0.1),
    bodyLarge = Base.bodyLarge.t(16, 24, FontWeight.Normal, 0.3),
    bodyMedium = Base.bodyMedium.t(14, 20, FontWeight.Normal, 0.2),
    bodySmall = Base.bodySmall.t(12, 16, FontWeight.Normal, 0.3),
    labelLarge = Base.labelLarge.t(14, 20, FontWeight.SemiBold, 0.1),
    labelMedium = Base.labelMedium.t(12, 16, FontWeight.SemiBold, 0.4),
    labelSmall = Base.labelSmall.t(11, 16, FontWeight.SemiBold, 0.5),
)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun isDarkUi(prefs: UiPrefs): Boolean {
    val v by prefs.version.collectAsState()
    v.hashCode()
    return when (prefs.theme) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
}

@Composable
fun IndiriciTheme(prefs: UiPrefs, content: @Composable () -> Unit) {
    val v by prefs.version.collectAsState()
    v.hashCode()
    val dark = isDarkUi(prefs)
    val ctx = LocalContext.current
    var scheme = when {
        prefs.dynamicColor && Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> DarkColors
        else -> LightColors
    }
    if (dark && prefs.amoled) scheme = scheme.amoled()
    MaterialTheme(colorScheme = scheme, typography = AppTypography, shapes = AppShapes, content = content)
}
