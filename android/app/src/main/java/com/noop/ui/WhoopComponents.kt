package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import java.util.Locale
import java.text.NumberFormat
import kotlin.math.cos
import kotlin.math.sin

enum class ScoreDialSize { Full, Compact }

@Composable
fun ScoreDial(
    label: String,
    value: String,
    unit: String = "",
    progress: Float?,
    color: Color,
    modifier: Modifier = Modifier,
    size: ScoreDialSize = ScoreDialSize.Full,
    target: Float? = null,
    targetRange: ClosedFloatingPointRange<Float>? = null,
    accessibilityLabel: String? = null,
    viewportWidth: Dp? = null,
) {
    val compact = size == ScoreDialSize.Compact
    val diameter = if (compact) Metrics.compactDial else viewportWidth
        ?.takeIf { it.value.isFinite() && it.value > 0f }?.times(Metrics.fullScoreDialWidthFraction) ?: Metrics.detailDial
    val scale = if (compact) 1f else diameter / Metrics.detailDial
    val stroke = if (compact) Metrics.compactDialStroke else Metrics.detailDialStroke * scale
    Column(modifier.clearAndSetSemantics {
        contentDescription = accessibilityLabel ?: listOf(label, value + unit)
            .filter { it.isNotBlank() }.joinToString(", ")
    }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(diameter),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val width = stroke.toPx()
                val origin = Offset(width / 2, width / 2)
                val arcSize = Size(this.size.width - width, this.size.height - width)
                drawArc(Palette.ringTrack, -90f, 360f, false, origin, arcSize, style = Stroke(width))
                targetRange?.takeIf { it.start.isFinite() && it.endInclusive.isFinite() }?.let {
                    val start = it.start.coerceIn(0f, 1f)
                    val end = it.endInclusive.coerceIn(start, 1f)
                    drawArc(Palette.textSecondary.copy(alpha = StrandAlpha.chartMarker), -90f + start * 360f,
                        (end - start) * 360f, false, origin, arcSize, style = Stroke(width))
                }
                progress?.takeIf { it.isFinite() }?.let {
                    val fraction = it.coerceIn(0f, 1f)
                    if (fraction > 0f) drawArc(color, -90f, fraction * 360f, false, origin, arcSize,
                        style = Stroke(width, cap = if (fraction == 1f) StrokeCap.Butt else StrokeCap.Round))
                }
                target?.takeIf { it.isFinite() }?.let {
                    val angle = Math.toRadians((-90 + it.coerceIn(0f, 1f) * 360).toDouble())
                    val center = Offset(this.size.width / 2, this.size.height / 2)
                    val radius = (this.size.minDimension - width) / 2
                    val vector = Offset(cos(angle).toFloat(), sin(angle).toFloat())
                    drawLine(Palette.textPrimary, center + vector * (radius - width / 2),
                        center + vector * (radius + width / 2), Metrics.chartLineWidth.toPx())
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(value, style = if (compact) NoopType.dialValueCompact else NoopType.dialValueFull.copy(fontSize = NoopType.dialValueFull.fontSize * scale),
                        color = Palette.textPrimary, maxLines = 1)
                    if (unit.isNotEmpty()) Text(unit,
                        style = if (compact) NoopType.dialUnitCompact else NoopType.dialUnitFull.copy(fontSize = NoopType.dialUnitFull.fontSize * scale),
                        color = Palette.textPrimary, modifier = Modifier.padding(bottom = Metrics.space4))
                }
                if (!compact) Text(label.uppercase(Locale.getDefault()), style = NoopType.overline,
                    color = Palette.textPrimary, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = Metrics.space24))
            }
        }
        if (compact) Text(label.uppercase(Locale.getDefault()), style = NoopType.overline,
            color = Palette.textPrimary, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = Metrics.space10))
    }
}

@Composable
fun MetricCard(
    label: String,
    value: String,
    unit: String = "",
    detail: String? = null,
    icon: ImageVector? = null,
    color: Color = Palette.textPrimary,
    modifier: Modifier = Modifier,
) {
    NoopCard(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space10)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Metrics.space8)) {
                if (icon != null) Icon(icon, null, tint = color, modifier = Modifier.size(Metrics.iconSmall))
                Text(label.uppercase(Locale.getDefault()), style = NoopType.overline, color = Palette.textSecondary)
            }
            Row(verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(Metrics.space4)) {
                Text(value, style = NoopType.tileValueLarge, color = color)
                if (unit.isNotBlank()) Text(unit, style = NoopType.caption, color = Palette.textSecondary,
                    modifier = Modifier.padding(bottom = Metrics.space2))
            }
            if (!detail.isNullOrBlank()) Text(detail, style = NoopType.caption, color = Palette.textSecondary)
        }
    }
}

@Composable
fun TrackedSectionHeader(
    title: String,
    microLabel: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Metrics.space4)) {
            if (!microLabel.isNullOrBlank()) Text(microLabel.uppercase(Locale.getDefault()),
                style = NoopType.overline, color = Palette.textSecondary)
            Text(title, style = NoopType.title2, color = Palette.textPrimary)
        }
        if (actionLabel != null && onAction != null) TextButton(onClick = onAction) {
            Text(actionLabel.uppercase(Locale.getDefault()), style = NoopType.overline, color = Palette.textPrimary)
        }
    }
}

@Composable
fun ContributorRow(
    label: String,
    value: String,
    unit: String = "",
    icon: ImageVector? = null,
    comparison: String? = null,
    comparisonIcon: ImageVector? = null,
    comparisonColor: Color = Palette.textSecondary,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().heightIn(min = Metrics.contributorMinHeight)
        .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space12)) {
        if (icon != null) Icon(icon, null, tint = Palette.textSecondary, modifier = Modifier.size(Metrics.space24))
        Text(label.uppercase(Locale.getDefault()), style = NoopType.overline,
            color = Palette.textPrimary, modifier = Modifier.weight(1f))
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Metrics.space4)) {
                Text(value, style = NoopType.chartValueLarge, color = Palette.textPrimary)
                if (unit.isNotBlank()) Text(unit, style = NoopType.caption, color = Palette.textSecondary)
            }
            if (!comparison.isNullOrBlank()) Text(comparison, style = NoopType.caption, color = comparisonColor)
        }
        if (comparisonIcon != null) Icon(comparisonIcon, comparison, tint = comparisonColor,
            modifier = Modifier.size(Metrics.iconSmall))
    }
}

@Composable
fun StatusPill(label: String, icon: ImageVector? = null, color: Color = Palette.positive, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(Metrics.cornerBadge))
        .background(color.copy(alpha = StrandAlpha.selectedFill))
        .padding(horizontal = Metrics.space8, vertical = Metrics.space6),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space6)) {
        if (icon != null) Icon(icon, null, tint = color, modifier = Modifier.size(Metrics.iconTiny))
        Text(label.uppercase(Locale.getDefault()), style = NoopType.overline, color = color)
    }
}

@Composable
fun InsightCallout(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Metrics.cardRadius)
    Column(modifier.fillMaxWidth().clip(shape).background(Palette.surfaceBase)
        .border(Metrics.divider, Palette.coachGradient, shape).padding(Metrics.cardPadding),
        verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
        Text(text, style = NoopType.body, color = Palette.textPrimary)
        if (actionLabel != null && onAction != null) Row(
            Modifier.fillMaxWidth().heightIn(min = Metrics.iconButton).clickable(onClick = onAction),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Metrics.space8)) {
            Text(actionLabel.uppercase(Locale.getDefault()), style = NoopType.overline, color = Palette.coachCyan,
                modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = Palette.coachCyan,
                modifier = Modifier.size(Metrics.space24))
        }
    }
}

@Composable
fun TopChrome(
    dateLabel: String,
    previousLabel: String,
    nextLabel: String,
    profileLabel: String,
    strapLabel: String,
    avatarInitials: String,
    batteryPercent: Int? = null,
    isConnected: Boolean = false,
    canGoNext: Boolean = true,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onDate: () -> Unit,
    onProfile: () -> Unit,
    onStrap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth().heightIn(min = Metrics.chromeMinHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space4)) {
        Box(Modifier.size(Metrics.iconButton).clip(CircleShape).clickable(onClick = onProfile)
            .semantics { contentDescription = profileLabel }, contentAlignment = Alignment.Center) {
            Box(Modifier.size(Metrics.chromeAvatar).background(Palette.surfaceOverlay, CircleShape),
                contentAlignment = Alignment.Center) {
                Text(avatarInitials, style = NoopType.caption, color = Palette.textPrimary)
            }
        }
        Row(Modifier.weight(1f).clip(RoundedCornerShape(Metrics.cornerPill)).background(Palette.surfaceRaised),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevious, modifier = Modifier.size(Metrics.iconButton)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, previousLabel, tint = Palette.textPrimary)
            }
            Text(dateLabel.uppercase(Locale.getDefault()), style = NoopType.overline,
                color = Palette.textPrimary, textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f).heightIn(min = Metrics.iconButton)
                    .clickable(onClick = onDate).padding(vertical = Metrics.space16), maxLines = 1,
                overflow = TextOverflow.Ellipsis)
            IconButton(onClick = onNext, enabled = canGoNext, modifier = Modifier.size(Metrics.iconButton)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, nextLabel,
                    tint = Palette.textPrimary.copy(alpha = if (canGoNext) 1f else Palette.disabledOpacity))
            }
        }
        Row(Modifier.widthIn(min = Metrics.iconButton).heightIn(min = Metrics.iconButton).clip(RoundedCornerShape(Metrics.cornerSm))
            .clickable(onClick = onStrap).padding(horizontal = Metrics.space6)
            .semantics { contentDescription = strapLabel },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Metrics.space4)) {
            if (batteryPercent != null) Text(NumberFormat.getPercentInstance().format(batteryPercent.coerceIn(0, 100) / 100.0),
                style = NoopType.captionNumber, color = Palette.textPrimary)
            Icon(Icons.Filled.BatteryStd, null, tint = Palette.textPrimary, modifier = Modifier.size(Metrics.iconSmall))
            Box(Modifier.size(Metrics.space6).background(
                if (isConnected) Palette.positive else Palette.textTertiary, CircleShape))
        }
    }
}

data class TabCapsuleItem(val id: String, val label: String, val icon: ImageVector)

@Composable
fun TabCapsule(items: List<TabCapsuleItem>, selectedID: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(Metrics.cornerPill)
    Row(modifier.clip(shape).background(Palette.surfaceRaised.copy(alpha = BottomBarStyleStore.barAlpha))
        .border(Metrics.divider, Palette.hairline.copy(alpha = StrandAlpha.subtleLine), shape)
        .selectableGroup().padding(horizontal = Metrics.space6, vertical = Metrics.space4),
        verticalAlignment = Alignment.CenterVertically) {
        items.forEach { item ->
            val selected = item.id == selectedID
            val tint = if (selected) Palette.textPrimary else Palette.textSecondary
            Column(Modifier.weight(1f).heightIn(min = Metrics.tabHeight * BottomBarStyleStore.scale)
                .clip(RoundedCornerShape(Metrics.cornerSm))
                .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(item.id) })
                .padding(vertical = Metrics.space8), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Icon(item.icon, null, tint = tint, modifier = Modifier.size(Metrics.space24 * BottomBarStyleStore.scale))
                Spacer(Modifier.size(Metrics.space4))
                Text(item.label, style = NoopType.footnote, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun CoachOrb(label: String, onTap: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.size(Metrics.coachOrb).clip(RoundedCornerShape(Metrics.cardRadius))
        .background(Palette.surfaceRaised).border(Metrics.divider, Palette.coachGradient,
            RoundedCornerShape(Metrics.cardRadius)).clickable(onClick = onTap)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(Metrics.chromeAvatar).border(Metrics.divider, Palette.coachGradient, CircleShape),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.AutoAwesome, null, tint = Palette.coachCyan, modifier = Modifier.size(Metrics.space24))
        }
    }
}

object WhoopChartStyle {
    val barWidth get() = Metrics.chartBarWidth
    val lineWidth get() = Metrics.chartLineWidth
    val gridWidth get() = Metrics.chartGridWidth
    val gridColor get() = Palette.hairline
    val axisColor get() = Palette.textTertiary
    val sleepAwake get() = Palette.sleepAwake
    val sleepLight get() = Palette.sleepLight
    val sleepDeep get() = Palette.sleepDeep
    val sleepREM get() = Palette.sleepREM
}
