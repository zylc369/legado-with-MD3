package io.legado.app.feature.customtts

import android.media.MediaPlayer
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import io.legado.app.feature.customtts.components.ConnectionSection
import io.legado.app.feature.customtts.components.HybridSection
import io.legado.app.feature.customtts.components.SegmentSection
import io.legado.app.feature.customtts.components.VoiceSection
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton
import kotlinx.coroutines.flow.Flow

/** 定制 TTS 配置页（无状态 Screen）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomTtsScreen(
    state: CustomTtsUiState,
    onIntent: (CustomTtsIntent) -> Unit,
    effects: Flow<CustomTtsEffect>,
    onBack: () -> Unit,
) {
    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()
    val context = LocalContext.current
    val player = remember { MediaPlayer() }
    DisposableEffect(Unit) {
        onDispose { runCatching { player.release() } }
    }
    LaunchedEffect(effects) {
        effects.collectLatest { effect ->
            when (effect) {
                is CustomTtsEffect.PlayPreview -> runCatching {
                    player.reset()
                    player.setDataSource(effect.path)
                    player.prepare()
                    player.start()
                }
                is CustomTtsEffect.ShowToast ->
                    Toast.makeText(context, effect.message, Toast.LENGTH_SHORT).show()
            }
        }
    }
    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = "定制朗读引擎",
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    TopBarNavigationButton(onClick = onBack)
                },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ConnectionSection(state, onIntent) }
            item { VoiceSection(state, onIntent) }
            item { HybridSection(state, onIntent) }
            item { SegmentSection(state, onIntent) }
            item {
                Button(
                    onClick = { onIntent(CustomTtsIntent.Save) },
                    enabled = !state.saving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (state.saving) "保存中…" else "保存并应用")
                }
            }
        }
    }
}
