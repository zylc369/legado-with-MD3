package io.legado.app.ui.widget.components.privacy

import android.content.Context
import android.content.ContextWrapper
import androidx.fragment.app.FragmentActivity

/** 从 Compose 的 Context 链里找回承载系统生物验证框的 FragmentActivity */
fun Context.findFragmentActivity(): FragmentActivity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is FragmentActivity) return current
        current = current.baseContext
    }
    return null
}
