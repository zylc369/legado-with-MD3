package io.legado.app.feature.customtts.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.legado.app.feature.customtts.CustomTtsIntent
import io.legado.app.feature.customtts.CustomTtsUiState

/** 连接配置区块（relay/PSK/节点/本机网关端口 + 启用与测试）。 */
@Composable
fun ConnectionSection(
    state: CustomTtsUiState,
    onIntent: (CustomTtsIntent) -> Unit,
) {
    SectionCard(
        title = "连接",
        subtitle = "手机本机网关 → P2P（打洞/中继）→ MacMini 节点",
    ) {
        SettingField("中继服务器地址", state.relayHost) {
            onIntent(CustomTtsIntent.SetRelayHost(it))
        }
        SettingField("中继端口", state.relayPort, KeyboardType.Number) {
            onIntent(CustomTtsIntent.SetRelayPort(it))
        }
        SettingField("PSK（base64）", state.psk) {
            onIntent(CustomTtsIntent.SetPsk(it))
        }
        SettingField("节点 ID", state.nodeId) {
            onIntent(CustomTtsIntent.SetNodeId(it))
        }
        SettingField("本地网关端口", state.localPort, KeyboardType.Number) {
            onIntent(CustomTtsIntent.SetLocalPort(it))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = state.gatewayEnabled,
                onCheckedChange = { onIntent(CustomTtsIntent.SetGatewayEnabled(it)) },
            )
            Spacer(Modifier.width(8.dp))
            Text("启用 RemoteLink 网关")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { onIntent(CustomTtsIntent.TestConnection) },
                enabled = !state.testing,
            ) {
                Text(if (state.testing) "测试中…" else "测试连接")
            }
            Spacer(Modifier.width(12.dp))
            Text(
                state.connectionStatus.ifEmpty {
                    if (state.gatewayRunning) {
                        "网关运行中: ${state.localPort}"
                    } else {
                        state.gatewayError?.let { "网关错误: $it" } ?: "网关未运行"
                    }
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
