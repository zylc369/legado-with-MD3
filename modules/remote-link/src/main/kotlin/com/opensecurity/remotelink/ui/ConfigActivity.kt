package com.opensecurity.remotelink.ui

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import com.opensecurity.remotelink.gateway.ConfigStore
import com.opensecurity.remotelink.gateway.RemoteLinkManager

/**
 * RemoteLink 极简配置页（表单: relay 地址/端口/PSK/节点ID/本地端口 + 启用开关）。
 * 保存后即时重启网关; Legado 的 HttpTTS 引擎配
 * http://127.0.0.1:<本地端口>/tts?text={{speakText}}&speed={{speakSpeed}}
 */
class ConfigActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = ConfigStore(this)
        val pad = (resources.displayMetrics.density * 12).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        fun field(label: String, value: String, inputType: Int): EditText {
            root.addView(TextView(this).apply { text = label })
            return EditText(this).apply {
                setText(value)
                this.inputType = inputType
            }.also { root.addView(it) }
        }

        val host = field("中继服务器地址（VPS IP）", store.relayHost,
            android.text.InputType.TYPE_CLASS_TEXT)
        val port = field("中继端口", store.relayPort.toString(),
            android.text.InputType.TYPE_CLASS_NUMBER)
        val psk = field("PSK（base64，relayd genkey 生成）", store.pskBase64,
            android.text.InputType.TYPE_CLASS_TEXT)
        val node = field("节点 ID（MacMini 注册名）", store.nodeId,
            android.text.InputType.TYPE_CLASS_TEXT)
        val local = field("本地网关端口（Legado 引擎 URL 用）", store.localPort.toString(),
            android.text.InputType.TYPE_CLASS_NUMBER)

        val enabled = Switch(this).apply { text = "启用 RemoteLink 网关"; isChecked = store.enabled }
        root.addView(enabled)

        val status = TextView(this).apply {
            text = if (RemoteLinkManager.currentPort > 0) {
                "网关运行中: 127.0.0.1:${RemoteLinkManager.currentPort}"
            } else "网关未运行（配置保存并启用后启动）"
            gravity = Gravity.CENTER
            setPadding(0, pad, 0, 0)
        }
        root.addView(status)

        root.addView(Button(this).apply {
            text = "保存并应用"
            setOnClickListener {
                store.relayHost = host.text.toString().trim()
                store.relayPort = port.text.toString().toIntOrNull() ?: 41000
                store.pskBase64 = psk.text.toString().trim()
                store.nodeId = node.text.toString().trim().ifEmpty { "macmini" }
                store.localPort = local.text.toString().toIntOrNull() ?: 18888
                store.enabled = enabled.isChecked
                RemoteLinkManager.restart(this@ConfigActivity)
                Toast.makeText(this@ConfigActivity, if (RemoteLinkManager.currentPort > 0) {
                    "已启动: 127.0.0.1:${RemoteLinkManager.currentPort}"
                } else {
                    "已保存（未启用或配置不完整）"
                }, Toast.LENGTH_LONG).show()
                status.text = "网关端口: ${RemoteLinkManager.currentPort}"
            }
        })

        setContentView(android.widget.ScrollView(this).apply { addView(root) })
    }
}
