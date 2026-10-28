package io.legado.app.feature.privateshelf

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.legado.app.R
import io.legado.app.help.security.BiometricUnlockLauncher
import io.legado.app.help.security.PrivateSecureController
import io.legado.app.ui.main.bookshelf.BookItem
import io.legado.app.ui.main.bookshelf.BookShelfItem
import kotlinx.collections.immutable.persistentListOf
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.AppTextField
import io.legado.app.ui.widget.components.PasswordTextField
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.button.series.SmallPlainButton
import io.legado.app.ui.widget.components.button.series.SmallTonalButton
import io.legado.app.ui.widget.components.privacy.findFragmentActivity
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.TopBarActionButton
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton
import io.legado.app.utils.toastOnUi
import org.koin.compose.koinInject
import org.koin.androidx.compose.koinViewModel

/**
 * 私密模式目的地。
 *
 * 形态由 (hasPassword, sessionUnlocked) 派生：开通向导 → 验证 → 书架。
 * 返回（系统/顶栏）= 退出私密模式：**立即锁定会话**再离页；
 * 进入阅读器不算退出（闸门与 FLAG_SECURE 由 PrivateReadGate 接管）。
 */
@Composable
fun PrivateShelfRouteScreen(
    onBackClick: () -> Unit,
    onOpenBook: (BookShelfItem) -> Unit,
    viewModel: PrivateShelfViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val secureController: PrivateSecureController = koinInject()
    val unlockTitle = stringResource(R.string.private_unlock_title)
    val unlockSubtitle = stringResource(R.string.private_unlock_subtitle)
    val unlockUsePassword = stringResource(R.string.private_unlock_use_password)

    // 私密模式书架在前台期间保持 FLAG_SECURE
    DisposableEffect(Unit) {
        secureController.retain()
        onDispose { secureController.release() }
    }

    // 只弹栈：锁定由页面销毁（VM onCleared）统一执行，避免过渡期闪验证页
    val exit = { onBackClick() }
    // 书架形态才拦截返回：开通/验证形态直接离页（会话本来就是锁定态）
    BackHandler(enabled = uiState.phase is PrivateShelfPhase.Shelf) { exit() }

    LaunchedEffect(Unit) {
        viewModel.effects.collect { effect ->
            when (effect) {
                PrivateShelfEffect.RequestBiometricUnlock -> {
                    val activity = context.findFragmentActivity()
                    if (activity == null) {
                        // 无宿主时回退密码输入；验证页仍展示密码框
                    } else {
                        BiometricUnlockLauncher.launch(
                            activity = activity,
                            title = unlockTitle,
                            subtitle = unlockSubtitle,
                            negativeButtonText = unlockUsePassword,
                            onPassword = { viewModel.onIntent(PrivateShelfIntent.SubmitPassword(it)) },
                            onFallback = { },
                            onError = { message -> context.toastOnUi(message) },
                        )
                    }
                }

                is PrivateShelfEffect.ShowToastRes -> context.toastOnUi(effect.resId)
            }
        }
    }

    // 验证形态且生物可用：进入即自动弹一次系统验证框
    var autoBiometricLaunched by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(uiState.phase, uiState.biometricPreferred) {
        if (uiState.phase is PrivateShelfPhase.Verify &&
            uiState.biometricPreferred &&
            !autoBiometricLaunched
        ) {
            autoBiometricLaunched = true
            viewModel.onIntent(PrivateShelfIntent.RequestBiometricUnlock)
        }
    }

    when (uiState.phase) {
        PrivateShelfPhase.Onboarding -> OnboardingContent(
            onIntent = viewModel::onIntent,
        )

        PrivateShelfPhase.Verify -> VerifyContent(
            biometricPreferred = uiState.biometricPreferred,
            onIntent = viewModel::onIntent,
            onExit = onBackClick,
        )

        PrivateShelfPhase.Shelf -> ShelfContent(
            uiState = uiState,
            onIntent = viewModel::onIntent,
            onExit = exit,
            onOpenBook = onOpenBook,
        )
    }
}

@Composable
private fun OnboardingContent(onIntent: (PrivateShelfIntent) -> Unit) {
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    val passwordKeyboard = KeyboardOptions(keyboardType = KeyboardType.Password)

    AppScaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
        Icon(
            imageVector = Icons.Outlined.Lock,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = LegadoTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(16.dp))
        AppText(
            text = stringResource(R.string.private_mode_onboarding_title),
            style = LegadoTheme.typography.titleLarge,
        )
        Spacer(modifier = Modifier.height(8.dp))
        AppText(
            text = stringResource(R.string.private_mode_onboarding_desc),
            style = LegadoTheme.typography.bodySmall,
            color = LegadoTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(24.dp))
        PrivatePasswordField(
            value = password,
            onValueChange = { password = it },
            label = stringResource(R.string.private_mode_password_set),
            keyboardOptions = passwordKeyboard,
        )
        Spacer(modifier = Modifier.height(12.dp))
        PrivatePasswordField(
            value = confirm,
            onValueChange = { confirm = it },
            label = stringResource(R.string.private_mode_password_confirm),
            keyboardOptions = passwordKeyboard,
        )
            Spacer(modifier = Modifier.height(24.dp))
            SmallTonalButton(
                onClick = {
                    onIntent(PrivateShelfIntent.SetupPassword(password, confirm))
                },
                text = stringResource(R.string.private_mode_setup_and_enter),
            )
        }
    }
}

@Composable
private fun VerifyContent(
    biometricPreferred: Boolean,
    onIntent: (PrivateShelfIntent) -> Unit,
    onExit: () -> Unit,
) {
    var password by rememberSaveable { mutableStateOf("") }
    var showResetConfirm by rememberSaveable { mutableStateOf(false) }

    AppScaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(padding)
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            modifier = Modifier.size(40.dp),
            tint = LegadoTheme.colorScheme.primary,
        )
        Spacer(modifier = Modifier.height(16.dp))
        AppText(
            text = stringResource(R.string.private_mode_verify_title),
            style = LegadoTheme.typography.titleLarge,
        )
        Spacer(modifier = Modifier.height(24.dp))
        PrivatePasswordField(
            value = password,
            onValueChange = { password = it },
            label = stringResource(R.string.private_unlock_password_title),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SmallPlainButton(
                onClick = onExit,
                text = stringResource(R.string.cancel),
            )
            SmallTonalButton(
                onClick = { onIntent(PrivateShelfIntent.SubmitPassword(password)) },
                text = stringResource(R.string.private_verify_and_open),
            )
        }
            if (biometricPreferred) {
                Spacer(modifier = Modifier.height(12.dp))
                SmallPlainButton(
                    onClick = { onIntent(PrivateShelfIntent.RequestBiometricUnlock) },
                    text = stringResource(R.string.private_mode_use_biometric),
                )
            }
            Spacer(modifier = Modifier.height(20.dp))
            SmallPlainButton(
                onClick = { showResetConfirm = true },
                text = stringResource(R.string.private_mode_forgot_password),
            )
        }
    }

    if (showResetConfirm) {
        AppAlertDialog(
            show = true,
            onDismissRequest = { showResetConfirm = false },
            title = stringResource(R.string.private_mode_reset_title),
            text = stringResource(R.string.private_mode_reset_desc),
            confirmText = stringResource(R.string.private_mode_reset_confirm),
            onConfirm = {
                showResetConfirm = false
                onIntent(PrivateShelfIntent.ResetPassword)
            },
            dismissText = stringResource(R.string.cancel),
            onDismiss = { showResetConfirm = false },
        )
    }
}

@Composable
private fun ShelfContent(
    uiState: PrivateShelfUiState,
    onIntent: (PrivateShelfIntent) -> Unit,
    onExit: () -> Unit,
    onOpenBook: (BookShelfItem) -> Unit,
) {
    AppScaffold(
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = stringResource(R.string.private_mode_title),
                navigationIcon = {
                    TopBarNavigationButton(onClick = onExit)
                },
                actions = {
                    TopBarActionButton(
                        onClick = onExit,
                        imageVector = Icons.Default.LockOpen,
                        contentDescription = stringResource(R.string.private_mode_exit),
                    )
                },
            )
        },
    ) { padding ->
        if (uiState.books.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                AppText(
                    text = stringResource(R.string.private_mode_empty_hint),
                    style = LegadoTheme.typography.bodyMedium,
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            BookshelfStyleGrid(
                uiState = uiState,
                padding = padding,
                onOpenBook = onOpenBook,
                onIntent = onIntent,
            )
        }
    }

    uiState.pendingUnmark?.let { book ->
        AppAlertDialog(
            show = true,
            onDismissRequest = { onIntent(PrivateShelfIntent.DismissUnmark) },
            title = stringResource(R.string.private_mode_unmark),
            text = stringResource(R.string.private_mode_unmark_confirm, book.name),
            confirmText = stringResource(android.R.string.ok),
            onConfirm = { onIntent(PrivateShelfIntent.ConfirmUnmark) },
            dismissText = stringResource(android.R.string.cancel),
            onDismiss = { onIntent(PrivateShelfIntent.DismissUnmark) },
        )
    }
}

/** 与公开书架同一套 BookItem 与布局参数：私密模式只是换数据源，不另立样式 */
@Composable
private fun BookshelfStyleGrid(
    uiState: PrivateShelfUiState,
    padding: PaddingValues,
    onOpenBook: (BookShelfItem) -> Unit,
    onIntent: (PrivateShelfIntent) -> Unit,
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val settings = uiState.bookshelfSettings
    val layoutMode = if (isLandscape) {
        settings.bookshelfLayoutModeLandscape
    } else {
        settings.bookshelfLayoutModePortrait
    }
    val layoutGrid = if (isLandscape) {
        settings.bookshelfLayoutGridLandscape
    } else {
        settings.bookshelfLayoutGridPortrait
    }
    val layoutList = if (isLandscape) {
        settings.bookshelfLayoutListLandscape
    } else {
        settings.bookshelfLayoutListPortrait
    }
    val columns = if (layoutMode == 0) layoutList else layoutGrid
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns.coerceAtLeast(1)),
        modifier = Modifier
            .fillMaxSize()
            .padding(padding),
        contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(uiState.books, key = { it.bookUrl }) { book ->
            BookItem(
                settings = settings,
                customTagColors = if (uiState.enableCustomTagColors) {
                    uiState.customTagColors
                } else {
                    persistentListOf()
                },
                bookUi = book.toUiItem(),
                layoutMode = layoutMode,
                gridStyle = settings.bookshelfGridLayout,
                isCompact = settings.bookshelfLayoutCompact,
                titleSmallFont = settings.bookshelfTitleSmallFont,
                titleCenter = settings.bookshelfTitleCenter,
                titleMaxLines = settings.bookshelfTitleMaxLines,
                coverShadow = settings.bookshelfCoverShadow,
                onClick = { onOpenBook(book) },
                onLongClick = { onIntent(PrivateShelfIntent.RequestUnmark(book)) },
            )
        }
    }
}

@Composable
private fun PrivatePasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardOptions: KeyboardOptions,
) {
    AppTextField(
        value = value,
        onValueChange = onValueChange,
        label = label,
        backgroundColor = LegadoTheme.colorScheme.surface,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = keyboardOptions,
        modifier = Modifier.fillMaxWidth(),
    )
}
