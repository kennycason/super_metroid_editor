package com.supermetroid.editor.ui

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

private const val DEFAULT_MIN_PANE_HEIGHT_DP = 120f
private const val HEIGHT_UPDATE_EPSILON_DP = 0.5f

internal data class SidebarSplitHeights(
    val topPaneDp: Float,
    val bottomPaneDp: Float,
)

internal fun computeSidebarSplitHeights(
    totalHeightDp: Float,
    requestedBottomPaneDp: Float,
    dividerHeightDp: Float = 10f,
    minPaneHeightDp: Float = DEFAULT_MIN_PANE_HEIGHT_DP,
): SidebarSplitHeights {
    val contentHeightDp = (totalHeightDp - dividerHeightDp).coerceAtLeast(0f)
    val effectiveMinPaneDp = minPaneHeightDp.coerceAtMost(contentHeightDp / 2f)
    val defaultBottomPaneDp = contentHeightDp / 2f
    val requestedDp = requestedBottomPaneDp
        .takeIf { it.isFinite() && it > 0f }
        ?: defaultBottomPaneDp
    val bottomPaneDp = requestedDp.coerceIn(
        minimumValue = effectiveMinPaneDp,
        maximumValue = contentHeightDp - effectiveMinPaneDp,
    )
    return SidebarSplitHeights(
        topPaneDp = (contentHeightDp - bottomPaneDp).coerceAtLeast(0f),
        bottomPaneDp = bottomPaneDp.coerceAtLeast(0f),
    )
}

internal fun shouldUpdateSidebarSplitState(
    currentBottomPaneDp: Float,
    computedBottomPaneDp: Float,
): Boolean =
    !currentBottomPaneDp.isFinite() ||
        abs(currentBottomPaneDp - computedBottomPaneDp) > HEIGHT_UPDATE_EPSILON_DP

@Composable
internal fun SidebarVerticalSplit(
    bottomPaneHeightDp: Float,
    onBottomPaneHeightChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    minPaneHeightDp: Float = DEFAULT_MIN_PANE_HEIGHT_DP,
    dividerHeight: Dp = 10.dp,
    topPane: @Composable (Modifier) -> Unit,
    bottomPane: @Composable (Modifier) -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val totalHeightDp = maxHeight.value
        val dividerHeightDp = dividerHeight.value
        val split = computeSidebarSplitHeights(
            totalHeightDp = totalHeightDp,
            requestedBottomPaneDp = bottomPaneHeightDp,
            dividerHeightDp = dividerHeightDp,
            minPaneHeightDp = minPaneHeightDp,
        )

        LaunchedEffect(bottomPaneHeightDp, split.bottomPaneDp) {
            if (shouldUpdateSidebarSplitState(bottomPaneHeightDp, split.bottomPaneDp)) {
                onBottomPaneHeightChange(split.bottomPaneDp)
            }
        }

        Column(modifier = Modifier.fillMaxSize()) {
            topPane(Modifier.fillMaxWidth().height(split.topPaneDp.dp))
            DraggableDividerHorizontal(
                height = dividerHeight,
                onDelta = { deltaPx ->
                    val deltaDp = with(density) { deltaPx.toDp().value }
                    val resizedSplit = computeSidebarSplitHeights(
                        totalHeightDp = totalHeightDp,
                        requestedBottomPaneDp = split.bottomPaneDp - deltaDp,
                        dividerHeightDp = dividerHeightDp,
                        minPaneHeightDp = minPaneHeightDp,
                    )
                    onBottomPaneHeightChange(resizedSplit.bottomPaneDp)
                },
            )
            bottomPane(Modifier.fillMaxWidth().height(split.bottomPaneDp.dp))
        }
    }
}
