package com.opensecurity.customtts.model

import com.google.gson.JsonParser

/**
 * 服务端音色目录解析（GET /api/tts/voices → models）。
 *
 * 响应: {"voices":[{id,name,gender,description,is_default,available,...}]}
 * 容错: 缺字段用默认值；单个条目非法跳过；整体非法返回空列表。
 */
object CustomTtsVoiceCatalogParser {

    fun parse(json: String): List<CustomTtsVoiceEntry> {
        val root = runCatching { JsonParser.parseString(json).asJsonObject }.getOrNull()
            ?: return emptyList()
        val arr = runCatching { root.getAsJsonArray("voices") }.getOrNull() ?: return emptyList()
        val out = ArrayList<CustomTtsVoiceEntry>()
        for (el in arr) {
            val entry = runCatching {
                val o = el.asJsonObject
                val id = o.get("id")?.takeIf { !it.isJsonNull }?.asString ?: return@runCatching null
                if (id.isBlank()) return@runCatching null
                CustomTtsVoiceEntry(
                    id = id,
                    name = o.get("name")?.takeIf { !it.isJsonNull }?.asString ?: id,
                    gender = o.get("gender")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
                    description = o.get("description")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
                    isDefault = o.get("is_default")?.takeIf { !it.isJsonNull }?.asBoolean ?: false,
                    available = o.get("available")?.takeIf { !it.isJsonNull }?.asBoolean ?: true,
                )
            }.getOrNull() ?: continue
            out.add(entry)
        }
        return out
    }
}
