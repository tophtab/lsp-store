@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.tophtab.lspstore.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.pullToRefresh
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Pull distance, in dp, that counts towards a refresh. Material's default is
 * 80.dp, and its modifier only counts half of the finger travel, so the stock
 * box needs ~160dp of drag before the refresh fires. 48.dp brings that to
 * ~96dp, and the indicator travels 48dp instead of 80dp with it.
 */
private val RefreshPullThreshold = 48.dp

/**
 * [androidx.compose.material3.pulltorefresh.PullToRefreshBox] with a shorter
 * pull. The Material3 box hard-codes [PullToRefreshDefaults.PositionalThreshold]
 * and exposes no way to change it, so this mirrors its layout on top of the
 * public [pullToRefresh] modifier, which does take a threshold.
 */
@Composable
fun PullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    state: PullToRefreshState = rememberPullToRefreshState(),
    contentAlignment: Alignment = Alignment.TopStart,
    indicator: @Composable BoxScope.() -> Unit = {
        PullToRefreshDefaults.Indicator(
            state = state,
            isRefreshing = isRefreshing,
            modifier = Modifier.align(Alignment.TopCenter),
            maxDistance = RefreshPullThreshold,
        )
    },
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.pullToRefresh(
            isRefreshing = isRefreshing,
            state = state,
            enabled = true,
            threshold = RefreshPullThreshold,
            onRefresh = onRefresh,
        ),
        contentAlignment = contentAlignment,
    ) {
        content()
        indicator()
    }
}
