@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.realtimetranspose.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.realtimetranspose.R

/**
 * "Nocturne" design system — see
 * docs/design/design_handoff_transpose_ui/README.md and
 * design/nocturne-styles.css for the source spec this file implements.
 * Every literal color/size in the two redesigned screens should come from
 * here, not be typed inline.
 */
object NocturneColors {
    val bg = Color(0xFF161826)
    val surface = Color(0xFF232532)
    val text = Color(0xFFE9E9ED)
    val textMuted = text.copy(alpha = 0.55f)
    val textFaint = text.copy(alpha = 0.45f)
    val divider = text.copy(alpha = 0.16f)

    val accent = Color(0xFF9184D9)
    val accent300 = Color(0xFFD2CEFD)
    val accent400 = Color(0xFFB5ABFC)
    val accent700 = Color(0xFF5D5294)
    val accent900 = Color(0xFF2B2741)

    val neutral900 = Color(0xFF292B31)

    val errorText = Color(0xFFF0A1AE)
    val errorBorder = Color(0xFFE2647A).copy(alpha = 0.35f)
}

/**
 * Minimal M3 ColorScheme so ripple/indication and any unstyled component
 * defaults stay dark-consistent — the two screens color almost everything
 * explicitly via [NocturneColors] rather than MaterialTheme.colorScheme.
 */
val NocturneMaterialColorScheme = darkColorScheme(
    background = NocturneColors.bg,
    surface = NocturneColors.surface,
    onBackground = NocturneColors.text,
    onSurface = NocturneColors.text,
    primary = NocturneColors.accent,
    onPrimary = NocturneColors.bg,
    secondary = NocturneColors.accent,
    onSecondary = NocturneColors.bg,
    error = NocturneColors.errorText,
    onError = NocturneColors.bg,
    outline = NocturneColors.divider,
)

/** Variable fonts (see res/font/) — weight comes from [FontVariation], not separate files. */
private val interVariable = FontFamily(
    Font(R.font.inter_variable, weight = FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
    Font(R.font.inter_variable, weight = FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
)

private val jetBrainsMonoVariable = FontFamily(
    Font(R.font.jetbrains_mono_variable, weight = FontWeight.Normal, variationSettings = FontVariation.Settings(FontVariation.weight(400))),
)

/**
 * Named text styles matching the handoff's exact scale (40/19/14/13/12.5/12/11/10.5sp).
 * Deliberately not routed through Material3's fixed type-role names — this
 * scale doesn't map cleanly onto them.
 */
object NocturneType {
    val heading = interVariable
    val mono = jetBrainsMonoVariable

    /** Big pitch/speed value, e.g. "+3", "1.00x". Files: 40sp. */
    val valueDisplay = TextStyle(
        fontFamily = mono,
        fontWeight = FontWeight.Medium,
        fontSize = 40.sp,
        textAlign = TextAlign.Center,
    )

    /** Empty-state title, "No file loaded". */
    val emptyTitle = TextStyle(fontFamily = heading, fontWeight = FontWeight.Medium, fontSize = 19.sp)

    /** Button label / Browser mono value size. */
    val button = TextStyle(fontFamily = heading, fontWeight = FontWeight.Medium, fontSize = 14.sp)
    val browserValueMono = TextStyle(fontFamily = mono, fontWeight = FontWeight.Normal, fontSize = 14.sp, textAlign = TextAlign.Center)

    /** File name, tab label, placeholder body text. */
    val body13 = TextStyle(fontFamily = heading, fontWeight = FontWeight.Normal, fontSize = 13.sp)

    /** Secondary body copy (empty-state subtitle). */
    val body12_5 = TextStyle(fontFamily = heading, fontWeight = FontWeight.Normal, fontSize = 12.5.sp, lineHeight = 17.sp)

    /** Loop status line, small buttons. */
    val mono12 = TextStyle(fontFamily = mono, fontWeight = FontWeight.Normal, fontSize = 12.sp)
    val button12 = TextStyle(fontFamily = heading, fontWeight = FontWeight.Medium, fontSize = 12.sp)

    /** Uppercase section labels, metadata, diagnostic labels. */
    val sectionLabel = TextStyle(
        fontFamily = heading,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.12.em,
    )
    val metadata = TextStyle(fontFamily = heading, fontWeight = FontWeight.Normal, fontSize = 11.sp)
    val diagnosticLabel = TextStyle(fontFamily = heading, fontWeight = FontWeight.Normal, fontSize = 11.sp)

    /** Header wordmark "TRANSPOSE". */
    val wordmark = TextStyle(
        fontFamily = heading,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 0.22.em,
    )

    /** Diagnostic panel lines (Hook/Level/AdBlock). */
    val diagnosticMono = TextStyle(fontFamily = mono, fontWeight = FontWeight.Normal, fontSize = 10.5.sp)
}

/** Spacing scale (0.7x density per the handoff) and radii, in dp. */
object NocturneSpacing {
    val space1 = 2.8.dp
    val space2 = 5.6.dp
    val space3 = 8.4.dp
    val space4 = 11.2.dp
    val space6 = 16.8.dp
    val space8 = 22.4.dp

    val screenPadding = 16.dp
    val blockGap = 18.dp
    val blockInnerGap = 10.dp

    val radiusSm = 4.dp
    val radiusMd = 8.dp
    val radiusLg = 14.dp

    /** Minimum touch target per the handoff — apply via Modifier.sizeIn even
     * when the visible control (e.g. a 28dp stepper button) is smaller. */
    val minTouchTarget = 44.dp
}

/**
 * Soft radial-gradient "glow" behind a circular control — the handoff's CSS
 * `box-shadow: 0 0 <blur> accent <alpha>%` glows. Deliberately NOT
 * Modifier.shadow: elevation shadows with a CircleShape outline render as a
 * visibly faceted polygon (an "octagon") on some devices/OS versions rather
 * than a smooth circle, which a plain radial gradient never does.
 *
 * [glowRadius] is measured from the composable's center, i.e. it already
 * includes half the control's own diameter — pass the same "0 0 Xdp" value
 * the handoff specifies plus roughly half the control size.
 */
fun Modifier.accentGlow(glowRadius: Dp, alpha: Float = 0.22f, color: Color = NocturneColors.accent): Modifier =
    drawBehind {
        val radiusPx = glowRadius.toPx()
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = alpha), color.copy(alpha = 0f)),
                radius = radiusPx,
            ),
            radius = radiusPx,
        )
    }
