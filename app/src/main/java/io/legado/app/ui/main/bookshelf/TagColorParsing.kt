package io.legado.app.ui.main.bookshelf

import io.legado.app.ui.config.themeConfig.TagColorPair
import io.legado.app.utils.GSON
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/** 主题设置里的自定义标签配色 JSON → UI 模型（书架与私密模式共用，避免两处解析漂移） */
internal fun parseTagColors(json: String?): ImmutableList<TagColorPair> = try {
    if (json.isNullOrBlank()) {
        persistentListOf()
    } else {
        GSON.fromJson(json, Array<TagColorPair>::class.java).toImmutableList()
    }
} catch (_: Exception) {
    persistentListOf()
}
