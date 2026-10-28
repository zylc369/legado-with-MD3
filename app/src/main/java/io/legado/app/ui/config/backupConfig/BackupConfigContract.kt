package io.legado.app.ui.config.backupConfig

import androidx.annotation.StringRes
import androidx.compose.runtime.Stable
import io.legado.app.domain.model.settings.BackupSettings
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Stable
data class BackupConfigUiState(
    val settings: BackupSettings = BackupSettings(),
    val activeSheet: BackupConfigSheet? = null,
    val activeDialog: BackupConfigDialog? = null,
    val backupNames: ImmutableList<String> = persistentListOf(),
    val ignoreItems: ImmutableList<BackupIgnoreItem> = persistentListOf(),
    val backupIgnoreItems: ImmutableList<BackupIgnoreItem> = persistentListOf(),
    val dbIgnoreItems: ImmutableList<BackupIgnoreItem> = persistentListOf(),
    val backupDbIgnoreItems: ImmutableList<BackupIgnoreItem> = persistentListOf(),
)

@Stable
data class BackupIgnoreItem(
    val key: String,
    val title: String,
    val checked: Boolean,
)

sealed interface BackupConfigSheet {
    data object ChooseBackupPath : BackupConfigSheet
    data object ChooseBackupAndRun : BackupConfigSheet
    data object BackupOptions : BackupConfigSheet
    data object RestoreOptions : BackupConfigSheet
    data object RestoreFiles : BackupConfigSheet
    data object IgnoreRestoreItems : BackupConfigSheet
    data object IgnoreBackupItems : BackupConfigSheet
}

sealed interface BackupConfigDialog {
    data class WebDavAuth(
        val account: String,
        val password: String,
        val passwordVisible: Boolean = false,
    ) : BackupConfigDialog

    data class ConfirmLocalRestoreFallback(val error: String?) : BackupConfigDialog
    data class Loading(@StringRes val titleRes: Int) : BackupConfigDialog
}

sealed interface BackupConfigIntent {
    data class SetWebDavUrl(val value: String) : BackupConfigIntent
    data class SetWebDavDir(val value: String) : BackupConfigIntent
    data class SetWebDavDeviceName(val value: String) : BackupConfigIntent
    data class SetSyncBookProgress(val value: Boolean) : BackupConfigIntent
    data class SetSyncBookProgressPlus(val value: Boolean) : BackupConfigIntent
    data class SetAutoCheckNewBackup(val value: Boolean) : BackupConfigIntent
    data class SetOnlyLatestBackup(val value: Boolean) : BackupConfigIntent
    data class SetBackupSyncMode(val value: String) : BackupConfigIntent
    data class OpenSheet(val sheet: BackupConfigSheet) : BackupConfigIntent
    data object DismissSheet : BackupConfigIntent
    data object OpenWebDavAuth : BackupConfigIntent
    data class EditWebDavAccount(val value: String) : BackupConfigIntent
    data class EditWebDavPassword(val value: String) : BackupConfigIntent
    data object TogglePasswordVisibility : BackupConfigIntent
    data object SaveWebDavAuth : BackupConfigIntent
    data object TestWebDav : BackupConfigIntent
    data object OpenIgnoreDialog : BackupConfigIntent
    data class ToggleIgnoreItem(val key: String, val value: Boolean) : BackupConfigIntent
    data object SaveIgnoreItems : BackupConfigIntent
    data object OpenBackupIgnoreDialog : BackupConfigIntent
    data class ToggleBackupIgnoreItem(val key: String, val value: Boolean) : BackupConfigIntent
    data object SaveBackupIgnoreItems : BackupConfigIntent
    data class ToggleDbIgnoreItem(val key: String, val value: Boolean) : BackupConfigIntent
    data object SaveDbIgnoreItems : BackupConfigIntent
    data class ToggleBackupDbIgnoreItem(val key: String, val value: Boolean) : BackupConfigIntent
    data object SaveBackupDbIgnoreItems : BackupConfigIntent
    data object DismissDialog : BackupConfigIntent
    data object SelectBackupDirectory : BackupConfigIntent
    data object SelectBackupAndRunDirectory : BackupConfigIntent
    data class BackupDirectorySelected(val path: String, val runBackup: Boolean) : BackupConfigIntent
    data class RequestBackup(val mode: String) : BackupConfigIntent
    data class PerformBackup(val path: String, val mode: String) : BackupConfigIntent
    data object RequestLocalRestore : BackupConfigIntent
    data class RestoreLocal(val uri: String) : BackupConfigIntent
    data object RequestNetworkRestore : BackupConfigIntent
    data class RestoreNetwork(val name: String) : BackupConfigIntent
    data object ConfirmLocalRestoreFallback : BackupConfigIntent
    data object RequestImportOldData : BackupConfigIntent

    /** 未设置该地址的备份密码：设置后继续备份 */
    data class ConfirmBackupPassword(
        val password: String,
        val runMode: String,
        val runPath: String,
        val addressKey: String,
    ) : BackupConfigIntent

    /** 不设置密码，直接备份（敏感信息只有弱保护） */
    data class SkipBackupPassword(
        val runMode: String,
        val runPath: String,
    ) : BackupConfigIntent

    /** 恢复前输入密码（本地备份无法确定来源地址，必须询问） */
    data class ConfirmRestorePassword(
        val password: String,
        val mode: String,
        val target: String,
        val addressKey: String?,
    ) : BackupConfigIntent

    data object CancelRestorePassword : BackupConfigIntent
}

sealed interface BackupConfigEffect {
    data object LaunchBackupDirectoryPicker : BackupConfigEffect
    data object LaunchBackupAndRunDirectoryPicker : BackupConfigEffect
    data object LaunchRestoreFilePicker : BackupConfigEffect
    data object LaunchImportOldDataPicker : BackupConfigEffect
    data class RequestStoragePermission(val path: String, val mode: String) : BackupConfigEffect

    /** 备份前提醒设置备份密码（用于加密备份中的账号等敏感信息） */
    data class RequestBackupPassword(
        val mode: String,
        val path: String,
        val addressKey: String,
    ) : BackupConfigEffect

    /** 恢复前询问密码 */
    data class RequestRestorePassword(
        val mode: String,
        val target: String,
        val addressKey: String?,
        val suggested: String?,
    ) : BackupConfigEffect
    data class ShowMessage(@StringRes val messageRes: Int, val argument: String? = null) : BackupConfigEffect
}
