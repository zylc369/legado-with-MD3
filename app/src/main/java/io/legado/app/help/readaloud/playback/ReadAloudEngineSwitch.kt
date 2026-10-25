package io.legado.app.help.readaloud.playback

import androidx.compose.runtime.Stable
import io.legado.app.domain.model.readaloud.ReadAloudEngineSelection
import io.legado.app.domain.model.readaloud.ReadAloudVoice
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/** 快捷切换的作用域：只改本书，或改全局默认。 */
enum class ReadAloudEngineScope { Book, Global }

/** 面板里的引擎分组（标题由 UI 本地化）。 */
enum class ReadAloudEngineGroupType { System, Http, Cloud }

/** 引擎的选中标记来源。 */
enum class ReadAloudEngineTag {
    None,

    /** 本书单独设置过并指向该引擎。 */
    BookCurrent,

    /** 全局默认指向该引擎（本书未单独设置时即生效，或当前作用域为全局）。 */
    GlobalCurrent,
}

data class ReadAloudEngineSource(
    val engineType: String,
    val engineId: String,
    val name: String,
)

@Stable
data class ReadAloudEngineOption(
    val engineType: String,
    val engineId: String,
    val name: String,
    val speakerId: String,
    val voiceName: String,
    val selected: Boolean,
    val tag: ReadAloudEngineTag,
)

@Stable
data class ReadAloudEngineGroup(
    val type: ReadAloudEngineGroupType,
    val options: ImmutableList<ReadAloudEngineOption>,
)

data class ReadAloudEngineVoiceRef(
    val speakerId: String,
    val displayName: String,
)

/**
 * 「朗读引擎」快捷切换的纯逻辑：分组、选中标记与音色沿用。
 *
 * 不持有任何 Android/IO 依赖，便于单测；引擎目录、音色名与当前选择由调用方提供。
 */
object ReadAloudEngineSwitch {

    fun key(engineType: String, engineId: String): String = "$engineType:$engineId"

    fun voiceKey(engineType: String, engineId: String, speakerId: String): String =
        "$engineType:$engineId:$speakerId"

    /**
     * 当前作用域真正生效的选择：本书未单独设置时继承全局默认。
     */
    fun effectiveSelection(
        bookSelection: ReadAloudEngineSelection?,
        globalSelection: ReadAloudEngineSelection?,
        scope: ReadAloudEngineScope,
    ): ReadAloudEngineSelection? = when (scope) {
        ReadAloudEngineScope.Book -> bookSelection ?: globalSelection
        ReadAloudEngineScope.Global -> globalSelection
    }

    fun buildGroups(
        sources: List<ReadAloudEngineSource>,
        bookSelection: ReadAloudEngineSelection?,
        globalSelection: ReadAloudEngineSelection?,
        scope: ReadAloudEngineScope,
        voiceMemory: Map<String, ReadAloudEngineVoiceRef>,
        voiceNames: Map<String, String>,
    ): List<ReadAloudEngineGroup> {
        val effective = effectiveSelection(bookSelection, globalSelection, scope)
        val options = sources.map { source ->
            val matchesBook = bookSelection.matches(source)
            val matchesGlobal = globalSelection.matches(source)
            val selected = effective.matches(source)
            val voice = voiceFor(source, bookSelection, globalSelection, voiceMemory, voiceNames)
            ReadAloudEngineOption(
                engineType = source.engineType,
                engineId = source.engineId,
                name = source.name,
                speakerId = voice.speakerId,
                voiceName = voice.displayName,
                selected = selected,
                tag = when {
                    // 本书单独设置过：无论当前作用域如何都标出来，避免"全局改了这本书怎么没变"
                    matchesBook -> ReadAloudEngineTag.BookCurrent
                    matchesGlobal -> ReadAloudEngineTag.GlobalCurrent
                    else -> ReadAloudEngineTag.None
                },
            )
        }
        return listOf(
            ReadAloudEngineGroupType.System,
            ReadAloudEngineGroupType.Http,
            ReadAloudEngineGroupType.Cloud,
        ).mapNotNull { type ->
            val groupOptions = options.filter { it.engineType == type.engineType() }
            groupOptions.takeIf { it.isNotEmpty() }
                ?.let { ReadAloudEngineGroup(type, it.toImmutableList()) }
        }
    }

    /**
     * 切到某个引擎时沿用的音色：
     * 会话内记忆 > 本书/全局里该引擎的选择 > 引擎默认（speakerId 为空）。
     */
    fun voiceFor(
        source: ReadAloudEngineSource,
        bookSelection: ReadAloudEngineSelection?,
        globalSelection: ReadAloudEngineSelection?,
        voiceMemory: Map<String, ReadAloudEngineVoiceRef>,
        voiceNames: Map<String, String>,
    ): ReadAloudEngineVoiceRef {
        voiceMemory[key(source.engineType, source.engineId)]?.let { return it }
        val known = listOfNotNull(bookSelection, globalSelection).firstOrNull {
            it.engineType == source.engineType && it.engineId == source.engineId
        }
        val speakerId = known?.speakerId.orEmpty()
        val displayName = if (speakerId.isBlank()) {
            ""
        } else {
            voiceNames[voiceKey(source.engineType, source.engineId, speakerId)] ?: speakerId
        }
        return ReadAloudEngineVoiceRef(speakerId, displayName)
    }

    fun buildSelection(
        source: ReadAloudEngineSource,
        voice: ReadAloudEngineVoiceRef,
    ): ReadAloudEngineSelection = ReadAloudEngineSelection(
        engineType = source.engineType,
        engineId = source.engineId,
        speakerId = voice.speakerId,
        // 与「引擎与音色」页保持一致：displayName 存引擎名，音色名另由音色表解析
        displayName = source.name,
    )

    private fun ReadAloudEngineSelection?.matches(source: ReadAloudEngineSource): Boolean =
        this != null && engineType == source.engineType && engineId == source.engineId
}

private fun ReadAloudEngineGroupType.engineType(): String = when (this) {
    ReadAloudEngineGroupType.System -> ReadAloudVoice.ENGINE_SYSTEM
    ReadAloudEngineGroupType.Http -> ReadAloudVoice.ENGINE_HTTP
    ReadAloudEngineGroupType.Cloud -> ReadAloudVoice.ENGINE_CLOUD
}
