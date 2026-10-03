package com.noop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics

@Composable
internal fun DetailContributorRow(
    label: String,
    value: String,
    unit: String = "",
    icon: ImageVector? = null,
    comparison: String? = null,
    comparisonIcon: ImageVector? = null,
    comparisonColor: Color = Palette.textSecondary,
    modifier: Modifier = Modifier,
) {
    if (LocalDensity.current.fontScale > 1f) {
        Column(modifier.semantics(mergeDescendants = true) {},
            verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
            ContributorRow(label, "", icon = icon)
            ContributorRow("", value, unit, comparison = comparison, comparisonIcon = comparisonIcon,
                comparisonColor = comparisonColor)
        }
    } else {
        ContributorRow(label, value, unit, icon, comparison, comparisonIcon, comparisonColor, modifier)
    }
}
