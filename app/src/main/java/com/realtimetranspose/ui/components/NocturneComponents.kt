package com.realtimetranspose.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.realtimetranspose.ui.theme.NocturneColors
import com.realtimetranspose.ui.theme.NocturneSpacing
import com.realtimetranspose.ui.theme.NocturneType

/** Small shared pieces used across both screens. See Theme.kt for tokens. */

/** Uppercase 11sp label, e.g. "PITCH", with an optional trailing slot (a Reset button). */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text.uppercase(), style = NocturneType.sectionLabel, color = NocturneColors.textFaint)
        trailing?.invoke()
    }
}

/** Text-only accent button — "Reset", "Change", etc. Dims and stops responding when disabled. */
@Composable
fun GhostTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(NocturneSpacing.radiusSm))
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .alpha(if (enabled) 1f else 0.35f),
    ) {
        Text(text, style = NocturneType.button12, color = NocturneColors.accent)
    }
}

/** Circular outline icon button (steppers, reset icon in Browser, clear-loop trash). */
@Composable
fun CircleIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 38.dp,
    iconSize: Dp = 16.dp,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    val touchSize = maxOf(diameter, NocturneSpacing.minTouchTarget)
    Box(
        modifier = modifier
            .size(touchSize)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(diameter)
                .alpha(if (enabled) 1f else 0.4f)
                .border(1.dp, if (enabled) NocturneColors.accent else NocturneColors.divider, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = contentDescription, tint = NocturneColors.accent, modifier = Modifier.size(iconSize))
        }
    }
}

/** Small "A"/"B" loop-mark toggle button — outlined+accent when marked, plain when not. */
@Composable
fun LoopMarkButton(
    label: String,
    marked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(width = 38.dp, height = 30.dp)
            .clip(RoundedCornerShape(NocturneSpacing.radiusMd))
            .border(
                1.dp,
                if (marked) NocturneColors.accent else NocturneColors.divider,
                RoundedCornerShape(NocturneSpacing.radiusMd),
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = NocturneType.button12, color = if (marked) NocturneColors.accent else NocturneColors.text)
    }
}

/** Borderless icon button — the Browser panel's small reset icon. */
@Composable
fun GhostIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 14.dp,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    Box(
        modifier = modifier
            .size(NocturneSpacing.minTouchTarget)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = NocturneColors.accent,
            modifier = Modifier.size(iconSize).alpha(if (enabled) 1f else 0.35f),
        )
    }
}

/** Small status dot — accent (ok) / textMuted (idle) / error (failed). */
@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier, size: Dp = 5.dp) {
    Box(modifier = modifier.size(size).background(color, CircleShape))
}

/**
 * Generic error slot — one component for every pipeline failure message
 * (see ui-redesign-brief.md "Casos de error"), not one design per case.
 */
@Composable
fun ErrorCard(title: String, detail: String?, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(NocturneSpacing.radiusMd))
            .border(1.dp, NocturneColors.errorBorder, RoundedCornerShape(NocturneSpacing.radiusMd))
            .padding(horizontal = 11.dp, vertical = 10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(
                Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = NocturneColors.errorText,
                modifier = Modifier.size(16.dp),
            )
            androidx.compose.foundation.layout.Column {
                Text(title, style = NocturneType.body12_5, color = NocturneColors.errorText)
                detail?.let {
                    Text(it, style = NocturneType.diagnosticMono, color = NocturneColors.textMuted)
                }
            }
        }
    }
}
