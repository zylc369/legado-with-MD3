package io.legado.app.ui.book.readaloud.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.help.IntentHelp
import io.legado.app.ui.book.read.sheet.ReadAloudConfigContent
import io.legado.app.ui.book.readaloud.player.ReadAloudConfigIntent
import io.legado.app.ui.book.readaloud.player.ReadAloudPlayerConfigHostAction
import io.legado.app.ui.book.readaloud.player.ReadAloudPlayerIntent
import io.legado.app.ui.book.readaloud.player.ReadAloudPlayerUiState
import io.legado.app.ui.book.readaloud.player.ReadAloudPlayerViewModel
import io.legado.app.ui.book.readaloud.player.ReadAloudSettingsUiState
import io.legado.app.ui.book.readaloud.player.applyReadAloudConfigIntent
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton
import org.koin.compose.koinInject

/**
 * 「朗读设置」独立页宿主。
 *
 * 阅读界面与听书播放浮层共用同一份 [ReadAloudConfigContent]；本页是第三个宿主，
 * 也是唯一以 Navigation 3 路由承载的宿主。设置快照与写回都复用播放器的
 * [ReadAloudPlayerViewModel]（Koin 单例），所以阅读界面、听书浮层、本页三处设置实时同步，
 * 不再需要「离开时收弹层、返回时重开弹层」的补丁。
 */
@Composable
fun ReadAloudSettingsRouteScreen(
    bookUrl: String?,
    onBack: () -> Unit,
    onOpenTtsEnginesAndVoices: (String?) -> Unit,
    onOpenTtsCache: () -> Unit,
    onOpenBookVoiceCasting: (String) -> Unit,
    viewModel: ReadAloudPlayerViewModel = koinInject(),
) {
    val settingsState by viewModel.readAloudSettings.collectAsStateWithLifecycle()
    val playerState by viewModel.uiState.collectAsStateWithLifecycle()
    ReadAloudSettingsScreen(
        state = settingsState,
        playerState = playerState,
        onBack = onBack,
        onConfigIntent = { intent ->
            viewModel.applyReadAloudConfigIntent(intent) { action ->
                when (action) {
                    ReadAloudPlayerConfigHostAction.OpenTtsEnginesAndVoices ->
                        onOpenTtsEnginesAndVoices(bookUrl)

                    ReadAloudPlayerConfigHostAction.OpenTtsCache -> onOpenTtsCache()

                    ReadAloudPlayerConfigHostAction.OpenBookVoiceCasting ->
                        bookUrl?.takeIf(String::isNotBlank)?.let(onOpenBookVoiceCasting)

                    ReadAloudPlayerConfigHostAction.OpenSystemTtsSettings ->
                        IntentHelp.openTTSSetting()

                    // asPage=true 时四个数值项就地铺开为滑块，不会发出这四个选择器动作。
                    else -> Unit
                }
            }
        },
        onPlayerIntent = viewModel::onIntent,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadAloudSettingsScreen(
    state: ReadAloudSettingsUiState,
    playerState: ReadAloudPlayerUiState,
    onBack: () -> Unit,
    onConfigIntent: (ReadAloudConfigIntent) -> Unit,
    onPlayerIntent: (ReadAloudPlayerIntent) -> Unit,
) {
    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()
    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = stringResource(R.string.aloud_config),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    TopBarNavigationButton(onClick = onBack)
                },
            )
        },
    ) { paddingValues ->
        ReadAloudConfigContent(
            state = state,
            playerState = playerState,
            onIntent = onConfigIntent,
            onPlayerIntent = onPlayerIntent,
            asPage = true,
            modifier = Modifier.padding(paddingValues),
        )
    }
}
