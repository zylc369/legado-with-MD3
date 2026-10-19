package io.legado.app.feature.customtts

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** 定制 TTS 配置页 Route 宿主（Koin ViewModel + 导航回调）。 */
@Composable
fun CustomTtsRouteScreen(
    viewModel: CustomTtsViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    CustomTtsScreen(
        state = state,
        onIntent = viewModel::onIntent,
        effects = viewModel.effects,
        onBack = onBack,
    )
}
