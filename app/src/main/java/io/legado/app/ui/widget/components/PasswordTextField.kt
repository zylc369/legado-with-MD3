package io.legado.app.ui.widget.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import io.legado.app.R
import io.legado.app.ui.theme.LegadoTheme

/**
 * 密码输入框：右侧眼睛图标切换明文/密文。
 *
 * 统一封装，避免各处自己拼 visualTransformation 与图标后行为不一致。
 */
@Composable
fun PasswordTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    backgroundColor: Color = Color.Unspecified,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    AppTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        label = label,
        backgroundColor = backgroundColor,
        visualTransformation = if (visible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation('•')
        },
        keyboardOptions = keyboardOptions,
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    imageVector = if (visible) Icons.Default.VisibilityOff
                    else Icons.Default.Visibility,
                    contentDescription = stringResource(
                        if (visible) R.string.hide_password else R.string.show_password
                    ),
                    tint = LegadoTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}
