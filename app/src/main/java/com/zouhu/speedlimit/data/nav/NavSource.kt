package com.zouhu.speedlimit.data.nav

import com.zouhu.speedlimit.domain.model.NavUiState
import kotlinx.coroutines.flow.StateFlow

/** Navigation data source contract implemented by the real Baidu navigation bridge. */
interface NavSource {
    val state: StateFlow<NavUiState>
}
